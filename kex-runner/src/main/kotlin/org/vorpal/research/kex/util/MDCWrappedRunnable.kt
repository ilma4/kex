package org.vorpal.research.kex.util

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.slf4j.MDC
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MDCWrappedRunnable(private val runnable: Runnable) : Runnable {
    private val context: Map<String, String> = MDC.getCopyOfContextMap()
    override fun run() {
        try {
            MDC.setContextMap(context)
            runnable.run()
        } finally {
            MDC.clear()
        }
    }
}

fun newFixedThreadPoolContextWithMDC(nThreads: Int, name: String): ExecutorCoroutineDispatcher {
    require(nThreads >= 1) { "Expected at least one thread, but $nThreads specified" }
    val threadNo = AtomicInteger()
    val executor = Executors.newScheduledThreadPool(nThreads) { runnable ->
        val t = Thread(MDCWrappedRunnable(runnable), if (nThreads == 1) name else name + "-" + threadNo.incrementAndGet())
        t.isDaemon = true
        t
    }
    return executor.asCoroutineDispatcher()
}