package appbase.demo

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver

internal data class Node(val id:Int,val name:String,val host:String,val port:String,val protocol:String,val group:String="Local")

internal class DemoModel {
    val nodes=mutableStateListOf(Node(1,"Tokyo 01","tokyo.example","443","VLESS"),Node(2,"Hong Kong 01","hk.example","443","Shadowsocks"),Node(3,"Singapore 01","sg.example","443","Trojan"))
    val configs=mutableStateListOf("Default","Direct","Proxy")
    val configBodies=mutableStateMapOf<String,String>()
    val subscriptions=mutableStateListOf<String>()
    val logs=mutableStateListOf("Demo initialized")
    val options=mutableStateMapOf<String,Boolean>()
    var nextId by mutableIntStateOf(4)
    var selected by mutableIntStateOf(1)
    var editing by mutableStateOf<Node?>(null)
    var configEdit by mutableStateOf("")
    var activeConfig by mutableStateOf("Default")
    companion object {
        val saver=Saver<DemoModel,Bundle>(save={ m -> Bundle().apply {
            putStringArrayList("nodes",ArrayList(m.nodes.flatMap{listOf(it.id.toString(),it.name,it.host,it.port,it.protocol,it.group)}))
            putStringArrayList("configs",ArrayList(m.configs))
            putStringArrayList("bodies",ArrayList(m.configBodies.flatMap{listOf(it.key,it.value)}))
            putStringArrayList("subscriptions",ArrayList(m.subscriptions))
            putStringArrayList("logs",ArrayList(m.logs.take(100)))
            putStringArrayList("options",ArrayList(m.options.flatMap{listOf(it.key,it.value.toString())}))
            putInt("next",m.nextId);putInt("selected",m.selected);putInt("editing",m.editing?.id ?: -1)
            putString("configEdit",m.configEdit);putString("active",m.activeConfig)
        }},restore={ b -> DemoModel().apply {
            nodes.clear();b.getStringArrayList("nodes").orEmpty().chunked(6).filter{it.size==6}.forEach{nodes.add(Node(it[0].toInt(),it[1],it[2],it[3],it[4],it[5]))}
            configs.clear();configs.addAll(b.getStringArrayList("configs").orEmpty())
            b.getStringArrayList("bodies").orEmpty().chunked(2).filter{it.size==2}.forEach{configBodies[it[0]]=it[1]}
            subscriptions.addAll(b.getStringArrayList("subscriptions").orEmpty())
            logs.clear();logs.addAll(b.getStringArrayList("logs").orEmpty())
            b.getStringArrayList("options").orEmpty().chunked(2).filter{it.size==2}.forEach{options[it[0]]=it[1].toBoolean()}
            nextId=b.getInt("next",4);selected=b.getInt("selected",1);editing=nodes.firstOrNull{it.id==b.getInt("editing",-1)}
            configEdit=b.getString("configEdit").orEmpty();activeConfig=b.getString("active").orEmpty()
        }})
    }
}
