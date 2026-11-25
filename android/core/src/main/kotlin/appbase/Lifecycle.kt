package appbase

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class AppPhase { INACTIVE, FOREGROUND, BACKGROUND, CLOSED }
data class LifecycleChanged(val phase: AppPhase) : AppEvent

/** Latest state is authoritative; platform callbacks must be serialized by the owner. */
class AppLifecycle(parent: CoroutineScope, private val events: EventBus? = null) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val context = parent.coroutineContext.minusKey(Job)
    private val mutable = MutableStateFlow(AppPhase.INACTIVE)
    val phase = mutable.asStateFlow()
    private var active: CoroutineScope? = null
    @Synchronized fun transition(next: AppPhase) {
        check(mutable.value != AppPhase.CLOSED)
        require(next != AppPhase.CLOSED)
        if (next == mutable.value) return
        active?.cancel(); active = null
        if (next == AppPhase.FOREGROUND) active = CoroutineScope(context + SupervisorJob(job))
        mutable.value = next
        events?.tryEmit(LifecycleChanged(next))
    }
    @Synchronized fun foregroundScope(): CoroutineScope? = active
    @Synchronized override fun close() {
        if (mutable.value == AppPhase.CLOSED) return
        job.cancel(); active = null; mutable.value = AppPhase.CLOSED
    }
}
