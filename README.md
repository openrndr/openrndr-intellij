# OPENRNDR Plugin for IntelliJ

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
