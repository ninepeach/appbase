package appbase.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoApp() }
    }
}

private data class Node(val id: Int, val name: String, val host: String, val port: String, val protocol: String)
private val tabs = listOf("Home", "Config", "Data", "Settings")
private val pages = listOf("Home","Config","Data","Settings","Node editor","Routing","Subscriptions","Configuration editor","Rules","Traffic","Connections","Logs","Appearance","Language","DNS","Notifications","Privacy","Advanced","Diagnostics","About","Licenses","Import","Onboarding")

@Composable
private fun DemoApp() {
    var appearance by rememberSaveable { mutableStateOf("System") }
    val dark = appearance == "Dark" || (appearance == "System" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xff64aaff)) else lightColorScheme(primary = Color(0xff0066cc))) {
        DemoShell(appearance) { appearance = it }
    }
}

@Composable
private fun DemoShell(appearance: String, setAppearance: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val navs = remember { tabs.map { Navigation(pages.associateWith { Layer.SCREEN }, it) } }
    val nav = navs[tab]
    val stack by nav.stack.collectAsState()
    val page = stack.last().id
    val holder = rememberSaveableStateHolder()
    val nodes = remember { mutableStateListOf(Node(1,"Tokyo 01","tokyo.example","443","VLESS"),Node(2,"Hong Kong 01","hk.example","443","Shadowsocks"),Node(3,"Singapore 01","sg.example","443","Trojan")) }
    var nextId by remember { mutableIntStateOf(4) }
    var selected by remember { mutableIntStateOf(1) }
    var editing by remember { mutableStateOf<Node?>(null) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var routing by rememberSaveable { mutableStateOf("Config") }
    var language by rememberSaveable { mutableStateOf("System") }
    val configs = remember { mutableStateListOf("Default","Direct","Proxy") }
    var activeConfig by remember { mutableStateOf("Default") }
    var configEdit by remember { mutableStateOf("") }
    val logs = remember { mutableStateListOf("Demo initialized") }
    val options = remember { mutableStateMapOf<String,Boolean>() }
    val queue = remember { PresentationQueue() }
    fun confirm(title:String, action:()->Unit) { queue.enqueue(ModalRequest(title,title,"This changes the current demo session only.",action=action)) }
    val snackbar = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { queue.notify(notice); notice = "" } }
    fun open(p: String) { nav.push(p) }
    fun back() { nav.pop() }
    fun addLog(s: String) { logs.add(0,s); if(logs.size > 100) logs.removeAt(logs.lastIndex) }
    BackHandler(stack.size > 1 || tab != 0) { if (!nav.pop()) tab = 0 }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val wide = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail { tabs.forEachIndexed { i,t -> NavigationRailItem(selected = tab==i,onClick={tab=i},icon={Text(listOf("⌂","▤","∿","⚙")[i])},label={Text(t)}) } }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = { if (!wide && stack.size == 1) NavigationBar { tabs.forEachIndexed { i,t -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("⌂","▤","∿","⚙")[i])},label={Text(t)}) } } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(stack.size>1) TextButton(onClick={back()}) { Text("‹ Back") }
                            Text(page,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                            if(page=="Home") TextButton(onClick={editing=null;open("Node editor")}) { Text("+ Add") }
                            if(page=="Config") TextButton(onClick={configEdit="";open("Configuration editor")}) { Text("+ Add") }
                        }
                        holder.SaveableStateProvider("$tab:${stack.last().instance}") {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                when(page) {
                                    "Home" -> {
                                        Group {
                                            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                                Text(if(connected) "Connected · simulated" else "Disconnected",Modifier.weight(1f))
                                                Switch(connected,{ if(nodes.any{n->n.id==selected}) { connected=it;addLog(if(it)"Simulated connection started" else "Disconnected") } else notice="Select a node first" })
                                            }
                                            Item("Global routing",routing){open("Routing")}
                                            Item("Connectivity test","Simulate"){notice="Simulated test complete";addLog("Connectivity test")}
                                        }
                                        var query by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(query,{query=it},label={Text("Search nodes")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                        Text("LOCAL SERVERS",style=MaterialTheme.typography.labelMedium)
                                        Group {
                                            val visible=nodes.filter{it.name.contains(query,true)}
                                            if(visible.isEmpty()) UIContent(ContentStatus.EMPTY,"No matching nodes") {}
                                            visible.forEach { n ->
                                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                                    RadioButton(selected==n.id,{selected=n.id;connected=false})
                                                    Column(Modifier.weight(1f).clickable{selected=n.id;connected=false}.padding(vertical=12.dp)){Text(n.name);Text(n.protocol+" · "+n.host,style=MaterialTheme.typography.bodySmall)}
                                                    TextButton(onClick={editing=n;open("Node editor")}){Text("Edit")}
                                                }; HorizontalDivider()
                                            }
                                        }
                                        Group { Item("Subscriptions","Manage"){open("Subscriptions")};Item("Import node"){open("Import")} }
                                    }
                                    "Node editor" -> {
                                        val original=editing
                                        var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
                                        var host by rememberSaveable { mutableStateOf(original?.host.orEmpty()) }
                                        var port by rememberSaveable { mutableStateOf(original?.port ?: "443") }
                                        var protocol by rememberSaveable { mutableStateOf(original?.protocol ?: "VLESS") }
                                        val valid=name.isNotBlank()&&host.isNotBlank()&&port.toIntOrNull()?.let{it in 1..65535}==true
                                        OutlinedTextField(name,{name=it},label={Text("Name")},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(host,{host=it},label={Text("Server address")},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(port,{port=it},label={Text("Port")},isError=port.toIntOrNull()?.let{it in 1..65535}!=true,modifier=Modifier.fillMaxWidth())
                                        Group { listOf("VLESS","Shadowsocks","Trojan","SOCKS5","HTTP").forEach { p -> Choice(p,protocol==p){protocol=p} } }
                                        Button(enabled=valid,onClick={val n=Node(original?.id ?: nextId++,name.trim(),host.trim(),port,protocol);val i=nodes.indexOfFirst{it.id==n.id};if(i<0)nodes.add(n) else nodes[i]=n;notice="Node saved in memory";back()},modifier=Modifier.fillMaxWidth()){Text("Save")}
                                        if(original!=null) OutlinedButton(onClick={confirm("Delete node?") {nodes.removeAll{it.id==original.id};if(selected==original.id){connected=false;selected=nodes.firstOrNull()?.id ?: -1};back()}},modifier=Modifier.fillMaxWidth()){Text("Delete node")}
                                    }
                                    "Routing" -> Group { listOf("Config","Proxy","Direct","Scene").forEach{Choice(it,routing==it){routing=it;back()}} }
                                    "Config" -> Group {
                                        configs.forEach { c -> Item(c,if(c==activeConfig)"Active" else ""){configEdit=c;open("Configuration editor")} }
                                        Item("Rules"){open("Rules")};Item("Import configuration"){open("Import")}
                                    }
                                    "Configuration editor" -> {
                                        var name by rememberSaveable { mutableStateOf(configEdit) }
                                        var body by rememberSaveable { mutableStateOf("# Demo configuration\n[General]\n") }
                                        OutlinedTextField(name,{name=it},label={Text("Name")},modifier=Modifier.fillMaxWidth())
                                        OutlinedTextField(body,{body=it},label={Text("Configuration preview")},minLines=8,modifier=Modifier.fillMaxWidth())
                                        Button(enabled=name.isNotBlank(),onClick={if(name !in configs)configs.add(name);activeConfig=name;back()},modifier=Modifier.fillMaxWidth()){Text("Save and activate (mock)")}
                                        if(configEdit in configs) TextButton(onClick={confirm("Delete configuration?") {configs.remove(configEdit);if(activeConfig==configEdit)activeConfig=configs.firstOrNull().orEmpty();back()}}){Text("Delete")}
                                    }
                                    "Data" -> {
                                        Group { Item("Upload",if(connected)"12 MB" else "0 MB"){open("Traffic")};Item("Download",if(connected)"86 MB" else "0 MB"){open("Traffic")};Item("Connections"){open("Connections")};Item("Logs"){open("Logs")} }
                                        Text("All traffic values are simulated.",style=MaterialTheme.typography.bodySmall)
                                    }
                                    "Traffic" -> { Text("Session traffic · mock"); repeat(7){i->Row(verticalAlignment=Alignment.CenterVertically){Text("Day ${i+1}",Modifier.width(64.dp));LinearProgressIndicator(progress={if(connected)(i+1)/8f else 0f},modifier=Modifier.weight(1f))}} }
                                    "Connections" -> { if(connected) Group{Item("example.org:443","TCP"){notice="Simulated connection"}} else Text("No active connections") }
                                    "Logs" -> {
                                        var filter by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(filter,{filter=it},label={Text("Filter logs")},modifier=Modifier.fillMaxWidth())
                                        logs.filter{it.contains(filter,true)}.forEach{Text(it)}
                                        TextButton(onClick={confirm("Clear logs?") {logs.clear()}}){Text("Clear")}
                                    }
                                    "Settings" -> {
                                        Group { Item("Appearance",appearance){open("Appearance")};Item("Language",language){open("Language")};Item("DNS"){open("DNS")};Item("Notifications"){open("Notifications")};Item("Privacy"){open("Privacy")};Item("Advanced"){open("Advanced")} }
                                        Group { Item("Diagnostics"){open("Diagnostics")};Item("About"){open("About")};Item("Onboarding"){open("Onboarding")} }
                                    }
                                    "Appearance" -> Group { listOf("System","Light","Dark").forEach{Choice(it,appearance==it){setAppearance(it)}} }
                                    "Language" -> { Text("Language preference preview; translations not implemented.");Group{listOf("System","English","繁體中文","简体中文","日本語").forEach{Choice(it,language==it){language=it}}} }
                                    "DNS","Notifications","Privacy","Advanced" -> Group {
                                        val names=when(page){"DNS"->listOf("Use custom DNS","DNS over HTTPS");"Notifications"->listOf("Connection notifications","Error notifications");"Privacy"->listOf("Redact logs","Hide server address");else->listOf("Auto connect","Verbose logging","Show diagnostics")}
                                        names.forEach{n->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(n,Modifier.weight(1f));Switch(options[n]?:false,{options[n]=it})};HorizontalDivider()}
                                    }
                                    "Diagnostics" -> Group { Item("UI stack",stack.size.toString()){notice=stack.joinToString(" → "){it.id}};Item("Nodes",nodes.size.toString()){notice="In-memory UI state"};Item("Runtime logs"){open("Logs")} }
                                    "About" -> { Text("Appbase Demo",style=MaterialTheme.typography.headlineMedium);Text("Native Compose UI prototype. No VPN, networking or persistent storage.");Group{Item("Open source licenses"){open("Licenses")}} }
                                    "Licenses" -> Text("Dependency license inventory is not yet generated.")
                                    "Rules" -> Group { listOf("DOMAIN,example.org,PROXY","GEOIP,LOCAL,DIRECT","FINAL,PROXY").forEach{Text(it,Modifier.padding(vertical=12.dp))} }
                                    "Subscriptions","Import" -> {
                                        var input by rememberSaveable { mutableStateOf("") }
                                        OutlinedTextField(input,{input=it},label={Text(if(page=="Import")"Paste configuration or URI" else "Subscription URL")},modifier=Modifier.fillMaxWidth())
                                        Button(enabled=input.isNotBlank(),onClick={notice="Preview only: no network or import parser";back()}){Text("Preview")}
                                    }
                                    "Onboarding" -> {Text("Welcome to Appbase",style=MaterialTheme.typography.headlineMedium);Text("Explore nodes, configuration and simulated traffic. No VPN permission is required.");Button(onClick={back()}){Text("Done")} }
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
