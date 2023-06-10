package org.openrndr.plugin.intellij.utils

import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.resolve.constants.DoubleValue
import org.openrndr.color.ColorXYZa
import org.openrndr.plugin.intellij.editor.ConstantValueContainer

internal typealias ArgumentMap = Map<ValueParameterDescriptor, ConstantValueContainer<*>>

/**
 * @return all constant [Double]s in the map in canonical order.
 */
internal val ArgumentMap.colorComponents: List<Double>
    get() = toList().sortedBy { it.first.index }.mapNotNull {
        ((it.second as? ConstantValueContainer.Constant)?.value as? DoubleValue)?.value
    }

internal fun ArgumentMap.computeWhitePoint(): ColorXYZa? = colorComponents.let {
    when (it.size) {
        3 -> ColorXYZa(it[0], it[1], it[2], 1.0)
        4 -> ColorXYZa(it[0], it[1], it[2], it[3])
        else -> null
    }
}