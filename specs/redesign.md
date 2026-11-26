# Appbase 代码审查与重新设计（v3）

状态：已评审的目标设计，尚未实现。本文件是重构的唯一依据。v3 吸收了 [OpenAI 对 v2 的评审](reviews/openai-v2-review.md) 中全部 P0 和 P1 意见。实现进度记录在第 10 节。

## 0. 定位

**麻雀虽小，五脏俱全；接口简单，内部扎实；充分利用原生平台。**

- 业务开发者面对的是少量、强类型、语义明确的入口（`App`、`UIManager`、各业务 Service）。
- 内部实现职责清晰，资源有上限，取消、失败和编程错误严格区分，所有边界行为都有测试。
- 平台已经提供的能力（进程生命周期、Navigation 3、`rememberSaveable` 和 ViewModel、WorkManager）直接使用，Appbase 只补平台没有的部分。
- Android 优先实现；iOS 保留规划，按相同的语义独立实现，不共享代码。

## 1. 结论

现有代码是一个约 1.2k 行的内核加 UI 运行时原型。单个组件写得严谨，但整体结构有三个主要问题：

1. **模块之间只能通过全局的无类型通道协作。** Command Registry、EventBus、StateStore 依赖字符串 id 和全局表，编译器无法检查；Capability 谁都能自己构造，没有实际约束力。
2. **线程模型不统一。** UIManager 用锁加 `Dispatchers.Default`，SavedState 强制主线程，规格写的是“主线程”，代码并没有这样做。
3. **模块边界混在一起。** 纯逻辑内核与 Compose、Nav3、Activity 放在同一个 Android library 里，并以 `api` 暴露给所有使用方。

重新设计的方向：**模块之间用强类型 Service 协作，状态和事件由 Service 自己持有；UI 运行时限定在主线程，对外只有 `UIManager` 一个入口；平台能做的交给平台。** 保留现有设计中真正有价值的部分：有上限的执行队列和两种超时、弹层所有权与级联关闭、提示队列、运行时拒绝不抛异常。

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

1. **模块之间用强类型 Service 协作。** 插件提供接口（如 `OrderService`），依赖它的插件声明依赖后直接调用。状态由拥有它的 Service 以 `StateFlow` 暴露；事件由拥有它的 Service 以 `Event<T>` 暴露。没有全局总线。
2. **取消、失败、编程错误三者分开。**
   - 外部协程取消以 `CancellationException` 继续传播，绝不吞掉；
   - 可预期的失败（业务拒绝、超时、权限不足、远程错误）用 `Outcome.Failure` 表达；
   - 编程错误和配置错误（未注册的路由、重复 id、未授予的接口）直接抛异常，尽早暴露。
3. **UI 运行时限定在主线程。** `UIManager` 及其内部组件不加锁，所有修改都在主线程同步完成。release 包同样在每个公开入口检查线程，错误线程调用直接抛异常。内核和 Service 层保证线程安全。
4. **平台能做的交给平台。** 前后台状态用 `ProcessLifecycleOwner`，页面渲染用 Nav3，页面状态用 `rememberSaveable` 和 ViewModel，需要持久化的后台任务用 WorkManager。
5. **权限通过“能拿到哪些接口”来控制，而不是字符串权限表。** 只读和写入拆成不同接口，由组装方决定给谁。这只是同一进程内的架构约束，**不是安全隔离**：真正的隔离要靠操作系统权限、进程隔离和服务端授权。
6. **所有资源都有上限，超限行为必须明确、可观察，并符合该资源的业务语义。**
   - 拒绝必须让调用方知道（`Failure`、null 或 false），不能只写一条日志；
   - 丢弃或淘汰必须有计数，被淘汰的一方要收到通知（例如 `Handle.await()` 返回取消）；
   - 溢出策略是资源契约的一部分，写进 API 文档；可配置的策略在构造时显式选择；
   - 正在等待或正在执行的工作不能因为容量原因被淘汰。

   | 资源 | 容量满时的策略 | 计数 |
   | --- | --- | --- |
   | Logger 环形缓冲区 | 丢弃最旧记录 | `evicted` |
   | Inspector 环形缓冲区 | 丢弃最旧记录 | `evicted` |
   | Notice（替代 Toast 和 Snackbar） | 淘汰最旧的一条 | `evicted` |
   | `Event<T>` | 拒绝新事件，`publish` 返回 false | `rejected` |
   | `SerialExecutor` / `BoundedExecutor` | 拒绝新任务（`Failure(Busy)`），绝不丢弃已接受的任务 | `rejected` |
   | Service 返回的 `Flow` | 背压 | — |
   | 提示队列（`enqueueModal`） | 拒绝；或按优先级淘汰更低优先级的排队提示，被淘汰者 `await()` 返回 null | `rejected`、`evicted` |
   | 导航栈 | 拒绝新页面（`openPage` 返回 null） | — |
   | WakeRouter | 拒绝（`REJECTED`），或按 key 与排队中的请求合并 | `rejected`、`merged` |
   | WakeRouter 去重表 | 只淘汰已完成的记录；待处理和执行中的记录不淘汰 | `evicted` |
   | SavedState | 拒绝新写入，保留原值 | `rejected` |

7. **先有真实 App，再有抽象。** 只实现 TapOrder 这类真实 App 当下用得上的能力。
8. **插件机制保持简单。** 显式列表、依赖排序、异步启动、逆序关闭、完整的失败与取消语义；不扫描、不用反射或注解、不动态加载、不引入 DI 容器。生命周期的完整性优先于代码行数。

## 4. 模块结构与依赖

4 个模块。依赖关系不是一条直线：`design` 可以独立使用，`ui` 可选依赖 `design`。

```
           appbase-kernel  （纯 Kotlin/JVM）
            ▲          ▲
            │          │
  appbase-android    appbase-ui ┄┄┄> appbase-design（可选）
   （Android）       （Android + Compose）  （Android + Compose，可独立使用）
            ▲          │
            └──────────┘
             ui 依赖 android
```

| 模块 | 类型 | 对外依赖 | 核心组件 |
| --- | --- | --- | --- |
| `appbase-kernel` | Kotlin/JVM | kotlinx-coroutines-core、kotlinx-serialization | App、Plugin、ServiceRegistry、Grants、Outcome/AppError、SerialExecutor、BoundedExecutor、`Event<T>`、AppLifecycle 状态机、SavedState 与 `SavedStatePlatform` 接口、Logger、Inspector、WakeRouter |
| `appbase-android` | Android library | kernel、androidx.lifecycle-process、androidx.savedstate、WorkManager | AndroidApp、进程生命周期绑定、AndroidSavedState、LogcatSink、CrashCapture、PersistentInbox |
| `appbase-ui` | Android library + Compose | kernel、android、navigation3、lifecycle-viewmodel-navigation3、kotlinx-serialization；可选 design | UIManager（内部为 Navigator + Presenter）、UIHost、NavSnapshot |
| `appbase-design` | Android library + Compose | material3 | DesignSystem、Theme、通用组件 |

约束：

- **没有循环依赖。** 接口定义在下层（如 `SavedStatePlatform` 在 kernel），实现放在上层（`AndroidSavedState` 在 android）。
- 每个模块开启 Kotlin explicit API mode；包名为 `net.ninepeach.appbase.kernel`、`.android`、`.ui`、`.design`。
- 只有出现在公开 API 里的类型才用 `api` 依赖，其余一律 `implementation`。
- 只有 `appbase-ui` 和 `appbase-design` 依赖 Compose；只用 Service 和后台能力的代码只依赖 kernel 和 android。
- kernel 必须在没有 Android SDK 的机器上完成 Gradle 配置、编译和测试（包括配置阶段，不只是源码不引用 `android.*`）。
- 未来的基础插件按需选择依赖：纯逻辑的（协议、编解码）只依赖 kernel；需要平台能力的（Room、WorkManager、Keystore）可以依赖 android。插件之间只通过 Service 接口协作。

## 5. 核心 API

### 5.1 通信方式选择

| 场景 | 推荐方式 |
| --- | --- |
| 模块之间调用 | 强类型 Service 接口 |
| 业务状态 | Service 自己持有的 `StateFlow` |
| 一对多通知 | Service 自己持有的 `Event<T>` |
| 串行任务 | `SerialExecutor` |
| 限制并发的任务 | `BoundedExecutor` |
| 流式数据 | Service 方法返回 `Flow`；需要时内部用 `Channel` |
| 必须送达的后台任务（订单、同步） | `appbase-android` 的 `PersistentInbox`（WorkManager）交给 Service 处理 |
| 允许丢失的外部唤醒（推送、深链接、通知点击） | `WakeRouter` |

不再有全局的 Command Registry、`ChannelCommand`、StateStore 和 EventBus。

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
    fun <T : Any> provide(key: ServiceKey<T>, service: T)   // 只能在 start 执行期间调用
    fun <T : Any> require(key: ServiceKey<T>): T            // 只能取到组装方授予本插件的接口
}

class App(plugins: List<Plugin>, grants: Grants = Grants.dependencies(), limits: AppLimits = AppLimits()) {
    val state: StateFlow<AppState>         // NEW / STARTING / RUNNING / STOPPING / STOPPED / FAILED
    suspend fun start(): Outcome<Unit>
    suspend fun stop()
    fun <T : Any> service(key: ServiceKey<T>): T?   // 仅供组装方（Application、UI 层）使用，不暴露给插件
}
```

**接口授予。** `Grants` 描述“哪个插件可以 `require` 哪些 `ServiceKey`”。默认规则：插件只能拿到它在 `dependsOn` 里声明的插件所提供的接口。组装方可以进一步收窄：

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
    grants = Grants.dependencies().deny(OrderWriterKey, to = "kiosk"),   // Kiosk 只能读
)
```

`App` 实例不传给插件；插件只能通过 `PluginContext` 获得服务，因此无法绕过 Grants。

**生命周期契约：**

| 场景 | 行为 |
| --- | --- |
| 配置校验 | 构造时校验 id 唯一、依赖存在、没有循环；失败直接抛异常 |
| 启动顺序 | 按依赖拓扑排序依次调用 `start`；同一时刻只启动一个插件 |
| `require` 未授予的接口 | 抛异常，该插件启动失败 |
| 某个插件的 `start` 中途失败 | 取消该插件的 `scope`，移除它已 `provide` 的服务，调用它的 `stop` 让它清理自身；然后按相反顺序停止已启动的插件；`start()` 返回 `Failure`，`state` 进入 FAILED |
| `App.start()` 被调用方取消 | 按上一行同样的方式回滚，然后继续抛出 `CancellationException` |
| 重复调用 `start` | 只有在 NEW 状态下有效；其他状态抛 `IllegalStateException` |
| 启动期间调用 `stop` | `stop` 取消正在进行的启动，等待回滚完成后返回 |
| 重复调用 `stop` | 幂等，后续调用等待第一次完成后直接返回 |
| `Plugin.stop` 抛异常 | 记录日志，继续停止其他插件 |
| `Plugin.stop` 长时间不返回 | 每个插件最多等待 `limits.stopTimeout`（默认 5 秒），超时记录日志并继续，最终 `state` 为 STOPPED 并带上超时插件列表 |
| 插件停止后 | 取消它的 `scope`，从注册表中移除它提供的服务 |
| `start` 返回后再调用 `provide` | 抛 `IllegalStateException` |

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
    data class Timeout(override val code: String) : AppError       // 执行或排队超时（与调用方取消区分）
    data class Busy(override val code: String) : AppError          // 容量已满，可稍后重试
    data class Unavailable(override val code: String) : AppError   // 依赖的插件或执行器未运行
    data class Network(override val code: String, val retryable: Boolean) : AppError
    data class Remote(override val code: String, val status: Int) : AppError
    class Internal(override val code: String, internal val cause: Throwable) : AppError {
        override fun toString() = "Internal($code)"   // 不把异常信息带进 UI、日志字段或序列化
    }
}
```

何时用 `Outcome`：

- **可能出现可预期失败的操作**（I/O、远程调用、权限、配额、超时）返回 `Outcome<T>`；
- **纯内存的查询和状态读取**直接返回数据或 `StateFlow`；
- **框架边界**（执行器、WakeRouter）返回 `Outcome` 或明确的结果枚举。

```kotlin
interface OrderService {
    val orders: StateFlow<List<Order>>
    val orderCreated: SharedFlow<OrderCreated>
    suspend fun submit(order: Order): Outcome<Order>
    fun find(id: OrderId): Order?
}
```

规则：

- 外部协程取消以 `CancellationException` 继续传播；
- 框架内部的执行超时转换为 `Failure(AppError.Timeout)`，不以 `CancellationException` 的形式泄漏给调用方；
- 编程错误直接抛异常；
- 内部实现不要用 `runCatching` 或标准库的 `Result` 包装 suspend 调用：`runCatching` 会把 `CancellationException` 也吞掉；
- `AppError.Internal.cause` 只在框架内部用于记录诊断信息（异常类名和堆栈），不对 UI 公开，也不进入日志字段。

### 5.4 SerialExecutor 与 BoundedExecutor

从现有 `CommandService` 中提取：有上限的队列、排队超时与执行超时分开计算、取消向上传播、明确拒绝。

```kotlin
class BoundedExecutor(
    scope: CoroutineScope,
    parallelism: Int,
    queueCapacity: Int,
    name: String,
) {
    val state: StateFlow<ExecutorState>   // RUNNING / FAILED / CLOSED，以及排队数、运行数、拒绝数
    suspend fun <T> submit(
        queueTimeout: Duration = 30.seconds,
        executionTimeout: Duration = 30.seconds,
        block: suspend () -> T,
    ): Outcome<T>
    suspend fun <T> submitOutcome(
        queueTimeout: Duration = 30.seconds,
        executionTimeout: Duration = 30.seconds,
        block: suspend () -> Outcome<T>,
    ): Outcome<T>                         // 执行结果与业务结果扁平化为一层
    suspend fun close(drain: Boolean = true)
}

class SerialExecutor(scope: CoroutineScope, queueCapacity: Int, name: String)   // 等价于 parallelism = 1
```

两层语义：`submit` 的 `Failure` 只表示**执行层面**的失败（Busy、Timeout、Unavailable、Internal）；业务失败由 `block` 自己返回 `Outcome`，并通过 `submitOutcome` 扁平化。

故障与关闭：

| 情况 | 行为 |
| --- | --- |
| 队列已满 | 立即返回 `Failure(Busy)`；已接受的任务不受影响 |
| 排队超时 / 执行超时 | 返回 `Failure(Timeout)`，`code` 分别为 `queue_timeout` / `execution_timeout`；超时的任务被取消 |
| 调用方取消 | 排队中的任务被移出队列，执行中的任务被取消；调用方照常收到 `CancellationException` |
| `block` 抛出普通 `Exception` | 该任务返回 `Failure(Internal)`；worker 继续运行，并发度不变 |
| `block` 抛出 `CancellationException`，但调用方并未取消 | 视为任务内部取消，返回 `Failure(Internal("cancelled"))`，不传播给调用方 |
| `block` 抛出 `Error`（如 OOM） | 执行器进入 FAILED 状态：排队中的任务全部返回 `Failure(Unavailable)`，新提交立即返回 `Failure(Unavailable)`；不承诺之后还能安全运行；`Error` 交给 scope 的异常处理器 |
| `close(drain = true)` | 拒绝新任务（`Unavailable`），等待排队和执行中的任务完成 |
| `close(drain = false)` | 拒绝新任务，排队中的任务返回 `Failure(Unavailable)`，执行中的任务被取消 |

任何情况下，调用方都不会永久等待。

### 5.5 Event

每个 Service 自己持有强类型的事件，不再有全局 EventBus。

```kotlin
class Event<T : Any>(capacity: Int = 64) {
    val flow: SharedFlow<T>
    val rejected: StateFlow<Long>
    fun publish(value: T): Boolean        // 不挂起；缓冲区满时拒绝新事件，返回 false，计数加一
}

class OrderServiceImpl : OrderService {
    private val created = Event<OrderCreated>()
    override val orderCreated = created.flow
}
```

- 内部使用 `MutableSharedFlow(replay = 0, extraBufferCapacity = capacity, onBufferOverflow = SUSPEND)` 加 `tryEmit`：缓冲区满时 `tryEmit` 返回 false，因此拒绝数可以精确统计。不提供“丢弃最旧事件”的策略，因为 `SharedFlow` 的公开 API 无法统计它丢掉了多少。
- **没有订阅者时事件被丢弃，`publish` 仍返回 true。** `publish` 成功只表示被接受，不表示已送达，这是契约的一部分。
- 慢订阅者只影响同一个 `Event` 的发布方，不同事件之间互不影响。
- 需要当前值的场景用 `StateFlow`；必须送达的场景用执行器或 PersistentInbox。

### 5.6 生命周期

kernel 的 `AppLifecycle` 只保留状态机（`phase` 和 `foregroundScope`）。由 `appbase-android` 的 `AndroidApp` 订阅 `ProcessLifecycleOwner` 来驱动它，Activity 不再手动调用。

```kotlin
class AndroidApp(application: Application, plugins: List<Plugin>, grants: Grants = Grants.dependencies()) {
    val app: App
    val lifecycle: AppLifecycle
    // 内部：ProcessLifecycleOwner -> AppLifecycle、LogcatSink、可选 CrashCapture
}
```

平台行为与限制：

- `ProcessLifecycleOwner` 按整个进程计算前后台：屏幕旋转、Activity 之间切换不会进入 BACKGROUND；
- 进入后台的事件有约 700 ms 的延迟（平台为了吸收配置变更而设计）；
- 进程被系统杀死时不会收到任何回调，`foregroundScope` 中的工作不能依赖清理逻辑，必须能在下次启动时从持久化状态恢复。

### 5.7 SavedState

接口和核心逻辑放在 kernel，Android 实现放在 android。API 与当前实现一致：

```kotlin
interface SavedStatePlatform {                       // kernel
    fun checkThread()
    fun consume(key: String): Map<String, ByteArray>?
    fun register(key: String, provider: () -> Map<String, ByteArray>): AutoCloseable
    fun fits(values: Map<String, ByteArray>): Boolean
}
class SavedState(platform: SavedStatePlatform, /* limits */)   // kernel
class AndroidSavedState(registry: SavedStateRegistry)          // android
```

`slot(key, serializer)` 返回 `Slot<T>?`；`Slot` 是 `AutoCloseable`；超限时拒绝新写入、保留原值；未认领的恢复数据只保留一次保存；解码失败的数据被删除。详见 `saved-state.md`。导航快照不使用 SavedState，由 `UIManager` 单独注册（见 6.7）。

## 6. UI 运行时：UIManager

**`UIManager` 是业务开发者唯一的 UI 入口。** 内部拆成 `Navigator`（页面）和 `Presenter`（弹层）两个 `internal` 类，各自职责单一、可以单独测试。UIManager 负责组合和跨边界规则：返回键优先级、有阻塞弹层时禁止跳转、页面关闭时级联关闭弹层。它必须保持薄，具体逻辑放在两个内部类里。

### 6.1 标识与数据模型

```kotlin
@JvmInline value class InstanceId(val value: String)    // 页面和弹层共用的唯一标识

sealed interface UIInstance { val id: InstanceId }
data class PageInstance(override val id: InstanceId, val tab: String, val key: NavKey) : UIInstance
data class PresentationInstance(
    override val id: InstanceId,
    val owner: InstanceId,              // 所属页面或父弹层
    val presentation: Presentation<*>,
) : UIInstance

interface Presentation<R : Any> {
    val layer: Layer
    val dismissible: Boolean            // 返回键和点击外部能否关闭
    val blocking: Boolean               // 是否独占输入；MODAL 和 SYSTEM 默认 true
}
enum class Layer { OVERLAY, MODAL, SYSTEM }
```

页面和弹层使用同一种 `InstanceId`，`close(id)` 和 `scope(id)` 对两者都有效。

### 6.2 公开 API

```kotlin
@MainThread
class UIManager(
    tabs: Map<String, NavKey>,
    routes: RouteRegistry,              // 所有页面路由及其序列化器，见 6.6
    lifecycle: AppLifecycle,
    limits: UILimits = UILimits(),
) : AutoCloseable {
    val state: StateFlow<UIState>       // 页面与弹层合成的一份快照，UIHost 只读这一份

    // 初始化（只能在第一次 openPage / openModal 之前调用一次）
    fun restore(bytes: ByteArray): Boolean   // 校验失败返回 false 并保持在根页面
    fun save(): ByteArray?                   // 带版本号的 NavSnapshot，超限返回 null

    // 页面
    fun openPage(key: NavKey): InstanceId?       // 被阻塞或导航栈已满时返回 null
    fun replacePage(key: NavKey): InstanceId?    // 替换当前顶层页面；根页面不能被替换（返回 null）
    fun selectRoot(tab: String): Boolean
    fun back(): Boolean

    // 弹层
    fun <R : Any> openModal(p: Presentation<R>): Handle<R>?
    fun <R : Any> enqueueModal(p: Presentation<R>, key: String, priority: Int = 0,
                               ttl: Duration = 60.seconds): Handle<R>?
    fun showOverlay(p: Presentation<Unit>): Handle<Unit>?
    fun showSystem(p: Presentation<Unit>): Handle<Unit>?
    fun notify(notice: Notice, duration: Duration = 4.seconds)

    // 通用
    fun close(id: InstanceId): Boolean
    fun scope(id: InstanceId): CoroutineScope?

    // 后台线程入口
    suspend fun <T> dispatch(block: UIManager.() -> T): T   // 切到 Dispatchers.Main.immediate 执行

    override fun close()                // 关闭整个运行时：关闭所有实例，所有 Handle 以 null 完成
}
```

`replacePage` 会先关闭旧页面及其全部弹层，再放入新页面，二者在同一次原子操作中完成。

### 6.3 Handle

```kotlin
class Handle<R : Any> internal constructor(/* ... */) {
    val id: InstanceId
    suspend fun await(): R?             // null 表示被取消、被级联关闭、被淘汰或运行时已关闭
    fun complete(result: R): Boolean    // 主线程调用；只有第一次有效，返回 true 并关闭该弹层
}
```

- **只完成一次。** `complete`、`close(id)`、级联关闭、淘汰中最先发生的一个决定结果，之后的 `complete` 返回 false；
- **过期 Handle。** 弹层已经关闭后调用 `complete` 返回 false，不会影响之后打开的弹层；
- **线程。** `complete` 只能在主线程调用；`await` 可以在任意线程调用；
- **调用方取消。** 取消 `await` 的调用方只会让调用方停止等待，不会关闭弹层。需要关闭时显式调用 `close(id)`。

### 6.4 原子操作

所有会修改状态的公开方法都在主线程上同步完成，中途不挂起。以 `close(id)` 为例：

1. 计算被关闭的实例及其全部后代（子页面、弹层、名下排队的提示）；
2. Navigator 移除页面；Presenter 移除弹层和排队提示。这是同步的直接调用，不经过 Flow 或 Event；
3. 生成并发布一份新的 `UIState`；
4. 内部状态一致之后，再取消被移除实例的 `scope`，并以 null 完成它们的 Handle。

第 4 步触发的回调如果重新调用 UIManager，看到的一定是已经完成的状态。`openPage`、`replacePage`、`back`、`selectRoot`、`enqueueModal` 的提升，以及运行时关闭，都遵循同样的“先改状态、再发布、最后释放资源”的顺序。

### 6.5 内部：Navigator 与 Presenter

**Navigator**

- 每个 Tab 一个 `List<PageInstance>`；`key` 是业务的 `@Serializable` 路由，不含框架字段；
- `NavDisplay` 直接渲染这份列表，`contentKey = id`，同一路由打开两次也互不干扰；
- 页面数有上限（默认每个 Tab 32 个），满时 `openPage` 返回 null。

**Presenter**

- 模态框的父子栈与提示队列分开；
- 只有当前 Tab 顶层页面名下的弹层可见；页面被覆盖或切换 Tab 时，弹层隐藏但不销毁；
- 不可关闭的弹层会吞掉返回事件；
- `notify` 最多保留 N 条（默认 3 条），淘汰最旧的一条。

**提示队列**

- 去重范围：同一个 key 在“排队中 + 正在显示”中只能存在一个，与 owner 无关；
- owner 是入队时的顶层页面；owner 被关闭时，它名下排队的提示一起丢弃，Handle 以 null 完成；
- TTL 从入队时开始计算，使用单调时钟；过期的提示不会被提升；
- 提升条件：App 在前台、owner 是当前 Tab 的顶层页面、没有阻塞弹层、实例容量未满；
- 按优先级排序，同优先级先进先出；
- 队列满时：如果新提示的优先级高于队列中最低的一条，淘汰那一条（它的 `await()` 返回 null，`evicted` 加一）；否则拒绝新提示（返回 null，`rejected` 加一）。

### 6.6 路由注册与渲染

```kotlin
val routes = RouteRegistry {
    page<Home> { HomeScreen() }
    page<ServerDetail> { key -> ServerDetailScreen(key.serverId) }
}
```

- `RouteRegistry` 同时登记每个路由的 Composable 和序列化器，UIHost 用它生成 Nav3 的 `entryProvider`；
- 构造 UIManager 时检查：`tabs` 中的每个根路由都已注册；
- `openPage` 传入未注册的路由类型属于编程错误，直接抛异常；`restore` 遇到未注册的路由视为快照无效，返回 false；
- 能在启动时发现的只有根路由缺失。其他页面是否注册，由测试保证：每个 App 的测试应遍历它的全部路由，确认都能打开。

返回键：

- `NavDisplay.onBack` 和弹层的返回处理都只调用 `UIManager.back()`；
- `back()` 的顺序：可见的阻塞 SYSTEM → 可见的阻塞 MODAL → 可见的阻塞 OVERLAY → 当前页面；目标不可关闭时吞掉事件并返回 true；位于根页面时返回 false；
- 有阻塞弹层时，用优先级更高的返回处理拦截 predictive back，底下的页面不播放预览动画；手势取消时不改变任何状态。

前后台状态由 UIManager 自己订阅 `AppLifecycle.phase`，UIHost 不参与。

### 6.7 保存与恢复

- `NavSnapshot(version, selected, tabs: Map<String, List<SavedPage>>)`，其中 `SavedPage(id, key)`，用 `RouteRegistry` 提供的序列化器编码；
- **参数随路由一起保存和恢复**，修复现有实现丢失参数的问题；
- 有字节上限（默认 64 KiB）和页面数上限；超限时 `save()` 返回 null 并记录 `navigation.save.rejected`，下次启动回到根页面；
- 弹层、提示、通知、scope 和 Handle 都不保存；
- `restore` 只能在初始化阶段调用一次，之后调用抛 `IllegalStateException`；
- UIManager 自己向 SavedStateRegistry 注册保存回调，回调内部不允许抛异常。

### 6.8 线程

UIManager 及其内部组件标注 `@MainThread`，内部不加锁。**release 包和 debug 包都在每个公开入口检查线程**，非主线程调用抛 `IllegalStateException`。后台代码通过 `ui.dispatch { openPage(...) }` 切到主线程。通知和提示的定时器运行在主线程作用域里。

## 7. 诊断与唤醒

### 7.1 Logger

保留“不自动记录 payload”的原则，但允许调用方显式带上字段，并按字段名脱敏。

```kotlin
sealed interface LogValue {
    data class Text(val value: String) : LogValue
    data class Number(val value: Long) : LogValue
    data class Decimal(val value: Double) : LogValue
    data class Flag(val value: Boolean) : LogValue
}

interface Logger {
    fun log(level: LogLevel, code: String, vararg fields: Pair<String, LogValue>)
    fun info(code: String, vararg fields: Pair<String, LogValue>)
    fun child(tag: String): Logger
}

log.info("http.done", "status" to 200.lv, "ms" to 132.lv)   // .lv 是到 LogValue 的扩展
```

- 字段只接受标量（字符串、整数、浮点数、布尔），不接受任意对象，避免把整个业务对象或异常信息写进日志；
- 默认脱敏字段名：`token`、`password`、`secret`、`authorization`、`cookie`，可配置；
- 字段值超过 256 字符截断，每条最多 16 个字段；
- release 包默认不向 Logcat 输出 DEBUG。

**Sink**

```kotlin
interface LogSink { fun write(record: LogRecord) }
```

- 内置 `RingBufferSink`（容量满时丢弃最旧，计数 `evicted`），android 提供 `LogcatSink`；
- 写日志的调用方只把记录放进一个有上限的内存队列，由单独的协程分发给各个 sink，sink 再慢也不会阻塞业务热路径；
- 分发队列满时丢弃最新记录并计数；
- 某个 sink 抛异常时，记录到内部计数并跳过，不影响其他 sink；连续失败超过阈值的 sink 被暂停。

### 7.2 Inspector

- 固定大小的环形缓冲区，只在查看时生成快照；容量满时丢弃最旧记录，计数 `evicted`；
- 记录每个执行器当前的排队深度和运行中的任务（有上限），以及已完成任务的耗时和结果；
- release 包默认关闭。

### 7.3 WakeRouter

WakeRouter 只处理**允许丢失**的外部唤醒（刷新、跳转、展示通知）。必须送达的业务任务不经过它，而是走 `appbase-android` 的 PersistentInbox：

```
普通唤醒（允许丢失）  ──>  WakeRouter                 ──> Service
可靠任务（订单、同步） ──>  PersistentInbox（WorkManager）──> Service
```

kernel 不知道 WorkManager 的存在。

```kotlin
class WakeRouter(scope: CoroutineScope, limits: WakeLimits = WakeLimits()) {
    fun register(route: String, policy: WakePolicy, handler: suspend (WakeRequest) -> Outcome<Unit>)
    fun submit(request: WakeRequest): WakeAdmission   // ACCEPTED / MERGED / DUPLICATE / EXPIRED / UNKNOWN_ROUTE / INVALID / REJECTED
    val results: Flow<WakeResult>
}

enum class WakePolicy { REJECT_WHEN_FULL, MERGE_BY_KEY }

data class WakeLimits(
    val maxRoutes: Int = 32,
    val maxPending: Int = 64,            // 全局待处理请求数
    val maxConcurrency: Int = 4,         // 全局并发执行数
    val maxCompletedDedupe: Int = 256,   // 已完成的去重记录
    val executionTimeout: Duration = 30.seconds,
)
```

- `submit` 不挂起：校验、去重，然后放入全局有上限的待处理队列；
- 同一 route 串行，不同 route 并行，全局并发数不超过 `maxConcurrency`；
- 全局待处理数达到上限时：`REJECT_WHEN_FULL` 返回 REJECTED；`MERGE_BY_KEY` 如果队列中已有同 key 的请求则合并（返回 MERGED），否则返回 REJECTED；
- 注册超过 `maxRoutes` 个 route 属于配置错误，抛异常；
- 去重：待处理和执行中的请求 id 不会被淘汰；只有已完成的记录按过期时间和 `maxCompletedDedupe` 清理；
- 每个已接受的请求都会在 `results` 中得到明确结果（成功、失败、超时）。

**PersistentInbox（appbase-android）**

```kotlin
interface PersistentInbox {
    suspend fun enqueue(item: InboxItem): Outcome<Unit>   // 返回成功时已经写入持久化存储
}
```

由 WorkManager 负责调度和重试，处理逻辑交给对应的 Service；条目有幂等键，重复提交只处理一次。

## 8. 删除与保留清单

| 现有代码 | 处理 | 理由 |
| --- | --- | --- |
| `StateStore` / `StateKey` | 删除 | 状态由 Service 以 `StateFlow` 暴露 |
| 全局 Command Registry、`CommandClient`、`ChannelCommand` | 删除 | 模块之间改用 Service；流式数据用 `Flow` |
| `CommandService` 的队列、串行/并行、两种超时、取消传播 | 保留，提取为 `SerialExecutor` / `BoundedExecutor` | 执行能力本身有价值；同时修复超时泄漏和 worker 减少 |
| `Capability` | 删除 | 改为通过授予的接口控制；安全隔离交给系统和服务端 |
| 全局 `EventBus` | 删除 | 改为由 Service 持有的强类型 `Event<T>` |
| `AppLifecycle` 状态机 | 保留 | 改由 `ProcessLifecycleOwner` 驱动 |
| `UIManager` 公开入口 | 保留 | 唯一的 UI 入口；内部拆成 Navigator 和 Presenter |
| 模态框父子栈、提示队列、级联关闭、`scope(id)`、运行时拒绝返回 null | 保留，移入 Presenter | 官方库没有、也是最有价值的部分 |
| 扁平 Map 格式的导航保存恢复 | 删除 | 改为带参数的 `NavSnapshot` |
| `notify` + Snackbar 与 TOAST 层 | 合并为 `UIManager.notify` | 只保留一套通知机制 |
| `UIHost` 的 `Map<String, Composable>` renderer | 改为 `RouteRegistry` + Nav3 `entryProvider` | 路由与序列化器一起注册，可以枚举和测试 |
| `SavedState` typed slot | 保留，移入 kernel；`AndroidSavedState` 移入 android | 避免循环依赖 |
| `Logger`（只有 code） | 扩展为标量字段和异步 sink | 现在连状态码都记不了 |
| `Inspector` | 改为环形缓冲区，增加排队深度和运行中任务 | 去掉热路径上的复制 |
| `CrashCapture` | 保留，移入 `appbase-android`；状态为“已编码，尚待验证” | 还没有在真机上验证过 |
| `WakeRouter` | 重写为全局有上限的队列模型；可靠任务改走 PersistentInbox | 并发时不再丢失唤醒，总量有上限 |
| `ui` 模块的 `ScreenHost` | 删除 | 与 Nav3 转场重复 |
| `DesignSystem.kt` | 保留，移入 `appbase-design` | 去掉写死的文字 |
| `tests/Smoke.kt` + `run.sh` | 删除 | 改为各模块的 `src/test`（JUnit、kotlinx-coroutines-test、Turbine） |
| `architecture.md` 的 VPN、计费、推送、媒体等平台能力目录 | 移到“未来规划” | 在有具体 App 需求前不纳入设计范围 |

## 9. 迁移计划与验收

分 6 个阶段依次进行。每个阶段完成后，本地编译和全部测试通过，再按 `AGENTS.md` 的规则推送到 main。需要在装有 Android SDK 和模拟器的机器上进行。

**当前基线：** 从提交 `29d41c9` 起，CI 可以编译 core、ui 和 appbaseTest，JVM 冒烟测试通过。现有行为还没有在模拟器或真机上验证过。

| 阶段 | 内容 | 验收标准 |
| --- | --- | --- |
| 0. 基线与工程化 | 先在模拟器上手动跑一遍 appbaseTest（Run all tests、Page/Modal/Overlay、Save and recreate Activity），把结果记录到 `specs/baseline.md`；然后添加 Gradle wrapper、version catalog、ktlint 和 detekt、explicit API mode；CI 改为运行 `./gradlew build` | 基线记录完成（包括失败项）；CI 绿；所有现有冒烟测试仍然通过 |
| 1. 拆模块 | 建立 kernel、android、ui、design 四个模块，迁入现有代码，修改包名；`tests/Smoke.kt` 拆成各模块的 JUnit 测试；`SavedState` 放入 kernel | 在没有安装 Android SDK 的环境中，`./gradlew :appbase-kernel:test` 能完成配置、编译和测试；模块之间没有循环依赖；appbaseTest 在模拟器上的表现与基线一致 |
| 2. 内核 | Plugin 生命周期（5.2 的全部契约）；服务注册表与 Grants；`Outcome` / `AppError`；`SerialExecutor` / `BoundedExecutor`（5.4 的全部故障与关闭语义）；`Event<T>`；删除 StateStore、Command Registry、Capability、全局 EventBus | 5.2 的生命周期表和 5.4 的故障表每一行都有对应测试并通过；执行超时返回 `Failure(Timeout)`，调用方取消时照常传播 `CancellationException`；普通异常不降低并发度；致命 `Error` 让执行器进入可观察的 FAILED 状态，且所有等待者都拿到结果；慢订阅者只影响同一个 `Event` |
| 3. Android 绑定 | `AndroidApp`；`ProcessLifecycleOwner` 驱动生命周期；Logger 字段、异步 sink 与 LogcatSink；WakeRouter 全局有上限的队列与两种策略；PersistentInbox | 仪器测试：旋转屏幕和切换 Activity 不触发 BACKGROUND；并发提交超过上限数量的唤醒时，所有已接受的请求都有明确结果，超出的请求明确返回 REJECTED 或 MERGED；sink 抛异常或阻塞时，业务线程上的 `log` 调用不受影响 |
| 4. Navigator | `RouteRegistry`、`InstanceId`、`PageInstance`；`NavSnapshot` 带参数保存恢复；UIHost 改用 `entryProvider` | 模拟器：同一路由打开两次状态互不干扰；切换 Tab 后草稿和滚动位置保留；启用“不保留活动”并在后台用 `adb shell am kill` 杀死进程后，从最近任务中返回，页面栈和参数完整恢复；遍历全部路由的测试通过，未注册路由的行为符合 6.6 |
| 5. Presenter | 弹层、提示队列、通知移入 Presenter，结果改为强类型 `Handle`；6.4 的原子操作；UIManager 改为薄的组合层；删除旧实现 | 现有 UIManager 的测试场景全部迁移为自动化测试并通过；Handle 只完成一次、过期 Handle、调用方取消不关闭弹层等 6.3 的规则都有测试；提示被淘汰时 `await()` 返回 null；在回调里重入 UIManager 看到的是完整状态；模拟器上打开模态框后做返回手势，底下的页面不播放预览动画；appbaseTest 的手动测试保留 |

阶段 5 完成后，用 TapOrder 的 Kiosk 流程做第一个真实 App 验证：新订单提示、断网系统横幅、付款模态框、员工 PIN 模态框。之后再加网络、KV、数据库、同步等基础插件。

## 10. 实现进度

| 阶段 | 状态 |
| --- | --- |
| 0 | **完成**（2026-11-27）。模拟器基线记录于 `specs/baseline.md`：10/10 自动化测试通过，4 项手动 UI 检查全部通过。Gradle wrapper 8.11.1、version catalog（`android/gradle/libs.versions.toml`）、ktlint 12.1.1 和 detekt 1.23.6（均设 ignoreFailures，允许现有违规）、三个模块均开启 explicit API mode 并补全所有 public 修饰符、CI 改为 `./gradlew -PwithDemo=true build`。`./gradlew -PwithDemo=true build` 本地验证通过（BUILD SUCCESSFUL）。 |
| 1 | 未开始 |
| 2 | 未开始 |
| 3 | 未开始 |
| 4 | 未开始（现有代码已用 Nav3 渲染页面，但仍是字符串路由） |
| 5 | 未开始 |

只记录实际验证过的结果。

## 11. 已确定的决策

- 包名前缀：`net.ninepeach.appbase`。
- 平台：Android 优先实现；iOS 保留规划，按相同语义独立实现，不共享代码。
- 协作：不开启分支保护，允许直接推送到 main；推送前必须满足 `AGENTS.md` 中的检查要求。

## 12. 修订记录

- v1：初稿（在线文档）。
- v2：UIManager 保持唯一公开入口；溢出策略按资源语义区分；只在可能失败的操作上使用 Outcome；从 CommandService 提取执行器；用接口授予取代 Capability；插件机制保持最小。
- v3：根据 [OpenAI 评审](reviews/openai-v2-review.md) 修正：SavedState 移入 kernel 以消除循环依赖；补全 Plugin 生命周期契约，`App.service` 仅限组装方；Executor 的故障、关闭语义与 `submitOutcome`；全局 EventBus 改为服务持有的 `Event<T>`；UIManager 原子操作、`InstanceId`、`blocking`、Handle 契约、`restore` 限制、`replacePage` 语义和运行时关闭；WakeRouter 全局上限，可靠任务改走 PersistentInbox，待处理去重记录不淘汰；以及全部 P1 细节。同时修正评审中一处过时信息：编译基线已经存在，但尚未在模拟器上验证。
