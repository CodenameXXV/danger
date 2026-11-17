package ua.nebosumy

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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
 * Офлайн-синтез мовлення українською через Sherpa-ONNX + модель Piper VITS
 * (український голос Lada з rhasspy/piper-voices).
 *
 * Модель НЕ вшита в APK: при першому використанні вона один раз докачується
 * з офіційного релізу sherpa-onnx (туди покладено готові Piper-моделі) та
 * розпаковується у filesDir. Далі синтез працює повністю без інтернету.
 *
 * Примітка: модель vits-piper-uk_UA-ukrainian_tts-medium НЕ використовуємо —
 * вона зламана (видає сміття; підтверджено тестом і офіційним демо k2-fsa).
 */
object Sherpa {
    const val ENGINE = "sherpa"
    private const val MODEL_NAME = "vits-piper-uk_UA-lada-x_low"
    private const val MODEL_FILE = "uk_UA-lada-x_low.onnx"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_NAME.tar.bz2"
    // контрольна сума архіву з офіційного checksum.txt релізу tts-models
    private const val MODEL_SHA256 =
        "7a2228f249299d19016cd6ca170bf6916f8664231bcbd9e61f447b861a039cf5"
    // точний розмір uk_UA-lada-x_low.onnx всередині архіву (fp32)
    private const val MODEL_ONNX_SIZE = 20576273L
    // сумарний розмір усіх файлів архіву після розпакування (~39 МБ, 359 файлів)
    private const val MODEL_TOTAL_SIZE = 38573140L
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

    fun ready(c: Context): Boolean =
        File(dir(c), MODEL_FILE).isFile && File(dir(c), "tokens.txt").isFile &&
            File(dir(c), "espeak-ng-data").isDirectory

    fun statusText(c: Context): String = when {
        ready(c) -> "Модель готова (офлайн)"
        busy.get() -> status ?: "Завантаження моделі…"
        else -> "Модель не завантажена (~25 МБ, завантажиться при першому використанні)"
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
        val audio = t.generate(text, sid = 0, speed = SPEED)
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
                vits = OfflineTtsVitsModelConfig(
                    model = File(d, MODEL_FILE).absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    // espeak-ng-data потрібен Piper-моделям для фонемізації тексту
                    dataDir = File(d, "espeak-ng-data").absolutePath,
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
        val tmp = File(c.cacheDir, "$MODEL_NAME.tar.bz2")
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
                        var done = 0L
                        var last = 0L
                        while (true) {
                            if (Thread.interrupted()) throw InterruptedException()
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = done * 100 / total
                                if (p != last) {
                                    last = p
                                    set("Завантаження моделі $p%…")
                                }
                            }
                        }
                    }
                }
                // архів має бути докачаний до кінця
                if (total > 0 && done != total) {
                    throw RuntimeException(
                        "архів докачався не повністю ($done з $total байтів)"
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
            if (File(d, MODEL_FILE).length() != MODEL_ONNX_SIZE) {
                throw RuntimeException("файл моделі несподіваного розміру")
            }
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
                // прибираємо кореневу папку архіву vits-piper-…/
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
