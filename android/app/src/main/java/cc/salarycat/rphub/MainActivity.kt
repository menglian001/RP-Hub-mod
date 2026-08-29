package cc.salarycat.rphub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

        /** onResume 自动检查更新的最小间隔，避免每次切前台都打一次网络。 */
        private const val AUTO_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /** 渲染进程崩溃后最多自动重建几次；超过就提示用户而不是无限重试。 */
        private const val MAX_RENDERER_CRASH_RECOVERY = 3
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var container: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 上次自动检查更新的时刻，用于 onResume 节流。0 表示还没查过。 */
    private var lastAutoCheckAt = 0L

    /** 静默检查的失败提示每个进程只弹一次，避免网络长期不通时反复打扰。 */
    private var silentErrorShown = false

    /** 渲染进程崩溃后重建的次数。连续崩溃说明重建也救不回来，不再无限重试。 */
    private var rendererCrashCount = 0

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

        // 必须最先装：没有这个，Java 层任何未捕获异常都会让进程静默消失，
        // onRenderProcessGone 不触发，Toast 也来不及显示，什么线索都留不下。
        CrashLog.installGlobalHandler(applicationContext)
        // 存活标记。上次若没走到 markCleanExit，说明是被系统直接杀掉的
        // （LMK 或 native 崩溃）—— 那种死法没有任何回调机会，只能这样反推。
        CrashLog.markStart(applicationContext)

        // 全屏：内容画到系统栏底下并隐藏状态栏/导航栏。
        // 旧版只把两条栏染成 #0F172A，网页是浅色的，看起来就是上下各一条黑边。
        enableFullscreen()

        // 关键：在 WebView 加载之前完成提升，首屏就是新内容。
        // 旧壳把提升放在弹窗回调里，弹窗不出现就永远卡住。
        try {
            // 先丢弃旧于内置内容的 active，否则装了新壳也会继续加载陈旧网页
            if (ContentManager.discardStaleActive(applicationContext)) {
                Log.i(TAG, "启动时已丢弃陈旧的热更新内容")
            }
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

    /**
     * 切回前台时也检查一次更新。
     *
     * 只在 onCreate 里查会漏掉最常见的情形：进程一直活着，用户从后台切回来，
     * Activity 不重建，于是永远不再检查，线上早就发了新内容也拿不到。
     * 用时间间隔节流，避免频繁切前台时反复打网络。
     */
    override fun onResume() {
        super.onResume()

        val now = System.currentTimeMillis()
        if (now - lastAutoCheckAt < AUTO_CHECK_INTERVAL_MS) return
        checkUpdate(silent = true)
    }

    /**
     * 系统内存紧张时的预警。
     *
     * 这是被杀之前唯一的通知机会 —— 等到进程真被 LMK 干掉就什么回调都没有了。
     * 转告网页侧主动释放缓存，比被动撞 OOM 好。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 只认「前台内存紧张」这两级。RUNNING_* 之外的 UI_HIDDEN(20) /
        // BACKGROUND(40) / MODERATE(60) 数值更大但只是切后台，
        // 用 >= 会导致每次切后台都清缓存 —— 白丢命中率。
        val severe = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        Log.w(TAG, "onTrimMemory(level=$level, severe=$severe)")
        CrashLog.recordTrimMemory(applicationContext, level)
        if (!severe) return
        // 网页没定义这个函数也没关系，JS 里做了存在性判断
        runCatching {
            webView.evaluateJavascript(
                "window.RPHubOnMemoryPressure && window.RPHubOnMemoryPressure($level);",
                null
            )
        }.onFailure { Log.w(TAG, "通知网页释放内存失败: ${it.message}") }
    }

    /**
     * 渲染进程崩溃后重建 WebView。
     *
     * 崩掉的 WebView 实例无法复用，必须从容器摘除并 destroy，再建一个新的。
     * 只重跑 configureWebView（它只往新实例上挂设置，幂等）；
     * setupBackHandling 不能重跑 —— 那是 addCallback，再调会叠加第二个返回键
     * 回调。它内部读的是 webView 字段（调用时求值），指向新实例后本来就继续可用。
     */
    private fun recreateWebView() {
        val old = webView
        container.removeView(old)
        // 先断开 JS 桥再销毁，避免残留引用
        try {
            old.removeJavascriptInterface("RPHubNative")
        } catch (e: Exception) {
            Log.w(TAG, "移除 JS 接口失败: ${e.message}")
        }
        old.destroy()

        webView = WebView(this)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView()
        webView.loadUrl(HOME_URL)
    }

    /**
     * 真全屏：边到边布局 + 隐藏系统栏，从边缘上划可临时唤出。
     * 刘海屏还要允许内容延伸进挖孔区，否则横屏时那一侧仍是黑条。
     */
    private fun enableFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 切回前台、关闭输入法或系统弹窗后系统栏会自己冒出来，重新藏起来
        if (hasFocus) enableFullscreen()
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

            // 页面自身是 https://localhost（WebViewAssetLoader 提供），
            // 默认策略会把它发往 http://192.168.x.x 的请求当混合内容拦掉，
            // 于是局域网自建 API 完全连不上。这里放行混合内容。
            // 配套 AndroidManifest 的 usesCleartextTraffic=true —— 两者缺一都不行：
            // 前者是 WebView 层策略，后者是系统层明文 HTTP 开关。
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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

            /**
             * 渲染进程死了（几乎总是 OOM：长会话 + 大量图片把 WebView 的堆撑爆）。
             *
             * 不重写这个方法、或者重写后返回 false，系统就会把宿主进程一起杀掉 ——
             * 用户看到的就是「用着用着突然闪退」，没有任何提示。largeHeap 只是把
             * 天花板抬高，撞上去结果一样。
             *
             * 返回 true 表示「崩溃已由 App 处理」，进程得以存活；这里原地换一个
             * 新的 WebView 重新加载。会话数据在 IndexedDB 里，不受影响。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash() ?: false
                } else {
                    false
                }
                Log.e(TAG, "WebView 渲染进程终止 (didCrash=$crashed)，第 ${rendererCrashCount + 1} 次")

                // 落盘留证。闪退时 Toast 往往来不及显示（甚至整个进程已经没了），
                // 只有写进 SharedPreferences 才能在下次启动时把现场读出来。
                CrashLog.recordRendererGone(
                    applicationContext,
                    didCrash = crashed,
                    attempt = rendererCrashCount + 1
                )

                // 崩掉的那个 WebView 已经不可用了，必须摘掉再销毁，否则新的也起不来
                if (view !== webView) {
                    // 不是当前那个（理论上不会发生），只做清理
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                    return true
                }

                rendererCrashCount++
                if (rendererCrashCount > MAX_RENDERER_CRASH_RECOVERY) {
                    // 连续崩这么多次，重建也救不回来，如实告知而不是无限闪一个空白页
                    Toast.makeText(
                        this@MainActivity,
                        "页面反复崩溃，请重启应用；若持续发生请清理部分聊天记录或图片",
                        Toast.LENGTH_LONG
                    ).show()
                    return true
                }

                Toast.makeText(this@MainActivity, "页面内存不足已重新加载", Toast.LENGTH_SHORT).show()
                recreateWebView()
                return true
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
                // 单页应用没有历史记录，先问网页要不要自己消化这次返回
                // （关弹窗、退出图片管理等），网页说没处理才退出 App。
                askWebToHandleBack { handled -> if (!handled) finish() }
            }
        })
    }

    /**
     * 询问网页侧的 window.RPHubHandleBack()。
     * 网页没定义、抛异常或超时都视为未处理，避免返回键失灵按不动。
     */
    private fun askWebToHandleBack(onResult: (Boolean) -> Unit) {
        var settled = false
        val finish = { handled: Boolean ->
            if (!settled) {
                settled = true
                onResult(handled)
            }
        }
        webView.postDelayed({ finish(false) }, 400)
        try {
            webView.evaluateJavascript(
                "(function(){try{return !!(window.RPHubHandleBack&&window.RPHubHandleBack());}catch(e){return false;}})()"
            ) { value -> finish(value == "true") }
        } catch (e: Exception) {
            Log.w(TAG, "返回键询问网页失败: ${e.message}")
            finish(false)
        }
    }

    /**
     * 后台检查更新。下载完成后不弹窗打断使用，
     * 若当前无内容可用则立即提升，否则下次启动自动生效。
     *
     * [silent] 为真时只在真的有变化或出错时提示；为假（用户主动点检查）时总给回应。
     */
    private fun checkUpdate(silent: Boolean) {
        lastAutoCheckAt = System.currentTimeMillis()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ContentManager.checkAndDownload(applicationContext, autoPromote = true)
            }

            // 失败必须说出来。旧版把失败和"已是最新"混成同一个分支，
            // 静默检查时什么都不显示，网络不通看起来就跟已经最新一样。
            // 但静默检查每进程只提示一次，长期无网时不至于每次切前台都弹。
            result.error?.let { reason ->
                Log.w(TAG, "检查更新失败: $reason")
                if (!silent || !silentErrorShown) {
                    if (silent) silentErrorShown = true
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            silentErrorShown = false

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
        // 走到这里说明是正常退出，清掉存活标记，下次启动就不会误报成被杀。
        // isChangingConfigurations 时进程还活着（只是重建 Activity），不能清。
        if (!isChangingConfigurations) {
            CrashLog.markCleanExit(applicationContext)
        }
        webView.removeJavascriptInterface("RPHubNative")
        super.onDestroy()
    }
}
