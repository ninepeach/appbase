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
    fun transition(next: AppPhase) {
        val previous = synchronized(this) {
        check(mutable.value != AppPhase.CLOSED)
        require(next != AppPhase.CLOSED)
        if (next == mutable.value) return
        val old = active; active = null
        if (next == AppPhase.FOREGROUND) active = CoroutineScope(context + SupervisorJob(job))
        mutable.value = next
        events?.tryEmit(LifecycleChanged(next))
        old
        }
        previous?.cancel()
    }
    @Synchronized fun foregroundScope(): CoroutineScope? = active
    override fun close() {
        synchronized(this) {
            if (mutable.value == AppPhase.CLOSED) return
            active = null; mutable.value = AppPhase.CLOSED
            events?.tryEmit(LifecycleChanged(AppPhase.CLOSED))
        }
        job.cancel()
    }
}
