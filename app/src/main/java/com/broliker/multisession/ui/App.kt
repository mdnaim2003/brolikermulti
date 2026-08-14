package com.broliker.multisession.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

private const val HOME_URL = "https://mbasic.facebook.com/"
private const val PAGE_SIZE = 50

private data class SessionMeta(
    val id: String,
    val profileName: String,
    val name: String,
    val group: String,
    val createdAt: Long,
    val lastOpenedAt: Long = 0L,
    val archived: Boolean = false,
)

private class SessionStore(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            "bro_sessions",
            Context.MODE_PRIVATE,
        )

    fun load(): List<SessionMeta> =
        runCatching {
            val arr =
                JSONArray(
                    prefs.getString(
                        "items",
                        "[]",
                    ),
                )

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
                            lastOpenedAt = o.optLong(
                                "lastOpenedAt",
                            ),
                            archived = o.optBoolean(
                                "archived",
                                false,
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

    fun save(
        items: List<SessionMeta>,
    ) {
        val arr = JSONArray()

        items.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put(
                        "profileName",
                        s.profileName,
                    )
                    put("name", s.name)
                    put("group", s.group)
                    put(
                        "createdAt",
                        s.createdAt,
                    )
                    put(
                        "lastOpenedAt",
                        s.lastOpenedAt,
                    )
                    put(
                        "archived",
                        s.archived,
                    )
                },
            )
        }

        prefs.edit()
            .putString(
                "items",
                arr.toString(),
            )
            .apply()
    }
}

private fun nextSessionNumber(
    sessions: List<SessionMeta>,
): Int {
    var maxNumber = 0

    sessions.forEach { session ->
        val match =
            Regex(
                "^My Session (\\d+)$",
            ).find(session.name.trim())

        if (match != null) {
            val number =
                match.groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: 0

            maxNumber =
                max(
                    maxNumber,
                    number,
                )
        }
    }

    return maxNumber + 1
}

private fun uniqueImportedName(
    existing: List<SessionMeta>,
    base: String,
): String {
    val names =
        existing
            .map { it.name.lowercase() }
            .toMutableSet()

    if (!names.contains(base.lowercase())) {
        return base
    }

    var index = 2

    while (
        names.contains(
            "$base ($index)".lowercase(),
        )
    ) {
        index++
    }

    return "$base ($index)"
}

private fun buildBackupJson(
    sessions: List<SessionMeta>,
): String {
    val root =
        JSONObject().apply {
            put(
                "format",
                "bro_liker_session_backup",
            )
            put(
                "version",
                1,
            )
            put(
                "created_at",
                System.currentTimeMillis(),
            )
            put(
                "session_count",
                sessions.size,
            )
            put(
                "credentials_included",
                false,
            )
            put(
                "note",
                "Session metadata/profile references only. Authentication cookies or tokens are not exported.",
            )

            val items =
                JSONArray()

            sessions.forEach { s ->
                items.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put(
                            "profile_name",
                            s.profileName,
                        )
                        put(
                            "name",
                            s.name,
                        )
                        put(
                            "group",
                            s.group,
                        )
                        put(
                            "created_at",
                            s.createdAt,
                        )
                        put(
                            "last_opened_at",
                            s.lastOpenedAt,
                        )
                        put(
                            "archived",
                            s.archived,
                        )
                    },
                )
            }

            put(
                "sessions",
                items,
            )
        }

    return root.toString(2)
}

private fun importBackupJson(
    existing: List<SessionMeta>,
    raw: String,
): List<SessionMeta> {
    val root =
        JSONObject(raw)

    if (
        root.optString("format") !=
        "bro_liker_session_backup"
    ) {
        throw IllegalArgumentException(
            "Invalid Bro Liker backup file.",
        )
    }

    val arr =
        root.optJSONArray("sessions")
            ?: throw IllegalArgumentException(
                "No sessions found in backup.",
            )

    val result =
        existing.toMutableList()

    for (i in 0 until arr.length()) {
        val item =
            arr.getJSONObject(i)

        val baseName =
            item.optString(
                "name",
                "Imported Session",
            )

        val name =
            uniqueImportedName(
                result,
                baseName,
            )

        val profileName =
            "profile_${UUID.randomUUID()}"

        result +=
            SessionMeta(
                id = UUID.randomUUID().toString(),
                profileName = profileName,
                name = name,
                group = item.optString(
                    "group",
                ),
                createdAt =
                    System.currentTimeMillis(),
                lastOpenedAt = 0L,
                archived =
                    item.optBoolean(
                        "archived",
                        false,
                    ),
            )
    }

    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroLikerApp() {
    val context =
        LocalContext.current.applicationContext

    val store =
        remember {
            SessionStore(context)
        }

    var sessions by remember {
        mutableStateOf(
            store.load(),
        )
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

    var showMore by remember {
        mutableStateOf(false)
    }

    var showSettings by remember {
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

    var page by remember {
        mutableIntStateOf(0)
    }

    var importMessage by remember {
        mutableStateOf<String?>(null)
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.CreateDocument(
                    "application/json",
                ),
        ) { uri: Uri? ->

            if (uri != null) {
                runCatching {
                    context.contentResolver
                        .openOutputStream(uri)
                        ?.use { output ->

                            output.write(
                                buildBackupJson(
                                    sessions,
                                ).toByteArray(
                                    Charsets.UTF_8,
                                ),
                            )
                        }
                }.onSuccess {
                    importMessage =
                        "Backup exported successfully."
                }.onFailure {
                    importMessage =
                        "Export failed: ${it.message}"
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->

            if (uri != null) {
                runCatching {
                    val raw =
                        context.contentResolver
                            .openInputStream(uri)
                            ?.use {
                                it.readBytes()
                                    .toString(
                                        Charsets.UTF_8,
                                    )
                            }
                            ?: throw IllegalArgumentException(
                                "Unable to read file.",
                            )

                    val imported =
                        importBackupJson(
                            sessions,
                            raw,
                        )

                    sessions = imported

                    store.save(
                        imported,
                    )

                    page = 0

                    imported
                }.onSuccess { imported ->
                    importMessage =
                        "Imported ${imported.size - sessions.size + imported.size} sessions."
                }.onFailure {
                    importMessage =
                        "Import failed: ${it.message}"
                }
            }
        }

    LaunchedEffect(
        query,
        filter,
    ) {
        page = 0
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
                groups =
                    sessions
                        .map { it.group }
                        .filter {
                            it.isNotBlank()
                        }
                        .distinct()
                        .sorted(),
                suggestedName =
                    "My Session ${
                        nextSessionNumber(
                            sessions,
                        )
                    }",
                onDismiss = {
                    showCreate = false
                },
            ) { name, group ->

                val finalName =
                    if (name.isBlank()) {
                        "My Session ${
                            nextSessionNumber(
                                sessions,
                            )
                        }"
                    } else {
                        name.trim()
                    }

                val profileName =
                    "profile_${UUID.randomUUID()}"

                val meta =
                    SessionMeta(
                        id =
                            UUID.randomUUID()
                                .toString(),
                        profileName =
                            profileName,
                        name = finalName,
                        group = group,
                        createdAt =
                            System.currentTimeMillis(),
                    )

                sessions =
                    sessions + meta

                store.save(sessions)

                selected = meta
                showCreate = false
            }
        }

        return
    }

    val groups =
        sessions
            .map {
                it.group.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .sorted()

    val filtered =
        sessions
            .filter {
                !it.archived
            }
            .filter { session ->

                val groupOk =
                    when {
                        filter == "All" ->
                            true

                        filter ==
                            "Ungrouped" ->
                            session.group.isBlank()

                        else ->
                            session.group ==
                                filter
                    }

                val searchOk =
                    query.isBlank() ||
                        session.name.contains(
                            query,
                            ignoreCase = true,
                        )

                groupOk && searchOk
            }

    val totalPages =
        max(
            1,
            (
                filtered.size +
                    PAGE_SIZE -
                    1
                ) /
                    PAGE_SIZE,
        )

    if (page >= totalPages) {
        page =
            totalPages - 1
    }

    val fromIndex =
        page * PAGE_SIZE

    val toIndex =
        minOf(
            fromIndex + PAGE_SIZE,
            filtered.size,
        )

    val visible =
        if (fromIndex < toIndex) {
            filtered.subList(
                fromIndex,
                toIndex,
            )
        } else {
            emptyList()
        }

    val allVisibleSelected =
        visible.isNotEmpty() &&
            visible.all {
                it.id in selectedIds
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
                            fontWeight =
                                FontWeight.Bold,
                        )

                        Text(
                            "${filtered.size} sessions • Page ${page + 1}/$totalPages",
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                            maxLines = 1,
                            overflow =
                                TextOverflow.Ellipsis,
                        )
                    }
                },

                actions = {

                    IconButton(
                        onClick = {
                            showCreate = true
                        },
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription =
                                "Create session",
                        )
                    }

                    IconButton(
                        onClick = {
                            showGroupManager = true
                        },
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription =
                                "Groups",
                        )
                    }

                    IconButton(
                        onClick = {
                            showSettings = true
                        },
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription =
                                "Settings",
                        )
                    }

                    IconButton(
                        onClick = {
                            showMore = true
                        },
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription =
                                "More",
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
                                Text(
                                    "Select all on this page",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.SelectAll,
                                    null,
                                )
                            },
                            onClick = {
                                selectedIds =
                                    if (
                                        allVisibleSelected
                                    ) {
                                        selectedIds -
                                            visible.map {
                                                it.id
                                            }
                                    } else {
                                        selectedIds +
                                            visible.map {
                                                it.id
                                            }
                                    }

                                showMore = false
                            },
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Clear selection",
                                )
                            },
                            onClick = {
                                selectedIds =
                                    emptySet()
                                showMore = false
                            },
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Bulk move to group",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.SwapVert,
                                    null,
                                )
                            },
                            enabled =
                                selectedIds
                                    .isNotEmpty(),
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
                    contentDescription =
                        "Create session",
                )
            }
        },

    ) { pad ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(
                    horizontal = 16.dp,
                    vertical = 10.dp,
                ),
        ) {

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
                        "Search sessions",
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        null,
                    )
                },
            )

            Spacer(
                Modifier.height(10.dp),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {

                FilterChip(
                    selected =
                        filter == "All",
                    onClick = {
                        filter = "All"
                    },
                    label = {
                        Text("All")
                    },
                )

                FilterChip(
                    selected =
                        filter ==
                            "Ungrouped",
                    onClick = {
                        filter =
                            "Ungrouped"
                    },
                    label = {
                        Text("Ungrouped")
                    },
                )

                Spacer(
                    Modifier.weight(1f),
                )

                if (
                    selectedIds.isNotEmpty()
                ) {
                    Text(
                        "${selectedIds.size} selected",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,
                    )
                }
            }

            if (groups.isNotEmpty()) {

                Spacer(
                    Modifier.height(6.dp),
                )

                LazyGroupRow(
                    groups = groups,
                    selectedGroup = filter,
                    onSelect = {
                        filter = it
                    },
                )
            }

            Spacer(
                Modifier.height(8.dp),
            )

            if (
                visible.isEmpty()
            ) {

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment =
                        Alignment.Center,
                ) {

                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                    ) {

                        Text(
                            "No sessions found",
                            fontWeight =
                                FontWeight.SemiBold,
                        )

                        Spacer(
                            Modifier.height(8.dp),
                        )

                        Text(
                            "Create a new session to get started.",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                        )

                        Spacer(
                            Modifier.height(14.dp),
                        )

                        Button(
                            onClick = {
                                showCreate = true
                            },
                        ) {
                            Icon(
                                Icons.Default.Add,
                                null,
                            )

                            Spacer(
                                Modifier.width(6.dp),
                            )

                            Text(
                                "Create Session",
                            )
                        }
                    }
                }

            } else {

                LazyColumn(
                    modifier =
                        Modifier.weight(1f),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                    contentPadding =
                        PaddingValues(
                            bottom = 100.dp,
                        ),
                ) {

                    items(
                        visible,
                        key = {
                            it.id
                        },
                    ) { session ->

                        SessionCard(
                            session = session,
                            selected =
                                session.id in selectedIds,
                            onSelect = {

                                selectedIds =
                                    if (
                                        session.id in
                                        selectedIds
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
                                                    System.currentTimeMillis(),
                                            )
                                        } else {
                                            it
                                        }
                                    }

                                sessions =
                                    updated

                                store.save(
                                    updated,
                                )

                                selected =
                                    updated.first {
                                        it.id ==
                                            session.id
                                    }
                            },
                            onRename = {
                                showRename =
                                    session
                            },
                            onDelete = {

                                sessions =
                                    sessions.filterNot {
                                        it.id ==
                                            session.id
                                    }

                                store.save(
                                    sessions,
                                )

                                if (
                                    WebViewFeature
                                        .isFeatureSupported(
                                            WebViewFeature
                                                .MULTI_PROFILE,
                                        )
                                ) {
                                    runCatching {
                                        ProfileStore
                                            .getInstance()
                                            .deleteProfile(
                                                session.profileName,
                                            )
                                    }
                                }

                                selectedIds =
                                    selectedIds -
                                        session.id
                            },
                        )
                    }
                }
            }

            if (
                filtered.isNotEmpty()
            ) {
                PaginationBar(
                    page = page,
                    totalPages =
                        totalPages,
                    onPrevious = {
                        if (
                            page > 0
                        ) {
                            page--
                        }
                    },
                    onNext = {
                        if (
                            page <
                            totalPages - 1
                        ) {
                            page++
                        }
                    },
                    onPage = {
                        page = it
                    },
                )
            }
        }
    }

    if (showCreate) {

        CreateSessionDialog(
            groups =
                groups,
            suggestedName =
                "My Session ${
                    nextSessionNumber(
                        sessions,
                    )
                }",
            onDismiss = {
                showCreate = false
            },
        ) { name, group ->

            val finalName =
                if (
                    name.isBlank()
                ) {
                    "My Session ${
                        nextSessionNumber(
                            sessions,
                        )
                    }"
                } else {
                    name.trim()
                }

            val meta =
                SessionMeta(
                    id =
                        UUID.randomUUID()
                            .toString(),
                    profileName =
                        "profile_${
                            UUID.randomUUID()
                        }",
                    name = finalName,
                    group = group,
                    createdAt =
                        System.currentTimeMillis(),
                )

            sessions += meta

            store.save(
                sessions,
            )

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

                    if (
                        it.id ==
                        current.id
                    ) {
                        it.copy(
                            name =
                                newName,
                        )
                    } else {
                        it
                    }
                }

            store.save(
                sessions,
            )

            showRename = null
        }
    }

    if (
        showGroupManager
    ) {

        GroupManagerDialog(
            sessions = sessions,
            onDismiss = {
                showGroupManager =
                    false
            },
            onFilter = {
                filter = it
                showGroupManager =
                    false
            },
            onSave = { updated ->
                sessions = updated
                store.save(
                    updated,
                )
            },
        )
    }

    if (
        showBulkGroup
    ) {

        BulkGroupDialog(
            groups =
                groups,
            onDismiss = {
                showBulkGroup = false
            },
        ) { group ->

            sessions =
                sessions.map {

                    if (
                        it.id in
                        selectedIds
                    ) {
                        it.copy(
                            group = group,
                        )
                    } else {
                        it
                    }
                }

            store.save(
                sessions,
            )

            selectedIds =
                emptySet()

            showBulkGroup =
                false
        }
    }

    if (
        showSettings
    ) {

        SettingsDialog(
            onDismiss = {
                showSettings = false
            },
            onExport = {

                exportLauncher.launch(
                    "bro-liker-backup.json",
                )

                showSettings = false
            },
            onImport = {

                importLauncher.launch(
                    arrayOf(
                        "application/json",
                        "text/plain",
                        "*/*",
                    ),
                )

                showSettings = false
            },
            onClearSelection = {
                selectedIds =
                    emptySet()
            },
        )
    }

    importMessage?.let { message ->

        AlertDialog(
            onDismissRequest = {
                importMessage = null
            },
            title = {
                Text("Backup")
            },
            text = {
                Text(message)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importMessage =
                            null
                    },
                ) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun LazyGroupRow(
    groups: List<String>,
    selectedGroup: String,
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement =
            Arrangement.spacedBy(8.dp),
        contentPadding =
            PaddingValues(
                horizontal = 2.dp,
            ),
    ) {

        items(
            groups,
            key = {
                it
            },
        ) { group ->

            FilterChip(
                selected =
                    selectedGroup ==
                        group,
                onClick = {
                    onSelect(group)
                },
                label = {
                    Text(
                        group,
                        maxLines = 1,
                        overflow =
                            TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun PaginationBar(
    page: Int,
    totalPages: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPage: (Int) -> Unit,
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape =
            RoundedCornerShape(12.dp),
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 8.dp,
                        vertical = 6.dp,
                    ),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {

            IconButton(
                enabled =
                    page > 0,
                onClick =
                    onPrevious,
            ) {
                Icon(
                    Icons.Default.ArrowBackIosNew,
                    contentDescription =
                        "Previous page",
                )
            }

            Spacer(
                Modifier.width(2.dp),
            )

            PageNumbers(
                current =
                    page,
                total =
                    totalPages,
                onPage =
                    onPage,
            )

            Spacer(
                Modifier.weight(1f),
            )

            IconButton(
                enabled =
                    page <
                        totalPages - 1,
                onClick =
                    onNext,
            ) {
                Icon(
                    Icons.Default.ArrowForwardIos,
                    contentDescription =
                        "Next page",
                )
            }
        }
    }
}

@Composable
private fun PageNumbers(
    current: Int,
    total: Int,
    onPage: (Int) -> Unit,
) {
    val numbers =
        when {
            total <= 5 ->
                (0 until total).toList()

            current <= 2 ->
                listOf(
                    0,
                    1,
                    2,
                    3,
                    -1,
                    total - 1,
                )

            current >= total - 3 ->
                listOf(
                    0,
                    -1,
                    total - 4,
                    total - 3,
                    total - 2,
                    total - 1,
                )

            else ->
                listOf(
                    0,
                    -1,
                    current - 1,
                    current,
                    current + 1,
                    -1,
                    total - 1,
                )
        }

    Row(
        horizontalArrangement =
            Arrangement.spacedBy(2.dp),
    ) {

        numbers.forEach { number ->

            if (number == -1) {

                Text(
                    "...",
                    modifier =
                        Modifier.padding(
                            horizontal = 5.dp,
                            vertical = 10.dp,
                        ),
                )

            } else {

                FilterChip(
                    selected =
                        current ==
                            number,
                    onClick = {
                        onPage(number)
                    },
                    label = {
                        Text(
                            "${number + 1}",
                        )
                    },
                )
            }
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
        Modifier.fillMaxWidth(),
    ) {

        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {

            Checkbox(
                checked =
                    selected,
                onCheckedChange = {
                    onSelect()
                },
            )

            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        MaterialTheme
                            .colorScheme
                            .primaryContainer,
                        RoundedCornerShape(
                            12.dp,
                        ),
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
                Modifier.width(10.dp),
            )

            Column(
                Modifier.weight(1f),
            ) {

                Text(
                    session.name,
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )

                Text(
                    if (
                        session.group
                            .isBlank()
                    ) {
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
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
            }

            TextButton(
                onClick =
                    onOpen,
            ) {
                Text(
                    "Open",
                )
            }

            IconButton(
                onClick =
                    onRename,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription =
                        "Rename",
                )
            }

            IconButton(
                onClick =
                    onDelete,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription =
                        "Delete",
                )
            }
        }
    }
}

@Composable
private fun CreateSessionDialog(
    groups: List<String>,
    suggestedName: String,
    onDismiss: () -> Unit,
    onCreate: (
        String,
        String,
    ) -> Unit,
) {
    var name by remember {
        mutableStateOf(
            suggestedName,
        )
    }

    var group by remember {
        mutableStateOf(
            "",
        )
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {
            Text(
                "Create new session",
            )
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp,
                    ),
            ) {

                OutlinedTextField(
                    value =
                        name,
                    onValueChange = {
                        name = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "Session name",
                        )
                    },
                    singleLine = true,
                )

                OutlinedTextField(
                    value =
                        group,
                    onValueChange = {
                        group = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "Group (optional)",
                        )
                    },
                    singleLine = true,
                )

                if (
                    groups.isNotEmpty()
                ) {

                    LazyGroupRow(
                        groups =
                            groups,
                        selectedGroup =
                            group,
                        onSelect = {
                            group = it
                        },
                    )
                }

                Text(
                    "Every session uses its own persistent browser profile.",
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
                    )
                },
            ) {
                Text(
                    "Create & Open",
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss,
            ) {
                Text(
                    "Cancel",
                )
            }
        },
    )
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (
        String,
    ) -> Unit,
) {
    var value by remember {
        mutableStateOf(
            current,
        )
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {
            Text(
                "Rename session",
            )
        },

        text = {

            OutlinedTextField(
                value =
                    value,
                onValueChange = {
                    value = it
                },
                modifier =
                    Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text(
                        "Name",
                    )
                },
            )
        },

        confirmButton = {

            Button(
                enabled =
                    value.isNotBlank(),
                onClick = {
                    onSave(
                        value.trim(),
                    )
                },
            ) {
                Text(
                    "Save",
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss,
            ) {
                Text(
                    "Cancel",
                )
            }
        },
    )
}

@Composable
private fun GroupManagerDialog(
    sessions: List<SessionMeta>,
    onDismiss: () -> Unit,
    onFilter: (
        String,
    ) -> Unit,
    onSave: (
        List<SessionMeta>,
    ) -> Unit,
) {
    var showCreate by remember {
        mutableStateOf(false)
    }

    var renameTarget by remember {
        mutableStateOf<String?>(null)
    }

    var newGroupName by remember {
        mutableStateOf("")
    }

    val groups =
        sessions
            .map {
                it.group.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .sorted()

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {

                Column(
                    Modifier.weight(1f),
                ) {

                    Text(
                        "Groups",
                        fontWeight =
                            FontWeight.Bold,
                    )

                    Text(
                        "${groups.size} groups",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                    )
                }

                IconButton(
                    onClick = {
                        showCreate = true
                    },
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription =
                            "Create group",
                    )
                }
            }
        },

        text = {

            if (
                groups.isEmpty()
            ) {

                Column {

                    Text(
                        "No groups created yet.",
                    )

                    Spacer(
                        Modifier.height(10.dp),
                    )

                    Button(
                        onClick = {
                            showCreate = true
                        },
                    ) {
                        Text(
                            "Create first group",
                        )
                    }
                }

            } else {

                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            6.dp,
                        ),
                ) {

                    item {

                        OutlinedButton(
                            onClick = {
                                onFilter(
                                    "All",
                                )
                            },
                        ) {

                            Icon(
                                Icons.Default.Folder,
                                null,
                            )

                            Spacer(
                                Modifier.width(
                                    6.dp,
                                ),
                            )

                            Text(
                                "All Sessions",
                            )
                        }
                    }

                    item {

                        HorizontalDivider()
                    }

                    items(
                        groups,
                        key = {
                            it
                        },
                    ) { group ->

                        val count =
                            sessions.count {
                                it.group ==
                                    group &&
                                    !it.archived
                            }

                        Card(
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            8.dp,
                                        ),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                            ) {

                                Column(
                                    Modifier.weight(
                                        1f,
                                    ),
                                ) {

                                    Text(
                                        group,
                                        fontWeight =
                                            FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow =
                                            TextOverflow
                                                .Ellipsis,
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
                                                .onSurfaceVariant,
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        onFilter(
                                            group,
                                        )
                                    },
                                ) {
                                    Text(
                                        "Open",
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        renameTarget =
                                            group
                                        newGroupName =
                                            group
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription =
                                            "Rename group",
                                    )
                                }

                                IconButton(
                                    onClick = {

                                        val updated =
                                            sessions.map {
                                                if (
                                                    it.group ==
                                                    group
                                                ) {
                                                    it.copy(
                                                        group =
                                                            "",
                                                    )
                                                } else {
                                                    it
                                                }
                                            }

                                        onSave(
                                            updated,
                                        )
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription =
                                            "Delete group",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick =
                    onDismiss,
            ) {
                Text(
                    "Close",
                )
            }
        },
    )

    if (
        showCreate ||
        renameTarget != null
    ) {

        AlertDialog(
            onDismissRequest = {
                showCreate = false
                renameTarget = null
            },

            title = {
                Text(
                    if (
                        renameTarget !=
                        null
                    ) {
                        "Rename group"
                    } else {
                        "Create group"
                    },
                )
            },

            text = {

                OutlinedTextField(
                    value =
                        newGroupName,
                    onValueChange = {
                        newGroupName = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            "Group name",
                        )
                    },
                )
            },

            confirmButton = {

                Button(
                    enabled =
                        newGroupName
                            .isNotBlank(),

                    onClick = {

                        val clean =
                            newGroupName
                                .trim()

                        if (
                            renameTarget !=
                            null
                        ) {

                            val updated =
                                sessions.map {

                                    if (
                                        it.group ==
                                        renameTarget
                                    ) {
                                        it.copy(
                                            group =
                                                clean,
                                        )
                                    } else {
                                        it
                                    }
                                }

                            onSave(
                                updated,
                            )

                        } else {

                            // Group exists as soon as
                            // a session is assigned to it.
                            // For immediate creation, add
                            // no fake session.
                        }

                        newGroupName = ""
                        showCreate = false
                        renameTarget = null
                    },
                ) {
                    Text(
                        if (
                            renameTarget !=
                            null
                        ) {
                            "Rename"
                        } else {
                            "Create",
                        },
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        showCreate = false
                        renameTarget = null
                    },
                ) {
                    Text(
                        "Cancel",
                    )
                }
            },
        )
    }
}

@Composable
private fun BulkGroupDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onApply: (
        String,
    ) -> Unit,
) {
    var group by remember {
        mutableStateOf(
            "",
        )
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {
            Text(
                "Move selected sessions",
            )
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp,
                    ),
            ) {

                OutlinedTextField(
                    value =
                        group,
                    onValueChange = {
                        group = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            "Group name",
                        )
                    },
                )

                if (
                    groups.isNotEmpty()
                ) {

                    Text(
                        "Existing groups",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,
                    )

                    LazyGroupRow(
                        groups =
                            groups,
                        selectedGroup =
                            group,
                        onSelect = {
                            group = it
                        },
                    )
                }
            }
        },

        confirmButton = {

            Button(
                enabled =
                    group.isNotBlank(),
                onClick = {
                    onApply(
                        group.trim(),
                    )
                },
            ) {
                Text(
                    "Move",
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss,
            ) {
                Text(
                    "Cancel",
                )
            }
        },
    )
}

@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearSelection: () -> Unit,
) {
    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {

                Icon(
                    Icons.Default.Settings,
                    null,
                )

                Spacer(
                    Modifier.width(
                        8.dp,
                    ),
                )

                Text(
                    "Settings",
                    fontWeight =
                        FontWeight.Bold,
                )
            }
        },

        text = {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp,
                    ),
            ) {

                Button(
                    modifier =
                        Modifier.fillMaxWidth(),
                    onClick =
                        onExport,
                ) {

                    Icon(
                        Icons.Default.FileDownload,
                        null,
                    )

                    Spacer(
                        Modifier.width(
                            8.dp,
                        ),
                    )

                    Text(
                        "Export Sessions",
                    )
                }

                Button(
                    modifier =
                        Modifier.fillMaxWidth(),
                    onClick =
                        onImport,
                ) {

                    Icon(
                        Icons.Default.FileUpload,
                        null,
                    )

                    Spacer(
                        Modifier.width(
                            8.dp,
                        ),
                    )

                    Text(
                        "Import Sessions",
                    )
                }

                OutlinedButton(
                    modifier =
                        Modifier.fillMaxWidth(),
                    onClick =
                        onClearSelection,
                ) {
                    Text(
                        "Clear Selection",
                    )
                }

                Text(
                    "Export/Import contains session metadata and group information only. Login credentials and authentication cookies are not included.",
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

            TextButton(
                onClick =
                    onDismiss,
            ) {
                Text(
                    "Close",
                )
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
        mutableStateOf(
            HOME_URL,
        )
    }

    var urlBarText by remember {
        mutableStateOf(
            HOME_URL,
        )
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

    val multiProfileSupported =
        remember {
            WebViewFeature
                .isFeatureSupported(
                    WebViewFeature
                        .MULTI_PROFILE,
                )
        }

    fun profileStore():
        ProfileStore =
        ProfileStore.getInstance()

    fun configureProfile(
        webView: WebView,
    ) {

        if (
            multiProfileSupported
        ) {

            runCatching {

                profileStore()
                    .getOrCreateProfile(
                        session.profileName,
                    )

                WebViewCompat.setProfile(
                    webView,
                    session.profileName,
                )
            }
        }

        webView.settings.apply {

            javaScriptEnabled =
                true

            domStorageEnabled =
                true

            databaseEnabled =
                true

            javaScriptCanOpenWindowsAutomatically =
                true

            setSupportMultipleWindows(
                true,
            )

            loadsImagesAutomatically =
                true

            blockNetworkImage =
                false

            blockNetworkLoads =
                false

            useWideViewPort =
                true

            loadWithOverviewMode =
                true

            builtInZoomControls =
                true

            displayZoomControls =
                false

            setSupportZoom(
                true,
            )

            allowFileAccess =
                true

            allowContentAccess =
                true

            mixedContentMode =
                WebSettings
                    .MIXED_CONTENT_COMPATIBILITY_MODE

            mediaPlaybackRequiresUserGesture =
                false

            cacheMode =
                WebSettings.LOAD_DEFAULT
        }

        if (
            multiProfileSupported
        ) {

            runCatching {

                profileStore()
                    .getProfile(
                        session.profileName,
                    )
                    ?.cookieManager
                    ?.apply {

                        setAcceptCookie(
                            true,
                        )

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

                    setAcceptCookie(
                        true,
                    )

                    setAcceptThirdPartyCookies(
                        webView,
                        true,
                    )
            }
        }
    }

    fun flushProfileCookies() {

        if (
            multiProfileSupported
        ) {

            runCatching {

                profileStore()
                    .getProfile(
                        session.profileName,
                    )
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
        webView: WebView,
    ): WebViewClient =
        object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean =
                false

            override fun onPageStarted(
                view: WebView,
                url: String,
                favicon: Bitmap?,
            ) {

                currentUrl =
                    url

                urlBarText =
                    url

                isLoading =
                    true

                errorMsg =
                    null

                canBack =
                    view.canGoBack()

                canForward =
                    view.canGoForward()
            }

            override fun onPageFinished(
                view: WebView,
                url: String,
            ) {

                currentUrl =
                    url

                urlBarText =
                    url

                isLoading =
                    false

                canBack =
                    view.canGoBack()

                canForward =
                    view.canGoForward()

                flushProfileCookies()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {

                if (
                    request.isForMainFrame
                ) {

                    isLoading =
                        false

                    errorMsg =
                        "Error ${error.errorCode}: ${error.description}"
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {

                isLoading =
                    false

                errorMsg =
                    if (
                        detail.didCrash()
                    ) {
                        "WebView renderer crashed. Tap Reload."
                    } else {
                        "WebView renderer stopped. Tap Reload."
                    }

                return true
            }
        }

    fun makeChromeClient(
        webView: WebView,
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
                    WebView(
                        view.context,
                    )

                configureProfile(
                    child,
                )

                child.webViewClient =
                    makeClient(
                        child,
                    )

                child.webChromeClient =
                    makeChromeClient(
                        child,
                    )

                child.layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                activeWebView
                    ?.visibility =
                    android.view.View.GONE

                root.addView(
                    child,
                )

                activeWebView =
                    child

                val transport =
                    resultMsg.obj
                        as? WebView.WebViewTransport
                        ?: return false

                transport.webView =
                    child

                resultMsg.sendToTarget()

                return true
            }
        }

    fun navigateTo(
        input: String,
    ) {

        val trimmed =
            input.trim()

        if (
            trimmed.isBlank()
        ) {
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
                    ) ->
                    trimmed

                trimmed.contains(
                    ".",
                ) ->
                    "https://$trimmed"

                else ->
                    "https://www.google.com/search?q=" +
                        java.net.URLEncoder.encode(
                            trimmed,
                            "UTF-8",
                        )
            }

        activeWebView
            ?.loadUrl(
                url,
            )

        urlBarText =
            url
    }

    fun closePopupIfPossible():
        Boolean {

        val root =
            rootRef
                ?: return false

        val current =
            activeWebView
                ?: return false

        if (
            root.childCount <= 1
        ) {
            return false
        }

        root.removeView(
            current,
        )

        current.stopLoading()
        current.destroy()

        val parent =
            root.getChildAt(
                root.childCount - 1,
            ) as? WebView

        parent?.let {

            it.visibility =
                android.view.View.VISIBLE

            activeWebView =
                it

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
                            },
                        ) {

                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription =
                                    "Close",
                            )
                        }
                    },

                    title = {

                        OutlinedTextField(
                            value =
                                urlBarText,
                            onValueChange = {
                                urlBarText =
                                    it
                            },
                            modifier =
                                Modifier.fillMaxWidth(),
                            singleLine =
                                true,
                            placeholder = {
                                Text(
                                    "URL or search...",
                                )
                            },
                            keyboardOptions =
                                KeyboardOptions(
                                    imeAction =
                                        ImeAction.Go,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onGo = {
                                        navigateTo(
                                            urlBarText,
                                        )
                                    },
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
                                errorMsg =
                                    null

                                activeWebView
                                    ?.reload()
                            },
                        ) {

                            Icon(
                                Icons.Default.Refresh,
                                contentDescription =
                                    "Reload",
                            )
                        }

                        IconButton(
                            onClick = {
                                showMenu =
                                    true
                            },
                        ) {

                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription =
                                    "More",
                            )
                        }

                        DropdownMenu(
                            expanded =
                                showMenu,
                            onDismissRequest = {
                                showMenu =
                                    false
                            },
                        ) {

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Back",
                                    )
                                },
                                enabled =
                                    canBack,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        null,
                                    )
                                },
                                onClick = {
                                    activeWebView
                                        ?.goBack()

                                    showMenu =
                                        false
                                },
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Forward",
                                    )
                                },
                                enabled =
                                    canForward,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        null,
                                    )
                                },
                                onClick = {
                                    activeWebView
                                        ?.goForward()

                                    showMenu =
                                        false
                                },
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Facebook Home",
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Home,
                                        null,
                                    )
                                },
                                onClick = {
                                    navigateTo(
                                        HOME_URL,
                                    )

                                    showMenu =
                                        false
                                },
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create another session",
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                    )
                                },
                                onClick = {
                                    showMenu =
                                        false

                                    onCreateAnother()
                                },
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Close",
                                    )
                                },
                                onClick = {
                                    showMenu =
                                        false

                                    flushProfileCookies()

                                    onBack()
                                },
                            )
                        }
                    },
                )

                if (
                    isLoading
                ) {

                    LinearProgressIndicator(
                        progress = {
                            loadingProgress /
                                100f
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
                .padding(
                    pad,
                ),
        ) {

            AndroidView(

                modifier =
                    Modifier.fillMaxSize(),

                factory = { ctx ->

                    FrameLayout(
                        ctx,
                    ).also { root ->

                        rootRef =
                            root

                        val initial =
                            WebView(
                                ctx,
                            )

                        configureProfile(
                            initial,
                        )

                        initial.webViewClient =
                            makeClient(
                                initial,
                            )

                        initial.webChromeClient =
                            makeChromeClient(
                                initial,
                            )

                        initial.layoutParams =
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )

                        root.addView(
                            initial,
                        )

                        activeWebView =
                            initial

                        initial.loadUrl(
                            HOME_URL,
                        )
                    }
                },

                update = { root ->

                    rootRef =
                        root

                    val child =
                        root.getChildAt(
                            root.childCount - 1,
                        ) as? WebView

                    if (
                        child != null &&
                        activeWebView == null
                    ) {
                        activeWebView =
                            child
                    }
                },

                onRelease = { root ->

                    for (
                        i in
                        root.childCount - 1 downTo 0
                    ) {

                        (
                            root.getChildAt(
                                i,
                            ) as? WebView
                            )?.let { web ->

                            runCatching {

                                web.stopLoading()

                                web.loadUrl(
                                    "about:blank",
                                )

                                web.removeAllViews()

                                web.destroy()
                            }
                        }
                    }

                    root.removeAllViews()

                    activeWebView =
                        null

                    rootRef =
                        null
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
                                .copy(
                                    alpha =
                                        0.96f,
                                ),
                        )
                        .padding(
                            24.dp,
                        ),
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
                        Modifier.height(
                            8.dp,
                        ),
                    )

                    Text(
                        err,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )

                    Spacer(
                        Modifier.height(
                            16.dp,
                        ),
                    )

                    Button(
                        onClick = {
                            errorMsg =
                                null

                            activeWebView
                                ?.reload()
                        },
                    ) {
                        Text(
                            "Reload",
                        )
                    }
                }
            }
        }
    }
}