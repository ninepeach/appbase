# Diagnostics

## Design principles
Bounded local diagnostic metadata, no automatic payloads, exception messages or credentials. Logger and Inspector independent of UI. Crash capture explicit opt-in, best effort.

## API
Logger(capacity = 1000, minimum = INFO); write(level, code); info(code); snapshot(): List<LogRecord>. Inspector(capacity = 100); traces: StateFlow<List<Trace>>. CrashCapture(directory, logger); install(); close().

Example: app.log.info("app.started"); observe app.inspector.traces in an owner scope.

## Problems and solutions
Logger evicts oldest records, capacity 1–10000, code length 1–128 and restricted characters. Inspector capacity 1–10000, oversized trace metadata omitted. Trace sequence is not request correlation; correlation and redaction of caller-supplied IDs remain pending. Crash report stores exception class, bounded stack frames and last 100 log records, delegates previous handler or halts. No guarantee on OOM, disk failure or process kill. One Appbase handler owner is enforced process-wide; install/close use a shared monitor and close restores the prior handler only if still current. Concurrent capture is skipped via AtomicBoolean. Unique temporary files are replaced using ATOMIC_MOVE and cleaned up; unsupported atomic moves may lose the new report. Logger and Inspector snapshots are unmodifiable; trace identifiers and phases are character-restricted. Private directory selection, handler-chain races, fsync/durability and fault tests remain pending. No upload.

## Acceptance
Test eviction, filtering, invalid codes, trace bounds, concurrent writes, crash file failure, handler delegation and restoration. Source modified; tests/build not verified.

## Wake ingress
App owns a bounded logger and explicit wake router. Logs are event codes, not arbitrary payloads; callers must not encode secrets in codes. Crash capture is optional and process-wide; it cannot guarantee capture during OOM, native crashes, deadlocks or OS termination. Wake routing is serialized with immediate BUSY on contention; successful IDs are retained until expiry within a bounded capacity. External adapters must authenticate and validate payloads before dispatch. No durable delivery guarantee. Lifecycle events are best effort; consumers reconcile from current phase. UI is not initialized by the core wake router. Handler selection and deduplication run under the router monitor, handler execution outside it while holding the dispatch mutex. Close clears routes and deduplication; in-flight handler is not cancelled and returns CLOSED if close wins before completion. Handler failures propagate; successful IDs are not recorded on failure. Native ingress adapters and lifecycle policies remain future work.
