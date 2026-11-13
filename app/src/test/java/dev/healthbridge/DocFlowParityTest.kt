package dev.healthbridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DocFlowParityTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, "docs/ux-flows.md").isFile && File(it, "app/src/main").isDirectory }
        ?: error("Cannot locate repository root containing docs/ux-flows.md and app/src/main")
    private val document = File(root, "docs/ux-flows.md")
    private val text = document.readText()
    private val declaration = Regex("\\b(?:class|interface|object)\\s+([A-Z][A-Za-z0-9_]*)")
    private val declarations = File(root, "app/src/main").walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java") }
        .flatMap { declaration.findAll(it.readText()).map { match -> match.groupValues[1] } }
        .toSet()
    private val identifier = Regex("\\b[A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*\\b")

    @Test fun fileAliasesResolve() {
        val aliasTable = text.substringAfter("| Alias | File |", "").substringBefore("\n\n")
        val paths = Regex("\\[[^]]+]\\(([^)]+)\\)").findAll(aliasTable).map { it.groupValues[1] }.toList()
        assertTrue("ux-flows.md must contain file aliases", paths.isNotEmpty())
        paths.forEach { path ->
            val target = File(document.parentFile, path).canonicalFile
            assertTrue("ux-flows.md alias does not resolve inside repository: $path",
                target.toPath().startsWith(root.toPath()) && target.isFile)
        }
    }

    @Test fun implementedClassesAreNotDescribedAsUnimplemented() {
        text.lineSequence().filter { it.startsWith("|") && it.contains("Not implemented", ignoreCase = true) }
            .forEach { row ->
                val named = identifier.findAll(row).map { it.value }.filter { it in declarations }.toSet()
                assertTrue("ux-flows.md says Not implemented for existing source classes: ${named.sorted().joinToString()}", named.isEmpty())
            }
    }

    @Test fun citedClassIdentifiersResolve() {
        // Backticks mark symbol citations. Uppercase constants and lowercase functions are not type claims.
        val citations = Regex("`([^`]+)`").findAll(text).map { it.groupValues[1] }
            .filter { citation -> citation.none { it.isWhitespace() } }
        val names = citations.flatMap { citation ->
            identifier.findAll(citation).map { it.value }
        }.toSet()
        assertTrue("ux-flows.md must cite source classes", names.isNotEmpty())
        names.forEach { name ->
            assertTrue("ux-flows.md cites undeclared source class: $name", name in declarations)
        }
    }
}
