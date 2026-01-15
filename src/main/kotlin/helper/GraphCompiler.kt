package helper

import domain.PlcGraphDef

object GraphCompiler {

    /**
     * ВХОД (из web): inA/inB считаются как nodeId (id узла), -1 = не подключено
     *ЕЗУЛЬТАТ (для PLC): inA/inB будут nodeIndex (позиция в nodes[])
     */
    fun compileForPlc(src: PlcGraphDef): PlcGraphDef {
        // 1) порядок для PLC: оставляем как пришло (стабильно и предсказуемо)
        val nodes = src.nodes

        // 2) id -> index (позиция в массиве)
        val idToIndex = nodes.mapIndexed { idx, n -> n.id to idx }.toMap()

        // 3) переписываем входы
        val compiledNodes = nodes.map { n ->
            n.copy(
                inA = remapInput(n.inA, idToIndex),
                inB = remapInput(n.inB, idToIndex)
            )
        }

        return src.copy(nodes = compiledNodes)
    }

    private fun remapInput(inputId: Int, idToIndex: Map<Int, Int>): Int {
        if (inputId < 0) return -1
        return idToIndex[inputId]
            ?: throw IllegalArgumentException("Graph input points to unknown nodeId=$inputId")
    }
}
