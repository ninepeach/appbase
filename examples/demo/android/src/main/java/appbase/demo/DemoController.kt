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
    fun moveNode(id:Int,delta:Int, visibleIds: List<Int>) {
        val index=model.nodes.indexOfFirst{it.id==id}
        val visibleIndex = visibleIds.indexOf(id)
        if (visibleIndex < 0) return
        val targetId = visibleIds.getOrNull(visibleIndex + delta) ?: return
        val target = model.nodes.indexOfFirst { it.id == targetId }
        if(index>=0 && target in model.nodes.indices) {
            val node=model.nodes.removeAt(index)
            model.nodes.add(target,node)
        }
    }

    fun saveNode(id: Int?, name: String, host: String, port: String, protocol: String, group: String) {
        val node = Node(id ?: model.nextId++, name.trim(), host.trim(), port, protocol, group.trim().ifBlank { "Local" })
        val index = model.nodes.indexOfFirst { it.id == node.id }
        if (index < 0) model.nodes.add(node) else model.nodes[index] = node
    }

    fun saveConfiguration(original: String, name: String, body: String) {
        val target = name.trim()
        require(target.isNotEmpty() && (target == original || target !in model.configs)) { "Invalid or duplicate configuration name" }
        val index = model.configs.indexOf(original)
        if (index < 0) model.configs.add(target) else model.configs[index] = target
        if (original != target) model.configBodies.remove(original)
        model.configBodies[target] = body
        model.activeConfig = target
    }

    fun deleteConfiguration(name: String) {
        model.configs.remove(name)
        model.configBodies.remove(name)
        if (model.activeConfig == name) model.activeConfig = model.configs.firstOrNull().orEmpty()
    }

    fun deleteNodes(ids:Set<Int>) {
        if (ids.isEmpty()) return
        model.nodes.removeAll { it.id in ids }
        if (model.selected in ids) model.selected=model.nodes.firstOrNull()?.id ?: -1
        log("Nodes deleted")
    }

    fun moveNodesToGroup(ids:Set<Int>,group:String) {
        require(group.isNotBlank()) { "Group must not be blank" }
        model.nodes.replaceAll { if (it.id in ids) it.copy(group=group.trim()) else it }
        log("Nodes moved to group")
    }
}
