package org.vorpal.research.kex.concolic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.junit.Test

@ExperimentalSerializationApi
@InternalSerializationApi
class EnumLongTest : ConcolicTest() {
    @Test
    fun enumTest() {
        assertCoverage(cm["org/vorpal/research/kex/test/concolic/EnumConcolicTests"], 1.0)
    }
}