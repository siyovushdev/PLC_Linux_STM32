package ktorserver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class GraphMeta(val graphJson: String, val sha256: String)

class GraphStore(private val dir: Path) {

    private val stagedFile: Path = dir.resolve("graph_staged.json")
    private val stagedSha: Path = dir.resolve("graph_staged.sha256")

    private val activeFile: Path = dir.resolve("graph_active.json")
    private val activeSha: Path = dir.resolve("graph_active.sha256")

    init {
        Files.createDirectories(dir)
    }

    @Synchronized
    fun saveStaged(graphJson: String) {
        atomicWrite(stagedFile, graphJson)
        atomicWrite(stagedSha, sha256Hex(graphJson))
    }

    @Synchronized
    fun promoteStagedToActive() {
        if (!Files.exists(stagedFile)) return
        Files.copy(stagedFile, activeFile, StandardCopyOption.REPLACE_EXISTING)
        if (Files.exists(stagedSha)) {
            Files.copy(stagedSha, activeSha, StandardCopyOption.REPLACE_EXISTING)
        } else {
            // fallback если sha не было
            val json = Files.readString(activeFile, StandardCharsets.UTF_8)
            atomicWrite(activeSha, sha256Hex(json))
        }
    }

    @Synchronized
    fun loadActiveMetaOrNull(): GraphMeta? {
        if (!Files.exists(activeFile)) return null
        val json = Files.readString(activeFile, StandardCharsets.UTF_8)
        val sha = if (Files.exists(activeSha)) {
            Files.readString(activeSha, StandardCharsets.UTF_8).trim()
        } else {
            sha256Hex(json)
        }
        return GraphMeta(graphJson = json, sha256 = sha)
    }

    private fun atomicWrite(path: Path, content: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(
            tmp,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
