
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ktorserver.*
import java.io.File

fun main() {
//    startKoin { modules(cliModule, jvmModule /* + твои другие модули */) }

    val plcHost = System.getenv("PLC_HOST") ?: "192.168.1.222"
    val plcPort = (System.getenv("PLC_PORT") ?: "5555").toInt()
    val httpPort = (System.getenv("HTTP_PORT") ?: "8082").toInt()

    // где хранить active/staged граф (по умолчанию рядом с сервисом)
    val stateDir = chooseStateDir()
    val graphStore = GraphStore(stateDir)

    val gateway = PlcGateway(plcHost, plcPort, graphStore)

    embeddedServer(Netty, port = httpPort) {
        install(ContentNegotiation) {
            gson {
                disableHtmlEscaping()
                serializeNulls()
            }
        }

        routing {
            get("/api/health") {
                call.respond(mapOf("ok" to true))
            }

            get("/api/plc/log/dump") {
                val from = call.request.queryParameters["from"]?.toIntOrNull() ?: 0
                val count = call.request.queryParameters["count"]?.toIntOrNull() ?: 64

                if (from < 0 || count <= 0 || count > 512) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "Bad query params: from>=0, 1<=count<=512"))
                    return@get
                }

                runCatching { gateway.logDump(from, count) }
                    .onSuccess { call.respond(it) }
                    .onFailure { e ->
                        call.respond(
                            HttpStatusCode.BadGateway,
                            mapOf("ok" to false, "error" to (e.message ?: "PLC error"))
                        )
                    }
            }


            post("/api/plc/persist/save") {
                runCatching { gateway.persistSaveActive() }
                    .onSuccess { ok -> call.respond(mapOf("ok" to ok)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadGateway, mapOf("ok" to false, "error" to (e.message ?: "PLC error")))
                    }
            }

            post("/api/plc/persist/load") {
                runCatching { gateway.persistLoadActive() }
                    .onSuccess { ok -> call.respond(mapOf("ok" to ok)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadGateway, mapOf("ok" to false, "error" to (e.message ?: "PLC error")))
                    }
            }


            get("/api/plc/status") {
                runCatching { gateway.status() }
                    .onSuccess { call.respond(it) }
                    .onFailure { e ->
                        call.respond(
                            HttpStatusCode.BadGateway,
                            mapOf("ok" to false, "error" to (e.message ?: "PLC error"))
                        )
                    }
            }

            get("/api/plc/graph/active") {
                val meta = gateway.getActiveGraphMetaOrNull()
                if (meta == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "error" to "No active graph stored"))
                } else {
                    call.respond(mapOf("ok" to true, "graphJson" to meta.graphJson, "sha256" to meta.sha256))
                }
            }


            post("/api/plc/graph/upload") {
                val req = call.receive<UploadGraphReq>()
                try {
                    val res = gateway.uploadGraphFromJson(req.graphJson)
                    call.respond(res)
                } catch (e: Throwable) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("ok" to false, "error" to (e.message ?: "error"))
                    )
                }
            }

            post("/api/plc/graph/activate") {
                try {
                    val res = gateway.activateGraph()
                    call.respond(res)
                } catch (e: Throwable) {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        mapOf("ok" to false, "error" to (e.message ?: "PLC error"))
                    )
                }
            }



            post("/api/plc/output/force") {
                val req = call.receive<ForceReq>()
                runCatching { gateway.force(req) }
                    .onSuccess { call.respond(mapOf("ok" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadGateway, mapOf("ok" to false, "error" to (e.message ?: "PLC error")))
                    }
            }

            post("/api/plc/output/release") {
                val req = call.receive<ReleaseReq>()
                runCatching { gateway.release(req) }
                    .onSuccess { call.respond(mapOf("ok" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadGateway, mapOf("ok" to false, "error" to (e.message ?: "PLC error")))
                    }
            }

            post("/api/plc/mem/reset") {
                runCatching { gateway.memReset() }
                    .onSuccess { call.respond(mapOf("ok" to true, "values" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (e.message ?: "error")))
                    }
            }


            post("/api/plc/mem/info") {
                runCatching { gateway.memInfo() }
                    .onSuccess { call.respond(mapOf("ok" to true, "values" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (e.message ?: "error")))
                    }
            }


            post("/api/plc/mem/read") {
                val req = call.receive<MemReadReq>()
                runCatching { gateway.memRead(req) }
                    .onSuccess { call.respond(mapOf("ok" to true, "values" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (e.message ?: "error")))
                    }
            }

            post("/api/plc/mem/write/bool") {
                val req = call.receive<MemWriteBoolReq>()
                runCatching { gateway.memWriteBool(req) }
                    .onSuccess { call.respond(mapOf("ok" to true, "written" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (e.message ?: "error")))
                    }
            }

            post("/api/plc/mem/write/int") {
                val req = call.receive<MemWriteIntReq>()
                runCatching { gateway.memWriteInt(req) }
                    .onSuccess { call.respond(mapOf("ok" to true, "written" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (e.message ?: "error")))
                    }
            }

            post("/api/plc/mem/write/real") {
                val req = call.receive<MemWriteRealReq>()
                runCatching { gateway.memWriteReal(req) }
                    .onSuccess { call.respond(mapOf("ok" to true, "written" to it)) }
                    .onFailure { e ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (e.message ?: "error")))
                    }
            }

            staticFiles("/", File("/opt/plc-bridge/web")) {
                default("index.html")
            }

            get("{...}") {
                call.respondFile(File("/opt/plc-bridge/web/index.html"))
            }
        }
    }.start(wait = true)
}


private fun chooseStateDir(): java.nio.file.Path {
    val env = System.getenv("PLC_STATE_DIR")
    if (!env.isNullOrBlank()) return java.nio.file.Paths.get(env)

    val preferred = java.nio.file.Paths.get("/opt/plc-bridge/state")
    try {
        java.nio.file.Files.createDirectories(preferred)
        return preferred
    } catch (_: Throwable) {
        val home = System.getProperty("user.home") ?: "."
        return java.nio.file.Paths.get(home, ".plc-bridge", "state")
    }
}