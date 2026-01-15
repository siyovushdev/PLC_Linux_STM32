package ktorserver

import data.GraphJsonCodec
import data.PlcProtocol
import domain.ActivateGraphResult
import domain.UploadGraphResult
import helper.GraphCompiler

class PlcGateway(
    private val host: String,
    private val port: Int,
    private val graphStore: GraphStore,
) {
    init {
        PlcProtocol.setEndpoint(host, port)
    }

    suspend fun status() = PlcProtocol.requestStatus()

    suspend fun uploadGraphFromJson(graphJson: String): UploadGraphResult {
        // 1) сохраняем staged (то, что пришло с веба)
        graphStore.saveStaged(graphJson)

        // 2) валидируем/парсим + отправляем в PLC
        val raw = GraphJsonCodec.fromJson(graphJson)      // как сейчас
        val compiled = GraphCompiler.compileForPlc(raw)
        return PlcProtocol.uploadGraph(compiled)
    }

    suspend fun activateGraph(): ActivateGraphResult {
        val res = PlcProtocol.activateGraph()

        // Если активировалось успешно — делаем staged -> active
        // (подстрой условие под твой ActivateGraphResult, но ниже универсально)
        val ok = when {
            // часто бывает res.ok:Boolean
            tryGetOk(res) == true -> true
            else -> false
        }
        if (ok) graphStore.promoteStagedToActive()

        return res
    }

    fun getActiveGraphMetaOrNull(): GraphMeta? = graphStore.loadActiveMetaOrNull()

    suspend fun force(req: ForceReq): Boolean =
        PlcProtocol.forceOutputInt(req.nodeIndex, req.valueInt, req.holdMs)

    suspend fun release(req: ReleaseReq): Boolean =
        PlcProtocol.releaseOutput(req.nodeIndex)

    suspend fun memInfo(): PlcProtocol.PlcMemInfo = PlcProtocol.requestMemInfo()
    suspend fun memReset(): Boolean = PlcProtocol.memReset()

    suspend fun memRead(req: MemReadReq): Any {
        return when (req.type.lowercase()) {
            "bool" -> PlcProtocol.memReadBool(req.from, req.count)
            "int" -> PlcProtocol.memReadInt(req.from, req.count)
            "real" -> PlcProtocol.memReadReal(req.from, req.count)
            else -> throw IllegalArgumentException("Unknown mem type: ${req.type}")
        }
    }

    suspend fun memWriteBool(req: MemWriteBoolReq): Int =
        PlcProtocol.memWriteBool(req.from, req.values)

    suspend fun memWriteInt(req: MemWriteIntReq): Int =
        PlcProtocol.memWriteInt(req.from, req.values)

    suspend fun memWriteReal(req: MemWriteRealReq): Int =
        PlcProtocol.memWriteReal(req.from, req.values)

    private fun tryGetOk(res: Any): Boolean? {
        return try {
            val m = res::class.members.firstOrNull { it.name == "ok" } ?: return null
            val v = m.call(res)
            v as? Boolean
        } catch (_: Throwable) {
            null
        }
    }
}
