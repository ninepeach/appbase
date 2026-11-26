# Appbase 代码审查与重新设计（v2）

状态：已评审的目标设计，尚未实现。本文件是重构的唯一依据，在线讨论稿以本文件为准。实现进度记录在第 10 节。

## 0. 定位

**麻雀虽小，五脏俱全；接口简单，内部扎实；充分利用原生平台。**

- 业务开发者面对的是少量、强类型、语义明确的入口（`App`、`UIManager`、各业务 Service）。
- 内部实现拆分清晰、资源有上限、取消和失败严格区分，所有边界行为都有测试。
- 平台已经提供的能力（进程生命周期、Navigation 3、`rememberSaveable`/ViewModel、WorkManager）直接使用，Appbase 只补平台没有的部分。

## 1. 结论

现有代码是一个约 1.2k 行的内核加 UI 运行时原型。单个组件写得严谨，但整体结构有三个主要问题：

1. **模块之间只能通过全局的无类型通道协作。** Command Registry、EventBus、StateStore 依赖字符串 id 和全局表，编译器无法检查；Capability 谁都能自己构造，没有实际约束力。
2. **线程模型不统一。** UIManager 用锁加 `Dispatchers.Default`，SavedState 强制主线程，规格写的是“主线程”，代码并没有这样做。
3. **模块边界混在一起。** 纯逻辑内核与 Compose、Nav3、Activity 放在同一个 Android library 里，并以 `api` 暴露给所有使用方。

重新设计的方向：**模块之间用强类型 Service 协作；UI 运行时限定在主线程，对外只有 `UIManager` 一个入口；平台能做的交给平台。** 保留现有设计中真正有价值的部分：有上限的执行队列和两种超时、弹层所有权与级联关闭、提示队列、运行时拒绝不抛异常。

## 2. 问题清单

“高”表示会导致崩溃、功能错误或给人错误的安全感；“中”表示设计上会拖累后续开发；“低”表示工程化问题。

| 严重度 | 位置 | 问题 | 后果 |
| --- | --- | --- | --- |
| 高 | `Lifecycle.kt` + appbaseTest | 前后台靠 Activity 的 `onStart`/`onStop` 手动调用 `transition()` | 旋转屏幕或切换 Activity 都会进入 BACKGROUND，`foregroundScope` 里的工作被取消 |
| 高 | `Core.kt` `execute` | 执行超时时把 `TimeoutCancellationException` 原样抛给调用方 | 它是 `CancellationException` 的子类：在 `launch` 里协程会静默结束，调用之后的代码不执行，也不作为未捕获异常上报。**执行超时与调用方取消无法区分** |
| 高 | `Core.kt` `consume` | handler 抛出 `Error` 时重新抛出，终止该 worker | worker 数量悄悄减少，最终队列不再被消费 |
| 高 | `Core.kt` `Capability` | `commands.client(Capability(...))` 任何代码都能调用 | 权限检查形同虚设，还给人“有边界”的错觉 |
| 高 | `WakeRouter.kt` | 用 `Mutex.tryLock` 串行化，并发到达时直接返回 BUSY；handler 在锁内执行 | 两条推送同时到达会丢掉一条；一个慢 handler 让所有唤醒返回 BUSY |
| 高 | UIManager 保存恢复 | 导航快照不保存参数，恢复时用 `emptyMap()` 重建 | 进程被杀后回来，详情页丢失参数 |
| 中 | 整体 | 线程模型不统一（见第 1 节） | 规则只靠文档约束，组合使用时容易在错误的线程调用 |
| 中 | `core` 模块 | 内核与 UIHost、AndroidSavedState 同在一个 Android library，以 `api` 暴露 Compose、Activity、Nav3 | 内核不能在纯 JVM 上测试；所有使用方被迫依赖 Compose |
| 中 | `Core.kt` `App` / `Plugin` | `App` 写死 7 个子系统；`Plugin` 只有同步 `install`，没有 id、依赖声明和异步初始化 | 依赖排序和循环检测没有实现；插件初始化无法做 I/O |
| 中 | `State.kt` | StateStore 是全局可变容器，谁都能 `set` | 状态归属不清 |
| 中 | `Event.kt` | 所有事件共用一个缓冲区，溢出时挂起 | 一个慢订阅者会卡住所有发布方，也没有丢弃计数 |
| 中 | `UIManager.kt` | 412 行单个类；路由是字符串，参数和结果是 `Map<String, String>` | 难以维护；模态框结果没有类型 |
| 中 | `UIManager.kt` | `presentations()` 沿父链逐个查找，每次修改都复制全部实例 | O(n²) 的重算，压在主线程上 |
| 中 | `UIManager.kt` | `notify`（Snackbar）与 `toast`（TOAST 层）两套通知并存 | 使用方不知道该用哪个 |
| 中 | `UIHost.kt` | renderer 是 `Map<String, Composable>`，用 `getValue` 取；前后台状态在 Composable 里同步 | 漏注册的页面要到打开时才崩溃；UI 和生命周期耦合 |
| 中 | `Diagnostics.kt` | Logger 只能记录 code，不能带字段；没有 Logcat 输出；Inspector 每次记录都复制整个列表 | 连 HTTP 状态码都记不了；命令热路径上有多余的内存分配 |
| 低 | `ui` 模块 | `ScreenHost` 的转场与 Nav3 重复；组件里写死了 "Retry" 和 "›" | 已过时，无法本地化 |
| 低 | 构建 | 没有 Gradle wrapper 和 version catalog；测试是 kotlinc 加 curl 跑的 `main()`；未开启 explicit API mode；包名只有一级 `appbase` | 版本不可复现，测试不能在 IDE 里跑，公开 API 边界不受控 |
| 低 | 协作流程 | 多个 agent 同时直推 main，历史上有过 force push | 改动互相覆盖；没编译过的代码直接进 main |

另外，规格的体量与代码不成比例：`architecture.md` 规划了 VPN、计费、推送等 10 组平台能力，而网络、KV、数据库、后台任务这些基础能力一个都还没有。

## 3. 设计原则

1. **模块之间用强类型 Service 协作。** 插件提供接口（如 `OrderService`），依赖它的插件声明依赖后直接调用。状态由拥有它的 Service 以 `StateFlow` 暴露。一对多的通知用 Event。
2. **取消、失败、编程错误三者分开。**
   - 外部协程取消以 `CancellationException` 继续传播，绝不吞掉；
   - 可预期的业务失败（拒绝、超时、权限不足、远程错误）用 `Outcome.Failure` 表达；
   - 编程错误（未注册的路由、重复 id、配置错误）直接抛异常，尽早暴露。
3. **UI 运行时限定在主线程。** `UIManager` 及其内部组件不加锁，所有修改都在 `Dispatchers.Main.immediate` 上进行；内核和 Service 层保证线程安全。
4. **平台能做的交给平台。** 前后台状态用 `ProcessLifecycleOwner`，页面渲染用 Nav3，页面状态用 `rememberSaveable` 和 ViewModel，需要持久化的后台任务用 WorkManager。
5. **权限通过“能拿到哪些接口”来控制，而不是字符串权限表。** 只读和写入拆成不同接口，由组装方决定给谁。这只是同一进程内的架构约束，**不是安全隔离**：真正的隔离要靠操作系统权限、进程隔离和服务端授权。
6. **所有资源都有上限，超限行为必须明确、可观察，并符合该资源的业务语义。**
   - 拒绝必须让调用方知道（`Failure`、null 或 false），不能只写一条日志；
   - 丢弃或淘汰必须有计数，被淘汰的一方要收到通知（例如 `Handle.await()` 返回取消）；
   - 溢出策略是资源契约的一部分，写进 API 文档；可配置的策略在构造时显式选择。

   | 资源 | 容量满时的策略 |
   | --- | --- |
   | Logger | 丢弃最旧记录，增加计数 |
   | Inspector | 丢弃最旧记录 |
   | Toast / Notice | 淘汰最旧的一条 |
   | 非关键 Event | 构造时选择：丢弃最旧或丢弃最新，带计数 |
   | SerialExecutor / BoundedExecutor | 明确拒绝新任务（`Failure(Busy)`），绝不丢弃已接受的任务 |
   | Flow / Channel 流式数据 | 背压 |
   | 提示队列（enqueueModal） | 明确拒绝；或按优先级淘汰更低优先级的提示，并通知被淘汰者 |
   | 导航栈 | 拒绝新页面（`openPage` 返回 null） |
   | 唤醒队列 | 明确拒绝、按 key 合并，或先持久化再处理 |
   | SavedState | 拒绝新写入，保留原值 |

7. **先有真实 App，再有抽象。** 只实现 TapOrder 这类真实 App 当下用得上的能力。
8. **插件机制保持简单。** 显式列表、依赖排序、异步启动、逆序关闭，仅此而已：不扫描、不用反射或注解、不动态加载、不引入 DI 容器。

## 4. 模块结构与依赖

拆成 4 个模块，依赖单向向下。内核是纯 Kotlin/JVM 模块，不引用任何 `android.*` 类，可以用普通 JUnit 在几秒内跑完测试。

```
appbase-design   (可选) 设计 token、通用组件
      │
appbase-ui       Android + Compose：UIManager（内部为 Navigator + Presenter）、UIHost、SavedState、NavSnapshot
      │
appbase-android  Android 绑定：AndroidApp、进程生命周期、SavedState 平台适配、Logcat、崩溃捕获、唤醒入口
      │
appbase-kernel   纯 Kotlin/JVM：App、Plugin、服务注册表、Outcome/AppError、SerialExecutor、BoundedExecutor、EventBus、Logger、WakeRouter
```

| 模块 | 类型 | 对外依赖 | 从现有代码迁入 |
| --- | --- | --- | --- |
| `appbase-kernel` | Kotlin/JVM | kotlinx-coroutines-core | `Core.kt`（执行器部分）、`Event.kt`、`Lifecycle.kt`（仅状态机）、`Diagnostics.kt` 的 Logger 和 Inspector、`WakeRouter.kt` |
| `appbase-android` | Android library | kernel、androidx.lifecycle-process、androidx.savedstate | `AndroidSavedState.kt`、`CrashCapture`、新增 `AndroidApp` 和 Logcat sink |
| `appbase-ui` | Android library + Compose | kernel、android、navigation3、lifecycle-viewmodel-navigation3、kotlinx-serialization | `UIManager.kt`（内部拆分）、`UIHost.kt`、`SavedState.kt` |
| `appbase-design` | Android library + Compose | ui、material3 | 现有 `ui` 模块的 `DesignSystem.kt` |

约束：

- 每个模块开启 Kotlin explicit API mode；包名改为至少两级（暂定 `net.ninepeach.appbase.*`，见第 11 节）。
- 只有出现在公开 API 里的类型才用 `api` 依赖，其余一律 `implementation`。
- 只有 `appbase-ui` 依赖 Compose 和 Nav3。只用 Service 和后台能力的代码（如同步 Worker）只依赖 kernel 和 android。
- 未来的基础能力（网络、KV、数据库、同步）各自是独立的插件模块，只依赖 kernel，彼此通过 Service 接口协作。

## 5. 核心 API

### 5.1 通信方式选择

| 场景 | 推荐方式 |
| --- | --- |
| 模块之间调用 | 强类型 Service 接口 |
| 业务状态 | Service 自己持有的 `StateFlow` |
| 一对多通知 | `EventBus`（基于 SharedFlow） |
| 串行任务 | `SerialExecutor` |
| 限制并发的任务 | `BoundedExecutor` |
| 流式数据 | Service 方法返回 `Flow`；需要时内部用 `Channel` |
| 持久化后台任务 | WorkManager（由 `appbase-android` 提供入口） |
| 外部消息入口（推送、深链接、通知点击） | `WakeRouter` |

不再有全局的 Command Registry、`ChannelCommand` 和 StateStore。

### 5.2 App、Plugin 和服务注册表

```kotlin
class ServiceKey<T : Any>(val name: String)   // 身份标识，不按名字查找

interface Plugin {
    val id: String
    val dependsOn: Set<String> get() = emptySet()
    suspend fun start(ctx: PluginContext)
    suspend fun stop() {}
}

interface PluginContext {
    val scope: CoroutineScope              // 插件停止时取消
    val log: Logger                        // 已带上 plugin id
    val events: EventBus
    fun <T : Any> provide(key: ServiceKey<T>, service: T)
    fun <T : Any> require(key: ServiceKey<T>): T   // 只能取到组装方授予本插件的接口
}

class App(plugins: List<Plugin>, grants: Grants = Grants.dependencies()) {
    val state: StateFlow<AppState>         // NEW / STARTING / RUNNING / STOPPING / STOPPED / FAILED
    suspend fun start(): Outcome<Unit>
    suspend fun stop()
    fun <T : Any> service(key: ServiceKey<T>): T?
}
```

插件机制的全部内容：

1. 校验插件 id 唯一、依赖存在、没有循环（配置错误，直接抛异常）；
2. 按依赖拓扑排序，依次调用 `start`；
3. 任一插件启动失败，按相反顺序 `stop` 已启动的插件，`start()` 返回 `Failure`；
4. `stop()` 按启动的相反顺序执行，每个插件的 `scope` 随之取消。

不扫描、不反射、不动态加载，没有 DI 容器。预计 150 行以内。

**接口授予。** `Grants` 描述“哪个插件可以 `require` 哪些 `ServiceKey`”。默认规则是：插件只能拿到它在 `dependsOn` 里声明的插件所提供的接口。组装方可以进一步收窄，例如只把 `OrderWriter` 授予下单插件：

```kotlin
interface OrderReader {
    val orders: StateFlow<List<Order>>
    fun find(id: OrderId): Order?
}
interface OrderWriter {
    suspend fun submit(order: Order): Outcome<Order>
}

val app = App(
    plugins = listOf(NetworkPlugin(), DatabasePlugin(), OrderPlugin(), KioskPlugin(), StaffPlugin()),
    grants = Grants.dependencies()
        .deny(OrderWriterKey, to = "kiosk")      // Kiosk 只能读
)
```

`require` 一个没有被授予的接口属于配置错误，启动立即失败。

### 5.3 错误模型

```kotlin
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

sealed interface AppError {
    val code: String
    data class Rejected(override val code: String) : AppError      // 正常的业务拒绝
    data class Denied(override val code: String) : AppError        // 权限不足
    data class Timeout(override val code: String) : AppError       // 执行超时（与调用方取消区分）
    data class Busy(override val code: String) : AppError          // 容量已满，可稍后重试
    data class Unavailable(override val code: String) : AppError   // 依赖的插件未运行
    data class Network(override val code: String, val retryable: Boolean) : AppError
    data class Remote(override val code: String, val status: Int) : AppError
    data class Internal(override val code: String, val cause: Throwable) : AppError
}
```

何时用 `Outcome`：

- **可能出现可预期失败的操作**（I/O、远程调用、权限、配额、超时）返回 `Outcome<T>`；
- **纯内存的查询和状态读取**直接返回数据或 `StateFlow`；
- **框架边界**（执行器、WakeRouter）一律返回 `Outcome` 或明确的结果枚举，因为它们本身就可能拒绝。

```kotlin
interface OrderService {
    val orders: StateFlow<List<Order>>
    suspend fun submit(order: Order): Outcome<Order>
    fun find(id: OrderId): Order?
}
```

规则：

- 外部协程取消以 `CancellationException` 继续传播；
- 框架内部的执行超时转换为 `Failure(AppError.Timeout)`，不以 `CancellationException` 的形式泄漏给调用方；
- 编程错误直接抛异常；
- 内部实现不要用 `runCatching` 或标准库的 `Result` 包装 suspend 调用：`runCatching` 会把 `CancellationException` 也吞掉；
- `Error`（如 OOM）不捕获。

### 5.4 SerialExecutor 与 BoundedExecutor

从现有 `CommandService` 中提取：有上限的队列、排队超时与执行超时分开计算、取消向上传播、明确拒绝。

```kotlin
class BoundedExecutor(
    scope: CoroutineScope,
    parallelism: Int,
    queueCapacity: Int,
    name: String,
) {
    suspend fun <T> submit(
        queueTimeout: Duration = 30.seconds,
        executionTimeout: Duration = 30.seconds,
        block: suspend () -> T,
    ): Outcome<T>
}

class SerialExecutor(scope: CoroutineScope, queueCapacity: Int, name: String)   // 等价于 parallelism = 1
```

- 队列满时立即返回 `Failure(Busy)`，绝不丢弃已接受的任务；
- 排队超时与执行超时都返回 `Failure(Timeout)`，`code` 区分是哪一种；
- 调用方取消时，任务也随之取消，异常照常传播；
- `block` 抛出 `Error` 时，该 worker 结束后会被重新拉起，`parallelism` 不会减少；
- 统计排队数、运行数、拒绝数，供 Inspector 读取。

### 5.5 EventBus

```kotlin
enum class EventOverflow { DROP_OLDEST, DROP_LATEST }

class EventBus(capacity: Int = 64, overflow: EventOverflow = EventOverflow.DROP_OLDEST) {
    fun publish(event: AppEvent): Boolean      // 不挂起；被丢弃时返回 false 并计数
    fun <T : AppEvent> observe(type: KClass<T>): Flow<T>
    val dropped: StateFlow<Long>
}
```

EventBus 只用于非关键通知。需要当前值的场景用 Service 的 `StateFlow`；必须送达的场景用执行器或持久化队列。

### 5.6 生命周期

kernel 的 `AppLifecycle` 只保留状态机（`phase` 和 `foregroundScope`）。由 `appbase-android` 的 `AndroidApp` 订阅 `ProcessLifecycleOwner` 来驱动它；Activity 不再手动调用，旋转屏幕和切换 Activity 都不会触发 BACKGROUND。

```kotlin
class AndroidApp(application: Application, plugins: List<Plugin>, grants: Grants = Grants.dependencies()) {
    val app: App
    // 内部：ProcessLifecycleOwner -> AppLifecycle、Logcat sink、可选 CrashCapture
}
```

## 6. UI 运行时：UIManager

**`UIManager` 是业务开发者唯一的 UI 入口。** 内部拆成 `Navigator`（页面）和 `Presenter`（弹层）两个 `internal` 类，各自职责单一、可以单独测试。UIManager 只做组合和跨边界规则：返回键优先级、有阻塞弹层时禁止跳转、页面关闭时级联关闭它的弹层。它必须保持薄，具体逻辑一律放在两个内部类里。

### 6.1 公开 API

```kotlin
@MainThread
class UIManager(
    tabs: Map<String, NavKey>,
    serializers: SerializersModule,
    lifecycle: AppLifecycle,
    limits: UILimits = UILimits(),
) {
    val state: StateFlow<UIState>          // 页面与弹层合成的一份快照，UIHost 只读这一份

    // 页面
    fun openPage(key: NavKey): PageId?     // 被阻塞或导航栈已满时返回 null
    fun replacePage(key: NavKey): PageId?
    fun selectRoot(tab: String): Boolean
    fun back(): Boolean                    // 先弹层（SYSTEM、MODAL、阻塞 OVERLAY），再页面

    // 弹层
    fun <R : Any> openModal(p: Presentation<R>): Handle<R>?
    fun <R : Any> enqueueModal(p: Presentation<R>, key: String, priority: Int = 0,
                               ttl: Duration = 60.seconds): Handle<R>?
    fun showOverlay(p: Presentation<Unit>): Handle<Unit>?
    fun showSystem(p: Presentation<Unit>): Handle<Unit>?
    fun notify(notice: Notice, duration: Duration = 4.seconds)   // 替代原来的 toast 和 notify

    // 通用
    fun close(id: InstanceId): Boolean
    fun scope(id: InstanceId): CoroutineScope?

    // 后台线程入口
    suspend fun <T> dispatch(block: UIManager.() -> T): T  // 切到 Dispatchers.Main.immediate 执行

    // 保存与恢复
    fun save(): ByteArray?                 // 带版本号的 NavSnapshot，超限返回 null
    fun restore(bytes: ByteArray): Boolean // 校验失败就回到根页面
}

interface Presentation<R : Any> { val layer: Layer; val dismissible: Boolean }
enum class Layer { OVERLAY, MODAL, SYSTEM }

class Handle<R : Any> internal constructor(/* ... */) {
    val id: InstanceId
    suspend fun await(): R?                // null 表示取消、被级联关闭或被淘汰
    fun complete(result: R)
}
```

使用方式：

```kotlin
@Serializable data class ServerDetail(val serverId: String) : NavKey
object ConfirmDelete : Presentation<Boolean> { override val layer = Layer.MODAL; override val dismissible = true }

ui.openPage(ServerDetail("tokyo-01"))
val ok = ui.openModal(ConfirmDelete)?.await() == true
```

### 6.2 内部：Navigator

- 每个 Tab 一个 `List<PageEntry>`，`PageEntry(id: PageId, key: NavKey)`；`key` 是业务的 `@Serializable` 路由，不含框架字段。
- `NavDisplay` 直接渲染这份列表，`contentKey = id`，同一路由打开两次也互不干扰。
- `NavSnapshot(version, selected, tabs)` 用 kotlinx.serialization 序列化，**参数随路由一起保存和恢复**；有字节和数量上限，校验失败回到根页面。
- 页面关闭时取消它的 `scope`，并通知 Presenter 做级联关闭。

### 6.3 内部：Presenter

保留现有设计中的语义：

- 模态框的父子栈与提示队列分开；
- 只有当前 Tab 顶层页面名下的弹层可见；页面被覆盖或切换 Tab 时，弹层隐藏但不销毁；
- 页面关闭时级联关闭它的弹层，并丢弃它名下排队的提示；
- 不可关闭的弹层会吞掉返回事件；
- 提示队列按 key 去重，按优先级排序（同优先级先进先出），TTL 过期；满时按第 3 节的规则拒绝或淘汰更低优先级的提示，被淘汰的 `Handle.await()` 返回 null；
- `notify` 最多保留 N 条，淘汰最旧的一条。

### 6.4 返回键和渲染

- `NavDisplay.onBack` 和弹层的返回处理都只调用 `UIManager.back()`。
- 有阻塞弹层时，用优先级更高的返回处理拦截 predictive back，底下的页面不会播放预览动画；手势取消时不改变任何状态。
- UIHost 只渲染 `UIManager.state`，页面内容通过 Nav3 的 `entryProvider { entry<ServerDetail> { ... } }` 注册。漏注册的页面在启动时检查出来。
- 前后台状态由 UIManager 自己订阅 `AppLifecycle.phase`，UIHost 不参与。

### 6.5 线程

UIManager 及其内部组件标注 `@MainThread`，内部不加锁；debug 包在每个公开方法入口做主线程断言。后台代码通过 `ui.dispatch { openPage(...) }` 切到主线程。toast 和提示的定时器运行在主线程作用域里。

## 7. 诊断与唤醒

### 7.1 Logger

保留“不自动记录 payload”的原则，但允许调用方显式带上字段，并按字段名脱敏。

```kotlin
interface Logger {
    fun log(level: LogLevel, code: String, vararg fields: Pair<String, Any?>)
    fun info(code: String, vararg fields: Pair<String, Any?>)
    fun child(tag: String): Logger
}
interface LogSink { fun write(record: LogRecord) }   // RingBufferSink（内置）、LogcatSink（android）

log.info("http.done", "status" to 200, "ms" to 132)
log.info("auth.ok", "token" to token)               // 输出为 token=***
```

- 默认脱敏字段名：`token`、`password`、`secret`、`authorization`、`cookie`，可配置；
- 字段值超过 256 字符截断，每条最多 16 个字段；
- 环形缓冲区满时丢弃最旧记录，并计数；
- release 包默认不向 Logcat 输出 DEBUG。

### 7.2 Inspector

固定大小的环形缓冲区，只在查看时生成快照；每个任务只记录最终状态（耗时、结果）。满时丢弃最旧记录。release 包默认关闭。

### 7.3 WakeRouter

```kotlin
class WakeRouter(scope: CoroutineScope, capacity: Int = 64, dedupe: Int = 256) {
    fun register(route: String, policy: WakePolicy, handler: suspend (WakeRequest) -> Outcome<Unit>)
    fun submit(request: WakeRequest): WakeAdmission   // ACCEPTED / MERGED / DUPLICATE / EXPIRED / UNKNOWN_ROUTE / INVALID / REJECTED
    val results: Flow<WakeResult>
}

enum class WakePolicy { REJECT_WHEN_FULL, MERGE_BY_KEY, PERSIST_FIRST }
```

- `submit` 不挂起：只做校验和去重，然后放入该 route 的有上限队列；
- 每个 route 一个消费者，route 内串行、route 之间并行，慢 handler 不会卡住其他 route；
- 满时的行为由 route 注册时选择的策略决定：
  - `REJECT_WHEN_FULL`：返回 REJECTED；
  - `MERGE_BY_KEY`：与队列里同 key 的请求合并（适合“刷新订单列表”这类幂等请求）；
  - `PERSIST_FIRST`：先写入 `appbase-android` 提供的持久化队列（WorkManager），再处理（适合必须送达的订单推送）；
- 去重表按过期时间淘汰。

## 8. 删除与保留清单

| 现有代码 | 处理 | 理由 |
| --- | --- | --- |
| `StateStore` / `StateKey` | 删除 | 状态由 Service 以 `StateFlow` 暴露 |
| 全局 Command Registry、`CommandClient`、`ChannelCommand` | 删除 | 模块之间改用 Service；流式数据用 `Flow` |
| `CommandService` 的队列、串行/并行、两种超时、取消传播 | 保留，提取为 `SerialExecutor` / `BoundedExecutor` | 执行能力本身有价值；同时修复超时泄漏和 worker 减少 |
| `Capability` | 删除 | 改为通过授予的接口控制；安全隔离交给系统和服务端 |
| `EventBus.emit`（挂起版） | 删除 | 改为不挂起的 `publish`，溢出策略在构造时选择，带计数 |
| `AppLifecycle` 状态机 | 保留 | 改由 `ProcessLifecycleOwner` 驱动 |
| `UIManager` 公开入口 | 保留 | 唯一的 UI 入口；内部拆成 Navigator 和 Presenter |
| 模态框父子栈、提示队列、级联关闭、`scope(id)`、运行时拒绝返回 null | 保留，移入 Presenter | 官方库没有、也是最有价值的部分 |
| 扁平 Map 格式的导航保存恢复 | 删除 | 改为带参数的 `NavSnapshot` |
| `notify` + Snackbar 与 TOAST 层 | 合并为 `UIManager.notify` | 只保留一套通知机制 |
| `UIHost` 的 `Map<String, Composable>` renderer | 改为 Nav3 `entryProvider` | 漏写的页面在启动时发现 |
| `SavedState` typed slot | 保留，移入 `appbase-ui` | 刚重写过，设计合理 |
| `Logger`（只有 code） | 扩展为带字段和 sink | 现在连状态码都记不了 |
| `Inspector` | 改为环形缓冲区 | 去掉热路径上的复制 |
| `CrashCapture` | 保留，移入 `appbase-android` | 实现可用 |
| `WakeRouter` | 重写为按 route 的队列模型 | 并发时不再丢失唤醒 |
| `ui` 模块的 `ScreenHost` | 删除 | 与 Nav3 转场重复 |
| `DesignSystem.kt` | 保留，移入 `appbase-design` | 去掉写死的文字 |
| `tests/Smoke.kt` + `run.sh` | 删除 | 改为各模块的 `src/test`（JUnit、kotlinx-coroutines-test、Turbine） |
| `architecture.md` 的 VPN、计费、推送、媒体等平台能力目录 | 移到“未来规划” | 在有具体 App 需求前不纳入设计范围 |

## 9. 迁移计划与验收

分 6 个阶段，每个阶段一个分支、一个 PR。本地 `./gradlew build` 和测试通过、CI 变绿后才能合并。需要在装有 Android SDK 的机器上进行。

| 阶段 | 内容 | 验收标准 |
| --- | --- | --- |
| 0. 工程化 | Gradle wrapper、version catalog、ktlint 和 detekt、explicit API mode；CI 跑 `./gradlew build`；main 开启分支保护 | CI 绿；未经 PR 无法推送到 main |
| 1. 拆模块 | 建立 kernel / android / ui / design 四个模块并迁入现有代码，修改包名；`tests/Smoke.kt` 拆成各模块的 JUnit 测试 | `./gradlew :appbase-kernel:test` 不需要 Android SDK；行为不变 |
| 2. 内核 | Plugin（id、依赖、异步 start/stop、回滚）；服务注册表与 Grants；`Outcome` / `AppError`；`SerialExecutor` / `BoundedExecutor`；EventBus 溢出策略；删除 StateStore、Command Registry、Capability | 测试覆盖：依赖排序、循环检测、启动失败回滚、未授予接口时启动失败、执行超时返回 `Failure(Timeout)` 且调用方取消照常传播、队列满返回 `Busy` 且已接受的任务全部完成、handler 抛 `Error` 后并发度不变、慢订阅者不阻塞发布方 |
| 3. Android 绑定 | `AndroidApp`；`ProcessLifecycleOwner` 驱动生命周期；Logger 字段与 Logcat sink；WakeRouter 队列化与三种策略 | 仪器测试：旋转屏幕不触发 BACKGROUND；并发提交 100 个唤醒全部处理；`MERGE_BY_KEY` 合并生效 |
| 4. Navigator | NavKey 与 PageEntry；NavSnapshot 带参数保存恢复；UIHost 改用 `entryProvider` | 模拟器：同一路由打开两次状态互不干扰；切换 Tab 后草稿和滚动位置保留；`adb shell am kill` 后恢复到原页面且参数正确 |
| 5. Presenter | 弹层、提示队列、通知移入 Presenter，结果改为强类型；UIManager 改为薄的组合层；删除旧实现 | 现有 UIManager 的测试场景全部迁移并通过；提示被淘汰时 `await()` 返回 null；打开模态框后做返回手势，底下的页面不播放预览动画 |

阶段 5 完成后，用 TapOrder 的 Kiosk 流程做第一个真实 App 验证：新订单提示、断网系统横幅、付款模态框、员工 PIN 模态框。之后再加网络、KV、数据库、同步等基础插件。

## 10. 实现进度

| 阶段 | 状态 |
| --- | --- |
| 0 | 未开始 |
| 1 | 未开始 |
| 2 | 未开始 |
| 3 | 未开始 |
| 4 | 未开始（现有代码已用 Nav3 渲染页面，但仍是字符串路由） |
| 5 | 未开始 |

只记录实际验证过的结果。

## 11. 待定问题

- 包名前缀（暂定 `net.ninepeach.appbase`）。
- iOS 是否仍在计划内。如果暂不做，规格里的跨平台接口一致性要求可以先去掉。
