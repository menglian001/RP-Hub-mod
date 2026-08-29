package cc.salarycat.rphub

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * 崩溃与内存压力留证。
 *
 * 闪退最难办的地方是没有任何线索：Toast 来不及显示，进程被 LMK 杀掉时
 * 连回调都没有。所以把现场写进 SharedPreferences —— 下次启动一定读得到。
 * 网页侧通过 RPHubNative.crashInfo() 取出来，用户可以直接反馈。
 */
object CrashLog {

    private const val PREFS = "rphub_crash_log"
    private const val KEY_LAST_RENDERER_GONE = "last_renderer_gone"
    private const val KEY_RENDERER_GONE_COUNT = "renderer_gone_count"
    private const val KEY_LAST_TRIM = "last_trim"
    private const val KEY_WORST_TRIM = "worst_trim"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordRendererGone(ctx: Context, didCrash: Boolean, attempt: Int) {
        val runtime = Runtime.getRuntime()
        val payload = JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("didCrash", didCrash)
            put("attempt", attempt)
            // 崩的是渲染进程，这里读到的是宿主进程的堆，但仍能反映整体压力
            put("javaHeapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1048576)
            put("javaHeapMaxMb", runtime.maxMemory() / 1048576)
            put("sdk", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("shell", BuildConfig.VERSION_NAME)
            put("content", ContentManager.effectiveVersion(ctx))
        }
        prefs(ctx).edit()
            .putString(KEY_LAST_RENDERER_GONE, payload.toString())
            .putInt(KEY_RENDERER_GONE_COUNT, prefs(ctx).getInt(KEY_RENDERER_GONE_COUNT, 0) + 1)
            .apply()
    }

    fun recordTrimMemory(ctx: Context, level: Int) {
        val worst = prefs(ctx).getInt(KEY_WORST_TRIM, 0)
        prefs(ctx).edit()
            .putString(KEY_LAST_TRIM, JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("level", level)
                put("name", trimName(level))
            }.toString())
            .putInt(KEY_WORST_TRIM, maxOf(worst, level))
            .apply()
    }

    /** 给网页侧的一次性诊断快照。 */
    fun snapshot(ctx: Context): String {
        val p = prefs(ctx)
        val runtime = Runtime.getRuntime()
        return JSONObject().apply {
            put("rendererGoneCount", p.getInt(KEY_RENDERER_GONE_COUNT, 0))
            put("lastRendererGone", p.getString(KEY_LAST_RENDERER_GONE, null) ?: JSONObject.NULL)
            put("lastTrimMemory", p.getString(KEY_LAST_TRIM, null) ?: JSONObject.NULL)
            put("worstTrimMemory", trimName(p.getInt(KEY_WORST_TRIM, 0)))
            put("javaHeapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1048576)
            put("javaHeapMaxMb", runtime.maxMemory() / 1048576)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("sdk", Build.VERSION.SDK_INT)
            put("shell", BuildConfig.VERSION_NAME)
        }.toString()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun trimName(level: Int): String = when (level) {
        0 -> "none"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE(80) 即将被杀"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE(60)"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND(40)"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN(20)"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL(15) 前台内存极度紧张"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW(10)"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE(5)"
        else -> "level=$level"
    }
}
