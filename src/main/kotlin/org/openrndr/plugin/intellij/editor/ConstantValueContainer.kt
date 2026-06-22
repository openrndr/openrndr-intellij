package org.openrndr.plugin.intellij.editor

import org.openrndr.color.ColorXYZa

/**
 * A resolved color-constructor argument value.
 *
 * In the K1 descriptor world this used to wrap `org.jetbrains.kotlin.resolve.constants.ConstantValue`.
 * With the Analysis API we only ever care about plain compile-time values (Double/Int/String) and
 * resolved white points, so we store those directly. This keeps the value descriptor-free and, crucially,
 * safe to use outside of an `analyze {}` block (Analysis API symbols/types must not escape that block).
 */
internal sealed class ConstantValueContainer {
    /** A compile-time constant argument value. Always a [Double], [Int] or [String]. */
    class Constant(val value: Any) : ConstantValueContainer()

    /** A resolved white point passed as the `ref` argument of a color model that uses one. */
    class WhitePoint(val value: ColorXYZa) : ConstantValueContainer()

    /**
     * A known argument that is not a color component, e.g. the `linearity` enum argument of
     * [org.openrndr.color.ColorRGBa]. Kept so the argument map can be complete, but ignored by all
     * consumers that only look at color components.
     */
    object Other : ConstantValueContainer()

    companion object {
        private val ALPHA = Constant(1.0)
        private val REF = WhitePoint(ColorXYZa.NEUTRAL)

        /**
         * The default value of a parameter we omitted from a call, when we know what that default is
         * without having to evaluate it.
         *
         * @param paramName the simple parameter name
         * @param shorthand whether the containing function is a color shorthand (`rgb`/`hsl`/`hsv`),
         * where the alpha parameter is named `a` instead of `alpha`
         */
        fun getDefaultValueIfKnown(paramName: String, shorthand: Boolean): ConstantValueContainer? = when {
            paramName == "alpha" || (paramName == "a" && shorthand) -> ALPHA
            paramName == "ref" -> REF
            paramName == "linearity" -> Other
            else -> null
        }
    }
}
