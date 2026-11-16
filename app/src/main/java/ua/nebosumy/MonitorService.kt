package ua.nebosumy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Bus {
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var running = false
    @Volatile var playLast = false
    @Volatile var lastText = "Зупинено"

    fun post(s: String) {
        lastText = s
        listener?.invoke(s)
    }
}

private data class Msg(val id: Int, val text: String, val audio: String?)

class MonitorService : Service() {

    @Volatile private var active = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        if (!active) {
            active = true
            Bus.running = true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nebosumy:monitor").apply { acquire() }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        Bus.running = false
        worker?.interrupt()
        wake?.let { if (it.isHeld) it.release() }
        Bus.post("Зупинено")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mon", "Моніторинг", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "mon")
            .setContentTitle("Небо Сумщини")
            .setContentText("Слухаю канал")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun interval(): Long =
        getSharedPreferences("s", MODE_PRIVATE).getInt("interval", 2) * 1000L

    private fun loop() {
        var lastVoice = -1
        try {
            while (active) {
                var wait = interval()
                try {
                    val msgs = fetch()
                    val voices = msgs.filter { it.audio != null }.sortedBy { it.id }
                    if (lastVoice < 0) lastVoice = voices.lastOrNull()?.id ?: 0

                    if (Bus.playLast) {
                        Bus.playLast = false
                        voices.lastOrNull()?.let {
                            Bus.post(summary(msgs, "▶ останнє голосове #${it.id}"))
                            play(it.audio!!)
                            lastVoice = maxOf(lastVoice, it.id)
                        }
                    }

                    for (m in voices.filter { it.id > lastVoice }) {
                        if (!active) break
                        lastVoice = m.id
                        Bus.post(summary(msgs, "▶ нове голосове #${m.id}"))
                        play(m.audio!!)
                    }

                    val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    Bus.post(summary(msgs, "Слухаю канал, оновлено $t"))
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    Bus.post("Помилка: ${e.message}")
                    wait = 5000
                }
                var w = 0L
                while (active && w < wait && !Bus.playLast) {
                    Thread.sleep(200)
                    w += 200
                }
            }
        } catch (e: InterruptedException) {
        }
    }

    private fun summary(msgs: List<Msg>, head: String): String {
        val tail = msgs.sortedBy { it.id }.takeLast(8).reversed().joinToString("\n\n") {
            if (it.audio != null) {
                "#${it.id} 🔊 голосове" + if (it.text.isNotEmpty()) ": ${it.text}" else ""
            } else {
                "#${it.id} ${it.text}"
            }
        }
        return head + "\n\n" + tail
    }

    private fun fetch(): List<Msg> {
        val c = URL("https://t.me/s/NeboSumyOperatyvno").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    private fun parse(html: String): List<Msg> {
        val marks = Regex("data-post=\"[^\"/]+/(\\d+)\"").findAll(html).toList()
        val audioRe = Regex("<audio[^>]*\\ssrc=\"([^\"]+)\"")
        val textRe = Regex("js-message_text[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
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

    private fun play(url: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        am.requestAudioFocus(req)
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(url)
            val v = getSharedPreferences("s", MODE_PRIVATE).getInt("volume", 100) / 100f
            mp.setVolume(v, v)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                latch.countDown()
                true
            }
            mp.prepareAsync()
            latch.await(120, TimeUnit.SECONDS)
        } finally {
            mp.release()
            am.abandonAudioFocusRequest(req)
        }
    }
}
