package appbase.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import appbase.*
import appbase.ui.UIManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lateinit var ui: UIManager
        ui = UIManager("home", mapOf("home" to Layer.SCREEN, "detail" to Layer.SCREEN, "confirm" to Layer.MODAL), mapOf(
            "home" to { _ -> DemoHome({ ui.navigation.open("detail") }, { ui.navigation.open("confirm") }) },
            "detail" to { _ -> DemoTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) { TextButton(onClick = { ui.navigation.back() }) { Text("‹ 返回") }; Text("详情", style = MaterialTheme.typography.headlineMedium); Text("这是纯 UI 示例，不包含代理功能。") } } },
            "confirm" to { entry -> AlertDialog(onDismissRequest = { ui.navigation.close(entry.instance) }, title = { Text("添加节点") }, text = { Text("仅演示弹窗和返回栈，不创建真实连接。") }, confirmButton = { TextButton(onClick = { ui.navigation.close(entry.instance) }) { Text("确定") } }) }
        ))
        ui.mount(this)
    }
}
@Composable private fun DemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff3295df), background = Color.Black, surface = Color(0xff1c1c1e))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
@Composable private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp), content = content)
}
@Composable private fun Item(icon: String, title: String, subtitle: String = "", value: String = "", action: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) { Text(title); if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        Text(value, color = Color.Gray); Spacer(Modifier.width(8.dp)); Text("›", color = Color.Gray)
    }
}
@Composable private fun DemoHome(detail: () -> Unit, modal: () -> Unit) = DemoTheme {
    var tab by rememberSaveable { mutableStateOf(0) }; var connected by rememberSaveable { mutableStateOf(false) }; var expanded by rememberSaveable { mutableStateOf(true) }; var selected by rememberSaveable { mutableStateOf("Tokyo-01") }
    val tabs = listOf("首页", "配置", "数据", "设置")
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (tab == 0) "Appbase Demo" else tabs[tab], style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); if (tab == 0) TextButton(onClick = modal) { Text("＋") } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            when(tab) {
                0 -> { Group { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (connected) "已连接（模拟）" else "未连接", modifier = Modifier.weight(1f)); Switch(connected, { connected = it }) }; HorizontalDivider(); Item("◎", "全局路由", value = "配置", action = detail); HorizontalDivider(); Item("◉", "连通性测试", value = "模拟", action = detail) }; Group { Item(if (expanded) "⌄" else "›", "本地节点", action = { expanded = !expanded }); if (expanded) listOf("Tokyo-01", "HongKong-01", "Singapore-01").forEachIndexed { i, name -> HorizontalDivider(); Item(if (selected == name) "●" else "○", name, "Demo / TCP", listOf("32 ms", "48 ms", "76 ms")[i]) { selected = name } } }; Group { Item("⌄", "订阅节点", "示例分组", action = detail) } }
                1 -> Group { listOf("默认配置", "直连模式", "代理模式").forEach { Item("▤", it, action = detail); HorizontalDivider() }; Item("＋", "添加配置", action = modal) }
                2 -> Group { Item("↗", "上传", value = "12 MB", action = detail); HorizontalDivider(); Item("↘", "下载", value = "86 MB", action = detail); Text("模拟数据", Modifier.padding(vertical = 16.dp), color = Color.Gray) }
                3 -> { Group { Item("◎", "语言", value = "简体中文", action = detail) }; Text("高级", color = Color.Gray); Group { listOf("延迟测试方法", "小组件", "捷径", "诊断", "外观").forEach { Item("◇", it, action = detail); HorizontalDivider() } } }
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { tabs.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("⌂", "▤", "∿", "⚙")[index]) }, label = { Text(label) }) } }
    }
}
