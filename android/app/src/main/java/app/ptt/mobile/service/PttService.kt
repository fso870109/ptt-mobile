package app.ptt.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.ptt.mobile.R
import app.ptt.mobile.engine.PttEngine
import app.ptt.mobile.model.Alert
import app.ptt.mobile.ui.MainActivity

class PttService : Service(), PttEngine.Listener {
    inner class LocalBinder : Binder() { val service: PttService get() = this@PttService }

    private val binder = LocalBinder()
    lateinit var engine: PttEngine
        private set
    private var mediaSession: MediaSessionCompat? = null
    private var startedFg = false

    override fun onCreate() {
        super.onCreate()
        engine = PttEngine.get(this)
        createChannels()
        setupMediaSession()
        engine.addListener(this)
        startAsForeground()
        engine.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PTT_DOWN, ACTION_PTT_START -> engine.startTalk("notification")
            ACTION_PTT_UP, ACTION_PTT_STOP -> engine.stopTalk("notification")
            ACTION_PTT_TOGGLE -> {
                if (engine.state.pressing) engine.stopTalk("notification")
                else engine.startTalk("notification")
            }
            ACTION_STOP -> {
                engine.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_CONNECT -> engine.connect()
        }
        if (!startedFg) startAsForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.removeListener(this)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // keep running so lock-screen / headset PTT still works
        super.onTaskRemoved(rootIntent)
    }

    override fun onState(s: PttEngine.UiState) {
        updateNotification(s)
        val state = if (s.pressing && s.granted) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PLAYING // stay "playing" so we keep media buttons
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        if (s.authLost) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onAlert(a: Alert) {
        val mine = a.userId == engine.state.me?.id
        if (mine) return
        val text = "${a.name} 發出緊急警報" + (a.channelName?.let { "（$it）" } ?: "")
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("緊急警報")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(ALERT_ID, n) } catch (_: SecurityException) {}
    }

    override fun onAlertEnd(id: String, by: String) {
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
    }

    private fun startAsForeground() {
        val n = buildNotification(engine.state)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, computeTypes())
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, computeTypes())
            } else {
                startForeground(NOTIF_ID, n)
            }
            startedFg = true
        } catch (e: SecurityException) {
            // Android 14: cannot start microphone FGS from background. Fall back to playback-only.
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else startForeground(NOTIF_ID, n)
                startedFg = true
            } catch (_: Exception) {
                stopSelf()
            }
        }
    }

    private fun computeTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasPerm(android.Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPerm(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, getString(R.string.notif_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
            }
        )
    }

    private fun setupMediaSession() {
        val ms = MediaSessionCompat(this, "PTT")
        ms.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        ms.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val ke = mediaButtonEvent.mediaKey() ?: return super.onMediaButtonEvent(mediaButtonEvent)
                if (!PttKeys.isPttKey(ke.keyCode) && ke.keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
                    ke.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY &&
                    ke.keyCode != KeyEvent.KEYCODE_MEDIA_PAUSE &&
                    ke.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
                    ke.keyCode != KeyEvent.KEYCODE_MEDIA_STOP
                ) return super.onMediaButtonEvent(mediaButtonEvent)
                when (ke.action) {
                    KeyEvent.ACTION_DOWN -> if (ke.repeatCount == 0) {
                        if (ke.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            if (engine.state.pressing) engine.stopTalk("headset") else engine.startTalk("headset")
                        } else if (ke.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || ke.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                            engine.stopTalk("headset")
                        } else {
                            engine.startTalk("headset")
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (ke.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) engine.stopTalk("headset")
                    }
                }
                return true
            }
            override fun onPlay() { engine.startTalk("headset") }
            override fun onPause() { engine.stopTalk("headset") }
            override fun onStop() { engine.stopTalk("headset") }
        })
        ms.isActive = true
        ms.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        ms.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        mediaSession = ms
    }

    private fun Intent.mediaKey(): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun updateNotification(s: PttEngine.UiState) {
        if (!startedFg) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: PttEngine.UiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val talkPi = pendingService(1, if (s.pressing) ACTION_PTT_STOP else ACTION_PTT_START)
        val title = when {
            s.pressing && s.granted -> "正在發話"
            s.conn == "on" -> "已連線 · ${s.target?.label() ?: "未選頻道"}"
            else -> s.connText
        }
        val text = engine.talkStatusText().ifBlank { "鎖屏或用耳機按鍵即可對講" }
        val builder = NotificationCompat.Builder(this, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_mic, if (s.pressing) "結束發話" else "發話", talkPi)
        mediaSession?.let {
            builder.setStyle(MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0))
        }
        return builder.build()
    }

    private fun pendingService(req: Int, action: String): PendingIntent {
        val i = Intent(this, PttService::class.java).setAction(action)
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ALERT_ID = 1002
        const val CH_CALL = "ptt_call"
        const val CH_ALERT = "ptt_alert"
        const val ACTION_PTT_START = "app.ptt.mobile.PTT_START"
        const val ACTION_PTT_STOP = "app.ptt.mobile.PTT_STOP"
        const val ACTION_PTT_DOWN = "app.ptt.mobile.PTT_DOWN"
        const val ACTION_PTT_UP = "app.ptt.mobile.PTT_UP"
        const val ACTION_PTT_TOGGLE = "app.ptt.mobile.PTT_TOGGLE"
        const val ACTION_STOP = "app.ptt.mobile.STOP"
        const val ACTION_CONNECT = "app.ptt.mobile.CONNECT"

        fun start(ctx: Context) {
            val i = Intent(ctx, PttService::class.java).setAction(ACTION_CONNECT)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PttService::class.java).setAction(ACTION_STOP))
        }
    }
}

object PttKeys {
    // Common hardware / OEM PTT keycodes (including unofficial ones used by rugged handsets)
    private val codes = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_F1,
        KeyEvent.KEYCODE_F2,
        KeyEvent.KEYCODE_F10,
        228, // some Kyocera / Sonim maps
        229,
        291,
        142,
    )

    fun isPttKey(code: Int): Boolean = code in codes
}
