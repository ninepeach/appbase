我已經完整審查了 GitHub 上最新的 [`specs/redesign.md`](https://github.com/ninepeach/appbase/blob/main/specs/redesign.md?utm_source=chatgpt.com)（v2）。

整體架構方向合理，可以確定為 Appbase 下一階段的設計基礎，但還不適合直接按照文檔開始全面重構。

CC 已經吸收了之前的大部分建議，尤其是保留 UIManager、強類型 Service、取消全局 Command Registry、區分資源溢出策略，以及明確 Capability 不是安全隔離。

不過，部分 API 契約仍然存在矛盾，有幾個問題甚至會在拆分模塊時直接造成循環依賴。

## 一、整體評估

| 設計項目                             | 評價                |
| -------------------------------- | ----------------- |
| 整體架構方向                           | 合理                |
| 四模塊拆分                            | 合理，但依賴關係需要修正      |
| App / Plugin / Service           | 基本合理，生命週期契約不完整    |
| Outcome / AppError               | 合理                |
| SerialExecutor / BoundedExecutor | 需要補充故障及關閉語義       |
| EventBus                         | 仍需重新設計            |
| UIManager 公共入口                   | 合理                |
| Navigator / Presenter            | 合理，需補充原子操作契約      |
| SavedState                       | 模塊歸屬錯誤            |
| Logger / Inspector               | 需要修正              |
| WakeRouter                       | 存在較大的設計缺陷         |
| 遷移及驗收計劃                          | 方向合理，部分驗收條件不可直接成立 |

## 二、必須修正的問題（P0）

### 1. 第 4 節：SavedState 模塊歸屬會造成循環依賴

文檔仍然將 `SavedState.kt` 放在 `appbase-ui`，將 `AndroidSavedState.kt` 放在 `appbase-android`。

但 `AndroidSavedState` 實現了 `SavedState.kt` 中定義的 `SavedStatePlatform`。

目前文檔中的依賴問題

正確的歸屬應該是：

| 文件                   | 模塊      |
| -------------------- | ------- |
| SavedState.kt        | kernel  |
| SavedStatePlatform   | kernel  |
| AndroidSavedState.kt | android |
| NavSnapshot.kt       | ui      |

Kernel 增加 kotlinx-serialization 依賴即可，仍然是純 JVM 模塊。

同時，第 4 節還寫著「只有 appbase-ui 依賴 Compose」，但 appbase-design 本身就是 Compose 組件庫，這也是矛盾。

### 2. 第 5.2 節：Plugin 啟動和停止的契約不完整

目前文檔只定義了拓撲排序、啟動失敗回滾和逆序停止。

還缺少以下重要規則：

| 場景                  | 必須定義的行為                         |
| ------------------- | ------------------------------- |
| Plugin.start 執行一半失敗 | 清理該 Plugin 自身，而不只是已成功啟動的 Plugin |
| App.start 被取消       | 回滾已初始化資源，繼續傳播取消                 |
| 啟動期間調用 stop         | 明確串行化或取消啟動                      |
| 重複 start            | 明確拒絕                            |
| 重複 stop             | 冪等                              |
| Plugin.stop 拋異常     | 繼續清理其他 Plugin                   |
| Plugin.stop 長時間阻塞   | 有界等待及故障報告                       |
| Plugin 停止後          | 移除服務註冊、取消 Scope                 |
| PluginContext 被長期持有 | 禁止在啟動完成後繼續 provide              |

另外，`App.service(key)` 應明確限定為組裝方入口。如果任意 Plugin 都能取得 App 並調用它，Grants 的架構約束就可以被繞過。

文檔中的「預計 150 行以內」也建議刪除。代碼行數不是可靠性指標，不應為了滿足行數限制犧牲生命週期完整性。

### 3. 第 5.4 節：Executor 的 Error 處理仍然有問題

文檔一方面寫：

> Error（如 OOM）不捕获。

另一方面又承諾：

> 该 worker 结束后会被重新拉起，parallelism 不会减少。

兩者並非必然矛盾，但目前沒有定義如何隔離 worker 故障、如何監督重啟，以及重啟失敗時如何處理已接受的任務。

我建議：

- 普通 Exception：完成當前任務的失敗結果，worker 繼續運行。
- CancellationException：根據取消來源處理，不轉換成普通業務失敗。
- 致命 Error：進入明確的故障狀態，不承諾 OOM 後仍能安全運行。
- Executor 關閉：拒絕新任務，明確處理排隊及執行中的任務，不能讓調用者永久等待。

此外，`submit()` 返回 `Outcome<T>`，而 block 本身可能返回 `Outcome<Order>`，這會產生 `Outcome<Outcome<Order>>`。需要明確這是執行結果與業務結果的兩層語義，還是提供專門的扁平化入口。

### 4. 第 5.5 節：EventBus 的核心問題仍未解決

CC 保留了：

```
class EventBus(
    capacity: Int = 64,
    overflow: EventOverflow = EventOverflow.DROP_OLDEST
)
```

這仍然是一個全局 EventBus。

如果底層所有事件仍共用一個 SharedFlow 緩衝區，那麼之前指出的事件互相影響問題依然存在。

而且 SharedFlow 在 `DROP_OLDEST` 或 `DROP_LATEST` 策略下，`tryEmit()` 不能直接告訴你實際丟棄了多少事件。

文檔承諾的精確 dropped 計數，不能僅靠 SharedFlow 的公開 API 實現。

我建議第一版直接採用獨立的強類型 Event：

```
class Event<T : Any>(capacity: Int = 64) {
    val flow: SharedFlow<T>
    val rejected: StateFlow<Long>

    fun publish(value: T): Boolean
}
```

使用非掛起 `tryEmit` 和明確的拒絕策略，不需要全局字符串 ID、KClass 分發表或者自研消息中間件。

```
class OrderService {
    private val created = Event<OrderCreated>()

    val orderCreated = created.flow
}
```

這才真正符合「狀態和事件由服務自己擁有」的設計思想。沒有訂閱者時的事件丟失也需要明確記錄為契約，而不是把 publish 成功理解為已送達。

### 5. 第 6 節：UIManager 的原子性沒有明確定義

這一版 UIManager 的設計比上一版合理很多，保留了唯一公共入口，也沒有再增加 BackDispatcher。

但第 6.2 節仍然寫著：

> 页面关闭时取消它的 scope，并通知 Presenter 做级联关闭。

這個「通知」必須明確為同步操作，不能使用 Event 或 Flow 異步通知。

正確的關閉流程應該是：

UIManager.close(pageId)

計算頁面及所有受影響的子實例

Navigator 移除頁面

Presenter 移除彈層及排隊提示

生成並發布唯一的 UIState

取消已移除的 Scope、完成 Handle 結果

整個邏輯狀態修改過程在主線程執行，不允許中途掛起。

資源取消及結果完成必須在內部狀態一致後執行，避免回調重入時看到半關閉狀態。

此外，還有幾個 API 問題：

| API                     | 問題                                  |
| ----------------------- | ----------------------------------- |
| `PageId` / `InstanceId` | 沒有定義兩者的類型關係                         |
| `Presentation`          | 缺少 blocking 屬性，但 Back 規則依賴 blocking |
| `Handle.complete()`     | 沒有明確 exactly-once、過期 Handle 和線程契約   |
| `Handle.await()`        | 調用方取消是否影響彈層生命週期未定義                  |
| `UIManager`             | 缺少 close/dispose 整個 Runtime 的接口     |
| `UIManager.restore()`   | 沒有限定只能在初始化階段使用                      |
| `replacePage()`         | 沒有定義根頁面保護及舊頁面的級聯清理                  |

這些都應該在編碼前確定。

### 6. 第 7.3 節：WakeRouter 仍然存在架構缺陷

目前的設計是每個 Route 一個有界隊列、一個消費者。

假設每個 Route 的 capacity 是 64，註冊了 100 個 Route，整個系統就可能有 6,400 個排隊請求及 100 個消費者。

這不是真正的全局有界設計。

建議增加以下約束，數值只是建議初始值：

| 限制          | 建議默認值 |
| ----------- | ----- |
| 最大 Route 數量 | 32    |
| 全局待處理請求     | 64    |
| 全局最大執行並發    | 4     |
| 已完成去重記錄     | 256   |
| 單次執行超時      | 30 秒  |

同一 Route 串行，不同 Route 有界並行。

還有一個更重要的問題：

```
enum class WakePolicy {
    REJECT_WHEN_FULL,
    MERGE_BY_KEY,
    PERSIST_FIRST
}
```

`PERSIST_FIRST` 不應該是 Kernel WakeRouter 的隊列策略。

因為 Kernel 是純 JVM，而持久化 WorkManager 屬於 Android。非掛起的 `submit()` 也不能直接承諾數據已經完成持久化。

應該拆成：

普通喚醒

允許丟失的通知

WakeRouter

可靠業務任務

訂單、同步任務

Persistent Inbox

WorkManager

Typed Service

實際業務處理

Kernel 不需要知道 WorkManager 的存在。

另外，Pending 和 Running 的去重 ID 不應被淘汰。只有已完成的去重記錄才可以按 TTL 和容量清理。

## 三、建議修正的問題（P1）

| 位置              | 問題                                              | 建議                                      |
| --------------- | ----------------------------------------------- | --------------------------------------- |
| §3 資源上限         | Inspector 丟棄沒有計數                                | 統一增加 eviction counter                   |
| §4 模塊依賴         | 基礎 SDK 只能依賴 Kernel                              | Room、WorkManager、Keystore 等允許依賴 Android |
| §5.3 AppError   | Internal 直接持有 Throwable                         | 避免異常信息直接進入 UI 或序列化                      |
| §5.6 Lifecycle  | ProcessLifecycleOwner 不會觸發錯誤 BACKGROUND 的描述過於絕對 | 明確延遲及 OS 生命週期限制                         |
| §6.3 Prompt     | 去重範圍不明確                                         | 明確 queued + active、owner、TTL            |
| §6.4 Renderer   | 聲稱漏註冊可以在啟動時發現                                   | 必須定義可枚舉的 Route 註冊及驗證機制                  |
| §6.5 Thread     | 只有 debug 包檢查主線程                                 | 建議 release 也保留入口檢查                      |
| §7.1 Logger     | 接受任意 Any?                                       | 限制為安全的標量字段                              |
| §7.1 LogSink    | Sink 失敗及阻塞行為未定義                                 | 隔離輸出異常，避免阻塞業務熱路徑                        |
| §7.2 Inspector  | 只記錄最終狀態                                         | 保留有界的 Running 狀態和隊列深度                   |
| §8 CrashCapture | 標記為「實現可用」                                       | 改為已編碼，尚待驗證                              |

其中 UIManager 的主線程檢查，我認為不應只存在於 Debug 版本。既然整個 UI Runtime 移除了鎖，Release 版本也必須防止錯誤線程破壞內部狀態。

## 四、最終模塊結構

我建議修正為以下依賴關係，而不是四個模塊強制串成一條直線。

虛線表示可選依賴。Design 可以獨立使用。

| 模塊      | 核心組件                                                                                               |
| ------- | -------------------------------------------------------------------------------------------------- |
| kernel  | App、Plugin、ServiceRegistry、Outcome、Executor、Event、Lifecycle、SavedState、Logger、Inspector、WakeRouter |
| android | AndroidApp、ProcessLifecycle、AndroidSavedState、LogcatSink、CrashCapture                              |
| ui      | UIManager、Navigator、Presenter、UIHost、NavSnapshot                                                   |
| design  | DesignSystem、Theme、通用組件                                                                            |

這個規模已經足夠，不需要繼續拆分更多核心模塊。

## 五、遷移計劃需要修正的地方

第 9 節的六階段計劃可以保留，但以下驗收條件需要修改。

| 原驗收條件                    | 修正                                            |
| ------------------------ | --------------------------------------------- |
| Kernel 測試不需要 Android SDK | Gradle 配置階段也必須能獨立執行，而不只是 Kernel 源碼不依賴 Android |
| Error 後並發度不變             | 普通異常不減少並發度；致命 Error 進入可觀察的故障狀態                |
| 並發提交 100 個喚醒全部處理         | 所有已接受請求得到明確結果，超限請求明確拒絕                        |
| 漏註冊 Route 在啟動時發現         | 測試註冊完整性及未註冊 Route 的拒絕行為                       |
| `adb shell am kill` 後恢復  | 驗證真實進程死亡、Task 保留、重新創建及參數恢復                    |
| UIManager 原有測試全部通過       | 保留 appbaseTest 手動測試，並補充自動化回歸測試                |

另外，拆模塊階段要求「行為不變」，但當前 Nav3 代碼尚未建立經驗證的可編譯基線。因此應先修復依賴和編譯問題，再做純粹的模塊遷移，否則無法判斷新出現的問題是原有 Bug 還是重構引入的。

## 六、我的最終結論

## 架構審查結果

整體架構方向

通過

模塊邊界

需修正

API 契約完整性

需修正

工程化階段

可以開始

全面代碼重構

暫不批准

我認為不需要再推翻架構，也不需要重新寫一份完全不同的方案。讓 CC 基於現有 `redesign.md` 修正上述六個 P0 問題和 P1 細節即可。

特別是 SavedState 模塊歸屬、Plugin 生命週期、Executor 故障策略、Event 模型、UI 原子性、WakeRouter 全局容量，這六項必須確定。

iOS 建議繼續保留，Android 優先實現。包名前綴暫定 `net.ninepeach.appbase`，分支保護及 PR 流程則需要你確認，因為與之前直接提交 main 的協作方式不同。

本次審查基於 GitHub 最新版 `redesign.md`，文件 SHA 為 `351de519ea85495bd975ecb4106ea1655281e83e`。沒有修改任何文件，也沒有執行編譯測試。本次主要審查目標架構及 API 契約的一致性，並非對所有現有源碼重新進行逐行審計。