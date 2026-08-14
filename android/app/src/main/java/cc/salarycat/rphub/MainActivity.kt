package cc.salarycat.rphub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HOST = "localhost"
        private const val HOME_URL = "https://$HOST/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var container: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        if (result.resultCode != Activity.RESULT_OK) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data
        val uris = when {
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            else -> null
        }
        cb.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 关键：在 WebView 加载之前完成提升，首屏就是新内容。
        // 旧壳把提升放在弹窗回调里，弹窗不出现就永远卡住。
        try {
            if (ContentManager.promoteIfPending(applicationContext)) {
                Log.i(TAG, "启动时已提升待用内容")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动提升失败: ${e.message}", e)
        }

        container = FrameLayout(this)
        webView = WebView(this)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(container)

        configureWebView()
        setupBackHandling()

        webView.loadUrl(HOME_URL)

        checkUpdate(silent = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .setHttpAllowed(false)
            .addPathHandler("/", WebContentPathHandler(applicationContext))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieCompat.enable(webView)

        webView.addJavascriptInterface(
            NativeBridge(this) { checkUpdate(silent = false) },
            "RPHubNative"
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                // 站内导航交给 WebView，外链走系统浏览器
                if (uri.host == HOST) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开链接", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg ?: return false
                Log.d(TAG, "[web] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                view?.let {
                    container.addView(
                        it,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { container.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView.visibility = View.VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(params?.createIntent() ?: return false)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                finish()
            }
        })
    }

    /**
     * 后台检查更新。下载完成后不弹窗打断使用，
     * 若当前无内容可用则立即提升，否则下次启动自动生效。
     */
    private fun checkUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ContentManager.checkAndDownload(applicationContext, autoPromote = true)
            }

            if (!result.updated) {
                if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        "已是最新内容（v${result.version}）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            // 首次安装场景已在 checkAndDownload 中提升，此处直接刷新
            if (ContentManager.activeVersion(applicationContext) == result.version) {
                Toast.makeText(
                    this@MainActivity,
                    "内容已更新到 v${result.version}",
                    Toast.LENGTH_SHORT
                ).show()
                reloadContent()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "已下载新内容 v${result.version}，下次启动生效",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun reloadContent() {
        webView.clearCache(true)
        webView.loadUrl(HOME_URL)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("RPHubNative")
        super.onDestroy()
    }
}
