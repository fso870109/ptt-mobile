package app.ptt.mobile.engine

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("ptt", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) { p.edit().putString("serverUrl", v.trim().trimEnd('/')).apply() }

    var company: String
        get() {
            val v = p.getString("company", DEFAULT_ORG)?.trim().orEmpty()
            return v.ifBlank { DEFAULT_ORG }
        }
        set(v) { p.edit().putString("company", v.trim().uppercase()).apply() }

    var username: String
        get() = p.getString("username", "") ?: ""
        set(v) { p.edit().putString("username", v).apply() }

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v).apply() }

    var channel: String
        get() = p.getString("channel", "") ?: ""
        set(v) { p.edit().putString("channel", v).apply() }

    var shareLoc: Boolean
        get() = p.getBoolean("shareLoc", false)
        set(v) { p.edit().putBoolean("shareLoc", v).apply() }

    /** 講話方式：hold＝按住講話、lock＝鎖定講話、vox＝聲控 */
    var talkMode: String
        get() = (p.getString("talkMode", "hold") ?: "hold").let { if (it in TALK_MODES) it else "hold" }
        set(v) { p.edit().putString("talkMode", if (v in TALK_MODES) v else "hold").apply() }

    /** 聲控靈敏度 0～100（越大越容易觸發） */
    var voxSens: Int
        get() = p.getInt("voxSens", 50).coerceIn(0, 100)
        set(v) { p.edit().putInt("voxSens", v.coerceIn(0, 100)).apply() }

    fun clearSession() {
        p.edit().remove("token").apply()
    }

    companion object {
        const val DEFAULT_SERVER = "https://ptt-prototype.onrender.com"
        const val DEFAULT_ORG = "DEMO"
        val TALK_MODES = listOf("hold", "lock", "vox")
    }
}
