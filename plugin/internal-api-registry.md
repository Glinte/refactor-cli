# IntelliJ internal API registry

This plugin targets the pinned IntelliJ 2026.2 release line. Every internal API use is
listed here so an IDE upgrade has an explicit review checklist.

The Gradle project deliberately keeps `untilBuild = "262.*"`. Its verifier warning about
forward compatibility is accepted: silently loading these APIs on an unreviewed platform
line would be less safe than requiring an explicit upgrade.

| Symbol | Why no stable alternative | Failure if removed |
| --- | --- | --- |
| `com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl` and `getFileWatcher()` | The public VFS API exposes watch registration but not the active local watcher's health. Honest `status` output requires the platform implementation and its watcher. | `status.watcherOperational` cannot be reported. Full refresh remains a safe fallback. |
| `com.intellij.openapi.vfs.impl.local.FileWatcher` and `isOperational()` | There is no public watcher-health query. The value is informational only; synchronization never trusts it to skip the full refresh. | `status.watcherOperational` cannot be reported. Full refresh remains a safe fallback. |
