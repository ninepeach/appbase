package appbase.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

@Composable
internal fun ConfigurationEditor(model:DemoModel,controller:DemoController,language:String,back:()->Unit,confirm:(String,()->Unit)->Unit) {
    val original = model.configEdit
    var name by rememberSaveable(original) { mutableStateOf(original) }
    var body by rememberSaveable(original) { mutableStateOf(model.configBodies[original] ?: "# Demo configuration\n[General]\n") }
    val duplicate = name.trim() != original && name.trim() in model.configs
    OutlinedTextField(name, { name = it }, label = { Text(demoLabel("Name", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = duplicate)
    OutlinedTextField(body, { body = it }, label = { Text(demoLabel("Configuration preview", language)) }, minLines = 8, modifier = Modifier.fillMaxWidth())
    Button(enabled = name.isNotBlank() && !duplicate, onClick = {
        controller.saveConfiguration(original, name, body)
        back()
    }, modifier = Modifier.fillMaxWidth()) { Text(demoLabel("Save and activate (mock)", language)) }
    if (original in model.configs) TextButton(onClick = { confirm("Delete configuration?") {
        controller.deleteConfiguration(original)
        back()
    } }) { Text(demoLabel("Delete", language)) }
}
