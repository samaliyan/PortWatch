package com.samaliyan.portwatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Low-level network checks. */
object Checker {

    fun check(server: Server, target: Probe, timeoutMs: Int): CheckResult {
        val port = target.port
        return if (port == null) ping(server, target, timeoutMs) else tcp(server, target, port, timeoutMs)
    }

    /** Same idea as "telnet host port": open a TCP connection and close it. */
    private fun tcp(server: Server, target: Probe, port: Int, timeoutMs: Int): CheckResult {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(server.host, port), timeoutMs) }
            val ms = (System.nanoTime() - start) / 1_000_000
            CheckResult(server.id, server.name, target, true, ms, "Open")
        } catch (e: Exception) {
            CheckResult(server.id, server.name, target, false, null, describe(e))
        }
    }

    private val timeRegex = Regex("time[=<]([0-9.]+)")

    private fun ping(server: Server, target: Probe, timeoutMs: Int): CheckResult {
        val waitSec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        return try {
            val p = ProcessBuilder("ping", "-c", "1", "-W", waitSec.toString(), server.host)
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor((waitSec + 2).toLong(), TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return CheckResult(server.id, server.name, target, false, null, "Timeout")
            }
            val out = p.inputStream.bufferedReader().readText()
            if (p.exitValue() == 0) {
                val ms = timeRegex.find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
                CheckResult(server.id, server.name, target, true, ms, "Reply")
            } else {
                val detail = if (out.contains("unknown host", true)) "Unknown host" else "No reply"
                CheckResult(server.id, server.name, target, false, null, detail)
            }
        } catch (e: Exception) {
            // Fallback when the ping binary is not available.
            try {
                val start = System.nanoTime()
                val ok = InetAddress.getByName(server.host).isReachable(timeoutMs)
                val ms = (System.nanoTime() - start) / 1_000_000
                CheckResult(server.id, server.name, target, ok, if (ok) ms else null, if (ok) "Reply" else "No reply")
            } catch (e2: Exception) {
                CheckResult(server.id, server.name, target, false, null, describe(e2))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Timeout"
        is ConnectException -> if (e.message?.contains("refused", true) == true) "Refused" else "Connect failed"
        is NoRouteToHostException -> "No route"
        is UnknownHostException -> "Unknown host"
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
            val timeout = Store.timeoutMs(app)
            val results = coroutineScope {
                servers.flatMap { s ->
                    s.targets().map { t -> async(Dispatchers.IO) { Checker.check(s, t, timeout) } }
                }.awaitAll()
            }
            val db = HistoryDb.get(app)
            val map = Live.results.value.toMutableMap()
            for (r in results) {
                val prev = Store.lastState(app, r.key)
                if (prev != r.ok) {
                    db.add(r)
                    Store.setLastState(app, r.key, r.ok)
                    if (!r.ok) Notifier.down(app, r)
                    else if (prev == false && Store.notifyRecovery(app)) Notifier.up(app, r)
                }
                map[r.key] = r
            }
            Live.results.value = map
            Live.lastRun.value = System.currentTimeMillis()
        } finally {
            Live.checking.value = false
        }
    }
}
