package app.ptt.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import app.ptt.mobile.engine.PttEngine

/**
 * Catches OEM walkie-talkie PTT button broadcasts when the app / service is alive.
 */
class PttButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = PttEngine.get(context)
        val action = intent.action ?: return
        val downActions = setOf(
            "android.intent.action.PTT_BUTTON_DOWN",
            "com.sonim.intent.action.PTT_KEY_DOWN",
        )
        val upActions = setOf(
            "android.intent.action.PTT_BUTTON_UP",
            "com.sonim.intent.action.PTT_KEY_UP",
        )
        when (action) {
            in downActions -> engine.startTalk("hw")
            in upActions -> engine.stopTalk("hw")
            else -> {
                val ke = key(intent)
                if (ke != null) {
                    if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) engine.startTalk("hw")
                    else if (ke.action == KeyEvent.ACTION_UP) engine.stopTalk("hw")
                    return
                }
                val state = intent.getIntExtra("state", -1)
                when (state) {
                    1 -> engine.startTalk("hw")
                    0 -> engine.stopTalk("hw")
                    else -> {
                        // unknown payload: treat as toggle
                        if (engine.state.pressing) engine.stopTalk("hw") else engine.startTalk("hw")
                    }
                }
            }
        }
    }

    private fun key(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
}
