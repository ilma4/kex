package org.vorpal.research.kex.concolic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.KexRunnerTest
import org.vorpal.research.kex.asm.analysis.concolic.InstructionConcolicChecker
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.asm.transform.SymbolicTraceCollector
import org.vorpal.research.kex.asm.util.Visibility
import org.vorpal.research.kex.jacoco.CoverageReporter
import org.vorpal.research.kex.launcher.ClassLevel
import org.vorpal.research.kex.trace.runner.ExecutorMasterController
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kthelper.logging.log
import kotlin.test.assertEquals

@ExperimentalSerializationApi
@InternalSerializationApi
abstract class ConcolicTest : KexRunnerTest() {

    override fun createTraceCollector(context: ExecutionContext) = SymbolicTraceCollector(context)

    fun assertCoverage(klass: Class, expectedCoverage: Double = 1.0) {
        ExecutorMasterController.use {
            it.start(analysisContext)
            executePipeline(analysisContext.cm, Package.defaultPackage) {
                +ClassInstantiationDetector(analysisContext.cm, Visibility.PRIVATE)
            }

            InstructionConcolicChecker.run(analysisContext, klass.allMethods)

            val coverage = CoverageReporter(listOf(jar)).execute(klass.cm, ClassLevel(klass))
            log.debug(coverage.print(true))
            assertEquals(expectedCoverage, coverage.instructionCoverage.ratio)
        }
    }
}