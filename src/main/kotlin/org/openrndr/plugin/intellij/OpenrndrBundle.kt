package org.openrndr.plugin.intellij

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.OpenrndrBundle"

/**
 * Accessor for the plugin's localizable strings, backed by the `messages/OpenrndrBundle.properties` resource
 * bundle. Using a [DynamicBundle] (rather than raw string literals) lets the IDE resolve and validate message
 * keys and supports localization. Currently the only message is the undo label for the color-picker edit.
 */
object OpenrndrBundle : DynamicBundle(BUNDLE) {
    /** Looks up the message for [key] and formats it with [params]. */
    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}