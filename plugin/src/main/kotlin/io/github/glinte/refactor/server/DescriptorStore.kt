package io.github.glinte.refactor.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.security.MessageDigest

internal class DescriptorStore(
    private val port: Int,
    private val token: String,
) {
    private val log = logger<DescriptorStore>()
    private val directory = System.getenv("REFACTOR_AGENT_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".refactor-agent")
    private val ownedDescriptors = mutableSetOf<Path>()

    @Synchronized
    fun write(projects: Collection<Project>) {
        val roots = projects
            .filterNot(Project::isDisposed)
            .mapNotNull(Project::getBasePath)
            .map { Path.of(it).toAbsolutePath().normalize().toString() }
            .distinct()
            .sorted()
        Files.createDirectories(directory)
        restrictDirectoryPermissions()
        val desiredTargets = roots
            .mapTo(mutableSetOf()) { root ->
                directory.resolve("${hash(root)}-${ProcessHandle.current().pid()}.json")
            }
        ownedDescriptors.filterNot(desiredTargets::contains).forEach { obsolete ->
            runCatching { Files.deleteIfExists(obsolete) }
                .onFailure { log.warn("Could not remove obsolete refactor descriptor $obsolete", it) }
            ownedDescriptors.remove(obsolete)
        }
        if (roots.isEmpty()) return

        roots.forEach { root ->
            val descriptor = buildJsonObject {
                put("protocolVersion", 1)
                put("idePid", ProcessHandle.current().pid())
                put("ideBuild", ApplicationInfo.getInstance().build.asString())
                put("port", port)
                put("token", token)
                put("projects", buildJsonArray { add(root) })
            }
            val contents = Json.encodeToString(descriptor)
            val target = directory.resolve("${hash(root)}-${ProcessHandle.current().pid()}.json")
            val temporary = Files.createTempFile(directory, ".descriptor-", ".tmp")
            try {
                Files.writeString(temporary, contents)
                restrictFilePermissions(temporary)
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
                ownedDescriptors.add(target)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    @Synchronized
    fun close() {
        ownedDescriptors.forEach { path ->
            runCatching { Files.deleteIfExists(path) }
                .onFailure { log.warn("Could not remove refactor descriptor $path", it) }
        }
        ownedDescriptors.clear()
    }

    private fun restrictDirectoryPermissions() {
        runCatching {
            Files.setPosixFilePermissions(
                directory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        restrictWindowsAcl(directory, inherit = true)
    }

    private fun restrictFilePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        restrictWindowsAcl(path, inherit = false)
    }

    private fun restrictWindowsAcl(path: Path, inherit: Boolean) {
        runCatching {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: return@runCatching
            val builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path))
                .setPermissions(AclEntryPermission.entries.toSet())
            if (inherit) {
                builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            }
            view.acl = listOf(builder.build())
        }.onFailure {
            log.warn("Could not restrict Windows ACL for refactor descriptor path $path", it)
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
