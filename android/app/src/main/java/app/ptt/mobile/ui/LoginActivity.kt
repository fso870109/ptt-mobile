package app.ptt.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.ptt.mobile.R
import app.ptt.mobile.engine.Prefs
import app.ptt.mobile.model.JsonX
import app.ptt.mobile.net.ApiClient
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val api = ApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.token.isNotBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_login)
        val server = findViewById<EditText>(R.id.serverUrl)
        val company = findViewById<EditText>(R.id.company)
        val user = findViewById<EditText>(R.id.username)
        val pass = findViewById<EditText>(R.id.password)
        val btn = findViewById<MaterialButton>(R.id.loginBtn)
        val err = findViewById<TextView>(R.id.error)
        server.setText(prefs.serverUrl.ifBlank { Prefs.DEFAULT_SERVER })
        company.setText(prefs.company.ifBlank { Prefs.DEFAULT_ORG })
        user.setText(prefs.username)

        btn.setOnClickListener {
            val url = server.text.toString().trim().trimEnd('/')
            val username = user.text.toString().trim()
            val password = pass.text.toString()
            val org = company.text.toString().trim()
            if (url.isBlank() || username.isBlank() || password.isBlank() || org.isBlank()) {
                err.text = "請輸入伺服器、公司代碼、帳號與密碼"
                return@setOnClickListener
            }
            btn.isEnabled = false
            err.text = ""
            thread {
                try {
                    val result = api.login(url, username, password, org)
                    runOnUiThread {
                        prefs.serverUrl = url
                        prefs.company = org.uppercase()
                        prefs.username = username
                        prefs.token = result.token
                        JsonX.user(result.userJson) // validate shape
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                } catch (e: ApiClient.ApiError) {
                    runOnUiThread {
                        err.text = e.display
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        err.text = "無法連線到伺服器（若剛喚醒免費主機請等 30–60 秒再試）\n${e.message ?: ""}"
                        btn.isEnabled = true
                    }
                }
            }
        }
    }
}
