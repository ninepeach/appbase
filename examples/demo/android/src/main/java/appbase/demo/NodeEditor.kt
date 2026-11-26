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
internal fun NodeEditor(model:DemoModel, language:String, notify:(String)->Unit, back:()->Unit, disconnect:()->Unit, confirm:(String,()->Unit)->Unit) {
    val nodes=model.nodes

                                        val original=model.editing
                                        var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
                                        var host by rememberSaveable { mutableStateOf(original?.host.orEmpty()) }
                                        var port by rememberSaveable { mutableStateOf(original?.port ?: "443") }
                                        var group by rememberSaveable { mutableStateOf(original?.group ?: "Local") }
                                        OutlinedTextField(group,{group=it},label={Text(demoLabel("Group",language))},modifier=Modifier.fillMaxWidth())
                                        var protocol by rememberSaveable { mutableStateOf(original?.protocol ?: "VLESS") }
                                        val valid=name.isNotBlank()&&host.isNotBlank()&&port.toIntOrNull()?.let{it in 1..65535}==true
                                        OutlinedTextField(name,{name=it},label={Text(demoLabel("Name",language))},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(host,{host=it},label={Text(demoLabel("Server address",language))},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(port,{port=it},label={Text(demoLabel("Port",language))},isError=port.toIntOrNull()?.let{it in 1..65535}!=true,modifier=Modifier.fillMaxWidth())
                                        Group { listOf("VLESS","Shadowsocks","Trojan","SOCKS5","HTTP").forEach { p -> Choice(p,protocol==p){protocol=p} } }
                                        Button(enabled=valid,onClick={val n=Node(original?.id ?: model.nextId++,name.trim(),host.trim(),port,protocol,group.trim().ifBlank{"Local"});val i=nodes.indexOfFirst{it.id==n.id};if(i<0)nodes.add(n) else nodes[i]=n;notify("Node saved in memory");back()},modifier=Modifier.fillMaxWidth()){Text(demoLabel("Save",language))}
                                        if(original!=null) OutlinedButton(onClick={confirm("Delete node?") {nodes.removeAll{it.id==original.id};if(model.selected==original.id){disconnect();model.selected=nodes.firstOrNull()?.id ?: -1};back()}},modifier=Modifier.fillMaxWidth()){Text(demoLabel("Delete node",language))}
                                    
}
