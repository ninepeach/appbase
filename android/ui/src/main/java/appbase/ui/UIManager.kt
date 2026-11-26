package appbase.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import appbase.*

class UIManager(private val root: String, definitions: Map<String, Layer>, private val renderers: Map<String, @Composable (UIEntry) -> Unit>) {
    val navigation = Navigation(definitions, root)
    init { require(definitions.keys == renderers.keys) }
    fun mount(activity: ComponentActivity) { activity.setContent { MaterialTheme { Content() } } }
    @Composable fun Content() {
        val stack by navigation.stack.collectAsState()
        val holder = rememberSaveableStateHolder()
        var previous by remember { mutableStateOf(emptySet<Long>()) }
        LaunchedEffect(stack) {
            val current = stack.map { it.instance }.toSet()
            (previous - current).forEach { holder.removeState(it.toString()) }
            previous = current
        }
        BackHandler(enabled = stack.any { it.layer != Layer.TOAST && it.instance != stack.first().instance }) { navigation.back() }
        Box(Modifier.fillMaxSize()) {
            val screen = stack.last { it.layer == Layer.SCREEN }
            ScreenHost(holder,screen.instance.toString()) { transition -> Box(Modifier.fillMaxSize().then(transition)) { renderers.getValue(screen.id)(screen) } }
            stack.filter { it.layer != Layer.SCREEN }.sortedWith(compareBy<UIEntry> { it.layer.ordinal }.thenBy { it.instance }).forEach { item ->
                key(item.instance) { renderers.getValue(item.id)(item) }
            }
        }
    }
    companion object {
        fun mount(activity: ComponentActivity, content: @Composable () -> Unit) { activity.setContent { MaterialTheme { content() } } }
    }
}
