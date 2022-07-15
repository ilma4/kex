package org.vorpal.research.kex.concolic

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.junit.Test

@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class SetLongTest : ConcolicTest() {
    @Test
    fun setConcolicTest() {
        assertCoverage(cm["org/vorpal/research/kex/test/concolic/SetConcolicTests"], 1.0)
    }
}