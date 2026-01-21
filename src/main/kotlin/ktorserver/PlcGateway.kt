package ktorserver

import data.GraphJsonCodec
import data.PlcProtocol
import data.prettyJson
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

    suspend fun persistSaveActive(): Boolean =
        PlcProtocol.persistSaveActive()

    suspend fun persistLoadActive(): Boolean =
        PlcProtocol.persistLoadActive()

    suspend fun status() = PlcProtocol.requestStatus()

    suspend fun uploadGraphFromJson(graphJson: String): UploadGraphResult {
        println("========== GRAPH BEFORE COMPILE ==========")
        println(prettyJson(graphJson))

        val t0 = System.currentTimeMillis()
        try {
            println(">>> parse start (${t0})")
            val raw = GraphJsonCodec.fromJson(graphJson)
            println("<<< parse ok (+${System.currentTimeMillis() - t0} ms)")

            val t1 = System.currentTimeMillis()
            println(">>> compile start (${t1})")
            val compiled = GraphCompiler.compileForPlc(raw)
            println("<<< compile ok (+${System.currentTimeMillis() - t1} ms)")

            val compiledJson = GraphJsonCodec.toJson(compiled)

            println("========== GRAPH AFTER COMPILE ==========")
            println(prettyJson(compiledJson))

            println(">>> saveStaged start")
            graphStore.saveStaged(prettyJson(compiledJson))
            println("<<< saveStaged ok")

            val t2 = System.currentTimeMillis()
            println(">>> plc upload start (${t2})")
            val res = PlcProtocol.uploadGraph(compiled)
            println("<<< plc upload ok (+${System.currentTimeMillis() - t2} ms)")

            return res
        } catch (e: Throwable) {
            println("!!! uploadGraphFromJson FAILED: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            println("=== uploadGraphFromJson finished (+${System.currentTimeMillis() - t0} ms) ===")
        }
    }



    suspend fun activateGraph(): ActivateGraphResult {
        val res = PlcProtocol.activateGraph()
        if (res.ok) {
            graphStore.promoteStagedToActive()
        }
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
