package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.SynchronousFlag

/**
 * The on-disk driver the composition root uses (D3: a leaf factory, not a port). D12:
 *  - WAL: SQLiter's default `journalMode`
 *  - file protection: iOS default for a file created without an explicit class is
 *    `completeUntilFirstUserAuthentication` (Apple, "Encrypting Your App's Files") — the class GRDB set
 *  - synchronous=FULL: the ONE thing set by hand, via `onConfiguration`
 * The file lands in Application Support (SQLiter's default base path). The schema is created on first
 * open and migrated by version from then on (SQLDelight `.sqm`, lane C).
 */
fun nativeDriver(name: String = "northform.db"): SqlDriver = NativeSqliteDriver(
    schema = NorthformDb.Schema,
    name = name,
    onConfiguration = { config ->
        config.copy(extendedConfig = config.extendedConfig.copy(synchronousFlag = SynchronousFlag.FULL))
    },
)

/** SQLiter opens a shared-cache in-memory database for `inMemory = true`, so one reader pool sees one database. */
actual fun inMemoryDriver(name: String): SqlDriver = NativeSqliteDriver(
    schema = NorthformDb.Schema,
    name = name,
    maxReaderConnections = 1,
    onConfiguration = { it.copy(inMemory = true) },
)
