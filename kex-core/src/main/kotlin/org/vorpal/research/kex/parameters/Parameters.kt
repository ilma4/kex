package org.vorpal.research.kex.parameters

import kotlinx.serialization.Serializable
import org.vorpal.research.kex.asm.manager.instantiationManager
import org.vorpal.research.kex.asm.util.AccessModifier
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.*
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexRtManager.isKexRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.state.predicate.CallPredicate
import org.vorpal.research.kex.state.term.CallTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.util.KfgTargetFilter
import org.vorpal.research.kex.util.MockingMode
import org.vorpal.research.kex.util.mockingMode
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kthelper.logging.debug
import org.vorpal.research.kthelper.logging.error
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.logging.warn
import kotlin.random.Random

@Serializable
data class Parameters<T>(
    val instance: T?,
    val arguments: List<T>,
    val statics: Set<T> = setOf()
) {
    val asList get() = listOfNotNull(instance) + arguments + statics

    override fun toString(): String = buildString {
        appendLine("instance: $instance")
        if (arguments.isNotEmpty())
            appendLine("args: ${arguments.joinToString("\n")}")
        if (statics.isNotEmpty())
            appendLine("statics: ${statics.joinToString("\n")}")
    }
}

fun <T, U> Parameters<T>.map(transform: (T) -> U): Parameters<U> {
    return Parameters(
        this.instance?.let(transform),
        this.arguments.map(transform),
        this.statics.mapTo(mutableSetOf(), transform)
    )
}

val Parameters<Any?>.asDescriptors: Parameters<Descriptor>
    get() {
        val context = Object2DescriptorConverter()
        return Parameters(
            context.convert(instance),
            arguments.map { context.convert(it) },
            statics.mapTo(mutableSetOf()) { context.convert(it) },
        )
    }

fun Parameters<Descriptor>.concreteParameters(
    cm: ClassManager,
    accessLevel: AccessModifier,
    random: Random
) = Parameters(
    instance?.concretize(cm, accessLevel, random),
    arguments.map { it.concretize(cm, accessLevel, random) },
    statics.mapTo(mutableSetOf()) { it.concretize(cm, accessLevel, random) },
)

fun Parameters<Descriptor>.filterStaticFinals(cm: ClassManager): Parameters<Descriptor> {
    val filteredStatics = statics
        .map { it.deepCopy() }
        .filterIsInstance<ClassDescriptor>()
        .mapNotNullTo(mutableSetOf()) { klass ->
            val kfgClass = (klass.type as KexClass).kfgClass(cm.type)
            for ((name, type) in klass.fields.keys.toSet()) {
                val field = kfgClass.getField(name, type.getKfgType(cm.type))
                if (field.isFinal) klass.remove(name to type)
            }
            when {
                klass.fields.isNotEmpty() -> klass
                else -> null
            }
        }
    return Parameters(instance, arguments, filteredStatics)
}

private val ignoredStatics: Set<KfgTargetFilter> by lazy {
    kexConfig.getMultipleStringValue("testGen", "ignoreStatic").flatMapTo(mutableSetOf()) {
        val filter = KfgTargetFilter.parse(it)
        listOf(filter, filter.rtMapped)
    }
}

fun Parameters<Descriptor>.filterIgnoredStatic(): Parameters<Descriptor> {
    val filteredStatics = statics
        .filterIsInstance<ClassDescriptor>()
        .filterTo(mutableSetOf()) { descriptor ->
            val typeName = descriptor.type.toString()
            ignoredStatics.all { ignored ->
                !ignored.matches(typeName)
            }
        }
    return Parameters(instance, arguments, filteredStatics)
}


fun createDescriptorToMock(
    allDescriptors: Collection<Descriptor>,
    types: TypeFactory
): Map<Descriptor, MockDescriptor> {
    val descriptorToMock = mutableMapOf<Descriptor, MockDescriptor>()
    val visited = mutableSetOf<Descriptor>()
    allDescriptors.map { it.replaceWithMock(types, descriptorToMock, visited) }
    return descriptorToMock
}

private fun Descriptor.insertMocks(
    types: TypeFactory,
    descriptorToMock: MutableMap<Descriptor, MockDescriptor>,
    withMocksInserted: MutableSet<Descriptor>
) {
    fun Descriptor.replaceWithMock() = replaceWithMock(types, descriptorToMock, withMocksInserted)

    if (this in withMocksInserted) return
    withMocksInserted.add(this)

    when (this) {
        is ConstantDescriptor -> {}
        is ClassDescriptor -> {
            fields.mapValuesTo(fields) { (_, value) -> value.replaceWithMock() }
        }

        is ObjectDescriptor -> {
            fields.mapValuesTo(fields) { (_, value) -> value.replaceWithMock() }
        }

        is MockDescriptor -> {
            fields.mapValuesTo(fields) { (_, value) -> value.replaceWithMock() }
            for ((_, returns) in methodReturns) {
                returns.mapTo(returns) { value -> value.replaceWithMock() }
            }
        }

        is ArrayDescriptor -> {
            elements.mapValuesTo(elements) { (_, value) -> value.replaceWithMock() }
        }
    }
}

fun Descriptor.isMockable(types: TypeFactory): Boolean {
    val klass = (type.getKfgType(types) as? ClassType)?.klass ?: return false
    val necessaryConditions = !klass.isFinal && !type.isKexRt && this is ObjectDescriptor
    return necessaryConditions && when (kexConfig.mockingMode) {
        MockingMode.FULL -> true
        MockingMode.BASIC -> !instantiationManager.isInstantiable(klass)
        null -> false
    }
}

fun Descriptor.requireMocks(types: TypeFactory, visited: MutableSet<Descriptor>): Boolean {
    if (this in visited) return false
    if (this.isMockable(types)) return true
    visited.add(this)
    fun Descriptor.requireMocks() = requireMocks(types, visited)
    return when (this) {
        is ConstantDescriptor -> false
        is ClassDescriptor -> fields.values.any { it.requireMocks() }
        is ObjectDescriptor -> fields.values.any { it.requireMocks() }
        is MockDescriptor -> (fields.values + allReturns).any { it.requireMocks() }
        is ArrayDescriptor -> elements.values.any { it.requireMocks() }
    }
}

private fun Descriptor.replaceWithMock(
    types: TypeFactory,
    descriptorToMock: MutableMap<Descriptor, MockDescriptor>,
    withMocksInserted: MutableSet<Descriptor>
): Descriptor {
    if (descriptorToMock[this] != null) return descriptorToMock[this]!!
    if (!this.isMockable(types)) {
        this.insertMocks(types, descriptorToMock, withMocksInserted)
        return this
    }

    this as ObjectDescriptor
    val klass = (this.type.getKfgType(types) as? ClassType)?.klass
    klass ?: return this.also { log.error { "Got null class to mock. Descriptor: $this" } }

    val mock = MockDescriptor(klass.methods, this)
        .also { it.fields.putAll(this@replaceWithMock.fields) }
        .also { log.debug { "Created mock descriptor for ${it.term}" } }
    withMocksInserted.add(this)
    descriptorToMock[this] = mock
    descriptorToMock[mock] = mock
    mock.insertMocks(types, descriptorToMock, withMocksInserted)
    return mock
}

fun setupMocks(
    methodCalls: List<Pair<CallPredicate, Descriptor>>,
    termToDescriptor: Map<Term, Descriptor>,
    descriptorToMock: Map<Descriptor, MockDescriptor>
) {
    for ((callPredicate, value) in methodCalls) {
        val call = callPredicate.call as CallTerm
        val mock = termToDescriptor[call.owner]?.let { descriptorToMock[it] ?: it }
        mock ?: log.warn { "No mock for $call" }
        if (mock is MockDescriptor) {
            mock.addReturnValue(call.method, value)
        }
    }
}