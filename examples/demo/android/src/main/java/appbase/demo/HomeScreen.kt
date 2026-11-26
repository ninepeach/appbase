package appbase.demo

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import appbase.ui.*
import appbase.ui.UIGroup as Group
import appbase.ui.UIRow as Item

@Composable
internal fun HomeScreen(
    model: DemoModel,
    controller: DemoController,
    language: String,
    connected: Boolean,
    setConnected: (Boolean) -> Unit,
    notify: (String) -> Unit,
    open: (String) -> Unit,
    confirm: (String, () -> Unit) -> Unit,
    routing: String,
) {
    val nodes = model.nodes
    Group {
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(demoLabel(if(connected) "Connected · simulated" else "Disconnected",language),Modifier.weight(1f))
            Switch(connected,{ if(nodes.any{n->n.id==model.selected}) { setConnected(it);controller.log(if(it)"Simulated connection started" else "Disconnected") } else notify("Select a node first") })
        }
        Item(demoLabel("Global routing",language),demoLabel(routing,language)){open("Routing")}
        Item(demoLabel("Connectivity test",language),demoLabel("Simulate",language)){notify("Simulated test complete");controller.log("Connectivity test")}
    }
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(query,{query=it},label={Text(demoLabel("Search nodes",language))},modifier=Modifier.fillMaxWidth(),singleLine=true)
    var batch by rememberSaveable { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf("") }
    val groups = listOf<String?>(null) + nodes.map { it.group }.distinct()
    val activeFilter = groupFilter.takeIf { it in groups }
    Row(verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={batch=!batch;marked = arrayListOf()}){Text(demoLabel(if(batch)"Done" else "Manage",language))}
        TextButton(onClick={controller.sortNodes()}){Text(demoLabel("Sort A–Z",language))}
        TextButton(onClick={groupFilter=groups[(groups.indexOf(activeFilter)+1)%groups.size]}){Text(activeFilter ?: demoLabel("All", language))}
    }
    if(batch) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled=marked.isNotEmpty(),onClick={val ids=marked.toSet();confirm("Delete selected nodes?"){if (model.selected in ids) setConnected(false);controller.deleteNodes(ids);marked = arrayListOf();batch=false}}){Text(demoLabel("Delete selected",language) + " (" + marked.size + ")")}
            TextButton(enabled = marked.isNotEmpty() && destination.isNotBlank(), onClick = {
                controller.moveNodesToGroup(marked.toSet(), destination)
                marked = arrayListOf()
                destination = ""
            }) { Text(demoLabel("Move to group", language)) }
        }
        OutlinedTextField(destination, { destination = it }, label = { Text(demoLabel("Group", language)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
    Group {
        val visible=nodes.filter{it.name.contains(query,true)&&(activeFilter==null||it.group==activeFilter)}
        if(visible.isEmpty()) UIContent(ContentStatus.EMPTY,demoLabel("No matching nodes",language)) {}
        val visibleIds by rememberUpdatedState(visible.map { it.id })
        visible.forEach { n -> key(n.id) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(batch) Checkbox(n.id in marked,{marked = ArrayList(if (it) marked + n.id else marked - n.id)}) else RadioButton(model.selected==n.id,{model.selected=n.id;setConnected(false)})
                Column(Modifier.weight(1f).clickable {
                    if (batch) marked = ArrayList(if (n.id in marked) marked - n.id else marked + n.id)
                    else if (model.selected != n.id) { model.selected = n.id; setConnected(false) }
                }.padding(vertical=12.dp)){Text(n.name);Text(n.group+" · "+n.protocol+" · "+n.host,style=MaterialTheme.typography.bodySmall)}
                if(batch) {
                    Text("☰", Modifier.padding(12.dp).pointerInput(n.id) {
                        var distance=0f
                        detectDragGesturesAfterLongPress(onDragStart={distance=0f},onDragEnd={distance=0f},onDragCancel={distance=0f}) { change, delta ->
                            change.consume()
                            distance+=delta.y
                            val threshold=48.dp.toPx()
                            if(kotlin.math.abs(distance)>=threshold){
                                controller.moveNode(n.id, if (distance > 0) 1 else -1, visibleIds)
                                distance=0f
                            }
                        }
                    })
                    TextButton(onClick={controller.moveNode(n.id,-1,visibleIds)}){Text("↑")}
                    TextButton(onClick={controller.moveNode(n.id,1,visibleIds)}){Text("↓")}
                } else TextButton(onClick={model.editing=n;open("Node editor")}){Text(demoLabel("Edit",language))}
            }; HorizontalDivider()
        } }
    }
    Group { Item(demoLabel("Subscriptions",language),demoLabel("Manage",language)){open("Subscriptions")};Item(demoLabel("Import node",language)){open("Import")} }
}
