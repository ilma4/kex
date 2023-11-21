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
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kthelper.logging.error
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.logging.warn
import kotlin.random.Random

@Serializable
data class Parameters<T>(
    val instance: T?,
    val arguments: List<T>,
    val statics: Set<T> = setOf(),
    val others: Set<T> = setOf()
) {
    val asList get() = listOfNotNull(instance) + arguments + statics + others

    override fun toString(): String = buildString {
        appendLine("instance: $instance")
        if (arguments.isNotEmpty())
            appendLine("args: ${arguments.joinToString("\n")}")
        if (statics.isNotEmpty())
            appendLine("statics: ${statics.joinToString("\n")}")
        if (others.isNotEmpty()) {
            appendLine("others: ${others.joinToString("\n")}")
        }
    }
}

val Parameters<Any?>.asDescriptors: Parameters<Descriptor>
    get() {
        val context = Object2DescriptorConverter()
        return Parameters(
            context.convert(instance),
            arguments.map { context.convert(it) },
            statics.mapTo(mutableSetOf()) { context.convert(it) },
            others.mapTo(mutableSetOf()) { context.convert(it) }
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
    others.mapTo(mutableSetOf()) { it.concretize(cm, accessLevel, random) }
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
    return Parameters(instance, arguments, filteredStatics, others)
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
    return Parameters(instance, arguments, filteredStatics, others)
}

private fun Descriptor.insertMocks(
    types: TypeFactory,
    descriptorToMock: MutableMap<Descriptor, MockDescriptor>,
    visited: MutableSet<Descriptor>
) {
    fun <T> insertIntoMap(map: MutableMap<T, Descriptor>) =
        map.mapValuesTo(map) { (_, value) -> value.replaceWithMock(types, descriptorToMock, visited) }

    if (this in visited) return
    visited.add(this)

    when (this) {
        is ConstantDescriptor -> {}
        is ClassDescriptor -> {
            insertIntoMap(this.fields)
//            this.fields.mapValuesTo(this.fields) { (_, value) -> value.replaceWithMock(types, descriptorToMock) }
        }

        is ObjectDescriptor -> {
            insertIntoMap(this.fields)
//            this.fields.mapValuesTo(this.fields) { (_, value) -> value.replaceWithMock(types, descriptorToMock) }
        }

        is MockDescriptor -> {
//            insertIntoMap(this.fields)
            this.fields.mapValuesTo(this.fields) { (_, value) ->
                value.replaceWithMock(
                    types,
                    descriptorToMock,
                    visited
                )
            }
            for ((_, returns) in methodReturns) {
                returns.mapTo(returns) { value -> value.replaceWithMock(types, descriptorToMock, visited) }
            }
        }

        is ArrayDescriptor -> {
            insertIntoMap(this.elements)
//            this.elements.mapValuesTo(this.elements) { (_, value) -> value.replaceWithMock(types, descriptorToMock) }
        }
    }
}

private fun Descriptor.notMockable(types: TypeFactory): Boolean {
    val klass = (type.getKfgType(types) as? ClassType)?.klass
    return klass == null || instantiationManager.isInstantiable(klass) || type.isKexRt || this !is ObjectDescriptor
}

private fun Descriptor.replaceWithMock(
    types: TypeFactory,
    descriptorToMock: MutableMap<Descriptor, MockDescriptor>,
    visited: MutableSet<Descriptor>
): Descriptor {
    val descriptor = this
    if (descriptorToMock[descriptor] != null) return descriptorToMock[descriptor]!!
    val klass = (descriptor.type.getKfgType(types) as? ClassType)?.klass
    return if (descriptor.notMockable(types)) {
        descriptor.insertMocks(types, descriptorToMock, visited)
        descriptor
    } else {
        klass ?: return descriptor.also { log.error { "Got null class to mock." } }
        val mock = if (descriptor is ObjectDescriptor) {
            MockDescriptor(klass.methods, descriptor).also { it.fields.putAll(descriptor.fields) }
        } else {
            log.warn { "Strange descriptor to mock. Expected ObjectDescriptor. Got: $descriptor" }
            MockDescriptor(descriptor.term, descriptor.type as KexClass, klass.methods)
        }
        visited.add(descriptor)
        descriptorToMock[descriptor] = mock
        mock.insertMocks(types, descriptorToMock, visited)
        mock
    }
}

private fun Collection<Descriptor>.replaceUninstantiableWithMocks(
    types: TypeFactory,
    descriptorToMock: MutableMap<Descriptor, MockDescriptor>
): Collection<Descriptor> {
    val visited = mutableSetOf<Descriptor>()
    return map { it.replaceWithMock(types, descriptorToMock, visited) }
}

fun Parameters<Descriptor>.generateInitialMocks(
    types: TypeFactory
): Pair<Parameters<Descriptor>, Map<Descriptor, MockDescriptor>> {
    val descriptorToMock = mutableMapOf<Descriptor, MockDescriptor>()
    val mockedArguments = arguments.replaceUninstantiableWithMocks(types, descriptorToMock).toList()
    val mockedStatics = statics.replaceUninstantiableWithMocks(types, descriptorToMock).toSet()
    val mockedOthers = others.replaceUninstantiableWithMocks(types, descriptorToMock).toSet()

    return Parameters(instance, mockedArguments, mockedStatics, mockedOthers) to descriptorToMock
}


/*
fun Parameters<Descriptor>.generateMocks(
    methodCalls: List<Pair<CallPredicate, Descriptor>>,
    termToDescriptor: MutableMap<Term, Descriptor>,
    cm: ClassManager,
    accessLevel: AccessModifier
): Parameters<Descriptor> {
    val withMocks = this.generateInitialMocks(cm.type)
    setupMocks(methodCalls, termToDescriptor)
    return withMocks
}
*/

fun setupMocks(
    methodCalls: List<Pair<CallPredicate, Descriptor>>,
    termToDescriptor: Map<Term, Descriptor>,
    descriptorToMock: Map<Descriptor, MockDescriptor>
) {
    for ((callPredicate, value) in methodCalls) {
        val call = callPredicate.call as CallTerm
        val mock = termToDescriptor[call.owner]?.let { descriptorToMock[it] ?: it }
        if (mock is MockDescriptor) {
            mock.addReturnValue(call.method, value)
        }
    }
}