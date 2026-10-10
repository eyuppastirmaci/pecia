package dev.eyuppastirmaci.pecia.build

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a class appears in more than one classpath entry with different content. Identical copies and module
 * descriptors are ignored.
 */
abstract class CheckDuplicateClasses : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        // Class entry name -> content digest -> classpath entries that hold that content.
        val copies = sortedMapOf<String, MutableMap<String, MutableList<String>>>()

        for (entry in classpath.files) {
            forEachClass(entry) { name, bytes ->
                if (!name.endsWith("module-info.class")) {
                    copies.getOrPut(name) { mutableMapOf() }.getOrPut(digest(bytes)) { mutableListOf() }.add(entry.name)
                }
            }
        }

        val conflicts = copies.filterValues { it.size > 1 }.map { (name, versions) ->
            "$name: ${versions.values.flatten().sorted().joinToString()}"
        }
        report.get().asFile.writeText(conflicts.joinToString("") { "$it\n" })

        if (conflicts.isNotEmpty()) {
            throw GradleException("Classes with different content on the classpath:\n" + conflicts.joinToString("\n"))
        }
    }

    private fun forEachClass(entry: File, action: (String, ByteArray) -> Unit) {
        if (entry.isDirectory) {
            entry.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { action(it.relativeTo(entry).invariantSeparatorsPath, it.readBytes()) }
        } else if (entry.isFile) {
            ZipFile(entry).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { action(it.name, zip.getInputStream(it).use { input -> input.readBytes() }) }
            }
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
