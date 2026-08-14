package com.broliker.multisession.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val HOME_URL = "https://m.facebook.com/"
private const val PAGE_SIZE = 50

// ─── Data ───────────────────────────────────────────────────────────────────

private data class SessionMeta(
    val id: String,
    val profileName: String,
    val name: String,
    val group: String,
    val createdAt: Long,
    val lastOpenedAt: Long = 0L,
    val archived: Boolean = false,
    val serialNumber: Int = 0,
)

// ─── Store ───────────────────────────────────────────────────────────────────

private class SessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("bro_sessions", Context.MODE_PRIVATE)

    fun load(): List<SessionMeta> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SessionMeta(
                        id = o.getString("id"),
                        profileName = o.getString("profileName"),
                        name = o.getString("name"),
                        group = o.optString("group"),
                        createdAt = o.optLong("createdAt"),
                        lastOpenedAt = o.optLong("lastOpenedAt"),
                        archived = o.optBoolean("archived", false),
                        serialNumber = o.optInt("serialNumber", 0),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(items: List<SessionMeta>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("profileName", s.profileName)
                put("name", s.name)
                put("group", s.group)
                put("createdAt", s.createdAt)
                put("lastOpenedAt", s.lastOpenedAt)
                put("archived", s.archived)
                put("serialNumber", s.serialNumber)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun nextSerialNumber(sessions: List<SessionMeta>): Int {
        return (sessions.maxOfOrNull { it.serialNumber } ?: 0) + 1
    }

    // ── Export ──────────────────────────────────────────────────────────────

    fun exportToJson(sessions: List<SessionMeta>): String {
        val root = JSONObject()
        root.put("app", "Bro Liker")
        root.put("version", 1)
        root.put("exported_at", System.currentTimeMillis())
        val arr = JSONArray()
        sessions.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("profileName", s.profileName)
                put("name", s.name)
                put("group", s.group)
                put("createdAt", s.createdAt)
                put("lastOpenedAt", s.lastOpenedAt)
                put("archived", s.archived)
                put("serialNumber", s.serialNumber)
                // collect cookies from both facebook.com domains
                val cookieStr = buildString {
                    listOf(
                        "https://www.facebook.com",
                        "https://m.facebook.com",
                        "https://facebook.com",
                    ).forEach { domain ->
                        val raw = runCatching {
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                ProfileStore.getInstance()
                                    .getProfile(s.profileName)
                                    ?.cookieManager
                                    ?.getCookie(domain)
                            } else {
                                CookieManager.getInstance().getCookie(domain)
                            }
                        }.getOrNull()
                        if (!raw.isNullOrBlank()) append(raw).append("; ")
                    }
                }.trim().trimEnd(';').trim()
                put("cookies", cookieStr)
            }
            arr.put(obj)
        }
        root.put("sessions", arr)
        return root.toString(2)
    }

    fun importFromJson(json: String): List<SessionMeta> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("sessions")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SessionMeta(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        profileName = o.optString("profileName").ifBlank { "profile_${UUID.randomUUID()}" },
                        name = o.optString("name", "Imported Session"),
                        group = o.optString("group"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        lastOpenedAt = o.optLong("lastOpenedAt"),
                        archived = o.optBoolean("archived", false),
                        serialNumber = o.optInt("serialNumber", 0),
                    )
                )
            }
        }
    }

    fun restoreCookies(sessions: List<SessionMeta>, json: String) {
        runCatching {
            val root = JSONObject(json)
            val arr = root.getJSONArray("sessions")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val profileName = o.optString("profileName")
                val cookiesRaw = o.optString("cookies")
                if (cookiesRaw.isBlank()) continue
                val domains = listOf(
                    "https://www.facebook.com",
                    "https://m.facebook.com",
                    "https://facebook.com",
                )
                val pairs = cookiesRaw.split(";").map { it.trim() }.filter { it.isNotBlank() }
                val cm = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    runCatching {
                        ProfileStore.getInstance().getOrCreateProfile(profileName)
                        ProfileStore.getInstance().getProfile(profileName)?.cookieManager
                    }.getOrNull()
                } else null
                pairs.forEach { pair ->
                    domains.forEach { domain ->
                        runCatching {
                            if (cm != null) {
                                cm.setCookie(domain, pair)
                            } else {
                                CookieManager.getInstance().setCookie(domain, pair)
                            }
                        }
                    }
                }
                cm?.flush() ?: CookieManager.getInstance().flush()
            }
        }
    }
}

// ─── Cookie helpers ──────────────────────────────────────────────────────────

private fun getCookiesAsJson(
    session: SessionMeta,
    multiProfileSupported: Boolean,
): String {
    val domains = listOf(
        "https://www.facebook.com",
        "https://m.facebook.com",
        "https://facebook.com",
    )
    val merged = mutableMapOf<String, String>()
    domains.forEach { domain ->
        val raw = runCatching {
            if (multiProfileSupported) {
                ProfileStore.getInstance()
                    .getProfile(session.profileName)
                    ?.cookieManager
                    ?.getCookie(domain)
            } else {
                CookieManager.getInstance().getCookie(domain)
            }
        }.getOrNull()
        if (!raw.isNullOrBlank()) {
            raw.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val k = pair.substring(0, idx).trim()
                    val v = pair.substring(idx + 1).trim()
                    merged[k] = v
                }
            }
        }
    }
    val json = JSONObject()
    merged.forEach { (k, v) -> json.put(k, v) }
    return json.toString(2)
}

private fun copySafeSessionInfo(
    context: Context,
    session: SessionMeta,
    currentUrl: String,
    pageTitle: String,
) {
    val json = JSONObject().apply {
        put("app", "Bro Liker")
        put("session_id", session.id)
        put("session_name", session.name)
        put("profile_name", session.profileName)
        put("group", session.group)
        put("current_url", currentUrl)
        put("page_title", pageTitle)
        put("created_at", session.createdAt)
        put("last_opened_at", session.lastOpenedAt)
    }.toString(2)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Session Info", json))
    Toast.makeText(context, "Session info copied", Toast.LENGTH_SHORT).show()
}

private fun copyCookieInfo(
    context: Context,
    session: SessionMeta,
    multiProfileSupported: Boolean,
) {
    val json = getCookiesAsJson(session, multiProfileSupported)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Cookie Info", json))
    Toast.makeText(context, "Cookie info copied", Toast.LENGTH_SHORT).show()
}

// ─── Screens enum ────────────────────────────────────────────────────────────

private enum class Screen { HOME, SETTINGS }

// ─── Root Composable ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroLikerApp() {
    val context = LocalContext.current.applicationContext
    val store = remember { SessionStore(context) }
    var sessions by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<SessionMeta?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    // Browser screen takes over full composable
    if (selected != null) {
        BrowserScreen(
            session = selected!!,
            onBack = { selected = null },
            onCreateAnother = { showCreate = true },
        )
        if (showCreate) {
            CreateSessionDialog(
                groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
                nextSerial = store.nextSerialNumber(sessions),
                onDismiss = { showCreate = false },
            ) { name, group ->
                val serial = store.nextSerialNumber(sessions)
                val meta = SessionMeta(
                    id = UUID.randomUUID().toString(),
                    profileName = "profile_${UUID.randomUUID()}",
                    name = name,
                    group = group,
                    createdAt = System.currentTimeMillis(),
                    serialNumber = serial,
                )
                sessions = sessions + meta
                store.save(sessions)
                selected = meta
                showCreate = false
            }
        }
        return
    }

    when (currentScreen) {
        Screen.HOME -> HomeScreen(
            sessions = sessions,
            store = store,
            onOpenSession = { session ->
                val updated = sessions.map {
                    if (it.id == session.id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
                }
                sessions = updated
                store.save(updated)
                selected = updated.first { it.id == session.id }
            },
            onSessionsChanged = { sessions = it; store.save(it) },
            onCreateNew = { showCreate = true },
            onGoSettings = { currentScreen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            sessions = sessions,
            store = store,
            onBack = { currentScreen = Screen.HOME },
            onSessionsChanged = { sessions = it; store.save(it) },
        )
    }

    if (showCreate && selected == null) {
        CreateSessionDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            nextSerial = store.nextSerialNumber(sessions),
            onDismiss = { showCreate = false },
        ) { name, group ->
            val serial = store.nextSerialNumber(sessions)
            val meta = SessionMeta(
                id = UUID.randomUUID().toString(),
                profileName = "profile_${UUID.randomUUID()}",
                name = name,
                group = group,
                createdAt = System.currentTimeMillis(),
                serialNumber = serial,
            )
            sessions = sessions + meta
            store.save(sessions)
            selected = meta
            showCreate = false
        }
    }
}

// ─── Home Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    sessions: List<SessionMeta>,
    store: SessionStore,
    onOpenSession: (SessionMeta) -> Unit,
    onSessionsChanged: (List<SessionMeta>) -> Unit,
    onCreateNew: () -> Unit,
    onGoSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filterGroup by remember { mutableStateOf("All") }
    var currentPage by remember { mutableIntStateOf(0) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showRename by remember { mutableStateOf<SessionMeta?>(null) }
    var showBulkGroup by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showGroupManager by remember { mutableStateOf(false) }

    val groups = listOf("All", "Ungrouped") +
        sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

    val filtered = sessions
        .filter { !it.archived }
        .filter { s ->
            val gOk = when (filterGroup) {
                "All" -> true
                "Ungrouped" -> s.group.isBlank()
                else -> s.group == filterGroup
            }
            gOk && (query.isBlank() || s.name.contains(query, ignoreCase = true))
        }
        .sortedBy { it.serialNumber }

    val totalPages = maxOf(1, (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE)
    if (currentPage >= totalPages) currentPage = 0
    val pageItems = filtered.drop(currentPage * PAGE_SIZE).take(PAGE_SIZE)
    val allPageSelected = pageItems.isNotEmpty() && pageItems.all { it.id in selectedIds }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text("Bro Liker", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "${sessions.count { !it.archived }} sessions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCreateNew) {
                        Icon(Icons.Default.Add, "New session")
                    }
                    IconButton(onClick = { showGroupManager = true }) {
                        Icon(Icons.Default.Folder, "Groups")
                    }
                    IconButton(onClick = onGoSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                    IconButton(onClick = { showMore = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text(if (allPageSelected) "Deselect all" else "Select all") },
                            leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                            onClick = {
                                selectedIds = if (allPageSelected) emptySet()
                                else pageItems.map { it.id }.toSet()
                                showMore = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear selection") },
                            onClick = { selectedIds = emptySet(); showMore = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Bulk move to group") },
                            leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { showBulkGroup = true; showMore = false },
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; currentPage = 0 },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search sessions") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(8.dp))

            // Group chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(groups) { group ->
                    val isSelected = filterGroup == group
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { filterGroup = group; currentPage = 0 },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            if (group != "All" && group != "Ungrouped") {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                group,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Selection info bar
            if (selectedIds.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selectedIds.size} selected",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showBulkGroup = true }) {
                            Text("Move to Group")
                        }
                        TextButton(onClick = { selectedIds = emptySet() }) {
                            Text("Clear")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Session list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (pageItems.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No sessions found", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onCreateNew) { Text("Create Session") }
                            }
                        }
                    }
                } else {
                    items(pageItems, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            isSelected = session.id in selectedIds,
                            onSelect = {
                                selectedIds = if (session.id in selectedIds)
                                    selectedIds - session.id else selectedIds + session.id
                            },
                            onOpen = { onOpenSession(session) },
                            onRename = { showRename = session },
                            onDelete = {
                                val updated = sessions.filterNot { it.id == session.id }
                                onSessionsChanged(updated)
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                    runCatching {
                                        ProfileStore.getInstance().deleteProfile(session.profileName)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // Pagination
            if (totalPages > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0,
                        modifier = Modifier.height(36.dp),
                    ) { Text("← Prev") }

                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Page ${currentPage + 1} / $totalPages",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                    Text(
                        "  (${filtered.size} total)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1,
                        modifier = Modifier.height(36.dp),
                    ) { Text("Next →") }
                }
                Spacer(Modifier.height(4.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Rename dialog
    showRename?.let { current ->
        RenameDialog(current = current.name, onDismiss = { showRename = null }) { newName ->
            val updated = sessions.map { if (it.id == current.id) it.copy(name = newName) else it }
            onSessionsChanged(updated)
            showRename = null
        }
    }

    // Bulk group dialog
    if (showBulkGroup) {
        BulkGroupDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            onDismiss = { showBulkGroup = false },
        ) { group ->
            val updated = sessions.map { if (it.id in selectedIds) it.copy(group = group) else it }
            onSessionsChanged(updated)
            selectedIds = emptySet()
            showBulkGroup = false
        }
    }

    // Group manager
    if (showGroupManager) {
        GroupManagerDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            currentFilter = filterGroup,
            sessionCounts = buildMap {
                sessions.filter { !it.archived }.groupBy { it.group.ifBlank { "__ungrouped__" } }
                    .forEach { (k, v) -> put(k, v.size) }
            },
            allCount = sessions.count { !it.archived },
            onDismiss = { showGroupManager = false },
            onFilter = { filterGroup = it; currentPage = 0; showGroupManager = false },
            onRenameGroup = { old, new ->
                val updated = sessions.map { if (it.group == old) it.copy(group = new) else it }
                onSessionsChanged(updated)
            },
            onDeleteGroup = { grp ->
                val updated = sessions.map { if (it.group == grp) it.copy(group = "") else it }
                onSessionsChanged(updated)
            },
        )
    }
}

// ─── Session Card ─────────────────────────────────────────────────────────────

@Composable
private fun SessionCard(
    session: SessionMeta,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onSelect() })

            // Avatar circle
            Box(
                Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    session.name.firstOrNull()?.uppercaseChar()?.toString() ?: "S",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    session.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        if (session.group.isNotBlank()) append("📁 ${session.group}  ")
                        append("#${session.serialNumber}")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Open button
            FilledTonalButton(
                onClick = onOpen,
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) { Text("Open", fontSize = 12.sp) }

            Spacer(Modifier.width(2.dp))

            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─── Settings Screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    sessions: List<SessionMeta>,
    store: SessionStore,
    onBack: () -> Unit,
    onSessionsChanged: (List<SessionMeta>) -> Unit,
) {
    val context = LocalContext.current
    var importStatus by remember { mutableStateOf("") }
    var exportStatus by remember { mutableStateOf("") }
    var showImportConfirm by remember { mutableStateOf<String?>(null) }

    // Export launcher — creates a file via SAF
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            // flush all cookies first
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                sessions.forEach { s ->
                    runCatching {
                        ProfileStore.getInstance().getProfile(s.profileName)?.cookieManager?.flush()
                    }
                }
            } else {
                CookieManager.getInstance().flush()
            }
            val json = store.exportToJson(sessions)
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            exportStatus = "✅ Exported ${sessions.size} sessions successfully"
        }.onFailure { exportStatus = "❌ Export failed: ${it.message}" }
    }

    // Import launcher — picks a JSON file
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: return@rememberLauncherForActivityResult
            showImportConfirm = json
        }.onFailure { importStatus = "❌ Failed to read file: ${it.message}" }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Backup & Restore",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Export saves all sessions with cookies. Import restores them including login state (if cookies are still valid).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Export Sessions", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${sessions.count { !it.archived }} active sessions will be exported with cookies",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                                    .format(Date())
                                exportLauncher.launch("broliker_backup_$timestamp.json")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("📤  Export to File")
                        }
                        if (exportStatus.isNotBlank()) {
                            Text(exportStatus, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Import Sessions", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Pick a previously exported .json backup file",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("📥  Import from File")
                        }
                        if (importStatus.isNotBlank()) {
                            Text(importStatus, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Divider()
                Spacer(Modifier.height(4.dp))
                Text(
                    "ℹ️  Cookie restore works best when done on the same device shortly after export.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }
        }
    }

    // Import confirm dialog
    showImportConfirm?.let { json ->
        val count = runCatching {
            JSONObject(json).getJSONArray("sessions").length()
        }.getOrDefault(0)

        AlertDialog(
            onDismissRequest = { showImportConfirm = null },
            title = { Text("Import $count sessions?") },
            text = {
                Text(
                    "This will ADD the imported sessions to your existing ones and attempt to restore cookies. Existing sessions won't be deleted.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(onClick = {
                    runCatching {
                        val imported = store.importFromJson(json)
                        // Merge: skip if same profileName already exists
                        val existingProfiles = sessions.map { it.profileName }.toSet()
                        val toAdd = imported.filter { it.profileName !in existingProfiles }
                        val merged = sessions + toAdd
                        onSessionsChanged(merged)
                        store.restoreCookies(toAdd, json)
                        importStatus = "✅ Imported ${toAdd.size} sessions. Cookies restored!"
                    }.onFailure { importStatus = "❌ Import failed: ${it.message}" }
                    showImportConfirm = null
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

// ─── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun CreateSessionDialog(
    groups: List<String>,
    nextSerial: Int,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("My Session $nextSerial") }
    var group by remember { mutableStateOf("") }
    var showGroupSuggestions by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    group, { group = it; showGroupSuggestions = groups.isNotEmpty() },
                    label = { Text("Group (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showGroupSuggestions && groups.isNotEmpty()) {
                    Text("Existing groups:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(groups) { g ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { group = g },
                            ) {
                                Text(g, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp)
                            }
                        }
                    }
                }
                Text(
                    "Each session has its own isolated browser profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), group.trim()) }) {
                Text("Create & Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value, { value = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BulkGroupDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var group by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    group, { group = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isNotEmpty()) {
                    Text("Quick pick:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(groups) { g ->
                            Surface(
                                color = if (group == g) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { group = g },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (group == g) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(g, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = group.isNotBlank(), onClick = { onApply(group.trim()) }) {
                Text("Move")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GroupManagerDialog(
    groups: List<String>,
    currentFilter: String,
    sessionCounts: Map<String, Int>,
    allCount: Int,
    onDismiss: () -> Unit,
    onFilter: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Groups") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // All
                item {
                    GroupRow(
                        name = "All",
                        count = allCount,
                        isActive = currentFilter == "All",
                        onClick = { onFilter("All") },
                        onRename = null,
                        onDelete = null,
                    )
                }
                // Ungrouped
                item {
                    val cnt = sessionCounts["__ungrouped__"] ?: 0
                    GroupRow(
                        name = "Ungrouped",
                        count = cnt,
                        isActive = currentFilter == "Ungrouped",
                        onClick = { onFilter("Ungrouped") },
                        onRename = null,
                        onDelete = null,
                    )
                }
                if (groups.isNotEmpty()) {
                    item { Divider(Modifier.padding(vertical = 4.dp)) }
                }
                items(groups) { group ->
                    if (renamingGroup == group) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                renameValue, { renameValue = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                label = { Text("New name") },
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = {
                                if (renameValue.isNotBlank()) {
                                    onRenameGroup(group, renameValue.trim())
                                }
                                renamingGroup = null
                            }) {
                                Icon(Icons.Default.Check, "Save", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { renamingGroup = null }) {
                                Icon(Icons.Default.Close, "Cancel")
                            }
                        }
                    } else {
                        GroupRow(
                            name = group,
                            count = sessionCounts[group] ?: 0,
                            isActive = currentFilter == group,
                            onClick = { onFilter(group) },
                            onRename = {
                                renamingGroup = group
                                renameValue = group
                            },
                            onDelete = { onDeleteGroup(group) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun GroupRow(
    name: String,
    count: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (name == "All" || name == "Ungrouped") Icons.Default.Home else Icons.Default.FolderOpen,
                null,
                modifier = Modifier.size(18.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(name, Modifier.weight(1f), fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                "$count",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRename != null) {
                IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ─── Browser Screen ───────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    session: SessionMeta,
    onBack: () -> Unit,
    onCreateAnother: () -> Unit,
) {
    val context = LocalContext.current
    var rootRef by remember { mutableStateOf<FrameLayout?>(null) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(HOME_URL) }
    var urlBarText by remember { mutableStateOf(HOME_URL) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("Facebook") }

    val multiProfileSupported = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    fun profileStore() = ProfileStore.getInstance()

    fun configureProfile(webView: WebView) {
        if (multiProfileSupported) {
            runCatching {
                profileStore().getOrCreateProfile(session.profileName)
                WebViewCompat.setProfile(webView, session.profileName)
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (multiProfileSupported) {
            runCatching {
                profileStore().getProfile(session.profileName)?.cookieManager?.apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }
            }
        } else {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    fun flushCookies() {
        if (multiProfileSupported) {
            runCatching {
                profileStore().getProfile(session.profileName)?.cookieManager?.flush()
            }
        } else {
            CookieManager.getInstance().flush()
        }
    }

    fun makeClient(webView: WebView) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url; urlBarText = url; isLoading = true; errorMsg = null
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }
        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url; urlBarText = url; isLoading = false
            canBack = view.canGoBack(); canForward = view.canGoForward()
            flushCookies()
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                isLoading = false
                errorMsg = "Error ${error.errorCode}: ${error.description}"
            }
        }
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            isLoading = false
            errorMsg = if (detail.didCrash()) "Renderer crashed. Tap Reload." else "Renderer stopped. Tap Reload."
            return true
        }
    }

    fun makeChromeClient(webView: WebView) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loadingProgress = newProgress; isLoading = newProgress < 100
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }
        override fun onReceivedTitle(view: WebView, title: String) {
            if (title.isNotBlank()) pageTitle = title
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val root = rootRef ?: return false
            val child = WebView(view.context)
            configureProfile(child)
            child.webViewClient = makeClient(child)
            child.webChromeClient = makeChromeClient(child)
            child.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            activeWebView?.visibility = android.view.View.GONE
            root.addView(child); activeWebView = child
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = child; resultMsg.sendToTarget()
            return true
        }
    }

    fun navigateTo(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return
        val url = when {
            trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true) -> trimmed
            trimmed.contains(".") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        }
        activeWebView?.loadUrl(url); urlBarText = url
    }

    fun closePopup(): Boolean {
        val root = rootRef ?: return false
        val current = activeWebView ?: return false
        if (root.childCount <= 1) return false
        root.removeView(current); current.stopLoading(); current.destroy()
        val parent = root.getChildAt(root.childCount - 1) as? WebView
        parent?.let {
            it.visibility = android.view.View.VISIBLE; activeWebView = it
            canBack = it.canGoBack(); canForward = it.canGoForward()
            currentUrl = it.url ?: HOME_URL; urlBarText = currentUrl
        }
        return true
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!closePopup()) { flushCookies(); onBack() }
                        }) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    title = {
                        OutlinedTextField(
                            value = urlBarText,
                            onValueChange = { urlBarText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("URL or search...", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateTo(urlBarText) }),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    },
                    actions = {
                        IconButton(onClick = { errorMsg = null; activeWebView?.reload() }) {
                            Icon(Icons.Default.Refresh, "Reload")
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Back") },
                                enabled = canBack,
                                leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                                onClick = { activeWebView?.goBack(); showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Forward") },
                                enabled = canForward,
                                leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                                onClick = { activeWebView?.goForward(); showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Facebook Home") },
                                leadingIcon = { Icon(Icons.Default.Home, null) },
                                onClick = { navigateTo(HOME_URL); showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Create another session") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { showMenu = false; onCreateAnother() },
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Copy Session Info") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    showMenu = false; flushCookies()
                                    copySafeSessionInfo(context, session, currentUrl, pageTitle)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Cookie Info") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    showMenu = false; flushCookies()
                                    copyCookieInfo(context, session, multiProfileSupported)
                                },
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Close") },
                                onClick = { showMenu = false; flushCookies(); onBack() },
                            )
                        }
                    },
                )
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { loadingProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).also { root ->
                        rootRef = root
                        val initial = WebView(ctx)
                        configureProfile(initial)
                        initial.webViewClient = makeClient(initial)
                        initial.webChromeClient = makeChromeClient(initial)
                        initial.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        root.addView(initial); activeWebView = initial
                        initial.loadUrl(HOME_URL)
                    }
                },
                update = { root ->
                    rootRef = root
                    val child = root.getChildAt(root.childCount - 1) as? WebView
                    if (child != null && activeWebView == null) activeWebView = child
                },
                onRelease = { root ->
                    for (i in root.childCount - 1 downTo 0) {
                        (root.getChildAt(i) as? WebView)?.let { web ->
                            runCatching { web.stopLoading(); web.loadUrl("about:blank"); web.removeAllViews(); web.destroy() }
                        }
                    }
                    root.removeAllViews(); activeWebView = null; rootRef = null
                },
            )
            errorMsg?.let { err ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Page failed to load", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(currentUrl, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { errorMsg = null; activeWebView?.reload() }) { Text("Reload") }
                }
            }
        }
    }
}