package com.thehbc.iso2god

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.thehbc.iso2god.ui.theme.ISO2GODTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class IsoInfo(
    val title: String,
    val title_id: String,
    val media_id: String,
    val data_parts: Long,
    val data_size: Long
)

/** 进度阶段码，与 android-bridge 里的 ProgressStage 一一对应，改动必须两边同步。 */
object ProgressStage {
    const val WRITING_PARTS = 0
    const val WRITING_MHT = 1
    const val WRITING_HEADER = 2
}

/** 失败原因码 → 文案。code 由 android-bridge 给出，见其 ErrorCode。 */
private fun conversionErrorMessage(context: Context, code: String): String = context.getString(
    when (code) {
        "JNI" -> R.string.error_jni
        "ISO_READ" -> R.string.error_iso_read
        "PART_COUNT_MISMATCH" -> R.string.error_part_count_mismatch
        "WRITE_PART" -> R.string.error_write_part
        "MHT" -> R.string.error_mht
        "HEADER" -> R.string.error_header
        else -> R.string.error_unknown
    }
)

/**
 * 拼出「前缀：原因（诊断细节）」。detail 是原生层给的英文技术信息，为空时省略括号部分。
 * 这里不在 Composable 上下文（由协程调用），所以用 context.getString 而非 stringResource。
 */
private fun failureText(
    context: Context,
    @StringRes prefixRes: Int,
    code: String,
    detail: String
): String {
    val prefix = context.getString(prefixRes)
    val reason = conversionErrorMessage(context, code)
    return if (detail.isBlank()) {
        context.getString(R.string.failure_format, prefix, reason)
    } else {
        context.getString(R.string.failure_format_detail, prefix, reason, detail)
    }
}

class MainActivity : ComponentActivity() {
    interface ProgressCallback {
        // 签名必须与 android-bridge 里的 call_method("onProgress", "(III)V", ...) 一致
        fun onProgress(current: Int, total: Int, stage: Int)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ISO2GODTheme {
                var showLicenses by remember { mutableStateOf(false) }

                if (showLicenses) {
                    // 许可页自带 Scaffold 与返回箭头（分组 → 组件 → 许可全文）
                    LicensesScreen(onClose = { showLicenses = false })
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text("ISO2GOD Android", fontWeight = FontWeight.Bold) },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                actions = {
                                    IconButton(onClick = { showLicenses = true }) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = stringResource(R.string.licenses_title)
                                        )
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        MainScreen(
                            modifier = Modifier.padding(innerPadding),
                            activity = this
                        )
                    }
                }
            }
        }
    }

    external fun getIsoInfo(fd: Int): String
    external fun convertIso(isoFd: Int, headerFd: Int, partFds: IntArray, callback: ProgressCallback): String

    companion object {
        init {
            System.loadLibrary("iso2god")
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, activity: MainActivity) {
    val context = LocalContext.current

    var statusText by remember { mutableStateOf(context.getString(R.string.status_select_iso)) }
    var isoInfo by remember { mutableStateOf<IsoInfo?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressMessage by remember { mutableStateOf("") }
    var isConverting by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            sourceUri = it
            statusText = context.getString(R.string.status_analyzing)
            isoInfo = null
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openFileDescriptor(it, "r")?.use { pfd ->
                        val json = activity.getIsoInfo(pfd.fd)
                        val obj = JSONObject(json)
                        if (!obj.optBoolean("ok", false)) {
                            val text = failureText(
                                context = context,
                                prefixRes = R.string.failure_prefix_analyze,
                                code = obj.optString("code", "UNKNOWN"),
                                detail = obj.optString("detail", "")
                            )
                            withContext(Dispatchers.Main) {
                                statusText = text
                            }
                        } else {
                            val info = IsoInfo(
                                title = obj.getString("title"),
                                title_id = obj.getString("title_id"),
                                media_id = obj.getString("media_id"),
                                data_parts = obj.getLong("data_parts"),
                                data_size = obj.getLong("data_size")
                            )
                            withContext(Dispatchers.Main) {
                                isoInfo = info
                                statusText = context.getString(R.string.status_analyzed)
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText = context.getString(R.string.status_read_error, e.message)
                    }
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            val info = isoInfo ?: return@let
            val srcUri = sourceUri ?: return@let

            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions; proceed anyway
            }

            statusText = context.getString(R.string.status_preparing)
            isConverting = true
            showProgress = true
            progress = 0f
            progressMessage = context.getString(R.string.progress_init)

            scope.launch(Dispatchers.IO) {
                var isoPfd: ParcelFileDescriptor? = null
                var headerPfd: ParcelFileDescriptor? = null
                val partPfds = ArrayList<ParcelFileDescriptor>()

                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)

                    val titleDir = rootDoc?.findFile(info.title_id) ?: rootDoc?.createDirectory(info.title_id)
                    val contentDir = titleDir?.findFile("00007000") ?: titleDir?.createDirectory("00007000")

                    if (contentDir == null) {
                        withContext(Dispatchers.Main) {
                            statusText = context.getString(R.string.status_create_output_dir_failed)
                            isConverting = false
                            showProgress = false
                        }
                        return@launch
                    }

                    contentDir.findFile(info.media_id)?.delete()
                    val headerFile = contentDir.createFile("application/octet-stream", info.media_id)

                    val dataDirName = "${info.media_id}.data"
                    val dataDir = contentDir.findFile(dataDirName) ?: contentDir.createDirectory(dataDirName)

                    if (headerFile == null || dataDir == null) {
                        withContext(Dispatchers.Main) {
                            statusText = context.getString(R.string.status_create_header_failed)
                            isConverting = false
                            showProgress = false
                        }
                        return@launch
                    }

                    val partFds = IntArray(info.data_parts.toInt())

                    withContext(Dispatchers.Main) {
                        progressMessage = context.getString(R.string.progress_creating_parts, info.data_parts)
                    }

                    for (i in 0 until info.data_parts.toInt()) {
                        val name = "Data%04d".format(i)
                        dataDir.findFile(name)?.delete()
                        val partFile = dataDir.createFile("application/octet-stream", name)
                            ?: throw Exception(context.getString(R.string.error_create_part, i))

                        val pfd = context.contentResolver.openFileDescriptor(partFile.uri, "rw")
                            ?: throw Exception(context.getString(R.string.error_open_part, i))

                        partPfds.add(pfd)
                        partFds[i] = pfd.fd
                    }

                    isoPfd = context.contentResolver.openFileDescriptor(srcUri, "r")
                    headerPfd = context.contentResolver.openFileDescriptor(headerFile.uri, "rw")

                    if (isoPfd != null && headerPfd != null) {
                        withContext(Dispatchers.Main) {
                            statusText = context.getString(R.string.status_converting)
                        }

                        val callback = object : MainActivity.ProgressCallback {
                            override fun onProgress(current: Int, total: Int, stage: Int) {
                                if (total > 0) {
                                    // 完成前不显示 100%：后面还有 MHT 与数据头两个阶段
                                    progress = (current.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
                                }
                                progressMessage = when (stage) {
                                    ProgressStage.WRITING_PARTS -> context.getString(
                                        R.string.progress_writing_part,
                                        current + 1,
                                        total
                                    )

                                    ProgressStage.WRITING_MHT -> context.getString(R.string.progress_writing_mht)
                                    ProgressStage.WRITING_HEADER -> context.getString(R.string.progress_writing_header)
                                    else -> progressMessage
                                }
                            }
                        }

                        val result = JSONObject(activity.convertIso(isoPfd.fd, headerPfd.fd, partFds, callback))
                        val ok = result.optBoolean("ok", false)
                        val text = if (ok) {
                            context.getString(R.string.result_success)
                        } else {
                            failureText(
                                context = context,
                                prefixRes = R.string.failure_prefix_convert,
                                code = result.optString("code", "UNKNOWN"),
                                detail = result.optString("detail", "")
                            )
                        }

                        withContext(Dispatchers.Main) {
                            statusText = text
                            isConverting = false
                            showProgress = false
                            if (ok) progress = 1f
                            progressMessage = if (ok) {
                                context.getString(R.string.progress_done)
                            } else {
                                context.getString(R.string.progress_aborted)
                            }
                        }
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText = context.getString(R.string.status_convert_error, e.message)
                        isConverting = false
                        showProgress = false
                    }
                    e.printStackTrace()
                } finally {
                    isoPfd?.close()
                    headerPfd?.close()
                    partPfds.forEach { it.close() }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 状态卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isoInfo != null) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isoInfo != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮：紧跟状态卡，下方信息卡的出现/消失不会再推走它们的位置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                enabled = !isConverting,
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_select_iso))
            }

            Button(
                onClick = { folderPickerLauncher.launch(null) },
                modifier = Modifier.weight(1f),
                enabled = isoInfo != null && !isConverting,
                contentPadding = PaddingValues(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_start_conversion))
            }
        }

        if (isoInfo != null) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ISO 信息卡片
        AnimatedVisibility(
            visible = isoInfo != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            isoInfo?.let { info ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.info_card_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // ifBlank 的 lambda 不是 Composable 上下文，先取出来再传
                        val unknownTitle = stringResource(R.string.title_unknown)
                        InfoRow(
                            label = stringResource(R.string.label_name),
                            value = info.title.ifBlank { unknownTitle }
                        )
                        InfoRow(label = stringResource(R.string.label_title_id), value = info.title_id)
                        InfoRow(label = stringResource(R.string.label_media_id), value = info.media_id)
                        InfoRow(
                            label = stringResource(R.string.label_size),
                            value = stringResource(
                                R.string.size_gb,
                                info.data_size.toDouble() / (1024 * 1024 * 1024)
                            )
                        )
                        InfoRow(label = stringResource(R.string.label_data_parts), value = info.data_parts.toString())
                    }
                }
            }
        }

        if (showProgress) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 进度卡片
        AnimatedVisibility(
            visible = showProgress,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.progress_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = progressMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
