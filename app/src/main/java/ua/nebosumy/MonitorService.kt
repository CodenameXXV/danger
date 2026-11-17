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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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

class MonitorService : Service() {

    @Volatile private var active = false
    @Volatile private var ttsErr: String? = null
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    private val urlRe = Regex("https?://\\S+|t\\.me/\\S+")
    private val emojiRe = Regex("[\\p{So}\\p{Sk}\\u200D\\uFE0F\\u20E3\\uFFFC]")
    private val spaceRe = Regex("\\s+")
    private val sentenceRe = Regex("(?<=[.!?…])\\s+")

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!active) {
            active = true
            Bus.running = true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monitor:work").apply { acquire() }
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
            .setContentTitle("Монітор")
            .setContentText("Моніторинг активний")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun prefs() = getSharedPreferences("s", MODE_PRIVATE)

    private fun loop() {
        val seen = HashMap<String, Int>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            while (active) {
                var wait = prefs().getInt("interval", 2) * 1000L
                try {
                    val names = Store.channels(this).filter { it.second }.map { it.first }
                    seen.keys.retainAll(names.toSet())
                    if (names.isEmpty()) {
                        Bus.post("Немає активних каналів. Додайте канал і увімкніть його.")
                    } else {
                        val futures = names.map { n -> n to pool.submit(Callable { Telegram.fetch(n) }) }
                        val results = LinkedHashMap<String, List<Msg>>()
                        val errors = ArrayList<String>()
                        for ((n, f) in futures) {
                            try {
                                results[n] = f.get().sortedBy { it.id }
                            } catch (e: InterruptedException) {
                                throw e
                            } catch (e: Exception) {
                                errors.add("@$n: ${e.cause?.message ?: e.message}")
                            }
                        }
                        if (results.isEmpty()) wait = 5000

                        if (Bus.playLast) {
                            Bus.playLast = false
                            for ((n, msgs) in results) {
                                msgs.lastOrNull()?.let {
                                    Bus.post(summary(results, errors, "▶ останнє: @$n #${it.id}"))
                                    handle(it)
                                }
                            }
                        }

                        for ((n, msgs) in results) {
                            val max = msgs.lastOrNull()?.id ?: 0
                            val prev = seen[n]
                            seen[n] = maxOf(prev ?: 0, max)
                            if (prev == null) continue
                            for (m in msgs.filter { it.id > prev }) {
                                if (!active) break
                                Bus.post(summary(results, errors, "▶ нове: @$n #${m.id}"))
                                handle(m)
                            }
                        }

                        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        Bus.post(summary(results, errors, "Моніторинг активний, оновлено $t"))
                    }
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
        } finally {
            pool.shutdownNow()
        }
    }

    private fun summary(results: Map<String, List<Msg>>, errors: List<String>, head: String): String {
        val sb = StringBuilder(head)
        ttsErr?.let { sb.append("\n⚠ Озвучення: ").append(it) }
        for (e in errors) sb.append("\n⚠ ").append(e)
        for ((n, msgs) in results) {
            sb.append("\n\n@").append(n)
            for (m in msgs.takeLast(3).reversed()) {
                sb.append("\n#").append(m.id).append(' ')
                if (m.audio != null) sb.append("🔊 ")
                sb.append(m.text.take(200))
            }
        }
        return sb.toString()
    }

    private fun handle(m: Msg) {
        val audio = m.audio
        if (audio != null) {
            withFocus { playNow(audio) }
            return
        }
        if (!prefs().getBoolean("tts", true)) return
        val clean = m.text.replace(urlRe, " ").replace(emojiRe, "").replace(spaceRe, " ").trim()
        if (clean.isEmpty()) return
        val words = clean.split(" ").size
        if (words > prefs().getInt("maxwords", 40)) return
        speak(clean)
    }

    private fun withFocus(block: () -> Unit) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        am.requestAudioFocus(req)
        try {
            block()
        } finally {
            am.abandonAudioFocusRequest(req)
        }
    }

    private fun playNow(source: String) {
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(source)
            val v = prefs().getInt("volume", 100) / 100f
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
        }
    }

    private fun speak(text: String) {
        try {
            withFocus {
                for (chunk in chunks(text)) {
                    val f = File(cacheDir, "tts_" + System.nanoTime() + ".mp3")
                    try {
                        download(chunk, f)
                        playNow(f.absolutePath)
                    } finally {
                        f.delete()
                    }
                }
            }
            ttsErr = null
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            ttsErr = e.message ?: e.javaClass.simpleName
        }
    }

    private fun download(text: String, out: File) {
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

    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (sentence in text.split(sentenceRe)) {
            for (piece in pieces(sentence)) {
                if (cur.isEmpty()) {
                    cur = piece
                } else if (cur.length + 1 + piece.length <= 180) {
                    cur = "$cur $piece"
                } else {
                    out.add(cur)
                    cur = piece
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun pieces(s: String): List<String> {
        if (s.length <= 180) return listOf(s)
        val out = ArrayList<String>()
        var cur = ""
        for (w in s.split(" ")) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > 180) {
                out.add(cur)
                cur = w
            } else {
                cur = if (cur.isEmpty()) w else "$cur $w"
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
