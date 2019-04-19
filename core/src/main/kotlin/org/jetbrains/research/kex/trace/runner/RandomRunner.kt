package org.jetbrains.research.kex.trace.runner

import org.jetbrains.research.kex.config.GlobalConfig
import org.jetbrains.research.kex.random.GenerationException
import org.jetbrains.research.kex.random.defaultRandomizer
import org.jetbrains.research.kex.trace.Trace
import org.jetbrains.research.kex.trace.TraceManager
import org.jetbrains.research.kex.util.log
import java.util.*
import org.jetbrains.research.kfg.ir.Method as KfgMethod

internal val runs = GlobalConfig.getIntValue("runner", "runs", 10)

class RandomRunner(method: KfgMethod, loader: ClassLoader) : AbstractRunner(method, loader) {
    private val random = defaultRandomizer

    fun run() = repeat(runs) { _ ->
        if (TraceManager.isBodyCovered(method)) return
        val (instance, args) = try {
            val i = when {
                method.isStatic -> null
                else -> random.next(javaClass)
            }
            val a = javaMethod.genericParameterTypes.map { random.next(it) }.toTypedArray()
            i to a
        } catch (e: GenerationException) {
            log.debug("Cannot invoke $method")
            log.debug("Cause: ${e.message}")
            log.debug("Skipping method")
            return
        }

        val trace = try {
            invoke(instance, args)
        } catch (e: Exception) {
            log.error("Failed when running method $method")
            log.error("Exception: $e")
            null
        } ?: return@repeat

        val queue = ArrayDeque<Trace>()
        queue.add(trace)
        while (queue.isNotEmpty()) {
            val current = queue.pollFirst()
            TraceManager.addTrace(current.method, current)
            queue.addAll(current.subtraces)
        }
    }
}