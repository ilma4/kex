package org.jetbrains.research.kex.util

import org.jetbrains.research.kfg.ir.Class

inline fun <reified T : Any> Any.castTo() = this as? T ?: unreachable { "Cast failure" }

@Suppress("UNCHECKED_CAST")
fun <T : Any> Any.uncheckedCastTo() = this as? T ?: unreachable { "Unchecked cast failure" }

val Class.canonicalDesc: String
    get() = this.fullname.replace('/', '.')