package cc.salarycat.rphub

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 内容读取顺序：files/web/active -> APK assets/web。
 *
 * 与旧壳的关键差异：任何路径都不会返回 null。旧壳返回 null 时
 * WebView 会转去请求真实网络，在 https://localhost 上必然失败，
 * 用户看到的就是 ERR_CONNECTION_REFUSED，掩盖了真实原因。
 */
class WebContentPathHandler(
    private val ctx: Context
) : WebViewAssetLoader.PathHandler {

    companion object {
        private const val TAG = "WebContentPath"
        private const val ASSET_PREFIX = "web/"
    }

    override fun handle(path: String): WebResourceResponse? {
        val clean = path.trimStart('/').ifEmpty { "index.html" }
        val resolved = if (clean.endsWith("/")) clean + "index.html" else clean

        openFromActive(resolved)?.let { return ok(resolved, it) }
        openFromAssets(resolved)?.let { return ok(resolved, it) }

        // 目录式访问的兜底：/novel -> novel/index.html
        if (!resolved.contains('.')) {
            val indexPath = "$resolved/index.html"
            openFromActive(indexPath)?.let { return ok(indexPath, it) }
            openFromAssets(indexPath)?.let { return ok(indexPath, it) }
        }

        Log.w(TAG, "资源不存在: $resolved")
        return notFound(resolved)
    }

    private fun openFromActive(path: String): InputStream? = try {
        ContentManager.openFromActive(ctx, path)
    } catch (e: Exception) {
        Log.w(TAG, "读取热更新文件失败 $path: ${e.message}")
        null
    }

    private fun openFromAssets(path: String): InputStream? = try {
        ctx.assets.open(ASSET_PREFIX + path)
    } catch (e: IOException) {
        null
    }

    private fun ok(path: String, stream: InputStream): WebResourceResponse {
        val mime = guessMime(path)
        val encoding = if (mime.startsWith("text/") ||
            mime == "application/javascript" ||
            mime == "application/json"
        ) "utf-8" else null

        return WebResourceResponse(mime, encoding, 200, "OK", defaultHeaders(), stream)
    }

    /**
     * 明确回一个 404，而不是 null。这样 WebView 不会转去访问真实网络，
     * 页面里的 fetch 也能拿到确定的状态码，便于定位问题。
     */
    private fun notFound(path: String): WebResourceResponse {
        val body = "404 Not Found: $path".toByteArray()
        return WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found",
            defaultHeaders(), ByteArrayInputStream(body)
        )
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        "Access-Control-Allow-Origin" to "*"
    )

    private fun guessMime(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
            name.endsWith(".js") || name.endsWith(".mjs") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".ico") -> "image/x-icon"
            name.endsWith(".woff2") -> "font/woff2"
            name.endsWith(".woff") -> "font/woff"
            name.endsWith(".ttf") -> "font/ttf"
            name.endsWith(".otf") -> "font/otf"
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".wasm") -> "application/wasm"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
