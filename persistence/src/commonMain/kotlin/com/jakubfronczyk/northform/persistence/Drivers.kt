package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.SqlDriver

/**
 * A fresh, empty database that lives in memory and dies with the driver: the store's tests and the
 * simulator. The one `expect/actual` the architecture allows (D3): a leaf factory for the SQL driver,
 * nothing above it varies per platform.
 */
expect fun inMemoryDriver(name: String = "northform-memory"): SqlDriver
