package com.github.damontecres.wholphin.jellytip.iptv

import android.content.Context
import org.json.JSONObject

/**
 * Live TV buffer modes (web 76e062ea, lib/liveBuffer.ts): Auto / Low / Normal / High / Max = how far behind live to sit
 * so a network hiccup doesn't freeze the picture. ExoPlayer mapping: each profile is a LoadControl (start cushion,
 * cushion after a rebuffer, buffer sizes) plus the live target offset for HLS. A LoadControl is fixed when the player
 * is built, so a different profile takes effect at the next channel opened (the player is rebuilt then); the web
 * applies some of it at once. Auto starts at Normal, steps up one level after a stall (to High at most, Max is manual),
 * remembers the level per channel and steps back down after 10 quiet minutes. Low = the buffers the TV used before.
 * Films, episodes, recordings and catch-up always use Low (unchanged).
 */
object LiveBuffer {
    enum class Profile(
        val label: String,
        /** Rough distance behind live (UI + HLS target offset). */
        val behindMs: Long,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        /** Cushion before the first frame: Low/Normal keep zaps instant. */
        val startMs: Int,
        /** Cushion rebuilt after a rebuffer. */
        val afterRebufferMs: Int,
    ) {
        LOW("Low", 3_000, 2_000, 20_000, 700, 1_500),
        NORMAL("Normal", 8_000, 6_000, 30_000, 700, 6_000),
        HIGH("High", 15_000, 11_000, 45_000, 6_000, 11_000),
        MAX("Max", 30_000, 22_000, 60_000, 6_000, 22_000),
    }

    const val AUTO = "auto"
    val MODES = listOf(AUTO, "low", "normal", "high", "max")
    val AUTO_MIN = Profile.NORMAL
    val AUTO_MAX = Profile.HIGH
    const val STEP_DOWN_MS = 10 * 60_000L
    const val DEBOUNCE_MS = 3_000L
    const val GRACE_MS = 20_000L

    fun profileOf(mode: String): Profile? = Profile.entries.firstOrNull { it.name.equals(mode, true) }

    fun modeLabel(mode: String): String = if (mode == AUTO) "Auto" else profileOf(mode)?.let { "${it.label} (~${it.behindMs / 1000} s)" } ?: "Auto"

    fun nextMode(mode: String): String = MODES[(MODES.indexOf(mode).coerceAtLeast(0) + 1) % MODES.size]

    /** Auto level for one channel. */
    data class AutoState(
        val level: Profile,
        val lastStall: Long = 0,
        val lastChange: Long = 0,
        val startedAt: Long = 0,
    )

    private fun clampAuto(p: Profile): Profile = if (p.ordinal < AUTO_MIN.ordinal) AUTO_MIN else if (p.ordinal > AUTO_MAX.ordinal) AUTO_MAX else p

    fun initAuto(
        remembered: Profile?,
        now: Long,
    ): AutoState = AutoState(clampAuto(remembered ?: AUTO_MIN), startedAt = now)

    /** A stall: one step up (debounced; not while the last step's cushion is still being built). Second = stepped. */
    fun onStall(
        s: AutoState,
        now: Long,
    ): Pair<AutoState, Boolean> {
        if (s.lastStall > 0 && now - s.lastStall < DEBOUNCE_MS) return s to false
        val st = s.copy(lastStall = now)
        if (s.lastChange > 0 && now - s.lastChange < GRACE_MS) return st to false
        val next = Profile.entries[minOf(s.level.ordinal + 1, AUTO_MAX.ordinal)]
        if (next == s.level) return st to false
        return st.copy(level = next, lastChange = now) to true
    }

    /** Quiet for [STEP_DOWN_MS]: one step down, never below Normal. */
    fun onTick(
        s: AutoState,
        now: Long,
    ): AutoState {
        if (s.level.ordinal <= AUTO_MIN.ordinal) return s
        if (now - maxOf(s.lastStall, s.lastChange, s.startedAt) < STEP_DOWN_MS) return s
        return s.copy(level = Profile.entries[s.level.ordinal - 1], lastChange = now)
    }

    // ---- device store: the default mode + Auto's level per channel ----

    private const val PREFS = "jellytip_live_buffer"
    private const val KEY_MODE = "mode"

    fun loadMode(context: Context): String = prefs(context).getString(KEY_MODE, AUTO)?.takeIf { it in MODES } ?: AUTO

    fun saveMode(
        context: Context,
        mode: String,
    ) = prefs(context).edit().putString(KEY_MODE, mode).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadAuto(
        context: Context,
        channelId: String,
        now: Long,
    ): AutoState {
        val raw = prefs(context).getString("ch:$channelId", null) ?: return initAuto(null, now)
        return runCatching {
            val o = JSONObject(raw)
            AutoState(
                level = clampAuto(profileOf(o.optString("level")) ?: AUTO_MIN),
                lastStall = o.optLong("lastStall"),
                lastChange = o.optLong("lastChange"),
                startedAt = o.optLong("startedAt", now),
            )
        }.getOrElse { initAuto(null, now) }
    }

    private fun saveAuto(
        context: Context,
        channelId: String,
        s: AutoState,
    ) = prefs(context)
        .edit()
        .putString(
            "ch:$channelId",
            JSONObject().put("level", s.level.name).put("lastStall", s.lastStall).put("lastChange", s.lastChange).put("startedAt", s.startedAt).toString(),
        ).apply()

    /** The profile to build the player with for [target] (live channels only; everything else is Low). */
    fun profileFor(
        context: Context,
        target: IptvTarget,
        now: Long = System.currentTimeMillis(),
    ): Profile {
        if (!target.isLive) return Profile.LOW
        val mode = loadMode(context)
        if (mode != AUTO) return profileOf(mode) ?: AUTO_MIN
        val s = onTick(loadAuto(context, target.channelId, now), now)
        saveAuto(context, target.channelId, s)
        return s.level
    }

    /** A rebuffer on a live channel in Auto: returns the new level when it stepped up (it applies from the next open). */
    fun stalled(
        context: Context,
        target: IptvTarget,
        now: Long = System.currentTimeMillis(),
    ): Profile? {
        if (!target.isLive || loadMode(context) != AUTO) return null
        val (s, stepped) = onStall(loadAuto(context, target.channelId, now), now)
        saveAuto(context, target.channelId, s)
        return if (stepped) s.level else null
    }
}
