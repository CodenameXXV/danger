package ua.nebosumy

import android.content.Context
import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val id: Int, val text: String, val audio: String?)

object Store {
    private val nameRe = Regex("^(?:https?://)?(?:(?:www\\.)?(?:t|telegram)\\.me/(?:s/)?)?@?([A-Za-z][A-Za-z0-9_]{3,31})(?:[/?#].*)?$")

    fun channels(c: Context): MutableList<Pair<String, Boolean>> {
        val raw = c.getSharedPreferences("s", Context.MODE_PRIVATE)
            .getString("channels", "NeboSumyOperatyvno:1") ?: ""
        return raw.split("\n")
            .filter { it.contains(":") }
            .map { it.substringBeforeLast(":") to (it.substringAfterLast(":") == "1") }
            .toMutableList()
    }

    fun save(c: Context, list: List<Pair<String, Boolean>>) {
        val raw = list.joinToString("\n") { it.first + ":" + (if (it.second) "1" else "0") }
        c.getSharedPreferences("s", Context.MODE_PRIVATE).edit().putString("channels", raw).apply()
    }

    fun parseName(input: String): String? = nameRe.find(input.trim())?.groupValues?.get(1)
}

object Telegram {
    private val postRe = Regex("data-post=\"[^\"/]+/(\\d+)\"")
    private val audioRe = Regex("<audio[^>]*\\ssrc=\"([^\"]+)\"")
    private val textRe = Regex("js-message_text[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val titleRe = Regex("property=\"og:title\"\\s+content=\"([^\"]*)\"")

    fun download(channel: String): String {
        val c = URL("https://t.me/s/$channel").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    fun fetch(channel: String): List<Msg> = parse(download(channel))

    fun parse(html: String): List<Msg> {
        val marks = postRe.findAll(html).toList()
        val out = ArrayList<Msg>()
        for (i in marks.indices) {
            val start = marks[i].range.first
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else html.length
            val chunk = html.substring(start, end)
            val audio = audioRe.find(chunk)?.groupValues?.get(1)?.replace("&amp;", "&")
            val raw = textRe.find(chunk)?.groupValues?.get(1) ?: ""
            val text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            out.add(Msg(marks[i].groupValues[1].toInt(), text, audio))
        }
        return out.distinctBy { it.id }
    }

    fun test(channel: String): String {
        val html = download(channel)
        val msgs = parse(html).sortedBy { it.id }
        if (msgs.isEmpty()) {
            return "Повідомлень не знайдено. Канал не існує, приватний або для нього вимкнено веб-перегляд."
        }
        val title = titleRe.find(html)?.groupValues?.get(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() } ?: channel
        val voices = msgs.count { it.audio != null }
        val last = msgs.last()
        return "✅ Працює\n$title\n\nПовідомлень на сторінці: ${msgs.size}\nГолосових: $voices\n\nОстаннє #${last.id}: " +
            (if (last.audio != null) "🔊 голосове " else "") + last.text.take(200)
    }
}
