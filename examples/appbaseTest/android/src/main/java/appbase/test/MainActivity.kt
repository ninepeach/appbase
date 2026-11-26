package appbase.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import appbase.AndroidSavedState
import appbase.App
import appbase.AppContext
import appbase.AppEvent
import appbase.AppPhase
import appbase.Capability
import appbase.ChannelCommand
import appbase.Command
import appbase.CommandService
import appbase.Execution
import appbase.LogLevel
import appbase.Plugin
import appbase.SavedState
import appbase.StateKey
import appbase.UIHost
import appbase.UIInstance
import appbase.UILayer
import appbase.UIManager
import appbase.UIRoute
import appbase.WakeReason
import appbase.WakeRequest
import appbase.WakeResult
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

private object Increment : Command<Int>
private object Timeout : Command<Unit>
private object Numbers : ChannelCommand<Int>
private data class ProbeEvent(val value: Int) : AppEvent
private val countKey = StateKey<Int>("demo.count")

private class CoreTestPlugin : Plugin {
    override fun install(context: AppContext, commands: CommandService) {
        context.state.declare(countKey, 0)
        commands.register(Increment::class, "demo.increment", Execution(serial = true)) {
            context.state.update(countKey) { it + 1 }
            context.state.observe(countKey).value
        }
        commands.register(Timeout::class, "demo.timeout", Execution(timeoutMs = 50)) { delay(500) }
        commands.registerChannel(Numbers::class, "demo.numbers") { flowOf(1, 2, 3) }
    }
}

private data class TestResult(val name: String, val passed: Boolean, val detail: String)

class MainActivity : ComponentActivity() {
    private val app = App(listOf(CoreTestPlugin()))
    private lateinit var savedState: SavedState
    private lateinit var uiManager: UIManager
    private lateinit var navigationRegistration: AutoCloseable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.start()
        savedState = SavedState(AndroidSavedState(savedStateRegistry))
        uiManager = UIManager(
            routes = listOf(
                UIRoute("home", UILayer.PAGE),
                UIRoute("detail", UILayer.PAGE),
                UIRoute("modal", UILayer.MODAL),
                UIRoute("overlay", UILayer.OVERLAY),
            ),
            roots = mapOf("main" to "home"),
        )
        navigationRegistration = uiManager.bindSavedState(savedState)
        UIHost(
            manager = uiManager,
            lifecycle = app.lifecycle,
            renderers = mapOf(
                "home" to { Home(it) },
                "detail" to { Detail(it) },
                "modal" to { Modal(it) },
                "overlay" to { Overlay(it) },
            ),
        ).mount(this) { content -> MaterialTheme { Surface(Modifier.fillMaxSize()) { content() } } }
    }

    override fun onStart() {
        super.onStart()
        app.lifecycle.transition(AppPhase.FOREGROUND)
    }

    override fun onStop() {
        if (app.lifecycle.phase.value != AppPhase.CLOSED) app.lifecycle.transition(AppPhase.BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        navigationRegistration.close()
        uiManager.close()
        savedState.close()
        app.close()
        super.onDestroy()
    }

    @Composable
    private fun Home(instance: UIInstance) {
        var running by remember { mutableStateOf(false) }
        var results by remember { mutableStateOf(emptyList<TestResult>()) }
        var restoredCount by remember { mutableStateOf((savedState.get("recreation.count") as? Int) ?: 0) }
        LaunchedEffect(running) {
            if (!running) return@LaunchedEffect
            results = runCoreTests()
            running = false
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Appbase Core Test", style = MaterialTheme.typography.headlineSmall)
            Text("Public API verification only. No product simulation or Core extensions.")
            Button(onClick = { running = true }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Running…" else "Run all tests")
            }
            if (results.isNotEmpty()) {
                Text("${results.count { it.passed }} / ${results.size} passed", style = MaterialTheme.typography.titleMedium)
                results.forEach { ResultRow(it) }
            }
            Text("Manual UIHost / UIManager checks", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uiManager.openPage("detail") }) { Text("Page") }
                OutlinedButton(onClick = { uiManager.openModal("modal") }) { Text("Modal") }
                OutlinedButton(onClick = { uiManager.showOverlay("overlay") }) { Text("Overlay") }
            }
            Text("SavedState recreation count: $restoredCount")
            OutlinedButton(onClick = {
                restoredCount += 1
                check(savedState.set("recreation.count", restoredCount))
                recreate()
            }) { Text("Save and recreate Activity") }
            Text("Current instance: ${instance.id.take(8)}")
        }
    }

    @Composable
    private fun ResultRow(result: TestResult) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = (if (result.passed) "PASS  " else "FAIL  ") + result.name,
                    color = if (result.passed) Color(0xFF137333) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(result.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun Detail(instance: UIInstance) {
        TestSurface("Page rendered by UIHost", instance) { uiManager.back() }
    }

    @Composable
    private fun Modal(instance: UIInstance) {
        TestSurface("Modal rendered by UIHost", instance) { uiManager.close(instance.id) }
    }

    @Composable
    private fun Overlay(instance: UIInstance) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Overlay rendered by UIHost")
            OutlinedButton(onClick = { uiManager.close(instance.id) }) { Text("Close overlay") }
        }
    }

    @Composable
    private fun TestSurface(title: String, instance: UIInstance, close: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("Instance ${instance.id.take(8)}")
                Button(onClick = close) { Text("Back") }
            }
        }
    }

    private suspend fun runCoreTests(): List<TestResult> = coroutineScope {
        val tests = listOf<Pair<String, suspend () -> String>>(
            "Command" to {
                val client = app.commands.client(Capability(setOf("demo.increment", "demo.timeout")))
                check(client.execute(Increment) > 0)
                check(runCatching { client.execute(Timeout) }.exceptionOrNull() is TimeoutCancellationException)
                "execution and timeout"
            },
            "Event" to {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { app.event.observe(ProbeEvent::class).first() }
                app.event.emit(ProbeEvent(7))
                check(pending.await().value == 7)
                "emit and typed observation"
            },
            "State" to {
                app.state.set(countKey, 40)
                app.state.update(countKey) { it + 2 }
                check(app.state.observe(countKey).value == 42)
                "set, update, and StateFlow"
            },
            "Channel" to {
                val client = app.commands.client(Capability(setOf("demo.numbers")))
                check(client.stream(Numbers).toList() == listOf(1, 2, 3))
                "ordered Flow collection"
            },
            "Lifecycle" to {
                app.lifecycle.transition(AppPhase.BACKGROUND)
                check(app.lifecycle.foregroundScope() == null)
                app.lifecycle.transition(AppPhase.FOREGROUND)
                check(app.lifecycle.foregroundScope() != null)
                "background cancellation and foreground scope"
            },
            "Diagnostics" to {
                app.log.write(LogLevel.INFO, "demo.test")
                check(app.log.snapshot().last().code == "demo.test")
                check(app.inspector.traces.value.any { it.command == "demo.increment" })
                "bounded log and command trace"
            },
            "SavedState" to {
                check(savedState.set("test.value", 42))
                check(savedState.get("test.value") == 42)
                "Android adapter set and get"
            },
            "UIManager" to {
                val manager = UIManager(
                    listOf(UIRoute("root", UILayer.PAGE), UIRoute("page", UILayer.PAGE), UIRoute("modal", UILayer.MODAL)),
                    mapOf("main" to "root"),
                )
                try {
                    val page = checkNotNull(manager.openPage("page"))
                    val queued = manager.enqueueModal("modal", "demo.queue")
                    check(queued != null && manager.isAlive(queued))
                    check(manager.back() && !manager.isAlive(queued))
                    check(manager.close(page) && !manager.back())
                    "page, modal queue, and back"
                } finally {
                    manager.close()
                }
            },
            "UIHost" to {
                check(uiManager.state.value.instances.any { it.route.name == "home" })
                "mounted with live root renderer"
            },
            "WakeRouter" to {
                val route = "demo.${UUID.randomUUID()}"
                var calls = 0
                app.platform.register(route) { calls += 1 }
                val request = WakeRequest(WakeReason.DEEP_LINK, UUID.randomUUID().toString(), route, System.currentTimeMillis() + 60_000)
                check(app.platform.dispatch(request) == WakeResult.HANDLED)
                check(app.platform.dispatch(request) == WakeResult.DUPLICATE && calls == 1)
                "dispatch and in-memory deduplication"
            },
        )
        tests.map { (name, test) ->
            runCatching { test() }.fold(
                onSuccess = { TestResult(name, true, it) },
                onFailure = { TestResult(name, false, it.message ?: it::class.java.simpleName) },
            )
        }
    }
}
