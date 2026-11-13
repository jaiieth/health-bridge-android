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
    private val declarations = File(root, "app/src").walkTopDown()
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
        listOf(document, File(root, "design-system/health-bridge/MASTER.md")).forEach { checkedDocument ->
            checkedDocument.readText().lineSequence()
                .filter { it.startsWith("|") && it.contains("Not implemented", ignoreCase = true) }
                .forEach { row ->
                    val named = identifier.findAll(row).map { it.value }.filter { it in declarations }.toSet()
                    assertTrue("${checkedDocument.relativeTo(root)} says Not implemented for existing source classes: ${named.sorted().joinToString()}", named.isEmpty())
                }
        }
    }

    @Test fun citedClassIdentifiersResolve() {
        listOf(document, File(root, "design-system/health-bridge/MASTER.md"), File(root, "docs/ui-skill-third-query.md")).forEach { citedDocument ->
            val text = citedDocument.readText()
            // Backticks mark symbol citations. Uppercase constants and lowercase functions are not type claims.
            val citations = Regex("(?<!`)`([^`\\n]+)`(?!`)").findAll(text).map { it.groupValues[1] }
                .filter { citation -> citation.none { it.isWhitespace() } }
            val names = citations.flatMap { citation ->
                identifier.findAll(citation).map { it.value }
            }.toSet()
            assertTrue("${citedDocument.relativeTo(root)} must cite source classes", names.isNotEmpty())
            names.forEach { name ->
                assertTrue("${citedDocument.relativeTo(root)} cites undeclared source class: $name", name in declarations)
            }
        }
    }

    @Test fun thirdQueryMemberCitationsResolve() {
        val path = "docs/ui-skill-third-query.md"
        Regex("`([A-Z][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)`")
            .findAll(File(root, path).readText()).forEach { citation ->
                val (owner, member) = citation.destructured
                val source = File(root, "app/src/main/java/dev/healthbridge/$owner.kt")
                assertTrue("$path cites missing source file for $owner", source.isFile)
                assertTrue("$path cites undeclared member: $owner.$member",
                    Regex("\\b(?:fun|val|var|class|object|interface)\\s+${Regex.escape(member)}\\b")
                        .containsMatchIn(source.readText()))
            }
    }

    @Test fun revisionProvenanceCannotGoStale() {
        val pin = "6ae86f61f520d6ca229d2f40e070410638c908c5"
        val provenanceFiles = setOf("design-system/health-bridge/MASTER.md", "docs/ui-design-rationale.md")
        val token = Regex("(?<![A-Za-z0-9_])[0-9a-fA-F]{7,40}(?![A-Za-z0-9_])")
        (provenanceFiles + "docs/ux-flows.md").forEach { path ->
            File(root, path).readLines().forEach { line ->
                token.findAll(line).forEach { match ->
                    val allowed = path in provenanceFiles && match.value == pin &&
                        line.startsWith("|") && (line.startsWith("| Skill repository |") || line.startsWith("| CLI |"))
                    assertTrue("$path contains forbidden commit reference: ${match.value}", allowed)
                }
            }
        }
    }
}
