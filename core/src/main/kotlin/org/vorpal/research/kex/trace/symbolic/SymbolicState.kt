package org.vorpal.research.kex.trace.symbolic

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.Instruction

@Serializable
data class Clause(
    @Contextual val instruction: Instruction,
    val predicate: Predicate
)

@Serializable
data class ClauseState(val state: List<Clause> = emptyList()) : List<Clause> by state {
    override fun iterator() = state.iterator()
    fun asState() = BasicState(state.map { it.predicate })
}

@Serializable
data class PathCondition(val path: List<Clause> = emptyList()) : List<Clause> by path {
    override fun iterator() = path.iterator()

    fun subPath(clause: Clause) = path.subList(0, path.indexOf(clause) + 1)

    fun asState() = BasicState(path.map { it.predicate })
}

@Serializable
data class WrappedValue(val method: @Contextual Method, val value: @Contextual Value)

@Serializable
abstract class SymbolicState {
    abstract val clauses: ClauseState
    abstract val path: PathCondition
    abstract val concreteValueMap: Map<Term, @Contextual Descriptor>
    abstract val termMap: Map<Term, @Contextual WrappedValue>

    operator fun get(term: Term) = termMap.getValue(term)

    fun subPath(clause: Clause): List<Clause> = path.subList(0, path.indexOf(clause) + 1)

    fun isEmpty() = clauses.isEmpty()
    fun isNotEmpty() = clauses.isNotEmpty()
}

@Serializable
@SerialName("SymbolicStateImpl")
data class SymbolicStateImpl(
    override val clauses: ClauseState,
    override val path: PathCondition,
    override val concreteValueMap: @Contextual Map<Term, @Contextual Descriptor>,
    override val termMap: @Contextual Map<Term, @Contextual WrappedValue>,
) : SymbolicState() {
    override fun toString() = "${clauses.asState()}"
}

fun symbolicState(
    state: ClauseState = ClauseState(emptyList()),
    path: PathCondition = PathCondition(emptyList()),
    concreteValueMap: Map<Term, Descriptor> = emptyMap(),
    termMap: Map<Term, WrappedValue> = emptyMap(),
) = SymbolicStateImpl(
    state, path, concreteValueMap, termMap
)