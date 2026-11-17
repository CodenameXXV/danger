package ua.nebosumy

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Офлайн-синтез мовлення українською через Sherpa-ONNX + модель Supertonic 3
 * (10 голосів українською, 44.1 кГц; sid 0-4 — жіночі F1-F5, sid 5-9 — чоловічі M1-M5).
 *
 * Модель НЕ вшита в APK: при першому використанні вона один раз докачується
 * з офіційного релізу sherpa-onnx та розпаковується у filesDir.
 * Далі синтез працює повністю без інтернету.
 *
 * Примітка: Piper-моделі vits-piper-uk_UA-ukrainian_tts-medium (там теж є чоловічі
 * голоси) — зламані в релізі k2-fsa (кривий tokens.txt, видає сміття), тому не
 * використовуємо; перевірено тестом і коментарем мейнтейнера k2-fsa.
 */
object Sherpa {
    const val ENGINE = "sherpa"
    private const val MODEL_NAME = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_NAME.tar.bz2"
    // контрольна сума архіву з офіційного checksum.txt релізу tts-models
    private const val MODEL_SHA256 =
        "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
    // сумарний розмір усіх файлів архіву після розпакування (~139 МБ, 9 файлів)
    private const val MODEL_TOTAL_SIZE = 145316356L
    // 10 голосів: sid 0-4 жіночі (F1-F5), sid 5-9 чоловічі (M1-M5) — обрано чоловічий M1
    private const val VOICE_SID = 5
    private const val NUM_STEPS = 8
    private const val LANG = "uk"
    private const val SPEED = 1.0f
    private const val NUM_THREADS = 2

    private val busy = AtomicBoolean(false)
    @Volatile private var tts: OfflineTts? = null

    /** Текст поточної операції ("Завантаження 42%…", "Розпакування…") — для UI. */
    @Volatile var status: String? = null
        private set

    /** Слухач статусу (викликається у фоновому потоці; UI треба постити через Handler). */
    @Volatile var onStatus: ((String?) -> Unit)? = null

    private fun dir(c: Context): File = File(c.filesDir, "sherpa/$MODEL_NAME")

    fun ready(c: Context): Boolean = FILE_NAMES.all { File(dir(c), it).isFile }

    private val FILE_NAMES = arrayOf(
        "duration_predictor.int8.onnx",
        "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx",
        "vocoder.int8.onnx",
        "tts.json",
        "unicode_indexer.bin",
        "voice.bin",
    )

    fun statusText(c: Context): String = when {
        ready(c) -> "Модель готова (офлайн)"
        busy.get() -> status ?: "Завантаження моделі…"
        else -> "Модель не завантажена (~123 МБ, завантажиться при першому використанні)"
    }

    /**
     * Синтез тексту -> WAV-файл. Якщо моделі немає — синхронно докачує її
     * (викликати лише з фонового потоку, наприклад з "Перевірити голос").
     * Якщо модель на диску побита (обірване завантаження) — сам її перекачає.
     */
    fun speak(c: Context, text: String, out: File) {
        ensure(c)
        val t = try {
            tts(c)
        } catch (e: Exception) {
            if (!ready(c)) throw e
            // модель є, але завантажується з помилкою — стираємо і качаємо заново
            release()
            dir(c).deleteRecursively()
            ensure(c)
            tts(c)
        }
        val gen = GenerationConfig(
            speed = SPEED,
            sid = VOICE_SID,
            numSteps = NUM_STEPS,
            extra = mapOf("lang" to LANG),
        )
        val audio = t.generateWithConfig(text, gen)
        if (audio.samples.isEmpty()) throw RuntimeException("порожнє аудіо")
        writeWav(out, audio.samples, audio.sampleRate)
    }

    /** Неблокуюче завантаження моделі (ідемпотентне) — для сервісу. */
    fun ensureAsync(c: Context) {
        if (ready(c) || busy.get()) return
        Thread {
            try {
                download(c)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun ensure(c: Context) {
        if (!ready(c)) download(c)
    }

    @Synchronized
    private fun tts(c: Context): OfflineTts {
        tts?.let { return it }
        val d = dir(c)
        if (!ready(c)) throw RuntimeException("офлайн-модель не завантажена")
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(d, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(d, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(d, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(d, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(d, "tts.json").absolutePath,
                    unicodeIndexer = File(d, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(d, "voice.bin").absolutePath,
                ),
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            ),
        )
        val t = OfflineTts(assetManager = null, config = cfg)
        tts = t
        return t
    }

    fun release() {
        tts?.release()
        tts = null
    }

    private fun set(s: String?) {
        status = s
        onStatus?.invoke(s)
    }

    @Synchronized
    private fun download(c: Context) {
        if (ready(c)) return
        // стираємо весь каталог sherpa: прибирає і старі/побиті моделі
        File(c.filesDir, "sherpa").deleteRecursively()
        val d = dir(c)
        val tmp = File(c.cacheDir, "supertonic3.tar.bz2")
        busy.set(true)
        try {
            set("Завантаження моделі 0%…")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            try {
                if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { i ->
                    FileOutputStream(tmp).use { o ->
                        val buf = ByteArray(64 * 1024)
                        var last = 0L
                        while (true) {
                            if (Thread.interrupted()) throw InterruptedException()
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            if (total > 0) {
                                val p = o.channel.position() * 100 / total
                                if (p != last) {
                                    last = p
                                    set("Завантаження моделі $p%…")
                                }
                            }
                        }
                    }
                }
                // архів має бути докачаний до кінця (розмір файла vs Content-Length)
                if (total > 0 && tmp.length() != total) {
                    throw RuntimeException(
                        "архів докачався не повністю (${tmp.length()} з $total байтів)"
                    )
                }
            } finally {
                conn.disconnect()
            }

            // перевірка цілісності завантаженого архіву
            set("Перевірка цілісності…")
            val got = sha256(tmp)
            if (!got.equals(MODEL_SHA256, ignoreCase = true)) {
                throw RuntimeException(
                    "архів пошкоджено при завантаженні (сума не збігається) — спробуйте ще раз"
                )
            }

            set("Розпакування моделі 0%…")
            extract(tmp, d)
            if (!ready(c)) throw RuntimeException("архів моделі пошкоджений")
        } finally {
            busy.set(false)
            tmp.delete()
            set(null)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(archive: File, target: File) {
        target.mkdirs()
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive))).use { t ->
            var done = 0L
            var last = -1L
            while (true) {
                val e = t.nextTarEntry ?: break
                if (!t.canReadEntryData(e)) continue
                // прибираємо кореневу папку архіву sherpa-onnx-supertonic-…/
                val rel = e.name.dropWhile { it == '/' }
                    .substringAfter('/', "")
                if (rel.isEmpty()) continue
                if (rel.contains("..")) continue // захист від path traversal
                val f = File(target, rel)
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { o -> t.copyTo(o, 64 * 1024) }
                    done += e.size
                    // прогрес: розпакування на флеш-пам'ять тривале, треба бачити рух
                    val p = (done * 100 / MODEL_TOTAL_SIZE).coerceIn(0, 100)
                    if (p != last) {
                        last = p
                        set("Розпакування моделі $p%…")
                    }
                }
            }
            if (done != MODEL_TOTAL_SIZE) {
                throw RuntimeException("архів моделі розпаковано не повністю")
            }
        }
    }

    private fun writeWav(out: File, samples: FloatArray, rate: Int) {
        val pcm = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = if (s > 1f) 1f else if (s < -1f) -1f else s
            bb.putShort((v * 32767f).toInt().toShort())
        }
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
