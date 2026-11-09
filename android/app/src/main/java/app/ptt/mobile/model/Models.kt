package app.ptt.mobile.model

import org.json.JSONArray
import org.json.JSONObject

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val disabled: Boolean = false,
)

data class Channel(
    val id: String,
    val name: String,
    val open: Boolean,
    val myRole: String,
    val myRoleLabel: String,
    val canTalk: Boolean,
    val online: Int,
    val last: Long,
    val lastTalk: LastTalk?,
)

data class LastTalk(
    val from: String,
    val fromName: String,
    val toName: String?,
    val ts: Long,
    val durMs: Long,
)

data class Member(val id: String, val name: String, val role: String)
data class OnlineUser(val id: String, val name: String, val channel: String?)
data class DmSummary(
    val conv: String,
    val peerId: String,
    val peerName: String,
    val last: Long,
    val lastTalk: LastTalk?,
)

data class LocEntry(
    val id: String,
    val name: String,
    val online: Boolean,
    val channel: String?,
    val channelName: String?,
    val lat: Double,
    val lng: Double,
    val acc: Int?,
    val battery: Int?,
    val charging: Boolean?,
    val ts: Long,
)

data class ChatMessage(
    val id: String,
    val conv: String,
    val from: String,
    val fromName: String,
    val kind: String,
    val text: String?,
    val photoId: String?,
    val mime: String?,
    val ts: Long,
)

data class Alert(
    val id: String,
    val userId: String,
    val name: String,
    val channel: String?,
    val channelName: String?,
    val lat: Double?,
    val lng: Double?,
    val acc: Int?,
    val ts: Long,
)

data class TalkInfo(
    val from: String,
    val fromName: String,
    val streamId: Int,
    val prio: Int,
    val conv: String,
)

sealed class Target {
    data class ChannelT(val id: String, val name: String) : Target()
    data class DmT(val peerId: String, val peerName: String, val conv: String) : Target()

    fun conv(): String = when (this) {
        is ChannelT -> id
        is DmT -> conv
    }

    fun label(): String = when (this) {
        is ChannelT -> "# $name"
        is DmT -> "🔒 私人對講：$peerName"
    }
}

object JsonX {
    fun str(o: JSONObject, k: String): String? =
        if (!o.has(k) || o.isNull(k)) null else o.optString(k)

    fun lng(o: JSONObject, k: String): Long? =
        if (!o.has(k) || o.isNull(k)) null else o.optLong(k)

    fun dbl(o: JSONObject, k: String): Double? =
        if (!o.has(k) || o.isNull(k)) null else o.optDouble(k)

    fun int(o: JSONObject, k: String): Int? =
        if (!o.has(k) || o.isNull(k)) null else o.optInt(k)

    fun bool(o: JSONObject, k: String): Boolean? =
        if (!o.has(k) || o.isNull(k)) null else o.optBoolean(k)

    fun user(o: JSONObject) = User(
        id = o.getString("id"),
        username = o.optString("username"),
        displayName = o.optString("displayName"),
        role = o.optString("role", "user"),
        disabled = o.optBoolean("disabled", false),
    )

    fun lastTalk(o: JSONObject?): LastTalk? {
        if (o == null) return null
        return LastTalk(
            from = o.optString("from"),
            fromName = o.optString("fromName"),
            toName = str(o, "toName"),
            ts = o.optLong("ts"),
            durMs = o.optLong("durMs"),
        )
    }

    fun channel(o: JSONObject) = Channel(
        id = o.getString("id"),
        name = o.optString("name"),
        open = o.optBoolean("open"),
        myRole = o.optString("myRole", "member"),
        myRoleLabel = o.optString("myRoleLabel", ""),
        canTalk = o.optBoolean("canTalk", true),
        online = o.optInt("online"),
        last = o.optLong("last"),
        lastTalk = if (o.has("lastTalk") && !o.isNull("lastTalk")) lastTalk(o.optJSONObject("lastTalk")) else null,
    )

    fun channels(a: JSONArray?) = map(a) { channel(it) }

    fun member(o: JSONObject) = Member(o.optString("id"), o.optString("name"), o.optString("role"))
    fun members(a: JSONArray?) = map(a) { member(it) }

    fun online(o: JSONObject) = OnlineUser(o.optString("id"), o.optString("name"), str(o, "channel"))
    fun onlines(a: JSONArray?) = map(a) { online(it) }

    fun dm(o: JSONObject) = DmSummary(
        conv = o.optString("conv"),
        peerId = o.optString("peerId"),
        peerName = o.optString("peerName"),
        last = o.optLong("last"),
        lastTalk = if (o.has("lastTalk") && !o.isNull("lastTalk")) lastTalk(o.optJSONObject("lastTalk")) else null,
    )
    fun dms(a: JSONArray?) = map(a) { dm(it) }

    fun loc(o: JSONObject) = LocEntry(
        id = o.optString("id"),
        name = o.optString("name"),
        online = o.optBoolean("online"),
        channel = str(o, "channel"),
        channelName = str(o, "channelName"),
        lat = o.optDouble("lat"),
        lng = o.optDouble("lng"),
        acc = int(o, "acc"),
        battery = int(o, "battery"),
        charging = bool(o, "charging"),
        ts = o.optLong("ts"),
    )
    fun locs(a: JSONArray?) = map(a) { loc(it) }

    fun alert(o: JSONObject) = Alert(
        id = o.optString("id"),
        userId = o.optString("userId"),
        name = o.optString("name"),
        channel = str(o, "channel"),
        channelName = str(o, "channelName"),
        lat = dbl(o, "lat"),
        lng = dbl(o, "lng"),
        acc = int(o, "acc"),
        ts = o.optLong("ts"),
    )
    fun alerts(a: JSONArray?) = map(a) { alert(it) }

    fun chat(o: JSONObject) = ChatMessage(
        id = o.optString("id"),
        conv = o.optString("conv"),
        from = o.optString("from"),
        fromName = o.optString("fromName"),
        kind = o.optString("kind", "text"),
        text = str(o, "text"),
        photoId = str(o, "photoId"),
        mime = str(o, "mime"),
        ts = o.optLong("ts"),
    )
    fun chats(a: JSONArray?) = map(a) { chat(it) }

    private fun <T> map(a: JSONArray?, f: (JSONObject) -> T): List<T> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { f(a.getJSONObject(it)) }
    }
}
