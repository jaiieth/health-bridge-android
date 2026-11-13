package dev.healthbridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class UiTokenTest {
    private val res = File("src/main/res")
    private fun values(file: File, tag: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.trim()
        }
    }
    private fun luminance(hex: String): Double {
        val channels = hex.removePrefix("#").chunked(2).map {
            val v = it.toInt(16) / 255.0
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
    }
    @Test fun allForegroundsPassBothThemes() {
        val lines = mutableListOf("theme\tforeground\tbackground\tratio\tminimum")
        for (theme in listOf("values", "values-night")) {
            val c = values(File(res, "$theme/colors.xml"), "color")
            val foregrounds = listOf("text", "muted", "on_disabled", "off", "blocked", "paused", "waiting", "unknown", "primary", "outline")
            val pairs = foregrounds.flatMap { f -> listOf("surface", "background").map { f to it } } +
                listOf("on_primary" to "primary", "on_disabled" to "disabled", "primary" to "disabled")
            for ((fg, bg) in pairs) {
                val a = luminance(c.getValue("hb_$fg")); val b = luminance(c.getValue("hb_$bg"))
                val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                val minimum = if (fg == "outline") 3.0 else 4.5
                lines += "$theme\t$fg\t$bg\t${String.format(java.util.Locale.US, "%.3f", ratio)}\t$minimum"
                assertTrue("$theme $fg on $bg = $ratio", ratio >= minimum)
            }
        }
        File("build/reports/ui-contrast.tsv").apply { parentFile?.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    }
    @Test fun touchAndTypeConstantsRemainAccessible() {
        val d = values(File(res, "values/dimens.xml"), "dimen")
        assertTrue(d.getValue("hb_touch_min").removeSuffix("dp").toDouble() >= 48)
        assertEquals(listOf("4dp", "8dp", "16dp", "24dp", "32dp"),
            listOf("hb_space_xs", "hb_space_sm", "hb_space_md", "hb_space_lg", "hb_space_xl").map { d.getValue(it) })
        val source = File("src/main/java/dev/healthbridge/MainActivity.kt").readText()
        assertTrue(source.contains("bottomMargin = dim(R.dimen.hb_space_sm)"))
        assertFalse(source.contains("textSize ="))
        assertFalse(source.contains("RippleDrawable"))
        for (key in listOf("hb_type_title", "hb_type_section", "hb_type_body", "hb_type_label"))
            assertTrue(d.getValue(key).endsWith("sp"))
    }
    @Test fun tokenWidgetNeverLoadsSavedSecretAndAlwaysMasks() {
        val source = File("src/main/java/dev/healthbridge/MainActivity.kt").readText()
        val tokenWidget = source.substringAfter("val token = input").substringBefore("val minutes = input")
        assertTrue(tokenWidget.contains("PasswordTransformationMethod.getInstance()"))
        assertTrue(tokenWidget.contains("setText(SyncStatusDisplay.tokenFieldValue())"))
        assertTrue(tokenWidget.contains("isSaveEnabled = false"))
        assertFalse(tokenWidget.contains("settings.token"))
        assertFalse(source.contains("sync-control"))
        assertTrue(source.contains("Snapshot(settings.syncEnabled,"))
        assertEquals(1, Regex("Schedule\\.periodic").findAll(source).count())
        assertTrue(source.contains("if (!changingControl)"))
        assertTrue(source.substringAfter("control = Switch(this).apply {").substringBefore("sync.addView(control").contains("isSaveEnabled = false"))
        val saveHandler = source.substringAfter("save.setOnClickListener {").substringBefore("val access = card")
        assertFalse(saveHandler.contains("Schedule.periodic"))
        assertTrue(saveHandler.contains("Schedule.pauseHealth"))
        val startup = source.substringAfter("        lifecycleScope.launch {\n            withContext(Dispatchers.IO)")
        assertTrue(startup.substringBefore("override fun onStart").contains("Schedule.pauseHealth"))
        assertFalse(startup.substringBefore("override fun onStart").contains("Schedule.periodic"))
        assertTrue(source.contains("current.syncEnabled = checked"))
        assertTrue(source.contains("Schedule.pauseHealth(this@MainActivity)"))
        assertTrue(source.contains("labelFor = field.id"))
        assertTrue(source.contains("View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        assertFalse(source.contains("LayoutParams.MATCH_PARENT, 48"))
    }
}
