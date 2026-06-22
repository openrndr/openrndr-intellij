package org.openrndr.plugin.intellij.utils

import org.openrndr.color.ColorXYZa
import org.openrndr.plugin.intellij.editor.ConstantValueContainer

/**
 * Identifies a resolved color-constructor argument.
 *
 * Replaces the K1 `ValueParameterDescriptor` key: it carries only the plain data the consumers need
 * (the parameter [index], used to put components in canonical order, and its [name]) so that nothing
 * tied to an `analyze {}` session escapes into the argument map.
 */
internal data class ColorArgument(val index: Int, val name: String)

internal typealias ArgumentMap = Map<ColorArgument, ConstantValueContainer>

/**
 * @return all constant [Double]s in the map in canonical (parameter) order.
 */
internal val ArgumentMap.colorComponents: List<Double>
    get() = toList().sortedBy { it.first.index }.mapNotNull {
        (it.second as? ConstantValueContainer.Constant)?.value as? Double
    }

internal fun ArgumentMap.computeWhitePoint(): ColorXYZa? = colorComponents.let {
    when (it.size) {
        3 -> ColorXYZa(it[0], it[1], it[2], 1.0)
        4 -> ColorXYZa(it[0], it[1], it[2], it[3])
        else -> null
    }
}
