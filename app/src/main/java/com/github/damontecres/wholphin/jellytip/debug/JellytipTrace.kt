package com.github.damontecres.wholphin.jellytip.debug

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jellytip debug trace: the last [MAX_EVENTS] events (navigation with its trigger, keys, focus, players, layout shifts,
 * image failures, crashes) in a ring buffer, mirrored to logcat (tag `JellytipTrace`) and written to
 * `files/jellytip_trace.txt` a moment after each burst of events, so it survives a crash or a restart.
 * Settings › Jellytip › "Send debug report" ships it to the companion ([JellytipDebugReport]).
 * Never log tokens: [log] strips `api_key=` values.
 */
object JellytipTrace {
    const val TAG = "JellytipTrace"
    private const val MAX_EVENTS = 1000
    private const val FLUSH_DELAY_MS = 2_000L
    private const val FILE_NAME = "jellytip_trace.txt"
    const val CRASH_MARKER = "jellytip_crash_pending"

    private val events = ArrayDeque<String>(MAX_EVENTS)
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jellytip-trace").apply { isDaemon = true } }
    private val flushQueued = AtomicBoolean(false)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val tokenPattern = Regex("(api_key|ApiKey|Token)=[^&\\s\"]+")

    @Volatile
    private var file: File? = null

    /** Once, from MainActivity.onCreate: loads the previous session's events and installs the crash dump. */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        try {
            if (f.exists()) {
                synchronized(events) { f.readLines().takeLast(MAX_EVENTS).forEach { events.addLast(it) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "trace file unreadable", e)
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log("crash", "${thread.name}: ${error.javaClass.name}: ${error.message} @ ${error.stackTrace.take(8).joinToString(" < ")}")
                flushNow()
                // No network while dying: the next start sends the report (JellytipDebugReport.sendPendingCrash)
                File(context.filesDir, CRASH_MARKER).writeText(System.currentTimeMillis().toString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        log("app", "start ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
    }

    fun log(
        kind: String,
        message: String,
    ) {
        val line = "${time.format(Date())} [$kind] ${message.replace(tokenPattern, "$1=…")}"
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
        Log.i(TAG, line)
        if (flushQueued.compareAndSet(false, true)) {
            writer.schedule({ flushNow() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun snapshot(): List<String> = synchronized(events) { events.toList() }

    fun flushNow() {
        flushQueued.set(false)
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(snapshot().joinToString("\n"))
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "trace not written", e)
        }
    }

    /** Short call site for navigation events: the first app frames outside the trace/navigation plumbing. */
    fun caller(skip: Int = 0): String =
        Throwable()
            .stackTrace
            .asSequence()
            .filter { it.className.startsWith("com.github.damontecres.wholphin") }
            .filterNot { it.className.contains("JellytipTrace") || it.className.contains("NavigationManager") || it.className.contains("JellytipNavGate") }
            .drop(skip)
            .take(5)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    // --- typed helpers -------------------------------------------------------------------------------------------

    fun key(event: KeyEvent) {
        if (event.repeatCount > 0) return
        val action = if (event.action == KeyEvent.ACTION_DOWN) "down" else if (event.action == KeyEvent.ACTION_UP) "up" else "act${event.action}"
        log("key", "${KeyEvent.keyCodeToString(event.keyCode)} $action")
    }

    fun nav(
        what: String,
        from: Any?,
        to: Any?,
        reason: String? = null,
    ) {
        val sinceKey = SystemClock.uptimeMillis() - JellytipNavGate.lastOkAt
        log("nav", "$what ${short(from)} -> ${short(to)} reason=${reason ?: "-"} lastOk=${sinceKey}ms via ${caller()}")
    }

    fun focus(message: String) = log("focus", message)

    fun player(
        owner: String,
        message: String,
    ) = log("player", "$owner $message")

    fun layout(message: String) = log("layout", message)

    fun image(message: String) = log("image", message)

    private fun short(d: Any?): String = d?.toString()?.take(160) ?: "null"
}

/**
 * Nothing but an explicit OK may open details from a browse page (Home, a library page, the watchlist). Ricki saw Home
 * jump to another title's page 5-10 s after hovering, with no key press: whatever the trigger, a navigation from a
 * browse page to a title must follow an OK / Enter / Play key within [OK_WINDOW_MS] (long-press opens on key-up, at most
 * 1.5 s later), or it is blocked and traced with its call site.
 */
object JellytipNavGate {
    private const val OK_WINDOW_MS = 3_000L

    @Volatile
    var lastOkAt = -OK_WINDOW_MS * 10
        private set

    fun isOkKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    /** From MainActivity.dispatchKeyEvent, for every key. */
    fun onKey(event: KeyEvent) {
        if (isOkKey(event.keyCode)) lastOkAt = SystemClock.uptimeMillis()
    }

    /** Pure rule (unit tested): may a page of kind [fromBrowse] open [toTitle] [msSinceOk] after the last OK key? */
    fun allowed(
        fromBrowse: Boolean,
        toTitle: Boolean,
        msSinceOk: Long,
    ): Boolean = !fromBrowse || !toTitle || msSinceOk in 0..OK_WINDOW_MS

    fun allow(
        fromBrowse: Boolean,
        toTitle: Boolean,
    ): Boolean = allowed(fromBrowse, toTitle, SystemClock.uptimeMillis() - lastOkAt)
}

/**
 * "Send debug report" (Settings › Jellytip): POSTs the trace and device/app info to the companion
 * (`POST /v1/debug/report`). If that fails (route not there yet, offline), the report is written to the app's external
 * files folder instead (`/sdcard/Android/data/<package>/files/`, readable with `adb pull`, no root or run-as needed).
 * Returns a short message for a toast.
 */
object JellytipDebugReport {
    /** After a crash, the next start sends the trace once the companion client is up. */
    suspend fun sendPendingCrash(context: Context) {
        val marker = File(context.filesDir, JellytipTrace.CRASH_MARKER)
        if (!marker.exists()) return
        marker.delete()
        JellytipTrace.log("report", "sending report for the crash at ${marker.name}")
        send(context, reason = "crash")
    }

    suspend fun send(
        context: Context,
        reason: String = "user",
    ): String {
        JellytipTrace.log("report", "debug report requested ($reason)")
        JellytipTrace.flushNow()
        val body = build(context, reason)
        val api = com.github.damontecres.wholphin.jellytip.JellytipApi.shared
        val answer = api?.postJson("/v1/debug/report", body.toString())
        if (answer != null && answer.first in 200..299) return "Debug report sent"
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "jellytip-debug-${System.currentTimeMillis()}.json")
            file.writeText(body.toString(2))
            JellytipTrace.log("report", "saved ${file.absolutePath} (server answered ${answer?.first ?: "nothing"})")
            "Saved: ${file.absolutePath}"
        } catch (e: Exception) {
            "Could not send or save the report: ${e.javaClass.simpleName}"
        }
    }

    private fun build(
        context: Context,
        reason: String,
    ): JSONObject {
        val pm = context.packageManager
        val version =
            try {
                pm.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                null
            }
        // Contract (jellytip-specials.md "Debug reports"): {app, version, device, user, createdAt, events:[{t, type, msg}]};
        // the server names the user from the token, so no user name (or token) is sent
        val events = JSONArray()
        JellytipTrace.snapshot().forEach { line ->
            val m = LINE.matchEntire(line)
            events.put(
                if (m == null) {
                    JSONObject().put("msg", line)
                } else {
                    JSONObject().put("t", m.groupValues[1]).put("type", m.groupValues[2]).put("msg", m.groupValues[3])
                },
            )
        }
        return JSONObject()
            .put("app", "jellytip-tv")
            .put("version", version)
            .put("package", context.packageName)
            .put("reason", reason)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ${Build.SUPPORTED_ABIS.firstOrNull()}")
            .put("user", JSONObject.NULL)
            .put("createdAt", java.time.Instant.now().toString())
            .put("events", events)
    }

    private val LINE = Regex("""^(\S+ \S+) \[([^\]]+)\] (.*)$""", RegexOption.DOT_MATCHES_ALL)
}
