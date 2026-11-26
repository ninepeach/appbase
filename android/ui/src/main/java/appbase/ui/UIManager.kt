package appbase.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import appbase.*

/**
 * Owns a bounded navigation stack and renders one screen plus ordered presentation layers.
 *
 * Construction is O(n). Navigation operations are delegated to [Navigation]. [Content] may
 * block only while composables supplied by the caller block. All calls must run on the main
 * thread. Removed destinations also lose their saved form state.
 */
class UIManager(private val root: String, definitions: Map<String, Layer>, private val renderers: Map<String, @Composable (UIEntry) -> Unit>) {
    val navigation = Navigation(definitions, root)
    init { require(definitions.keys == renderers.keys) }
    fun mount(activity: ComponentActivity) { activity.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Content() } } } }
    @Composable fun Content() {
        val stack by navigation.stack.collectAsState()
        val holder = rememberSaveableStateHolder()
        var previous by remember { mutableStateOf(emptySet<Long>()) }
        LaunchedEffect(stack) {
            val current = stack.map { it.instance }.toSet()
            (previous - current).forEach { holder.removeState(it.toString()) }
            previous = current
        }
        BackHandler(enabled = stack.drop(1).any { it.layer != Layer.TOAST }) { navigation.back() }
        Box(Modifier.fillMaxSize()) {
            val screen = stack.last { it.layer == Layer.SCREEN }
            ScreenHost(holder,screen.instance.toString()) { transition -> Box(Modifier.fillMaxSize().then(transition)) { renderers.getValue(screen.id)(screen) } }
            stack.filter { it.layer != Layer.SCREEN }.sortedWith(compareBy<UIEntry> { it.layer.ordinal }.thenBy { it.instance }).forEach { item ->
                key(item.instance) { renderers.getValue(item.id)(item) }
            }
        }
    }
    companion object {
        /**
         * Hosts independent page stacks and a shared presentation queue. Keep roots and page IDs
         * stable across recreation. Page state survives switching roots and is discarded on pop.
         * Transient presentations are cleared when the host leaves composition.
         */
        @Composable fun Pages(
            roots: List<String>,
            pages: Set<String>,
            content: @Composable (PageSession, PresentationQueue, SnackbarHostState) -> Unit,
        ) = Presentation { queue, snackbar ->
            require(roots.isNotEmpty() && roots.distinct().size == roots.size && roots.all { it in pages })
            val saver = remember(roots, pages) {
                Saver<List<Navigation>, ArrayList<ArrayList<String>>>(
                    save = { stacks -> ArrayList(stacks.map { nav -> ArrayList(nav.stack.value.map { it.id }) }) },
                    restore = { saved -> roots.mapIndexed { index, root ->
                        Navigation(pages.associateWith { Layer.SCREEN }, root).apply {
                            saved.getOrNull(index)?.takeIf { it.firstOrNull() == root }
                                ?.drop(1)?.takeWhile { it in pages }?.take(127)?.forEach { push(it) }
                        }
                    } },
                )
            }
            val stacks = rememberSaveable(roots, pages, saver = saver) {
                roots.map { Navigation(pages.associateWith { Layer.SCREEN }, it) }
            }
            var selected by rememberSaveable { mutableIntStateOf(0) }
            val index = selected.takeIf { it in roots.indices } ?: 0
            val nav = stacks[index]
            val stack by nav.stack.collectAsState()
            val holder = rememberSaveableStateHolder()
            val session = PageSession(index, stack, nav, holder) { next ->
                require(next in roots.indices) { "Unknown page root index: $next" }
                selected = next
            }
            BackHandler(enabled = stack.size > 1 || index != 0) {
                if (nav.stack.value.size > 1) session.back() else session.selectRoot(0)
            }
            content(session, queue, snackbar)
        }

        /** Owns transient presentations for one mounted composition. */
        @Composable fun Presentation(content: @Composable (PresentationQueue, SnackbarHostState) -> Unit) {
            val queue = remember { PresentationQueue() }
            val snackbar = remember { SnackbarHostState() }
            DisposableEffect(queue) { onDispose { queue.clear() } }
            content(queue, snackbar)
            PresentationHost(queue, snackbar)
        }
        fun mount(activity: ComponentActivity, content: @Composable () -> Unit) { activity.setContent { MaterialTheme { content() } } }
    }

    /** Main-thread page controls for the active root. Opening and closing copy its bounded stack. */
    class PageSession internal constructor(
        val rootIndex: Int,
        val stack: List<UIEntry>,
        private val navigation: Navigation,
        private val holder: SaveableStateHolder,
        private val select: (Int) -> Unit,
    ) {
        val page: String get() = stack.last().id
        fun selectRoot(index: Int) = select(index)
        fun open(id: String) { navigation.push(id) }
        fun back() {
            val depth = navigation.stack.value.size
            if (navigation.pop()) holder.removeState("$rootIndex:$depth")
        }

        @Composable fun Content(content: @Composable (Modifier) -> Unit) {
            ScreenHost(holder, "$rootIndex:${stack.size}", content)
        }
    }
}
