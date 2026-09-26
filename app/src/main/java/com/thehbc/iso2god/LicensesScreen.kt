package com.thehbc.iso2god

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 第三方许可数据，由 tools/generate_android_licenses.py 与
 * tools/generate_rust_licenses.py 生成到 app/src/main/assets/licenses/*.json。
 * 结构（两侧同构）：
 *   { "title": …, "licenses": [{id, name, text}], "entries": [{name, version, licenseIds}] }
 *
 * 许可正文按 id 去重存放，entry 只引用 id —— 否则 110 个组件各带一份
 * Apache-2.0 全文会让资源膨胀上百倍。
 */
data class LicenseEntry(val name: String, val version: String?, val licenseIds: List<String>)

data class LicenseText(val id: String, val name: String, val text: String)

data class LicenseGroup(
    val title: String,
    val licenses: List<LicenseText>,
    val entries: List<LicenseEntry>,
)

private sealed interface LicensesRoute {
    object Groups : LicensesRoute
    data class Entries(val group: LicenseGroup) : LicensesRoute
    data class Detail(val group: LicenseGroup, val entry: LicenseEntry) : LicensesRoute
}

private fun loadLicenseGroups(context: Context): List<LicenseGroup> = try {
    val assets = context.assets
    (assets.list("licenses") ?: emptyArray())
        .filter { it.endsWith(".json") }
        .sorted()
        .mapNotNull { fileName ->
            runCatching {
                val json = assets.open("licenses/$fileName").bufferedReader().use { it.readText() }
                val obj = JSONObject(json)

                val licensesJson = obj.getJSONArray("licenses")
                val licenses = (0 until licensesJson.length()).map { i ->
                    val item = licensesJson.getJSONObject(i)
                    LicenseText(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        text = item.getString("text"),
                    )
                }

                val entriesJson = obj.getJSONArray("entries")
                val entries = (0 until entriesJson.length()).map { i ->
                    val item = entriesJson.getJSONObject(i)
                    val idsJson = item.getJSONArray("licenseIds")
                    LicenseEntry(
                        name = item.getString("name"),
                        version = item.optString("version").ifBlank { null },
                        licenseIds = (0 until idsJson.length()).map { idsJson.getString(it) },
                    )
                }

                LicenseGroup(title = obj.getString("title"), licenses = licenses, entries = entries)
            }.getOrNull()
        }
} catch (_: Exception) {
    emptyList()
}

/** 某个条目适用的许可名，用于列表副标题。 */
private fun LicenseGroup.licenseNamesOf(entry: LicenseEntry): String = entry.licenseIds
    .mapNotNull { id -> licenses.firstOrNull { it.id == id }?.name }
    .distinct()
    .joinToString(", ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val groups by produceState(initialValue = emptyList<LicenseGroup>()) {
        value = withContext(Dispatchers.IO) { loadLicenseGroups(context) }
    }
    var route by remember { mutableStateOf<LicensesRoute>(LicensesRoute.Groups) }
    var query by remember { mutableStateOf("") }

    fun goUp() {
        route = when (val current = route) {
            is LicensesRoute.Detail -> LicensesRoute.Entries(current.group)
            is LicensesRoute.Entries -> LicensesRoute.Groups
            LicensesRoute.Groups -> LicensesRoute.Groups
        }
    }

    BackHandler(enabled = route != LicensesRoute.Groups) { goUp() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (val current = route) {
                            LicensesRoute.Groups -> stringResource(R.string.licenses_title)
                            is LicensesRoute.Entries -> current.group.title
                            is LicensesRoute.Detail -> current.entry.name
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = { if (route == LicensesRoute.Groups) onClose() else goUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()) {
            when (val current = route) {
                LicensesRoute.Groups -> GroupsList(groups) { route = LicensesRoute.Entries(it) }
                is LicensesRoute.Entries -> EntryList(
                    group = current.group,
                    query = query,
                    onQueryChange = { query = it },
                    onOpen = { route = LicensesRoute.Detail(current.group, it) },
                )

                is LicensesRoute.Detail -> EntryDetail(current.group, current.entry)
            }
        }
    }
}

/** 一级：第三方组件来源分组。 */
@Composable
private fun GroupsList(groups: List<LicenseGroup>, onOpen: (LicenseGroup) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(groups, key = { it.title }) { group ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(group) },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.licenses_group_summary,
                            group.entries.size,
                            group.licenses.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // 本应用自身的许可声明
        item {
            Text(
                text = stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 二级：某个分组下的组件列表，带搜索。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryList(
    group: LicenseGroup,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (LicenseEntry) -> Unit,
) {
    val filtered = remember(query, group) {
        if (query.isBlank()) {
            group.entries
        } else {
            group.entries.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.licenses_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.licenses_no_results),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                items(filtered, key = { it.name + (it.version ?: "") }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(
                                listOfNotNull(entry.version, group.licenseNamesOf(entry))
                                    .joinToString(" · ")
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        },
                        modifier = Modifier.clickable { onOpen(entry) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 三级：具体条目与其适用许可的全文。 */
@Composable
private fun EntryDetail(group: LicenseGroup, entry: LicenseEntry) {
    val licenses = remember(entry, group) {
        entry.licenseIds.mapNotNull { id -> group.licenses.firstOrNull { it.id == id } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        entry.version?.let { version ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = version,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        licenses.forEach { license ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = license.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // SelectionContainer 允许长按复制许可正文
            SelectionContainer {
                Text(
                    text = license.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
