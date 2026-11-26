package appbase.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import appbase.ui.UIGroup as Group
import appbase.ui.UIRow as Item
import appbase.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SubscriptionScreen(model:DemoModel,language:String,notify:(String)->Unit,confirm:(String,()->Unit)->Unit, queue: PresentationQueue) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    if (model.subscriptions.isEmpty()) UIContent(ContentStatus.EMPTY, demoLabel("No subscriptions", language)) {}
    model.subscriptions.forEach { address -> Group { Item(address, demoLabel("Remove", language)) { confirm("Remove subscription?") { model.subscriptions.remove(address) } } } }
    val validUrl = remember(url) {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank() && uri?.userInfo == null
    }
    if (url.isNotEmpty() && !validUrl) {
        UIContent(ContentStatus.ERROR, demoLabel("Enter a valid HTTPS URL", language)) {}
    }
    OutlinedTextField(url, { url = it.trim() }, label = { Text(demoLabel("Subscription URL", language)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = url.isNotEmpty() && !validUrl)
    Button(enabled = validUrl && url !in model.subscriptions, onClick = { model.subscriptions.add(url); url = "" }) { Text(demoLabel("Add", language)) }
    Button(enabled = model.subscriptions.isNotEmpty() && !refreshing, onClick = {
        refreshing = true
        scope.launch {
            try {
                val token = queue.beginOverlay(demoLabel("Refreshing subscriptions…", language))
                try {
                    delay(450)
                    notify("Simulated refresh complete")
                } finally {
                    queue.endOverlay(token)
                }
            } finally {
                refreshing = false
            }
        }
    }) { Text(demoLabel("Refresh all", language)) }
}
