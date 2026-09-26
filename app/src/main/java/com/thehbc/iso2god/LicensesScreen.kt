package com.thehbc.iso2god

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三方许可数据，由 tools/generate_android_licenses.py 与
 * tools/generate_rust_licenses.py 生成到 app/src/main/assets/licenses 目录。
 * 结构（两侧同构）：
 *   { "licenses": [{name, text, copyrights?, components: [{name, version}]}] }
 *
 * 页面为单页 credits 式：按许可分节，节内先列适用组件与版权行，再附许可全文，
 * 不做逐组件的二级页面。许可正文由生成脚本按（许可名，去声明头后的正文）去重，
 * 各 crate 只差版权行的几十份 MIT 归并成一份，版权行集中在 copyrights 里。
 */
data class LicenseComponent(val name: String, val version: String?)

data class LicenseSection(
    val name: String,
    val text: String,
    val copyrights: List<String>,
    val components: List<LicenseComponent>,
)

private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

private fun loadLicenseSections(context: Context): List<LicenseSection> = try {
    val assets = context.assets
    (assets.list("licenses") ?: emptyArray())
        .filter { it.endsWith(".json") }
        .sorted()
        .flatMap { fileName ->
            runCatching {
                val json = assets.open("licenses/$fileName").bufferedReader().use { it.readText() }
                val arr = JSONObject(json).getJSONArray("licenses")
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    val comps = item.getJSONArray("components")
                    LicenseSection(
                        name = item.getString("name"),
                        text = item.getString("text"),
                        copyrights = item.optJSONArray("copyrights")?.toStringList() ?: emptyList(),
                        components = (0 until comps.length()).map { j ->
                            val c = comps.getJSONObject(j)
                            LicenseComponent(
                                name = c.getString("name"),
                                version = c.optString("version").ifBlank { null },
                            )
                        },
                    )
                }
            }.getOrNull().orEmpty()
        }
        // 两份数据若出现同名许可（如双许可 crate 横跨两侧），合并为同一节
        .groupBy { it.name }
        .map { (_, same) ->
            same.reduce { acc, next ->
                acc.copy(
                    copyrights = (acc.copyrights + next.copyrights).distinct(),
                    components = (acc.components + next.components).distinct(),
                )
            }
        }
} catch (_: Exception) {
    emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val sections by produceState(initialValue = emptyList<LicenseSection>()) {
        value = withContext(Dispatchers.IO) { loadLicenseSections(context) }
    }

    // 页面由 MainActivity 按状态切换显示，系统返回（手势或按键）应关闭本页
    // 回到主界面，而不是走 Activity 默认行为直接退出应用
    BackHandler(onBack = onClose)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.licenses_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            sections.forEach { section ->
                Spacer(modifier = Modifier.height(24.dp))
                LicenseSectionView(section)
            }
        }
    }
}

/** 一个许可一节：适用组件名单与版权行排成小字段落，随后附许可全文。 */
@Composable
private fun LicenseSectionView(section: LicenseSection) {
    SelectionContainer {
        Column {
            Text(
                text = section.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = section.components.joinToString(" · ") { component ->
                    listOfNotNull(component.name, component.version).joinToString(" ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (section.copyrights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = section.copyrights.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = section.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
