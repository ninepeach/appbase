package appbase.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.Saver
import androidx.compose.foundation.layout.*
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

private val tabs = listOf("Home", "Config", "Data", "Settings")
private val pages = listOf("Home","Config","Data","Settings","Node editor","Routing","Subscriptions","Configuration editor","Rules","Traffic","Connections","Logs","Appearance","Language","DNS","Notifications","Privacy","Advanced","Diagnostics","About","Licenses","Import","Onboarding")

@Composable
internal fun DemoApp(deviceEvent: String?, onDeviceEventHandled: () -> Unit) {
    var appearance by rememberSaveable { mutableStateOf("System") }
    val dark = appearance == "Dark" || (appearance == "System" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xff64aaff)) else lightColorScheme(primary = Color(0xff0066cc))) {
        DemoShell(appearance, { appearance = it }, deviceEvent, onDeviceEventHandled)
    }
}

@Composable
private fun DemoShell(appearance: String, setAppearance: (String) -> Unit, deviceEvent: String?, onDeviceEventHandled: () -> Unit) = UIManager.Presentation { queue, snackbar ->
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val navSaver = Saver<List<Navigation>, ArrayList<String>>(
        save = { list -> ArrayList(list.map { n -> n.stack.value.joinToString("|") { it.id } }) },
        restore = { saved -> tabs.mapIndexed { i, root ->
            Navigation(pages.associateWith { Layer.SCREEN }, root).apply {
                saved.getOrNull(i)?.split("|")?.drop(1)?.takeWhile { it in pages }?.forEach { push(it) }
            }
        } }
    )
    val navs = rememberSaveable(saver=navSaver) { tabs.map { Navigation(pages.associateWith { Layer.SCREEN }, it) } }
    val nav = navs[tab]
    val stack by nav.stack.collectAsStateWithLifecycle()
    val page = stack.last().id
    val holder = rememberSaveableStateHolder()
    val model = rememberSaveable(saver=DemoModel.saver) { DemoModel() }
    val controller=remember(model){DemoController(model)}
    LaunchedEffect(deviceEvent) {
        if (deviceEvent != null) {
            controller.log(deviceEvent)
            onDeviceEventHandled()
        }
    }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,controller) {
        val observer=LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> controller.log("UI resumed")
                Lifecycle.Event.ON_PAUSE -> controller.log("UI paused")
                Lifecycle.Event.ON_START -> controller.log("UI foreground")
                Lifecycle.Event.ON_STOP -> controller.log("UI background")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val nodes=model.nodes
    val configs=model.configs
    val logs=model.logs
    val options=model.options
    var connected by rememberSaveable { mutableStateOf(false) }
    var routing by rememberSaveable { mutableStateOf("Config") }
    var language by rememberSaveable { mutableStateOf("System") }
    fun confirm(title:String, action:()->Unit) { queue.enqueue(ModalRequest(title, demoLabel(title, language), demoLabel("This changes the current demo session only.", language), demoLabel("Confirm", language), demoLabel("Cancel", language), action=action)) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice, language) { if (notice.isNotEmpty()) { queue.notify(demoLabel(notice, language)); notice = "" } }
    fun open(p: String) { nav.push(p) }
    fun back() { val key="$tab:${nav.stack.value.size}"; if(nav.pop()) holder.removeState(key) }
    fun addLog(s: String) { controller.log(s) }
    BackHandler(stack.size > 1 || tab != 0) { if (nav.stack.value.size > 1) back() else tab = 0 }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val wide = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail { tabs.forEachIndexed { i,t -> NavigationRailItem(selected = tab==i,onClick={tab=i},icon={Text(listOf("⌂","▤","∿","⚙")[i])},label={Text(demoLabel(t,language))}) } }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = { if (!wide && stack.size == 1) NavigationBar { tabs.forEachIndexed { i,t -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("⌂","▤","∿","⚙")[i])},label={Text(demoLabel(t,language))}) } } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(stack.size>1) TextButton(onClick={back()}) { Text(demoLabel("‹ Back",language)) }
                            Text(demoLabel(page,language),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                            if(page=="Home") TextButton(onClick={model.editing=null;open("Node editor")}) { Text(demoLabel("+ Add",language)) }
                            if(page=="Config") TextButton(onClick={model.configEdit="";open("Configuration editor")}) { Text(demoLabel("+ Add",language)) }
                        }
                        ScreenHost(holder,"$tab:${stack.size}") { transition ->
                            UIPage(transition) {
                                when(page) {
                                    "Home" -> {
                                        Group {
                                            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                                Text(demoLabel(if(connected) "Connected · simulated" else "Disconnected",language),Modifier.weight(1f))
                                                Switch(connected,{ if(nodes.any{n->n.id==model.selected}) { connected=it;addLog(if(it)"Simulated connection started" else "Disconnected") } else notice="Select a node first" })
                                            }
                                            Item(demoLabel("Global routing",language),demoLabel(routing,language)){open("Routing")}
                                            Item(demoLabel("Connectivity test",language),demoLabel("Simulate",language)){notice="Simulated test complete";addLog("Connectivity test")}
                                        }
                                        var query by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(query,{query=it},label={Text(demoLabel("Search nodes",language))},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                        var batch by rememberSaveable { mutableStateOf(false) }
                                        var marked by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
                                        var groupFilter by rememberSaveable { mutableStateOf("All") }
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            TextButton(onClick={batch=!batch;marked = arrayListOf()}){Text(demoLabel(if(batch)"Done" else "Manage",language))}
                                            TextButton(onClick={controller.sortNodes()}){Text(demoLabel("Sort A–Z",language))}
                                            TextButton(onClick={val groups=listOf("All")+nodes.map{it.group}.distinct();groupFilter=groups[(groups.indexOf(groupFilter)+1)%groups.size]}){Text(demoLabel(groupFilter, language))}
                                        }
                                        if(batch) {
                                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                                TextButton(enabled=marked.isNotEmpty(),onClick={val ids=marked.toSet();confirm("Delete selected nodes?"){if (model.selected in ids) connected=false;controller.deleteNodes(ids);marked = arrayListOf();batch=false}}){Text(demoLabel("Delete selected",language) + " (" + marked.size + ")")}
                                                TextButton(enabled=marked.isNotEmpty(),onClick={controller.moveNodesToGroup(marked.toSet(),"Local");marked = arrayListOf()}){Text(demoLabel("Move to Local",language))}
                                                TextButton(enabled=marked.isNotEmpty(),onClick={controller.moveNodesToGroup(marked.toSet(),"Work");marked = arrayListOf()}){Text(demoLabel("Move to Work",language))}
                                            }
                                        }
                                        Group {
                                            val visible=nodes.filter{it.name.contains(query,true)&&(groupFilter=="All"||it.group==groupFilter)}
                                            if(visible.isEmpty()) UIContent(ContentStatus.EMPTY,demoLabel("No matching nodes",language)) {}
                                            val visibleIds by rememberUpdatedState(visible.map { it.id })
                                            visible.forEach { n -> key(n.id) {
                                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                                    if(batch) Checkbox(n.id in marked,{marked = ArrayList(if (it) marked + n.id else marked - n.id)}) else RadioButton(model.selected==n.id,{model.selected=n.id;connected=false})
                                                    Column(Modifier.weight(1f).clickable{model.selected=n.id;connected=false}.padding(vertical=12.dp)){Text(n.name);Text(n.group+" · "+n.protocol+" · "+n.host,style=MaterialTheme.typography.bodySmall)}
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
                                    "Node editor" -> NodeEditor(model,controller,language,{notice=it},{back()},{connected=false},::confirm)
                                    "Routing" -> Group { listOf("Config","Proxy","Direct","Scene").forEach{Choice(demoLabel(it,language),routing==it){routing=it;back()}} }
                                    "Config" -> Group {
                                        configs.forEach { c -> Item(c,if(c==model.activeConfig)demoLabel("Active",language) else ""){model.configEdit=c;open("Configuration editor")} }
                                        Item(demoLabel("Rules",language)){open("Rules")};Item(demoLabel("Import configuration",language)){open("Import")}
                                    }
                                    "Configuration editor" -> ConfigurationEditor(model,controller,language,::back,::confirm)
                                    "Data" -> {
                                        Group { Item(demoLabel("Upload",language),if(connected)"12 MB" else "0 MB"){open("Traffic")};Item(demoLabel("Download",language),if(connected)"86 MB" else "0 MB"){open("Traffic")};Item(demoLabel("Connections",language)){open("Connections")};Item(demoLabel("Logs",language)){open("Logs")} }
                                        Text(demoLabel("All traffic values are simulated.",language),style=MaterialTheme.typography.bodySmall)
                                    }
                                    "Traffic" -> { Text(demoLabel("Session traffic · mock",language)); repeat(7){i->Row(verticalAlignment=Alignment.CenterVertically){Text(demoLabel("Day",language)+" "+(i+1),Modifier.width(64.dp));LinearProgressIndicator(progress={if(connected)(i+1)/8f else 0f},modifier=Modifier.weight(1f))}} }
                                    "Connections" -> { if(connected) Group{Item("example.org:443","TCP"){notice="Simulated connection"}} else UIContent(ContentStatus.EMPTY,demoLabel("No active connections",language)) {} }
                                    "Logs" -> {
                                        var filter by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(filter,{filter=it},label={Text(demoLabel("Filter logs",language))},modifier=Modifier.fillMaxWidth())
                                        logs.map { demoLabel(it, language) }.filter { it.contains(filter, true) }.forEach { Text(it) }
                                        TextButton(onClick={confirm("Clear logs?") {logs.clear()}}){Text(demoLabel("Clear",language))}
                                    }
                                    "Settings" -> {
                                        Group { Item(demoLabel("Appearance",language),demoLabel(appearance,language)){open("Appearance")};Item(demoLabel("Language",language),demoLabel(language,language)){open("Language")};Item("DNS"){open("DNS")};Item(demoLabel("Notifications",language)){open("Notifications")};Item(demoLabel("Privacy",language)){open("Privacy")};Item(demoLabel("Advanced",language)){open("Advanced")} }
                                        Group { Item(demoLabel("Diagnostics",language)){open("Diagnostics")};Item(demoLabel("About",language)){open("About")};Item(demoLabel("Onboarding",language)){open("Onboarding")} }
                                    }
                                    "Appearance" -> Group { listOf("System","Light","Dark").forEach{Choice(demoLabel(it,language),appearance==it){setAppearance(it)}} }
                                    "Language" -> { Text(demoLabel("Language",language));Group{listOf("System","English","繁體中文","简体中文","日本語").forEach{Choice(demoLabel(it,language),language==it){language=it}}} }
                                    "DNS","Notifications","Privacy","Advanced" -> Group {
                                        val names=when(page){"DNS"->listOf("Use custom DNS","DNS over HTTPS");"Notifications"->listOf("Connection notifications","Error notifications");"Privacy"->listOf("Redact logs","Hide server address");else->listOf("Auto connect","Verbose logging","Show diagnostics")}
                                        names.forEach{n->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(demoLabel(n,language),Modifier.weight(1f));Switch(options[n]?:false,{options[n]=it})};HorizontalDivider()}
                                    }
                                    "Diagnostics" -> Group { Item(demoLabel("UI stack",language),stack.size.toString()){notice=stack.joinToString(" → "){demoLabel(it.id, language)}};Item(demoLabel("Nodes",language),nodes.size.toString()){notice="In-memory UI state"};Item(demoLabel("Runtime logs",language)){open("Logs")} }
                                    "About" -> { Text("Appbase Demo",style=MaterialTheme.typography.headlineMedium);Text(demoLabel("No VPN, networking or persistent storage.",language));Group{Item(demoLabel("Open source licenses",language)){open("Licenses")}} }
                                    "Licenses" -> Text(demoLabel("Dependency license inventory is not yet generated.",language))
                                    "Rules" -> Group { listOf("DOMAIN,example.org,PROXY","GEOIP,LOCAL,DIRECT","FINAL,PROXY").forEach{Text(it,Modifier.padding(vertical=12.dp))} }
                                    "Subscriptions" -> SubscriptionScreen(model,language,{notice=it},::confirm,queue)
                                    "Import" -> {
                                        var input by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(input,{input=it},label={Text(demoLabel(if(page=="Import")"Paste configuration or URI" else "Subscription URL",language))},modifier=Modifier.fillMaxWidth())
                                        Button(enabled=input.isNotBlank(),onClick={notice="Preview only: no network or import parser";back()}){Text(demoLabel("Preview",language))}
                                    }
                                    "Onboarding" -> {Text(demoLabel("Welcome to Appbase",language),style=MaterialTheme.typography.headlineMedium);Text(demoLabel("No VPN, networking or persistent storage.",language));Button(onClick={back()}){Text(demoLabel("Done",language))} }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

