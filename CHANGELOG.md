# Changelog

## [Unreleased]

### Added

- Completion lists in sections with KDoc summaries: colors, `drawer.`, `program`, mouse and keyboard events,
  vectors, List/Set/Map/String, render targets, buffers, textures, shaders, shade and compute styles,
  vertex formats and filters
- orx-compositor support: sections in `compose { }` and `layer { }`, and every filter on the classpath in
  `post(…)` and `blend(…)`, sectioned by orx-fx package
- Members annotated with orx-parameters (`@DoubleParameter`, `@ColorParameter`, …) listed first, in any class
- GLSL in `fragmentTransform`, `vertexTransform`, `computeTransform` and the preambles, with the GLSL Support
  plugin (suggested when missing)
- Program structure inspection with quick fixes
- Asset path completion and navigation in `loadImage`, `loadFont` and the other loaders
- OPENRNDR Mode (View | OPENRNDR Mode): a minimal layout with play and stop buttons, New and Duplicate Program,
  New OPENRNDR Project, the OPENRNDR guide in an editor tab and a clean console
- Project Settings in OPENRNDR Mode: edit the project name, group and version, the OPENRNDR and orx versions
  and the snapshot repositories, then reload the Gradle project

### Changed

- Rewritten plugin: colors are evaluated with the project's own OPENRNDR instead of a bundled copy, so all
  color models and orx color spaces work with any OPENRNDR version
- Requires IntelliJ IDEA 2026.2 or later, and Java 25 to build
- The Kotlin plugin and the JCEF browser are optional dependencies
- Programs run from the OPENRNDR Mode play button use ZGC and, on Java 25 or later, compact object headers,
  unless the run configuration's VM options already choose a collector or set the headers

## [1.2.7] - 2026-08-20

### Changed

- Add a -mute flag to pass verification (plugin class path includes `intellij`)
- Update to Gradle 9.7.1
- Fix minor (mostly syntax) warnings

## [1.2.6] - 2026-08-19

### Added

- Add comments to source code
- Describe the plugin in the README
- Support for rgb(Int, Int, Int, Int)

### Changed

- Switch to Analysis API and K2 compiler
- Update OPENRNDR/ORX to 0.4.5
- Update Gradle to 9.6.0
- Update Kotlin to 2.4.0
- Update to simplified plugin template
- Update gradle-intellij-plugin to 1.16.1
- Raise minimum supported IntelliJ version to 2023.2 due to https://youtrack.jetbrains.com/issue/KT-58021
- Simplified build.gradle.kts based on the upstream plugin template

### Removed

- Fix tests not passing after update
- Fix color picker not updating source code after update

### Fixed

- Fix color linearity being ignored
- Deal with linearity difference in rgb() between openrndr 0.4.5 and 0.5.0

## [1.1.2] - 2023-06-11

### Changed

- Update Kotlin to 1.8.22
- Update OPENRNDR/ORX to 0.4.3

### Fixed

- Refactor extensions from `object` to `class` to avoid issues with Kotlin 
  singletons https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#caution

## [1.1.1] - 2023-03-19

### Changed

- Update Kotlin to 1.8.10
- Update OPENRNDR/ORX to 0.4.2
- Update gradle-intellij-plugin to 1.13.2

## [1.1.0] - 2022-12-24

### Added

- Support for 2022.3

## [1.0.1]

### Fixed

- Using color picker on `ColorRGBa.fromHex` with leading zeroes in the hex string produced unexpected results
- Color picker wasn't properly handling calls with function overloads such as `rgb("#f0f")`

## [1.0.0]

### Added

- ColorRGBa preview in the gutter
- Editing ColorRGBa with the color picker in the gutter
- ColorRGBa preview in debugger and expression evaluation dialog
- ColorRGBa preview in the auto-completion dialog

[Unreleased]: https://github.com/openrndr/openrndr-intellij/compare/v1.2.7...HEAD
[1.2.7]: https://github.com/openrndr/openrndr-intellij/compare/v1.2.6...v1.2.7
[1.2.6]: https://github.com/openrndr/openrndr-intellij/compare/v1.1.2...v1.2.6
[1.1.2]: https://github.com/openrndr/openrndr-intellij/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/openrndr/openrndr-intellij/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/openrndr/openrndr-intellij/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/openrndr/openrndr-intellij/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/openrndr/openrndr-intellij/commits/v1.0.0
