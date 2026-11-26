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
internal fun DemoApp() {
    var appearance by rememberSaveable { mutableStateOf("System") }
    val dark = appearance == "Dark" || (appearance == "System" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xff64aaff)) else lightColorScheme(primary = Color(0xff0066cc))) {
        DemoShell(appearance) { appearance = it }
    }
}

@Composable
private fun DemoShell(appearance: String, setAppearance: (String) -> Unit) {
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
    val nodes=model.nodes
    val configs=model.configs
    val configBodies=model.configBodies
    val subscriptions=model.subscriptions
    val logs=model.logs
    val options=model.options
    var connected by rememberSaveable { mutableStateOf(false) }
    var routing by rememberSaveable { mutableStateOf("Config") }
    var language by rememberSaveable { mutableStateOf("System") }
    val queue = remember { PresentationQueue() }
    DisposableEffect(queue) { onDispose { queue.clear() } }
    fun confirm(title:String, action:()->Unit) { queue.enqueue(ModalRequest(title,title,"This changes the current demo session only.",action=action)) }
    val snackbar = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { queue.notify(notice); notice = "" } }
    fun open(p: String) { nav.push(p) }
    fun back() { val key="$tab:${nav.stack.value.last().instance}"; if(nav.pop()) holder.removeState(key) }
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
                        ScreenHost(holder,"$tab:${stack.last().instance}") { transition ->
                            Column(Modifier.fillMaxSize().then(transition).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                when(page) {
                                    "Home" -> {
                                        Group {
                                            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                                Text(demoLabel(if(connected) "Connected · simulated" else "Disconnected",language),Modifier.weight(1f))
                                                Switch(connected,{ if(nodes.any{n->n.id==model.selected}) { connected=it;addLog(if(it)"Simulated connection started" else "Disconnected") } else notice="Select a node first" })
                                            }
                                            Item(demoLabel("Global routing",language),routing){open("Routing")}
                                            Item("Connectivity test","Simulate"){notice="Simulated test complete";addLog("Connectivity test")}
                                        }
                                        var query by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(query,{query=it},label={Text(demoLabel("Search nodes",language))},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                        var batch by rememberSaveable { mutableStateOf(false) }
                                        val marked = remember { mutableStateListOf<Int>() }
                                        var groupFilter by rememberSaveable { mutableStateOf("All") }
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            TextButton(onClick={batch=!batch;marked.clear()}){Text(demoLabel(if(batch)"Done" else "Manage",language))}
                                            TextButton(onClick={controller.sortNodes()}){Text(demoLabel("Sort A–Z",language))}
                                            TextButton(onClick={val groups=listOf("All")+nodes.map{it.group}.distinct();groupFilter=groups[(groups.indexOf(groupFilter)+1)%groups.size]}){Text(groupFilter)}
                                        }
                                        if(batch) TextButton(enabled=marked.isNotEmpty(),onClick={val ids=marked.toSet();confirm("Delete selected nodes?"){nodes.removeAll{it.id in ids};if(model.selected in ids){connected=false;model.selected=nodes.firstOrNull()?.id ?: -1};marked.clear();batch=false}}){Text("Delete selected (${marked.size})")}
                                        Group {
                                            val visible=nodes.filter{it.name.contains(query,true)&&(groupFilter=="All"||it.group==groupFilter)}
                                            if(visible.isEmpty()) UIContent(ContentStatus.EMPTY,"No matching nodes") {}
                                            visible.forEach { n -> key(n.id) {
                                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                                    if(batch) Checkbox(n.id in marked,{if(it)marked.add(n.id) else marked.remove(n.id)}) else RadioButton(model.selected==n.id,{model.selected=n.id;connected=false})
                                                    Column(Modifier.weight(1f).clickable{model.selected=n.id;connected=false}.padding(vertical=12.dp)){Text(n.name);Text(n.group+" · "+n.protocol+" · "+n.host,style=MaterialTheme.typography.bodySmall)}
                                                    if(batch) {
                                                        Text("☰", Modifier.padding(12.dp).pointerInput(n.id) {
                                                            var distance=0f
                                                            detectDragGesturesAfterLongPress(onDragStart={distance=0f},onDragEnd={distance=0f},onDragCancel={distance=0f}) { change, delta ->
                                                                change.consume()
                                                                distance+=delta.y
                                                                val threshold=48.dp.toPx()
                                                                if(kotlin.math.abs(distance)>=threshold){
                                                                    val i=nodes.indexOfFirst{it.id==n.id}
                                                                    val target=i+if(distance>0)1 else -1
                                                                    if(i>=0&&target in nodes.indices){val item=nodes.removeAt(i);nodes.add(target,item)}
                                                                    distance=0f
                                                                }
                                                            }
                                                        })
                                                        TextButton(onClick={controller.moveNode(n.id,-1)}){Text("↑")}
                                                        TextButton(onClick={controller.moveNode(n.id,1)}){Text("↓")}
                                                    } else TextButton(onClick={model.editing=n;open("Node editor")}){Text(demoLabel("Edit",language))}
                                                }; HorizontalDivider()
                                            } }
                                        }
                                        Group { Item(demoLabel("Subscriptions",language),"Manage"){open("Subscriptions")};Item("Import node"){open("Import")} }
                                    }
                                    "Node editor" -> {
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
                                        Button(enabled=valid,onClick={val n=Node(original?.id ?: model.nextId++,name.trim(),host.trim(),port,protocol,group.trim().ifBlank{"Local"});val i=nodes.indexOfFirst{it.id==n.id};if(i<0)nodes.add(n) else nodes[i]=n;notice="Node saved in memory";back()},modifier=Modifier.fillMaxWidth()){Text(demoLabel("Save",language))}
                                        if(original!=null) OutlinedButton(onClick={confirm("Delete node?") {nodes.removeAll{it.id==original.id};if(model.selected==original.id){connected=false;model.selected=nodes.firstOrNull()?.id ?: -1};back()}},modifier=Modifier.fillMaxWidth()){Text(demoLabel("Delete node",language))}
                                    }
                                    "Routing" -> Group { listOf("Config","Proxy","Direct","Scene").forEach{Choice(it,routing==it){routing=it;back()}} }
                                    "Config" -> Group {
                                        configs.forEach { c -> Item(c,if(c==model.activeConfig)"Active" else ""){model.configEdit=c;open("Configuration editor")} }
                                        Item("Rules"){open("Rules")};Item("Import configuration"){open("Import")}
                                    }
                                    "Configuration editor" -> {
                                        var name by rememberSaveable { mutableStateOf(model.configEdit) }
                                        var body by rememberSaveable { mutableStateOf(configBodies[model.configEdit] ?: "# Demo configuration\n[General]\n") }
                                        OutlinedTextField(name,{name=it},label={Text(demoLabel("Name",language))},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(body,{body=it},label={Text(demoLabel("Configuration preview",language))},minLines=8,modifier=Modifier.fillMaxWidth())
                                        Button(enabled=name.isNotBlank(),onClick={if(name != model.configEdit && model.configEdit in configs){configs.remove(model.configEdit);configBodies.remove(model.configEdit)};if(name !in configs)configs.add(name);configBodies[name]=body;model.activeConfig=name;back()},modifier=Modifier.fillMaxWidth()){Text(demoLabel("Save and activate (mock)",language))}
                                        if(model.configEdit in configs) TextButton(onClick={confirm("Delete configuration?") {configs.remove(model.configEdit);configBodies.remove(model.configEdit);if(model.activeConfig==model.configEdit)model.activeConfig=configs.firstOrNull().orEmpty();back()}}){Text(demoLabel("Delete",language))}
                                    }
                                    "Data" -> {
                                        Group { Item(demoLabel("Upload",language),if(connected)"12 MB" else "0 MB"){open("Traffic")};Item(demoLabel("Download",language),if(connected)"86 MB" else "0 MB"){open("Traffic")};Item("Connections"){open("Connections")};Item("Logs"){open("Logs")} }
                                        Text("All traffic values are simulated.",style=MaterialTheme.typography.bodySmall)
                                    }
                                    "Traffic" -> { Text("Session traffic · mock"); repeat(7){i->Row(verticalAlignment=Alignment.CenterVertically){Text("Day ${i+1}",Modifier.width(64.dp));LinearProgressIndicator(progress={if(connected)(i+1)/8f else 0f},modifier=Modifier.weight(1f))}} }
                                    "Connections" -> { if(connected) Group{Item("example.org:443","TCP"){notice="Simulated connection"}} else UIContent(ContentStatus.EMPTY,"No active connections") {} }
                                    "Logs" -> {
                                        var filter by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(filter,{filter=it},label={Text(demoLabel("Filter logs",language))},modifier=Modifier.fillMaxWidth())
                                        logs.filter{it.contains(filter,true)}.forEach{Text(it)}
                                        TextButton(onClick={confirm("Clear logs?") {logs.clear()}}){Text(demoLabel("Clear",language))}
                                    }
                                    "Settings" -> {
                                        Group { Item(demoLabel("Appearance",language),appearance){open("Appearance")};Item(demoLabel("Language",language),language){open("Language")};Item("DNS"){open("DNS")};Item("Notifications"){open("Notifications")};Item("Privacy"){open("Privacy")};Item("Advanced"){open("Advanced")} }
                                        Group { Item("Diagnostics"){open("Diagnostics")};Item("About"){open("About")};Item("Onboarding"){open("Onboarding")} }
                                    }
                                    "Appearance" -> Group { listOf("System","Light","Dark").forEach{Choice(it,appearance==it){setAppearance(it)}} }
                                    "Language" -> { Text(demoLabel("Language",language));Group{listOf("System","English","繁體中文","简体中文","日本語").forEach{Choice(it,language==it){language=it}}} }
                                    "DNS","Notifications","Privacy","Advanced" -> Group {
                                        val names=when(page){"DNS"->listOf("Use custom DNS","DNS over HTTPS");"Notifications"->listOf("Connection notifications","Error notifications");"Privacy"->listOf("Redact logs","Hide server address");else->listOf("Auto connect","Verbose logging","Show diagnostics")}
                                        names.forEach{n->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(n,Modifier.weight(1f));Switch(options[n]?:false,{options[n]=it})};HorizontalDivider()}
                                    }
                                    "Diagnostics" -> Group { Item("UI stack",stack.size.toString()){notice=stack.joinToString(" → "){it.id}};Item("Nodes",nodes.size.toString()){notice="In-memory UI state"};Item("Runtime logs"){open("Logs")} }
                                    "About" -> { Text("Appbase Demo",style=MaterialTheme.typography.headlineMedium);Text("Native Compose UI prototype. No VPN, networking or persistent storage.");Group{Item("Open source licenses"){open("Licenses")}} }
                                    "Licenses" -> Text("Dependency license inventory is not yet generated.")
                                    "Rules" -> Group { listOf("DOMAIN,example.org,PROXY","GEOIP,LOCAL,DIRECT","FINAL,PROXY").forEach{Text(it,Modifier.padding(vertical=12.dp))} }
                                    "Subscriptions" -> {
                                        var url by rememberSaveable { mutableStateOf("") }
                                        if(subscriptions.isEmpty()) UIContent(ContentStatus.EMPTY,"No subscriptions") {}
                                        subscriptions.forEach { address -> Group { Item(address,"Remove"){confirm("Remove subscription?"){subscriptions.remove(address)}} } }
                                        OutlinedTextField(url,{url=it},label={Text(demoLabel("Subscription URL",language))},modifier=Modifier.fillMaxWidth())
                                        Button(enabled=url.startsWith("https://")&&url !in subscriptions,onClick={subscriptions.add(url);url=""}){Text(demoLabel("Add",language))}
                                        Button(enabled=subscriptions.isNotEmpty(),onClick={notice="Simulated refresh complete"}){Text(demoLabel("Refresh all",language))}
                                    }
                                    "Import" -> {
                                        var input by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(input,{input=it},label={Text(if(page=="Import")"Paste configuration or URI" else "Subscription URL")},modifier=Modifier.fillMaxWidth())
                                        Button(enabled=input.isNotBlank(),onClick={notice="Preview only: no network or import parser";back()}){Text(demoLabel("Preview",language))}
                                    }
                                    "Onboarding" -> {Text("Welcome to Appbase",style=MaterialTheme.typography.headlineMedium);Text("Explore nodes, configuration and simulated traffic. No VPN permission is required.");Button(onClick={back()}){Text(demoLabel("Done",language))} }
                                }
                            }
                        }
                    }
                }
            }
        }
        PresentationHost(queue,snackbar)
    }
}

