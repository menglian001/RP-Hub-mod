package cc.salarycat.rphub

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃与内存压力留证。
 *
 * 闪退最难办的地方是没有任何线索：Toast 来不及显示，进程被 LMK 杀掉时
 * 连回调都没有。所以把现场写进 SharedPreferences —— 下次启动一定读得到。
 *
 * 覆盖三类死法：
 *  1. Java/Kotlin 层未捕获异常 —— installGlobalHandler，记完整堆栈
 *  2. WebView 渲染进程崩溃 —— recordRendererGone
 *  3. 被系统 LMK 直接杀掉 —— 没有任何回调，只能靠 recordTrimMemory 的
 *     最后一次内存警告 + 「上次没有正常退出」这个事实来反推
 */
object CrashLog {

    private const val PREFS = "rphub_crash_log"
    private const val KEY_LAST_FATAL = "last_fatal"
    private const val KEY_FATAL_COUNT = "fatal_count"
    private const val KEY_LAST_RENDERER_GONE = "last_renderer_gone"
    private const val KEY_RENDERER_GONE_COUNT = "renderer_gone_count"
    private const val KEY_LAST_TRIM = "last_trim"
    private const val KEY_WORST_TRIM = "worst_trim"

    /** 进程存活标记。启动置 true，正常退出置 false；启动时若仍为 true 说明上次是被杀的。 */
    private const val KEY_ALIVE = "alive"
    private const val KEY_LAST_START = "last_start"
    private const val KEY_DIRTY_EXIT_COUNT = "dirty_exit_count"
    private const val KEY_LAST_DIRTY_EXIT = "last_dirty_exit"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 全局兜底 handler。没有这个，Java 层任何未捕获异常都会让进程静默消失，
     * onRenderProcessGone 不会触发，什么记录都留不下。
     */
    fun installGlobalHandler(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordFatal(ctx, thread.name, error) }
            // 交回系统默认行为，保持原有的崩溃对话框与上报
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        val text = writer.toString()
        // SharedPreferences 不适合塞太长的文本，截断到够定位为止
        return if (text.length > 6000) text.take(6000) + "\n…(已截断)" else text
    }

    fun recordFatal(ctx: Context, threadName: String, error: Throwable) {
        val payload = baseInfo(ctx).apply {
            put("thread", threadName)
            put("type", error.javaClass.name)
            put("message", error.message ?: "")
            put("stack", stackTraceOf(error))
        }
        prefs(ctx).edit()
            .putString(KEY_LAST_FATAL, payload.toString())
            .putInt(KEY_FATAL_COUNT, prefs(ctx).getInt(KEY_FATAL_COUNT, 0) + 1)
            .apply()
    }

    fun recordRendererGone(ctx: Context, didCrash: Boolean, attempt: Int) {
        val payload = baseInfo(ctx).apply {
            put("didCrash", didCrash)
            put("attempt", attempt)
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
                put("javaHeapUsedMb", usedHeapMb())
            }.toString())
            .putInt(KEY_WORST_TRIM, maxOf(worst, level))
            .apply()
    }

    /**
     * 启动时调用。如果上次的存活标记还在，说明上次进程没走正常退出流程 ——
     * 多半是被系统杀掉（LMK / native crash），那种情况下没有任何回调机会。
     */
    fun markStart(ctx: Context) {
        val p = prefs(ctx)
        val wasAlive = p.getBoolean(KEY_ALIVE, false)
        val editor = p.edit()
            .putBoolean(KEY_ALIVE, true)
            .putLong(KEY_LAST_START, System.currentTimeMillis())
        if (wasAlive) {
            editor
                .putInt(KEY_DIRTY_EXIT_COUNT, p.getInt(KEY_DIRTY_EXIT_COUNT, 0) + 1)
                .putString(KEY_LAST_DIRTY_EXIT, JSONObject().apply {
                    put("detectedAt", System.currentTimeMillis())
                    put("previousStart", p.getLong(KEY_LAST_START, 0L))
                    put("lastTrim", p.getString(KEY_LAST_TRIM, null) ?: JSONObject.NULL)
                    put("note", "上次进程没有正常退出，且没有 Java 异常与渲染进程崩溃记录，" +
                        "多半是被系统直接杀掉（内存不足或 native 崩溃）")
                }.toString())
        }
        editor.apply()
    }

    /** 正常退出（onDestroy 且非配置变更）时清掉存活标记。 */
    fun markCleanExit(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ALIVE, false).apply()
    }

    /** 给网页侧的一次性诊断快照。 */
    fun snapshot(ctx: Context): String {
        val p = prefs(ctx)
        return JSONObject().apply {
            put("fatalCount", p.getInt(KEY_FATAL_COUNT, 0))
            put("lastFatal", p.getString(KEY_LAST_FATAL, null) ?: JSONObject.NULL)
            put("rendererGoneCount", p.getInt(KEY_RENDERER_GONE_COUNT, 0))
            put("lastRendererGone", p.getString(KEY_LAST_RENDERER_GONE, null) ?: JSONObject.NULL)
            put("dirtyExitCount", p.getInt(KEY_DIRTY_EXIT_COUNT, 0))
            put("lastDirtyExit", p.getString(KEY_LAST_DIRTY_EXIT, null) ?: JSONObject.NULL)
            put("lastTrimMemory", p.getString(KEY_LAST_TRIM, null) ?: JSONObject.NULL)
            put("worstTrimMemory", trimName(p.getInt(KEY_WORST_TRIM, 0)))
            put("javaHeapUsedMb", usedHeapMb())
            put("javaHeapMaxMb", Runtime.getRuntime().maxMemory() / 1048576)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("sdk", Build.VERSION.SDK_INT)
            put("shell", BuildConfig.VERSION_NAME)
            put("content", ContentManager.effectiveVersion(ctx))
        }.toString()
    }

    /** 是否有任何值得展示给用户的记录。 */
    fun hasReport(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getInt(KEY_FATAL_COUNT, 0) > 0
            || p.getInt(KEY_RENDERER_GONE_COUNT, 0) > 0
            || p.getInt(KEY_DIRTY_EXIT_COUNT, 0) > 0
    }

    fun clear(ctx: Context) {
        // 保留存活标记，否则清理动作本身会被当成一次异常退出
        val alive = prefs(ctx).getBoolean(KEY_ALIVE, true)
        prefs(ctx).edit().clear().putBoolean(KEY_ALIVE, alive).apply()
    }

    private fun usedHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1048576
    }

    private fun baseInfo(ctx: Context) = JSONObject().apply {
        put("at", System.currentTimeMillis())
        put("javaHeapUsedMb", usedHeapMb())
        put("javaHeapMaxMb", Runtime.getRuntime().maxMemory() / 1048576)
        put("sdk", Build.VERSION.SDK_INT)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("shell", BuildConfig.VERSION_NAME)
        put("content", runCatching { ContentManager.effectiveVersion(ctx) }.getOrDefault(-1))
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
