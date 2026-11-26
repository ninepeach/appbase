package appbase.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import appbase.ui.ContentStatus
import appbase.ui.UIContent
import appbase.ui.UIGroup as Group
import appbase.ui.UIRow as Item

@Composable
internal fun DataScreen(
    page: String,
    language: String,
    connected: Boolean,
    logs: MutableList<String>,
    open: (String) -> Unit,
    notify: (String) -> Unit,
    confirm: (String, () -> Unit) -> Unit,
) {
    when (page) {
        "Data" -> {
            Group { Item(demoLabel("Upload",language),if(connected)"12 MB" else "0 MB"){open("Traffic")};Item(demoLabel("Download",language),if(connected)"86 MB" else "0 MB"){open("Traffic")};Item(demoLabel("Connections",language)){open("Connections")};Item(demoLabel("Logs",language)){open("Logs")} }
            Text(demoLabel("All traffic values are simulated.",language),style=MaterialTheme.typography.bodySmall)
        }
        "Traffic" -> { Text(demoLabel("Session traffic · mock",language)); repeat(7){i->Row(verticalAlignment=Alignment.CenterVertically){Text(demoLabel("Day",language)+" "+(i+1),Modifier.width(64.dp));LinearProgressIndicator(progress={if(connected)(i+1)/8f else 0f},modifier=Modifier.weight(1f))}} }
        "Connections" -> { if(connected) Group{Item("example.org:443","TCP"){notify("Simulated connection")}} else UIContent(ContentStatus.EMPTY,demoLabel("No active connections",language)) {} }
        "Logs" -> {
            var filter by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(filter,{filter=it},label={Text(demoLabel("Filter logs",language))},modifier=Modifier.fillMaxWidth())
            logs.map { demoLabel(it, language) }.filter { it.contains(filter, true) }.forEach { Text(it) }
            TextButton(onClick={confirm("Clear logs?") {logs.clear()}}){Text(demoLabel("Clear",language))}
        }
    }
}
