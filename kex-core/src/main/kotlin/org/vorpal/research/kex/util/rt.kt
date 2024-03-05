@file:Suppress("unused")

package org.vorpal.research.kex.util

import org.vorpal.research.kex.config.Config
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.container.Container
import org.vorpal.research.kfg.container.JarContainer
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readLines

val Config.outputDirectory: Path get() = getPathValue("kex", "outputDir")!!.normalize()

@Deprecated("kex now does not write instrumented code into a directory")
val Config.instrumentedCodeDirectory: Path
    get() {
        val instrumentedDirName = getStringValue("output", "instrumentedDir", "instrumented")
        val instrumentedCodeDir = outputDirectory.resolve(instrumentedDirName).toAbsolutePath()
        if (!getBooleanValue("debug", "saveInstrumentedCode", false)) {
            deleteOnExit(instrumentedCodeDir)
        }
        return instrumentedCodeDir.normalize()
    }

val Config.compiledCodeDirectory: Path
    get() {
        val compiledCodeDirName = getStringValue("compile", "compileDir", "compiled")
        val compiledCodeDir = outputDirectory.resolve(compiledCodeDirName).toAbsolutePath()
        if (!getBooleanValue("debug", "saveCompiledCode", false)) {
            deleteOnExit(compiledCodeDir)
        }
        return compiledCodeDir.normalize()
    }

val Config.testcaseDirectory: Path
    get() {
        val testcaseDirName = getPathValue("testGen", "testsDir", "tests")
        return outputDirectory.resolve(testcaseDirName).toAbsolutePath().normalize()
    }

val Config.runtimeDepsPath: Path?
    get() = getPathValue("kex", "runtimeDepsPath")?.normalize()

val Config.libPath: Path?
    get() = getStringValue("kex", "libPath")?.let {
        runtimeDepsPath?.resolve(it)?.normalize()
    }

fun getJDKPath(): Path {
    return Paths.get(System.getProperty("java.home")).parent.toAbsolutePath()
}

fun getJavaPath(): Path = Paths.get(System.getProperty("java.home"), "bin", "java").toAbsolutePath()

val Config.isMockingEnabled: Boolean
    get() = getBooleanValue("mock", "enabled", false)

val Config.isMockitoClassesWorkaroundEnabled: Boolean
    get() = getBooleanValue("mock", "mockitoClassesWorkaround", true)

val Config.isMockitoJava8WorkaroundEnabled: Boolean
    get() = getBooleanValue("mock", "java8WorkaroundEnabled", false)

val Config.logTypeFix: Boolean
    get() = getBooleanValue("mock", "logTypeFix", false)

val Config.logStackTraceTypeFix: Boolean
    get() = getBooleanValue("mock", "logStackTraceTypeFix", false)

val Config.isExpectMocks: Boolean
    get() = getBooleanValue("mock", "expectMocks", false)

val Config.isFixConcreteLambdas: Boolean
    get() = getBooleanValue("mock", "concreteLambdasPassEnabled", false)

val Config.isEasyRandomExcludeLambdas: Boolean
    get() = getBooleanValue("mock", "easyRandomExcludeLambdas", false)

val Config.mockito: Container?
    get() {
        val libPath = libPath?.normalize() ?: return null
        val mockitoVersion = getStringValue("mock", "mockitoVersion") ?: return null
        val id = "inline"
//        val id = "core"
        val mockitoPath = libPath.resolve("mockito-$id-$mockitoVersion.jar").toAbsolutePath()
        return JarContainer(mockitoPath, Package("org.mockito"))
    }

val Config.mockitoWithDeps: List<Container>
    get() {
        val libPath = libPath?.normalize() ?: return emptyList()
        val mockitoVersion = getStringValue("mock", "mockitoVersion") ?: return emptyList()
        val inline =
            libPath.resolve("mockito-inline-$mockitoVersion.jar").normalize().toAbsolutePath()
        val core = libPath.resolve("mockito-core-$mockitoVersion.jar").normalize().toAbsolutePath()
        val bbVersion = "1.12.19"
        val bytebuddy = libPath.resolve("byte-buddy-$bbVersion.jar").normalize().toAbsolutePath()
        val bbAgent =
            libPath.resolve("byte-buddy-agent-$bbVersion.jar").normalize().toAbsolutePath()
        val objnesis = libPath.resolve("objenesis-3.3.jar").normalize().toAbsolutePath()
        listOf(
//            JarContainer(inline, Package("org.mockito")),
            JarContainer(core, Package("org.mockito")),
            JarContainer(objnesis, Package("org.objenesis")),
            JarContainer(bytebuddy, Package("net.bytebuddy")),
            JarContainer(bbAgent, Package("net.bytebuddy"))

        )
        return emptyList()
    }

val Config.byteBuddyAgent: Container?
    get() {
        val libPath = libPath?.normalize() ?: return null
        val version = "1.12.19" ?: return null
        val bytebuddyPath = libPath.resolve("byte-buddy-agent-$version.jar").toAbsolutePath()
        return JarContainer(bytebuddyPath, Package("net.bytebuddy"))
    }

// debug purposes, normally should be false
val Config.isMockTest: Boolean
    get() = getBooleanValue("mock", "test", false).also { if (it) println("Test feature invoked!") }


fun getRuntime(): Container? {
    if (!kexConfig.getBooleanValue("kex", "useJavaRuntime", true)) return null
    val libPath = kexConfig.libPath ?: return null
    val runtimeVersion = kexConfig.getStringValue("kex", "rtVersion") ?: return null
    return JarContainer(libPath.resolve("rt-${runtimeVersion}.jar"), Package.defaultPackage)
}

fun getIntrinsics(): Container? {
    val libPath = kexConfig.libPath ?: return null
    val intrinsicsVersion = kexConfig.getStringValue("kex", "intrinsicsVersion") ?: return null
    return JarContainer(
        libPath.resolve("kex-intrinsics-${intrinsicsVersion}.jar"),
        Package.defaultPackage
    )
}

fun getPathSeparator(): String = File.pathSeparator

fun getJunit(): Container? {
    val libPath = kexConfig.libPath ?: return null
    val junitVersion = kexConfig.getStringValue("kex", "junitVersion") ?: return null
    return JarContainer(
        libPath.resolve("junit-$junitVersion.jar").toAbsolutePath(),
        Package.defaultPackage
    )
}

fun getKexRuntime(): Container? {
    if (!kexConfig.getBooleanValue("kex", "useKexRuntime", true)) return null
    val libPath = kexConfig.libPath ?: return null
    val runtimeVersion = kexConfig.getStringValue("kex", "kexRtVersion") ?: return null
    return JarContainer(libPath.resolve("kex-rt-${runtimeVersion}.jar"), Package.defaultPackage)
}

fun getJvmVersion(): Int {
    val versionStr = System.getProperty("java.version")
    return """(1.)?(\d+)""".toRegex().find(versionStr)?.let {
        it.groupValues[2].toInt()
    } ?: unreachable { log.error("Could not detect JVM version: \"{}\"", versionStr) }
}

fun getJvmModuleParams(): List<String> = when (getJvmVersion()) {
    in 1..7 -> unreachable { log.error("Unsupported version of JVM: ${getJvmVersion()}") }
    8 -> emptyList()
    else -> buildList {
        val modules = kexConfig.runtimeDepsPath?.resolve("modules.info")?.readLines().orEmpty()
        for (module in modules) {
            add("--add-opens")
            add(module)
        }
        add("--illegal-access=warn")
    }
}
