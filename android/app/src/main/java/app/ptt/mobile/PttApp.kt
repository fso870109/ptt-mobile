package app.ptt.mobile

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PttApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(this)
    }

    companion object {
        private const val PREF = "ptt"
        const val THEME_KEY = "theme"

        fun themeMode(app: android.content.Context): String {
            return app.getSharedPreferences(PREF, MODE_PRIVATE).getString(THEME_KEY, "system") ?: "system"
        }

        fun setThemeMode(app: android.content.Context, mode: String) {
            app.getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(THEME_KEY, mode).apply()
            applyTheme(app)
        }

        fun applyTheme(app: android.content.Context) {
            AppCompatDelegate.setDefaultNightMode(
                when (themeMode(app)) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
