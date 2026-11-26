package appbase.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object UISpacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}

@Composable fun UIGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f),RoundedCornerShape(12.dp)).padding(horizontal=12.dp),content=content)
}

@Composable fun UIRow(title:String,value:String="",action:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=action).heightIn(min=52.dp).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f))
        if(value.isNotEmpty()) Text(value,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("›")
    }
    HorizontalDivider()
}

@Composable fun UIChoice(title:String,selected:Boolean,action:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=action),verticalAlignment=Alignment.CenterVertically) {
        RadioButton(selected,action)
        Text(title)
    }
}

enum class ContentStatus { READY, LOADING, EMPTY, ERROR }

@Composable fun UIContent(status:ContentStatus,message:String="",retryLabel:String="Retry",retry:(()->Unit)?=null,content:@Composable ()->Unit) {
    if(status==ContentStatus.READY) { content(); return }
    Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(status==ContentStatus.LOADING) CircularProgressIndicator()
        if(message.isNotEmpty()) Text(message,style=MaterialTheme.typography.bodyMedium)
        if(status==ContentStatus.ERROR && retry!=null) OutlinedButton(onClick=retry){Text(retryLabel)}
    }
}
