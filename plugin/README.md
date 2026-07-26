# IntelliJ plugin

This directory contains the IntelliJ Platform half of `refactor-cli`. It is a
self-contained Gradle build inside the repository, not a separate repository.

Run Gradle from the repository root:

```console
./plugin/gradlew -p plugin check
./plugin/gradlew -p plugin runIde
./plugin/gradlew -p plugin buildPlugin
```

On Windows, use `plugin\gradlew.bat` with the same arguments.

The Gradle wrapper intentionally lives here because the repository also contains
non-Gradle components. Repository-wide CI, dependency management, and IDE metadata
belong at the repository root.
