package domain

data class PlcConnectionInfo(
    val ip: String,
    val port: Int,
    val mode: String,           // RUN/STOP/ERROR
    val uptime: String,         // "01:23:45"
    val lastExchangeAgo: String,// "00:00:01 назад"
    val linkStatus: String      // "OK" / "TIMEOUT" / "ERROR"
)

data class PlcPerformanceInfo(
    val scanAvgMs: Float,
    val scanMaxMs: Float,
    val scanLimitMs: Float,
    val cpuLoadPercent: Int,
    val memoryUsagePercent: Int,
    val crcErrors: Int,
    val timeouts: Int,
    val scanLongSteps: Int = 0
)

data class PlcIoSummaryInfo(
    val diUsed: Int,
    val diTotal: Int,
    val doUsed: Int,
    val doTotal: Int,
    val aiUsed: Int,
    val aiTotal: Int,
    val pwmUsed: Int,
    val pwmTotal: Int
)

data class PlcActiveGraphInfo(
    val name: String,
    val version: String,
    val nodes: Int,
    val connections: Int,
    val inputs: Int,
    val outputs: Int,
    val compileErrors: Int,
    val activatedAt: String, // "Сегодня, 10:15"
    val runState: String     // RUN / STOP / ERROR
)

data class PlcAlarm(
    val time: String,
    val level: String,  // INFO/WARN/ERROR
    val message: String
)

/**
 * UI-модель одного узла для экрана «Ноды».
 * Здесь уже подготовлены текстовые поля, а не сырые int/float.
 */
data class PlcNodeUi(
    val id: Int,
    val type: Int,
    val valueType: Int,
    val title: String,        // например: "[10] DIGITAL_OUT"
    val valueText: String,    // "TRUE", "123", "12.345", "—"
    val details: String,      // "TON=1200 ms · FORCE=1 (500 ms)" и т.п.
    val isForced: Boolean
)

data class PlcNodeState(
    val id: Int,
    val type: PlcNodeType,
    val valueType: Int,
    val outBool: Boolean? = null,
    val outInt: Int? = null,
    val outFloat: Float? = null,
    val tonMs: Int? = null,
    val toffLeftMs: Int? = null,
    val pidSp: Float? = null,
    val pidPv: Float? = null,
    val pidI: Float? = null,
    val pidU: Float? = null,
    val forceActive: Boolean = false,
    val forceValue: Boolean? = null,
    val forceLeftMs: Int? = null,
)



data class PlcStatus(
    val cmd: String,
    val uptimeMs: Long,
    val active: Boolean,
    val cycleMs: Int,
    val scanAvgMs: Int,
    val scanMaxMs: Int,
    val scanLongSteps: Int,
    val nodesCount: Int,
    val nodes: List<PlcNodeState>
)

data class PlcStatusUiState(
    val connection: PlcConnectionInfo,
    val performance: PlcPerformanceInfo,
    val ioSummary: PlcIoSummaryInfo,
    val activeGraph: PlcActiveGraphInfo,
    val alarms: List<PlcAlarm>,
    val nodes: List<PlcNodeState> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)


enum class MemType { BOOL, INT, REAL }