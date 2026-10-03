package com.noop.firmware

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareSimulationIsolationTest {
    @Test
    fun `mock core and composition root cannot import hardware storage or networking`() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "android/app/src/main/java/com/noop/firmware").isDirectory }
        val sourceRoot = File(root, "android/app/src/main/java/com/noop")
        val sources = File(sourceRoot, "firmware").listFiles()!!.filter { it.extension == "kt" } +
            File(sourceRoot, "ui/FirmwareSimulationCard.kt")
        for (source in sources) {
            val text = source.readText()
            val imports = Regex("(?m)^import ([^\\s]+)").findAll(text).map { it.groupValues[1] }
            assertTrue(source.name, imports.all { it.startsWith("kotlin.") || it.startsWith("androidx.compose.") ||
                it.startsWith("com.noop.firmware.") || it == "com.noop.R" })
            for (symbol in listOf("AppViewModel", "WhoopBleClient", "NoopPrefs", "NoopApplication", "ByteArray", "java.net", "android.bluetooth")) {
                assertFalse("${source.name} depends on $symbol", text.contains(symbol))
            }
        }
        val card = File(sourceRoot, "ui/FirmwareSimulationCard.kt").readText()
        assertTrue(card.contains("fun FirmwareSimulationCard()"))
        assertTrue(card.contains("mutableStateOf(false)"))
    }
}
