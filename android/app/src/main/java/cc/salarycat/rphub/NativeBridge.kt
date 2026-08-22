package cc.salarycat.rphub

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 注入为 window.RPHubNative。
 * 相比旧壳额外暴露了 applyPendingUpdate / contentInfo，
 * 网页侧可以自助排查和触发更新，不再依赖弹窗。
 */
class NativeBridge(
    private val activity: Activity,
    private val onCheckUpdate: () -> Unit
) {

    companion object {
        /** 相册/下载目录下的子目录名，便于用户一眼找到本应用存的图 */
        private const val SAVE_SUBDIR = "RPHub"
        private const val LEGACY_STORAGE_PERMISSION =
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    }

    private val ctx: Context get() = activity.applicationContext

    @JavascriptInterface
    fun shellVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun shellVersionCode(): Int = BuildConfig.VERSION_CODE

    @JavascriptInterface
    fun contentVersion(): Int = ContentManager.effectiveVersion(ctx)

    /** 一次性拿到全部诊断信息，避免网页侧逐个试探。 */
    @JavascriptInterface
    fun contentInfo(): String {
        val json = JSONObject()
        json.put("shellVersion", BuildConfig.VERSION_NAME)
        json.put("shellVersionCode", BuildConfig.VERSION_CODE)
        json.put("bundledVersion", BuildConfig.BUNDLED_CONTENT_VERSION)
        json.put("activeVersion", ContentManager.activeVersion(ctx))
        json.put("stagingVersion", ContentManager.stagingVersion(ctx))
        json.put("effectiveVersion", ContentManager.effectiveVersion(ctx))
        json.put("hasActiveContent", ContentManager.hasActiveContent(ctx))
        json.put("updateBaseUrl", BuildConfig.UPDATE_BASE_URL)
        json.put("source", if (ContentManager.hasActiveContent(ctx)) "active" else "bundled")
        return json.toString()
    }

    @JavascriptInterface
    fun checkUpdate() {
        onCheckUpdate()
    }

    /**
     * 立即把已下载的内容提升并重启页面。网页侧可据此提供一个手动按钮，
     * 覆盖自动提升失败的极端情况。
     */
    @JavascriptInterface
    fun applyPendingUpdate(): Boolean {
        val promoted = ContentManager.promoteIfPending(ctx)
        if (promoted) {
            activity.runOnUiThread {
                toast("内容已更新到 v${ContentManager.activeVersion(ctx)}")
                (activity as? MainActivity)?.reloadContent()
            }
        }
        return promoted
    }

    @JavascriptInterface
    fun getAnnouncement(): String = "{}"

    /**
     * 把网页给的 data URL 落盘。图片进相册 `Pictures/RPHub`，其他进 `Download/RPHub`。
     *
     * 旧实现直接 `FileOutputStream` 写 `getExternalStoragePublicDirectory`，
     * 在 API 29+ 的分区存储下必然 EACCES；这里 API 29+ 改走 MediaStore（免权限），
     * API 24~28 才保留旧的直写路径并按需申请 WRITE_EXTERNAL_STORAGE。
     *
     * 返回 JSON，`{"ok":true,"name":"..."}` 或 `{"ok":false,"error":"..."}`。
     * 旧壳此方法返回 void，网页侧不要依赖返回值判断成败。
     */
    @JavascriptInterface
    fun saveBase64(fileName: String, mime: String, dataUrl: String): String {
        return try {
            val bytes = decodeDataUrl(dataUrl)
            if (bytes.isEmpty()) return result(false, error = "图片内容为空")

            val safeName = sanitize(fileName)
            val resolvedMime = resolveMime(mime, safeName)
            val isImage = resolvedMime.startsWith("image/")

            val savedName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(safeName, resolvedMime, bytes, isImage)
            } else {
                saveViaLegacyFile(safeName, bytes, isImage)
            }

            val where = if (isImage) "相册 $SAVE_SUBDIR" else "下载目录 $SAVE_SUBDIR"
            activity.runOnUiThread { toast("已保存到$where：$savedName") }
            result(true, name = savedName)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            activity.runOnUiThread { toast("保存失败：$message") }
            result(false, error = message)
        }
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        val payload = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        if (payload.isBlank()) return ByteArray(0)
        // 标准 base64 与 URL-safe base64 用的是两张不同的字母表，
        // 不能把两个 flag 或在一起（URL_SAFE 会让 '+' '/' 变成非法字符），
        // 先按标准解，失败再按 URL-safe 解一次。
        return try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            Base64.decode(payload, Base64.URL_SAFE)
        }
    }

    /** API 29+：MediaStore 写入不需要任何存储权限 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        fileName: String,
        mime: String,
        bytes: ByteArray,
        isImage: Boolean
    ): String {
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val baseDir = if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$SAVE_SUBDIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("系统拒绝创建媒体条目")

        try {
            resolver.openOutputStream(uri).use { out ->
                (out ?: throw IOException("无法打开写入流")).write(bytes)
            }
            // 清掉 IS_PENDING，否则文件对相册等其他应用不可见
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // 半成品条目留在媒体库里会变成一个打不开的空文件，回滚掉
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        // MediaStore 会按需自动加 (1) 之类的后缀，回读真实文件名再告诉用户
        return runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: fileName
    }

    /** API 24~28：分区存储之前的直写路径，需要 WRITE_EXTERNAL_STORAGE */
    private fun saveViaLegacyFile(fileName: String, bytes: ByteArray, isImage: Boolean): String {
        if (ContextCompat.checkSelfPermission(ctx, LEGACY_STORAGE_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.runOnUiThread {
                ActivityCompat.requestPermissions(activity, arrayOf(LEGACY_STORAGE_PERMISSION), 1001)
            }
            throw IOException("需要存储权限，请授权后重试")
        }

        val publicDir = Environment.getExternalStoragePublicDirectory(
            if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(publicDir, SAVE_SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录 ${dir.absolutePath}")

        var out = File(dir, fileName)
        var i = 1
        while (out.exists()) {
            val base = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "")
            out = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
            i++
        }

        FileOutputStream(out).use { it.write(bytes) }
        // 不扫描的话图片不会出现在相册里
        runCatching {
            MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
        }
        return out.name
    }

    private fun resolveMime(mime: String, fileName: String): String {
        val trimmed = mime.trim()
        if (trimmed.contains('/')) return trimmed.substringBefore(';').trim()
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // 前导点会变成隐藏文件，路径穿越片段也一并压掉
            .replace(Regex("^\\.+"), "")
            .trim()
        // MediaStore 的 DISPLAY_NAME 过长会插入失败，留出去重后缀的余量
        val limited = if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast('.', "")
            val base = cleaned.substringBeforeLast('.', cleaned).take(110)
            if (ext.isEmpty()) base else "$base.$ext"
        } else {
            cleaned
        }
        return limited.ifEmpty { "download" }
    }

    private fun result(ok: Boolean, name: String? = null, error: String? = null): String {
        val json = JSONObject()
        json.put("ok", ok)
        if (name != null) json.put("name", name)
        if (error != null) json.put("error", error)
        return json.toString()
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
