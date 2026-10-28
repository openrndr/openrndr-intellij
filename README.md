# OPENRNDR Plugin for IntelliJ

[Install it on the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/19736-openrndr)

Support for the [OPENRNDR](https://openrndr.org) creative coding framework in IntelliJ IDEA 2026.2 and later:
color swatches and a color picker, sectioned completion for the OPENRNDR and orx APIs, GLSL in shade styles,
program structure checks, and OPENRNDR Mode, a focused layout with one window, a play button and a console.

## OPENRNDR Mode

**View → OPENRNDR Mode** (Ctrl+Alt+P) switches the IDE to a minimal layout. Press it again to restore
everything as it was. The theme and color scheme are left alone.

| Area | In OPENRNDR Mode |
|---|---|
| Tool windows | All hidden except Run; no navigation bar, breadcrumbs or tool window stripes |
| Main toolbar | Only Search Everywhere and the sketch buttons below; the run widget and other widgets are hidden |
| Menu bar | Navigate, Code, Refactor, Build, Run, Tools and Git are hidden |
| File → New | Only **New OPENRNDR Project…** |

### Toolbar buttons

| Button | What it does |
|---|---|
| ▶ **Run Sketch** | Runs the file in the editor (Kotlin `fun main()` or Java `main`) with IntelliJ's own runner, never Gradle. It saves and builds first. |
| ■ **Stop** | Shown only while something is running; stops it |
| **New Program…** | OPENRNDR projects: creates `Name.kt` from the OPENRNDR program template. Dashes and spaces aren't allowed, and the first letter is made uppercase. |
| **Duplicate Program…** | OPENRNDR projects: copies the current program to a new file and suggests the next number (`Sketch01` → `Sketch02`, `Sketch` → `Sketch01`) |
| **OPENRNDR Guide** | OPENRNDR projects: opens [guide.openrndr.org](https://guide.openrndr.org) in an editor tab. Guide links stay in the tab, and other links open in your browser. |
| **Reformat Code**, **Comment with Line Comment**, **Rename** | The usual editor actions, as buttons |
| **Show Project Folder in Finder** | Opens the project root in Finder (Explorer or your file manager on other systems) |

### Console

Sketches started with ▶ get a console without the side toolbar. Soft wrap, scroll to end and clear
are on the top toolbar instead. **Clean Output** is on by default and can be toggled on that toolbar:

- **Hidden:** the `java … -classpath …` command line, the kotlin-logging banner, JDK native-access and
  `sun.misc.Unsafe` warnings, info/warn logging from OPENRNDR, LWJGL and JavaCPP, and MoltenVK chatter.
  Errors and everything your sketch prints stay.
- **Rewritten:** "Process finished with exit code …" becomes "Finished.", "Stopped.", "The program crashed (…)."
  or "The program stopped because of an error (exit code …)."

### New OPENRNDR Project

Asks for a name and a location, clones [openrndr-template](https://github.com/openrndr/openrndr-template)
there, replaces its git history with a fresh repository, and opens it. Needs `git` on your PATH.

## Always on

These work with or without OPENRNDR Mode. The OPENRNDR features are active in projects that depend on
OPENRNDR, and the Kotlin ones need the bundled Kotlin plugin.

### Colors

- **Gutter swatches and color picker.** `ColorRGBa(…)`, `rgb(…)`, `ColorRGBa.fromHex(…)`, `hsv(…)`, `hsl(…)`,
  named colors like `ColorRGBa.PINK` and orx presets get a swatch in the gutter. Clicking it opens a color
  picker that rewrites the code: components keep their linearity and number style, and a named color becomes
  `ColorRGBa.fromHex("#…")`. A whole drag in the picker is one undo step. Other orx color models (`ColorLABa`,
  `ColorOKLABa`, …) get a swatch without the picker.
- **Colors match your OPENRNDR version.** They're computed with the color classes from the project's own jars,
  so a linear `rgb(0.5, 0.5, 0.5)` shows as the `#bcbcbc` it draws.
- **Completion after `ColorRGBa.`** is split into sections with headings: *Constructors and factories*
  (`ColorRGBa(…)`, `fromHex`, `fromVector`), then *Color presets* (OPENRNDR's and orx's, each with a swatch),
  then everything else. Picking the constructor turns `ColorRGBa.` into `ColorRGBa(…)`.
- **Completion after a color value** (`ColorRGBa.PINK.`, `myColor.`, any OPENRNDR or orx color type) is split
  into *Properties* (`r`, `g`, `b`, `alpha`, `linearity`), *Linear Operations* (shown as operators:
  `ColorRGBa + ColorRGBa`, `ColorRGBa * Double`; picking one writes `color + `), *Operations* (`opacify`, `shade`, `shiftHue`, `mix`,
  orx's harmonies like `triadic`), *Query* (`luminance`, `minValue`, `maxValue`) and *Convert* (`toHSVa`,
  `toHSLa`, `toSRGB`, orx's `toOKLABa`), then everything else. The sections come from the project's OPENRNDR
  classes: stored fields are properties, functions returning colors are operations, `to…` functions convert,
  and the rest are queries. Each member shows the first sentence of its KDoc (when the library's sources are downloaded)
  instead of Kotlin's "for ColorRGBa in org.openrndr…" note.
- **Completion after `drawer.`** is split into *Drawing properties* (`fill`, `stroke`, `strokeWeight`, `lineCap`,
  `lineJoin`, `drawStyle`, `width`, `height`, `bounds`, then the other style properties), *Transforms* (`view`, `projection`, `model`, `ortho`,
  `perspective`, `translate`, `rotate`, `scale`, then `lookAt`, push/pop and `isolated`), then the drawing
  functions by kind: *Text* (`text`, `texts`, `textOnContour`, `writer`), *Shapes* (`circle`, `rectangle`,
  `lineSegment`, `contour`, `shape`, then the other primitives), *Images* (`image`, `images`) and *Advanced*
  (`vertexBuffer`, `vertexBufferInstances`), with KDoc summaries like after a color value and no `Unit`
  return types.
- **Completion in a program** (`program.`, `this.`, or plain Ctrl+Space inside `program { }` and its `extend { }`
  blocks) is split into *Events* (`ended`, `produceAssets`, `requestAssets`), *Drawing* (`drawer`,
  `backgroundColor`, `width`, `height`), *Images* (`loadImage`), *Text* (`loadFont`, `writer`), *Time* (`seconds`,
  `clock`, `frameCount`), *Input* (`mouse`, `keyboard`, `pointers`, `window`) and *Coroutines* (`launch`,
  `runBlocking`). Sections without items in a given spot get no heading.
- **Completion inside `transform { }`** (and `buildTransform { }`, or after a `TransformBuilder` value) starts with a
  *Transform* section: `translate`, `rotate`, `scale`, `multiply`, `transform`. Only TransformBuilder's own members
  and extensions go there, checked by declaration, so a top-level `translate(…)` or a `Program.rotate(…)` with the
  same name stays out. Nested in `program { }`, the innermost receiver (TransformBuilder) decides the sections.
- **Completion inside `renderTarget(…) { }`** (RenderTargetBuilder) has *Attach New* (`colorBuffer(format)`,
  `colorBuffer(name, format)`, `depthBuffer(format)`) and *Attach Existing* (overloads taking a `ColorBuffer`,
  `DepthBuffer`, `ArrayTexture`, `Cubemap`, `ArrayCubemap` or `VolumeTexture`). Overloads are placed by their
  parameters, without the `Unit` return type; the deprecated `colorBuffer(width, height, …)` stays out.
- **Completion after a render target, buffer or texture** (`RenderTarget`, `ColorBuffer`, `DepthBuffer`,
  `VertexBuffer`, `IndexBuffer`, `ArrayTexture`, `Cubemap`, `ArrayCubemap`, `VolumeTexture`) has sections per class:
  *Properties*, *Sampling* (filters, wrapping, mipmaps), *Write*, *Read*, *Copy*, *Save* (ColorBuffer), *Lifecycle*
  (`destroy`, `use`), and for RenderTarget *Attachments*, *Attach*, *Clear*, *Blend Modes* and *Bind*. The rest of
  the class (members and extensions, from orx too) goes to *Assorted*; `Unit` return types are hidden.
- **Completion after a `Shader`** has *Bind* (`begin`, `end`), *Uniforms*, *Bindings* (`buffer`, `image`), *Blocks*
  and *Lifecycle*; **after a `ShadeStyle` or inside `shadeStyle { }`**, *Transforms*, *Preambles*, *Parameters*,
  *Buffers*, *Images*, *Outputs* and *Combine* (`plus`); **after a `VertexFormat` or inside `vertexFormat { }`**,
  *Attributes* (`position`, `normal`, `color`, `textureCoordinate`, `attribute`), *Padding* and *Properties*.
  Members inherited from their interfaces (`uniform`, `parameter`) count as their own.
- **Completion after a `ComputeStyle` or inside `computeStyle { }`** has *Compute* (`computeTransform`,
  `computePreamble`, `workGroupSize`), *Parameters*, *Buffers*, *Images* and *Execute* (`execute`).
- **Completion in `compose { }`, `layer { }` and `aside { }`** (orx-compositor), and after a `Layer` or `Composite`,
  has *Draw* (`draw`, `mask`), *Layers* (`layer`, `aside`), *Filters* (`post`, `blend`, `apply`), *Properties*
  (`enabled`, `clearColor`, …) and, on a Composite, *Lifecycle*. Inside `draw { }` the program's sections apply.
- **Filters in `post(`:** typing `post(` in a compose block (or picking `post` from the list) opens a list of every
  Filter on the classpath: orx-fx's `Filter1to1`s in a section per package (*Blur*, *Color*, *Distort*, *Edges*,
  *Tonemap*, …), and all other filters (orx-fx's `Filter2to1`s, other libraries, your own) in *Assorted*. Each
  shows its package and KDoc summary; picking one writes `BoxBlur()` and adds the import.
- **Filters in `blend(`:** the same for `blend(` in a compose block, with only `Filter2to1`s: orx-fx's blend modes
  (`Add`, `Multiply`, `Screen`, `Overlay`, …) in *Blend Modes*, then every other `Filter2to1` on the classpath (your
  own too) in *Filter2to1*.
- **Completion after a filter** (`blur.` for a `BoxBlur`, any `Filter` subclass) **or in its `post(…) { }` block** has
  *Parameters*, *Apply*, *Properties* (`parameters`, `padding`, `depthBufferOut`) and *Lifecycle*.
- **Parameters section for any class:** members annotated with orx-parameters (`@DoubleParameter`,
  `@IntParameter`, `@ColorParameter`, `@BooleanParameter`, `@ActionParameter`, …) come first in a *Parameters*
  section, in the annotations' `order`: a filter's `spread` and `gain`, a Layer's `enabled` and `invertMask`, your
  own settings classes. This works after a value and in a block whose receiver is that class.
- **GLSL in shade and compute styles:** with the [GLSL Support](https://plugins.jetbrains.com/plugin/6993-glsl-support)
  plugin installed, the strings assigned to `fragmentTransform`, `vertexTransform`, `geometryTransform` and
  `computeTransform` (and the `…Preamble`s) are GLSL: highlighting, brace matching and GLSL completion. The
  transforms are wrapped in `void main() { … }` with OPENRNDR's variables declared (`x_fill`, `va_position`,
  `c_boundsPosition`, …, and `p_<name>` for `parameter("name", 1.0)` in the same block), so `x_fill.rgb`
  completes, without the tinted background of injected code. Kotlin's `$name` / `${…}` in the string count as names. Without the plugin, a banner above files
  with shader code offers to install it (or to dismiss the suggestion for good).
- **Completion after a `Vector2`, `Vector3` or `Vector4`** is split into *Properties* (`x`, `y`, …), *Linear
  Operations* (shown as operators: `Vector2 + Vector2`, `Vector2 * Double`), *Geometry* (`normalized`,
  `perpendicular`, `rotate`, `mix`, `clamp`, …), *Query* (`length`, `dot`, `distanceTo`, …), *Swizzle* (`xy0`, `yx`,
  …) and *Convert* (`toDoubleArray`, `vector3`, …), from the project's OPENRNDR classes.
- **Completion after a `List` or `MutableList`** is split into *Read* (`get`, `first`, `last`, …), *Write* (`add`,
  `addAll`, `set`, …), *Remove* (`remove`, `removeAt`, `clear`, …), *Sort* (`sort`, `sorted`, `sortBy`, …), *Query*
  (`size`, `isEmpty`, `count`, `any`, `sum`, …), *Search* (`contains`, `indexOf`, `find`, `binarySearch`, …), *Filter* (`filter`, `take`, `drop`, `distinct`, …), *Map*
  (`map`, `flatMap`, `forEach`, `groupBy`, `fold`, …) and *Random* (`random`, `shuffled`, `shuffle`), then
  *Assorted* for every other collection function. Only functions declared on collections go in these sections;
  scope functions like `let` and `apply` come after them. A read-only `List` has no Write or Remove section.
- **Collections of vectors** (`List<Vector2>`, `List<Vector3>`, …) start with a *Spatial* section: `bounds`, then
  `average`, `sum`, `map(sourceRect, targetRect)` and orx's `delaunayTriangulation`, `voronoiDiagram`. These are
  the extensions on lists of vectors, checked by declaration, so the standard `map { }` stays in *Map*.
- **Completion after a `Set` or `Map`** uses the same sections, with each type's own common functions: for sets,
  `intersect`, `subtract` and `union` under *Filter*; for maps, `get`, `getValue`, `keys`, `values` under *Read*,
  `put`, `getOrPut` under *Write*, `containsKey`, `containsValue` under *Search*, `filterKeys`, `mapValues` and so
  on. Sections without functions for a type (a map's *Random*) get no heading.
- **Completion after a `String`** uses the same sections (no Write or Remove: strings are immutable): *Read*
  (`get`, `first`, `substring`, …), *Sort* (`reversed`), *Query* (`length`, `isEmpty`, `isBlank`, …), *Search*
  (`contains`, `startsWith`, `endsWith`, `indexOf`, …), *Filter* (`trim`, `removePrefix`, `substringBefore`,
  `split`, `lines`, …), *Map* (`uppercase`, `lowercase`, `replace`, `padStart`, …), *Convert* (`toInt`,
  `toDouble`, `toIntOrNull`, `toRegex`, …), *Random*, *Assorted*.
- **Completion after `mouse.`** is split into *Properties* (`position`) and *Events* (`buttonUp`, `buttonDown`,
  `dragged`, `entered`, `exited`, `moved`, `scrolled`).
- **Completion after `keyboard.`** starts with *Events* (`keyDown`, `keyUp`, `keyRepeat`, `character`).
- **Any other type with OPENRNDR `Event<…>` properties** (orx types, your own classes, inherited events included)
  gets an *Events* section at the top of its completion list.
- **Swatches in completion** also show for color variables declared earlier in the same file.
- **Debugger:** color values in Variables, Watches and inline values get a swatch, linearity included.

### Program structure checks

An inspection (Settings → Editor → Inspections → OPENRNDR) warns about common mistakes, each with a quick fix
(Alt+Enter) when the move is safe:

| Mistake | Quick fix |
|---|---|
| `extend { }` inside `extend { }` (adds an extension every frame) | Move out of the outer `extend { }` |
| `configure { }` inside `program { }` (has no effect) | Move to `application { }` |
| `loadImage`, `loadFont`, `colorBuffer`, … in `application { }` outside `program { }` (no graphics context yet) | Move into `program { }` |
| `val x = loadImage(…)` and other loaders inside `extend { }` (loads every frame) | Load once before `extend { }` |

### Other

- **Asset paths in OPENRNDR projects:** the path in `loadImage("…")`, `loadFont("…", …)`, `loadSVG("…")`,
  `loadVideo("…")` and other loaders completes as you type, relative to the project root, which is where
  sketches run. Picking a folder moves into it. Cmd+click opens the file.

## Build & try it

Requires JDK 25. The Gradle daemon runs on JDK 25 too (`gradle/gradle-daemon-jvm.properties`), so your
`JAVA_HOME` can point elsewhere.

1. `./gradlew runIde` starts a sandbox IntelliJ IDEA 2026.2 with the plugin installed.
2. `./gradlew buildPlugin` builds `build/distributions/openrndr-intellij-<version>.zip`. Install it with
   Settings → Plugins → ⚙ → Install Plugin from Disk.
3. `./gradlew test` runs the tests. The completion tests put the OPENRNDR and orx 0.5.0 jars in their test
   project; the build downloads them (with sources) into the Gradle cache first.

The run configurations in `.run/` (Run Plugin, Run Tests, Run Verifications) do the same from the IDE.

The target IDE, which is also the lowest supported version, is `intellijIdea` in `gradle/libs.versions.toml`. The Kotlin features use the bundled Kotlin
plugin, and the guide tab the Web Browser (JCEF) plugin, both as optional dependencies. Without them, the rest
of the plugin still works, and the guide opens in IntelliJ's plain HTML tab.

The completion section headings use IntelliJ's internal `SeparatorLookupElement`, because its public grouped
completion drops the headings as soon as postfix templates follow the groups. The Plugin Verifier reports it as
internal API usage; `verifyPlugin` is set to fail only on compatibility problems, an invalid plugin and APIs
scheduled for removal. `docs/jetbrains-color-line-marker-bug.md` is a bug report for an IntelliJ issue with color gutter
swatches.

## Releasing

The GitHub workflows come from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template):

1. Set `version` in `gradle.properties` and describe the changes under `## [Unreleased]` in `CHANGELOG.md`.
2. A push to `main` runs **Build** (build, `check`, `verifyPlugin`) and creates a draft GitHub release with the
   Unreleased notes.
3. Publishing the draft runs **Release**: it moves the notes under the version in `CHANGELOG.md`, signs the plugin
   and publishes it to the Marketplace. It needs the `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
   `PRIVATE_KEY_PASSWORD` secrets.

The plugin ID `org.openrndr.plugin.intellij` must never change: the Marketplace knows the plugin by it.

## Where things live

The code is in `src/main/kotlin/org/openrndr/plugin/intellij/`, the tests in `src/test/kotlin/org/openrndr/plugin/intellij/`.

| File | Feature |
|---|---|
| `OpenrndrModeAction.kt` | The OPENRNDR Mode toggle and layout |
| `ActionGroupHider.kt` | Which toolbar items, menus and File → New entries are hidden |
| `RunSketchAction.kt`, `StopSketchAction.kt` | Play and stop |
| `SketchConsole.kt`, `CleanConsole.kt` | The sketch console and Clean Output rules |
| `OpenrndrProgramActions.kt` | New Program and Duplicate Program |
| `NewOpenrndrProjectAction.kt` | New OPENRNDR Project |
| `OpenrndrFileReferences.kt` | Asset path completion and navigation |
| `OpenrndrGuideAction.kt`, `OpenrndrGuideBrowser.kt` | The guide tab |
| `SketchEditGroup.kt`, `ShowProjectFolderAction.kt` | Editing and project folder buttons |
| `OpenrndrColorProvider.kt`, `OpenrndrColorRuntime.kt` | Gutter swatches and color picker; evaluating colors with the project's OPENRNDR |
| `OpenrndrColorCompletion.kt` | Completion swatches, sections and constructors |
| `OpenrndrDebuggerColors.kt` | Debugger color previews |
| `OpenrndrStructureInspection.kt` | Program structure checks and quick fixes |
| `fileTemplates/internal/` | The OPENRNDR program template |
| `src/test/kotlin/` | Tests for the color picker edits, the structure checks and completion sections |
