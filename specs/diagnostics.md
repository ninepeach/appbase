# Diagnostics

## Design principles
Bounded local diagnostic metadata, no automatic payloads, exception messages or credentials. Logger and Inspector independent of UI. Crash capture explicit opt-in, best effort.

## API
Logger(capacity = 1000, minimum = INFO); write(level, code); info(code); snapshot(): List<LogRecord>. Inspector(capacity = 100); traces: StateFlow<List<Trace>>. CrashCapture(directory, logger); install(); close().

Example: app.log.info("app.started"); observe app.inspector.traces in an owner scope.

## Problems and solutions
Logger evicts oldest records, capacity 1–10000, code length 1–128 and restricted characters. Inspector capacity 1–10000, oversized trace metadata omitted. Trace sequence is not request correlation; correlation and redaction of caller-supplied IDs remain pending. Crash report stores exception class, bounded stack frames and last 100 log records, delegates previous handler or halts. No guarantee on OOM, disk failure or process kill. Process-wide handler ownership, private directory, crash file atomic replacement and concurrent install/close need further hardening. No upload.

## Acceptance
Test eviction, filtering, invalid codes, trace bounds, concurrent writes, crash file failure, handler delegation and restoration. Source modified; tests/build not verified.
