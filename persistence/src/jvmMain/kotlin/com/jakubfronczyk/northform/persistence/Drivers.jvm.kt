package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/** `JdbcSqliteDriver` does not create the schema by itself (the native one does), so it is created here. */
actual fun inMemoryDriver(name: String): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NorthformDb.Schema.create(it) }
