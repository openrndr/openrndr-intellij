# OPENRNDR Plugin for IntelliJ

# Building the plugin

1. Open the project
2. Run the `buildPlugin` Gradle task
3. Open your IntelliJ plugins menu and find the option "Install Plugin from Disk..." and point it to the zip file
   within `build/distributions/` in the project directory

# Running the tests

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