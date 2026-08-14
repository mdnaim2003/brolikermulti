package com.broliker.multisession.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

private const val HOME_URL = "https://m.facebook.com/"
private const val PAGE_SIZE = 50

// ─── Session colors (auto-assign cycle) ──────────────────────────────────────

private val SESSION_COLORS = listOf(
    "#E91E63", "#9C27B0", "#3F51B5", "#2196F3", "#009688",
    "#4CAF50", "#FF9800", "#795548", "#607D8B", "#F44336",
    "#673AB7", "#03A9F4", "#8BC34A", "#FF5722", "#00BCD4",
)

private fun colorForIndex(index: Int): String = SESSION_COLORS[index % SESSION_COLORS.size]

// ─── Data ─────────────────────────────────────────────────────────────────────

private data class SessionMeta(
    val id: String,
    val profileName: String,
    val name: String,
    val group: String,
    val createdAt: Long,
    val lastOpenedAt: Long = 0L,
    val archived: Boolean = false,
    val color: String = "#2196F3",
    // Cached c_user from last cookie read (for search)
    val cachedCUser: String = "",
)

// ─── Store ────────────────────────────────────────────────────────────────────

private class SessionStore(context: Context) {
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
                        color = o.optString("color", "#2196F3"),
                        cachedCUser = o.optString("cachedCUser", ""),
                    )
                )
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
                put("color", s.color)
                put("cachedCUser", s.cachedCUser)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun nextSerialNumber(current: List<SessionMeta>): Int {
        val used = current.mapNotNull { s ->
            Regex("^My Session (\\d+)$").find(s.name)
                ?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        var n = 1
        while (used.contains(n)) n++
        return n
    }

    fun nextColorIndex(current: List<SessionMeta>): Int = current.size
}

// ─── Cookie helpers ───────────────────────────────────────────────────────────

private val FB_DOMAINS = listOf(
    "https://m.facebook.com",
    "https://www.facebook.com",
    "https://facebook.com",
    "https://static.xx.fbcdn.net",
)

/** Parse raw cookie header string → flat key→value map */
private fun parseCookieHeader(raw: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    raw.split(";").forEach { part ->
        val trimmed = part.trim()
        val eq = trimmed.indexOf('=')
        if (eq > 0) {
            val k = trimmed.substring(0, eq).trim()
            val v = trimmed.substring(eq + 1).trim()
            if (k.isNotEmpty()) map[k] = v
        } else if (trimmed.isNotEmpty()) {
            map[trimmed] = ""
        }
    }
    return map
}

/** Collect all cookies for a session from profile-scoped or global manager */
private fun collectCookiesForSession(
    session: SessionMeta,
    multiProfileSupported: Boolean,
    extraUrl: String = "",
): Map<String, String> {
    val profileMgr = if (multiProfileSupported) {
        runCatching {
            ProfileStore.getInstance().getProfile(session.profileName)?.cookieManager
        }.getOrNull()
    } else null

    val globalMgr = CookieManager.getInstance()
    val result = mutableMapOf<String, String>()

    val domains = if (extraUrl.isNotBlank() && !FB_DOMAINS.any { extraUrl.startsWith(it) })
        FB_DOMAINS + extraUrl else FB_DOMAINS

    domains.forEach { domain ->
        val raw = profileMgr?.getCookie(domain) ?: globalMgr.getCookie(domain)
        if (!raw.isNullOrBlank()) {
            parseCookieHeader(raw).forEach { (k, v) ->
                if (!result.containsKey(k)) result[k] = v
            }
        }
    }
    return result
}

/** Build exact output JSON object for one session */
private fun buildSessionCookieJson(
    session: SessionMeta,
    cookies: Map<String, String>,
): JSONObject {
    // Build cookies sub-object — keep all keys, empty string if unavailable
    val cookiesObj = JSONObject()
    // Priority keys first (always present, even if empty)
    val priorityKeys = listOf(
        "datr", "fr", "sb", "ps_l", "ps_n",
        "c_user", "xs", "presence", "wd", "dbln",
        "NID", "__Secure-STRP", "SEARCH_SAMESITE", "AEC", "OTZ"
    )
    priorityKeys.forEach { key ->
        cookiesObj.put(key, cookies[key] ?: "")
    }
    // Any extra keys not in priority list
    cookies.forEach { (k, v) ->
        if (!priorityKeys.contains(k)) cookiesObj.put(k, v)
    }

    return JSONObject().apply {
        put("sessionId", session.id)
        put("color", session.color)
        put("cookies", cookiesObj)
    }
}

// ─── Export / Import ──────────────────────────────────────────────────────────

private fun exportCookiesForProfile(
    session: SessionMeta,
    multiProfileSupported: Boolean,
): String {
    val cookies = collectCookiesForSession(session, multiProfileSupported)
    val cookieMap = JSONObject()
    FB_DOMAINS.forEach { domain ->
        val profileMgr = if (multiProfileSupported) {
            runCatching {
                ProfileStore.getInstance().getProfile(session.profileName)?.cookieManager
            }.getOrNull()
        } else null
        val raw = profileMgr?.getCookie(domain) ?: CookieManager.getInstance().getCookie(domain)
        if (!raw.isNullOrBlank()) cookieMap.put(domain, raw)
    }
    return cookieMap.toString()
}

private fun importCookiesForProfile(
    profileName: String,
    cookieJson: String,
    multiProfileSupported: Boolean,
) {
    try {
        val mgr = CookieManager.getInstance()
        mgr.setAcceptCookie(true)
        val obj = JSONObject(cookieJson)
        obj.keys().forEach { domain ->
            val cookieHeader = obj.getString(domain)
            cookieHeader.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isNotEmpty()) mgr.setCookie(domain, trimmed)
            }
        }
        mgr.flush()
    } catch (_: Exception) {}
}

private fun buildExportJson(
    sessions: List<SessionMeta>,
    multiProfileSupported: Boolean,
): String {
    val arr = JSONArray()
    sessions.filter { !it.archived }.forEach { s ->
        val cookieRaw = exportCookiesForProfile(s, multiProfileSupported)
        arr.put(JSONObject().apply {
            put("id", s.id)
            put("profileName", s.profileName)
            put("name", s.name)
            put("group", s.group)
            put("createdAt", s.createdAt)
            put("lastOpenedAt", s.lastOpenedAt)
            put("archived", s.archived)
            put("color", s.color)
            put("cachedCUser", s.cachedCUser)
            put("cookies", cookieRaw)
        })
    }
    return JSONObject().apply {
        put("app", "BroLiker")
        put("version", 3)
        put("exportedAt", System.currentTimeMillis())
        put("sessions", arr)
    }.toString(2)
}

private fun parseImportJson(
    json: String,
    multiProfileSupported: Boolean,
): List<SessionMeta> {
    val root = JSONObject(json)
    val arr = root.getJSONArray("sessions")
    val result = mutableListOf<SessionMeta>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val meta = SessionMeta(
            id = o.optString("id", UUID.randomUUID().toString()),
            profileName = o.optString("profileName", "profile_${UUID.randomUUID()}"),
            name = o.optString("name", "Imported Session ${i + 1}"),
            group = o.optString("group", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastOpenedAt = o.optLong("lastOpenedAt", 0L),
            archived = o.optBoolean("archived", false),
            color = o.optString("color", colorForIndex(i)),
            cachedCUser = o.optString("cachedCUser", ""),
        )
        result.add(meta)
        val cookiesStr = o.optString("cookies", "{}")
        if (cookiesStr.isNotBlank() && cookiesStr != "{}") {
            importCookiesForProfile(meta.profileName, cookiesStr, multiProfileSupported)
        }
    }
    return result
}

// ─── Cookie copy helpers ──────────────────────────────────────────────────────

/** Copy cookies for multiple sessions in exact format */
private fun copyMultiSessionCookies(
    context: Context,
    sessions: List<SessionMeta>,
    multiProfileSupported: Boolean,
) {
    try {
        val root = JSONObject()
        var totalSessions = 0
        var totalCookies = 0

        sessions.forEach { session ->
            val cookies = collectCookiesForSession(session, multiProfileSupported)
            val sessionObj = buildSessionCookieJson(session, cookies)
            root.put(session.name, sessionObj)
            totalSessions++
            totalCookies += cookies.size
        }

        val jsonString = root.toString(2)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BroLiker Multi Cookies", jsonString))

        val hasCUser = sessions.any { s ->
            collectCookiesForSession(s, multiProfileSupported).containsKey("c_user")
        }

        Toast.makeText(
            context,
            if (hasCUser) "✅ $totalSessions sessions, $totalCookies cookies copied!"
            else "⚠️ Copied but login cookies (c_user) not found. Login first.",
            Toast.LENGTH_LONG,
        ).show()

    } catch (e: Exception) {
        Toast.makeText(context, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Single session cookie copy (from browser screen) */
private fun copySingleSessionCookies(
    context: Context,
    session: SessionMeta,
    multiProfileSupported: Boolean,
    currentUrl: String,
    pageTitle: String,
) {
    try {
        val cookies = collectCookiesForSession(session, multiProfileSupported, currentUrl)
        val root = JSONObject()
        root.put(session.name, buildSessionCookieJson(session, cookies))

        val jsonString = root.toString(2)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BroLiker Cookies", jsonString))

        val cUser = cookies["c_user"]
        Toast.makeText(
            context,
            if (cUser != null) "✅ ${cookies.size} cookies copied! c_user: $cUser"
            else "⚠️ ${cookies.size} cookies copied. Login keys not found.",
            Toast.LENGTH_LONG,
        ).show()

    } catch (e: Exception) {
        Toast.makeText(context, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ─── Hex color → Compose Color ────────────────────────────────────────────────

private fun hexToColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF2196F3)
    }
}

// ─── Main composable ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroLikerApp() {
    val context = LocalContext.current.applicationContext
    val store = remember { SessionStore(context) }

    val multiProfileSupported = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    var sessions by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<SessionMeta?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf<SessionMeta?>(null) }
    var showGroupManager by remember { mutableStateOf(false) }
    var showBulkGroup by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var currentPage by remember { mutableIntStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val json = buildExportJson(sessions, multiProfileSupported)
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            Toast.makeText(context, "✅ Export সফল হয়েছে", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "❌ Export ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: return@rememberLauncherForActivityResult
            val imported = parseImportJson(json, multiProfileSupported)
            val existingIds = sessions.map { it.id }.toSet()
            val newOnes = imported.filter { it.id !in existingIds }
            val merged = sessions + newOnes
            sessions = merged
            store.save(merged)
            Toast.makeText(context, "✅ ${newOnes.size} session import হয়েছে", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "❌ Import ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    if (selected != null) {
        BrowserScreen(
            session = selected!!,
            multiProfileSupported = multiProfileSupported,
            onBack = { selected = null },
            onCreateAnother = { showCreate = true },
            onCUserDetected = { cUser ->
                // Update cachedCUser when detected from browser
                sessions = sessions.map {
                    if (it.id == selected!!.id) it.copy(cachedCUser = cUser) else it
                }
                store.save(sessions)
            }
        )
        if (showCreate) {
            val idx = store.nextColorIndex(sessions)
            CreateSessionDialog(
                groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
                nextSerial = store.nextSerialNumber(sessions),
                defaultColor = colorForIndex(idx),
                onDismiss = { showCreate = false },
            ) { name, group, color ->
                val profileName = "profile_${UUID.randomUUID()}"
                val meta = SessionMeta(
                    id = UUID.randomUUID().toString(),
                    profileName = profileName,
                    name = name,
                    group = group,
                    createdAt = System.currentTimeMillis(),
                    color = color,
                )
                sessions = sessions + meta
                store.save(sessions)
                selected = meta
                showCreate = false
            }
        }
        return
    }

    val groups = listOf("All", "Ungrouped") +
        sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

    // Search: name OR c_user match
    val filtered = sessions.filter { !it.archived }.filter { session ->
        val groupOk = when (filter) {
            "All" -> true
            "Ungrouped" -> session.group.isBlank()
            else -> session.group == filter
        }
        val q = query.trim()
        val matchOk = q.isBlank() ||
            session.name.contains(q, ignoreCase = true) ||
            (q.length >= 6 && session.cachedCUser.contains(q, ignoreCase = true))
        groupOk && matchOk
    }

    val totalPages = ((filtered.size - 1) / PAGE_SIZE + 1).coerceAtLeast(1)
    val safePage = currentPage.coerceIn(0, totalPages - 1)
    val pageItems = filtered.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)
    val allPageSelected = pageItems.isNotEmpty() && pageItems.all { it.id in selectedIds }

    LaunchedEffect(filter, query) { currentPage = 0 }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                    IconButton(onClick = { showGroupManager = true }) {
                        Icon(Icons.Default.AccountTree, "Groups")
                    }
                    var showMore by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMore = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text(if (allPageSelected) "Deselect page" else "Select page") },
                            leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                            onClick = {
                                selectedIds = if (allPageSelected)
                                    selectedIds - pageItems.map { it.id }.toSet()
                                else selectedIds + pageItems.map { it.id }.toSet()
                                showMore = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Select all in group") },
                            leadingIcon = { Icon(Icons.Default.DoneAll, null) },
                            onClick = {
                                selectedIds = selectedIds + filtered.map { it.id }.toSet()
                                showMore = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear selection") },
                            onClick = { selectedIds = emptySet(); showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Bulk move to group") },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { showBulkGroup = true; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete selected") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                sessions = sessions.filterNot { it.id in selectedIds }
                                store.save(sessions)
                                selectedIds = emptySet()
                                showMore = false
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, "New session")
            }
        }
    ) { pad ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Search bar ────────────────────────────────────────────
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search by name or c_user...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(10.dp))

            // ── Group filter chips ────────────────────────────────────
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(groups) { group ->
                    val count = when (group) {
                        "All" -> sessions.count { !it.archived }
                        "Ungrouped" -> sessions.count { !it.archived && it.group.isBlank() }
                        else -> sessions.count { !it.archived && it.group == group }
                    }
                    FilterChip(
                        selected = filter == group,
                        onClick = { filter = group },
                        label = { Text("$group ($count)") },
                        leadingIcon = if (filter == group) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Selection action bar ──────────────────────────────────
            if (selectedIds.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selectedIds.size} selected",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        // ── Copy cookies for selected ─────────────────
                        TextButton(onClick = {
                            val toCopy = sessions.filter { it.id in selectedIds }
                            copyMultiSessionCookies(context, toCopy, multiProfileSupported)
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                null,
                                Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Cookies", fontSize = 12.sp)
                        }
                        TextButton(onClick = { showBulkGroup = true }) {
                            Text("Group", fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            sessions = sessions.filterNot { it.id in selectedIds }
                            store.save(sessions)
                            selectedIds = emptySet()
                        }) {
                            Text("Del", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // ── Session list ──────────────────────────────────────────
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inbox,
                            null,
                            Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (query.isNotBlank()) "No sessions found" else "No sessions yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (query.isBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showCreate = true }) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Create first session")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(pageItems, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            selected = session.id in selectedIds,
                            onSelect = {
                                selectedIds = if (session.id in selectedIds)
                                    selectedIds - session.id
                                else selectedIds + session.id
                            },
                            onOpen = {
                                val updated = sessions.map {
                                    if (it.id == session.id)
                                        it.copy(lastOpenedAt = System.currentTimeMillis())
                                    else it
                                }
                                sessions = updated
                                store.save(updated)
                                selected = updated.first { it.id == session.id }
                            },
                            onRename = { showRename = session },
                            onDelete = {
                                sessions = sessions.filterNot { it.id == session.id }
                                store.save(sessions)
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                    runCatching {
                                        ProfileStore.getInstance().deleteProfile(session.profileName)
                                    }
                                }
                            },
                            onCopyCookies = {
                                copyMultiSessionCookies(context, listOf(session), multiProfileSupported)
                            },
                        )
                    }
                    if (totalPages > 1) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            PaginationBar(
                                current = safePage,
                                total = totalPages,
                                onPrev = { currentPage = (safePage - 1).coerceAtLeast(0) },
                                onNext = { currentPage = (safePage + 1).coerceAtMost(totalPages - 1) },
                                onPage = { currentPage = it },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    if (showCreate) {
        val idx = store.nextColorIndex(sessions)
        CreateSessionDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            nextSerial = store.nextSerialNumber(sessions),
            defaultColor = colorForIndex(idx),
            onDismiss = { showCreate = false },
        ) { name, group, color ->
            val profileName = "profile_${UUID.randomUUID()}"
            val meta = SessionMeta(
                id = UUID.randomUUID().toString(),
                profileName = profileName,
                name = name,
                group = group,
                createdAt = System.currentTimeMillis(),
                color = color,
            )
            sessions = sessions + meta
            store.save(sessions)
            selected = meta
            showCreate = false
        }
    }

    showRename?.let { current ->
        RenameDialog(current = current.name, onDismiss = { showRename = null }) { newName ->
            sessions = sessions.map { if (it.id == current.id) it.copy(name = newName) else it }
            store.save(sessions)
            showRename = null
        }
    }

    if (showGroupManager) {
        GroupManagerDialog(
            sessions = sessions,
            currentFilter = filter,
            onDismiss = { showGroupManager = false },
            onFilter = { filter = it; showGroupManager = false },
            onRenameGroup = { old, new ->
                sessions = sessions.map { if (it.group == old) it.copy(group = new) else it }
                store.save(sessions)
                if (filter == old) filter = new
            },
            onDeleteGroup = { groupName ->
                sessions = sessions.map { if (it.group == groupName) it.copy(group = "") else it }
                store.save(sessions)
                if (filter == groupName) filter = "All"
            }
        )
    }

    if (showBulkGroup) {
        BulkGroupDialog(
            groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted(),
            onDismiss = { showBulkGroup = false },
        ) { group ->
            sessions = sessions.map { if (it.id in selectedIds) it.copy(group = group) else it }
            store.save(sessions)
            selectedIds = emptySet()
            showBulkGroup = false
        }
    }

    if (showSettings) {
        SettingsDialog(
            sessionCount = sessions.count { !it.archived },
            onDismiss = { showSettings = false },
            onExport = {
                showSettings = false
                exportLauncher.launch("broliker_backup_${System.currentTimeMillis()}.json")
            },
            onImport = {
                showSettings = false
                importLauncher.launch(arrayOf("application/json", "*/*"))
            },
        )
    }
}

// ─── Pagination ───────────────────────────────────────────────────────────────

@Composable
private fun PaginationBar(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPage: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrev, enabled = current > 0) {
                Icon(Icons.Default.ChevronLeft, "Prev")
            }
            val range = ((current - 2).coerceAtLeast(0)..(current + 2).coerceAtMost(total - 1))
            if (range.first > 0) {
                PageChip(0, current, onPage)
                if (range.first > 1) Text("…", Modifier.padding(horizontal = 4.dp))
            }
            range.forEach { p -> PageChip(p, current, onPage) }
            if (range.last < total - 1) {
                if (range.last < total - 2) Text("…", Modifier.padding(horizontal = 4.dp))
                PageChip(total - 1, current, onPage)
            }
            IconButton(onClick = onNext, enabled = current < total - 1) {
                Icon(Icons.Default.ChevronRight, "Next")
            }
        }
    }
}

@Composable
private fun PageChip(page: Int, current: Int, onPage: (Int) -> Unit) {
    val sel = page == current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onPage(page) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${page + 1}",
            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
        )
    }
}

// ─── Session Card ─────────────────────────────────────────────────────────────

@Composable
private fun SessionCard(
    session: SessionMeta,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyCookies: () -> Unit,
) {
    val sessionColor = hexToColor(session.color)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onSelect() })

            // Color avatar
            Box(
                Modifier
                    .size(42.dp)
                    .background(sessionColor, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    session.name.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (session.group.isNotBlank()) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (session.group.isBlank()) "Ungrouped" else session.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Show c_user hint if cached
                    if (session.cachedCUser.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "·${session.cachedCUser.takeLast(6)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            Button(
                onClick = onOpen,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Open", fontSize = 13.sp)
            }

            Spacer(Modifier.width(2.dp))

            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, null)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy Cookies") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { showMenu = false; onCopyCookies() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { showMenu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

// ─── Create Session Dialog ────────────────────────────────────────────────────

@Composable
private fun CreateSessionDialog(
    groups: List<String>,
    nextSerial: Int,
    defaultColor: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("My Session $nextSerial") }
    var group by remember { mutableStateOf("") }
    var showGroupSuggestions by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    group, { group = it },
                    label = { Text("Group (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (groups.isNotEmpty()) {
                        {
                            IconButton(onClick = { showGroupSuggestions = !showGroupSuggestions }) {
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                        }
                    } else null,
                )
                if (showGroupSuggestions && groups.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            groups.forEach { g ->
                                Text(
                                    g,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { group = g; showGroupSuggestions = false }
                                        .padding(12.dp),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                // Color preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(hexToColor(defaultColor), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Auto color assigned",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Each session has its own isolated browser profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), group.trim(), defaultColor) }
            ) { Text("Create & Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─── Rename Dialog ────────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value, { value = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─── Group Manager Dialog ─────────────────────────────────────────────────────

@Composable
private fun GroupManagerDialog(
    sessions: List<SessionMeta>,
    currentFilter: String,
    onDismiss: () -> Unit,
    onFilter: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    val groups = sessions.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, null)
                Spacer(Modifier.width(8.dp))
                Text("Manage Groups")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupRow(
                    name = "All Sessions",
                    count = sessions.count { !it.archived },
                    isSelected = currentFilter == "All",
                    icon = Icons.Default.GridView,
                    onClick = { onFilter("All") },
                    canEdit = false,
                )
                GroupRow(
                    name = "Ungrouped",
                    count = sessions.count { !it.archived && it.group.isBlank() },
                    isSelected = currentFilter == "Ungrouped",
                    icon = Icons.Default.FolderOpen,
                    onClick = { onFilter("Ungrouped") },
                    canEdit = false,
                )
                if (groups.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Your Groups",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.forEach { group ->
                    if (renamingGroup == group) {
                        OutlinedTextField(
                            value = renameValue,
                            onValueChange = { renameValue = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = {
                                        if (renameValue.isNotBlank()) onRenameGroup(group, renameValue.trim())
                                        renamingGroup = null
                                    }) { Icon(Icons.Default.Check, null) }
                                    IconButton(onClick = { renamingGroup = null }) {
                                        Icon(Icons.Default.Close, null)
                                    }
                                }
                            }
                        )
                    } else {
                        GroupRow(
                            name = group,
                            count = sessions.count { !it.archived && it.group == group },
                            isSelected = currentFilter == group,
                            icon = Icons.Default.Folder,
                            onClick = { onFilter(group) },
                            canEdit = true,
                            onEdit = { renamingGroup = group; renameValue = group },
                            onDelete = { onDeleteGroup(group) },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No groups yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun GroupRow(
    name: String,
    count: Int,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    canEdit: Boolean,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null, Modifier.size(20.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            name, modifier = Modifier.weight(1f),
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
        Text("$count", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (canEdit) {
            IconButton(onClick = { onEdit?.invoke() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
            }
            IconButton(onClick = { onDelete?.invoke() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─── Bulk Group Dialog ────────────────────────────────────────────────────────

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
                    Text("Pick existing:", style = MaterialTheme.typography.labelMedium)
                    groups.forEach { existing ->
                        Text(
                            existing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                .clickable { group = existing }
                                .padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = group.isNotBlank(), onClick = { onApply(group.trim()) }) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─── Settings Dialog ──────────────────────────────────────────────────────────

@Composable
private fun SettingsDialog(
    sessionCount: Int,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(8.dp))
                Text("Settings")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("$sessionCount sessions saved", fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Backup & Restore", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Export saves all sessions with cookies. Import merges without overwriting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("Export Backup (.json)")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Import Backup (.json)")
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(10.dp)) {
                        Icon(Icons.Default.Warning, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Cookie restore works best on the same device & IP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ─── Browser Screen ───────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    session: SessionMeta,
    multiProfileSupported: Boolean,
    onBack: () -> Unit,
    onCreateAnother: () -> Unit,
    onCUserDetected: (String) -> Unit,
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

    fun flushAndDetectCUser() {
        if (multiProfileSupported) {
            runCatching {
                profileStore().getProfile(session.profileName)?.cookieManager?.flush()
            }
        } else {
            CookieManager.getInstance().flush()
        }
        // Detect c_user and cache it
        val cookies = collectCookiesForSession(session, multiProfileSupported)
        val cUser = cookies["c_user"]
        if (!cUser.isNullOrBlank() && cUser != session.cachedCUser) {
            onCUserDetected(cUser)
        }
    }

    fun makeClient(webView: WebView): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url; urlBarText = url; isLoading = true; errorMsg = null
            canBack = view.canGoBack(); canForward = view.canGoForward()
        }
        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url; urlBarText = url; isLoading = false
            canBack = view.canGoBack(); canForward = view.canGoForward()
            flushAndDetectCUser()
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                isLoading = false
                errorMsg = "Error ${error.errorCode}: ${error.description}"
            }
        }
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            isLoading = false
            errorMsg = if (detail.didCrash()) "WebView crashed. Tap Reload." else "WebView stopped. Tap Reload."
            return true
        }
    }

    fun makeChromeClient(webView: WebView): WebChromeClient = object : WebChromeClient() {
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
            child.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
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

    fun closePopupIfPossible(): Boolean {
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
                            if (!closePopupIfPossible()) { flushAndDetectCUser(); onBack() }
                        }) { Icon(Icons.Default.ArrowBack, "Close") }
                    },
                    title = {
                        OutlinedTextField(
                            value = urlBarText,
                            onValueChange = { urlBarText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("URL or search...") },
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
                                text = { Text("Back") }, enabled = canBack,
                                leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                                onClick = { activeWebView?.goBack(); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Forward") }, enabled = canForward,
                                leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                                onClick = { activeWebView?.goForward(); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Facebook Home") },
                                leadingIcon = { Icon(Icons.Default.Home, null) },
                                onClick = { navigateTo(HOME_URL); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("New session") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { showMenu = false; onCreateAnother() }
                            )
                            DropdownMenuItem(
                                text = { Text("📋 Copy Cookies (JSON)") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    showMenu = false
                                    flushAndDetectCUser()
                                    copySingleSessionCookies(
                                        context, session, multiProfileSupported, currentUrl, pageTitle
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Close") },
                                onClick = { showMenu = false; flushAndDetectCUser(); onBack() }
                            )
                        }
                    }
                )
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { loadingProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
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
                            runCatching {
                                web.stopLoading(); web.loadUrl("about:blank")
                                web.removeAllViews(); web.destroy()
                            }
                        }
                    }
                    root.removeAllViews(); activeWebView = null; rootRef = null
                }
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
                    Text("Page failed to load", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
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