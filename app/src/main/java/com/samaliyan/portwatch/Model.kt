package com.samaliyan.portwatch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One thing to check on a server: either ICMP ping or a TCP port. */
data class Probe(val port: Int?) {
    val isPing get() = port == null
    val label get() = if (port == null) "Ping" else "Port $port"
    val code get() = if (port == null) "ping" else "tcp:$port"
}

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val ports: List<Int>,
    val ping: Boolean,
) {
    fun targets(): List<Probe> =
        (if (ping) listOf(Probe(null)) else emptyList()) + ports.map { Probe(it) }
}

data class CheckResult(
    val serverId: String,
    val serverName: String,
    val target: Probe,
    val ok: Boolean,
    val ms: Long?,
    val detail: String,
    val time: Long = System.currentTimeMillis(),
    /** Not checked this round (e.g. ping while a VPN is on). Never counts as up or down. */
    val skipped: Boolean = false,
) {
    val key get() = key(serverId, target)

    companion object {
        fun key(serverId: String, target: Probe) = "$serverId|${target.code}"
    }
}

/** Live state shared between the UI and the background service. */
/** How checks can reach the network right now. */
enum class NetState {
    /** No VPN: normal checks. */
    Normal,
    /** VPN is on, but checks go directly over Wi-Fi/mobile data, around the VPN. */
    Direct,
    /** VPN is on and does not let PortWatch go around it: checks are paused. */
    Blocked,
}

object Live {
    val net = MutableStateFlow(NetState.Normal)
    val results = MutableStateFlow<Map<String, CheckResult>>(emptyMap())
    val checking = MutableStateFlow(false)
    val monitoring = MutableStateFlow(false)
    val lastRun = MutableStateFlow(0L)
}

/** Settings and server list, stored in SharedPreferences. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("portwatch", Context.MODE_PRIVATE)
    private fun states(ctx: Context) = ctx.getSharedPreferences("portwatch_state", Context.MODE_PRIVATE)

    fun servers(ctx: Context): List<Server> {
        val raw = prefs(ctx).getString("servers", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val p = o.optJSONArray("ports") ?: JSONArray()
                Server(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    ports = (0 until p.length()).map { p.getInt(it) },
                    ping = o.optBoolean("ping", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServers(ctx: Context, list: List<Server>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("host", s.host)
                put("ports", JSONArray(s.ports))
                put("ping", s.ping)
            })
        }
        prefs(ctx).edit().putString("servers", arr.toString()).apply()
    }

    fun intervalSec(ctx: Context) = prefs(ctx).getInt("interval", 60)
    fun setIntervalSec(ctx: Context, v: Int) = prefs(ctx).edit().putInt("interval", v).apply()

    fun timeoutMs(ctx: Context) = prefs(ctx).getInt("timeout", 3000)
    fun setTimeoutMs(ctx: Context, v: Int) = prefs(ctx).edit().putInt("timeout", v).apply()

    fun notifyRecovery(ctx: Context) = prefs(ctx).getBoolean("notifyRecovery", true)
    fun setNotifyRecovery(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notifyRecovery", v).apply()

    fun pausedNotified(ctx: Context) = prefs(ctx).getBoolean("pausedNotified", false)
    fun setPausedNotified(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("pausedNotified", v).apply()

    fun monitoring(ctx: Context) = prefs(ctx).getBoolean("monitoring", false)
    fun setMonitoring(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("monitoring", v).apply()

    /** Last known state of a check: true = up, false = down, null = never checked. */
    fun lastState(ctx: Context, key: String): Boolean? =
        if (states(ctx).contains(key)) states(ctx).getBoolean(key, false) else null

    fun setLastState(ctx: Context, key: String, ok: Boolean) =
        states(ctx).edit().putBoolean(key, ok).apply()

    fun forgetServer(ctx: Context, serverId: String) {
        val s = states(ctx)
        val e = s.edit()
        s.all.keys.filter { it.startsWith("$serverId|") }.forEach { e.remove(it) }
        e.apply()
    }
}
