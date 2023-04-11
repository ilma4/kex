package org.vorpal.research.kex.asm.analysis.crash.precondition

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.TraverserState
import org.vorpal.research.kex.descriptor.ArrayDescriptor
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.descriptor.FieldContainingDescriptor
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.term
import org.vorpal.research.kex.state.transformer.SymbolicStateTermRemapper
import org.vorpal.research.kex.trace.symbolic.PathClause
import org.vorpal.research.kex.trace.symbolic.PathClauseType
import org.vorpal.research.kex.trace.symbolic.PersistentSymbolicState
import org.vorpal.research.kex.trace.symbolic.StateClause
import org.vorpal.research.kex.trace.symbolic.persistentClauseStateOf
import org.vorpal.research.kex.trace.symbolic.persistentPathConditionOf
import org.vorpal.research.kex.trace.symbolic.persistentSymbolicState
import org.vorpal.research.kex.util.arrayIndexOOBClass
import org.vorpal.research.kex.util.asSet
import org.vorpal.research.kex.util.classCastClass
import org.vorpal.research.kex.util.negativeArrayClass
import org.vorpal.research.kex.util.nullptrClass
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.ArrayLoadInst
import org.vorpal.research.kfg.ir.value.instruction.ArrayStoreInst
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.CastInst
import org.vorpal.research.kfg.ir.value.instruction.FieldLoadInst
import org.vorpal.research.kfg.ir.value.instruction.FieldStoreInst
import org.vorpal.research.kfg.ir.value.instruction.NewArrayInst
import org.vorpal.research.kfg.ir.value.instruction.ThrowInst
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log

class ExceptionPreconditionBuilderImpl(
    val ctx: ExecutionContext,
    override val targetException: Class,
) : ExceptionPreconditionBuilder {
    val cm get() = ctx.cm
    private val preconditionManager = ExceptionPreconditionManager(ctx)
    override fun build(location: Instruction, state: TraverserState): Set<PersistentSymbolicState> =
        when (targetException) {
            cm.nullptrClass -> persistentSymbolicState(
                path = when (location) {
                    is ArrayLoadInst -> persistentPathConditionOf(
                        PathClause(PathClauseType.NULL_CHECK, location, path {
                            (state.mkTerm(location.arrayRef) eq null) equality true
                        })
                    )

                    is ArrayStoreInst -> persistentPathConditionOf(
                        PathClause(PathClauseType.NULL_CHECK, location, path {
                            (state.mkTerm(location.arrayRef) eq null) equality true
                        })
                    )

                    is FieldLoadInst -> when {
                        location.isStatic -> persistentPathConditionOf()
                        else -> persistentPathConditionOf(
                            PathClause(PathClauseType.NULL_CHECK, location, path {
                                (state.mkTerm(location.owner) eq null) equality true
                            })
                        )
                    }

                    is FieldStoreInst -> when {
                        location.isStatic -> persistentPathConditionOf()
                        else -> persistentPathConditionOf(
                            PathClause(PathClauseType.NULL_CHECK, location, path {
                                (state.mkTerm(location.owner) eq null) equality true
                            })
                        )
                    }

                    is CallInst -> when {
                        location.isStatic -> persistentPathConditionOf()
                        else -> persistentPathConditionOf(
                            PathClause(PathClauseType.NULL_CHECK, location, path {
                                (state.mkTerm(location.callee) eq null) equality true
                            })
                        )
                    }

                    else -> unreachable { log.error("Instruction ${location.print()} does not throw null pointer") }
                }
            ).asSet()

            cm.arrayIndexOOBClass -> {
                val (arrayTerm, indexTerm) = when (location) {
                    is ArrayLoadInst -> state.mkTerm(location.arrayRef) to state.mkTerm(location.index)
                    is ArrayStoreInst -> state.mkTerm(location.arrayRef) to state.mkTerm(location.index)
                    else -> unreachable { log.error("Instruction ${location.print()} does not throw array index out of bounds") }
                }
                setOf(
                    persistentSymbolicState(
                        path = persistentPathConditionOf(
                            PathClause(PathClauseType.BOUNDS_CHECK, location, path {
                                (indexTerm ge 0) equality false
                            })
                        )
                    ),
                    persistentSymbolicState(
                        path = persistentPathConditionOf(
                            PathClause(PathClauseType.BOUNDS_CHECK, location, path {
                                (indexTerm lt arrayTerm.length()) equality false
                            }),
                        )
                    )
                )
            }

            cm.negativeArrayClass -> when (location) {
                is NewArrayInst -> location.dimensions.mapTo(mutableSetOf()) { length ->
                    persistentSymbolicState(
                        path = persistentPathConditionOf(
                            PathClause(PathClauseType.BOUNDS_CHECK, location, path {
                                (state.mkTerm(length) ge 0) equality false
                            }),
                        )
                    )
                }

                else -> unreachable { log.error("Instruction ${location.print()} does not throw negative array size") }
            }

            cm.classCastClass -> when (location) {
                is CastInst -> persistentSymbolicState(
                    path = persistentPathConditionOf(
                        PathClause(PathClauseType.BOUNDS_CHECK, location, path {
                            (state.mkTerm(location.operand) `is` location.type.kexType) equality false
                        }),
                    )
                ).asSet()

                else -> unreachable { log.error("Instruction ${location.print()} does not throw class cast") }
            }

            else -> when (location) {
                is ThrowInst -> when (location.throwable.type) {
                    targetException.asType -> persistentSymbolicState().asSet()
                    else -> emptySet()
                }

                is CallInst -> preconditionManager.resolve(location, targetException)
                    ?.build(location, state)
                    ?: persistentSymbolicState().asSet()

                else -> unreachable { log.error("Instruction ${location.print()} does not throw target exception") }
            }
        }
}

class DescriptorExceptionPreconditionBuilder(
    val ctx: ExecutionContext,
    override val targetException: Class,
    private val parameterSet: Set<Parameters<Descriptor>>,
) : ExceptionPreconditionBuilder {
    override fun build(location: Instruction, state: TraverserState): Set<PersistentSymbolicState> {
        val callInst = (location as? CallInst)
            ?: unreachable { log.error("Descriptor precondition is not valid for non-call instructions") }

        return parameterSet.mapTo(mutableSetOf()) { parameters ->
            var result = persistentSymbolicState()

            val mapping = buildMap {
                if (!callInst.isStatic)
                    this[parameters.instance!!.term] = state.mkTerm(callInst.callee)
                for ((argValue, argDescriptor) in callInst.args.zip(parameters.arguments)) {
                    this[argDescriptor.term] = state.mkTerm(argValue)
                }
            }.toMutableMap()
            for (descriptor in parameters.asList) {
                result += descriptor.asSymbolicState(callInst, mapping)
            }
            result
        }
    }

    private fun Descriptor.asSymbolicState(
        location: Instruction,
        mapping: MutableMap<Term, Term>
    ): PersistentSymbolicState = when (this) {
        is ConstantDescriptor -> this.asSymbolicState(location, mapping)
        is FieldContainingDescriptor<*> -> this.asSymbolicState(location, mapping)
        is ArrayDescriptor -> this.asSymbolicState(location, mapping)
    }

    private fun ConstantDescriptor.asSymbolicState(
        location: Instruction,
        @Suppress("UNUSED_PARAMETER")
        mapping: MutableMap<Term, Term>
    ) = persistentSymbolicState(
        path = persistentPathConditionOf(
            PathClause(
                PathClauseType.CONDITION_CHECK,
                location,
                path {
                    (this@asSymbolicState.term eq when (this@asSymbolicState) {
                        is ConstantDescriptor.Null -> const(null)
                        is ConstantDescriptor.Bool -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Byte -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Short -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Char -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Int -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Long -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Float -> const(this@asSymbolicState.value)
                        is ConstantDescriptor.Double -> const(this@asSymbolicState.value)
                    }) equality true
                }
            )
        )
    )

    private fun FieldContainingDescriptor<*>.asSymbolicState(
        location: Instruction,
        mapping: MutableMap<Term, Term>
    ): PersistentSymbolicState {
        var current = persistentSymbolicState()
        val objectTerm = run {
            val objectTerm = mapping.getOrDefault(this.term, this.term)
            when {
                objectTerm.type != this.type -> term { generate(this@asSymbolicState.type) }.also { replacement ->
                    current += persistentSymbolicState(
                        state = persistentClauseStateOf(
                            StateClause(location, state {
                                replacement equality (objectTerm `as` this@asSymbolicState.type)
                            })
                        )
                    )
                    mapping[this.term] = replacement
                }

                else -> objectTerm
            }
        }
        for ((field, descriptor) in this.fields) {
            current += descriptor.asSymbolicState(location, mapping)
            current += persistentSymbolicState(
                path = persistentPathConditionOf(
                    PathClause(PathClauseType.CONDITION_CHECK, location, path {
                        val fieldTerm = mapping.getOrDefault(descriptor.term, descriptor.term)
                        (objectTerm.field(field).load() eq fieldTerm) equality true
                    })
                )
            )
        }
        return current
    }

    private fun ArrayDescriptor.asSymbolicState(
        location: Instruction,
        mapping: MutableMap<Term, Term>
    ): PersistentSymbolicState {
        var current = persistentSymbolicState()
        val arrayTerm = run {
            val arrayTerm = mapping.getOrDefault(this.term, this.term)
            when {
                arrayTerm.type != this.type -> term { generate(this@asSymbolicState.type) }.also { replacement ->
                    current += persistentSymbolicState(
                        state = persistentClauseStateOf(
                            StateClause(location, state {
                                replacement equality (arrayTerm `as` this@asSymbolicState.type)
                            })
                        )
                    )
                    mapping[this.term] = replacement
                }

                else -> arrayTerm
            }
        }
        current += persistentSymbolicState(
            path = persistentPathConditionOf(
                PathClause(PathClauseType.CONDITION_CHECK, location, path {
                    (arrayTerm.length() eq this@asSymbolicState.length) equality true
                })
            )
        )
        for ((index, descriptor) in this.elements) {
            current += descriptor.asSymbolicState(location, mapping)
            current += persistentSymbolicState(
                path = persistentPathConditionOf(
                    PathClause(PathClauseType.CONDITION_CHECK, location, path {
                        val elementTerm = mapping.getOrDefault(descriptor.term, descriptor.term)
                        (arrayTerm[index].load() eq elementTerm) equality true
                    })
                )
            )
        }
        return current
    }
}


data class ConstraintExceptionPrecondition(
    val parameters: Parameters<Term>,
    val precondition: PersistentSymbolicState
)


class ConstraintExceptionPreconditionBuilder(
    val ctx: ExecutionContext,
    override val targetException: Class,
    private val parameterSet: Set<ConstraintExceptionPrecondition>,
) : ExceptionPreconditionBuilder {
    override fun build(location: Instruction, state: TraverserState): Set<PersistentSymbolicState> {
        val callInst = (location as? CallInst)
            ?: unreachable { log.error("Descriptor precondition is not valid for non-call instructions") }

        return parameterSet.mapTo(mutableSetOf()) { (parameters, precondition) ->
            val mapping = buildMap {
                if (!callInst.isStatic)
                    this[parameters.instance!!] = state.mkTerm(callInst.callee)
                for ((argValue, argDescriptor) in callInst.args.zip(parameters.arguments)) {
                    this[argDescriptor] = state.mkTerm(argValue)
                }
            }

            SymbolicStateTermRemapper(mapping).apply(precondition)
        }
    }
}
