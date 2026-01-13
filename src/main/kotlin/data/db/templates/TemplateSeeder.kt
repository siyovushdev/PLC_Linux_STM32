package data.db.templates

import data.db.GraphTemplate
import data.db.GraphTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class TemplateSeederJvm(
    private val repo: GraphTemplateRepository,
    private val externalTemplatesPath: String? = null, // напр: "/etc/simpleplc/templates.json"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    suspend fun prepopulateIfNeeded() = withContext(Dispatchers.IO) {
        if (repo.count() > 0) return@withContext

        val raw = loadTemplatesJson()
        val rootEl = json.parseToJsonElement(raw)
        val templatesEl = rootEl.jsonObject["templates"] ?: error("templates missing")

        val now = System.currentTimeMillis()
        val entities = templatesEl.jsonArray.map { tEl ->
            val obj = tEl.jsonObject

            val name = obj["name"]?.jsonPrimitive?.content ?: "Unnamed"
            val category = obj["category"]?.jsonPrimitive?.content ?: "GENERAL"

            val desc = obj["description"]?.jsonObject
            val short = desc?.get("short")?.jsonPrimitive?.content ?: ""
            val longArr = desc?.get("long")?.jsonArray
            val longText = buildString {
                if (longArr != null) {
                    for (i in longArr.indices) {
                        if (isNotEmpty()) append('\n')
                        append(longArr[i].jsonPrimitive.content)
                    }
                }
            }
            val description =
                if (longText.isBlank()) short
                else short + "\n\n" + longText

            // сохраняем graph как JSON-строку 1 в 1 (как ты делал graphObj.toString()) :contentReference[oaicite:8]{index=8}
            val graphEl: JsonElement = obj["graph"] ?: error("graph missing in template $name")
            val graphJson = graphEl.toString()

            GraphTemplate(
                id = 0L,
                name = name,
                description = description,
                category = category,
                createdAt = now,
                updatedAt = now,
                graphJson = graphJson
            )
        }

        repo.insertAll(entities)
    }

    private fun loadTemplatesJson(): String {
        // 1) внешний путь (если задан и существует)
        if (!externalTemplatesPath.isNullOrBlank()) {
            val f = File(externalTemplatesPath)
            if (f.exists() && f.isFile) return f.readText()
        }

        // 2) ресурс внутри jar: src/main/resources/templates.json
        val stream = this::class.java.classLoader.getResourceAsStream("templates.json")
            ?: error("templates.json not found (neither external nor resources)")
        return stream.bufferedReader().use { it.readText() }
    }
}
