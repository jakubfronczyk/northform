package com.jakubfronczyk.northform.core.ports

import kotlinx.coroutines.flow.Flow
import com.jakubfronczyk.northform.core.LocationFix

/** GPS port (D92 restated). A new cold stream per call; the adapter drops cached and invalid fixes. */
interface LocationProvider {
    fun fixes(): Flow<LocationFix>
}
