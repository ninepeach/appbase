package appbase

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

public enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }
public data class LogRecord(val sequence: Long, val timeMs: Long, val level: LogLevel, val code: String)
/** Codes only: never automatically record payloads, exception messages or credentials. */
public class Logger(private val capacity: Int = 1000, private val minimum: LogLevel = LogLevel.INFO) {
    init { require(capacity in 1..10000) }
    private val records = ArrayDeque<LogRecord>()
    private val sequence = AtomicLong()
    @Synchronized public fun write(level: LogLevel, code: String) {
        require(code.length in 1..128 && code.all { it.isLetterOrDigit() || it in "._-" })
        if (level < minimum) return
        if (records.size == capacity) records.removeFirst()
        records.addLast(LogRecord(sequence.incrementAndGet(), System.currentTimeMillis(), level, code))
    }
    public fun info(code: String): Unit = write(LogLevel.INFO, code)
    @Synchronized public fun snapshot(): List<LogRecord> = Collections.unmodifiableList(records.toList())
}
/** Explicit opt-in, process-wide JVM handler. Best effort only; always delegates. */
public class CrashCapture(private val directory: File, private val logger: Logger) : AutoCloseable {
    private companion object { val ownership = Any(); var owner: CrashCapture? = null }
    private val capturing = AtomicBoolean(false)
    private var installed: Thread.UncaughtExceptionHandler? = null
    private var previous: Thread.UncaughtExceptionHandler? = null
    public fun install(): Unit = synchronized(ownership) {
        check(installed == null && owner == null) { "Crash handler already installed" }
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, error ->
            try { capture(error) } catch (_: Throwable) { /* best effort */ }
            finally { if (prior != null) prior.uncaughtException(thread, error) else Runtime.getRuntime().halt(1) }
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        previous = prior; installed = handler; owner = this
    }
    internal fun capture(error: Throwable) {
        if (!capturing.compareAndSet(false, true)) return
        try {
        check(directory.isDirectory || directory.mkdirs())
        val report = buildString {
            appendLine(error.javaClass.name.take(256))
            error.stackTrace.take(64).forEach { appendLine(it.toString().take(512)) }
            logger.snapshot().takeLast(100).forEach { appendLine("${it.sequence} ${it.level} ${it.code}") }
        }
        val tmp = File.createTempFile("crash-", ".tmp", directory)
        try {
            tmp.writeText(report)
            Files.move(tmp.toPath(), File(directory, "crash.last").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        } finally { capturing.set(false) }
    }
    override fun close() { synchronized(ownership) {
        if (owner !== this) return
        if (Thread.getDefaultUncaughtExceptionHandler() === installed) Thread.setDefaultUncaughtExceptionHandler(previous)
        installed = null; previous = null; owner = null
    } }
}

public data class Trace(val sequence: Long, val command: String, val phase: String)
public class Inspector(private val capacity: Int = 100) {
    init { require(capacity in 1..10000) }
    private val seq = AtomicLong()
    private val entries = kotlinx.coroutines.flow.MutableStateFlow<List<Trace>>(emptyList())
    public val traces: StateFlow<List<Trace>> = entries.asStateFlow()
    @Synchronized internal fun record(id: String, phase: String) { if (id.length !in 1..128 || phase.length !in 1..64 || !id.all { it.isLetterOrDigit() || it in "._-" } || !phase.all { it.isLetterOrDigit() || it in "._-" }) return; entries.value = Collections.unmodifiableList((entries.value + Trace(seq.incrementAndGet(), id, phase)).takeLast(capacity)) }
}
