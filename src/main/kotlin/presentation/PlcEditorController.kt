package presentation


import data.GraphJsonCodec
import data.PlcProtocol
import domain.ActivateGraphResult
import domain.PlcGraphDef
import domain.PlcGraphNodeDef
import domain.PlcNodeType
import domain.UploadGraphResult
import com.google.gson.Gson
import data.db.GraphTemplate
import data.db.GraphTemplateRepository
import data.db.templates.TemplateSeederJvm
import helper.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-модель одного узла в редакторе.
 * Всё в виде строк, чтобы удобно редактировать в TextField.
 */
data class EditorNodeUi(
    val localId: Int,
    val type: PlcNodeType,
    val valueType: Int,
    val inA: String = "-1",
    val inB: String = "-1",
    val paramInt: String = "0",
    val paramFloat: String = "0.0",
    val paramMs: String = "0",
    val flags: String = "0"
)

data class PlcEditorUiState(
    val cycleMs: String = "10",
    val nodes: List<EditorNodeUi> = emptyList(),

    // выбранный узел в UI (по localId)
    val selectedNodeId: Int? = null,

    val isUploading: Boolean = false,
    val isActivating: Boolean = false,
    val lastUpload: UploadGraphResult? = null,
    val lastActivate: ActivateGraphResult? = null,
    val errorMessage: String? = null
)

class PlcEditorController(
    private val scope: CoroutineScope,
    private val templates: GraphTemplateRepository,
    private val seeder: TemplateSeederJvm
) {


    private val _uiState = MutableStateFlow(PlcEditorUiState())
    val uiState: StateFlow<PlcEditorUiState> = _uiState

    private data class EditorSnapshot(
        val nodes: List<EditorNodeUi>,
        val selectedNodeId: Int?,
        val cycleMs: String
    )

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private val MAX_HISTORY = 30

    private fun PlcEditorUiState.toSnapshot(): EditorSnapshot =
        EditorSnapshot(
            nodes = this.nodes,
            selectedNodeId = this.selectedNodeId,
            cycleMs = this.cycleMs
        )

    private fun restoreFromSnapshot(snapshot: EditorSnapshot) {
        val current = _uiState.value
        _uiState.value = current.copy(
            nodes = snapshot.nodes,
            selectedNodeId = snapshot.selectedNodeId,
            cycleMs = snapshot.cycleMs,
            errorMessage = null // при откате чистим ошибку
        )
    }

    private fun pushUndo() {
        val snap = _uiState.value.toSnapshot()
        undoStack.addLast(snap)
        // при любом новом действии redo обнуляем
        redoStack.clear()
        if (undoStack.size > MAX_HISTORY) {
            undoStack.removeFirst()
        }
    }


    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (undoStack.isEmpty()) return
        val currentSnap = _uiState.value.toSnapshot()
        val prevSnap = undoStack.removeLast()
        // текущий в redo
        redoStack.addLast(currentSnap)
        restoreFromSnapshot(prevSnap)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val currentSnap = _uiState.value.toSnapshot()
        val nextSnap = redoStack.removeLast()
        // текущий в undo
        undoStack.addLast(currentSnap)
        restoreFromSnapshot(nextSnap)
    }


    // ---------- Палитра: добавление узла ----------

    fun addNode(type: PlcNodeType) {
        pushUndo()
        val current = _uiState.value
        val nextId = current.nodes.size // поддерживаем диапазон 0..N-1

        val node = EditorNodeUi(
            localId = nextId,
            type = type,
            valueType = defaultValueType(type),
            // важное: HEARTBEAT и логам сразу даём ненулевой период
            paramMs = defaultParamMs(type)
        )

        _uiState.value = current.copy(
            nodes = current.nodes + node,
            selectedNodeId = nextId,
            errorMessage = null
        )
    }

    private fun defaultParamMs(type: PlcNodeType): String = when (type) {
        PlcNodeType.HEARTBEAT -> "1000"  // 1 сек по умолчанию, иначе прошивка ругнётся на 0
        PlcNodeType.LOG -> "1000"  // чтобы лог сразу что-то писал
        else -> "0"
    }


    fun selectNode(nodeId: Int) {
        val current = _uiState.value
        if (current.nodes.any { it.localId == nodeId }) {
            _uiState.value = current.copy(selectedNodeId = nodeId)
        }
    }

    fun deleteNode(nodeId: Int) {
        pushUndo()
        val current = _uiState.value
        if (current.nodes.isEmpty()) return

        // удаляем нужный узел
        val remaining = current.nodes
            .filter { it.localId != nodeId }
            .sortedBy { it.localId }

        if (remaining.isEmpty()) {
            _uiState.value = current.copy(
                nodes = emptyList(),
                selectedNodeId = null
            )
            return
        }

        // стараяID -> новаяID
        val oldToNew = remaining
            .map { it.localId }
            .sorted()
            .mapIndexed { newId, oldId -> oldId to newId }
            .toMap()

        // перенумеруем узлы и исправим ссылки inA/inB
        val remapped = remaining
            .sortedBy { it.localId }
            .mapIndexed { newId, n ->
                val newInA = n.inA.toIntOrNull()?.let { oldId ->
                    oldToNew[oldId]
                }
                val newInB = n.inB.toIntOrNull()?.let { oldId ->
                    oldToNew[oldId]
                }

                n.copy(
                    localId = newId,
                    inA = newInA?.toString() ?: "-1",
                    inB = newInB?.toString() ?: "-1"
                )
            }

        val lastIndex = remapped.lastIndex
        val newSelectedIndex = nodeId.coerceAtMost(lastIndex)
        val newSelectedId = remapped[newSelectedIndex].localId

        _uiState.value = current.copy(
            nodes = remapped,
            selectedNodeId = newSelectedId
        )
    }

    // ---------- Обновление полей узла ----------

    fun updateNode(
        nodeId: Int,
        inA: String? = null,
        inB: String? = null,
        paramInt: String? = null,
        paramFloat: String? = null,
        paramMs: String? = null,
        flags: String? = null
    ) {
        pushUndo()
        val current = _uiState.value
        val updated = current.nodes.map { n ->
            if (n.localId != nodeId) n else n.copy(
                inA = inA ?: n.inA,
                inB = inB ?: n.inB,
                paramInt = paramInt ?: n.paramInt,
                paramFloat = paramFloat ?: n.paramFloat,
                paramMs = paramMs ?: n.paramMs,
                flags = flags ?: n.flags
            )
        }
        _uiState.value = current.copy(nodes = updated)
    }

    fun updateCycleMs(value: String) {
        pushUndo()
        _uiState.value = _uiState.value.copy(cycleMs = value)
    }

    fun applyTemplateDiToDo() {
        pushUndo()
        val di = EditorNodeUi(
            localId = 0,
            type = PlcNodeType.DIGITAL_IN,
            valueType = defaultValueType(PlcNodeType.DIGITAL_IN)
        )
        val doNode = EditorNodeUi(
            localId = 1,
            type = PlcNodeType.DIGITAL_OUT,
            inA = "0",
            valueType = defaultValueType(PlcNodeType.DIGITAL_OUT)
        )

        val current = _uiState.value
        _uiState.value = current.copy(
            nodes = listOf(di, doNode),
            selectedNodeId = 1,
            cycleMs = if (current.cycleMs.isBlank()) "10" else current.cycleMs,
            errorMessage = null
        )
    }

    fun applyTemplateDiTonDo() {
        pushUndo()
        val di = EditorNodeUi(
            localId = 0,
            type = PlcNodeType.DIGITAL_IN,
            valueType = defaultValueType(PlcNodeType.DIGITAL_IN)
        )
        val ton = EditorNodeUi(
            localId = 1,
            type = PlcNodeType.TON,
            inA = "0",
            paramMs = "1000",
            valueType = defaultValueType(PlcNodeType.TON)
        )
        val doNode = EditorNodeUi(
            localId = 2,
            type = PlcNodeType.DIGITAL_OUT,
            inA = "1",
            valueType = defaultValueType(PlcNodeType.DIGITAL_OUT)
        )

        val current = _uiState.value
        _uiState.value = current.copy(
            nodes = listOf(di, ton, doNode),
            selectedNodeId = 2,
            cycleMs = current.cycleMs.ifBlank { "10" },
            errorMessage = null
        )
    }

    fun applyTemplateAiToPwm() {
        pushUndo()
        val ai = EditorNodeUi(
            localId = 0,
            type = PlcNodeType.AI_IN,
            valueType = defaultValueType(PlcNodeType.AI_IN)
        )
        val pwm = EditorNodeUi(
            localId = 1,
            type = PlcNodeType.PWM_OUT,
            inA = "0",
            valueType = defaultValueType(PlcNodeType.PWM_OUT)
        )

        val current = _uiState.value
        _uiState.value = current.copy(
            nodes = listOf(ai, pwm),
            selectedNodeId = 1,
            cycleMs = if (current.cycleMs.isBlank()) "10" else current.cycleMs,
            errorMessage = null
        )
    }

    fun applyTemplateAiFilterMathAoMem() {
        pushUndo()

        // 0: AI_IN (аналоговый вход, канал 0)
        val ai = EditorNodeUi(
            localId = 0,
            type = PlcNodeType.AI_IN,
            valueType = defaultValueType(PlcNodeType.AI_IN)
        )

        // 1: FILTER_AVG (экспоненциальный фильтр, alpha = 0.5)
        val filter = EditorNodeUi(
            localId = 1,
            type = PlcNodeType.FILTER_AVG,
            inA = "0",          // берём из AI (id=0)
            paramInt = "500",   // alpha*1000 = 500 => 0.5
            valueType = defaultValueType(PlcNodeType.FILTER_AVG)
        )

        // 2: CONST_FLOAT = 2.0 (коэффициент усиления)
        val kConst = EditorNodeUi(
            localId = 2,
            type = PlcNodeType.CONST_FLOAT,
            paramFloat = "2.0",
            valueType = defaultValueType(PlcNodeType.CONST_FLOAT)
        )

        // 3: MATH_OP (умножение: out = filter * 2.0)
        // paramInt: 0=ADD, 1=SUB, 2=MUL, 3=DIV
        val math = EditorNodeUi(
            localId = 3,
            type = PlcNodeType.MATH_OP,
            inA = "1",          // FILTER_AVG
            inB = "2",          // CONST_FLOAT
            paramInt = "2",     // MUL
            valueType = defaultValueType(PlcNodeType.MATH_OP)
        )

        // 4: AO (аналоговый выход, канал 0)
        val ao = EditorNodeUi(
            localId = 4,
            type = PlcNodeType.AO,
            inA = "3",          // MATH_OP
            paramInt = "0",     // номер AO-канала (под своё железо)
            valueType = defaultValueType(PlcNodeType.AO)
        )

        // 5: MEM_REAL[0] (запоминаем результат)
        val mem = EditorNodeUi(
            localId = 5,
            type = PlcNodeType.MEM_REAL,
            inA = "3",          // тот же результат MATH_OP
            paramInt = "0",     // индекс MEM_REAL[0]
            valueType = defaultValueType(PlcNodeType.MEM_REAL)
        )

        val current = _uiState.value
        _uiState.value = current.copy(
            nodes = listOf(ai, filter, kConst, math, ao, mem),
            selectedNodeId = 5,
            cycleMs = current.cycleMs.ifBlank { "10" },
            errorMessage = null
        )
    }


    // ---------- Построение PlcGraphDef ----------

    private fun buildGraph(): PlcGraphDef {
        val s = _uiState.value

        val cycle = s.cycleMs.toIntOrNull()?.coerceAtLeast(1) ?: 10

        // сортируем по localId
        val sorted = s.nodes.sortedBy { it.localId }

        // карта localId -> индекс в массиве графа
        val idMap = sorted
            .mapIndexed { index, n -> n.localId to index }
            .toMap()

        val nodes = sorted.mapIndexed { index, n ->
            PlcGraphNodeDef(
                id = index,
                type = n.type,
                valueType = n.valueType,
                inA = n.inA.toIntOrNull()?.let { idMap[it] } ?: -1,
                inB = n.inB.toIntOrNull()?.let { idMap[it] } ?: -1,
                paramInt = n.paramInt.toIntOrNull() ?: 0,
                paramFloat = n.paramFloat.toFloatOrNull() ?: 0f,
                paramMs = n.paramMs.toIntOrNull() ?: 0,
                flags = n.flags.toIntOrNull() ?: 0
            )
        }

        return PlcGraphDef(
            cycleMs = cycle,
            nodes = nodes
        )
    }

    // ---------- upload_graph ----------

    fun uploadGraph() {
        // 1. Берём текущее состояние
        val ui = _uiState.value

        // 2. Валидируем граф
        val errors = validateGraph(ui)
        if (errors.isNotEmpty()) {
            _uiState.value = ui.copy(
                isUploading = false,
                errorMessage = errors.joinToString("\n")
            )
            return
        }

        // 3. Если ошибок нет — запускаем upload
        scope.launch {
            val prev = _uiState.value
            _uiState.value = prev.copy(
                isUploading = true,
                errorMessage = null
            )
            try {
                val graph = buildGraph()
                val res = PlcProtocol.uploadGraph(graph)
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    lastUpload = res,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    errorMessage = e.message ?: "Ошибка upload_graph"
                )
            }
        }
    }


    // ---------- activate_graph ----------

    fun activateGraph() {
        scope.launch {
            val prev = _uiState.value
            _uiState.value = prev.copy(isActivating = true, errorMessage = null)
            try {
                val res = PlcProtocol.activateGraph()
                _uiState.value = _uiState.value.copy(
                    isActivating = false,
                    lastActivate = res,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isActivating = false,
                    errorMessage = e.message ?: "Ошибка activate_graph"
                )
            }
        }
    }

    // ---------- Вспомогательное ----------

    private fun defaultValueType(type: PlcNodeType): Int =
        when (type) {
            // --- константы ---
            PlcNodeType.CONST_BOOL -> PlcValueType.BOOL
            PlcNodeType.CONST_INT -> PlcValueType.INT
            PlcNodeType.CONST_FLOAT -> PlcValueType.FLOAT

            // --- дискретный тракт / логика / флаги / алармы ---
            PlcNodeType.DIGITAL_IN -> PlcValueType.BOOL
            PlcNodeType.DIGITAL_OUT -> PlcValueType.BOOL
            PlcNodeType.AND2 -> PlcValueType.BOOL
            PlcNodeType.OR2 -> PlcValueType.BOOL
            PlcNodeType.NOT -> PlcValueType.BOOL
            PlcNodeType.SR -> PlcValueType.BOOL
            PlcNodeType.TON -> PlcValueType.BOOL
            PlcNodeType.TOFF -> PlcValueType.BOOL
            PlcNodeType.R_TRIG -> PlcValueType.BOOL
            PlcNodeType.F_TRIG -> PlcValueType.BOOL
            PlcNodeType.COMPARE_GT -> PlcValueType.BOOL
            PlcNodeType.COMPARE_LT -> PlcValueType.BOOL
            PlcNodeType.TP -> PlcValueType.BOOL
            PlcNodeType.HYST -> PlcValueType.BOOL
            PlcNodeType.WINDOW_CHECK -> PlcValueType.BOOL
            PlcNodeType.SAFE_OUTPUT -> PlcValueType.BOOL
            PlcNodeType.ALARM_GEN -> PlcValueType.BOOL
            PlcNodeType.ALARM_LATCH -> PlcValueType.BOOL
            PlcNodeType.HEARTBEAT -> PlcValueType.BOOL
            PlcNodeType.MEM_BOOL -> PlcValueType.BOOL // прошивка требует BOOL

            // --- счётчики ---
            // На прошивке для CTU/CTD разрешены INT или BOOL, берём INT по умолчанию
            PlcNodeType.CTU -> PlcValueType.INT
            PlcNodeType.CTD -> PlcValueType.INT
            PlcNodeType.CTUD -> PlcValueType.INT

            // --- аналоговый тракт ---
            PlcNodeType.AI_IN -> PlcValueType.FLOAT
            PlcNodeType.PWM_OUT -> PlcValueType.FLOAT
            PlcNodeType.AO -> PlcValueType.FLOAT
            PlcNodeType.SCALE -> PlcValueType.FLOAT
            PlcNodeType.ADD -> PlcValueType.FLOAT
            PlcNodeType.LIMIT -> PlcValueType.FLOAT
            PlcNodeType.ANALOG_AVG -> PlcValueType.FLOAT
            PlcNodeType.RAMP -> PlcValueType.FLOAT
            PlcNodeType.LOG -> PlcValueType.FLOAT
            PlcNodeType.PID -> PlcValueType.FLOAT
            PlcNodeType.FILTER_AVG -> PlcValueType.FLOAT
            PlcNodeType.MATH_OP -> PlcValueType.FLOAT  // прошивка разрешает INT/FLOAT, по умолчанию FLOAT
            PlcNodeType.MEM_REAL -> PlcValueType.FLOAT  // прошивка требует FLOAT

            // --- память INT ---
            PlcNodeType.MEM_INT -> PlcValueType.INT    // прошивка требует INT

            // --- мультиплексор / прочее ---
            // MUX2 и SAFE_OUTPUT по типу выходного сигнала зависят от входов, пока оставляем BOOL.
            PlcNodeType.MUX2 -> PlcValueType.BOOL

            // на всякий случай fallback
            else -> PlcValueType.BOOL
        }


    private fun requiresInputA(type: PlcNodeType): Boolean = when (type) {
        // один вход обязателен (inA)
        PlcNodeType.NOT,
        PlcNodeType.DIGITAL_OUT,
        PlcNodeType.TON,
        PlcNodeType.TOFF,
        PlcNodeType.R_TRIG,
        PlcNodeType.F_TRIG,
        PlcNodeType.PWM_OUT,
        PlcNodeType.COMPARE_GT,
        PlcNodeType.COMPARE_LT,
        PlcNodeType.AO,
        PlcNodeType.TP,
        PlcNodeType.HYST,
        PlcNodeType.SCALE,
        PlcNodeType.LIMIT,
        PlcNodeType.ANALOG_AVG,
        PlcNodeType.RAMP,
        PlcNodeType.LOG,
        PlcNodeType.PID,            // SP (или PV) в зависимости от реализации
        PlcNodeType.CTU,            // CLK
        PlcNodeType.CTD,            // CLK
        PlcNodeType.WINDOW_CHECK,   // x
        PlcNodeType.SAFE_OUTPUT,    // normal value
        PlcNodeType.ALARM_GEN,      // condition
        PlcNodeType.ALARM_LATCH,    // condition
        PlcNodeType.FILTER_AVG      // x
            -> true

        // два входа обязателены
        PlcNodeType.AND2,
        PlcNodeType.OR2,
        PlcNodeType.MUX2,
        PlcNodeType.PID,            // ещё раз, но уже в группе "оба" — всё равно true
        PlcNodeType.ADD,
        PlcNodeType.MATH_OP
            -> true

        // особые случаи:
        // CTUD — "хотя бы один": A или B. Отдельно проверим в validateGraph.
        // MEM_* могут работать и без входов (чтение), и с входами (запись).
        // AI_IN, CONST_* и HEARTBEAT — источники, входы не нужны.
        else -> false
    }

    private fun requiresInputB(type: PlcNodeType): Boolean = when (type) {
        // два входа обязательны
        PlcNodeType.AND2,
        PlcNodeType.OR2,
        PlcNodeType.MUX2,
        PlcNodeType.PID,        // SP/PV
        PlcNodeType.ADD,
        PlcNodeType.MATH_OP
            -> true

        // B опционален (RST/ACK и т.п.) — валидация не требует
        // SR, CTU/CTD, ALARM_LATCH — inB может быть -1.
        else -> false
    }


    private fun requiresParamMs(type: PlcNodeType): Boolean = when (type) {
        PlcNodeType.TON,
        PlcNodeType.TOFF,
        PlcNodeType.TP,
        PlcNodeType.LOG,
        PlcNodeType.HEARTBEAT -> true

        else -> false
    }


    private fun validateGraph(ui: PlcEditorUiState): List<String> {
        val errors = mutableListOf<String>()

        val nodes = ui.nodes
        if (nodes.isEmpty()) {
            errors += "Граф пуст: нет ни одного узла."
            return errors
        }

        // Быстрый доступ по id
        val byId = nodes.associateBy { it.localId }

        for (node in nodes) {
            val id = node.localId
            val prefix = "Node $id (${shortType(node.type)})"

            // --- Проверка входов A/B ---
            val inAId = node.inA.toIntOrNull()
            val inBId = node.inB.toIntOrNull()

            if (requiresInputA(node.type)) {
                if (inAId == null || inAId < 0) {
                    errors += "$prefix: не задан вход A."
                } else {
                    val src = byId[inAId]
                    if (src == null) {
                        errors += "$prefix: вход A ссылается на несуществующий узел id=$inAId."
                    } else if (src.localId >= id) {
                        errors += "$prefix: вход A ссылается на будущий узел id=$inAId (возможен цикл)."
                    }
                }
            } else {
                // если тип не требует A, но там что-то некорректное
                if (inAId != null && inAId >= 0 && byId[inAId] == null) {
                    errors += "$prefix: вход A ссылается на несуществующий узел id=$inAId."
                }
            }

            if (requiresInputB(node.type)) {
                if (inBId == null || inBId < 0) {
                    errors += "$prefix: не задан вход B."
                } else {
                    val src = byId[inBId]
                    if (src == null) {
                        errors += "$prefix: вход B ссылается на несуществующий узел id=$inBId."
                    } else if (src.localId >= id) {
                        errors += "$prefix: вход B ссылается на будущий узел id=$inBId (возможен цикл)."
                    }
                }
            } else {
                if (inBId != null && inBId >= 0 && byId[inBId] == null) {
                    errors += "$prefix: вход B ссылается на несуществующий узел id=$inBId."
                }
            }

            // --- paramMs для TON/TOFF ---
            if (requiresParamMs(node.type)) {
                val ms = node.paramMs.toIntOrNull()
                if (ms == null || ms <= 0) {
                    errors += "$prefix: некорректный Delay (ms): '${node.paramMs}'."
                }
            }

            // --- специальные проверки для некоторых типов ---
            when (node.type) {
                PlcNodeType.CTUD -> {
                    // на прошивке: хотя бы один вход CU/CD
                    val aId = node.inA.toIntOrNull() ?: -1
                    val bId = node.inB.toIntOrNull() ?: -1
                    if (aId < 0 && bId < 0) {
                        errors += "$prefix: CTUD требует хотя бы один вход (A или B)."
                    }
                }

                PlcNodeType.MATH_OP -> {
                    val op = node.paramInt.toIntOrNull() ?: 0
                    if (op !in 0..3) {
                        errors += "$prefix: MATH_OP ожидает paramInt в диапазоне 0..3 (0=ADD,1=SUB,2=MUL,3=DIV)."
                    }
                    if (node.valueType != PlcValueType.INT && node.valueType != PlcValueType.FLOAT) {
                        errors += "$prefix: MATH_OP поддерживает только INT или FLOAT."
                    }
                }

                PlcNodeType.FILTER_AVG -> {
                    val alpha1000 = node.paramInt.toIntOrNull() ?: 0
                    if (alpha1000 !in 0..1000) {
                        errors += "$prefix: FILTER_AVG ожидает paramInt (alpha*1000) в диапазоне 0..1000."
                    }
                    if (node.valueType != PlcValueType.FLOAT) {
                        errors += "$prefix: FILTER_AVG должен иметь тип FLOAT."
                    }
                }

                PlcNodeType.MEM_BOOL -> {
                    if (node.valueType != PlcValueType.BOOL) {
                        errors += "$prefix: MEM_BOOL должен иметь тип BOOL."
                    }
                    val idx = node.paramInt.toIntOrNull() ?: -1
                    if (idx < 0) {
                        errors += "$prefix: MEM_BOOL требует неотрицательный индекс в paramInt."
                    }
                }

                PlcNodeType.MEM_INT -> {
                    if (node.valueType != PlcValueType.INT) {
                        errors += "$prefix: MEM_INT должен иметь тип INT."
                    }
                    val idx = node.paramInt.toIntOrNull() ?: -1
                    if (idx < 0) {
                        errors += "$prefix: MEM_INT требует неотрицательный индекс в paramInt."
                    }
                }

                PlcNodeType.MEM_REAL -> {
                    if (node.valueType != PlcValueType.FLOAT) {
                        errors += "$prefix: MEM_REAL должен иметь тип FLOAT."
                    }
                    val idx = node.paramInt.toIntOrNull() ?: -1
                    if (idx < 0) {
                        errors += "$prefix: MEM_REAL требует неотрицательный индекс в paramInt."
                    }
                }

                PlcNodeType.WINDOW_CHECK -> {
                    val width = node.paramInt.toIntOrNull() ?: -1
                    if (width < 0) {
                        errors += "$prefix: WINDOW_CHECK ожидает paramInt (ширина*1000) >= 0."
                    }
                }

                PlcNodeType.HEARTBEAT -> {
                    val ms = node.paramMs.toIntOrNull() ?: 0
                    if (ms <= 0) {
                        errors += "$prefix: HEARTBEAT требует paramMs > 0 (мс)."
                    }
                }

                else -> Unit
            }

        }

        // хотя бы один выход
        val hasOutput =
            nodes.any { it.type == PlcNodeType.DIGITAL_OUT || it.type == PlcNodeType.PWM_OUT || it.type == PlcNodeType.AO }
        if (!hasOutput) {
            errors += "В графе нет ни одного выходного узла (DO / PWM)."
        }

        // цикл ПЛК
        val cycle = ui.cycleMs.toIntOrNull()
        if (cycle == null || cycle <= 0) {
            errors += "Некорректное значение цикла PLC (Cycle, ms): '${ui.cycleMs}'."
        }

        return errors
    }

    fun shortType(type: PlcNodeType): String = when (type) {
        // константы
        PlcNodeType.CONST_BOOL -> "CB"
        PlcNodeType.CONST_INT -> "CI"
        PlcNodeType.CONST_FLOAT -> "CF"

        // дискретные
        PlcNodeType.DIGITAL_IN -> "DI"
        PlcNodeType.DIGITAL_OUT -> "DO"
        PlcNodeType.AND2 -> "AND"
        PlcNodeType.OR2 -> "OR"
        PlcNodeType.NOT -> "NOT"
        PlcNodeType.SR -> "SR"
        PlcNodeType.TON -> "TON"
        PlcNodeType.TOFF -> "TOFF"
        PlcNodeType.R_TRIG -> "RT"
        PlcNodeType.F_TRIG -> "FT"
        PlcNodeType.TP -> "TP"
        PlcNodeType.HYST -> "HYST"
        PlcNodeType.WINDOW_CHECK -> "WIN"
        PlcNodeType.SAFE_OUTPUT -> "SAFE"
        PlcNodeType.ALARM_GEN -> "AG"
        PlcNodeType.ALARM_LATCH -> "AL"
        PlcNodeType.HEARTBEAT -> "HB"

        // сравнения
        PlcNodeType.COMPARE_GT -> "A>B"
        PlcNodeType.COMPARE_LT -> "A<B"

        // аналоговый тракт
        PlcNodeType.AI_IN -> "AI"
        PlcNodeType.PWM_OUT -> "PWM"
        PlcNodeType.AO -> "AO"
        PlcNodeType.SCALE -> "SCL"
        PlcNodeType.ADD -> "ADD"
        PlcNodeType.LIMIT -> "LIM"
        PlcNodeType.ANALOG_AVG -> "AVG"
        PlcNodeType.RAMP -> "RAMP"
        PlcNodeType.LOG -> "LOG"
        PlcNodeType.PID -> "PID"
        PlcNodeType.FILTER_AVG -> "FAVG"
        PlcNodeType.MATH_OP -> "MATH"

        // счётчики
        PlcNodeType.CTU -> "CTU"
        PlcNodeType.CTD -> "CTD"
        PlcNodeType.CTUD -> "CTUD"

        // память
        PlcNodeType.MEM_BOOL -> "MB"
        PlcNodeType.MEM_INT -> "MI"
        PlcNodeType.MEM_REAL -> "MR"

        // мультиплексор
        PlcNodeType.MUX2 -> "MUX2"

        else -> "N"
    }

    fun saveCurrentGraphAsTemplate(
        name: String,
        description: String = "",
        category: String = ""
    ) {
        scope.launch {
            val graph = buildGraph()
            val json = GraphJsonCodec.toJson(graph)
            val now = System.currentTimeMillis()

            // если тебе нужен upsert как в Android (findByNameAndCategory):
            val existing = templates.findByNameAndCategory(name, category)
            if (existing == null) {
                templates.insertAll(
                    listOf(
                        GraphTemplate(
                            id = 0,
                            name = name,
                            description = description,
                            category = category,
                            createdAt = now,
                            updatedAt = now,
                            graphJson = json
                        )
                    )
                )
            } else {
                // если хочешь update — добавим метод update(...) в repo (ниже скажу)
            }
            reloadTemplates()
        }
    }

    private fun applyGraph(graph: PlcGraphDef) {
        pushUndo()

        val nodesUi = graph.nodes.map { n ->
            EditorNodeUi(
                localId = n.id,
                type = n.type,
                valueType = n.valueType,
                inA = if (n.inA >= 0) n.inA.toString() else "-1",
                inB = if (n.inB >= 0) n.inB.toString() else "-1",
                paramInt = n.paramInt.toString(),
                paramFloat = n.paramFloat.toString(),
                paramMs = n.paramMs.toString(),
                flags = n.flags.toString()
            )
        }

        val lastId = nodesUi.maxOfOrNull { it.localId }

        _uiState.value = _uiState.value.copy(
            nodes = nodesUi,
            selectedNodeId = lastId,
            cycleMs = graph.cycleMs.toString(),
            errorMessage = null
        )
    }


    fun loadTemplateById(id: Long) {
        scope.launch {
            val tpl = templates.getById(id) ?: return@launch
            val graph = GraphJsonCodec.fromJson(tpl.graphJson)
            applyGraph(graph)
        }
    }

    val templatesFlow = MutableStateFlow<List<GraphTemplate>>(emptyList())

    fun reloadTemplates() {
        scope.launch {
            templatesFlow.value = templates.getAll()
        }
    }


    init {
        scope.launch {
            seeder.prepopulateIfNeeded()
            reloadTemplates()
        }
    }


    object PlcValueType {
        const val BOOL = 0
        const val INT = 1
        const val FLOAT = 2
    }


    companion object {
    }
}
