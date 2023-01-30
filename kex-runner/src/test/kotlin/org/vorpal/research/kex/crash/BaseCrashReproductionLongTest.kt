package org.vorpal.research.kex.crash

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.junit.Test
import org.vorpal.research.kex.test.crash.CrashTrigger
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class BaseCrashReproductionLongTest : CrashReproductionTest("base-crash-reproduction") {

    @Test
    fun testNullPointerException() {
        val expectedStackTrace = produceStackTrace { CrashTrigger().triggerNullPtr() }
        assertCrash(expectedStackTrace)
    }

    @Test
    fun testAssertionError() {
        val expectedStackTrace = produceStackTrace { CrashTrigger().triggerAssert() }
        assertCrash(expectedStackTrace)
    }

    @Test
    fun testArithmeticException() {
        val expectedStackTrace = produceStackTrace { CrashTrigger().triggerException() }
        assertCrash(expectedStackTrace)
    }
}
