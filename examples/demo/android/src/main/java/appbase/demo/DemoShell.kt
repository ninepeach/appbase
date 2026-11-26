package appbase.demo

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import appbase.ui.UIGroup as Group
import appbase.ui.UIRow as Item
import appbase.ui.UIChoice as Choice
import appbase.ui.*

private val tabs = listOf("Home", "Config", "Data", "Settings")
private val pages = setOf("Home","Config","Data","Settings","Node editor","Routing","Subscriptions","Configuration editor","Rules","Traffic","Connections","Logs","Appearance","Language","DNS","Notifications","Privacy","Advanced","Diagnostics","About","Licenses","Import","Onboarding")

@Composable
internal fun DemoApp(deviceEvent: String?, onDeviceEventHandled: () -> Unit) {
    var appearance by rememberSaveable { mutableStateOf("System") }
    val dark = appearance == "Dark" || (appearance == "System" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xff64aaff)) else lightColorScheme(primary = Color(0xff0066cc))) {
        DemoShell(appearance, { appearance = it }, deviceEvent, onDeviceEventHandled)
    }
}

@Composable
private fun DemoShell(appearance: String, setAppearance: (String) -> Unit, deviceEvent: String?, onDeviceEventHandled: () -> Unit) = UIHost.Pages(tabs, pages) { ui, queue, snackbar ->
    val tab = ui.rootIndex
    val stack = ui.stack
    val page = ui.page
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
    fun open(page: String) = ui.open(page)
    fun back() = ui.back()
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val wide = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail { tabs.forEachIndexed { i,t -> NavigationRailItem(selected = tab==i,onClick={ui.selectRoot(i)},icon={Text(listOf("⌂","▤","∿","⚙")[i])},label={Text(demoLabel(t,language))}) } }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = { if (!wide && stack.size == 1) NavigationBar { tabs.forEachIndexed { i,t -> NavigationBarItem(selected=tab==i,onClick={ui.selectRoot(i)},icon={Text(listOf("⌂","▤","∿","⚙")[i])},label={Text(demoLabel(t,language))}) } } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(stack.size>1) TextButton(onClick={back()}) { Text(demoLabel("‹ Back",language)) }
                            Text(demoLabel(page,language),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                            if(page=="Home") TextButton(onClick={model.editing=null;open("Node editor")}) { Text(demoLabel("+ Add",language)) }
                            if(page=="Config") TextButton(onClick={model.configEdit="";open("Configuration editor")}) { Text(demoLabel("+ Add",language)) }
                        }
                        ui.Content { transition ->
                            UIPage(transition) {
                                when(page) {
                                    "Home" -> HomeScreen(model, controller, language, connected, { connected = it }, { notice = it }, ::open, ::confirm, routing)
                                    "Node editor" -> NodeEditor(model,controller,language,{notice=it},{back()},{connected=false},::confirm)
                                    "Routing" -> Group { listOf("Config","Proxy","Direct","Scene").forEach{Choice(demoLabel(it,language),routing==it){routing=it;back()}} }
                                    "Config" -> Group {
                                        configs.forEach { c -> Item(c,if(c==model.activeConfig)demoLabel("Active",language) else ""){model.configEdit=c;open("Configuration editor")} }
                                        Item(demoLabel("Rules",language)){open("Rules")};Item(demoLabel("Import configuration",language)){open("Import")}
                                    }
                                    "Configuration editor" -> ConfigurationEditor(model,controller,language,::back,::confirm)
                                    "Data", "Traffic", "Connections", "Logs" -> DataScreen(page, language, connected, logs, ::open, { notice = it }, ::confirm)
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

