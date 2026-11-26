package appbase

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.reflect.KClass

interface Command<R>
interface ChannelCommand<T>
interface AppEvent
class StateKey<T>(val name: String)
class Capability(allowed: Set<String>) {
    private val allowed = allowed.toSet()
    fun check(id: String) { if (id !in allowed) throw SecurityException("Denied: $id") }
}
data class Execution(val timeoutMs: Long = 30_000, val serial: Boolean = false, val queueTimeoutMs: Long = 30_000) { init { require(timeoutMs > 0 && queueTimeoutMs > 0) } }
class QueueFull : IllegalStateException("Command queue full")
class AppContext internal constructor(val events: EventBus, val state: StateStore, val inspector: Inspector, val scope: CoroutineScope)
class CommandService(private val context: AppContext, workers: Int = 4, capacity: Int = 32) {
    private data class Entry(val id: String, val policy: Execution, val run: suspend (Any) -> Any?)
    private data class StreamEntry(val id: String, val run: (Any) -> Flow<*>)
    private data class Work(val entry: Entry, val input: Any, val result: CompletableDeferred<Any?>, val admitted: CompletableDeferred<Unit> = CompletableDeferred())
    private val commands = mutableMapOf<KClass<*>, Entry>()
    private val streams = mutableMapOf<KClass<*>, StreamEntry>()
    private val streamSlots = Semaphore(workers)
    private val ids = mutableSetOf<String>()
    private val parallel = Channel<Work>(capacity, onUndeliveredElement = { it.result.cancel() })
    private val serial = Channel<Work>(capacity, onUndeliveredElement = { it.result.cancel() })
    private val scope = CoroutineScope(context.scope.coroutineContext + SupervisorJob(context.scope.coroutineContext[Job]) + Dispatchers.Default)
    @Volatile private var started = false
    @Volatile private var closed = false
    init { require(workers > 0 && capacity > 0) }
    private val workerCount = workers
    private fun validId(id: String) = id.length in 1..128 && id.all { it.isLetterOrDigit() || it in "._-" }
    @Synchronized fun <C : Command<R>, R> register(type: KClass<C>, id: String, policy: Execution = Execution(), handler: suspend (C) -> R) {
        check(!started && !closed); require(validId(id) && type !in commands && ids.add(id)); commands[type] = Entry(id, policy) { @Suppress("UNCHECKED_CAST") handler(it as C) }
    }
    @Synchronized fun <C : ChannelCommand<T>, T> registerChannel(type: KClass<C>, id: String, handler: (C) -> Flow<T>) {
        check(!started && !closed); require(validId(id) && type !in streams && ids.add(id)); streams[type] = StreamEntry(id) { @Suppress("UNCHECKED_CAST") handler(it as C) }
    }
    @Synchronized internal fun start() {
        check(!closed && !started)
        repeat(workerCount) { scope.launch { consume(parallel) } }
        scope.launch { consume(serial) }
        started = true
    }
    fun client(capability: Capability) = CommandClient(this, capability)
    private suspend fun consume(queue: Channel<Work>) { for (work in queue) {
        if (!work.result.isActive) continue
        work.admitted.complete(Unit)
        supervisorScope {
            val task = async(start = CoroutineStart.LAZY) { context.inspector.record(work.entry.id, "running"); withTimeout(work.entry.policy.timeoutMs) { work.entry.run(work.input) } }
            val subscription = work.result.invokeOnCompletion { if (work.result.isCancelled) task.cancel() }
            try { work.result.complete(task.await()); context.inspector.record(work.entry.id, "completed") }
            catch (e: TimeoutCancellationException) { work.result.completeExceptionally(e); context.inspector.record(work.entry.id, "timeout") }
            catch (e: CancellationException) { work.result.cancel(e); context.inspector.record(work.entry.id, "cancelled") }
            catch (e: Throwable) { work.result.completeExceptionally(e); context.inspector.record(work.entry.id, "failed"); if (e is Error) throw e }
            finally { subscription.dispose(); task.cancel() }
        }
    } }
    internal suspend fun <R> execute(command: Command<R>, capability: Capability): R {
        check(started && !closed); val entry = commands[command::class] ?: error("Unregistered command"); capability.check(entry.id)
        val result = CompletableDeferred<Any?>(scope.coroutineContext[Job]); val queue = if (entry.policy.serial) serial else parallel
        context.inspector.record(entry.id, "queued")
        val work = Work(entry, command, result)
        if (!queue.trySend(work).isSuccess) { result.cancel(); context.inspector.record(entry.id, "rejected"); throw QueueFull() }
        try { val admissionGuard = result.invokeOnCompletion { if (it != null) work.admitted.completeExceptionally(it) }; try { withTimeout(entry.policy.queueTimeoutMs) { work.admitted.await() } } finally { admissionGuard.dispose() }; @Suppress("UNCHECKED_CAST") return result.await() as R } finally { if (!result.isCompleted) result.cancel() }
    }
    internal fun <T> stream(command: ChannelCommand<T>, capability: Capability): Flow<T> = flow {
        check(started && !closed); val entry = streams[command::class] ?: error("Unregistered channel"); capability.check(entry.id)
        coroutineScope {
            val collector = currentCoroutineContext()[Job]!!
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            check(scope.isActive) { "Channel service closed" }
            val guard = scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val completion = guard.invokeOnCompletion { if (!finished.get()) collector.cancel() }
            try { if (!streamSlots.tryAcquire()) throw QueueFull(); try { context.inspector.record(entry.id, "stream.open"); @Suppress("UNCHECKED_CAST") emitAll((flow { emitAll(entry.run(command) as Flow<T>) }).flowOn(Dispatchers.Default).buffer(0)) } finally { streamSlots.release(); context.inspector.record(entry.id, "stream.close") } } finally { finished.set(true); completion.dispose(); guard.cancel() }
        }
    }
    internal fun close() {
        synchronized(this) { if (closed) return; closed = true }
        scope.cancel()
        parallel.cancel()
        serial.cancel()
    }
}
class CommandClient internal constructor(private val service: CommandService, private val capability: Capability) {
    suspend fun <R> execute(command: Command<R>): R = service.execute(command, capability)
    fun <T> stream(command: ChannelCommand<T>): Flow<T> = service.stream(command, capability)
}
interface Plugin { fun install(context: AppContext, commands: CommandService); fun close() {} }
class App(plugins: List<Plugin> = emptyList(), workers: Int = 4, capacity: Int = 32) : AutoCloseable {
    private val plugins = plugins.toList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val context = AppContext(EventBus(), StateStore(), Inspector(), scope)
    val log = Logger()
    val event get() = context.events
    val state get() = context.state
    val inspector get() = context.inspector
    val lifecycle = AppLifecycle(scope, event)
    val platform = WakeRouter(event)
    val commands = CommandService(context, workers, capacity)
    val cmd get() = commands
    private val installed = mutableListOf<Plugin>()
    private enum class Status { NEW, STARTING, RUNNING, CLOSING, CLOSED }
    private var status = Status.NEW
    // App startup and shutdown are owner-thread operations; reentrant close during
    // installation is rejected so partial installation cannot escape rollback.
    @Synchronized fun start() {
        check(status == Status.NEW)
        status = Status.STARTING
        try {
            plugins.forEach { installed.add(it); it.install(context, commands) }
            commands.start()
            status = Status.RUNNING
        } catch (e: Throwable) {
            shutdown()
            throw e
        }
    }
    @Synchronized override fun close() {
        check(status != Status.STARTING) { "Cannot close during plugin installation" }
        shutdown()
    }
    private fun shutdown() {
        if (status == Status.CLOSING || status == Status.CLOSED) return
        status = Status.CLOSING
        try {
            platform.close()
            lifecycle.close()
            commands.close()
            scope.cancel()
        } finally {
            installed.asReversed().forEach { runCatching { it.close() }.onFailure { log.write(LogLevel.ERROR, "plugin.close.failed") } }
            installed.clear()
            status = Status.CLOSED
        }
    }
}
