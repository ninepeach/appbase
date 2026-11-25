package appbase

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }
data class LogRecord(val sequence: Long, val timeMs: Long, val level: LogLevel, val code: String)
/** Codes only: never automatically record payloads, exception messages or credentials. */
class Logger(private val capacity: Int = 1000, private val minimum: LogLevel = LogLevel.INFO) {
    init { require(capacity in 1..10000) }
    private val records = ArrayDeque<LogRecord>()
    private val sequence = AtomicLong()
    @Synchronized fun write(level: LogLevel, code: String) {
        require(code.length in 1..128 && code.all { it.isLetterOrDigit() || it in "._-" })
        if (level < minimum) return
        if (records.size == capacity) records.removeFirst()
        records.addLast(LogRecord(sequence.incrementAndGet(), System.currentTimeMillis(), level, code))
    }
    fun info(code: String) = write(LogLevel.INFO, code)
    @Synchronized fun snapshot(): List<LogRecord> = records.toList()
}
/** Explicit opt-in, process-wide JVM handler. Best effort only; always delegates. */
class CrashCapture(private val directory: File, private val logger: Logger) : AutoCloseable {
    private var installed: Thread.UncaughtExceptionHandler? = null
    private var previous: Thread.UncaughtExceptionHandler? = null
    @Synchronized fun install() {
        check(installed == null)
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, error ->
            try { capture(error) } catch (_: Throwable) { /* best effort */ }
            finally { if (prior != null) prior.uncaughtException(thread, error) else Runtime.getRuntime().halt(1) }
        }
        previous = prior; installed = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }
    @Synchronized internal fun capture(error: Throwable) {
        directory.mkdirs()
        val report = buildString {
            appendLine(error.javaClass.name.take(256))
            error.stackTrace.take(64).forEach { appendLine(it.toString().take(512)) }
            logger.snapshot().takeLast(100).forEach { appendLine("${it.sequence} ${it.level} ${it.code}") }
        }
        val tmp = File(directory, "crash.tmp")
        tmp.writeText(report)
        check(tmp.renameTo(File(directory, "crash.last")))
    }
    @Synchronized override fun close() {
        if (installed != null && Thread.getDefaultUncaughtExceptionHandler() === installed) Thread.setDefaultUncaughtExceptionHandler(previous)
        installed = null; previous = null
    }
}
