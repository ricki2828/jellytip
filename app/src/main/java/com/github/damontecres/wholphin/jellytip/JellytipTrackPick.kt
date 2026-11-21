package com.github.damontecres.wholphin.jellytip

/*
 * Default audio / subtitle tracks from the person's language prefs (web 60561c2a 2001c74a, lib/tracks.ts): ordered audio
 * languages, commentary / audio-description tracks skipped, forced subtitles when the audio is in a preferred language,
 * full subtitles when it isn't. Used only when the person has made no choice for the title or its series (Wholphin's
 * own memory wins). Pure; tested in TrackPickTest.
 */
object JellytipTrackPick {
    data class Stream(
        val index: Int,
        /** "Audio" | "Subtitle" */
        val type: String,
        val language: String?,
        val title: String?,
        val isDefault: Boolean,
        val isForced: Boolean,
        val isText: Boolean,
    )

    private val ALIASES =
        mapOf(
            "en" to listOf("eng"),
            "mi" to listOf("mao", "mri"),
            "fr" to listOf("fre", "fra"),
            "es" to listOf("spa"),
            "de" to listOf("ger", "deu"),
            "it" to listOf("ita"),
            "pt" to listOf("por"),
            "nl" to listOf("dut", "nld"),
            "sv" to listOf("swe"),
            "ja" to listOf("jpn"),
            "ko" to listOf("kor"),
            "zh" to listOf("chi", "zho", "cmn", "yue"),
            "hi" to listOf("hin"),
        )

    private fun base(tag: String?) = tag.orEmpty().lowercase().split('-', '_').first()

    fun langIs(
        tag: String?,
        code: String,
    ): Boolean {
        val t = base(tag)
        if (t.isEmpty()) return false
        return t == code || ALIASES[code].orEmpty().contains(t)
    }

    /** "" for unknown ("und", missing). */
    fun canonLang(tag: String?): String {
        val t = base(tag)
        if (t.isEmpty() || t == "und" || t == "unk" || t == "zxx") return ""
        return ALIASES.entries.firstOrNull { (code, list) -> t == code || t in list }?.key ?: t
    }

    private val COMMENTARY =
        Regex("""commentar|director'?s? ?(cut )?track|audio[ -]?desc|described|descriptive|visually impaired|isolated|karaoke|\bfx only\b|behind the scenes""", RegexOption.IGNORE_CASE)
    private val AD = Regex("""\bAD\b""")

    fun isCommentary(s: Stream): Boolean {
        val t = s.title.orEmpty()
        return COMMENTARY.containsMatchIn(t) || AD.containsMatchIn(t)
    }

    private fun best(m: List<Stream>): Stream? {
        val main = m.filterNot(::isCommentary)
        return main.firstOrNull { it.isDefault } ?: main.firstOrNull()
    }

    /** The first pref language the file has (never commentary); else [fallback], unless that is commentary and a plain one exists. */
    fun pickAudio(
        streams: List<Stream>,
        langs: List<String>,
        fallback: Int?,
    ): Int? {
        val audio = streams.filter { it.type == "Audio" }
        for (code in langs) best(audio.filter { langIs(it.language, code) })?.let { return it.index }
        val fb = audio.firstOrNull { it.index == fallback }
        if (fb != null && isCommentary(fb)) best(audio)?.let { return it.index }
        return fallback
    }

    /**
     * Subtitles (-1 = off): audio in a preferred language = forced subtitles in a preferred language, else [fallback];
     * audio in another known language = the first full text subtitle in a subtitle (else audio) pref language; audio
     * language unknown = never assumed foreign.
     */
    fun pickSubtitle(
        streams: List<Stream>,
        audioIndex: Int?,
        audioLangs: List<String>,
        subtitleLangs: List<String>,
        fallback: Int,
    ): Int {
        val audio = streams.firstOrNull { it.type == "Audio" && it.index == audioIndex }
        val known = audio != null && canonLang(audio.language).isNotEmpty()
        val matches = known && audioLangs.any { langIs(audio!!.language, it) }
        val foreign = known && !matches && audioLangs.isNotEmpty()
        val subs = streams.filter { it.type == "Subtitle" && it.isText && !isCommentary(it) }
        val subLangs = subtitleLangs.ifEmpty { audioLangs }
        if (foreign) {
            for (code in subLangs) {
                val m = subs.filter { langIs(it.language, code) }
                val pick = m.firstOrNull { !it.isForced && it.isDefault } ?: m.firstOrNull { !it.isForced } ?: m.firstOrNull()
                if (pick != null) return pick.index
            }
            return fallback
        }
        if (matches) {
            for (code in subLangs) subs.firstOrNull { it.isForced && langIs(it.language, code) }?.let { return it.index }
        }
        return fallback
    }

    fun streams(source: org.jellyfin.sdk.model.api.MediaSourceInfo): List<Stream> =
        source.mediaStreams.orEmpty().mapNotNull { s ->
            val type =
                when (s.type) {
                    org.jellyfin.sdk.model.api.MediaStreamType.AUDIO -> "Audio"
                    org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE -> "Subtitle"
                    else -> return@mapNotNull null
                }
            Stream(s.index, type, s.language, listOfNotNull(s.title, s.displayTitle).joinToString(" "), s.isDefault, s.isForced, s.isTextSubtitleStream == true)
        }
}
