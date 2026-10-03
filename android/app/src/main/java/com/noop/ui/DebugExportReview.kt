package com.noop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestBundleAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DebugExportReview {
    class Pending internal constructor(
        val id: Long,
        entries: List<Pair<String, ByteArray>>,
        val gate: ReportReviewGate,
        internal val output: suspend (List<Pair<String, ByteArray>>) -> Unit,
    ) {
        private val snapshot = entries.map { it.first to it.second.copyOf() }
        internal val entries get() = snapshot.map { it.first to it.second.copyOf() }
    }

    var pending by mutableStateOf<Pending?>(null)
        private set
    private var generation = 0L

    suspend fun beginPreparation(): Long {
        currentCoroutineContext().ensureActive()
        val ticket = ++generation
        pending = null
        return ticket
    }

    fun isCurrentPreparation(ticket: Long): Boolean = ticket == generation

    suspend fun stage(
        entries: List<Pair<String, ByteArray>>,
        capBytes: Int = 20 * 1024 * 1024,
        research: Boolean = false,
        ticket: Long? = null,
        output: suspend (List<Pair<String, ByteArray>>) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val preparation = ticket ?: beginPreparation()
        if (!isCurrentPreparation(preparation)) return
        val prepared = withContext(Dispatchers.IO) { if (research) prepareResearch(entries, capBytes) else prepare(entries, capBytes) }
        if (isCurrentPreparation(preparation)) pending = Pending(preparation, prepared, ReportReviewGate(prepared), output)
    }

    suspend fun stageResearch(
        entries: List<Pair<String, ByteArray>>,
        capBytes: Int = 20 * 1024 * 1024,
        ticket: Long? = null,
        output: suspend (List<Pair<String, ByteArray>>) -> Unit,
    ) = stage(entries, capBytes, research = true, ticket = ticket, output = output)

    fun cancel() {
        generation++
        pending = null
    }

    suspend fun confirm(id: Long, output: (suspend (List<Pair<String, ByteArray>>) -> Unit)? = null) {
        val review = pending?.takeIf { it.id == id } ?: return
        review.gate.confirm()
        pending = null
        if (review.gate.isCleared) (output ?: review.output)(review.entries)
    }

    companion object {
        val shared = DebugExportReview()

        fun prepare(entries: List<Pair<String, ByteArray>>, capBytes: Int = 20 * 1024 * 1024): List<Pair<String, ByteArray>> {
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate export attachment" }
            val redacted = TestBundleAssembler.redactEntries(entries).map { (name, bytes) ->
                val data = if (name == "screenshot.png") bytes else redactBearer(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                if (name == "report.txt" && data.size > capBytes) {
                    name to trimLine(data.copyOfRange(data.size - maxOf(0, capBytes), data.size))
                } else name to data
            }
            val prepared = TestBundleAssembler.capEntries(redacted, capBytes).first
                .map { (name, data) ->
                    // The shared Swift cap drops a partial JSONL record at the start of a trimmed tail.
                    val original = redacted.first { it.first == name }.second
                    name to if (name == "raw-capture.jsonl" && data.size < original.size) trimLine(data) else data.copyOf()
                }
            require(prepared.sumOf { it.second.size.toLong() } <= capBytes) { "The export bundle exceeds the byte limit" }
            return prepared
        }

        fun prepareResearch(entries: List<Pair<String, ByteArray>>, capBytes: Int = 20 * 1024 * 1024): List<Pair<String, ByteArray>> {
            require(entries.sumOf { it.second.size.toLong() } <= capBytes) { "The research bundle exceeds the byte limit" }
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate research attachment" }
            val metadataNames = setOf("meta.json", "events.jsonl", "events.csv")
            val prepared = entries.map { (name, data) ->
                if (name !in metadataNames) name to data.copyOf() else {
                    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
                    val scrubbed = redactBearer(com.noop.ble.redactStrapLogPii(text))
                        .replace(Regex("""("(?:device_id|strap_device_id)"\s*:\s*")[^"]*(")"""), "$1<device>$2")
                    name to scrubbed.toByteArray(Charsets.UTF_8)
                }
            }
            require(prepared.sumOf { it.second.size.toLong() } <= capBytes) { "The research bundle exceeds the byte limit" }
            return prepared
        }

        fun redactBearer(text: String): String =
            text.replace(Regex("""(?i:Bearer)(?:[\s\p{Z}\u0085]|\\[nrt])+[A-Za-z0-9._~+/-]+=*"""), "Bearer <redacted>")

        fun readResearchZip(file: java.io.File, capBytes: Int = 20 * 1024 * 1024): List<Pair<String, ByteArray>> {
            val entries = arrayListOf<Pair<String, ByteArray>>()
            var remaining = capBytes
            java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    require(entries.none { it.first == entry.name }) { "Duplicate research attachment" }
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        while (true) {
                            val count = zip.read(buffer, 0, minOf(buffer.size, remaining + 1))
                            if (count < 0) break
                            require(count <= remaining) { "The research bundle exceeds the byte limit" }
                            remaining -= count
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    entries.add(entry.name to bytes)
                }
            }
            return entries
        }

        private fun trimLine(data: ByteArray): ByteArray {
            val newline = data.indexOf(10.toByte())
            var start = if (newline < 0) 0 else newline + 1
            while (start < data.size && (data[start].toInt() and 0xC0) == 0x80) start++
            return data.copyOfRange(start, data.size)
        }
    }
}
