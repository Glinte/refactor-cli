# IntelliJ plugin

This directory contains the IntelliJ Platform half of `refactor-cli`. It is pinned to
IntelliJ IDEA 2026.2 and is a self-contained Gradle build inside the repository.

The plugin starts an authenticated loopback JSON-RPC server, synchronizes externally
changed files into the VFS, and delegates resolution, Find Usages, and rename operations
to IntelliJ PSI/refactoring APIs. Java and Kotlin are required dependencies; Python is
optional. Spring and Jakarta Persistence support are consumed opportunistically when
those bundled IDEA plugins contribute semantic non-source references.

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

Internal IntelliJ APIs are tracked in [`internal-api-registry.md`](internal-api-registry.md).
