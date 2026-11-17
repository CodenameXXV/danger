package ua.nebosumy

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object Voice {
    const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
    const val DEFAULT_VOICE = "Kore"
    const val ENGINE_SYSTEM = "system"
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_SHERPA = Sherpa.ENGINE
    private val rateRe = Regex("rate=(\\d+)")

    fun engine(c: Context): String =
        when (val e = c.getSharedPreferences("s", Context.MODE_PRIVATE).getString("engine", ENGINE_GEMINI)) {
            ENGINE_SYSTEM, ENGINE_GOOGLE, ENGINE_SHERPA -> e
            else -> ENGINE_GEMINI
        }

    /** Вбудований синтезатор мовлення телефону -> WAV-файл. */
    fun system(c: Context, text: String, out: File) {
        val status = AtomicInteger(TextToSpeech.ERROR)
        val ready = CountDownLatch(1)
        val tts = TextToSpeech(c.applicationContext) {
            status.set(it)
            ready.countDown()
        }
        try {
            if (!ready.await(8, TimeUnit.SECONDS) || status.get() != TextToSpeech.SUCCESS) {
                throw RuntimeException("вбудований синтезатор недоступний")
            }
            val r = tts.setLanguage(Locale("uk", "UA"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw RuntimeException("у телефоні немає української голосової моделі")
            }
            val done = CountDownLatch(1)
            val failed = AtomicBoolean(false)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    failed.set(true)
                    done.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed.set(true)
                    done.countDown()
                }
            })
            val t = text.take(TextToSpeech.getMaxSpeechInputLength())
            if (tts.synthesizeToFile(t, Bundle(), out, "nebo") != TextToSpeech.SUCCESS) {
                throw RuntimeException("не вдалося запустити синтез")
            }
            if (!done.await(30, TimeUnit.SECONDS)) throw RuntimeException("синтез завис")
            if (failed.get() || !out.exists() || out.length() < 100) {
                throw RuntimeException("не вдалося синтезувати мовлення")
            }
        } finally {
            tts.shutdown()
        }
    }

    /** Google Translate TTS (текст до ~180 символів) -> MP3-файл. */
    fun translate(text: String, out: File) {
        val u = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=uk&q=" +
            URLEncoder.encode(text, "UTF-8")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        c.setRequestProperty("Referer", "https://translate.google.com/")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            c.disconnect()
        }
    }

    fun gemini(c: Context, text: String, out: File) {
        val p = c.getSharedPreferences("s", Context.MODE_PRIVATE)
        val key = p.getString("gkey", "").orEmpty().trim()
        if (key.isEmpty()) throw RuntimeException("не вказано ключ")
        val model = p.getString("gmodel", DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val voice = p.getString("gvoice", DEFAULT_VOICE).orEmpty().trim().ifEmpty { DEFAULT_VOICE }

        val prompt = "Say in a calm, clear voice of a news announcer, in Ukrainian: $text"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                        )
                    )
            )

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(raw).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    raw
                }
                throw RuntimeException("HTTP $code: ${msg.take(200)}")
            }
            try {
                val inline = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getJSONObject("inlineData")
                val pcm = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                if (pcm.isEmpty()) throw RuntimeException("порожнє аудіо")
                val rate = rateRe.find(inline.optString("mimeType"))?.groupValues?.get(1)?.toInt() ?: 24000
                writeWav(out, pcm, rate)
            } catch (e: JSONException) {
                throw RuntimeException("відповідь без аудіо, спробуйте іншу модель")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeWav(out: File, pcm: ByteArray, rate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + pcm.size)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(1)
        h.putInt(rate)
        h.putInt(rate * 2)
        h.putShort(2)
        h.putShort(16)
        h.put("data".toByteArray())
        h.putInt(pcm.size)
        out.outputStream().use {
            it.write(h.array())
            it.write(pcm)
        }
    }
}
