package helper

import domain.PlcGraphDef
import domain.PlcGraphNodeDef
import domain.PlcNodeType
import java.util.*

object GraphCompiler {

    /**
     * Вход (web/JSON):
     * - nodes[] могут быть в любом порядке
     * - id могут быть любыми (уникальными)
     * - inA/inB ссылаются на id (или -1)
     * - для MUX2: paramInt ссылается на id узла-селектора (BOOL), sel берём из node[paramInt]
     *
     * Выход (для STM32):
     * - nodes[] упорядочены топологически (все зависимости раньше)
     * - nodes[i].id == i
     * - inA/inB переписаны в индексы
     * - MUX2.paramInt переписан в индекс селектора
     */
    fun compileForPlc(src: PlcGraphDef): PlcGraphDef {
        val original = src.nodes
        if (original.isEmpty()) return src

        // 1) уникальность id
        val ids = original.map { it.id }
        val dup = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(dup.isEmpty()) { "Duplicate node id(s): $dup" }

        // 2) id -> node
        val byId: Map<Int, PlcGraphNodeDef> = original.associateBy { it.id }

        // 3) граф зависимостей: dep -> node (dep должен быть раньше node)
        val deps: MutableMap<Int, MutableSet<Int>> = linkedMapOf()      // nodeId -> set(depIds)
        val reverse: MutableMap<Int, MutableSet<Int>> = linkedMapOf()   // depId -> set(nodes depending on it)

        fun addEdge(depId: Int, nodeId: Int) {
            if (depId == nodeId) throw IllegalArgumentException("Self-cycle at nodeId=$nodeId")
            deps.getOrPut(nodeId) { linkedSetOf() }.add(depId)
            reverse.getOrPut(depId) { linkedSetOf() }.add(nodeId)
        }

        // init
        for (n in original) {
            deps.putIfAbsent(n.id, linkedSetOf())
            reverse.putIfAbsent(n.id, linkedSetOf())
        }

        // build edges
        for (n in original) {
            // inA
            if (n.inA >= 0) {
                require(byId.containsKey(n.inA)) { "Node id=${n.id}: inA points to unknown nodeId=${n.inA}" }
                addEdge(n.inA, n.id)
            }
            // inB
            if (n.inB >= 0) {
                require(byId.containsKey(n.inB)) { "Node id=${n.id}: inB points to unknown nodeId=${n.inB}" }
                addEdge(n.inB, n.id)
            }
            // MUX2 selector (paramInt = selector nodeId)
            if (n.type == PlcNodeType.MUX2) {
                val selId = n.paramInt
                require(byId.containsKey(selId)) {
                    "MUX2 nodeId=${n.id}: selector paramInt points to unknown nodeId=$selId"
                }
                addEdge(selId, n.id)
            }
        }

        // 4) Kahn topo sort (детерминированно: сначала меньший id)
        val inDeg: MutableMap<Int, Int> = deps.mapValues { it.value.size }.toMutableMap()
        val ready = PriorityQueue<Int>() // smallest id first
        for ((id, d) in inDeg) if (d == 0) ready.add(id)

        val orderedIds = ArrayList<Int>(original.size)
        while (ready.isNotEmpty()) {
            val id = ready.poll()
            orderedIds.add(id)

            for (to in reverse[id].orEmpty()) {
                val nd = (inDeg[to] ?: 0) - 1
                inDeg[to] = nd
                if (nd == 0) ready.add(to)
            }
        }

        if (orderedIds.size != original.size) {
            val remaining = inDeg.filterValues { it > 0 }.keys.sorted()
            throw IllegalArgumentException("Graph has cycle(s). Remaining nodeIds: $remaining")
        }

        // 5) oldId -> newIndex
        val idToIndex: Map<Int, Int> = orderedIds.mapIndexed { idx, oldId -> oldId to idx }.toMap()

        // 6) собрать compiled nodes: id=index, ссылки = index
        val compiledNodes = orderedIds.mapIndexed { newIdx, oldId ->
            val n = byId.getValue(oldId)

            val inAIdx = remapRef(n.inA, idToIndex, field = "inA", nodeId = n.id)
            val inBIdx = remapRef(n.inB, idToIndex, field = "inB", nodeId = n.id)

            val paramIntRemapped =
                if (n.type == PlcNodeType.MUX2) {
                    val selId = n.paramInt
                    idToIndex[selId]
                        ?: throw IllegalArgumentException("MUX2 nodeId=${n.id}: selector not found after remap selId=$selId")
                } else {
                    n.paramInt
                }

            n.copy(
                id = newIdx,
                inA = inAIdx,
                inB = inBIdx,
                paramInt = paramIntRemapped
            )
        }

        // 7) жёсткая проверка под STM32: входы только назад
        for (i in compiledNodes.indices) {
            val n = compiledNodes[i]
            if (n.inA >= 0 && n.inA >= i) throw IllegalStateException("Compile error: node[$i] inA=${n.inA} points forward")
            if (n.inB >= 0 && n.inB >= i) throw IllegalStateException("Compile error: node[$i] inB=${n.inB} points forward")
            if (n.type == PlcNodeType.MUX2) {
                val sel = n.paramInt
                if (sel < 0 || sel >= i) throw IllegalStateException("Compile error: node[$i] MUX2 selector(paramInt)=$sel points forward")
            }
        }

        return src.copy(nodes = compiledNodes)
    }

    private fun remapRef(inputId: Int, idToIndex: Map<Int, Int>, field: String, nodeId: Int): Int {
        if (inputId < 0) return -1
        return idToIndex[inputId]
            ?: throw IllegalArgumentException("Node id=$nodeId: $field points to unknown nodeId=$inputId")
    }
}
