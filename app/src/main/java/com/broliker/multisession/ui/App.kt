package com.broliker.multisession.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val HOME_URL = "https://m.facebook.com/"

private data class SessionMeta(
    val id: String,
    val profileName: String,
    val name: String,
    val group: String,
    val createdAt: Long,
    val lastOpenedAt: Long = 0L,
    val archived: Boolean = false,
)

private class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("bro_sessions", Context.MODE_PRIVATE)
    fun load(): List<SessionMeta> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(SessionMeta(
                    id = o.getString("id"),
                    profileName = o.getString("profileName"),
                    name = o.getString("name"),
                    group = o.optString("group"),
                    createdAt = o.optLong("createdAt"),
                    lastOpenedAt = o.optLong("lastOpenedAt"),
                    archived = o.optBoolean("archived", false),
                ))
            }
        }
    }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }

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
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroLikerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val store = remember { SessionStore(context) }
    var sessions by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<SessionMeta?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf<SessionMeta?>(null) }
    var showGroup by remember { mutableStateOf(false) }
    var showBulkGroup by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    if (selected != null) {
        BrowserScreen(
            session = selected!!,
            onBack = { selected = null },
            onCreateAnother = { showCreate = true },
        )
        if (showCreate) {
            CreateSessionDialog(
                groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
                onDismiss = { showCreate = false },
            ) { name, group ->
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    val p = "profile_${UUID.randomUUID()}"
                    val meta = SessionMeta(UUID.randomUUID().toString(), p, name, group, System.currentTimeMillis())
                    sessions = sessions + meta
                    store.save(sessions)
                    selected = meta
                }
                showCreate = false
            }
        }
        return
    }

    val groups = listOf("All", "Ungrouped") + sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    val visible = sessions.filter { !it.archived }.filter {
        val groupOk = when (filter) {
            "All" -> true
            "Ungrouped" -> it.group.isBlank()
            else -> it.group == filter
        }
        groupOk && (query.isBlank() || it.name.contains(query, true))
    }
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selectedIds }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bro Liker", fontWeight = FontWeight.Bold)
                        Text("${sessions.count { !it.archived }} sessions", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "Create session") }
                    IconButton(onClick = { showGroup = true }) { Icon(Icons.Default.FilterList, "Groups") }
                    IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text("Select all visible") },
                            leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                            onClick = {
                                selectedIds = if (allVisibleSelected) emptySet() else visible.map { it.id }.toSet()
                                showMore = false
                            }
                        )
                        DropdownMenuItem(text = { Text("Clear selection") }, onClick = { selectedIds = emptySet(); showMore = false })
                        DropdownMenuItem(
                            text = { Text("Bulk move to group") },
                            leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { showBulkGroup = true; showMore = false }
                        )
                    }
                }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "Create session") } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search sessions") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.take(6).forEach { g ->
                            FilterChip(selected = filter == g, onClick = { filter = g }, label = { Text(g) })
                        }
                    }
                }
                items(visible, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        selected = session.id in selectedIds,
                        onSelect = { selectedIds = if (session.id in selectedIds) selectedIds - session.id else selectedIds + session.id },
                        onOpen = {
                            val updated = sessions.map { if (it.id == session.id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it }
                            sessions = updated; store.save(updated); selected = updated.first { it.id == session.id }
                        },
                        onRename = { showRename = session },
                        onDelete = {
                            sessions = sessions.filterNot { it.id == session.id }; store.save(sessions)
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) runCatching { ProfileStore.getInstance().deleteProfile(session.profileName) }
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateSessionDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            onDismiss = { showCreate = false },
        ) { name, group ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                val p = "profile_${UUID.randomUUID()}"
                val meta = SessionMeta(UUID.randomUUID().toString(), p, name, group, System.currentTimeMillis())
                sessions = sessions + meta
                store.save(sessions)
                selected = meta
            }
            showCreate = false
        }
    }

    showRename?.let { current ->
        RenameDialog(current.name, { showRename = null }) { newName ->
            sessions = sessions.map { if (it.id == current.id) it.copy(name = newName) else it }
            store.save(sessions); showRename = null
        }
    }

    if (showGroup) {
        GroupManagerDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            onDismiss = { showGroup = false },
            onFilter = { filter = it; showGroup = false },
        )
    }

    if (showBulkGroup) {
        BulkGroupDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            onDismiss = { showBulkGroup = false },
        ) { group ->
            sessions = sessions.map { if (it.id in selectedIds) it.copy(group = group) else it }
            store.save(sessions); selectedIds = emptySet(); showBulkGroup = false
        }
    }
}

@Composable
private fun SessionCard(session: SessionMeta, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onSelect() })
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("F", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (session.group.isBlank()) "Ungrouped" else session.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpen) { Text("Open") }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Rename") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
}

@Composable
private fun CreateSessionDialog(groups: List<String>, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("Session ${System.currentTimeMillis() % 100000}") }
    var group by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create new session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Session name") }, singleLine = true)
                OutlinedTextField(group, { group = it }, label = { Text(if (groups.isEmpty()) "Group (optional)" else "Group") }, singleLine = true)
                Text("Each session gets its own persistent browser profile. No software session-count cap is imposed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), group.trim()) }) { Text("Create & Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { Button(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GroupManagerDialog(groups: List<String>, onDismiss: () -> Unit, onFilter: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groups") },
        text = {
            Column {
                TextButton(onClick = { onFilter("All") }) { Text("All sessions") }
                TextButton(onClick = { onFilter("Ungrouped") }) { Text("Ungrouped") }
                groups.forEach { g -> TextButton(onClick = { onFilter(g) }) { Text(g) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun BulkGroupDialog(groups: List<String>, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var group by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move selected sessions") },
        text = { OutlinedTextField(group, { group = it }, label = { Text("Group name") }, singleLine = true) },
        confirmButton = { Button(enabled = group.isNotBlank(), onClick = { onApply(group.trim()) }) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(session: SessionMeta, onBack: () -> Unit, onCreateAnother: () -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Close session") } },
                title = {
                    Column {
                        Text(session.name, fontWeight = FontWeight.SemiBold)
                        Text(if (session.group.isBlank()) "Ungrouped" else session.group, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(enabled = canBack, onClick = { webViewRef?.goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
                    IconButton(onClick = { webViewRef?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(enabled = canForward, onClick = { webViewRef?.goForward() }) { Icon(Icons.Default.ArrowForward, "Forward") }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Home") },
                            leadingIcon = { Icon(Icons.Default.Home, null) },
                            onClick = { webViewRef?.loadUrl(HOME_URL); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Create another session") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { showMenu = false; onCreateAnother() }
                        )
                        DropdownMenuItem(text = { Text("Close") }, onClick = { showMenu = false; onBack() })
                    }
                },
            )
        }
    ) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                    }
                    webChromeClient = WebChromeClient()
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        WebViewCompat.setProfile(this, session.profileName)
                    }
                    loadUrl(HOME_URL)
                    webViewRef = this
                    canBack = canGoBack()
                    canForward = canGoForward()
                }
            },
            update = {
                webViewRef = it
                canBack = it.canGoBack()
                canForward = it.canGoForward()
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}
