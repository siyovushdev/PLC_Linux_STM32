package ktorserver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class GraphStore(private val dir: Path) {

    private val stagedFile: Path = dir.resolve("graph_staged.json")
    private val activeFile: Path = dir.resolve("graph_active.json")

    init {
        Files.createDirectories(dir)
    }

    @Synchronized
    fun saveStaged(graphJson: String) {
        atomicWrite(stagedFile, graphJson)
    }

    @Synchronized
    fun promoteStagedToActive() {
        if (!Files.exists(stagedFile)) return
        Files.copy(stagedFile, activeFile, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun loadActiveOrNull(): String? {
        if (!Files.exists(activeFile)) return null
        return Files.readString(activeFile, StandardCharsets.UTF_8)
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
}
