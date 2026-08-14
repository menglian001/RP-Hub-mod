package cc.salarycat.rphub

import android.webkit.CookieManager
import android.webkit.WebView

object CookieCompat {
    fun enable(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, false)
    }
}
