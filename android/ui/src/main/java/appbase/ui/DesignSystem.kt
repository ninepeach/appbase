package appbase.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public object UISpacing {
    public val xSmall: Dp = 4.dp
    public val small: Dp = 8.dp
    public val regular: Dp = 12.dp
    public val medium: Dp = 16.dp
    public val large: Dp = 24.dp
}

public object UIMetrics {
    public val contentMaxWidth: Dp = 760.dp
    public val rowMinHeight: Dp = 52.dp
    public val cardRadius: Dp = 14.dp
}

@Composable public fun UIPage(modifier:Modifier=Modifier, content:@Composable ColumnScope.()->Unit) {
    Box(modifier.fillMaxSize(), contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=UIMetrics.contentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(UISpacing.medium), verticalArrangement=Arrangement.spacedBy(UISpacing.medium), content=content)
    }
}

@Composable public fun UIGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f),RoundedCornerShape(UIMetrics.cardRadius)).padding(horizontal=UISpacing.regular),content=content)
}

@Composable public fun UIRow(title:String,value:String="",action:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=action).heightIn(min=UIMetrics.rowMinHeight).padding(vertical=UISpacing.regular),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f))
        if(value.isNotEmpty()) Text(value,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("›", style=MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider()
}

@Composable public fun UIChoice(title:String,selected:Boolean,action:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=action),verticalAlignment=Alignment.CenterVertically) {
        RadioButton(selected,action)
        Text(title)
    }
}

public enum class ContentStatus { READY, LOADING, EMPTY, ERROR }

@Composable public fun UIContent(status:ContentStatus,message:String="",retryLabel:String="Retry",retry:(()->Unit)?=null,content:@Composable ()->Unit) {
    if(status==ContentStatus.READY) { content(); return }
    Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(status==ContentStatus.LOADING) CircularProgressIndicator()
        if(message.isNotEmpty()) Text(message,style=MaterialTheme.typography.bodyMedium)
        if(status==ContentStatus.ERROR && retry!=null) OutlinedButton(onClick=retry){Text(retryLabel)}
    }
}

@Composable public fun UISectionTitle(text:String) {
    Text(text, style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.primary, modifier=Modifier.padding(start=UISpacing.xSmall))
}
