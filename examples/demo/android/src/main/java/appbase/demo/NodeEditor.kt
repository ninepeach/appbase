package appbase.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import appbase.ui.UIChoice
import appbase.ui.UIGroup

@Composable
internal fun NodeEditor(model: DemoModel, language: String, notify: (String) -> Unit, back: () -> Unit, disconnect: () -> Unit, confirm: (String, () -> Unit) -> Unit) {
    val original = model.editing
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var host by rememberSaveable(original?.id) { mutableStateOf(original?.host.orEmpty()) }
    var port by rememberSaveable(original?.id) { mutableStateOf(original?.port ?: "443") }
    var group by rememberSaveable(original?.id) { mutableStateOf(original?.group ?: "Local") }
    var protocol by rememberSaveable(original?.id) { mutableStateOf(original?.protocol ?: "VLESS") }
    val valid = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true
    OutlinedTextField(name, { name = it }, label = { Text(demoLabel("Name", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(group, { group = it }, label = { Text(demoLabel("Group", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(host, { host = it }, label = { Text(demoLabel("Server address", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(demoLabel("Port", language)) }, isError = port.toIntOrNull()?.let { it in 1..65535 } != true, modifier = Modifier.fillMaxWidth(), singleLine = true)
    UIGroup { listOf("VLESS", "Shadowsocks", "Trojan", "SOCKS5", "HTTP").forEach { UIChoice(it, protocol == it) { protocol = it } } }
    Button(enabled = valid, onClick = {
        val node = Node(original?.id ?: model.nextId++, name.trim(), host.trim(), port, protocol, group.trim().ifBlank { "Local" })
        val index = model.nodes.indexOfFirst { it.id == node.id }
        if (index < 0) model.nodes.add(node) else model.nodes[index] = node
        notify("Node saved in memory"); back()
    }, modifier = Modifier.fillMaxWidth()) { Text(demoLabel("Save", language)) }
    if (original != null) OutlinedButton(onClick = { confirm("Delete node?") {
        model.nodes.removeAll { it.id == original.id }
        if (model.selected == original.id) { disconnect(); model.selected = model.nodes.firstOrNull()?.id ?: -1 }
        back()
    } }, modifier = Modifier.fillMaxWidth()) { Text(demoLabel("Delete node", language)) }
}
