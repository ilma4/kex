package org.vorpal.research.kex.test.generation

import org.vorpal.research.kex.intrinsics.AssertIntrinsics.kexAssert
import org.vorpal.research.kex.intrinsics.AssertIntrinsics.kexUnreachable

class AbstractClassTests {
    abstract class AbstractClass(val x: Int, val y: Int)

    class Impl(x: Int, y: Int, val z: Int) : AbstractClass(x, y)

    class SecondImpl(x: Int, y: Int) : AbstractClass(x, y)

    class ThirdImpl(x: Int, y: Int, val inner: AbstractClass) : AbstractClass(x, y)

    fun testAbstractClass(klass: AbstractClass) {
        if (klass.x > klass.y) {
            kexAssert(true)
        }
        if (klass is SecondImpl) {
            kexAssert(true)
        } else if (klass is ThirdImpl) {
            kexUnreachable()
        }
    }


    interface Interface {
        val value: Int
    }

    class InterfaceImpl(override val value: Int) : Interface
    class ACAndInterface(x: Int) : AbstractClass(x, -43), Interface {
        override val value: Int
            get() = x
    }

    fun testInterface(interfaceInstance: Interface) {
        if (interfaceInstance.value > 50) {
            kexAssert(true)
            if (interfaceInstance is ACAndInterface) {
                kexAssert(true)
            }
        } else if (interfaceInstance is InterfaceImpl) {
            kexAssert(true)
        } else {
            kexAssert(true)
        }
    }
}