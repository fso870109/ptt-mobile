package app.ptt.mobile.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EventLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun add(kind: String, detail: String = "") {
        val msg = redact("[$kind] $detail")
        lines.add(fmt.format(Date()) + " " + msg)
        while (lines.size > 200) lines.removeAt(0)
    }

    @Synchronized
    fun dump(): List<String> = ArrayList(lines)

    fun redact(s: String): String = s
        .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE), "Bearer ***")
        .replace(Regex("\"(token|password|oldPassword|newPassword|authorization|cookie)\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "\"$1\":\"***\"")
        .replace(Regex("\\b(token|password|authorization)=([^\\s&]+)", RegexOption.IGNORE_CASE), "$1=***")
}
