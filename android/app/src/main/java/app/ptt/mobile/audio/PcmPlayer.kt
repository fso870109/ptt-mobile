package app.ptt.mobile.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays incoming s16le mono 16 kHz frames. Frames from a new streamId reset the jitter buffer.
 */
class PcmPlayer {
    private val q = LinkedBlockingQueue<ByteArray>(64)
    private val currentStream = AtomicInteger(-1)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun play(streamId: Int, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (currentStream.get() != streamId) {
            currentStream.set(streamId)
            q.clear()
            q.offer(ByteArray(0)) // flush marker
        }
        if (!q.offer(pcm)) {
            q.poll()
            q.offer(pcm)
        }
        ensureStarted()
    }

    fun endStream(streamId: Int) {
        if (currentStream.get() == streamId) {
            // leave queued audio to drain
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (running) return
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(min, FRAME_BYTES * 8)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SR)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track = if (Build.VERSION.SDK_INT >= 26) {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, buf, AudioTrack.MODE_STREAM)
        }
        running = true
        track?.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var primed = false
            var buffered = 0
            var preroll = FRAME_BYTES * 2 // 約 200ms，欠載時加大到 400ms 以吸收遲到／丟包
            val hold = ArrayList<ByteArray>()
            val t = track
            while (running && t != null) {
                val pkt = try { q.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (!running) break
                if (pkt == null) {
                    if (primed && q.isEmpty()) {
                        // underrun: next packet will re-prime with a slightly larger jitter buffer
                        primed = false
                        buffered = 0
                        hold.clear()
                        preroll = minOf(FRAME_BYTES * 4, preroll + FRAME_BYTES)
                    }
                    continue
                }
                if (pkt.isEmpty()) {
                    try { t.pause(); t.flush(); t.play() } catch (_: Exception) {}
                    primed = false
                    buffered = 0
                    hold.clear()
                    continue
                }
                if (!primed) {
                    hold.add(pkt)
                    buffered += pkt.size
                    if (buffered >= preroll) {
                        for (h in hold) writeAll(t, h)
                        hold.clear()
                        primed = true
                    }
                } else {
                    writeAll(t, pkt)
                }
            }
        }, "ptt-play").also { it.start() }
    }

    private fun writeAll(t: AudioTrack, data: ByteArray) {
        var off = 0
        while (off < data.size && running) {
            val n = t.write(data, off, data.size - off)
            if (n <= 0) break
            off += n
        }
    }

    @Synchronized
    fun stop() {
        running = false
        q.clear()
        try { thread?.interrupt() } catch (_: Exception) {}
        try { thread?.join(400) } catch (_: Exception) {}
        thread = null
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        currentStream.set(-1)
    }

    companion object {
        const val SR = 16000
        const val FRAME_BYTES = 3200
    }
}
