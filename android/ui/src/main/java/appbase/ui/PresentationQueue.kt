package appbase.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ModalRequest(val key:String,val title:String,val message:String,val confirm:String="Confirm",val cancel:String="Cancel",val priority:Int=0,val action:()->Unit)

/**
 * A bounded, main-thread presentation queue.
 *
 * Modal and notice insertion is O(n) and overlay insertion is O(1); memory is O(capacity).
 * Duplicate modal keys and notice text are rejected. Modal priority never interrupts the
 * currently visible modal. Resolving an empty queue or ending an unknown overlay has no effect. Compose state requires every
 * operation to run on Android's main thread.
 */
class PresentationQueue(private val capacity:Int=32) {
    init { require(capacity>0) }
    private val modals=mutableStateListOf<ModalRequest>()
    private val stackedModals=mutableStateListOf<ModalRequest>()
    private val notices=mutableStateListOf<String>()
    private val overlays=mutableStateListOf<Pair<Long,String>>()
    private var sequence=0L
    val modal get()=stackedModals.lastOrNull() ?: modals.firstOrNull()
    val notice get()=notices.firstOrNull()
    val overlay get()=overlays.lastOrNull()?.second
    fun enqueue(request:ModalRequest):Boolean {
        if(!canAddModal(request)) return false
        val index=(1 until modals.size).firstOrNull { modals[it].priority < request.priority } ?: modals.size
        modals.add(index,request);return true
    }

    /** Opens immediately above the current modal. Returns false for duplicate keys or a full queue. */
    fun pushModal(request: ModalRequest): Boolean {
        if (!canAddModal(request)) return false
        stackedModals.add(request)
        return true
    }

    private fun canAddModal(request: ModalRequest): Boolean =
        modals.size + stackedModals.size < capacity &&
            modals.none { it.key == request.key } && stackedModals.none { it.key == request.key }

    fun resolve(accepted:Boolean) {
        if (!accepted) {
            popModal()
            return
        }
        removeTopModal()?.action?.invoke()
    }

    /** Dismisses only the top modal without running its confirmation action. O(1). */
    fun popModal(): Boolean = removeTopModal() != null

    private fun removeTopModal(): ModalRequest? =
        when {
            stackedModals.isNotEmpty() -> stackedModals.removeAt(stackedModals.lastIndex)
            modals.isNotEmpty() -> modals.removeAt(0)
            else -> null
        }
    fun notify(message:String):Boolean {
        if(message.isBlank()||message in notices||notices.size>=capacity)return false
        notices.add(message);return true
    }
    fun consumeNotice(expected:String) { if(notice==expected)notices.removeAt(0) }
    fun beginOverlay(message:String):Long {
        require(message.isNotBlank()) { "Overlay message must not be blank" }
        check(overlays.size<capacity)
        val token=++sequence;overlays.add(token to message);return token
    }
    fun endOverlay(token:Long) { overlays.removeAll { it.first==token } }
    fun clear(){modals.clear();stackedModals.clear();notices.clear();overlays.clear()}
}
@Composable fun PresentationHost(queue:PresentationQueue,snackbar:SnackbarHostState) {
    val notice=queue.notice
    LaunchedEffect(notice) {
        if(notice!=null){snackbar.showSnackbar(notice);queue.consumeNotice(notice)}
    }
    val modal=queue.modal
    if(modal!=null) key(modal.key) {
        AlertDialog(onDismissRequest={queue.popModal()},title={Text(modal.title)},text={Text(modal.message)},confirmButton={TextButton(onClick={queue.resolve(true)}){Text(modal.confirm)}},dismissButton={TextButton(onClick={queue.popModal()}){Text(modal.cancel)}})
    }
    else queue.overlay?.let { message ->
        AlertDialog(onDismissRequest={},confirmButton={},text={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator(Modifier.size(32.dp));Text(message)}})
    }
}
