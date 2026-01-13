package domain

/**
 * Описание одного узла графа, ровно под decode_single_node() на STM32:
 *
 *  id          – уникальный ID узла (0..N-1)
 *  type        – см. PLC_NODE_* в plc.h
 *  valueType   – см. PLC_VAL_*
 *  inA / inB   – входы (ID других узлов или -1)
 *  paramInt    – целочисленный параметр (например номер DI/DO)
 *  paramFloat  – float-параметр
 *  paramMs     – параметр-время в миллисекундах
 *  flags       – флаги (PLC_NODE_FLAG_*)
 */
data class PlcGraphNodeDef(
    val id: Int,
    val type: PlcNodeType,
    val valueType: Int,
    val inA: Int = -1,
    val inB: Int = -1,
    val paramInt: Int = 0,
    val paramFloat: Float = 0f,
    val paramMs: Int = 0,
    val flags: Int = 0
)

/**
 * Целый граф для stm32: период цикла + список узлов.
 * На стороне прошивки это разбирает decode_graph().
 */
data class PlcGraphDef(
    val cycleMs: Int,
    val nodes: List<PlcGraphNodeDef>
)

/**
 * Ответ на upload_graph_ack.
 */
data class UploadGraphResult(
    val ok: Boolean,
    val stagingNodeCount: Int,
    val stagingCycleMs: Int
)

/**
 * Ответ на activate_graph_ack.
 */
data class ActivateGraphResult(
    val ok: Boolean,
    val active: Boolean,
    val cycleMs: Int,
    val nodesCount: Int
)
