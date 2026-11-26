import appbase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
object Add : Command<Int>
object Slow : Command<Int>
object Stream : ChannelCommand<Int>
object Ping : AppEvent
fun main() = runBlocking {
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val childLifecycle = AppLifecycle(parent)
    childLifecycle.transition(AppPhase.FOREGROUND)
    parent.coroutineContext[Job]!!.cancelAndJoin()
    check(childLifecycle.phase.value == AppPhase.CLOSED)
    check(childLifecycle.foregroundScope() == null)
    var save: (() -> Map<String, Any>)? = null
    val platform = object : SavedStatePlatform {
        override fun consume(key: String): Map<String, Any>? = null
        override fun register(key: String, provider: () -> Map<String, Any>): AutoCloseable {
            save = provider
            return AutoCloseable { save = null }
        }
    }
    SavedState(platform, SavedStateLimits(maxEntryBytes = 256, maxScopeBytes = 512, maxTotalBytes = 1024)).use { saved ->
        val bytes = byteArrayOf(1, 2)
        check(saved.set("bytes", bytes))
        bytes[0] = 9
        check((saved.get("bytes") as ByteArray)[0] == 1.toByte())
        check(!saved.set("oversized", ByteArray(1024)))
        saved.register("bad") { error("provider failure") }
        saved.register("good") { "ok" }
        val snapshot = checkNotNull(save).invoke()
        check(snapshot["good"] == "ok" && "bad" !in snapshot)
    }
    check(save == null)
    val key = StateKey<Int>("count")
    val host = App(listOf(object : Plugin {
        override fun install(context: AppContext, commands: CommandService) {
            context.state.declare(key, 0)
            commands.register(Add::class, "add", Execution(serial = true)) { context.state.update(key) { it + 1 }; context.state.observe(key).value }
            commands.register(Slow::class, "slow", Execution(timeoutMs = 100)) { delay(1000); 1 }
            commands.registerChannel(Stream::class, "stream") { flowOf(1,2,3) }
        }
    }), capacity = 32)
    host.start()
    try {
        val client = host.commands.client(Capability(setOf("add", "slow", "stream")))
        check((1..20).map { async { client.execute(Add) } }.awaitAll().sorted() == (1..20).toList())
        check(host.context.state.observe(key).value == 20)
        check(client.stream(Stream).toList() == listOf(1,2,3))
        check(runCatching { host.commands.client(Capability(emptySet())).execute(Add) }.exceptionOrNull() is SecurityException)
        check(runCatching { client.execute(Slow) }.exceptionOrNull() is TimeoutCancellationException)
        val event = async(start = CoroutineStart.UNDISPATCHED) { host.context.events.observe(Ping::class).first() }; host.context.events.emit(Ping); check(event.await() == Ping)
        val task = launch { client.execute(Slow) }; delay(10); task.cancelAndJoin(); check(task.isCancelled)
        val nav = UIManager(listOf(UIRoute("home", UILayer.PAGE), UIRoute("detail", UILayer.PAGE), UIRoute("dialog", UILayer.MODAL), UIRoute("overlay", UILayer.OVERLAY)), mapOf("home" to "home"))
        try {
            val overlay = nav.showOverlay("overlay")
            val detail = nav.openPage("detail")
            check(overlay !in nav.state.value.visible && nav.isAlive(overlay))
            val dialog = nav.openModal("dialog")
            check(nav.back()); check(!nav.isAlive(dialog)); check(!nav.close(dialog))
            val result = checkNotNull(nav.result(detail))
            check(nav.close(detail)); check(result.await() == UIResult.Cancelled)
            check(overlay in nav.state.value.visible)
            check(!nav.back())
            check(runCatching { nav.openPage("missing") }.isFailure)
        } finally { nav.close() }
        val lifecycle = host.lifecycle
        lifecycle.transition(AppPhase.FOREGROUND)
        val firstScope = checkNotNull(lifecycle.foregroundScope())
        lifecycle.transition(AppPhase.BACKGROUND)
        check(!firstScope.isActive && lifecycle.foregroundScope() == null)
        lifecycle.transition(AppPhase.FOREGROUND)
        check(lifecycle.foregroundScope() !== firstScope)
        repeat(1000) { lifecycle.transition(AppPhase.BACKGROUND); lifecycle.transition(AppPhase.FOREGROUND) }
        val bounded = UIManager(listOf(UIRoute("home", UILayer.PAGE), UIRoute("detail", UILayer.PAGE)), mapOf("home" to "home"), capacity = 2)
        try {
            bounded.openPage("detail")
            check(runCatching { bounded.openPage("detail") }.isFailure)
            check(bounded.back() && !bounded.back())
        } finally { bounded.close() }
        val logger = Logger(2)
        repeat(3) { logger.info("test.$it") }
        check(logger.snapshot().map { it.code } == listOf("test.1", "test.2"))
        val dir = java.nio.file.Files.createTempDirectory("appbase-crash").toFile()
        try { CrashCapture(dir, logger).capture(IllegalStateException("secret")); check(!java.io.File(dir, "crash.last").readText().contains("secret")) } finally { dir.deleteRecursively() }
        var calls = 0
        host.platform.register("sync") { calls++ }
        val wake = WakeRequest(WakeReason.PUSH, "1", "sync", System.currentTimeMillis() + 60000)
        check(host.platform.dispatch(wake) == WakeResult.HANDLED)
        check(host.platform.dispatch(wake) == WakeResult.DUPLICATE && calls == 1)
        check(host.platform.dispatch(wake.copy(id = "2", expiresAtMs = 0)) == WakeResult.EXPIRED)
        check(host.platform.dispatch(wake.copy(id = "3", route = "unknown")) == WakeResult.UNKNOWN_ROUTE)
        println("PASS: bounded logs, crash report redaction, wake routing and deduplication")
        println("PASS: lifecycle transitions, scope cancellation, bounded stack")
        println("PASS: navigation, modal priority, root protection")
        println("PASS: command, serial concurrency, state, channel, capability, timeout, event, cancellation")
    } finally { host.close() }
}
