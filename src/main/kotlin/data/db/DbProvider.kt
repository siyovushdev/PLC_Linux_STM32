package data.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object Db {
    fun connect(sqliteFile: String) {
        Database.connect("jdbc:sqlite:$sqliteFile", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.createMissingTablesAndColumns(GraphTemplatesTable)
        }
    }
}
