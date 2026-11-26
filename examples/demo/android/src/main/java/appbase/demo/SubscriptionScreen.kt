package appbase.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.Saver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import appbase.Layer
import appbase.Navigation
import appbase.ui.UIGroup as Group
import appbase.ui.UIRow as Item
import appbase.ui.UIChoice as Choice
import appbase.ui.*

@Composable
internal fun SubscriptionScreen(model:DemoModel,language:String,notify:(String)->Unit,confirm:(String,()->Unit)->Unit) {
    val subscriptions=model.subscriptions

                                        var url by rememberSaveable { mutableStateOf("") }
                                        if(subscriptions.isEmpty()) UIContent(ContentStatus.EMPTY,"No subscriptions") {}
                                        subscriptions.forEach { address -> Group { Item(address,"Remove"){confirm("Remove subscription?"){subscriptions.remove(address)}} } }
                                        OutlinedTextField(url,{url=it},label={Text(demoLabel("Subscription URL",language))},modifier=Modifier.fillMaxWidth())
                                        Button(enabled=url.startsWith("https://")&&url !in subscriptions,onClick={subscriptions.add(url);url=""}){Text(demoLabel("Add",language))}
                                        Button(enabled=subscriptions.isNotEmpty(),onClick={notify("Simulated refresh complete")}){Text(demoLabel("Refresh all",language))}
                                    
}
