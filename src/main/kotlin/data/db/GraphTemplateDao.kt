package data.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class GraphTemplateRepository {

    fun count(): Int = transaction {
        GraphTemplatesTable.selectAll().count().toInt()
    }

    fun insertAll(items: List<GraphTemplate>) = transaction {
        items.forEach { t ->
            GraphTemplatesTable.insert {
                it[name] = t.name
                it[description] = t.description
                it[category] = t.category
                it[createdAt] = t.createdAt
                it[updatedAt] = t.updatedAt
                it[graphJson] = t.graphJson
            }
        }
    }

    fun getAll(): List<GraphTemplate> = transaction {
        GraphTemplatesTable
            .selectAll()
            .orderBy(GraphTemplatesTable.name to SortOrder.ASC)
            .map { it.toModel() }
    }

    fun getById(id: Long): GraphTemplate? = transaction {
        GraphTemplatesTable
            .select { GraphTemplatesTable.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toModel()
    }

    fun findByNameAndCategory(name: String, category: String): GraphTemplate? = transaction {
        GraphTemplatesTable
            .select { (GraphTemplatesTable.name eq name) and (GraphTemplatesTable.category eq category) }
            .limit(1)
            .firstOrNull()
            ?.toModel()
    }

    fun clearAll() = transaction {
        GraphTemplatesTable.deleteAll()
    }

    private fun ResultRow.toModel() = GraphTemplate(
        id = this[GraphTemplatesTable.id],
        name = this[GraphTemplatesTable.name],
        description = this[GraphTemplatesTable.description],
        category = this[GraphTemplatesTable.category],
        createdAt = this[GraphTemplatesTable.createdAt],
        updatedAt = this[GraphTemplatesTable.updatedAt],
        graphJson = this[GraphTemplatesTable.graphJson]
    )
}
