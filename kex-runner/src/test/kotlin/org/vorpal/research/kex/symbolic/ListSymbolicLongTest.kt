package org.vorpal.research.kex.symbolic

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.junit.Test

@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class ListSymbolicLongTest : SymbolicTest() {
    @Test
    fun listConcolicTest() {
        assertCoverage(cm["org/vorpal/research/kex/test/concolic/ListConcolicTests"], 1.0)
    }
}
