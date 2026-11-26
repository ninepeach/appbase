package appbase.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

@Composable
internal fun ConfigurationEditor(model:DemoModel,language:String,back:()->Unit,confirm:(String,()->Unit)->Unit) {
    val original = model.configEdit
    var name by rememberSaveable(original) { mutableStateOf(original) }
    var body by rememberSaveable(original) { mutableStateOf(model.configBodies[original] ?: "# Demo configuration\n[General]\n") }
    OutlinedTextField(name, { name = it }, label = { Text(demoLabel("Name", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(body, { body = it }, label = { Text(demoLabel("Configuration preview", language)) }, minLines = 8, modifier = Modifier.fillMaxWidth())
    Button(enabled = name.isNotBlank(), onClick = {
        if (name != original && original in model.configs) { model.configs.remove(original); model.configBodies.remove(original) }
        if (name !in model.configs) model.configs.add(name)
        model.configBodies[name] = body; model.activeConfig = name; back()
    }, modifier = Modifier.fillMaxWidth()) { Text(demoLabel("Save and activate (mock)", language)) }
    if (original in model.configs) TextButton(onClick = { confirm("Delete configuration?") {
        model.configs.remove(original); model.configBodies.remove(original)
        if (model.activeConfig == original) model.activeConfig = model.configs.firstOrNull().orEmpty()
        back()
    } }) { Text(demoLabel("Delete", language)) }
}
