package appbase.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun ScreenHost(holder:SaveableStateHolder, screenKey:String, content:@Composable (Modifier)->Unit) {
    key(screenKey) {
        holder.SaveableStateProvider(screenKey) {
            val opacity=remember { Animatable(0f) }
            LaunchedEffect(Unit) { opacity.animateTo(1f,tween(160)) }
            content(Modifier.graphicsLayer { alpha=opacity.value })
        }
    }
}
