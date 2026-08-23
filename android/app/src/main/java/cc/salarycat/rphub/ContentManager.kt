package cc.salarycat.rphub

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 热更新内容管理。
 *
 * 目录布局（应用私有目录）：
 *   files/web/active   —— 当前生效内容，WebView 从这里读
 *   files/web/staging  —— 新下载并校验通过的内容，等待提升
 *
 * 与旧壳的关键差异：提升 staging -> active 由启动时自动完成，
 * 不依赖任何弹窗交互。旧壳一旦弹窗没出现，staging 会永久搁置。
 */
object ContentManager {

    private const val TAG = "ContentManager"
    private const val PREFS = "content_prefs"
    private const val KEY_ACTIVE_VERSION = "active_version"
    private const val KEY_STAGING_VERSION = "staging_version"

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024

    /**
     * 检查结果。
     *
     * [error] 为空表示流程正常走完（无论有没有新内容）；非空表示这次检查失败了，
     * 内容是给用户看的原因。旧版把失败与「已是最新」都表示成 updated=false，
     * 界面上二者不可区分，网络不通时看起来就像"已经最新"，热更新静默失效。
     */
    data class Result(
        val updated: Boolean,
        val version: Int,
        val notes: String?,
        val error: String? = null
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun webRoot(ctx: Context): File = File(ctx.filesDir, "web")
    fun activeDir(ctx: Context): File = File(webRoot(ctx), "active")
    fun stagingDir(ctx: Context): File = File(webRoot(ctx), "staging")

    /** 当前生效内容版本。0 表示还没有任何热更新内容，应回退到 APK 内置资源。 */
    fun activeVersion(ctx: Context): Int = prefs(ctx).getInt(KEY_ACTIVE_VERSION, 0)

    /** 已下载待提升的版本，0 表示没有。 */
    fun stagingVersion(ctx: Context): Int = prefs(ctx).getInt(KEY_STAGING_VERSION, 0)

    /**
     * 对外暴露的有效版本号：热更新内容与 APK 内置内容取较大者。
     */
    fun effectiveVersion(ctx: Context): Int =
        maxOf(activeVersion(ctx), BuildConfig.BUNDLED_CONTENT_VERSION)

    /**
     * active 目录是否有可用内容。
     *
     * 除了文件存在，还必须**不旧于 APK 内置内容**。
     * 少了这个版本比较，热更新过的用户装上带新功能的新壳后，
     * WebView 会继续加载陈旧的 active 内容，新壳里的网页改动永远不可见 ——
     * 2.2.0 就是这么翻车的：热更到 v59 的机器压住了内置 v65。
     */
    fun hasActiveContent(ctx: Context): Boolean =
        activeVersion(ctx) > 0 &&
            activeVersion(ctx) >= BuildConfig.BUNDLED_CONTENT_VERSION &&
            File(activeDir(ctx), "index.html").isFile

    /**
     * 启动时调用：若 active 比 APK 内置内容旧，直接丢弃。
     *
     * 只靠 [hasActiveContent] 判断也能让 WebView 回退到内置内容，
     * 但那份陈旧目录会一直占着空间，且下次 checkAndDownload 比对
     * effectiveVersion 时仍是干扰项，索引清掉更干净。
     */
    fun discardStaleActive(ctx: Context): Boolean {
        val active = activeVersion(ctx)
        if (active <= 0) return false
        if (active >= BuildConfig.BUNDLED_CONTENT_VERSION) return false

        Log.i(TAG, "active v$active 旧于内置 v${BuildConfig.BUNDLED_CONTENT_VERSION}，丢弃并改用内置内容")
        activeDir(ctx).deleteRecursively()
        prefs(ctx).edit().remove(KEY_ACTIVE_VERSION).apply()
        return true
    }

    /**
     * 启动时调用：若 staging 中存在比 active 更新的内容，立即提升。
     * 这一步是同步的，保证 WebView 首次加载就能看到新内容。
     */
    fun promoteIfPending(ctx: Context): Boolean {
        val staged = stagingVersion(ctx)
        if (staged <= 0) return false

        val staging = stagingDir(ctx)
        if (!File(staging, "index.html").isFile) {
            Log.w(TAG, "staging 记录为 v$staged 但内容缺失，清理")
            staging.deleteRecursively()
            prefs(ctx).edit().remove(KEY_STAGING_VERSION).apply()
            return false
        }

        // 与 effectiveVersion 比较，而不是只比 active：
        // 新壳内置内容可能已经比这个待提升包更新，提升上去反而是退版。
        if (staged <= effectiveVersion(ctx)) {
            Log.i(TAG, "staging v$staged 不新于当前 v${effectiveVersion(ctx)}，丢弃")
            staging.deleteRecursively()
            prefs(ctx).edit().remove(KEY_STAGING_VERSION).apply()
            return false
        }

        return promote(ctx, staged)
    }

    /**
     * 原子性地把 staging 提升为 active：
     * 先把旧 active 移到 trash，再把 staging 重命名为 active，最后清理 trash。
     * 任何一步失败都保留原内容。
     */
    private fun promote(ctx: Context, version: Int): Boolean {
        val root = webRoot(ctx)
        val active = activeDir(ctx)
        val staging = stagingDir(ctx)
        val trash = File(root, "trash-${System.currentTimeMillis()}")

        return try {
            if (active.exists() && !active.renameTo(trash)) {
                // 跨设备或权限问题导致 rename 失败，退化为复制
                Log.w(TAG, "active 重命名失败，改用复制方式")
                if (!copyTree(staging, active)) {
                    Log.e(TAG, "更新失败，保留原内容: 复制 staging 失败")
                    return false
                }
                staging.deleteRecursively()
                commitVersion(ctx, version)
                return true
            }

            if (!staging.renameTo(active)) {
                Log.e(TAG, "更新失败，保留原内容: staging 重命名失败")
                trash.renameTo(active)
                return false
            }

            trash.deleteRecursively()
            commitVersion(ctx, version)
            Log.i(TAG, "内容已更新到 v$version")
            true
        } catch (e: Exception) {
            Log.e(TAG, "更新失败，保留原内容: ${e.message}", e)
            if (!active.exists() && trash.exists()) trash.renameTo(active)
            false
        }
    }

    private fun commitVersion(ctx: Context, version: Int) {
        prefs(ctx).edit()
            .putInt(KEY_ACTIVE_VERSION, version)
            .remove(KEY_STAGING_VERSION)
            .apply()
    }

    private fun copyTree(from: File, to: File): Boolean = try {
        if (to.exists()) to.deleteRecursively()
        from.copyRecursively(to, overwrite = true)
    } catch (e: Exception) {
        Log.e(TAG, "复制目录失败: ${e.message}", e)
        false
    }

    /**
     * 检查远端清单，必要时下载并校验，成功后写入 staging。
     * 返回是否有新内容就绪。若 [autoPromote] 为真且当前没有内容在用，直接提升。
     */
    fun checkAndDownload(ctx: Context, autoPromote: Boolean = true): Result {
        val base = BuildConfig.UPDATE_BASE_URL
        val manifest = try {
            val text = httpGetText("${base}version.json?t=${System.currentTimeMillis()}")
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "读取版本清单失败: ${e.message}")
            return Result(false, effectiveVersion(ctx), null, "无法读取版本信息：${describe(e)}")
        }

        val remoteVersion = manifest.optInt("versionCode", 0)
        val notes = manifest.optString("notes", null)
        val minShell = manifest.optInt("minShellVersion", 1)
        val expectedSha = manifest.optString("sha256", "").lowercase()
        val zipName = manifest.optString("zip", "content.zip")

        if (minShell > BuildConfig.SHELL_VERSION) {
            Log.w(TAG, "该内容版本要求更新的客户端，跳过热更新")
            return Result(
                false,
                effectiveVersion(ctx),
                null,
                "远端内容 v$remoteVersion 需要更新版客户端（要求 $minShell，当前 ${BuildConfig.SHELL_VERSION}），请下载新 APK"
            )
        }

        val current = effectiveVersion(ctx)
        if (remoteVersion <= current) {
            Log.i(TAG, "已是最新内容（v$current）")
            return Result(false, current, null)
        }

        Log.i(TAG, "发现新内容 v$remoteVersion，开始下载")

        val tmpZip = File(ctx.cacheDir, "content-$remoteVersion.zip")
        try {
            httpDownload("$base$zipName?t=${System.currentTimeMillis()}", tmpZip)

            if (expectedSha.isNotEmpty()) {
                val actual = sha256(tmpZip)
                if (actual != expectedSha) {
                    Log.e(TAG, "校验失败: 期望 $expectedSha 实际 $actual")
                    return Result(false, current, null, "更新包校验失败，可能下载不完整，稍后重试")
                }
            }

            val staging = stagingDir(ctx)
            staging.deleteRecursively()
            staging.mkdirs()
            unzipSafely(tmpZip, staging)

            if (!File(staging, "index.html").isFile) {
                Log.e(TAG, "更新包缺少 index.html")
                staging.deleteRecursively()
                return Result(false, current, null, "更新包内容异常（缺少 index.html）")
            }

            prefs(ctx).edit().putInt(KEY_STAGING_VERSION, remoteVersion).apply()
            Log.i(TAG, "已下载新内容 v$remoteVersion")

            // 首次安装或当前无可用内容时立刻提升，避免用户看到旧的内置版本
            if (autoPromote && !hasActiveContent(ctx)) {
                promote(ctx, remoteVersion)
            }

            return Result(true, remoteVersion, notes)
        } catch (e: Exception) {
            Log.e(TAG, "更新失败，保留原内容: ${e.message}", e)
            return Result(false, current, null, "下载更新失败：${describe(e)}")
        } finally {
            tmpZip.delete()
        }
    }

    /**
     * 把异常翻译成一句用户能看懂的原因。
     * 直接用 message 常常是空的（例如 UnknownHostException 只带主机名），
     * 那样提示框里会出现"下载更新失败：null"。
     */
    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败，检查网络或代理"
        is java.net.SocketTimeoutException -> "连接超时，检查网络或代理"
        is java.net.ConnectException -> "无法连接服务器，检查网络或代理"
        is javax.net.ssl.SSLException -> "HTTPS 连接被中断，检查网络或代理"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    // ---- 网络 ----

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("User-Agent", "RPHub-Shell/${BuildConfig.VERSION_NAME}")
        return conn
    }

    private fun httpGetText(url: String): String {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(url: String, dest: File) {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---- 校验与解压 ----

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 解压并防御 zip-slip：所有条目必须落在目标目录内。
     */
    private fun unzipSafely(zip: File, destDir: File) {
        val destPath = destDir.canonicalFile
        var total = 0L

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (name.isEmpty() || name.startsWith("/") || name.contains("..")) {
                    throw SecurityException("非法条目路径: $name")
                }

                val target = File(destPath, name).canonicalFile
                if (!target.path.startsWith(destPath.path + File.separator) &&
                    target.path != destPath.path
                ) {
                    throw SecurityException("非法条目路径: $name")
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_ZIP_BYTES) {
                                throw SecurityException("更新包超出大小上限")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    fun openFromActive(ctx: Context, path: String): InputStream? {
        if (!hasActiveContent(ctx)) return null
        val root = activeDir(ctx).canonicalFile
        val target = File(root, path).canonicalFile
        if (!target.path.startsWith(root.path)) return null
        if (!target.isFile) return null
        return target.inputStream()
    }
}
