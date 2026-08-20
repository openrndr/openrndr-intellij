# OPENRNDR Plugin for IntelliJ

[Install it on the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/19736-openrndr)

<!-- Plugin description -->
Support for the [OPENRNDR framework](https://github.com/openrndr/openrndr) in Kotlin.

## Features

* ColorRGBa preview and color picker in the editor gutter
* ColorRGBa preview in the debugger view and autocomplete dialog

### ColorRGBa preview in the editor

<img width="463" alt="ColorRGBa preview in the editor" src="https://user-images.githubusercontent.com/6316604/181760872-87834eb6-71dd-4d01-9fdf-4fbf56528336.png">
<img width="442" alt="ColorRGBa preview in the debugger" src="https://user-images.githubusercontent.com/6316604/183113443-e7bbbf65-3305-463c-803c-a68bbf2b4a71.png">
<img width="490" alt="ColorRGBa preview in the autocomplete dialog" src="https://user-images.githubusercontent.com/6316604/183112801-bbd75d56-7cde-4623-bae6-7c8db4750475.png">

<!-- Plugin description end -->

[Using the color picker](https://user-images.githubusercontent.com/6316604/181222549-e1ab3f4b-28dc-4366-bf6b-6b7f2aa0fe28.webm)

## How it works

### The big picture

This is an IntelliJ Platform plugin that makes [OPENRNDR](https://github.com/openrndr/openrndr) colors
**visible** while you write and debug Kotlin code. OPENRNDR represents colors with a family of types — `ColorRGBa`
and a dozen other color models (`ColorHSVa`, `ColorLABa`, the orx color spaces, …) — that are just numbers in
the source code. The plugin renders those numbers as actual color swatches in four places:

* a clickable swatch in the **editor gutter** next to any color expression (with a color picker that edits your code).
* a swatch in the **autocomplete popup** next to color-valued completions.
* a swatch in the **debugger** variables view next to live color values.

Everything the plugin does reduces to one question asked in three different contexts: *"given this thing, what
color is it?"* For source code that means resolving and evaluating a Kotlin expression; for the debugger it means
reading fields off a live object over the debug connection.

The plugin is wired into the IDE entirely through extension points declared in
[`src/main/resources/META-INF/plugin.xml`](src/main/resources/META-INF/plugin.xml). It uses the **Kotlin Analysis
API**, so it works in both the legacy K1 and the new K2 Kotlin plugin modes.

### The parts and how they interact

The code lives under `src/main/kotlin/org/openrndr/plugin/intellij`, organized by IDE feature with a shared
`utils` foundation.

**Shared foundation (`utils/`)**: the heart of the plugin; the three features are thin adapters on top of it.

* [`ColorUtil`](src/main/kotlin/org/openrndr/plugin/intellij/utils/ColorUtil.kt): converts between OPENRNDR
  colors and AWT `Color`s, builds (via reflection) a map of all named color constants like `ColorRGBa.RED`, and
  exposes `resolveToColor()`, the central *"is this PSI element a color, and which one?"* function used by both
  the editor gutter and autocomplete.
* [`DescriptorUtil`](src/main/kotlin/org/openrndr/plugin/intellij/utils/DescriptorUtil.kt): Analysis API
  (`analyze {}`) helpers that recognize OPENRNDR color symbols, evaluate constructor arguments to compile-time
  constants (including a small constant-folder for non-`const` `val`s and arithmetic), and resolve reference
  white points.
* [`ArgumentMap`](src/main/kotlin/org/openrndr/plugin/intellij/utils/ArgumentMap.kt) /
  [`ConstantValueContainer`](src/main/kotlin/org/openrndr/plugin/intellij/editor/ConstantValueContainer.kt): the
  plain, Analysis-API-free data structures that resolved arguments are packed into, so values can safely be used
  *outside* the `analyze {}` block (a requirement of the Analysis API).
* [`ColorRGBaDescriptor`](src/main/kotlin/org/openrndr/plugin/intellij/editor/ColorRGBaDescriptor.kt): one entry
  per supported constructor / factory function (`ColorRGBa`, `rgb`, `fromHex`, `ColorHSVa`, every orx space …),
  each knowing how to turn resolved arguments **into** a color and a chosen color **back into** argument strings.

**1. Editor (`editor/`)**:
[`ColorRGBaColorProvider`](src/main/kotlin/org/openrndr/plugin/intellij/editor/ColorRGBaColorProvider.kt)
implements the IDE's `ElementColorProvider`. The IDE asks it for a color per element (it delegates to
`ColorUtil.resolveToColor`, which draws the gutter swatch), and when the user picks a new color, it rewrites the
source: it resolves the call to plain data while reading, then performs the PSI edit in a separate write command
(the Analysis API forbids doing both at once).

**2. Autocomplete (`completion/`)**:
[`ColorRGBaCompletionContributor`](src/main/kotlin/org/openrndr/plugin/intellij/completion/ColorRGBaCompletionContributor.kt)
runs after the normal completion contributors and decorates color-valued items with an icon: immediately for
known static colors, lazily (only when shown) for color-typed local properties.

**3. Debugger (`debugger/`)**:
[`ColorRGBaRendererProvider`](src/main/kotlin/org/openrndr/plugin/intellij/debugger/ColorRGBaRendererProvider.kt)
has no source to analyze, so it works over JDI: it claims any value implementing OPENRNDR's `ColorModel`, reads
its components off the live object (converting via a remote `toRGBa()` call when needed), and renders a swatch.

**Common**: all three features draw their swatch with the shared
[`RoundColorIcon`](src/main/kotlin/org/openrndr/plugin/intellij/ui/RoundColorIcon.kt), and the
color-picker undo label comes from
[`OpenrndrBundle`](src/main/kotlin/org/openrndr/plugin/intellij/OpenrndrBundle.kt), the localizable message bundle.

### Tests

Tests under `src/test/kotlin` boot a light in-memory IDE fixture with the real OPENRNDR jars on the classpath
(see `ColorRGBaTestCase`) so the Analysis API resolves colors just as it would in a real project, then assert the
gutter, and autocomplete produce the expected swatches.

## Building the plugin

1. Open the project
2. Run the `buildPlugin` Gradle task
3. Open your IntelliJ plugins menu and find the option "Install Plugin from Disk..." and point it to the zip file
   within `build/distributions/` in the project directory

## Running the tests

Run the Gradle `test` task. The tests boot a light in-memory IDE fixture with the OPENRNDR jars resolved
from the regular dependencies, so no extra setup is required. Cloning the intellij-community sources used to
be required for running tests, but it's no longer the case.

