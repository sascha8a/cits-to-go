package org.opentrafficmap.citstogo

import android.widget.Toast
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opentrafficmap.citstogo.logging.AppLogging
import org.opentrafficmap.citstogo.logging.LogSeverity
import java.util.logging.Level
import java.util.logging.Logger
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
private val logDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

@Composable
fun LogsPage(modifier: Modifier = Modifier) {
    val history = AppLogging.history
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var entries by remember { mutableStateOf(history.entries.value) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            history.entries.collect { entries = it }
        }
    }
    var minimum by rememberSaveable { mutableStateOf(LogSeverity.DEBUG) }
    val visible = remember(entries, minimum) { entries.asReversed().filter { it.severity >= minimum } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val snapshot = history.entries.value
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
                        stream.bufferedWriter().use { writer ->
                            writer.appendLine("C-ITS to go ${BuildConfig.VERSION_NAME}; session diagnostic log (UTC)")
                            writer.appendLine("Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
                            snapshot.forEach { writer.appendLine(it.asText()) }
                        }
                    }
                }
                result.exceptionOrNull()?.let {
                    Logger.getLogger("org.opentrafficmap.citstogo.Logs").log(Level.WARNING, "Log export failed", it)
                }
                Toast.makeText(context, if (result.isSuccess) R.string.logs_export_success else R.string.logs_export_error, Toast.LENGTH_LONG).show()
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.logs_count, visible.size, entries.size),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = { export.launch("cits-logs-${System.currentTimeMillis()}.txt") }) { Text(stringResource(R.string.logs_export)) }
            TextButton(onClick = history::clear) { Text(stringResource(R.string.logs_clear)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LogSeverity.entries) { severity ->
                val label = when (severity) {
                    LogSeverity.DEBUG -> R.string.logs_debug
                    LogSeverity.INFO -> R.string.logs_info
                    LogSeverity.WARNING -> R.string.logs_warning
                    LogSeverity.ERROR -> R.string.logs_error
                }
                FilterChip(selected = minimum == severity, onClick = { minimum = severity }, label = { Text(stringResource(label)) })
            }
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            BoxWithConstraints {
                val tableWidth = maxOf(maxWidth, 1_040.dp)
                Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        LogTableCell(stringResource(R.string.logs_time_column), Modifier.width(144.dp), header = true)
                        LogTableCell(stringResource(R.string.logs_level_column), Modifier.width(100.dp), header = true)
                        LogTableCell(stringResource(R.string.logs_source_column), Modifier.width(300.dp), header = true)
                        LogTableCell(stringResource(R.string.logs_message_column), Modifier.weight(1f), header = true)
                    }
                    HorizontalDivider()
                    if (visible.isEmpty()) {
                        Text(stringResource(R.string.logs_empty), Modifier.padding(12.dp))
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                            val levelColor = when (entry.severity) {
                                LogSeverity.ERROR -> MaterialTheme.colorScheme.error
                                LogSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                LogSeverity.INFO -> MaterialTheme.colorScheme.primary
                                LogSeverity.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            SelectionContainer {
                                Row(Modifier.fillMaxWidth().background(
                                    if (index % 2 == 0) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                )) {
                                    LogTableCell(
                                        "${logTimeFormat.format(entry.timestamp)}\n${logDateFormat.format(entry.timestamp)}",
                                        Modifier.width(144.dp),
                                    )
                                    Text(
                                        entry.severity.name, Modifier.width(100.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                        color = levelColor, style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                    )
                                    LogTableCell(entry.source, Modifier.width(300.dp))
                                    LogTableCell(entry.message, Modifier.weight(1f))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTableCell(text: String, modifier: Modifier = Modifier, header: Boolean = false) {
    Text(
        text, modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}
