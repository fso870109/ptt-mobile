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

    fun clearSession() {
        p.edit().remove("token").apply()
    }

    companion object {
        const val DEFAULT_SERVER = "https://ptt-prototype.onrender.com"
        const val DEFAULT_ORG = "DEMO"
    }
}
