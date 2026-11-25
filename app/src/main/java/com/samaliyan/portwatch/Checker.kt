package com.samaliyan.portwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Decides which network the checks use, so a VPN can never fake a result. */
object Net {

    class Route(val state: NetState, val network: Network?)

    fun route(ctx: Context): Route {
        if (!vpnActive(ctx)) return Route(NetState.Normal, null)
        val direct = underlyingNetwork(ctx) ?: return Route(NetState.Blocked, null)
        // The VPN decides whether apps may go around it. Test it with a real socket.
        return try {
            Socket().use { direct.bindSocket(it) }
            Route(NetState.Direct, direct)
        } catch (_: Exception) {
            Route(NetState.Blocked, null)
        }
    }

    fun state(ctx: Context): NetState = route(ctx).state

    @Suppress("DEPRECATION")
    private fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val any = cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (any) return true
        }
        // Fallback: some VPN apps are only visible as a network interface.
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
                ni.isUp && listOf("tun", "ppp", "wg", "ipsec", "utun").any { ni.name.startsWith(it) }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** The real Wi-Fi / mobile / ethernet network under the VPN. Wi-Fi first. */
    @Suppress("DEPRECATION")
    private fun underlyingNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val rank = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> return@mapNotNull null
            }
            rank to n
        }
        return candidates.minByOrNull { it.first }?.second
    }
}

/** Low-level network checks. */
object Checker {

    /** [network] is set when a VPN is on: the check is forced onto that real network. */
    fun check(server: Server, probe: Probe, timeoutMs: Int, network: Network?): CheckResult {
        val port = probe.port
        return when {
            port != null -> tcp(server, probe, port, timeoutMs, network)
            // The ping tool cannot be forced around a VPN, so it would report the VPN, not the server.
            network != null -> CheckResult(server.id, server.name, probe, false, null, "Skipped: VPN on", skipped = true)
            else -> ping(server, probe, timeoutMs)
        }
    }

    /**
     * Same idea as "telnet host port". After connecting we also wait for the first answer:
     * an immediate close or reset means a middlebox accepted the connection, not the server.
     */
    private fun tcp(server: Server, probe: Probe, port: Int, timeoutMs: Int, network: Network?): CheckResult {
        val start = System.nanoTime()
        return try {
            val address = network?.getByName(server.host) ?: InetAddress.getByName(server.host)
            Socket().use { socket ->
                network?.bindSocket(socket)
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                val connectMs = (System.nanoTime() - start) / 1_000_000
                socket.soTimeout = timeoutMs
                try {
                    if (socket.getInputStream().read() == -1) {
                        return CheckResult(server.id, server.name, probe, false, null, "Closed")
                    }
                } catch (_: SocketTimeoutException) {
                    // Connected and silent: a normal open port waiting for the client.
                }
                CheckResult(server.id, server.name, probe, true, connectMs, "Open")
            }
        } catch (e: Exception) {
            CheckResult(server.id, server.name, probe, false, null, describe(e))
        }
    }

    private val timeRegex = Regex("time[=<]([0-9.]+)")

    private fun ping(server: Server, probe: Probe, timeoutMs: Int): CheckResult {
        val waitSec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        return try {
            val p = ProcessBuilder("ping", "-c", "1", "-W", waitSec.toString(), server.host)
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor((waitSec + 2).toLong(), TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return CheckResult(server.id, server.name, probe, false, null, "Timeout")
            }
            val out = p.inputStream.bufferedReader().readText()
            if (p.exitValue() == 0) {
                val ms = timeRegex.find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
                CheckResult(server.id, server.name, probe, true, ms, "Reply")
            } else {
                val detail = if (out.contains("unknown host", true)) "Unknown host" else "No reply"
                CheckResult(server.id, server.name, probe, false, null, detail)
            }
        } catch (e: Exception) {
            // Fallback when the ping binary is not available.
            try {
                val start = System.nanoTime()
                val ok = InetAddress.getByName(server.host).isReachable(timeoutMs)
                val ms = (System.nanoTime() - start) / 1_000_000
                CheckResult(server.id, server.name, probe, ok, if (ok) ms else null, if (ok) "Reply" else "No reply")
            } catch (e2: Exception) {
                CheckResult(server.id, server.name, probe, false, null, describe(e2))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Timeout"
        is ConnectException -> if (e.message?.contains("refused", true) == true) "Refused" else "Connect failed"
        is NoRouteToHostException -> "No route"
        is UnknownHostException -> "Unknown host"
        is SocketException -> if (e.message?.contains("reset", true) == true) "Closed" else "Connection error"
        else -> e.javaClass.simpleName
    }
}

/** Runs checks, records state changes and sends alerts. Used by the UI and the service. */
object Engine {
    private val mutex = Mutex()

    suspend fun check(ctx: Context, only: Server? = null) = mutex.withLock {
        val app = ctx.applicationContext
        val servers = if (only != null) listOf(only) else Store.servers(app)
        if (servers.isEmpty()) return@withLock
        Live.checking.value = true
        try {
            val route = Net.route(app)
            Live.net.value = route.state
            val map = Live.results.value.toMutableMap()

            if (route.state == NetState.Blocked) {
                // Never report results we cannot trust. No history, no down alerts.
                for (s in servers) for (p in s.targets()) {
                    val r = CheckResult(s.id, s.name, p, false, null, "Paused: VPN on", skipped = true)
                    map[r.key] = r
                }
                Live.results.value = map
                Live.lastRun.value = System.currentTimeMillis()
                if (!Store.pausedNotified(app)) {
                    Notifier.paused(app)
                    Store.setPausedNotified(app, true)
                }
                return@withLock
            }
            Store.setPausedNotified(app, false)

            val timeout = Store.timeoutMs(app)
            val results = coroutineScope {
                servers.flatMap { s ->
                    s.targets().map { p -> async(Dispatchers.IO) { Checker.check(s, p, timeout, route.network) } }
                }.awaitAll()
            }
            val db = HistoryDb.get(app)
            for (r in results) {
                map[r.key] = r
                if (r.skipped) continue
                val prev = Store.lastState(app, r.key)
                if (prev != r.ok) {
                    db.add(r)
                    Store.setLastState(app, r.key, r.ok)
                    if (!r.ok) Notifier.down(app, r)
                    else if (prev == false && Store.notifyRecovery(app)) Notifier.up(app, r)
                }
            }
            Live.results.value = map
            Live.lastRun.value = System.currentTimeMillis()
        } finally {
            Live.checking.value = false
        }
    }
}
