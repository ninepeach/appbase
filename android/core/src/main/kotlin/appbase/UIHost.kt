package appbase

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Native renderer. UIManager owns all navigation and presentation decisions.
 * The caller owns the manager and its lifetime; composition disposal is not logical close.
 * Modal and blocking overlay renderers supply content, not another Dialog.
 */
class UIHost(
    private val manager: UIManager,
    renderers: Map<String, @Composable (UIInstance) -> Unit>,
    private val lifecycle: AppLifecycle? = null,
) {
    private val renderers = renderers.toMap()

    fun mount(activity: ComponentActivity, theme: @Composable (@Composable () -> Unit) -> Unit = { content -> content() }) {
        activity.setContent { theme { Content() } }
    }

    @Composable fun Content() {
        val state by manager.state.collectAsState()
        val notices by manager.notifications.collectAsState()
        val holder = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }
        var previous by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        val live = state.instances.map { it.id }.toSet()
        LaunchedEffect(live) {
            (previous - live).forEach { holder.removeState(it) }
            previous = ArrayList(live)
        }
        LaunchedEffect(lifecycle, manager) {
            lifecycle?.phase?.collect { manager.setForeground(it == AppPhase.FOREGROUND) }
        }
        val notice = notices.firstOrNull()
        LaunchedEffect(notice, state.foreground) {
            if (notice != null && state.foreground) {
                while (manager.notifications.value.firstOrNull() == notice) {
                    snackbar.showSnackbar(notice, duration = SnackbarDuration.Short)
                    manager.consumeNotice(notice)
                }
            }
        }
        BackHandler(enabled = state.foreground && manager.canGoBack()) { manager.back() }
        val visible = state.instances.filter { it.id in state.visible }
        val page = visible.lastOrNull { it.route.layer == UILayer.PAGE }
        // Blocking priority is decided by UIManager only; never recompute it here.
        val blocker = state.blocker?.let { id -> visible.firstOrNull { it.id == id } }
        val toasts = visible.filter { it.route.layer == UILayer.TOAST }
        Box(Modifier.fillMaxSize()) {
            if (page != null) {
                holder.SaveableStateProvider(page.id) { renderers.getValue(page.route.name)(page) }
            }
            visible.filter { it.route.layer != UILayer.PAGE && it.route.layer != UILayer.TOAST && !it.route.blocking }.forEach { item ->
                key(item.id) { holder.SaveableStateProvider(item.id) { renderers.getValue(item.route.name)(item) } }
            }
            Column(
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toasts.forEach { item -> key(item.id) { renderers.getValue(item.route.name)(item) } }
            }
            SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
        if (blocker != null) {
            key(blocker.id) {
                Dialog(
                    onDismissRequest = { if (blocker.route.dismissible) manager.close(blocker.id) },
                    properties = DialogProperties(
                        dismissOnBackPress = blocker.route.dismissible,
                        dismissOnClickOutside = blocker.route.dismissible,
                    ),
                ) {
                    holder.SaveableStateProvider(blocker.id) { renderers.getValue(blocker.route.name)(blocker) }
                }
            }
        }
    }
}
