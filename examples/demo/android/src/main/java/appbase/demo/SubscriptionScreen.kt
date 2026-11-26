package appbase.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import appbase.ui.UIGroup as Group
import appbase.ui.UIRow as Item
import appbase.ui.*

@Composable
internal fun SubscriptionScreen(model:DemoModel,language:String,notify:(String)->Unit,confirm:(String,()->Unit)->Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    if (model.subscriptions.isEmpty()) UIContent(ContentStatus.EMPTY, demoLabel("No subscriptions", language)) {}
    model.subscriptions.forEach { address -> Group { Item(address, "Remove") { confirm("Remove subscription?") { model.subscriptions.remove(address) } } } }
    OutlinedTextField(url, { url = it.trim() }, label = { Text(demoLabel("Subscription URL", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = url.isNotEmpty() && !url.startsWith("https://"))
    Button(enabled = url.startsWith("https://") && url !in model.subscriptions, onClick = { model.subscriptions.add(url); url = "" }) { Text(demoLabel("Add", language)) }
    Button(enabled = model.subscriptions.isNotEmpty(), onClick = { notify("Simulated refresh complete") }) { Text(demoLabel("Refresh all", language)) }
}
