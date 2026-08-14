package cc.salarycat.rphub

import android.app.Activity
import android.content.Context
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 注入为 window.RPHubNative。
 * 相比旧壳额外暴露了 applyPendingUpdate / contentInfo，
 * 网页侧可以自助排查和触发更新，不再依赖弹窗。
 */
class NativeBridge(
    private val activity: Activity,
    private val onCheckUpdate: () -> Unit
) {

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

    @JavascriptInterface
    fun saveBase64(fileName: String, mime: String, dataUrl: String) {
        try {
            val comma = dataUrl.indexOf(',')
            val payload = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
            val bytes = Base64.decode(payload, Base64.DEFAULT)

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "RPHub"
            )
            if (!dir.exists()) dir.mkdirs()

            val safe = sanitize(fileName)
            var out = File(dir, safe)
            var i = 1
            while (out.exists()) {
                val base = safe.substringBeforeLast('.', safe)
                val ext = safe.substringAfterLast('.', "")
                out = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
                i++
            }

            FileOutputStream(out).use { it.write(bytes) }
            activity.runOnUiThread { toast("已保存到下载目录：${out.name}") }
        } catch (e: Exception) {
            activity.runOnUiThread { toast("保存失败：${e.message}") }
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "download" }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
