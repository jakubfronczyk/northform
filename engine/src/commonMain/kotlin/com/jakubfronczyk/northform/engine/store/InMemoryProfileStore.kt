package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.ports.ProfileStore
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** [ProfileStore] in memory, for the simulator wiring and the tests; lost on quit. */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryProfileStore(initial: Profile? = null) : ProfileStore {
    private val saved = AtomicReference(initial)

    override suspend fun profile(): Profile? = saved.load()
    override suspend fun saveProfile(profile: Profile) = saved.store(profile)
}
