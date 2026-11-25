package com.samaliyan.portwatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)
private val Gray = Color(0xFF9CA3AF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        if (Store.monitoring(this)) {
            try { MonitorService.start(this) } catch (_: Exception) { }
        }
        setContent { AppTheme { App() } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF60A5FA))
    else lightColorScheme(primary = Color(0xFF2563EB), background = Color(0xFFF8FAFC), surface = Color(0xFFF8FAFC))
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Screen { Main, History, Settings }

@Composable
private fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.Main) }
    var servers by remember { mutableStateOf(Store.servers(ctx)) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }

    when (screen) {
        Screen.Main -> MainScreen(
            servers = servers,
            onServersChange = { servers = it; Store.saveServers(ctx, it) },
            onOpen = { screen = it },
        )
        Screen.History -> HistoryScreen(onBack = { screen = Screen.Main })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
    }
}

// ───────────────────────── Main ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    servers: List<Server>,
    onServersChange: (List<Server>) -> Unit,
    onOpen: (Screen) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val results by Live.results.collectAsState()
    val checking by Live.checking.collectAsState()
    val monitoring by Live.monitoring.collectAsState()
    val lastRun by Live.lastRun.collectAsState()

    var editing by remember { mutableStateOf<Server?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PortWatch", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { onOpen(Screen.History) }) { Icon(Icons.Default.List, "History") }
                    IconButton(onClick = { onOpen(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Default.Add, "Add server")
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    servers = servers,
                    results = results,
                    monitoring = monitoring,
                    lastRun = lastRun,
                    checking = checking,
                    onToggle = { on -> if (on) MonitorService.start(ctx) else MonitorService.stop(ctx) },
                    onCheckAll = { scope.launch { Engine.check(ctx) } },
                )
            }
            if (servers.isEmpty()) {
                item {
                    Text(
                        "No servers yet. Tap + to add one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
            }
            items(servers, key = { it.id }) { s ->
                ServerCard(
                    server = s,
                    results = results,
                    checking = checking,
                    onCheck = { scope.launch { Engine.check(ctx, s) } },
                    onEdit = { editing = s; showEditor = true },
                    onDelete = { deleting = s },
                )
            }
        }
    }

    if (showEditor) {
        ServerDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { s ->
                val old = servers.find { it.id == s.id }
                val list = if (old != null) servers.map { if (it.id == s.id) s else it } else servers + s
                if (old != null) {
                    // Targets or host may have changed: start fresh for this server.
                    Store.forgetServer(ctx, s.id)
                    Live.results.value = Live.results.value.filterKeys { !it.startsWith("${s.id}|") }
                }
                onServersChange(list)
                showEditor = false
                scope.launch { Engine.check(ctx, s) }
            }
        )
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete server?") },
            text = { Text("${s.name} (${s.host}) will be removed. History is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.forgetServer(ctx, s.id)
                    Live.results.value = Live.results.value.filterKeys { !it.startsWith("${s.id}|") }
                    onServersChange(servers.filter { it.id != s.id })
                    deleting = null
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusCard(
    servers: List<Server>,
    results: Map<String, CheckResult>,
    monitoring: Boolean,
    lastRun: Long,
    checking: Boolean,
    onToggle: (Boolean) -> Unit,
    onCheckAll: () -> Unit,
) {
    val ctx = LocalContext.current
    val keys = servers.flatMap { s -> s.targets().map { CheckResult.key(s.id, it) } }
    val known = keys.mapNotNull { results[it] }
    val up = known.count { it.ok }
    val down = known.count { !it.ok }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Monitoring", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (monitoring) "On · every ${Store.intervalSec(ctx)}s" else "Off",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = monitoring, onCheckedChange = onToggle, enabled = servers.isNotEmpty())
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("Up", up.toString(), Green)
                Stat("Down", down.toString(), if (down > 0) Red else MaterialTheme.colorScheme.onSurface)
                Stat("Last check", if (lastRun == 0L) "—" else timeOnly(lastRun), MaterialTheme.colorScheme.onSurface)
            }
            val vpn = remember(lastRun, monitoring) { Checker.vpnActive(ctx) }
            if (vpn) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "VPN is on. Port checks go through the VPN, so results show what the VPN can reach.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB45309),
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onCheckAll,
                enabled = !checking && servers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…")
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check all")
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    server: Server,
    results: Map<String, CheckResult>,
    checking: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val rs = server.targets().map { it to results[CheckResult.key(server.id, it)] }
    val overall = when {
        rs.any { it.second == null } && rs.none { it.second?.ok == false } -> Gray
        rs.all { it.second?.ok == true } -> Green
        else -> Red
    }

    Card(onClick = { if (!checking) onCheck() }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(overall, 12)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit", Modifier.size(20.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(6.dp))
            rs.forEach { (t, r) ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(when (r?.ok) { true -> Green; false -> Red; null -> Gray }, 8)
                    Spacer(Modifier.width(10.dp))
                    Text(t.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            r == null -> "Not checked"
                            r.ok && r.ms != null -> "${r.ms} ms"
                            else -> r.detail
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r?.ok == false) Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerDialog(initial: Server?, onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var ports by remember { mutableStateOf(initial?.ports?.joinToString(", ") ?: "") }
    var ping by remember { mutableStateOf(initial?.ping ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("IP or hostname") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    ports, { ports = it }, label = { Text("Ports (e.g. 22, 443, 808)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ping, onCheckedChange = { ping = it })
                    Text("Ping")
                }
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parts = ports.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                val parsed = parts.map { it.toIntOrNull() }
                error = when {
                    host.isBlank() -> "Enter an IP or hostname."
                    parsed.any { it == null || it !in 1..65535 } -> "Ports must be numbers from 1 to 65535."
                    parsed.isEmpty() && !ping -> "Add at least one port or enable Ping."
                    else -> null
                }
                if (error == null) {
                    val list = parsed.filterNotNull().distinct()
                    val n = name.trim().ifEmpty { host }
                    onSave(
                        initial?.copy(name = n, host = host, ports = list, ping = ping)
                            ?: Server(name = n, host = host, ports = list, ping = ping)
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ───────────────────────── History ─────────────────────────

private data class HistRow(val e: Event, val downFor: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    val lastRun by Live.lastRun.collectAsState()
    var events by remember { mutableStateOf(db.list()) }
    var downOnly by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(lastRun) { events = db.list() }

    // For each "up" event, work out how long the outage lasted.
    val rows = remember(events) {
        val lastDown = HashMap<String, Long>()
        val asc = events.asReversed().map { e ->
            val k = e.serverId + "|" + e.target
            if (!e.ok) { lastDown[k] = e.time; HistRow(e, null) }
            else HistRow(e, lastDown.remove(k)?.let { e.time - it })
        }
        asc.asReversed()
    }
    val shown = if (downOnly) rows.filter { !it.e.ok } else rows

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) {
                        Icon(Icons.Default.Delete, "Clear history")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !downOnly, onClick = { downOnly = false }, label = { Text("All") })
                FilterChip(selected = downOnly, onClick = { downOnly = true }, label = { Text("Down only") })
            }
            if (shown.isEmpty()) {
                Text(
                    "No events yet. Changes between up and down are recorded here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
                items(shown) { row ->
                    val e = row.e
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp)) { Dot(if (e.ok) Green else Red, 10) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${e.serverName} · ${e.target}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (e.ok) "Up" + (row.downFor?.let { " · was down ${duration(it)}" } ?: "")
                                else "Down · ${e.detail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (e.ok) Green else Red,
                            )
                        }
                        Text(dateTime(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All recorded events will be deleted.") },
            confirmButton = {
                TextButton(onClick = { db.clear(); events = emptyList(); confirmClear = false }) { Text("Clear", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

// ───────────────────────── Settings ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var interval by remember { mutableStateOf(Store.intervalSec(ctx).toString()) }
    var timeout by remember { mutableStateOf(Store.timeoutMs(ctx).toString()) }
    var recovery by remember { mutableStateOf(Store.notifyRecovery(ctx)) }
    var saved by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    // Re-check battery state when the user returns from the system dialog.
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryOk = isIgnoringBattery(ctx)
    }

    val intervalVal = interval.toIntOrNull()
    val timeoutVal = timeout.toIntOrNull()
    val intervalErr = intervalVal == null || intervalVal < 10
    val timeoutErr = timeoutVal == null || timeoutVal !in 500..30000

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                interval, { interval = it; saved = false },
                label = { Text("Check interval (seconds)") },
                supportingText = { Text(if (intervalErr) "Minimum 10 seconds" else "How often to check while monitoring is on") },
                isError = intervalErr, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                timeout, { timeout = it; saved = false },
                label = { Text("Timeout (ms)") },
                supportingText = { Text(if (timeoutErr) "Between 500 and 30000" else "Wait time before a check counts as failed") },
                isError = timeoutErr, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notify when back online")
                    Text("Alert also when a server recovers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = recovery, onCheckedChange = { recovery = it; saved = false })
            }
            Button(
                onClick = {
                    Store.setIntervalSec(ctx, intervalVal!!)
                    Store.setTimeoutMs(ctx, timeoutVal!!)
                    Store.setNotifyRecovery(ctx, recovery)
                    if (Live.monitoring.value) MonitorService.start(ctx) // refresh status notification
                    saved = true
                },
                enabled = !intervalErr && !timeoutErr,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saved) "Saved" else "Save") }

            HorizontalDivider()

            Text("Background", style = MaterialTheme.typography.titleSmall)
            Text(
                if (batteryOk) "Battery optimization is off for PortWatch. Monitoring can run reliably."
                else "Android may pause monitoring to save battery. Turn battery optimization off for reliable alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = if (batteryOk) Green else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!batteryOk) {
                OutlinedButton(onClick = { batteryLauncher.launch(ignoreBatteryIntent(ctx)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Disable battery optimization")
                }
            }
        }
    }

}

// ───────────────────────── Helpers ─────────────────────────

private fun isIgnoringBattery(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
private fun ignoreBatteryIntent(ctx: Context): Intent {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    return if (direct.resolveActivity(ctx.packageManager) != null) direct
    else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

private fun timeOnly(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))
private fun dateTime(t: Long) = SimpleDateFormat("yyyy-MM-dd\nHH:mm:ss", Locale.US).format(Date(t))

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 86400}d ${(s % 86400) / 3600}h"
    }
}
