package com.broliker.multisession.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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

                add(
                    SessionMeta(
                        id = o.getString("id"),
                        profileName = o.getString("profileName"),
                        name = o.getString("name"),
                        group = o.optString("group"),
                        createdAt = o.optLong("createdAt"),
                        lastOpenedAt = o.optLong("lastOpenedAt"),
                        archived = o.optBoolean("archived", false),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
        .sortedBy { it.name.lowercase() }

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
                }
            )
        }

        prefs.edit()
            .putString("items", arr.toString())
            .apply()
    }
}

private fun buildSafeSessionInfoJson(
    session: SessionMeta,
    currentUrl: String,
    pageTitle: String,
): String {
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
        put("archived", session.archived)
        put(
            "export_note",
            "Session metadata only. Authentication cookies/tokens are not exported."
        )
    }

    return json.toString(2)
}

private fun copySafeSessionInfo(
    context: Context,
    session: SessionMeta,
    currentUrl: String,
    pageTitle: String,
) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val json = buildSafeSessionInfoJson(
        session = session,
        currentUrl = currentUrl,
        pageTitle = pageTitle,
    )

    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            "Bro Liker Session Info",
            json,
        )
    )

    Toast.makeText(
        context,
        "Session info copied",
        Toast.LENGTH_SHORT,
    ).show()
}

private fun getCookiesAsJson(
    context: Context,
    session: SessionMeta,
    currentUrl: String,
    multiProfileSupported: Boolean,
): String {
    val rawCookies: String? = if (multiProfileSupported) {
        runCatching {
            val profileStore = ProfileStore.getInstance()
            profileStore.getProfile(session.profileName)
                ?.cookieManager
                ?.getCookie(currentUrl)
        }.getOrNull()
    } else {
        CookieManager.getInstance().getCookie(currentUrl)
    }

    val json = JSONObject()

    if (!rawCookies.isNullOrBlank()) {
        rawCookies
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    json.put(key, value)
                }
            }
    }

    return json.toString(2)
}

private fun copyCookieInfo(
    context: Context,
    session: SessionMeta,
    currentUrl: String,
    multiProfileSupported: Boolean,
) {
    val json = getCookiesAsJson(
        context = context,
        session = session,
        currentUrl = currentUrl,
        multiProfileSupported = multiProfileSupported,
    )

    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            "Bro Liker Cookie Info",
            json,
        )
    )

    Toast.makeText(
        context,
        "Cookie info copied",
        Toast.LENGTH_SHORT,
    ).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroLikerApp() {
    val context = LocalContext.current.applicationContext

    val store = remember {
        SessionStore(context)
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

    var showGroup by remember {
        mutableStateOf(false)
    }

    var showBulkGroup by remember {
        mutableStateOf(false)
    }

    var showMore by remember {
        mutableStateOf(false)
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

    if (selected != null) {
        BrowserScreen(
            session = selected!!,
            onBack = {
                selected = null
            },
            onCreateAnother = {
                showCreate = true
            },
        )

        if (showCreate) {
            CreateSessionDialog(
                groups = sessions
                    .map { it.group }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted(),
                onDismiss = {
                    showCreate = false
                },
            ) { name, group ->

                val profileName = "profile_${UUID.randomUUID()}"

                val meta = SessionMeta(
                    id = UUID.randomUUID().toString(),
                    profileName = profileName,
                    name = name,
                    group = group,
                    createdAt = System.currentTimeMillis(),
                )

                sessions = sessions + meta
                store.save(sessions)

                selected = meta
                showCreate = false
            }
        }

        return
    }

    val groups =
        listOf("All", "Ungrouped") +
            sessions
                .map { it.group }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

    val visible = sessions
        .filter { !it.archived }
        .filter { session ->

            val groupOk = when (filter) {
                "All" -> true
                "Ungrouped" -> session.group.isBlank()
                else -> session.group == filter
            }

            groupOk &&
                (
                    query.isBlank() ||
                        session.name.contains(
                            query,
                            ignoreCase = true,
                        )
                    )
        }

    val allVisibleSelected =
        visible.isNotEmpty() &&
            visible.all { it.id in selectedIds }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,

        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Bro Liker",
                            fontWeight = FontWeight.Bold,
                        )

                        Text(
                            "${sessions.count { !it.archived }} sessions",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },

                actions = {

                    IconButton(
                        onClick = {
                            showCreate = true
                        }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Create session",
                        )
                    }

                    IconButton(
                        onClick = {
                            showGroup = true
                        }
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Groups",
                        )
                    }

                    IconButton(
                        onClick = {
                            showMore = true
                        }
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                        )
                    }

                    DropdownMenu(
                        expanded = showMore,
                        onDismissRequest = {
                            showMore = false
                        },
                    ) {

                        DropdownMenuItem(
                            text = {
                                Text("Select all visible")
                            },

                            leadingIcon = {
                                Icon(
                                    Icons.Default.SelectAll,
                                    null,
                                )
                            },

                            onClick = {

                                selectedIds =
                                    if (allVisibleSelected) {
                                        emptySet()
                                    } else {
                                        visible
                                            .map { it.id }
                                            .toSet()
                                    }

                                showMore = false
                            },
                        )

                        DropdownMenuItem(
                            text = {
                                Text("Clear selection")
                            },

                            onClick = {
                                selectedIds = emptySet()
                                showMore = false
                            },
                        )

                        DropdownMenuItem(
                            text = {
                                Text("Bulk move to group")
                            },

                            leadingIcon = {
                                Icon(
                                    Icons.Default.SwapVert,
                                    null,
                                )
                            },

                            enabled = selectedIds.isNotEmpty(),

                            onClick = {
                                showBulkGroup = true
                                showMore = false
                            },
                        )
                    }
                },
            )
        },

        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showCreate = true
                },
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Create session",
                )
            }
        },
    ) { pad ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
        ) {

            OutlinedTextField(
                value = query,

                onValueChange = {
                    query = it
                },

                modifier = Modifier.fillMaxWidth(),

                singleLine = true,

                label = {
                    Text("Search sessions")
                },

                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        null,
                    )
                },
            )

            Spacer(
                Modifier.height(10.dp)
            )

            LazyColumn(
                horizontalAlignment = Alignment.Start,

                verticalArrangement = Arrangement.spacedBy(8.dp),

                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        bottom = 96.dp,
                    ),
            ) {

                item {

                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        groups
                            .take(6)
                            .forEach { group ->

                                FilterChip(
                                    selected = filter == group,

                                    onClick = {
                                        filter = group
                                    },

                                    label = {
                                        Text(group)
                                    },
                                )
                            }
                    }
                }

                items(
                    visible,
                    key = {
                        it.id
                    }
                ) { session ->

                    SessionCard(
                        session = session,

                        selected =
                            session.id in selectedIds,

                        onSelect = {

                            selectedIds =
                                if (session.id in selectedIds) {
                                    selectedIds - session.id
                                } else {
                                    selectedIds + session.id
                                }
                        },

                        onOpen = {

                            val updated =
                                sessions.map {

                                    if (it.id == session.id) {
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

                            sessions =
                                sessions.filterNot {
                                    it.id == session.id
                                }

                            store.save(sessions)

                            if (
                                WebViewFeature.isFeatureSupported(
                                    WebViewFeature.MULTI_PROFILE
                                )
                            ) {
                                runCatching {
                                    ProfileStore
                                        .getInstance()
                                        .deleteProfile(
                                            session.profileName
                                        )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateSessionDialog(
            groups = sessions
                .map { it.group }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted(),

            onDismiss = {
                showCreate = false
            },
        ) { name, group ->

            val profileName =
                "profile_${UUID.randomUUID()}"

            val meta = SessionMeta(
                id = UUID.randomUUID().toString(),
                profileName = profileName,
                name = name,
                group = group,
                createdAt = System.currentTimeMillis(),
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
            },
        ) { newName ->

            sessions =
                sessions.map {

                    if (it.id == current.id) {
                        it.copy(name = newName)
                    } else {
                        it
                    }
                }

            store.save(sessions)
            showRename = null
        }
    }

    if (showGroup) {

        GroupManagerDialog(
            groups = sessions
                .map { it.group }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted(),

            onDismiss = {
                showGroup = false
            },

            onFilter = {
                filter = it
                showGroup = false
            },
        )
    }

    if (showBulkGroup) {

        BulkGroupDialog(
            groups = sessions
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
                        it.copy(group = group)
                    } else {
                        it
                    }
                }

            store.save(sessions)

            selectedIds = emptySet()

            showBulkGroup = false
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionMeta,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth()
    ) {

        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),

            verticalAlignment =
                Alignment.CenterVertically,
        ) {

            Checkbox(
                checked = selected,

                onCheckedChange = {
                    onSelect()
                },
            )

            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp),
                    ),

                contentAlignment =
                    Alignment.Center,
            ) {

                Text(
                    "F",

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary,

                    fontWeight =
                        FontWeight.Bold,
                )
            }

            Spacer(
                Modifier.size(10.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    session.name,

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    fontWeight =
                        FontWeight.SemiBold,
                )

                Text(
                    if (session.group.isBlank()) {
                        "Ungrouped"
                    } else {
                        session.group
                    },

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

            TextButton(
                onClick = onOpen
            ) {
                Text("Open")
            }

            IconButton(
                onClick = onRename
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Rename",
                )
            }

            IconButton(
                onClick = onDelete
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                )
            }
        }
    }
}

@Composable
private fun CreateSessionDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember {
        mutableStateOf(
            "Session ${System.currentTimeMillis() % 100000}"
        )
    }

    var group by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("Create new session")
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
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
                )

                OutlinedTextField(
                    group,
                    {
                        group = it
                    },

                    label = {
                        Text(
                            if (groups.isEmpty()) {
                                "Group (optional)"
                            } else {
                                "Group"
                            }
                        )
                    },

                    singleLine = true,
                )

                Text(
                    "Each session gets its own persistent browser profile.",

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
                enabled = name.isNotBlank(),

                onClick = {
                    onCreate(
                        name.trim(),
                        group.trim(),
                    )
                },
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
            Text("Rename session")
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
            )
        },

        confirmButton = {

            Button(
                enabled = value.isNotBlank(),

                onClick = {
                    onSave(value.trim())
                },
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

@Composable
private fun GroupManagerDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onFilter: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("Groups")
        },

        text = {

            Column {

                TextButton(
                    onClick = {
                        onFilter("All")
                    }
                ) {
                    Text("All sessions")
                }

                TextButton(
                    onClick = {
                        onFilter("Ungrouped")
                    }
                ) {
                    Text("Ungrouped")
                }

                groups.forEach { group ->

                    TextButton(
                        onClick = {
                            onFilter(group)
                        }
                    ) {
                        Text(group)
                    }
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
private fun BulkGroupDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var group by remember {
        mutableStateOf("")
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

                OutlinedTextField(
                    value = group,

                    onValueChange = {
                        group = it
                    },

                    label = {
                        Text("Group name")
                    },

                    singleLine = true,
                )

                if (groups.isNotEmpty()) {

                    Text(
                        "Existing groups",

                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,
                    )

                    groups.forEach { existing ->

                        TextButton(
                            onClick = {
                                group = existing
                            }
                        ) {
                            Text(existing)
                        }
                    }
                }
            }
        },

        confirmButton = {

            Button(
                enabled = group.isNotBlank(),

                onClick = {
                    onApply(group.trim())
                },
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

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    session: SessionMeta,
    onBack: () -> Unit,
    onCreateAnother: () -> Unit,
) {
    val context = LocalContext.current

    var rootRef by remember {
        mutableStateOf<FrameLayout?>(null)
    }

    var activeWebView by remember {
        mutableStateOf<WebView?>(null)
    }

    var canBack by remember {
        mutableStateOf(false)
    }

    var canForward by remember {
        mutableStateOf(false)
    }

    var showMenu by remember {
        mutableStateOf(false)
    }

    var currentUrl by remember {
        mutableStateOf(HOME_URL)
    }

    var urlBarText by remember {
        mutableStateOf(HOME_URL)
    }

    var loadingProgress by remember {
        mutableIntStateOf(0)
    }

    var isLoading by remember {
        mutableStateOf(true)
    }

    var errorMsg by remember {
        mutableStateOf<String?>(null)
    }

    var pageTitle by remember {
        mutableStateOf("Facebook")
    }

    val multiProfileSupported = remember {
        WebViewFeature.isFeatureSupported(
            WebViewFeature.MULTI_PROFILE
        )
    }

    fun profileStore(): ProfileStore {
        return ProfileStore.getInstance()
    }

    fun configureProfile(
        webView: WebView
    ) {

        if (multiProfileSupported) {

            runCatching {

                profileStore()
                    .getOrCreateProfile(
                        session.profileName
                    )

                WebViewCompat.setProfile(
                    webView,
                    session.profileName
                )
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

            mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            mediaPlaybackRequiresUserGesture = false

            cacheMode =
                WebSettings.LOAD_DEFAULT
        }

        if (multiProfileSupported) {

            runCatching {

                profileStore()
                    .getProfile(session.profileName)
                    ?.cookieManager
                    ?.apply {

                        setAcceptCookie(true)

                        setAcceptThirdPartyCookies(
                            webView,
                            true,
                        )
                    }
            }

        } else {

            CookieManager
                .getInstance()
                .apply {

                    setAcceptCookie(true)

                    setAcceptThirdPartyCookies(
                        webView,
                        true,
                    )
                }
        }
    }

    fun flushProfileCookies() {

        if (multiProfileSupported) {

            runCatching {

                profileStore()
                    .getProfile(session.profileName)
                    ?.cookieManager
                    ?.flush()
            }

        } else {

            CookieManager
                .getInstance()
                .flush()
        }
    }

    fun makeClient(
        webView: WebView
    ): WebViewClient {

        return object : WebViewClient() {

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

                canBack = view.canGoBack()
                canForward = view.canGoForward()
            }

            override fun onPageFinished(
                view: WebView,
                url: String,
            ) {

                currentUrl = url
                urlBarText = url

                isLoading = false

                canBack = view.canGoBack()
                canForward = view.canGoForward()

                flushProfileCookies()
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
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {

                isLoading = false

                errorMsg =
                    if (detail.didCrash()) {
                        "WebView renderer crashed. Tap Reload."
                    } else {
                        "WebView renderer stopped. Tap Reload."
                    }

                return true
            }
        }
    }

    fun makeChromeClient(
        webView: WebView
    ): WebChromeClient {

        return object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView,
                newProgress: Int,
            ) {

                loadingProgress = newProgress

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
                    rootRef ?: return false

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
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                activeWebView?.visibility =
                    android.view.View.GONE

                root.addView(child)

                activeWebView = child

                val transport =
                    resultMsg.obj as? WebView.WebViewTransport
                        ?: return false

                transport.webView = child

                resultMsg.sendToTarget()

                return true
            }
        }
    }

    fun navigateTo(
        input: String
    ) {

        val trimmed =
            input.trim()

        if (trimmed.isBlank()) {
            return
        }

        val url =
            when {

                trimmed.startsWith(
                    "https://",
                    true,
                ) ||
                    trimmed.startsWith(
                        "http://",
                        true,
                    ) -> {
                    trimmed
                }

                trimmed.contains(".") -> {
                    "https://$trimmed"
                }

                else -> {

                    "https://www.google.com/search?q=" +
                        URLEncoder.encode(
                            trimmed,
                            "UTF-8",
                        )
                }
            }

        activeWebView?.loadUrl(url)

        urlBarText = url
    }

    fun closePopupIfPossible(): Boolean {

        val root =
            rootRef ?: return false

        val current =
            activeWebView ?: return false

        val children =
            root.childCount

        if (children <= 1) {
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
                it.url ?: HOME_URL

            urlBarText =
                currentUrl
        }

        return true
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

                                if (
                                    !closePopupIfPossible()
                                ) {

                                    flushProfileCookies()

                                    onBack()
                                }
                            }
                        ) {

                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Close",
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
                                contentDescription = "Reload",
                            )
                        }

                        IconButton(
                            onClick = {
                                showMenu = true
                            }
                        ) {

                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More",
                            )
                        }

                        DropdownMenu(

                            expanded = showMenu,

                            onDismissRequest = {
                                showMenu = false
                            },
                        ) {

                            DropdownMenuItem(

                                text = {
                                    Text("Back")
                                },

                                enabled = canBack,

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        null,
                                    )
                                },

                                onClick = {

                                    activeWebView?.goBack()

                                    showMenu = false
                                },
                            )

                            DropdownMenuItem(

                                text = {
                                    Text("Forward")
                                },

                                enabled = canForward,

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        null,
                                    )
                                },

                                onClick = {

                                    activeWebView?.goForward()

                                    showMenu = false
                                },
                            )

                            DropdownMenuItem(

                                text = {
                                    Text("Facebook Home")
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Home,
                                        null,
                                    )
                                },

                                onClick = {

                                    navigateTo(
                                        HOME_URL
                                    )

                                    showMenu = false
                                },
                            )

                            DropdownMenuItem(

                                text = {
                                    Text("Create another session")
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                    )
                                },

                                onClick = {

                                    showMenu = false

                                    onCreateAnother()
                                },
                            )

                            DropdownMenuItem(

                                text = {
                                    Text("Copy Session Info")
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        null,
                                    )
                                },

                                onClick = {

                                    showMenu = false

                                    flushProfileCookies()

                                    copySafeSessionInfo(
                                        context = context,
                                        session = session,
                                        currentUrl = currentUrl,
                                        pageTitle = pageTitle,
                                    )
                                },
                            )

                            DropdownMenuItem(

                                text = {
                                    Text("Copy Cookie Info")
                                },

                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        null,
                                    )
                                },

                                onClick = {

                                    showMenu = false

                                    flushProfileCookies()

                                    copyCookieInfo(
                                        context = context,
                                        session = session,
                                        currentUrl = currentUrl,
                                        multiProfileSupported = multiProfileSupported,
                                    )
                                },
                            )

                            DropdownMenuItem(

                                text = {
                                    Text("Close")
                                },

                                onClick = {

                                    showMenu = false

                                    flushProfileCookies()

                                    onBack()
                                },
                            )
                        }
                    },
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
        },
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
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )

                        root.addView(initial)

                        activeWebView = initial

                        initial.loadUrl(
                            HOME_URL
                        )
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
                },
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

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,

                        fontWeight =
                            FontWeight.Bold,
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        err,

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        currentUrl,

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
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