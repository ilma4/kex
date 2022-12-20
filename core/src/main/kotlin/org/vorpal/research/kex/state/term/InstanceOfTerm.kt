package org.vorpal.research.kex.state.term

import kotlinx.serialization.Serializable
import org.vorpal.research.kex.InheritorOf
import org.vorpal.research.kex.ktype.KexBool
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.transformer.Transformer
import org.vorpal.research.kthelper.defaultHashCode

@InheritorOf("Term")
@Serializable
class InstanceOfTerm(val checkedType: KexType, val operand: Term) : Term() {
    override val name = "$operand instanceof $checkedType"
    override val type: KexType = KexBool
    override val subTerms by lazy { listOf(operand) }

    override fun <T: Transformer<T>> accept(t: Transformer<T>): Term =
            when (val tOperand = t.transform(operand)) {
                operand -> this
                else -> term { tf.getInstanceOf(checkedType, tOperand) }
             }

    override fun hashCode() = defaultHashCode(super.hashCode(), checkedType)
    override fun equals(other: Any?): Boolean {
        if (other?.javaClass != this.javaClass) return false
        other as InstanceOfTerm
        return super.equals(other) && this.checkedType == other.checkedType
    }
}
