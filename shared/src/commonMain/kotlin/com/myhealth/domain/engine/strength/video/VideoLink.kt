package com.myhealth.domain.engine.strength.video

/**
 * Video links (P26.4): the first http(s) link in shared or pasted text, and YouTube's many forms
 * (`youtu.be/ID`, `watch?v=ID`, `shorts/ID`, `m.` / `music.` hosts, `embed/ID`, `live/ID`) brought
 * to one `https://www.youtube.com/watch?v=ID`, keeping a start time (`t=`). Links to other sites
 * are kept as they are.
 */
object VideoLink {

    /** What a share sheet hands over: a link plus, when the sending app gives one, a title. */
    data class Shared(val url: String?, val title: String?)

    fun firstUrl(text: String?): String? =
        text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', '"', '\'') }

    fun isYouTube(url: String): Boolean = youTubeId(url) != null

    fun youTubeId(url: String): String? {
        val m = YOUTUBE.find(url.trim()) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.takeIf { ID.matches(it) }
    }

    fun normalise(url: String): String {
        val trimmed = url.trim()
        val id = youTubeId(trimmed) ?: return trimmed
        val start = START.find(trimmed)?.groupValues?.get(1)
        return "https://www.youtube.com/watch?v=$id" + (start?.let { "&t=$it" } ?: "")
    }

    /**
     * A share: the link out of [text] (normalised), and as title [subject] if the app sent one,
     * else the text without the link when something is left.
     */
    fun fromShare(text: String?, subject: String?): Shared {
        val url = firstUrl(text) ?: firstUrl(subject)
        val rest = text?.let { t -> url?.let { t.replace(URL, " ") } ?: t }
            ?.replace(SPACES, " ")?.trim()?.trim('-', '|', ':', ' ')
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && firstUrl(it) == null }
            ?: rest?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }
        return Shared(url?.let(::normalise), title)
    }

    /** Is [url] something the Watch button can open? */
    fun isWebLink(url: String?): Boolean = url != null && WEB.matches(url.trim())

    private const val MAX_TITLE = 200
    private val SPACES = Regex("\\s+")
    private val URL = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val WEB = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE = Regex(
        "^https?://(?:(?:www|m|music)\\.)?(?:" +
            "youtube\\.com/(?:watch\\?(?:[^#\\s]*&)?v=([A-Za-z0-9_-]{11})|shorts/([A-Za-z0-9_-]{11})|embed/([A-Za-z0-9_-]{11})|live/([A-Za-z0-9_-]{11}))" +
            "|youtu\\.be/([A-Za-z0-9_-]{11}))",
        RegexOption.IGNORE_CASE,
    )
    private val START = Regex("[?&#]t=(\\d+[hms\\d]*)")
}
