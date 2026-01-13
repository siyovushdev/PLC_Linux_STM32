package data.db

import org.jetbrains.exposed.sql.Table

object GraphTemplatesTable : Table("graph_templates") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val description = text("description")
    val category = text("category")
    val createdAt = long("createdAt")
    val updatedAt = long("updatedAt")
    val graphJson = text("graphJson")

    override val primaryKey = PrimaryKey(id)
}

data class GraphTemplate(
    val id: Long,
    val name: String,
    val description: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val graphJson: String
)
