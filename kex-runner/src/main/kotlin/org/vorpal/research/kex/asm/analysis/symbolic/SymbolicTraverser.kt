package org.vorpal.research.kex.asm.analysis.symbolic

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.util.analyzeOrTimeout
import org.vorpal.research.kex.asm.analysis.util.checkAsync
import org.vorpal.research.kex.compile.CompilationException
import org.vorpal.research.kex.compile.CompilerHelper
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.*
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.UnsafeGenerator
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.state.predicate.inverse
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.TermBuilder
import org.vorpal.research.kex.state.transformer.*
import org.vorpal.research.kex.trace.symbolic.*
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.Constant
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.ValueFactory
import org.vorpal.research.kfg.ir.value.instruction.*
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

data class TraverserState(
    val symbolicState: PersistentSymbolicState,
    val valueMap: PersistentMap<Value, Term>,
    val stackTrace: PersistentList<Pair<Method, Instruction>>,
    val typeInfo: PersistentMap<Term, Type>,
    val blockPath: PersistentList<BasicBlock>,
    val nullCheckedTerms: PersistentSet<Term>,
    val boundCheckedTerms: PersistentSet<Pair<Term, Term>>,
    val typeCheckedTerms: PersistentMap<Term, Type>
)

@Suppress("RedundantSuspendModifier")
class SymbolicTraverser(
    val ctx: ExecutionContext,
    private val rootMethod: Method
) : TermBuilder() {
    val cm: ClassManager
        get() = ctx.cm
    val types: TypeFactory
        get() = ctx.types
    val values: ValueFactory
        get() = ctx.values

    private val pathSelector: SymbolicPathSelector = DequePathSelector()
    private val callResolver: SymbolicCallResolver = DefaultCallResolver(ctx)
    private val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)
    private var currentState: TraverserState? = null
    private var testIndex = AtomicInteger(0)
    private val compilerHelper = CompilerHelper(ctx)


    private val nullptrClass = cm["java/lang/NullPointerException"]
    private val arrayIndexOOBClass = cm["java/lang/ArrayIndexOutOfBoundsException"]
    private val negativeArrayClass = cm["java/lang/NegativeArraySizeException"]
    private val classCastClass = cm["java/lang/ClassCastException"]

    companion object {
        @ExperimentalTime
        @DelicateCoroutinesApi
        fun run(context: ExecutionContext, targets: Set<Method>) {
            val executors = kexConfig.getIntValue("symbolic", "numberOfExecutors", 8)
            val timeLimit = kexConfig.getIntValue("symbolic", "timeLimit", 100)

            val actualNumberOfExecutors = maxOf(1, minOf(executors, targets.size))
            val coroutineContext = newFixedThreadPoolContext(actualNumberOfExecutors, "symbolic-dispatcher")
            runBlocking(coroutineContext) {
                withTimeoutOrNull(timeLimit.seconds) {
                    targets.map {
                        async { SymbolicTraverser(context, it).analyze() }
                    }.awaitAll()
                }
            }
        }
    }

    private val Type.symbolicType: KexType get() = kexType.rtMapped
    private val org.vorpal.research.kfg.ir.Class.symbolicClass: KexType get() = kexType.rtMapped

    private fun TraverserState.mkValue(value: Value): Term = when (value) {
        is Constant -> const(value)
        else -> valueMap.getValue(value)
    }

    suspend fun analyze() = rootMethod.analyzeOrTimeout(ctx.accessLevel) {
        processMethod(it)
    }

    private suspend fun processMethod(method: Method) {
        val initialArguments = buildMap {
            val values = this@SymbolicTraverser.values
            this[values.getThis(method.klass)] = `this`(method.klass.symbolicClass)
            for ((index, type) in method.argTypes.withIndex()) {
                this[values.getArgument(index, method, type)] = arg(type.symbolicType, index)
            }
        }
        pathSelector.add(
            TraverserState(
                persistentSymbolicState(),
                initialArguments.toPersistentMap(),
                persistentListOf(),
                persistentMapOf(),
                persistentListOf(),
                persistentSetOf(),
                persistentSetOf(),
                persistentMapOf()
            ),
            method.body.entry
        )

        while (pathSelector.hasNext()) {
            val (currentState, currentBlock) = pathSelector.next()
            this.currentState = currentState
            traverseBlock(currentBlock)
            yield()
        }
    }

    private suspend fun traverseBlock(bb: BasicBlock, startIndex: Int = 0) {
        for (index in startIndex..bb.instructions.lastIndex) {
            val inst = bb.instructions[index]
            traverseInstruction(inst)
        }
    }


    private suspend fun traverseInstruction(inst: Instruction) {
        when (inst) {
            is ArrayLoadInst -> traverseArrayLoadInst(inst)
            is ArrayStoreInst -> traverseArrayStoreInst(inst)
            is BinaryInst -> traverseBinaryInst(inst)
            is CallInst -> traverseCallInst(inst)
            is CastInst -> traverseCastInst(inst)
            is CatchInst -> traverseCatchInst(inst)
            is CmpInst -> traverseCmpInst(inst)
            is EnterMonitorInst -> traverseEnterMonitorInst(inst)
            is ExitMonitorInst -> traverseExitMonitorInst(inst)
            is FieldLoadInst -> traverseFieldLoadInst(inst)
            is FieldStoreInst -> traverseFieldStoreInst(inst)
            is InstanceOfInst -> traverseInstanceOfInst(inst)
            is InvokeDynamicInst -> traverseInvokeDynamicInst(inst)
            is NewArrayInst -> traverseNewArrayInst(inst)
            is NewInst -> traverseNewInst(inst)
            is PhiInst -> traversePhiInst(inst)
            is UnaryInst -> traverseUnaryInst(inst)
            is BranchInst -> traverseBranchInst(inst)
            is JumpInst -> traverseJumpInst(inst)
            is ReturnInst -> traverseReturnInst(inst)
            is SwitchInst -> traverseSwitchInst(inst)
            is TableSwitchInst -> traverseTableSwitchInst(inst)
            is ThrowInst -> traverseThrowInst(inst)
            is UnreachableInst -> traverseUnreachableInst(inst)
            is UnknownValueInst -> traverseUnknownValueInst(inst)
            else -> unreachable("Unknown instruction ${inst.print()}")
        }
    }

    private suspend fun traverseArrayLoadInst(inst: ArrayLoadInst) {
        var traverserState = currentState ?: return

        val arrayTerm = traverserState.mkValue(inst.arrayRef)
        val indexTerm = traverserState.mkValue(inst.index)
        val res = generate(inst.type.symbolicType)

        traverserState = nullabilityCheck(traverserState, inst, arrayTerm)
        traverserState = boundsCheck(traverserState, inst, indexTerm, arrayTerm.length())

        val clause = StateClause(inst, state { res equality arrayTerm[indexTerm].load() })
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, res)
            )
        )
    }

    private suspend fun traverseArrayStoreInst(inst: ArrayStoreInst) {
        var traverserState = currentState ?: return

        val arrayTerm = traverserState.mkValue(inst.arrayRef)
        val indexTerm = traverserState.mkValue(inst.index)
        val valueTerm = traverserState.mkValue(inst.value)

        traverserState = nullabilityCheck(traverserState, inst, arrayTerm)
        traverserState = boundsCheck(traverserState, inst, indexTerm, arrayTerm.length())

        val clause = StateClause(inst, state { arrayTerm[indexTerm].store(valueTerm) })
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                )
            )
        )
    }

    private suspend fun traverseBinaryInst(inst: BinaryInst) {
        val traverserState = currentState ?: return

        val lhvTerm = traverserState.mkValue(inst.lhv)
        val rhvTerm = traverserState.mkValue(inst.rhv)
        val resultTerm = generate(inst.type.symbolicType)

        val clause = StateClause(
            inst,
            state { resultTerm equality lhvTerm.apply(resultTerm.type, inst.opcode, rhvTerm) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, resultTerm)
            )
        )
    }

    private suspend fun traverseBranchInst(inst: BranchInst) {
        val traverserState = currentState ?: return
        val condTerm = traverserState.mkValue(inst.cond)

        val trueClause = PathClause(
            PathClauseType.CONDITION_CHECK,
            inst,
            path { condTerm equality true }
        )
        val falseClause = trueClause.copy(predicate = trueClause.predicate.inverse())

        checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    path = traverserState.symbolicState.path.add(trueClause)
                ),
                blockPath = traverserState.blockPath.add(inst.parent)
            )
        )?.let { pathSelector += it to inst.trueSuccessor }

        checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    path = traverserState.symbolicState.path.add(falseClause)
                ),
                blockPath = traverserState.blockPath.add(inst.parent)
            )
        )?.let { pathSelector += it to inst.falseSuccessor }

        currentState = null
    }

    private suspend fun traverseCallInst(inst: CallInst) {
        var traverserState = currentState ?: return

        val callee = when {
            inst.isStatic -> staticRef(inst.method.klass)
            else -> traverserState.mkValue(inst.callee)
        }
        val argumentTerms = inst.args.map { traverserState.mkValue(it) }
        if (!inst.isStatic) {
            traverserState = nullabilityCheck(traverserState, inst, callee)
        }

        val candidates = callResolver.resolve(traverserState, inst)
        for (candidate in candidates) {
            processMethodCall(traverserState, inst, candidate, callee, argumentTerms)
        }
        currentState = when {
            candidates.isEmpty() -> {
                val receiver = when {
                    inst.isNameDefined -> {
                        val res = generate(inst.type.symbolicType)
                        traverserState = traverserState.copy(
                            valueMap = traverserState.valueMap.put(inst, res)
                        )
                        res
                    }

                    else -> null
                }
                val callClause = StateClause(
                    inst, state {
                        val callTerm = callee.call(inst.method, argumentTerms)
                        receiver?.call(callTerm) ?: call(callTerm)
                    }
                )
                checkReachability(
                    traverserState.copy(
                        symbolicState = traverserState.symbolicState.copy(
                            clauses = traverserState.symbolicState.clauses.add(callClause)
                        )
                    )
                )
            }

            else -> {
                null
            }
        }
    }

    private suspend fun traverseCastInst(inst: CastInst) {
        var traverserState = currentState ?: return

        val operandTerm = traverserState.mkValue(inst.operand)
        val resultTerm = generate(inst.type.symbolicType)

        traverserState = typeCheck(traverserState, inst, operandTerm, resultTerm.type)
        val clause = StateClause(
            inst,
            state { resultTerm equality (operandTerm `as` resultTerm.type) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, resultTerm)
            )
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun traverseCatchInst(inst: CatchInst) {
    }

    private suspend fun traverseCmpInst(inst: CmpInst) {
        val traverserState = currentState ?: return

        val lhvTerm = traverserState.mkValue(inst.lhv)
        val rhvTerm = traverserState.mkValue(inst.rhv)
        val resultTerm = generate(inst.type.symbolicType)

        val clause = StateClause(
            inst,
            state { resultTerm equality lhvTerm.apply(inst.opcode, rhvTerm) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, resultTerm)
            )
        )
    }

    private suspend fun traverseEnterMonitorInst(inst: EnterMonitorInst) {
        var traverserState = currentState ?: return
        val monitorTerm = traverserState.mkValue(inst.owner)

        traverserState = nullabilityCheck(traverserState, inst, monitorTerm)
        val clause = StateClause(
            inst,
            state { enterMonitor(monitorTerm) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                )
            )
        )
    }

    private suspend fun traverseExitMonitorInst(inst: ExitMonitorInst) {
        val traverserState = currentState ?: return

        val monitorTerm = traverserState.mkValue(inst.owner)

        val clause = StateClause(
            inst,
            state { exitMonitor(monitorTerm) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                )
            )
        )
    }

    private suspend fun traverseFieldLoadInst(inst: FieldLoadInst) {
        var traverserState = currentState ?: return

        val objectTerm = when {
            inst.isStatic -> staticRef(inst.field.klass)
            else -> traverserState.mkValue(inst.owner)
        }
        val res = generate(inst.type.symbolicType)

        traverserState = nullabilityCheck(traverserState, inst, objectTerm)

        val clause = StateClause(
            inst,
            state { res equality objectTerm.field(inst.field.type.symbolicType, inst.field.name).load() }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, res)
            )
        )
    }

    private suspend fun traverseFieldStoreInst(inst: FieldStoreInst) {
        var traverserState = currentState ?: return

        val objectTerm = when {
            inst.isStatic -> staticRef(inst.field.klass)
            else -> traverserState.mkValue(inst.owner)
        }
        val valueTerm = traverserState.mkValue(inst.value)

        traverserState = nullabilityCheck(traverserState, inst, objectTerm)

        val clause = StateClause(
            inst,
            state { objectTerm.field(inst.field.type.symbolicType, inst.field.name).store(valueTerm) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, valueTerm)
            )
        )
    }

    private suspend fun traverseInstanceOfInst(inst: InstanceOfInst) {
        val traverserState = currentState ?: return
        val operandTerm = traverserState.mkValue(inst.operand)
        val resultTerm = generate(inst.type.symbolicType)

        val clause = StateClause(
            inst,
            state { resultTerm equality (operandTerm `is` inst.targetType.symbolicType) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, resultTerm)
            )
        )
    }

    private suspend fun traverseInvokeDynamicInst(inst: InvokeDynamicInst) {
        val traverserState = currentState ?: return
        val resolvedCall = invokeDynamicResolver.resolve(traverserState, inst)
        if (resolvedCall == null) {
            currentState = traverserState.copy(
                valueMap = traverserState.valueMap.put(inst, generate(inst.type.symbolicType))
            )
            return
        }
        val candidate = resolvedCall.method
        val callee = resolvedCall.instance?.let {
            traverserState.mkValue(it)
        } ?: staticRef(candidate.klass)
        val argumentTerms = resolvedCall.arguments.map { traverserState.mkValue(it) }

        processMethodCall(traverserState, inst, candidate, callee, argumentTerms)
        currentState = null
    }

    private suspend fun processMethodCall(
        state: TraverserState,
        inst: Instruction,
        candidate: Method,
        callee: Term,
        argumentTerms: List<Term>
    ) {
        var traverserState = state
        val newValueMap = traverserState.valueMap.builder().let { builder ->
            if (!candidate.isStatic) builder[values.getThis(candidate.klass)] = callee
            for ((index, type) in candidate.argTypes.withIndex()) {
                builder[values.getArgument(index, candidate, type)] = argumentTerms[index]
            }
            builder.build()
        }
        if (!candidate.isStatic) {
            traverserState = typeCheck(traverserState, inst, callee, candidate.klass.symbolicClass)
        }
        val newState = traverserState.copy(
            symbolicState = traverserState.symbolicState,
            valueMap = newValueMap,
            stackTrace = traverserState.stackTrace.add(inst.parent.method to inst)
        )
        pathSelector.add(
            newState, candidate.body.entry
        )
    }

    private suspend fun traverseNewArrayInst(inst: NewArrayInst) {
        var traverserState = currentState ?: return

        val dimensions = inst.dimensions.map { traverserState.mkValue(it) }
        val resultTerm = generate(inst.type.symbolicType)

        dimensions.forEach {
            traverserState = newArrayBoundsCheck(traverserState, inst, it)
        }
        val clause = StateClause(
            inst,
            state { resultTerm.new(dimensions) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                typeInfo = traverserState.typeInfo.put(resultTerm, inst.type),
                valueMap = traverserState.valueMap.put(inst, resultTerm),
                nullCheckedTerms = traverserState.nullCheckedTerms.add(resultTerm),
                typeCheckedTerms = traverserState.typeCheckedTerms.put(resultTerm, inst.type)
            )
        )
    }

    private suspend fun traverseNewInst(inst: NewInst) {
        val traverserState = currentState ?: return
        val resultTerm = generate(inst.type.symbolicType)

        val clause = StateClause(
            inst,
            state { resultTerm.new() }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                typeInfo = traverserState.typeInfo.put(resultTerm, inst.type),
                valueMap = traverserState.valueMap.put(inst, resultTerm),
                nullCheckedTerms = traverserState.nullCheckedTerms.add(resultTerm),
                typeCheckedTerms = traverserState.typeCheckedTerms.put(resultTerm, inst.type)
            )
        )
    }

    private suspend fun traversePhiInst(inst: PhiInst) {
        val traverserState = currentState ?: return
        currentState = traverserState.copy(
            valueMap = traverserState.valueMap.put(
                inst,
                traverserState.mkValue(inst.incomings.getValue(traverserState.blockPath.last()))
            )
        )
    }

    private suspend fun traverseUnaryInst(inst: UnaryInst) {
        var traverserState = currentState ?: return
        val operandTerm = traverserState.mkValue(inst.operand)
        val resultTerm = generate(inst.type.symbolicType)

        if (inst.opcode == UnaryOpcode.LENGTH) {
            traverserState = nullabilityCheck(traverserState, inst, operandTerm)
        }
        val clause = StateClause(
            inst,
            state { resultTerm equality operandTerm.apply(inst.opcode) }
        )
        currentState = checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    clauses = traverserState.symbolicState.clauses.add(clause)
                ),
                valueMap = traverserState.valueMap.put(inst, resultTerm)
            )
        )
    }

    private suspend fun traverseJumpInst(inst: JumpInst) {
        val traverserState = currentState ?: return
        pathSelector += traverserState.copy(
            blockPath = traverserState.blockPath.add(inst.parent)
        ) to inst.successor

        currentState = null
    }

    private suspend fun traverseReturnInst(inst: ReturnInst) {
        val traverserState = currentState ?: return
        val stackTrace = traverserState.stackTrace
        val receiver = stackTrace.lastOrNull()?.second
        currentState = when {
            receiver == null -> {
                val result = check(rootMethod, traverserState.symbolicState)
                if (result != null) {
                    report(result)
                }
                null
            }

            inst.hasReturnValue && receiver.isNameDefined -> {
                val returnTerm = traverserState.mkValue(inst.returnValue)
                traverserState.copy(
                    valueMap = traverserState.valueMap.put(receiver, returnTerm),
                    stackTrace = stackTrace.removeAt(stackTrace.lastIndex)
                )
            }

            else -> traverserState.copy(
                stackTrace = stackTrace.removeAt(stackTrace.lastIndex)
            )
        }
        if (receiver != null) {
            val nextInst = receiver.parent.indexOf(receiver) + 1
            traverseBlock(receiver.parent, nextInst)
        }
    }

    private suspend fun traverseSwitchInst(inst: SwitchInst) {
        val traverserState = currentState ?: return
        val key = traverserState.mkValue(inst.key)
        for ((value, branch) in inst.branches) {
            val path = PathClause(
                PathClauseType.CONDITION_CHECK,
                inst,
                path { (key eq traverserState.mkValue(value)) equality true }
            )
            checkReachability(
                traverserState.copy(
                    symbolicState = traverserState.symbolicState.copy(
                        path = traverserState.symbolicState.path.add(path)
                    ),
                    blockPath = traverserState.blockPath.add(inst.parent)
                )
            )?.let {
                pathSelector += it to branch
            }
        }
        val defaultPath = PathClause(
            PathClauseType.CONDITION_CHECK,
            inst,
            path { key `!in` inst.operands.map { traverserState.mkValue(it) } }
        )
        checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    path = traverserState.symbolicState.path.add(defaultPath)
                ),
                blockPath = traverserState.blockPath.add(inst.parent)
            )
        )?.let {
            pathSelector += it to inst.default
        }

        currentState = null
    }

    private suspend fun traverseTableSwitchInst(inst: TableSwitchInst) {
        val traverserState = currentState ?: return
        val key = traverserState.mkValue(inst.index)
        val min = inst.range.first
        for ((index, branch) in inst.branches.withIndex()) {
            val path = PathClause(
                PathClauseType.CONDITION_CHECK,
                inst,
                path { (key eq const(min + index)) equality true }
            )
            checkReachability(
                traverserState.copy(
                    symbolicState = traverserState.symbolicState.copy(
                        path = traverserState.symbolicState.path.add(path)
                    ),
                    blockPath = traverserState.blockPath.add(inst.parent)
                )
            )?.let {
                pathSelector += it to branch
            }
        }
        val defaultPath = PathClause(
            PathClauseType.CONDITION_CHECK,
            inst,
            path { key `!in` inst.range.map { const(it) } }
        )
        checkReachability(
            traverserState.copy(
                symbolicState = traverserState.symbolicState.copy(
                    path = traverserState.symbolicState.path.add(defaultPath)
                ),
                blockPath = traverserState.blockPath.add(inst.parent)
            )
        )?.let {
            pathSelector += it to inst.default
        }
        currentState = null
    }

    private suspend fun traverseThrowInst(inst: ThrowInst) {
        var traverserState = currentState ?: return
        val persistentState = traverserState.symbolicState
        val throwableTerm = traverserState.mkValue(inst.throwable)

        traverserState = nullabilityCheck(traverserState, inst, throwableTerm)
        val throwClause = StateClause(
            inst,
            state { `throw`(throwableTerm) }
        )
        checkExceptionAndReport(
            traverserState.copy(
                symbolicState = persistentState.copy(
                    clauses = persistentState.clauses.add(throwClause)
                )
            ),
            inst,
            throwableTerm
        )
        currentState = null
    }

    private suspend fun traverseUnreachableInst(inst: UnreachableInst) {
        unreachable<Unit>("Unexpected visit of $inst in symbolic traverser")
    }

    private suspend fun traverseUnknownValueInst(inst: UnknownValueInst) {
        unreachable<Unit>("Unexpected visit of $inst in symbolic traverser")
    }

    private suspend fun nullabilityCheck(state: TraverserState, inst: Instruction, term: Term): TraverserState {
        if (term in state.nullCheckedTerms) return state

        val persistentState = state.symbolicState
        val nullityClause = PathClause(
            PathClauseType.NULL_CHECK,
            inst,
            path { (term eq null) equality true }
        )
        checkExceptionAndReport(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(nullityClause)
                )
            ),
            inst,
            generate(nullptrClass.symbolicClass)
        )
        return state.copy(
            symbolicState = persistentState.copy(
                path = persistentState.path.add(
                    nullityClause.copy(predicate = nullityClause.predicate.inverse())
                )
            ),
            nullCheckedTerms = state.nullCheckedTerms.add(term)
        )
    }

    private suspend fun boundsCheck(
        state: TraverserState,
        inst: Instruction,
        index: Term,
        length: Term
    ): TraverserState {
        if (index to length in state.boundCheckedTerms) return state

        val persistentState = state.symbolicState
        val zeroClause = PathClause(
            PathClauseType.BOUNDS_CHECK,
            inst,
            path { (index ge 0) equality false }
        )
        val lengthClause = PathClause(
            PathClauseType.BOUNDS_CHECK,
            inst,
            path { (index lt length) equality false }
        )
        checkExceptionAndReport(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(zeroClause)
                )
            ),
            inst,
            generate(arrayIndexOOBClass.symbolicClass)
        )
        checkExceptionAndReport(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(lengthClause)
                )
            ),
            inst,
            generate(arrayIndexOOBClass.symbolicClass)
        )
        return state.copy(
            symbolicState = persistentState.copy(
                path = persistentState.path.add(
                    zeroClause.copy(predicate = zeroClause.predicate.inverse())
                ).add(
                    lengthClause.copy(predicate = lengthClause.predicate.inverse())
                )
            ),
            boundCheckedTerms = state.boundCheckedTerms.add(index to length)
        )
    }

    private suspend fun newArrayBoundsCheck(state: TraverserState, inst: Instruction, index: Term): TraverserState {
        if (index to index in state.boundCheckedTerms) return state

        val persistentState = state.symbolicState
        val zeroClause = PathClause(
            PathClauseType.BOUNDS_CHECK,
            inst,
            path { (index ge 0) equality false }
        )
        checkExceptionAndReport(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(zeroClause)
                )
            ),
            inst,
            generate(negativeArrayClass.symbolicClass)
        )
        return state.copy(
            symbolicState = persistentState.copy(
                path = persistentState.path.add(
                    zeroClause.copy(predicate = zeroClause.predicate.inverse())
                )
            ),
            boundCheckedTerms = state.boundCheckedTerms.add(index to index)
        )
    }

    private suspend fun typeCheck(state: TraverserState, inst: Instruction, term: Term, type: KexType): TraverserState {
        if (type !is KexPointer) return state
        val previouslyCheckedType = state.typeCheckedTerms[term]
        val currentlyCheckedType = type.getKfgType(ctx.types)
        if (previouslyCheckedType != null && currentlyCheckedType.isSubtypeOf(previouslyCheckedType)) {
            return state
        }

        val persistentState = state.symbolicState
        val typeClause = PathClause(
            PathClauseType.TYPE_CHECK,
            inst,
            path { (term `is` type) equality false }
        )
        checkExceptionAndReport(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(typeClause)
                )
            ),
            inst,
            generate(classCastClass.symbolicClass)
        )
        return state.copy(
            symbolicState = persistentState.copy(
                path = persistentState.path.add(
                    typeClause.copy(predicate = typeClause.predicate.inverse())
                )
            ),
            typeCheckedTerms = state.typeCheckedTerms.put(term, currentlyCheckedType)
        )
    }

    private suspend fun checkReachability(
        state: TraverserState
    ): TraverserState? {
        return check(rootMethod, state.symbolicState)?.let { state }
    }

    private suspend fun checkExceptionAndReport(
        state: TraverserState,
        inst: Instruction,
        throwable: Term
    ) {
        val throwableType = throwable.type.getKfgType(types)
        val catcher: BasicBlock? = state.run {
            var catcher = inst.parent.handlers.firstOrNull { throwableType.isSubtypeOf(it.exception) }
            if (catcher != null) return@run catcher
            for (i in stackTrace.indices.reversed()) {
                val block = stackTrace[i].second.parent
                catcher = block.handlers.firstOrNull { throwableType.isSubtypeOf(it.exception) }
                if (catcher != null) return@run catcher
            }
            null
        }
        when {
            catcher != null -> {
                val catchInst = catcher.instructions.first { it is CatchInst } as CatchInst
                pathSelector += state.copy(
                    valueMap = state.valueMap.put(catchInst, throwable),
                    blockPath = state.blockPath.add(inst.parent)
                ) to catcher
            }

            else -> {
                val params = check(rootMethod, state.symbolicState)
                if (params != null) {
                    report(params, "_throw_${throwableType.toString().replace("[/$.]".toRegex(), "_")}")
                }
            }
        }
    }

    private fun report(parameters: Parameters<Descriptor>, testPostfix: String = "") {
        val generator = UnsafeGenerator(
            ctx,
            rootMethod,
            rootMethod.klassName + testPostfix + testIndex.getAndIncrement()
        )
        generator.generate(parameters)
        val testFile = generator.emit()
        try {
            compilerHelper.compileFile(testFile)
        } catch (e: CompilationException) {
            log.error("Failed to compile test file $testFile")
        }
    }

    private suspend fun check(method: Method, state: SymbolicState): Parameters<Descriptor>? =
        method.checkAsync(ctx, state)
}
