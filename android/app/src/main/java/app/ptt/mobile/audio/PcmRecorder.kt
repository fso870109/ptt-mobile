package app.ptt.mobile.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * 16 kHz / 16-bit / mono PCM recorder. Emits ~100ms frames (3200 bytes).
 */
class PcmRecorder {
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ByteArray) -> Unit): Boolean {
        stop()
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val bufSize = maxOf(min, FRAME_BYTES) * 2
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
        var created: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) { created = r; break }
                r.release()
            } catch (_: Exception) {}
        }
        val recorder = created ?: return false
        rec = recorder
        running = true
        recorder.startRecording()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(FRAME_BYTES)
            var filled = 0
            val tmp = ByteArray(maxOf(min, FRAME_BYTES))
            while (running) {
                val n = try { recorder.read(tmp, 0, tmp.size) } catch (_: Exception) { break }
                if (n <= 0) continue
                var off = 0
                while (off < n && running) {
                    val c = minOf(FRAME_BYTES - filled, n - off)
                    System.arraycopy(tmp, off, buf, filled, c)
                    filled += c
                    off += c
                    if (filled >= FRAME_BYTES) {
                        onFrame(buf.copyOf())
                        filled = 0
                    }
                }
            }
        }, "ptt-rec").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: Exception) {}
        thread = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }

    companion object {
        const val SR = 16000
        const val FRAME_BYTES = 3200 // 100ms
    }
}
