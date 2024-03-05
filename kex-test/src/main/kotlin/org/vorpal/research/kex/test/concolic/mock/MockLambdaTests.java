package org.vorpal.research.kex.test.concolic.mock;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.vorpal.research.kex.intrinsics.AssertIntrinsics;


@SuppressWarnings("ALL")
public class MockLambdaTests {

    final class Final {

        final int foo() {
            return 42;
        }
    }

    public static void testFinal(Final a) {
        if (a.foo() == 228) {
            AssertIntrinsics.kexAssert(true);
        } else {
            AssertIntrinsics.kexAssert(true);
        }
    }

/*
    public void testIntSupplier(IntSupplier a) {
        if (a.getAsInt() == 42) {
            AssertIntrinsics.kexAssert(true);
        } else {
            AssertIntrinsics.kexAssert(true);
        }
    }

    public void testBoxedIntSupplier(Supplier<Integer> a) {
        if (a.get() == 333) {
            AssertIntrinsics.kexAssert(true);
        } else {
            AssertIntrinsics.kexAssert(true);
        }
    }

    public void testFunction(Function<Object, Long> a) {
        if (a.apply(null) == 909L) {
            AssertIntrinsics.kexAssert(true);
        } else {
            AssertIntrinsics.kexAssert(true);
        }
    }
*/
}

