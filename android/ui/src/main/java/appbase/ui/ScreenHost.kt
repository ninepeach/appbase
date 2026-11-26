package appbase.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
public fun ScreenHost(holder:SaveableStateHolder, screenKey:String, content:@Composable (Modifier)->Unit) {
    key(screenKey) {
        holder.SaveableStateProvider(screenKey) {
            val progress=remember { Animatable(0f) }
            LaunchedEffect(Unit) { progress.animateTo(1f,tween(180)) }
            content(Modifier.graphicsLayer {
                alpha=progress.value
                translationX=(1f-progress.value)*24f
                scaleX=.985f+progress.value*.015f
                scaleY=.985f+progress.value*.015f
                transformOrigin=TransformOrigin.Center
            })
        }
    }
}
