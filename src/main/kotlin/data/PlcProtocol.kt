package data

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import domain.*
import helper.Log

/**
 * Любая ошибка протокола (CBOR, короткий пакет, неверный cmd…) → контролируемое исключение.
 */
class PlcProtocolException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

object PlcProtocol {

    private const val TAG = "PlcProtocol"

    @Volatile
    private var lastHost: String? = null

    @Volatile
    private var lastPort: Int = 0

    fun setEndpoint(host: String, port: Int) {
        PlcClient.setEndpoint(host, port)
        lastHost = host
        lastPort = port
        Log.d(TAG, "setEndpoint host=$host port=$port")
    }

    data class PlcMemInfo(
        val boolCount: Int,
        val intCount: Int,
        val realCount: Int
    )

    // -------------------------------------------------------------------------
    // PERSIST (FLASH)
    // -------------------------------------------------------------------------

    suspend fun persistSaveActive(): Boolean {
        return try {
            val req = CBORObject.NewMap().apply { Add("cmd", "save_active") }
            val resp = exchange(req, timeoutMs = 5000)

            val cmd = resp["cmd"]?.AsString() ?: ""
            if (cmd != "save_active_ack")
                throw PlcProtocolException("persistSaveActive(): unexpected cmd='$cmd'")

            resp["ok"]?.AsBoolean() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "persistSaveActive() ERROR", e)
            throw PlcProtocolException("persistSaveActive failed: ${e.message}", e)
        }
    }

    suspend fun persistLoadActive(): Boolean {
        return try {
            val req = CBORObject.NewMap().apply { Add("cmd", "load_active") }
            val resp = exchange(req, timeoutMs = 5000)

            val cmd = resp["cmd"]?.AsString() ?: ""
            if (cmd != "load_active_ack")
                throw PlcProtocolException("persistLoadActive(): unexpected cmd='$cmd'")

            resp["ok"]?.AsBoolean() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "persistLoadActive() ERROR", e)
            throw PlcProtocolException("persistLoadActive failed: ${e.message}", e)
        }
    }


    suspend fun requestMemInfo(): PlcMemInfo {
        val req = CBORObject.NewMap().apply { Add("cmd", "mem_info") }
        val resp = exchange(req, timeoutMs = 2000)

        val cmd = resp["cmd"]?.AsString() ?: ""
        if (cmd != "mem_info") throw PlcProtocolException("requestMemInfo: bad cmd=$cmd")

        return PlcMemInfo(
            boolCount = resp["boolCount"]?.AsInt32() ?: 0,
            intCount = resp["intCount"]?.AsInt32() ?: 0,
            realCount = resp["realCount"]?.AsInt32() ?: 0
        )
    }

    // -------------------------------------------------------------------------
    // STATUS
    // -------------------------------------------------------------------------

    suspend fun requestStatus(): PlcStatusUiState {
        val req = CBORObject.NewMap().apply { Add("cmd", "status") }
        val resp = exchange(req, timeoutMs = 3000)
        return mapStatusToUi(parseStatus(resp))
    }

    // -------------------------------------------------------------------------
    // FORCE OUTPUT
    // -------------------------------------------------------------------------

    suspend fun forceOutputInt(
        nodeIndex: Int,
        valueInt: Int,
        holdMs: Int = 0,
    ): Boolean {
        return try {
            val req = CBORObject.NewMap().apply {
                Add("cmd", "force_output")
                Add("nodeIndex", nodeIndex)
                Add("value", valueInt)
                if (holdMs > 0) Add("holdMs", holdMs)
            }

            val resp = exchange(req, timeoutMs = 2000)

            val cmd = resp["cmd"]?.AsString() ?: ""
            val ok = resp["ok"]?.AsBoolean() ?: false

            Log.d(TAG, "forceOutputInt(): reply cmd=$cmd ok=$ok")

            cmd == "force_output_ack" && ok
        } catch (e: Exception) {
            Log.e(TAG, "forceOutputInt() ERROR", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // RELEASE OUTPUT
    // -------------------------------------------------------------------------

    suspend fun releaseOutput(nodeIndex: Int): Boolean {
        return try {
            val req = CBORObject.NewMap().apply {
                Add("cmd", "release_output")
                Add("nodeIndex", nodeIndex)
            }

            val resp = exchange(req, timeoutMs = 2000)

            val cmd = resp["cmd"]?.AsString() ?: ""
            val ok = resp["ok"]?.AsBoolean() ?: false

            Log.d(TAG, "releaseOutput(): reply cmd=$cmd ok=$ok")

            cmd == "release_output_ack" && ok
        } catch (e: Exception) {
            Log.e(TAG, "releaseOutput() ERROR", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // UPLOAD GRAPH
    // -------------------------------------------------------------------------

    private fun PlcGraphDef.toCbor(): ByteArray {
        val nodes = CBORObject.NewArray()
        this.nodes.forEach { n ->
            nodes.Add(
                CBORObject.NewMap()
                    .Add("id", n.id)
                    .Add("type", n.type)
                    .Add("valueType", n.valueType)
                    .Add("inA", n.inA)
                    .Add("inB", n.inB)
                    .Add("paramInt", n.paramInt)
                    .Add("paramFloat", n.paramFloat.toDouble())
                    .Add("paramMs", n.paramMs)
                    .Add("flags", n.flags)
            )
        }
        val graphMap = CBORObject.NewMap()
            .Add("cycleMs", this.cycleMs)
            .Add("nodes", nodes)

        val root = CBORObject.NewMap()
            .Add("cmd", "upload_graph")
            .Add("graph", graphMap)

        return root.EncodeToBytes()
    }

    suspend fun uploadGraph(graph: PlcGraphDef): UploadGraphResult {
        return try {
            val payload = graph.toCbor()
            val resp = exchangeRaw(payload, timeoutMs = 3000)

            val cmd = resp["cmd"]?.AsString() ?: ""
            if (cmd != "upload_graph_ack")
                throw PlcProtocolException("uploadGraph(): unexpected cmd='$cmd'")

            val ok = resp["ok"]?.AsBoolean() ?: false
            val nodes = resp["stagingNodeCount"]?.AsInt32() ?: 0
            val cycleMs = resp["stagingCycleMs"]?.AsInt32() ?: 0

            Log.d(TAG, "uploadGraph(): OK=$ok nodes=$nodes cycle=$cycleMs")

            UploadGraphResult(ok, nodes, cycleMs)
        } catch (e: Exception) {
            Log.e(TAG, "uploadGraph() ERROR", e)
            throw PlcProtocolException("uploadGraph failed: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // ACTIVATE GRAPH
    // -------------------------------------------------------------------------

    suspend fun activateGraph(): ActivateGraphResult {
        return try {
            val req = CBORObject.NewMap().apply { Add("cmd", "activate_graph") }
            val resp = exchange(req, timeoutMs = 3000)

            val cmd = resp["cmd"]?.AsString() ?: ""
            if (cmd != "activate_graph_ack")
                throw PlcProtocolException("activateGraph(): unexpected cmd='$cmd'")

            val ok = resp["ok"]?.AsBoolean() ?: false
            val active = resp["active"]?.AsBoolean() ?: false
            val cycleMs = resp["cycleMs"]?.AsInt32() ?: 0
            val nodesCount = resp["nodesCount"]?.AsInt32() ?: 0

            Log.d(TAG, "activateGraph(): ok=$ok active=$active cycle=$cycleMs nodes=$nodesCount")

            ActivateGraphResult(ok, active, cycleMs, nodesCount)
        } catch (e: Exception) {
            Log.e(TAG, "activateGraph() ERROR", e)
            throw PlcProtocolException("activateGraph failed: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // MEM
    // -------------------------------------------------------------------------

    suspend fun memReadBool(from: Int, count: Int): List<Boolean> {
        val req = CBORObject.NewMap().apply {
            Add("cmd", "mem_read")
            Add("type", "bool")
            Add("from", from)
            Add("count", count)
        }

        val resp = exchange(req, timeoutMs = 3000)
        if (resp["cmd"]?.AsString() != "mem_read") {
            throw PlcProtocolException("memReadBool: bad cmd=${resp["cmd"]}")
        }
        val values = resp["values"] ?: return emptyList()
        return values.values.map { it.AsBoolean() }
    }

    suspend fun memReadInt(from: Int, count: Int): List<Int> {
        val req = CBORObject.NewMap().apply {
            Add("cmd", "mem_read")
            Add("type", "int")
            Add("from", from)
            Add("count", count)
        }

        val resp = exchange(req, timeoutMs = 3000)
        if (resp["cmd"]?.AsString() != "mem_read") {
            throw PlcProtocolException("memReadInt: bad cmd=${resp["cmd"]}")
        }
        val values = resp["values"] ?: return emptyList()
        return values.values.map { it.AsInt32() }
    }

    suspend fun memReadReal(from: Int, count: Int): List<Float> {
        val req = CBORObject.NewMap().apply {
            Add("cmd", "mem_read")
            Add("type", "real")
            Add("from", from)
            Add("count", count)
        }

        val resp = exchange(req, timeoutMs = 3000)
        if (resp["cmd"]?.AsString() != "mem_read") {
            throw PlcProtocolException("memReadReal: bad cmd=${resp["cmd"]}")
        }
        val values = resp["values"] ?: return emptyList()
        return values.values.map { v ->
            when (v.type) {
                CBORType.Number, CBORType.FloatingPoint -> v.AsDouble().toFloat()
                else -> 0f
            }
        }
    }

    suspend fun memWriteBool(from: Int, values: List<Boolean>): Int {
        val arr = CBORObject.NewArray()
        values.forEach { arr.Add(it) }

        val req = CBORObject.NewMap().apply {
            Add("cmd", "mem_write")
            Add("type", "bool")
            Add("from", from)
            Add("values", arr)
        }

        val resp = exchange(req, timeoutMs = 3000)
        if (resp["cmd"]?.AsString() != "mem_write_ack") {
            throw PlcProtocolException("memWriteBool: bad cmd=${resp["cmd"]}")
        }
        val ok = resp["ok"]?.AsBoolean() ?: false
        if (!ok) return 0
        return resp["written"]?.AsInt32() ?: 0
    }

    suspend fun memWriteInt(from: Int, values: List<Int>): Int {
        val arr = CBORObject.NewArray()
        values.forEach { arr.Add(it) }

        val req = CBORObject.NewMap().apply {
            Add("cmd", "mem_write")
            Add("type", "int")
            Add("from", from)
            Add("values", arr)
        }

        val resp = exchange(req, timeoutMs = 3000)
        if (resp["cmd"]?.AsString() != "mem_write_ack") {
            throw PlcProtocolException("memWriteInt: bad cmd=${resp["cmd"]}")
        }
        val ok = resp["ok"]?.AsBoolean() ?: false
        if (!ok) return 0
        return resp["written"]?.AsInt32() ?: 0
    }

    suspend fun memWriteReal(from: Int, values: List<Float>): Int {
        val arr = CBORObject.NewArray()
        values.forEach { arr.Add(it.toDouble()) } // безопасно для CBOR

        val req = CBORObject.NewMap().apply {
            Add("cmd", "mem_write")
            Add("type", "real")
            Add("from", from)
            Add("values", arr)
        }

        Log.d("PLC.MEM", "REQ mem_write real from=$from values=$values cbor=${cborToDiag(req)}")

        val resp = exchange(req, timeoutMs = 3000)

        Log.d("PLC.MEM", "RESP mem_write real cbor=${cborToDiag(resp)}")
        Log.d(
            "PLC.MEM",
            "RESP fields cmd=${resp["cmd"]?.AsString()} ok=${resp["ok"]?.AsBoolean()} written=${resp["written"]?.AsInt32()}"
        )

        if (resp["cmd"]?.AsString() != "mem_write_ack") {
            throw PlcProtocolException("memWriteReal: bad cmd=${resp["cmd"]}")
        }
        val ok = resp["ok"]?.AsBoolean() ?: false
        if (!ok) return 0
        return resp["written"]?.AsInt32() ?: 0
    }

    suspend fun memReset(): Boolean {
        val req = CBORObject.NewMap().apply { Add("cmd", "mem_reset") }
        val resp = exchange(req, timeoutMs = 3000)
        if (resp["cmd"]?.AsString() != "mem_reset_ack") {
            throw PlcProtocolException("memReset: bad cmd=${resp["cmd"]}")
        }
        return resp["ok"]?.AsBoolean() ?: false
    }

    // -------------------------------------------------------------------------
    // CORE EXCHANGE (PlcClient.exchange + parse LEN16 + CBOR)
    // -------------------------------------------------------------------------

    private suspend fun exchange(req: CBORObject, timeoutMs: Int = 3000): CBORObject {
        val payload = req.EncodeToBytes()
        return exchangeRaw(payload, timeoutMs)
    }

    private suspend fun exchangeRaw(payload: ByteArray, timeoutMs: Int): CBORObject {
        val frame = buildFrame(payload)

        val raw: ByteArray = PlcClient.exchangeRobust(frame, timeoutMs = timeoutMs)
            ?: throw PlcProtocolException("exchange(): timeout or no reply")

        if (raw.size < 2) throw PlcProtocolException("exchange(): reply too short raw=${raw.size}")

        val len = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        if (len <= 0 || 2 + len > raw.size) {
            throw PlcProtocolException("exchange(): bad len=$len raw=${raw.size}")
        }

        val payloadBytes = raw.copyOfRange(2, 2 + len)

        return try {
            CBORObject.DecodeFromBytes(payloadBytes)
        } catch (e: Exception) {
            throw PlcProtocolException("exchange(): invalid CBOR", e)
        }
    }

    private fun cborToDiag(obj: CBORObject): String {
        return try {
            obj.toString()
        } catch (_: Throwable) {
            "<cbor-toString-failed>"
        }
    }

    // -------------------------------------------------------------------------
    // STATUS PARSER
    // -------------------------------------------------------------------------

    private fun parseStatus(obj: CBORObject): PlcStatus {
        val cmd = obj["cmd"]?.AsString() ?: ""
        val uptimeMs = obj["uptimeMs"]?.AsNumber()?.ToInt64Unchecked() ?: 0L
        val active = obj["active"]?.AsBoolean() ?: false
        val cycleMs = obj["cycleMs"]?.AsInt32() ?: 0
        val scanAvg = obj["scanAvgMs"]?.AsInt32() ?: 0
        val scanMax = obj["scanMaxMs"]?.AsInt32() ?: 0
        val scanAvgUs = obj["scanAvgUs"]?.AsNumber()?.ToInt64Unchecked() ?: 0L
        val scanMaxUs = obj["scanMaxUs"]?.AsNumber()?.ToInt64Unchecked() ?: 0L
        val scanLong = obj["scanLongSteps"]?.AsInt32() ?: 0

        val workAvgUs = obj["workAvgUs"]?.AsNumber()?.ToInt64Unchecked() ?: 0L
        val workMaxUs = obj["workMaxUs"]?.AsNumber()?.ToInt64Unchecked() ?: 0L
        val workLongSteps = obj["workLongSteps"]?.AsInt32() ?: 0

        val cycleRealAvgMs = obj["cycleRealAvgMs"]?.AsInt32() ?: 0
        val cycleRealMaxMs = obj["cycleRealMaxMs"]?.AsInt32() ?: 0

        val nodesCount = obj["nodesCount"]?.AsInt32() ?: 0

        val list = mutableListOf<PlcNodeState>()
        val arr = obj["nodesState"]

        if (arr != null && arr.type == CBORType.Array) {
            for (i in 0 until arr.size()) {
                val n = arr[i]

                val typeIdx = n["type"]?.AsInt32() ?: 0
                val type = PlcNodeType.entries.firstOrNull { it.index == typeIdx }
                    ?: PlcNodeType.entries.firstOrNull { it.name == "UNKNOWN" }
                    ?: PlcNodeType.entries.first() // безопасный fallback

                list += PlcNodeState(
                    id = n["id"]?.AsInt32() ?: 0,
                    type = type,
                    valueType = n["valueType"]?.AsInt32() ?: 0,
                    outBool = if (n.ContainsKey("outBool")) n["outBool"].AsBoolean() else null,
                    outInt = if (n.ContainsKey("outInt")) n["outInt"].AsInt32() else null,
                    outFloat = if (n.ContainsKey("outFloat")) cborToFloat(n["outFloat"]) else null,
                    tonMs = if (n.ContainsKey("tonMs")) n["tonMs"].AsInt32() else null,
                    toffLeftMs = if (n.ContainsKey("toffLeftMs")) n["toffLeftMs"].AsInt32() else null,
                    pidSp = if (n.ContainsKey("sp")) cborToFloat(n["sp"]) else null,
                    pidPv = if (n.ContainsKey("pv")) n["pv"].AsSingle() else null,
                    pidI = if (n.ContainsKey("i")) n["i"].AsSingle() else null,
                    pidU = if (n.ContainsKey("u")) n["u"].AsSingle() else null,
                    forceActive = n["forceActive"]?.AsBoolean() ?: false,
                    forceValue = if (n.ContainsKey("forceValue")) n["forceValue"].AsBoolean() else null,
                    forceLeftMs = if (n.ContainsKey("forceLeftMs")) n["forceLeftMs"].AsInt32() else null,
                )
            }
        }

        return PlcStatus(
            cmd = cmd,
            uptimeMs = uptimeMs,
            active = active,
            cycleMs = cycleMs,

            scanAvgMs = scanAvg,
            scanMaxMs = scanMax,
            scanAvgUs = scanAvgUs,
            scanMaxUs = scanMaxUs,
            scanLongSteps = scanLong,

            workAvgUs = workAvgUs,
            workMaxUs = workMaxUs,
            workLongSteps = workLongSteps,
            cycleRealAvgMs = cycleRealAvgMs,
            cycleRealMaxMs = cycleRealMaxMs,

            nodesCount = nodesCount,
            nodes = list
        )


    }

    private fun cborToFloat(v: CBORObject): Float =
        when (v.type) {
            CBORType.Number, CBORType.FloatingPoint -> v.AsDouble().toFloat()
            else -> 0f
        }

    // -------------------------------------------------------------------------
    // FRAME BUILDER (len + payload)
    // -------------------------------------------------------------------------

    private fun buildFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val buf = ByteArray(2 + len)

        buf[0] = (len and 0xFF).toByte()
        buf[1] = ((len ushr 8) and 0xFF).toByte()

        System.arraycopy(payload, 0, buf, 2, len)

        return buf
    }

    // -------------------------------------------------------------------------
    // UI MAPPER (uses PlcClient stats; no fake fields)
    // -------------------------------------------------------------------------

    private fun mapStatusToUi(s: PlcStatus): PlcStatusUiState {
        val st = PlcClient.getStats()
        val mode = if (s.active) "RUN" else "STOP"

        val nodes: List<PlcNodeState> = s.nodes

        val diNodes = nodes.filter { it.type == PlcNodeType.DIGITAL_IN }
        val doNodes = nodes.filter { it.type == PlcNodeType.DIGITAL_OUT }
        val aiNodes = nodes.filter { it.type == PlcNodeType.AI_IN }
        val pwmNodes = nodes.filter { it.type == PlcNodeType.PWM_OUT }

        val diTotal = diNodes.size
        val diUsed = diNodes.count { it.outBool == true }

        val doTotal = doNodes.size
        val doUsed = doNodes.count { it.outBool == true }

        val aiTotal = aiNodes.size
        val aiUsed = aiNodes.size

        val pwmTotal = pwmNodes.size
        val pwmUsed = pwmNodes.size

        val connection = PlcConnectionInfo(
            ip = st.host ?: (lastHost ?: "empty"),
            port = if (st.port != 0) st.port else lastPort,
            mode = mode,
            uptime = formatMsToHhMmSs(s.uptimeMs),
            lastExchangeAgo = formatAgo(st.lastSuccessAtMs),
            linkStatus = if (st.lastSuccessAtMs > 0L) "OK" else "NO_LINK"
        )

        val avgMs: Float =
            if (s.scanAvgUs > 0) (s.scanAvgUs.toFloat() / 1000f) else s.scanAvgMs.toFloat()

        val maxMs: Float =
            if (s.scanMaxUs > 0) (s.scanMaxUs.toFloat() / 1000f) else s.scanMaxMs.toFloat()

        val workAvgMs: Float = if (s.workAvgUs > 0) (s.workAvgUs.toFloat() / 1000f) else 0f
        val workMaxMs: Float = if (s.workMaxUs > 0) (s.workMaxUs.toFloat() / 1000f) else 0f

        val cycleRealAvg: Float = s.cycleRealAvgMs.toFloat()
        val cycleRealMax: Float = s.cycleRealMaxMs.toFloat()

        val performance = PlcPerformanceInfo(
            scanAvgMs = avgMs,
            scanMaxMs = maxMs,
            scanAvgUs = s.scanAvgUs,
            scanMaxUs = s.scanMaxUs,

            workAvgMs = workAvgMs,
            workMaxMs = workMaxMs,
            workAvgUs = s.workAvgUs,
            workMaxUs = s.workMaxUs,

            cycleRealAvgMs = cycleRealAvg,
            cycleRealMaxMs = cycleRealMax,

            scanLimitMs = s.cycleMs.toFloat().coerceAtLeast(1f),
            cpuLoadPercent = 0,
            memoryUsagePercent = 0,
            crcErrors = 0,
            timeouts = st.timeouts.toInt(),
            scanLongSteps = s.scanLongSteps,
            workLongSteps = s.workLongSteps
        )



        val ioSummary = PlcIoSummaryInfo(
            diUsed = diUsed,
            diTotal = diTotal,
            doUsed = doUsed,
            doTotal = doTotal,
            aiUsed = aiUsed,
            aiTotal = aiTotal,
            pwmUsed = pwmUsed,
            pwmTotal = pwmTotal
        )

        val activeGraph = PlcActiveGraphInfo(
            name = "active_graph",
            version = "—",
            nodes = s.nodesCount,
            connections = 0,
            inputs = 0,
            outputs = 0,
            compileErrors = 0,
            activatedAt = "—",
            runState = mode
        )

        val alarms = emptyList<PlcAlarm>()

        return PlcStatusUiState(
            connection = connection,
            performance = performance,
            ioSummary = ioSummary,
            activeGraph = activeGraph,
            alarms = alarms,
            nodes = nodes,
            isLoading = false,
            errorMessage = null
        )
    }

    private fun formatAgo(ms: Long): String {
        if (ms <= 0L) return "никогда"
        val diffSec = ((System.currentTimeMillis() - ms) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 1 -> "только что"
            diffSec < 60 -> "${diffSec} сек назад"
            diffSec < 3600 -> "${diffSec / 60} мин назад"
            diffSec < 86400 -> "${diffSec / 3600} ч назад"
            else -> "${diffSec / 86400} дн назад"
        }
    }

    private fun formatMsToHhMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
