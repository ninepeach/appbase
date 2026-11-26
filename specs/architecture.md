# Appbase Architecture Specification

Status: target design specification, not a claim of implementation completeness.

## 1. Purpose and principles

Appbase provides a lightweight, complete, reliable native application runtime and modular SDK. Product developers concentrate on product UI, domain logic and data models. Appbase must not contain product-specific business logic or impose a visual style.

- Minimal core, explicit composition, native-first execution, bounded resources, observable behavior, secure defaults and deterministic ownership.
- Android and iOS each implement a complete independent native Appbase. No shared source code, runtime, kernel, cross-platform adaptation layer or mandatory common binary.
- Design philosophy, subsystem boundaries, public interface names, parameter semantics, data models, lifecycle and error contracts should be consistent wherever practical. Each implementation follows its language and OS conventions. Platform differences must be documented, not hidden by simulated success.
- Each platform independently builds, tests, releases and selects dependencies. Common specifications describe required behavior rather than a mandatory implementation.
- Optional libraries must be independently usable where practical; unused modules must not introduce permissions, services or unnecessary dependencies. No dynamic plugin code loading.

## 2. Architecture and dependency boundaries

Seven subsystems:
1. Microkernel: composition, plugin registry, dependency graph, resource ownership and lifecycle.
2. Communication: typed Command, Event, Channel and State.
3. UI Runtime: UI instances, navigation, presentation, interaction and restoration.
4. Service Runtime: embedded backend, service lifecycle and task execution.
5. Foundation SDK: general-purpose libraries.
6. Platform SDK: native OS capabilities.
7. Diagnostics: logging, tracing, health, crash and performance inspection.

Product UI and services depend on explicit contracts. UI does not own business services or access their private storage directly. Services may call typed interfaces directly; not every function must traverse a bus. Foundation libraries must not depend on UI. Platform modules may use native OS facilities without depending on product UI. Diagnostics observes subsystems through bounded interfaces. Dependency cycles are prohibited.

## 3. Microkernel

App owns application resources. Plugins register explicitly before start. The registry validates unique IDs, missing dependencies and cycles, seals registration at startup, starts in dependency order and stops in reverse order. Startup failure rolls back initialized resources; optional failures produce a documented degraded state. Start/stop must have explicit state, cancellation and idempotency semantics. Resource cleanup must be bounded and observable.

Scopes: process, application, session, service, task, UI instance and request. Ownership is explicit; these scopes are not necessarily a single nested chain. Closing a scope cancels its work and subscriptions without affecting unrelated scopes. No mandatory heavyweight dependency injection or dynamic loading.

Capability is an architectural command allowlist, not a security sandbox. OS permission is a separate boundary. Trusted caller identity must not be inferred from untrusted payload fields.

## 4. Communication

- Command: typed request/result; registered handlers; bounded parallel and serial queues; cancellation; separate queue and execution timeouts; explicit queue-full and handler failure. Middleware may enforce capabilities, trace and redact.
- Event: transient typed broadcast; documented buffer and overflow policy; scope-bound subscriptions; no durability or guaranteed delivery. Critical durable work must use a separate persistent mechanism.
- Channel: dedicated ordered stream with bounded concurrent admission, rendezvous delivery by default, backpressure, completion, error and cancellation.
- State: typed latest-value observable state; new observers receive current value; authoritative owner and atomic updates. Not an event history or durable store by default.

Default transport is in-process typed calls. Optional native IPC, FFI or web bridge must be explicit, versioned and secured. No mandatory HTTP server or serialization. Late results must not update closed UI instances. Cancellation must propagate rather than be swallowed.

## 5. UI Runtime

UIManager owns explicit UI definitions and navigation instances. Route identity is distinct from unique instance identity. Each instance has a route, arguments, owner, parent, layer, scope, state and restoration policy. Instance IDs must remain unambiguous across restoration. Route definitions are registered explicitly; UIHost renders using native UI; UIManager controls logical navigation and presentation, not ordinary widgets.

- Independent page stacks per tab; push, pop, replace, switch tab, deep link, exact close and back.
- Layers: PAGE, OVERLAY, MODAL, TOAST, SYSTEM. Visual order and input ownership are separate. Nonblocking notices never steal focus.
- Modal child stack is separate from independent prompt queue. A child modal covers its parent and restores it on close. Queued unrelated prompts must not interrupt an active parent-child chain. Queue supports bounded capacity, priority, deduplication, expiry and cancellation.
- Back targets the highest eligible interactive instance. A nondismissible modal consumes back rather than passing it through. Closing a parent closes descendants top-down. Stale IDs return false; repeated close is harmless.
- Distinguish alive, visible, interactive and app foreground state. Covered/hidden instances retain state according to policy; actual close releases owned work and drafts. Rendering disposal alone is not logical close.
- Serialize navigation mutations; separate logical close from exit animation; isolate rendering failures and late callbacks.
- Restore serializable navigation, form and scroll state on supported OS recreation. Never serialize callbacks, live resources or coroutines. Sensitive drafts require explicit policy.
- Provide design tokens, theme, dark mode, reusable components, loading/empty/error/offline/retry states, adaptive layouts, safe areas, keyboard/focus, gestures, accessibility, localization/RTL, transitions and reduced motion.

Suggested public operations: openPage, openModal, enqueueModal, showOverlay, toast, close(instanceId), back, switchTab. Modal results and cancellation must be explicit. UI-local state remains native; no mandatory global store for widgets.

## 6. Service Runtime

Embedded backend is a logical separation, not a mandatory separate process. ServiceManager handles service dependencies, start/stop, health and isolation. Business services own domain logic and repositories. TaskManager supports bounded parallel/serial tasks, cancellation, progress, retry/backoff, deadlines, scheduling and task groups.

UI tasks stop with their owner; application services can survive page closure. OS-managed work uses native scheduling and obeys OS restrictions. Persistent tasks require durable descriptors, idempotency, recovery and explicit retry policies; process death cannot restore arbitrary live execution. No guarantee of uninterrupted background execution.

## 7. Foundation SDK

Provide modular typed libraries with explicit errors, cancellation where relevant, resource limits and documented ownership.

| Module | Requirements |
|---|---|
| Network | HTTP/TLS, timeouts, connection pooling, interceptors, bounded retry |
| Socket / WebSocket / DNS | TCP/UDP, connection lifecycle, heartbeat, reconnect, resolution |
| Transfer | Upload/download, progress, cancellation, resumable transfer and integrity checks where supported |
| KV | Typed values, observation, atomic updates and migration |
| Database | Transactions, query, schema migration and concurrency |
| Storage | File/directory access, atomic writes, space checks and cleanup |
| Cache | Memory/disk, TTL, eviction and bounded capacity |
| SecureStorage / Crypto | Native secure key storage, authenticated encryption, hashing, signatures; no custom cryptographic primitives |
| Codec / Compression | Typed serialization, binary formats, bounded decoding and archive extraction |
| Logger | Structured levels, redaction, bounded retention and rotation |
| Config / Time / Validation / ID / Resource | Validated configuration, monotonic/wall clock distinction, validation, unique identifiers and resource access |

Prefer mature native libraries rather than reimplementing established primitives. Foundation modules must be usable without UI and, where practical, without the runtime.

## 8. Platform SDK

Each platform independently implements native capabilities. Interfaces expose availability, state, cancellation and typed failures. Unsupported or restricted operations must be explicit. Optional modules declare only required OS permissions and components.

| Group | Required capability catalog |
|---|---|
| System | Application metadata, device, lifecycle, settings, activity/system results, intents/deep links, clipboard, share, haptics, display, power, locale, file picker, accessibility |
| Permissions | Check/request, native authorization, denial/restriction, settings guidance, revocation and reconciliation |
| Network | Connectivity, Wi-Fi, cellular, network monitoring/policy, VPN, Bluetooth/BLE, NFC |
| Background | Native scheduled work, foreground service where available, alarms, boot events, wake routing |
| Notifications | Local notifications, channels/categories, actions, click routing |
| Push | Provider registration, token rotation, incoming messages, optional topic subscription |
| Commerce | Billing, paid subscriptions, purchase restoration, receipt verification interface, entitlements, review, update |
| Product services | Analytics, attribution, remote configuration and consent interfaces |
| Media/hardware | Camera, scanner, photo, audio, video, location, biometrics, sensors, screen capture, media session |
| Security | Native key store, app lock, screen protection where supported, integrity and data protection |

### 8.1 Permission coordination

Coordinate concurrent and duplicate authorization requests; bind requests to the appropriate native UI host; handle recreation, cancellation, denial, restrictions and settings return. Distinguish Granted, Denied, Restricted, RequiresSettings, Unavailable and Cancelled. Ordinary permissions, special permissions, VPN consent and biometric authentication have distinct native flows. Never bypass OS consent.

### 8.2 VPN

Provide authorization, start/stop, native tunnel lifecycle, profile validation, TUN resource ownership, foreground notification where required, network changes, revocation and traffic statistics interfaces. Protocol engine and routing policy belong to the product. Suggested states: Idle, Authorizing, Starting, Connecting, Connected, Reconnecting, Stopping, Failed, Revoked. Serialize transitions, handle rapid start/stop, close descriptors and reconcile actual OS state after process/service death. Never report connected before the tunnel is established.

### 8.3 Push and Wake

Normalize native push/system events into explicit WakeRouter routes. Validate source and payload, enforce size and expiry, deduplicate, bound queues and expose failures. Durable inbox and retry are optional explicit capabilities. Route to service/task/event or notification UI as appropriate. Push topic subscriptions are distinct from paid subscriptions. No guarantee of OS wake or message delivery.

### 8.4 Billing and subscriptions

Expose product catalog, purchase, pending transactions, restoration, renewal/cancellation, expiration, refunds and grace periods where supported. Entitlement state is separate from transaction state. Provide server verification and synchronization contracts; production commercial entitlements must not rely solely on client booleans. Respect store policies and platform-specific purchase flows.

## 9. Lifecycle and recovery

Observe application foreground/background, interactive/locked state where observable, native host recreation, memory pressure, connectivity and permission changes. Reconcile authoritative state on resume. Session logout clears session-owned resources. Process death requires initialization from durable state; OS callbacks and lock events may be incomplete. Document platform limitations. Do not claim reliable execution after termination.

## 10. Diagnostics and security

Provide bounded structured logs, trace IDs, plugin/service health, command queue and latency metrics, event drop counters, task and UI instance inspection, platform capability state, crash capture, crash-loop detection, main-thread stall/performance diagnostics and redacted diagnostic export. Crash capture is best effort and must preserve native crash handling; recovery occurs on subsequent launch. Debug inspector must be restricted or disabled in production. Never expose credentials, tokens, purchase receipts or sensitive user data by default. Monitor resource use without introducing unbounded retention.

Unified error semantics include Cancelled, Timeout, Unsupported, PermissionDenied, Restricted, Unavailable, NetworkError, StorageError, InvalidArgument, Conflict, QueueFull and InternalError. Include safe error codes, trace IDs and retryability where appropriate. UI maps errors to localized messages. Do not swallow cancellation.

## 11. Public interface design

Use consistent conceptual namespaces and operations across independent implementations: App.start/stop, ui.openPage/openModal/close/back, cmd.execute, event.publish, state.observe, channel.open, platform.permission.request, platform.vpn.start/stop, platform.push.register, platform.billing.purchase and entitlement.has. These are target interface names, not a statement that they exist today. Native async, typing and naming conventions take precedence over literal signature matching. Document intentional differences and API versioning.

## 12. Delivery and acceptance

Develop through complete application examples, not speculative abstractions. Proxy-style UI demo validates navigation, modal ownership, editing, localization and restoration without requiring a real protocol engine. Platform lab validates actual system capabilities separately. A second distinct application validates reuse without product-specific kernel changes.

Acceptance:
- Kernel dependency ordering, rollback, shutdown, scope cleanup and bounded resources.
- Typed communication, timeout, cancellation, overload, backpressure and latest-state behavior.
- UI parent/child modal restoration, queue isolation, stale IDs, rapid back, tab stacks, recreation and accessibility.
- Service ownership, task cancellation, recovery and background restrictions.
- Foundation persistence, migrations, failure handling and security.
- Real native permission, notification, VPN, push and billing flows on supported devices/accounts; mock results must be identified.
- Diagnostics redaction, capacity limits, crash behavior and production restrictions.
- Independent platform builds, automated tests, device tests and documented limitations.
- At least two distinct product scenarios before declaring the architecture generally validated.

Prioritize UI completion, runtime hardening, foundation libraries, core platform capabilities, advanced platform capabilities and production validation. Basic diagnostics and security are required throughout. Each platform releases independently; optional modules have their own readiness criteria. This specification does not certify current implementation or test status.

## Core and optional UI library placement

Core is the native application runtime and includes UIManager, UIHost, SavedState and its native adapter, alongside communication, lifecycle and diagnostics. UIManager and UIHost remain separate classes documented together in [UIManager](ui-manager.md). UIHost depends on UIManager, never the reverse. Core may depend on native SDK and UI frameworks but must not depend on the optional UI library or impose product visual styling. DesignSystem and reusable visual components belong to the optional UI library, which may depend on Core. Foundation libraries remain independently usable. Android Core is targeted to become an Android library with Compose support; this placement decision does not assert that migration or tests have been completed.

## Android Core migration status

UIHost and AndroidSavedState source files now reside in android/core/src/main/kotlin/appbase with package appbase. Core build configuration is Android Library with Compose; all Kotlin sources share src/main/kotlin/appbase; standalone smoke tests explicitly exclude UIHost.kt and AndroidSavedState.kt. UIHost.mount(activity, theme) accepts application-supplied composable theme wrapper (default identity). Earlier target-placement statements are superseded by this source migration. No compatibility aliases. Demo migration remains deferred. Build and native verification are pending; source migration does not establish successful compilation.
