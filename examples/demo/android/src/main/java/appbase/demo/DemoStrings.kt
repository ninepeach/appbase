package appbase.demo

internal fun demoLabel(key:String,language:String):String {
    val locale=if(language=="System") java.util.Locale.getDefault().language else language
    val index=when(locale){"繁體中文"->0;"简体中文","zh"->1;"日本語","ja"->2;else->return key}
    return mapOf(
        "Home" to listOf("首頁","首页","ホーム"),
        "Config" to listOf("配置","配置","設定ファイル"),
        "Data" to listOf("數據","数据","データ"),
        "Settings" to listOf("設定","设置","設定"),
        "Appearance" to listOf("外觀","外观","外観"),
        "Language" to listOf("語言","语言","言語"),
        "Node editor" to listOf("節點編輯","节点编辑","ノード編集"),
        "Routing" to listOf("路由","路由","ルーティング"),
        "Global routing" to listOf("全局路由","全局路由","グローバルルーティング"),
        "Subscriptions" to listOf("訂閱","订阅","購読"),
        "Configuration editor" to listOf("配置編輯","配置编辑","設定編集"),
        "Rules" to listOf("規則","规则","ルール"),
        "Traffic" to listOf("流量","流量","通信量"),
        "Connections" to listOf("連線","连接","接続"),
        "Logs" to listOf("日誌","日志","ログ"),
        "Notifications" to listOf("通知","通知","通知"),
        "Privacy" to listOf("私隱","隐私","プライバシー"),
        "Advanced" to listOf("進階","高级","詳細設定"),
        "Diagnostics" to listOf("診斷","诊断","診断"),
        "About" to listOf("關於","关于","情報"),
        "Import" to listOf("匯入","导入","インポート"),
        "Onboarding" to listOf("使用指南","使用指南","ガイド")
    )[key]?.get(index) ?: key
}
