package appbase.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ModalRequest(val key:String,val title:String,val message:String,val confirm:String="Confirm",val cancel:String="Cancel",val priority:Int=0,val action:()->Unit)
class PresentationQueue(private val capacity:Int=32) {
    init { require(capacity>0) }
    private val modals=mutableStateListOf<ModalRequest>()
    private val notices=mutableStateListOf<String>()
    private val overlays=mutableStateListOf<Pair<Long,String>>()
    private var sequence=0L
    val modal get()=modals.firstOrNull()
    val notice get()=notices.firstOrNull()
    val overlay get()=overlays.lastOrNull()?.second
    // All methods must be called on the UI thread.
    fun enqueue(request:ModalRequest):Boolean {
        if(modals.any{it.key==request.key} || modals.size>=capacity)return false
        val index=(1 until modals.size).firstOrNull { modals[it].priority < request.priority } ?: modals.size
        modals.add(index,request);return true
    }
    fun resolve(accepted:Boolean) {
        if(modals.isEmpty())return
        val item=modals.removeAt(0)
        if(accepted)item.action()
    }
    fun notify(message:String):Boolean {
        if(message.isBlank()||message in notices||notices.size>=capacity)return false
        notices.add(message);return true
    }
    fun consumeNotice(expected:String) { if(notice==expected)notices.removeAt(0) }
    fun beginOverlay(message:String):Long {
        check(overlays.size<capacity)
        val token=++sequence;overlays.add(token to message);return token
    }
    fun endOverlay(token:Long){overlays.removeAll { it.first==token }}
    fun clear(){modals.clear();notices.clear();overlays.clear()}
}
@Composable fun PresentationHost(queue:PresentationQueue,snackbar:SnackbarHostState) {
    val notice=queue.notice
    LaunchedEffect(notice) {
        if(notice!=null){snackbar.showSnackbar(notice);queue.consumeNotice(notice)}
    }
    val modal=queue.modal
    if(modal!=null) AlertDialog(onDismissRequest={queue.resolve(false)},title={Text(modal.title)},text={Text(modal.message)},confirmButton={TextButton(onClick={queue.resolve(true)}){Text(modal.confirm)}},dismissButton={TextButton(onClick={queue.resolve(false)}){Text(modal.cancel)}})
    else queue.overlay?.let { message ->
        AlertDialog(onDismissRequest={},confirmButton={},text={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator(Modifier.size(32.dp));Text(message)}})
    }
}
