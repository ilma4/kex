package org.vorpal.research.kex.smt.boolector

import org.vorpal.research.kex.util.deleteDirectory
import org.vorpal.research.kex.util.lowercased
import org.vorpal.research.kex.util.unzipArchive
import org.vorpal.research.kthelper.assert.ktassert
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.tryOrNull

abstract class BoolectorNativeLoader {
    companion object {
        const val BOOLECTOR_VERSION = "3.2.7"
        private val libraries = listOf("libboolector", "libboolector-java")
        private val supportedArchs = setOf("amd64", "x86_64")
        private val initializeCallback by lazy {
            System.setProperty("boolector.skipLibraryLoad", "true")

            val arch = System.getProperty("os.arch")
            ktassert(arch in supportedArchs) { log.error("Not supported arch: $arch") }

            val osProperty = System.getProperty("os.name").lowercased()
            val (zipName, libraryNames) = when {
                osProperty.startsWith("linux") -> {
                    "boolector-$BOOLECTOR_VERSION-linux64-native.zip" to libraries.map { "$it.so" }
                }
                else -> unreachable { log.error("Unknown OS: $osProperty") }
            }

            val tempDir = unzipArchive(
                BoolectorNativeLoader::class.java.classLoader.getResourceAsStream(zipName)
                    ?: unreachable { log.error("boolector archive not found") },
                "lib"
            )

            val resolvedLibraries = libraryNames.map { tempDir.resolve(it).toAbsolutePath().toString() }
            resolvedLibraries.forEach { System.load(it) }

            tryOrNull { deleteDirectory(tempDir) }
        }
    }

    init {
        initializeCallback
    }
}
