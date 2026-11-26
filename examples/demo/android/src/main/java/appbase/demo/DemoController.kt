package appbase.demo

internal class DemoController(private val model:DemoModel) {
    fun log(message:String) {
        model.logs.add(0,message)
        while(model.logs.size>100) model.logs.removeAt(model.logs.lastIndex)
    }
    fun sortNodes() {
        val sorted=model.nodes.sortedBy{it.name}
        model.nodes.clear()
        model.nodes.addAll(sorted)
    }
    fun moveNode(id:Int,delta:Int) {
        val index=model.nodes.indexOfFirst{it.id==id}
        val target=index+delta
        if(index>=0 && target in model.nodes.indices) {
            val node=model.nodes.removeAt(index)
            model.nodes.add(target,node)
        }
    }
}
