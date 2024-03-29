# Changelog

## [Unreleased]

### Added

### Changed
- Update Kotlin to 1.9.22
- Update gradle-intellij-plugin to 1.16.1
- Update OPENRNDR/ORX to 0.4.4
- Raise minimum supported IntelliJ version to 2023.2 due to https://youtrack.jetbrains.com/issue/KT-58021

### Removed

### Fixed

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

[Unreleased]: https://github.com/openrndr/openrndr-intellij/compare/v1.1.2...HEAD
[1.1.2]: https://github.com/openrndr/openrndr-intellij/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/openrndr/openrndr-intellij/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/openrndr/openrndr-intellij/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/openrndr/openrndr-intellij/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/openrndr/openrndr-intellij/commits/v1.0.0
