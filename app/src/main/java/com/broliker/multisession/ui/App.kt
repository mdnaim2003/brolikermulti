package com.broliker.multisession.ui

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.activity.compose.BackHandler
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

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

// ─── Session colors (auto-assign cycle) ──────────────────────────────────────

private val SESSION_COLORS = listOf(
    "#E91E63", "#9C27B0", "#3F51B5", "#2196F3", "#009688",
    "#4CAF50", "#FF9800", "#795548", "#607D8B", "#F44336",
    "#673AB7", "#03A9F4", "#8BC34A", "#FF5722", "#00BCD4",
)

private fun colorForIndex(index: Int): String =
    SESSION_COLORS[index % SESSION_COLORS.size]

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
    val cachedCUser: String = "",
)

// ─── Store ────────────────────────────────────────────────────────────────────

private class SessionStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("bro_sessions", Context.MODE_PRIVATE)

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
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("profileName", s.profileName)
                    put("name", s.name)
                    put("group", s.group)
                    put("createdAt", s.createdAt)
                    put("lastOpenedAt", s.lastOpenedAt)
                    put("archived", s.archived)
                    put("color", s.color)
                    put("cachedCUser", s.cachedCUser)
                }
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun nextSerialNumber(current: List<SessionMeta>): Int {
        val used = current.mapNotNull { s ->
            Regex("^My Session (\\d+)$")
                .find(s.name)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
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

private fun collectCookiesForSession(
    session: SessionMeta,
    multiProfileSupported: Boolean,
    extraUrl: String = "",
): Map<String, String> {
    val profileMgr = if (multiProfileSupported) {
        runCatching {
            ProfileStore
                .getInstance()
                .getProfile(session.profileName)
                ?.cookieManager
        }.getOrNull()
    } else {
        null
    }

    val globalMgr = CookieManager.getInstance()

    val result = linkedMapOf<String, String>()

    val urls = buildList {
        addAll(FB_DOMAINS)
        if (extraUrl.isNotBlank()) add(extraUrl)
    }.distinct()

    urls.forEach { url ->
        runCatching {
            profileMgr?.getCookie(url)?.let { result.putAll(parseCookieHeader(it)) }
        }

        runCatching {
            globalMgr.getCookie(url)?.let { result.putAll(parseCookieHeader(it)) }
        }
    }

    return result
}

private fun buildSessionCookieJson(
    session: SessionMeta,
    multiProfileSupported: Boolean,
    extraUrl: String = "",
): JSONObject {
    val cookies = collectCookiesForSession(
        session,
        multiProfileSupported,
        extraUrl,
    )

    return JSONObject().apply {
        put("id", session.id)
        put("sessionName", session.name)
        put("profileName", session.profileName)

        val cookieObj = JSONObject()
        cookies.forEach { (key, value) ->
            cookieObj.put(key, value)
        }

        put("cookies", cookieObj)
    }
}

private fun exportCookiesForProfile(
    session: SessionMeta,
    multiProfileSupported: Boolean,
): JSONObject {
    return buildSessionCookieJson(
        session = session,
        multiProfileSupported = multiProfileSupported,
    )
}

private fun importCookiesForProfile(
    session: SessionMeta,
    multiProfileSupported: Boolean,
    cookieObject: JSONObject,
) {
    if (!multiProfileSupported) return

    runCatching {
        val profile =
            ProfileStore.getInstance().getProfile(session.profileName)
                ?: return@runCatching

        val cookieManager = profile.cookieManager

        val keys = cookieObject.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = cookieObject.optString(key)

            if (value.isNotEmpty()) {
                cookieManager.setCookie(
                    HOME_URL,
                    "$key=$value; Path=/"
                )
            }
        }

        cookieManager.flush()
    }
}

private fun buildExportJson(
    sessions: List<SessionMeta>,
    multiProfileSupported: Boolean,
): String {
    val root = JSONObject().apply {
        put("version", 1)
        put("exportedAt", System.currentTimeMillis())

        val array = JSONArray()

        sessions.forEach { session ->
            val obj = JSONObject().apply {
                put("id", session.id)
                put("profileName", session.profileName)
                put("name", session.name)
                put("group", session.group)
                put("createdAt", session.createdAt)
                put("lastOpenedAt", session.lastOpenedAt)
                put("archived", session.archived)
                put("color", session.color)
                put("cachedCUser", session.cachedCUser)

                put(
                    "cookies",
                    exportCookiesForProfile(
                        session,
                        multiProfileSupported
                    )
                )
            }

            array.put(obj)
        }

        put("sessions", array)
    }

    return root.toString(2)
}

private fun parseImportJson(
    json: String,
    multiProfileSupported: Boolean,
): List<SessionMeta> {
    val result = mutableListOf<SessionMeta>()
    val root = JSONObject(json)
    val array = root.optJSONArray("sessions") ?: JSONArray()

    for (i in 0 until array.length()) {
        val o = array.getJSONObject(i)

        val id = o.optString("id", UUID.randomUUID().toString())
        val profileName =
            o.optString("profileName", "profile_${UUID.randomUUID()}")

        val session = SessionMeta(
            id = id,
            profileName = profileName,
            name = o.optString("name", "Imported Session"),
            group = o.optString("group"),
            createdAt = o.optLong(
                "createdAt",
                System.currentTimeMillis()
            ),
            lastOpenedAt = o.optLong("lastOpenedAt", 0L),
            archived = o.optBoolean("archived", false),
            color = o.optString("color", "#2196F3"),
            cachedCUser = o.optString("cachedCUser", ""),
        )

        result.add(session)

        val cookieContainer = o.optJSONObject("cookies")
        val cookieObject =
            cookieContainer?.optJSONObject("cookies")

        if (cookieObject != null) {
            importCookiesForProfile(
                session,
                multiProfileSupported,
                cookieObject
            )
        }
    }

    return result
}

private fun copyMultiSessionCookies(
    context: Context,
    sessions: List<SessionMeta>,
    multiProfileSupported: Boolean,
) {
    val root = JSONArray()

    sessions.forEach { session ->
        root.put(
            buildSessionCookieJson(
                session,
                multiProfileSupported
            )
        )
    }

    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager

    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            "Bro Liker Cookies",
            root.toString(2)
        )
    )

    Toast.makeText(
        context,
        "Cookies copied",
        Toast.LENGTH_SHORT
    ).show()
}

private fun copySingleSessionCookies(
    context: Context,
    session: SessionMeta,
    multiProfileSupported: Boolean,
    currentUrl: String,
    pageTitle: String,
) {
    val obj = buildSessionCookieJson(
        session,
        multiProfileSupported,
        currentUrl
    ).apply {
        put("url", currentUrl)
        put("title", pageTitle)
    }

    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager

    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            "Bro Liker Session Cookies",
            obj.toString(2)
        )
    )

    Toast.makeText(
        context,
        "Cookies copied",
        Toast.LENGTH_SHORT
    ).show()
}

private fun hexToColor(hex: String): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF2196F3))
}

// ─── Main application ─────────────────────────────────────────────────────────

@Composable
fun BroLikerApp() {
    val context = LocalContext.current.applicationContext
    val store = remember { SessionStore(context) }

    val multiProfileSupported = remember {
        WebViewFeature.isFeatureSupported(
            WebViewFeature.MULTI_PROFILE
        )
    }

    var sessions by remember {
        mutableStateOf(store.load())
    }

    var selected by remember {
        mutableStateOf<SessionMeta?>(null)
    }

    var showCreate by remember {
        mutableStateOf(false)
    }

    var showRename by remember {
        mutableStateOf<SessionMeta?>(null)
    }

    var showGroupManager by remember {
        mutableStateOf(false)
    }

    var showBulkGroup by remember {
        mutableStateOf(false)
    }

    var showSettings by remember {
        mutableStateOf(false)
    }

    var showExitConfirmation by remember {
        mutableStateOf(false)
    }

    var pendingDeleteIds by remember {
        mutableStateOf<Set<String>?>(null)
    }

    var pendingDeleteGroup by remember {
        mutableStateOf<String?>(null)
    }

    var query by remember {
        mutableStateOf("")
    }

    var filter by remember {
        mutableStateOf("All")
    }

    var selectedIds by remember {
        mutableStateOf(setOf<String>())
    }

    var currentPage by remember {
        mutableIntStateOf(0)
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/json"
            )
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult

            try {
                val json = buildExportJson(
                    sessions,
                    multiProfileSupported
                )

                context.contentResolver
                    .openOutputStream(uri)
                    ?.use {
                        it.write(json.toByteArray())
                    }

                Toast.makeText(
                    context,
                    "✅ Export সফল হয়েছে",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "❌ Export ব্যর্থ: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult

            try {
                val json =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use {
                            it.bufferedReader().readText()
                        }
                        ?: return@rememberLauncherForActivityResult

                val imported =
                    parseImportJson(
                        json,
                        multiProfileSupported
                    )

                val existingIds =
                    sessions.map { it.id }.toSet()

                val newOnes =
                    imported.filter {
                        it.id !in existingIds
                    }

                val merged = sessions + newOnes

                sessions = merged
                store.save(merged)

                Toast.makeText(
                    context,
                    "✅ ${newOnes.size} session import হয়েছে",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "❌ Import ব্যর্থ: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    if (selected != null) {
        BrowserScreen(
            session = selected!!,
            multiProfileSupported = multiProfileSupported,
            onBack = {
                selected = null
            },
            onCreateAnother = {
                showCreate = true
            },
            onCUserDetected = { cUser ->
                sessions = sessions.map {
                    if (it.id == selected!!.id) {
                        it.copy(
                            cachedCUser = cUser
                        )
                    } else {
                        it
                    }
                }

                store.save(sessions)
            }
        )

        if (showCreate) {
            val idx = store.nextColorIndex(sessions)

            CreateSessionDialog(
                groups = sessions
                    .map { it.group }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),

                nextSerial =
                    store.nextSerialNumber(sessions),

                defaultColor =
                    colorForIndex(idx),

                onDismiss = {
                    showCreate = false
                },
            ) { name, group, color ->

                val profileName =
                    "profile_${UUID.randomUUID()}"

                val meta =
                    SessionMeta(
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

    // Main screen system Back button:
    // never exits immediately; always asks for confirmation.
    BackHandler(enabled = true) {
        showExitConfirmation = true
    }

    val groups =
        listOf("All", "Ungrouped") +
            sessions
                .map { it.group }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

    val filtered =
        sessions
            .filter { !it.archived }
            .filter { session ->

                val groupOk =
                    when (filter) {
                        "All" -> true
                        "Ungrouped" ->
                            session.group.isBlank()

                        else ->
                            session.group == filter
                    }

                val q = query.trim()

                val matchOk =
                    q.isBlank() ||
                        session.name.contains(
                            q,
                            ignoreCase = true
                        ) ||
                        (
                            q.length >= 6 &&
                                session.cachedCUser.contains(
                                    q,
                                    ignoreCase = true
                                )
                            )

                groupOk && matchOk
            }

    val totalPages =
        ((filtered.size - 1) / PAGE_SIZE + 1)
            .coerceAtLeast(1)

    val safePage =
        currentPage.coerceIn(
            0,
            totalPages - 1
        )

    val pageItems =
        filtered
            .drop(safePage * PAGE_SIZE)
            .take(PAGE_SIZE)

    val allPageSelected =
        pageItems.isNotEmpty() &&
            pageItems.all {
                it.id in selectedIds
            }

    LaunchedEffect(filter, query) {
        currentPage = 0
    }

    Scaffold(
        contentWindowInsets =
            WindowInsets.safeDrawing,

        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Bro Liker",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        Text(
                            "${sessions.count { !it.archived }} sessions",
                            style =
                                MaterialTheme.typography.labelSmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                },

                actions = {

                    IconButton(
                        onClick = {
                            showSettings = true
                        }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            "Settings"
                        )
                    }

                    IconButton(
                        onClick = {
                            showGroupManager = true
                        }
                    ) {
                        Icon(
                            Icons.Default.AccountTree,
                            "Groups"
                        )
                    }

                    var showMore by remember {
                        mutableStateOf(false)
                    }

                    IconButton(
                        onClick = {
                            showMore = true
                        }
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "More"
                        )
                    }

                    DropdownMenu(
                        expanded = showMore,
                        onDismissRequest = {
                            showMore = false
                        }
                    ) {

                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (allPageSelected)
                                        "Deselect page"
                                    else
                                        "Select page"
                                )
                            },

                            leadingIcon = {
                                Icon(
                                    Icons.Default.SelectAll,
                                    null
                                )
                            },

                            onClick = {

                                selectedIds =
                                    if (allPageSelected) {
                                        selectedIds -
                                            pageItems
                                                .map { it.id }
                                                .toSet()
                                    } else {
                                        selectedIds +
                                            pageItems
                                                .map { it.id }
                                                .toSet()
                                    }

                                showMore = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Select all in group"
                                )
                            },

                            leadingIcon = {
                                Icon(
                                    Icons.Default.DoneAll,
                                    null
                                )
                            },

                            onClick = {

                                selectedIds =
                                    selectedIds +
                                        filtered
                                            .map { it.id }
                                            .toSet()

                                showMore = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("Clear selection")
                            },

                            onClick = {
                                selectedIds = emptySet()
                                showMore = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Bulk move to group"
                                )
                            },

                            leadingIcon = {
                                Icon(
                                    Icons.Default.DriveFileMove,
                                    null
                                )
                            },

                            enabled =
                                selectedIds.isNotEmpty(),

                            onClick = {
                                showBulkGroup = true
                                showMore = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("Delete selected")
                            },

                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null
                                )
                            },

                            enabled =
                                selectedIds.isNotEmpty(),

                            onClick = {
                                // IMPORTANT:
                                // Ask confirmation first.
                                pendingDeleteIds =
                                    selectedIds

                                showMore = false
                            }
                        )
                    }
                }
            )
        },

        floatingActionButton = {

            FloatingActionButton(
                onClick = {
                    showCreate = true
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    "New session"
                )
            }
        }

    ) { pad ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {

            Spacer(
                Modifier.height(8.dp)
            )

            OutlinedTextField(
                value = query,

                onValueChange = {
                    query = it
                },

                modifier =
                    Modifier.fillMaxWidth(),

                singleLine = true,

                label = {
                    Text(
                        "Search by name or c_user..."
                    )
                },

                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        null
                    )
                },

                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                null
                            )
                        }
                    }
                },

                shape =
                    RoundedCornerShape(12.dp),
            )

            Spacer(
                Modifier.height(10.dp)
            )

            LazyRow(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),

                contentPadding =
                    PaddingValues(vertical = 4.dp),
            ) {
                items(groups) { group ->

                    val count =
                        when (group) {

                            "All" ->
                                sessions.count {
                                    !it.archived
                                }

                            "Ungrouped" ->
                                sessions.count {
                                    !it.archived &&
                                        it.group.isBlank()
                                }

                            else ->
                                sessions.count {
                                    !it.archived &&
                                        it.group == group
                                }
                        }

                    FilterChip(
                        selected =
                            filter == group,

                        onClick = {
                            filter = group
                        },

                        label = {
                            Text(
                                "$group ($count)"
                            )
                        },

                        leadingIcon =
                            if (filter == group) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            }

            Spacer(
                Modifier.height(8.dp)
            )

            if (selectedIds.isNotEmpty()) {

                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .primaryContainer
                        ),

                    modifier =
                        Modifier.fillMaxWidth(),
                ) {

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 8.dp,
                                vertical = 4.dp
                            ),

                        verticalAlignment =
                            Alignment.CenterVertically,
                    ) {

                        Text(
                            "${selectedIds.size} selected",
                            modifier =
                                Modifier.weight(1f),
                            fontWeight =
                                FontWeight.SemiBold,
                        )

                        TextButton(
                            onClick = {
                                selectedIds =
                                    emptySet()
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }

                Spacer(
                    Modifier.height(8.dp)
                )
            }

            if (filtered.isEmpty()) {

                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {

                        Icon(
                            Icons.Default.Inbox,
                            null,
                            Modifier.size(64.dp),
                            tint =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                                    .copy(alpha = 0.4f),
                        )

                        Spacer(
                            Modifier.height(12.dp)
                        )

                        Text(
                            if (query.isNotBlank())
                                "No sessions found"
                            else
                                "No sessions yet",

                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                        )

                        if (query.isBlank()) {

                            Spacer(
                                Modifier.height(8.dp)
                            )

                            Button(
                                onClick = {
                                    showCreate = true
                                }
                            ) {

                                Icon(
                                    Icons.Default.Add,
                                    null
                                )

                                Spacer(
                                    Modifier.width(6.dp)
                                )

                                Text(
                                    "Create first session"
                                )
                            }
                        }
                    }
                }

            } else {

                LazyColumn(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),

                    contentPadding =
                        PaddingValues(bottom = 96.dp),
                ) {

                    items(
                        pageItems,
                        key = { it.id }
                    ) { session ->

                        SessionCard(
                            session = session,

                            selected =
                                session.id in selectedIds,

                            onSelect = {

                                selectedIds =
                                    if (
                                        session.id in selectedIds
                                    ) {
                                        selectedIds -
                                            session.id
                                    } else {
                                        selectedIds +
                                            session.id
                                    }
                            },

                            onOpen = {

                                val updated =
                                    sessions.map {
                                        if (
                                            it.id ==
                                                session.id
                                        ) {
                                            it.copy(
                                                lastOpenedAt =
                                                    System.currentTimeMillis()
                                            )
                                        } else {
                                            it
                                        }
                                    }

                                sessions = updated
                                store.save(updated)

                                selected =
                                    updated.first {
                                        it.id == session.id
                                    }
                            },

                            onRename = {
                                showRename = session
                            },

                            onDelete = {

                                // IMPORTANT:
                                // Do not delete immediately.
                                // Ask confirmation first.
                                pendingDeleteIds =
                                    setOf(session.id)
                            },

                            onCopyCookies = {
                                copyMultiSessionCookies(
                                    context,
                                    listOf(session),
                                    multiProfileSupported
                                )
                            },
                        )
                    }

                    if (totalPages > 1) {
                        item {

                            PaginationBar(
                                current = safePage,
                                total = totalPages,

                                onPrev = {
                                    currentPage =
                                        (safePage - 1)
                                            .coerceAtLeast(0)
                                },

                                onNext = {
                                    currentPage =
                                        (safePage + 1)
                                            .coerceAtMost(
                                                totalPages - 1
                                            )
                                },

                                onPage = {
                                    currentPage = it
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ─── Dialogs ─────────────────────────────────────────────────────────────

    if (showCreate) {

        val idx =
            store.nextColorIndex(sessions)

        CreateSessionDialog(
            groups =
                sessions
                    .map { it.group }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),

            nextSerial =
                store.nextSerialNumber(sessions),

            defaultColor =
                colorForIndex(idx),

            onDismiss = {
                showCreate = false
            },
        ) { name, group, color ->

            val profileName =
                "profile_${UUID.randomUUID()}"

            val meta =
                SessionMeta(
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

        RenameDialog(
            current = current.name,

            onDismiss = {
                showRename = null
            }

        ) { newName ->

            sessions =
                sessions.map {

                    if (it.id == current.id) {
                        it.copy(
                            name = newName
                        )
                    } else {
                        it
                    }
                }

            store.save(sessions)
            showRename = null
        }
    }

    if (showGroupManager) {

        GroupManagerDialog(
            sessions = sessions,

            currentFilter = filter,

            onDismiss = {
                showGroupManager = false
            },

            onFilter = {
                filter = it
                showGroupManager = false
            },

            onRenameGroup = { old, new ->

                sessions =
                    sessions.map {

                        if (it.group == old) {
                            it.copy(
                                group = new
                            )
                        } else {
                            it
                        }
                    }

                store.save(sessions)

                if (filter == old) {
                    filter = new
                }
            },

            onDeleteGroup = { groupName ->

                // IMPORTANT:
                // Group deletion also requires confirmation.
                pendingDeleteGroup = groupName
            }
        )
    }

    if (showBulkGroup) {

        BulkGroupDialog(
            groups =
                sessions
                    .map { it.group }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),

            onDismiss = {
                showBulkGroup = false
            },

        ) { group ->

            sessions =
                sessions.map {

                    if (it.id in selectedIds) {
                        it.copy(
                            group = group
                        )
                    } else {
                        it
                    }
                }

            store.save(sessions)
            selectedIds = emptySet()
            showBulkGroup = false
        }
    }

    if (showSettings) {

        SettingsDialog(
            sessionCount =
                sessions.count {
                    !it.archived
                },

            onDismiss = {
                showSettings = false
            },

            onExport = {
                showSettings = false

                exportLauncher.launch(
                    "broliker_backup_${System.currentTimeMillis()}.json"
                )
            },

            onImport = {
                showSettings = false

                importLauncher.launch(
                    arrayOf(
                        "application/json",
                        "*/*"
                    )
                )
            },
        )
    }

    val activity =
        context.findActivity()

    // ─── Exit confirmation ───────────────────────────────────────────────────

    if (showExitConfirmation) {

        AlertDialog(
            onDismissRequest = {
                showExitConfirmation = false
            },

            title = {
                Text("Exit Bro Liker?")
            },

            text = {
                Text(
                    "Are you sure you want to exit the app?"
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        showExitConfirmation = false

                        activity?.finish()
                    }
                ) {
                    Text("Exit")
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        showExitConfirmation = false
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // ─── Delete session confirmation ─────────────────────────────────────────

    pendingDeleteIds?.let { ids ->

        val count = ids.size

        AlertDialog(

            onDismissRequest = {
                pendingDeleteIds = null
            },

            title = {
                Text(
                    if (count == 1)
                        "Delete session?"
                    else
                        "Delete selected sessions?"
                )
            },

            text = {

                Text(
                    if (count == 1) {

                        "This session will be deleted permanently. This action cannot be undone."

                    } else {

                        "$count sessions will be deleted permanently. This action cannot be undone."
                    }
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        val idsToDelete =
                            pendingDeleteIds
                                ?: emptySet()

                        val toDelete =
                            sessions.filter {
                                it.id in idsToDelete
                            }

                        sessions =
                            sessions.filterNot {
                                it.id in idsToDelete
                            }

                        store.save(sessions)

                        selectedIds =
                            selectedIds - idsToDelete

                        pendingDeleteIds = null

                        // Preserve the existing profile deletion behavior.
                        if (multiProfileSupported) {

                            toDelete.forEach { session ->

                                runCatching {

                                    ProfileStore
                                        .getInstance()
                                        .deleteProfile(
                                            session.profileName
                                        )
                                }
                            }
                        }
                    }
                ) {

                    Text(
                        "Delete",
                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        pendingDeleteIds = null
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // ─── Delete group confirmation ───────────────────────────────────────────

    pendingDeleteGroup?.let { groupName ->

        val count =
            sessions.count {
                !it.archived &&
                    it.group == groupName
            }

        AlertDialog(

            onDismissRequest = {
                pendingDeleteGroup = null
            },

            title = {
                Text("Delete group?")
            },

            text = {

                Text(

                    if (count > 0) {

                        "Delete the group '$groupName'? The $count sessions will be kept but moved to Ungrouped."

                    } else {

                        "Delete the group '$groupName'?"
                    }
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        sessions =
                            sessions.map {

                                if (
                                    it.group ==
                                        groupName
                                ) {
                                    it.copy(
                                        group = ""
                                    )
                                } else {
                                    it
                                }
                            }

                        store.save(sessions)

                        if (filter == groupName) {
                            filter = "All"
                        }

                        pendingDeleteGroup = null
                    }
                ) {

                    Text(
                        "Delete",
                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        pendingDeleteGroup = null
                    }
                ) {
                    Text("Cancel")
                }
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

    Card(
        modifier =
            Modifier.fillMaxWidth(),
        shape =
            RoundedCornerShape(12.dp)
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),

            verticalAlignment =
                Alignment.CenterVertically,
        ) {

            IconButton(
                onClick = onPrev,
                enabled = current > 0
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    "Previous"
                )
            }

            Row(
                modifier =
                    Modifier.weight(1f),

                horizontalArrangement =
                    Arrangement.Center
            ) {

                val start =
                    (current - 2)
                        .coerceAtLeast(0)

                val end =
                    (start + 5)
                        .coerceAtMost(total)

                for (page in start until end) {

                    PageChip(
                        page = page,
                        current = current,
                        onPage = onPage
                    )
                }
            }

            IconButton(
                onClick = onNext,
                enabled = current < total - 1
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    "Next"
                )
            }
        }
    }
}

@Composable
private fun PageChip(
    page: Int,
    current: Int,
    onPage: (Int) -> Unit,
) {

    Surface(
        modifier =
            Modifier
                .padding(horizontal = 2.dp)
                .clip(CircleShape)
                .clickable {
                    onPage(page)
                },

        color =
            if (page == current)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme
                    .colorScheme
                    .surfaceVariant,

        shape =
            CircleShape,
    ) {

        Box(
            modifier =
                Modifier.size(36.dp),

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "${page + 1}",

                color =
                    if (page == current)
                        MaterialTheme
                            .colorScheme
                            .onPrimary
                    else
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,

                fontWeight =
                    if (page == current)
                        FontWeight.Bold
                    else
                        FontWeight.Normal,
            )
        }
    }
}

// ─── Session card ─────────────────────────────────────────────────────────────

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

    var showMenu by remember {
        mutableStateOf(false)
    }

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected)
                        MaterialTheme
                            .colorScheme
                            .secondaryContainer
                    else
                        MaterialTheme
                            .colorScheme
                            .surface
            ),

        shape =
            RoundedCornerShape(14.dp),
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 10.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically,
        ) {

            Checkbox(
                checked = selected,
                onCheckedChange = {
                    onSelect()
                }
            )

            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .background(
                            hexToColor(session.color),
                            CircleShape
                        )
            ) {

                Text(
                    session.name
                        .take(1)
                        .uppercase(),

                    modifier =
                        Modifier.align(
                            Alignment.Center
                        ),

                    color = Color.White,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                Modifier.width(10.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    session.name,

                    fontWeight =
                        FontWeight.SemiBold,

                    maxLines = 1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                if (session.group.isNotBlank()) {

                    Text(
                        session.group,

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                if (session.cachedCUser.isNotBlank()) {

                    Text(
                        "c_user: ${session.cachedCUser}",

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )
                }
            }

            TextButton(
                onClick = onOpen
            ) {
                Text("Open")
            }

            Box {

                IconButton(
                    onClick = {
                        showMenu = true
                    }
                ) {

                    Icon(
                        Icons.Default.MoreVert,
                        "Session menu"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,

                    onDismissRequest = {
                        showMenu = false
                    }
                ) {

                    DropdownMenuItem(
                        text = {
                            Text("Rename")
                        },

                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                null
                            )
                        },

                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Copy Cookies")
                        },

                        leadingIcon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                null
                            )
                        },

                        onClick = {
                            showMenu = false
                            onCopyCookies()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error
                            )
                        },

                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint =
                                    MaterialTheme
                                        .colorScheme
                                        .error
                            )
                        },

                        onClick = {

                            // Confirmation is handled by parent.
                            showMenu = false
                            onDelete()
                        }
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

    var name by remember {
        mutableStateOf(
            "My Session $nextSerial"
        )
    }

    var group by remember {
        mutableStateOf("")
    }

    var showGroupSuggestions by remember {
        mutableStateOf(false)
    }

    AlertDialog(

        onDismissRequest = onDismiss,

        title = {
            Text("New Session")
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                OutlinedTextField(
                    name,
                    {
                        name = it
                    },

                    label = {
                        Text("Session name")
                    },

                    singleLine = true,

                    modifier =
                        Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    group,
                    {
                        group = it
                    },

                    label = {
                        Text("Group (optional)")
                    },

                    singleLine = true,

                    modifier =
                        Modifier.fillMaxWidth(),

                    trailingIcon =
                        if (groups.isNotEmpty()) {

                            {
                                IconButton(
                                    onClick = {
                                        showGroupSuggestions =
                                            !showGroupSuggestions
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        null
                                    )
                                }
                            }

                        } else {
                            null
                        },
                )

                if (
                    showGroupSuggestions &&
                    groups.isNotEmpty()
                ) {

                    Card(
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Column {

                            groups.forEach { g ->

                                Text(
                                    g,

                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                group = g
                                                showGroupSuggestions =
                                                    false
                                            }
                                            .padding(12.dp),
                                )

                                HorizontalDivider()
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Box(
                        Modifier
                            .size(24.dp)
                            .background(
                                hexToColor(
                                    defaultColor
                                ),
                                CircleShape
                            )
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        "Auto color assigned",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                }

                Text(
                    "Each session has its own isolated browser profile.",

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                )
            }
        },

        confirmButton = {

            Button(
                enabled =
                    name.isNotBlank(),

                onClick = {
                    onCreate(
                        name.trim(),
                        group.trim(),
                        defaultColor
                    )
                }
            ) {
                Text("Create & Open")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },
    )
}

// ─── Rename Dialog ────────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {

    var value by remember {
        mutableStateOf(current)
    }

    AlertDialog(

        onDismissRequest = onDismiss,

        title = {
            Text("Rename Session")
        },

        text = {

            OutlinedTextField(
                value,

                {
                    value = it
                },

                singleLine = true,

                label = {
                    Text("Name")
                },

                modifier =
                    Modifier.fillMaxWidth(),
            )
        },

        confirmButton = {

            Button(
                enabled =
                    value.isNotBlank(),

                onClick = {
                    onSave(value.trim())
                }
            ) {
                Text("Save")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },
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

    val groups =
        sessions
            .map { it.group }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    var renamingGroup by remember {
        mutableStateOf<String?>(null)
    }

    var renameValue by remember {
        mutableStateOf("")
    }

    AlertDialog(

        onDismissRequest = onDismiss,

        title = {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Icon(
                    Icons.Default.AccountTree,
                    null
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text("Manage Groups")
            }
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(4.dp)
            ) {

                GroupRow(
                    name = "All Sessions",

                    count =
                        sessions.count {
                            !it.archived
                        },

                    isSelected =
                        currentFilter == "All",

                    icon =
                        Icons.Default.GridView,

                    onClick = {
                        onFilter("All")
                    },

                    canEdit = false,
                )

                GroupRow(
                    name = "Ungrouped",

                    count =
                        sessions.count {
                            !it.archived &&
                                it.group.isBlank()
                        },

                    isSelected =
                        currentFilter == "Ungrouped",

                    icon =
                        Icons.Default.FolderOpen,

                    onClick = {
                        onFilter("Ungrouped")
                    },

                    canEdit = false,
                )

                if (groups.isNotEmpty()) {

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 4.dp
                            )
                    )

                    Text(
                        "Your Groups",

                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                }

                groups.forEach { group ->

                    if (renamingGroup == group) {

                        OutlinedTextField(

                            value =
                                renameValue,

                            onValueChange = {
                                renameValue = it
                            },

                            singleLine = true,

                            modifier =
                                Modifier.fillMaxWidth(),

                            trailingIcon = {

                                Row {

                                    IconButton(
                                        onClick = {

                                            if (
                                                renameValue
                                                    .isNotBlank()
                                            ) {
                                                onRenameGroup(
                                                    group,
                                                    renameValue
                                                        .trim()
                                                )
                                            }

                                            renamingGroup =
                                                null
                                        }
                                    ) {

                                        Icon(
                                            Icons.Default.Check,
                                            null
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            renamingGroup =
                                                null
                                        }
                                    ) {

                                        Icon(
                                            Icons.Default.Close,
                                            null
                                        )
                                    }
                                }
                            }
                        )

                    } else {

                        GroupRow(

                            name = group,

                            count =
                                sessions.count {
                                    !it.archived &&
                                        it.group == group
                                },

                            isSelected =
                                currentFilter == group,

                            icon =
                                Icons.Default.Folder,

                            onClick = {
                                onFilter(group)
                            },

                            canEdit = true,

                            onEdit = {
                                renamingGroup = group
                                renameValue = group
                            },

                            // The parent displays the
                            // mandatory confirmation dialog.
                            onDelete = {
                                onDeleteGroup(group)
                            },
                        )
                    }
                }

                if (groups.isEmpty()) {

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        "No groups yet.",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("Close")
            }
        },
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

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected)
                        MaterialTheme
                            .colorScheme
                            .secondaryContainer
                    else
                        MaterialTheme
                            .colorScheme
                            .surface
            ),
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically,
        ) {

            Icon(
                icon,
                null
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    name,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Text(
                    "$count sessions",

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            if (canEdit) {

                IconButton(
                    onClick = {
                        onEdit?.invoke()
                    },

                    modifier =
                        Modifier.size(32.dp)
                ) {

                    Icon(
                        Icons.Default.Edit,
                        null,
                        Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = {
                        onDelete?.invoke()
                    },

                    modifier =
                        Modifier.size(32.dp)
                ) {

                    Icon(
                        Icons.Default.Delete,
                        null,
                        Modifier.size(16.dp),
                        tint =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }
            }
        }
    }
}

// ─── Bulk Group Dialog ────────────────────────────────────────────────────────

@Composable
private fun BulkGroupDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {

    var selectedGroup by remember {
        mutableStateOf(
            groups.firstOrNull() ?: ""
        )
    }

    AlertDialog(

        onDismissRequest = onDismiss,

        title = {
            Text("Move selected sessions")
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                groups.forEach { group ->

                    FilterChip(
                        selected =
                            selectedGroup == group,

                        onClick = {
                            selectedGroup = group
                        },

                        label = {
                            Text(group)
                        }
                    )
                }

                FilterChip(
                    selected =
                        selectedGroup.isBlank(),

                    onClick = {
                        selectedGroup = ""
                    },

                    label = {
                        Text("Ungrouped")
                    }
                )
            }
        },

        confirmButton = {

            Button(
                onClick = {
                    onMove(selectedGroup)
                }
            ) {
                Text("Move")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },
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
            Text("Settings")
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    "Sessions: $sessionCount"
                )

                Button(
                    onClick = onExport,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Icon(
                        Icons.Default.Upload,
                        null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text("Export Backup")
                }

                Button(
                    onClick = onImport,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Icon(
                        Icons.Default.Download,
                        null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text("Import Backup")
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("Close")
            }
        }
    )
}

// ─── Browser screen ───────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
    session: SessionMeta,
    multiProfileSupported: Boolean,
    onBack: () -> Unit,
    onCreateAnother: () -> Unit,
    onCUserDetected: (String) -> Unit,
) {

    val context =
        LocalContext.current

    var currentUrl by remember {
        mutableStateOf(HOME_URL)
    }

    var urlBarText by remember {
        mutableStateOf(HOME_URL)
    }

    var pageTitle by remember {
        mutableStateOf("Facebook")
    }

    var isLoading by remember {
        mutableStateOf(true)
    }

    var loadingProgress by remember {
        mutableIntStateOf(0)
    }

    var canBack by remember {
        mutableStateOf(false)
    }

    var canForward by remember {
        mutableStateOf(false)
    }

    var errorMsg by remember {
        mutableStateOf<String?>(null)
    }

    var showMenu by remember {
        mutableStateOf(false)
    }

    var activeWebView by remember {
        mutableStateOf<WebView?>(null)
    }

    var rootRef by remember {
        mutableStateOf<FrameLayout?>(null)
    }

    fun configureProfile(web: WebView) {

        web.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = true

            databaseEnabled = true

            loadsImagesAutomatically = true

            useWideViewPort = true

            loadWithOverviewMode = false

            builtInZoomControls = false

            displayZoomControls = false

            mediaPlaybackRequiresUserGesture = false

            cacheMode =
                WebSettings.LOAD_DEFAULT

            userAgentString =
                WebSettings
                    .getDefaultUserAgent(context)
        }

        web.setLayerType(
            android.view.View.LAYER_TYPE_HARDWARE,
            null
        )
    }

    fun flushAndDetectCUser() {

        runCatching {

            val mgr =
                if (multiProfileSupported) {

                    ProfileStore
                        .getInstance()
                        .getProfile(
                            session.profileName
                        )
                        ?.cookieManager

                } else {
                    CookieManager.getInstance()
                }

            mgr?.flush()
        }

        runCatching {

            val cookieMap =
                collectCookiesForSession(
                    session,
                    multiProfileSupported,
                    currentUrl
                )

            val cUser =
                cookieMap["c_user"]
                    .orEmpty()

            if (cUser.isNotBlank()) {
                onCUserDetected(cUser)
            }
        }
    }

    fun makeClient(
        web: WebView
    ): WebViewClient =
        object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {

                return false
            }

            override fun onPageStarted(
                view: WebView,
                url: String,
                favicon: Bitmap?,
            ) {

                currentUrl = url
                urlBarText = url
                isLoading = true
                errorMsg = null

                canBack =
                    view.canGoBack()

                canForward =
                    view.canGoForward()
            }

            override fun onPageFinished(
                view: WebView,
                url: String,
            ) {

                currentUrl = url
                urlBarText = url
                isLoading = false

                canBack =
                    view.canGoBack()

                canForward =
                    view.canGoForward()

                flushAndDetectCUser()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {

                if (request.isForMainFrame) {

                    isLoading = false

                    errorMsg =
                        "Error ${error.errorCode}: ${error.description}"
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail:
                    android.webkit.RenderProcessGoneDetail,
            ): Boolean {

                isLoading = false

                errorMsg =
                    if (detail.didCrash()) {
                        "WebView crashed. Tap Reload."
                    } else {
                        "WebView stopped. Tap Reload."
                    }

                return true
            }
        }

    fun makeChromeClient(
        webView: WebView
    ): WebChromeClient =
        object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView,
                newProgress: Int,
            ) {

                loadingProgress =
                    newProgress

                isLoading =
                    newProgress < 100

                canBack =
                    view.canGoBack()

                canForward =
                    view.canGoForward()
            }

            override fun onReceivedTitle(
                view: WebView,
                title: String,
            ) {

                if (title.isNotBlank()) {
                    pageTitle = title
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {

                val root =
                    rootRef
                        ?: return false

                val child =
                    WebView(view.context)

                configureProfile(child)

                child.webViewClient =
                    makeClient(child)

                child.webChromeClient =
                    makeChromeClient(child)

                child.layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                activeWebView
                    ?.visibility =
                    android.view.View.GONE

                root.addView(child)

                activeWebView = child

                val transport =
                    resultMsg.obj
                        as? WebView.WebViewTransport
                        ?: return false

                transport.webView = child
                resultMsg.sendToTarget()

                return true
            }
        }

    fun navigateTo(
        input: String
    ) {

        val trimmed =
            input.trim()

        if (trimmed.isBlank()) return

        val url =
            when {

                trimmed.startsWith(
                    "https://",
                    true
                ) ||
                    trimmed.startsWith(
                        "http://",
                        true
                    ) ->
                    trimmed

                trimmed.contains(".") ->
                    "https://$trimmed"

                else ->
                    "https://www.google.com/search?q=" +
                        URLEncoder.encode(
                            trimmed,
                            "UTF-8"
                        )
            }

        activeWebView?.loadUrl(url)

        urlBarText = url
    }

    fun closePopupIfPossible(): Boolean {

        val root =
            rootRef
                ?: return false

        val current =
            activeWebView
                ?: return false

        if (root.childCount <= 1) {
            return false
        }

        root.removeView(current)

        current.stopLoading()
        current.destroy()

        val parent =
            root.getChildAt(
                root.childCount - 1
            ) as? WebView

        parent?.let {

            it.visibility =
                android.view.View.VISIBLE

            activeWebView = it

            canBack =
                it.canGoBack()

            canForward =
                it.canGoForward()

            currentUrl =
                it.url
                    ?: HOME_URL

            urlBarText =
                currentUrl
        }

        return true
    }

    // ─── System Back handling ────────────────────────────────────────────────
    //
    // 1. If a popup WebView is open -> close popup.
    // 2. Otherwise if WebView has history -> go back in the WebView.
    // 3. Otherwise leave the current session and return to session list.
    //
    BackHandler(enabled = true) {

        when {

            closePopupIfPossible() -> {
                // Popup closed.
            }

            activeWebView?.canGoBack() == true -> {
                activeWebView?.goBack()
            }

            else -> {
                flushAndDetectCUser()
                onBack()
            }
        }
    }

    Scaffold(
        contentWindowInsets =
            WindowInsets.safeDrawing,

        topBar = {

            Column {

                TopAppBar(

                    navigationIcon = {

                        IconButton(
                            onClick = {

                                if (!closePopupIfPossible()) {
                                    flushAndDetectCUser()
                                    onBack()
                                }
                            }
                        ) {

                            Icon(
                                Icons.Default.ArrowBack,
                                "Close"
                            )
                        }
                    },

                    title = {

                        OutlinedTextField(
                            value = urlBarText,

                            onValueChange = {
                                urlBarText = it
                            },

                            modifier =
                                Modifier.fillMaxWidth(),

                            singleLine = true,

                            placeholder = {
                                Text(
                                    "URL or search..."
                                )
                            },

                            keyboardOptions =
                                KeyboardOptions(
                                    imeAction =
                                        ImeAction.Go
                                ),

                            keyboardActions =
                                KeyboardActions(
                                    onGo = {
                                        navigateTo(
                                            urlBarText
                                        )
                                    }
                                ),

                            textStyle =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                        )
                    },

                    actions = {

                        IconButton(
                            onClick = {
                                errorMsg = null
                                activeWebView?.reload()
                            }
                        ) {

                            Icon(
                                Icons.Default.Refresh,
                                "Reload"
                            )
                        }

                        IconButton(
                            onClick = {
                                showMenu = true
                            }
                        ) {

                            Icon(
                                Icons.Default.MoreVert,
                                "More"
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,

                            onDismissRequest = {
                                showMenu = false
                            }
                        ) {

                            DropdownMenuItem(
                                text = {
                                    Text("Back")
                                },

                                enabled = canBack,

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        null
                                    )
                                },

                                onClick = {

                                    activeWebView?.goBack()

                                    showMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text("Forward")
                                },

                                enabled = canForward,

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        null
                                    )
                                },

                                onClick = {

                                    activeWebView?.goForward()

                                    showMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text("Facebook Home")
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Home,
                                        null
                                    )
                                },

                                onClick = {

                                    navigateTo(
                                        HOME_URL
                                    )

                                    showMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text("New session")
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Add,
                                        null
                                    )
                                },

                                onClick = {

                                    showMenu = false

                                    onCreateAnother()
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "📋 Copy Cookies (JSON)"
                                    )
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        null
                                    )
                                },

                                onClick = {

                                    showMenu = false

                                    flushAndDetectCUser()

                                    copySingleSessionCookies(
                                        context,
                                        session,
                                        multiProfileSupported,
                                        currentUrl,
                                        pageTitle
                                    )
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text("Close")
                                },

                                onClick = {

                                    showMenu = false

                                    flushAndDetectCUser()

                                    onBack()
                                }
                            )
                        }
                    }
                )

                if (isLoading) {

                    LinearProgressIndicator(
                        progress = {
                            loadingProgress / 100f
                        },

                        modifier =
                            Modifier.fillMaxWidth(),
                    )
                }
            }
        }

    ) { pad ->

        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {

            AndroidView(

                modifier =
                    Modifier.fillMaxSize(),

                factory = { ctx ->

                    FrameLayout(ctx).also { root ->

                        rootRef = root

                        val initial =
                            WebView(ctx)

                        configureProfile(initial)

                        initial.webViewClient =
                            makeClient(initial)

                        initial.webChromeClient =
                            makeChromeClient(initial)

                        initial.layoutParams =
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                        root.addView(initial)

                        activeWebView = initial

                        initial.loadUrl(HOME_URL)
                    }
                },

                update = { root ->

                    rootRef = root

                    val child =
                        root.getChildAt(
                            root.childCount - 1
                        ) as? WebView

                    if (
                        child != null &&
                        activeWebView == null
                    ) {
                        activeWebView = child
                    }
                },

                onRelease = { root ->

                    for (
                        i in root.childCount - 1 downTo 0
                    ) {

                        (
                            root.getChildAt(i)
                                as? WebView
                            )?.let { web ->

                                runCatching {

                                    web.stopLoading()

                                    web.loadUrl(
                                        "about:blank"
                                    )

                                    web.removeAllViews()

                                    web.destroy()
                                }
                            }
                    }

                    root.removeAllViews()

                    activeWebView = null
                    rootRef = null
                }
            )

            errorMsg?.let { err ->

                Column(

                    Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme
                                .colorScheme
                                .errorContainer
                                .copy(alpha = 0.96f)
                        )
                        .padding(24.dp),

                    verticalArrangement =
                        Arrangement.Center,

                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                ) {

                    Text(
                        "Page failed to load",

                        fontWeight =
                            FontWeight.Bold,

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        err,

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        currentUrl,

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall
                    )

                    Spacer(
                        Modifier.height(16.dp)
                    )

                    Button(
                        onClick = {

                            errorMsg = null

                            activeWebView?.reload()
                        }
                    ) {
                        Text("Reload")
                    }
                }
            }
        }
    }
}