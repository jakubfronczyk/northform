package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.SynchronousFlag

/**
 * The one `expect/actual`-style leaf (D3) — a plain factory the composition root calls. D12:
 *  - WAL: SQLiter's default `journalMode` (DatabaseConfiguration.kt: `journalMode: JournalMode = JournalMode.WAL`)
 *  - file protection: iOS default for a file created without an explicit class is
 *    `completeUntilFirstUserAuthentication` (Apple, "Encrypting Your App's Files") — the class GRDB set
 *  - synchronous=FULL: the ONE thing set by hand, via `onConfiguration`
 *    (SQLDelight: NativeSqliteDriver(schema, name, maxReaderConnections, onConfiguration, …)).
 */
fun nativeDriver(name: String = "northform.db"): SqlDriver = NativeSqliteDriver(
    schema = NorthformDb.Schema,
    name = name,
    onConfiguration = { config ->
        config.copy(extendedConfig = config.extendedConfig.copy(synchronousFlag = SynchronousFlag.FULL))
    },
)
// TODO(spike): compile-check the exact `NorthformDb.Schema` type against SQLDelight 2.4.0 (sync vs async schema).
