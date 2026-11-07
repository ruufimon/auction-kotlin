package com.gavel.kernel.persistence

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

/** PostgreSQL `jsonb` column holding a JSON document as a String. */
class JsonbColumnType : ColumnType() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): Any = when (value) {
        is PGobject -> value.value ?: "null"
        else -> value.toString()
    }

    override fun notNullValueToDB(value: Any): Any = PGobject().apply {
        type = "jsonb"
        this.value = value as String
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
