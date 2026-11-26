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
internal fun ConfigurationEditor(model:DemoModel,language:String,back:()->Unit,confirm:(String,()->Unit)->Unit) {
    val configs=model.configs
    val configBodies=model.configBodies

                                        var name by rememberSaveable { mutableStateOf(model.configEdit) }
                                        var body by rememberSaveable { mutableStateOf(configBodies[model.configEdit] ?: "# Demo configuration\n[General]\n") }
                                        OutlinedTextField(name,{name=it},label={Text(demoLabel("Name",language))},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(body,{body=it},label={Text(demoLabel("Configuration preview",language))},minLines=8,modifier=Modifier.fillMaxWidth())
                                        Button(enabled=name.isNotBlank(),onClick={if(name != model.configEdit && model.configEdit in configs){configs.remove(model.configEdit);configBodies.remove(model.configEdit)};if(name !in configs)configs.add(name);configBodies[name]=body;model.activeConfig=name;back()},modifier=Modifier.fillMaxWidth()){Text(demoLabel("Save and activate (mock)",language))}
                                        if(model.configEdit in configs) TextButton(onClick={confirm("Delete configuration?") {configs.remove(model.configEdit);configBodies.remove(model.configEdit);if(model.activeConfig==model.configEdit)model.activeConfig=configs.firstOrNull().orEmpty();back()}}){Text(demoLabel("Delete",language))}
                                    
}
