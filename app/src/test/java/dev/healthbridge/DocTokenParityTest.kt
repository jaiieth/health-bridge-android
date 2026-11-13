package dev.healthbridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocTokenParityTest {
    @Test fun resourceTablesMatchXml() {
        val resources = File("src/main/res")
        val light = xmlValues(File(resources, "values/colors.xml"), "color")
        val dark = xmlValues(File(resources, "values-night/colors.xml"), "color")
        val dimensions = xmlValues(File(resources, "values/dimens.xml"), "dimen")
        val expected = light.keys + dark.keys + dimensions.keys
        val documented = mutableSetOf<String>()
        var header = emptyList<String>()
        File("../design-system/health-bridge/MASTER.md").forEachLine { line ->
            if (!line.startsWith("|")) {
                header = emptyList()
            } else {
                val cells = line.trim().removePrefix("|").removeSuffix("|")
                    .split('|').map { it.trim().removeSurrounding("`") }
                if (cells.firstOrNull() == "Token") header = cells
                val token = cells.firstOrNull().orEmpty()
                if (token.startsWith("hb_")) {
                    assertTrue("$token: document names nonexistent resource", token in expected)
                    assertTrue("$token: duplicate document row", documented.add(token))
                    fun column(name: String): String {
                        val index = header.indexOf(name)
                        assertTrue("$token: required $name column absent", index >= 0)
                        assertTrue("$token: required $name value absent", index < cells.size && cells[index].isNotBlank())
                        return cells[index]
                    }
                    if (token in dimensions) {
                        assertEquals("$token: dimension differs", dimensions.getValue(token), column("Value"))
                    } else {
                        assertTrue("$token: missing light XML resource", token in light)
                        assertTrue("$token: missing dark XML resource", token in dark)
                        assertEquals("$token: light hex differs", light.getValue(token), column("Light"))
                        assertEquals("$token: dark hex differs", dark.getValue(token), column("Dark"))
                    }
                }
            }
        }
        expected.forEach { token ->
            assertTrue("$token: resource missing from MASTER.md tables", token in documented)
        }
    }

    private fun xmlValues(file: File, tag: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName(tag)
        val values = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val token = node.attributes.getNamedItem("name").nodeValue
            if (token.startsWith("hb_")) {
                assertTrue("$token: duplicate XML resource in $file", token !in values)
                values[token] = node.textContent.trim()
            }
        }
        assertTrue("$file: no hb_ resource tokens found", values.isNotEmpty())
        return values
    }
}
