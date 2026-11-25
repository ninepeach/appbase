# Lifecycle and UI runtime

Foreground state is authoritative; events are advisory. Inactive and background cancel foreground-only scopes; resuming creates a fresh scope. Screen state is independent. OS suspension and process death cannot be reliably observed. Persist explicitly selected state before termination. Platform binding, screen MVC scopes, event backpressure, modal queue, adaptive layout and command background policies remain pending. Navigation mutations are synchronized and bounded; this is not yet an operation queue.
