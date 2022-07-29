# OPENRNDR Plugin for IntelliJ

<!-- Plugin description -->
Support for the [OPENRNDR framework](https://github.com/openrndr/openrndr) in Kotlin.

## Features

* ColorRGBa preview and editor in the editor gutter
* ColorRGBa preview in the debugger view

<!-- Plugin description end -->

### ColorRGBa preview in the editor

<img width="300" alt="ColorRGBa preview in the editor" src="https://user-images.githubusercontent.com/6316604/181222738-796779d7-ab92-4e2c-b7f3-ec0d33638c5a.png">
<img width="472" alt="ColorRGBa preview in the debugger" src="https://user-images.githubusercontent.com/6316604/181223088-30a40665-2f53-4068-b803-5f388be83232.png">

[Using the color picker](https://user-images.githubusercontent.com/6316604/181222549-e1ab3f4b-28dc-4366-bf6b-6b7f2aa0fe28.webm)

## Building the plugin

1. Open the project
2. Run the `buildPlugin` Gradle task
3. Open your IntelliJ plugins menu and find the option "Install Plugin from Disk..." and point it to the zip file
   within `build/distributions/` in the project directory

## Running the tests

The plugin tests have a dependency on openrndr-color which unfortunately means intellij-community sources will be needed
to run the tests.

Clone https://github.com/JetBrains/intellij-community ([consult their README](https://github.com/JetBrains/intellij-community#getting-intellij-idea-community-edition-source-code=))
in a directory adjacent to where you cloned this project so the resulting directory layout looks like the following.

```cmd
/projects/
├───intellij-community
└───openrndr-intellij
```

Now you can run the Gradle `test` task.
