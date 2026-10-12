package ru.fsp.talent.proctoring

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest

class WebmSeekableIndexerTest {
    private val mediaRecorderFixture by lazy {
        javaClass.getResourceAsStream("/proctoring/mediarecorder-native.webm")!!.use { it.readBytes() }
    }

    @Test
    fun `seekable index is independent of arbitrary receipt and block payload boundaries`() {
        val bytes = mediaRecorderFixture
        val single = listOf(receipt(0, bytes))
        val direct = WebmSeekableIndexer().index("session-a", "capture-a", "camera", single) { _, _ -> ByteArrayInputStream(bytes) }

        val parts = split(bytes, listOf(1, 37, 2, 509))
        assertTrue(parts.size <= ProctoringService.MAX_CHUNK_SEQUENCE + 1)
        val receipts = parts.mapIndexed(::receipt)
        val opened = mutableListOf<Int>()
        val segmented = WebmSeekableIndexer().index("session-b", "capture-b", "camera", receipts) { path, _ ->
            val index = path.fileName.toString().toInt()
            opened += index
            ByteArrayInputStream(parts[index])
        }

        assertArrayEquals(direct.prefix, segmented.prefix)
        assertEquals(direct.rawStart, segmented.rawStart)
        assertEquals(direct.totalBytes, segmented.totalBytes)
        assertEquals(direct.durationTicks, segmented.durationTicks)
        assertEquals(direct.cueCount, segmented.cueCount)
        assertEquals(parts.indices.toList(), opened)
    }

    @Test
    fun `truncated declared block payload is rejected instead of yielding an incomplete seek index`() {
        val truncated = mediaRecorderFixture.copyOf(mediaRecorderFixture.size - 1)
        val error = assertThrows(Exception::class.java) {
            WebmSeekableIndexer().index("session-c", "capture-c", "camera", listOf(receipt(0, truncated))) { _, _ ->
                ByteArrayInputStream(truncated)
            }
        }
        assertTrue(error is java.io.EOFException || error is ru.fsp.talent.core.ApiException)
    }

    @Test
    fun `nondefault timecode scale is retained when duration is rewritten`() {
        val bytes = mediaRecorderFixture.copyOf()
        val scaleElement = byteArrayOf(0x2a, 0xd7.toByte(), 0xb1.toByte(), 0x83.toByte(), 0x0f, 0x42, 0x40)
        val offset = bytes.indexOf(scaleElement)
        assertTrue(offset >= 0)
        bytes[offset + scaleElement.lastIndex] = 0x3f // 999,999 ns per tick; element width remains unchanged.
        val index = WebmSeekableIndexer().index("session-scale", "capture-scale", "camera", listOf(receipt(0, bytes))) { _, _ ->
            ByteArrayInputStream(bytes)
        }
        assertTrue(index.prefix.indexOf(scaleElement.copyOfRange(0, scaleElement.lastIndex) + byteArrayOf(0x3f)) >= 0,
            "rewritten Info must preserve the configured TimecodeScale")
        assertTrue(index.durationTicks.isFinite() && index.durationTicks > 0)
    }

    @Test
    fun `session erasure during a scan prevents index cache reinsertion`() {
        val bytes = mediaRecorderFixture
        val receipt = receipt(0, bytes)
        val indexer = WebmSeekableIndexer()
        var opens = 0
        val open = { _: Path, _: Long ->
            opens++
            if (opens == 1) indexer.evictSession("session-erase")
            ByteArrayInputStream(bytes)
        }
        indexer.index("session-erase", "capture", "camera", listOf(receipt), open)
        val afterFirstScan = opens
        indexer.index("session-erase", "capture", "camera", listOf(receipt), open)
        assertTrue(opens > afterFirstScan, "erasure racing a scan must prevent stale metadata from being cached")
    }

    @Test
    fun `indexer rejects metadata and cue budgets before producing a partial prefix`() {
        val bytes = mediaRecorderFixture
        assertThrows(ru.fsp.talent.core.ApiException::class.java) {
            WebmSeekableIndexer(maxPrefixBytes = 64).index("session-small", "capture", "camera", listOf(receipt(0, bytes))) { _, _ ->
                ByteArrayInputStream(bytes)
            }
        }
        assertThrows(ru.fsp.talent.core.ApiException::class.java) {
            WebmSeekableIndexer(maxCuePoints = 0).index("session-no-cues", "capture", "camera", listOf(receipt(0, bytes))) { _, _ ->
                ByteArrayInputStream(bytes)
            }
        }
    }

    @Test
    fun `missing duplicate and unknown-sized Info metadata are rejected`() {
        val bytes = mediaRecorderFixture
        val infoStart = bytes.indexOf(byteArrayOf(0x15, 0x49, 0xa9.toByte(), 0x66))
        assertTrue(infoStart >= 0)
        val infoLength = 5 + (bytes[infoStart + 4].toInt() and 0x7f)
        val withoutInfo = bytes.copyOfRange(0, infoStart) + bytes.copyOfRange(infoStart + infoLength, bytes.size)
        val duplicateInfo = bytes.copyOfRange(0, infoStart + infoLength) + bytes.copyOfRange(infoStart, infoStart + infoLength) +
            bytes.copyOfRange(infoStart + infoLength, bytes.size)
        val unknownInfoChildSize = bytes.copyOf().also {
            val scaleId = it.indexOf(byteArrayOf(0x2a, 0xd7.toByte(), 0xb1.toByte()))
            assertTrue(scaleId >= 0)
            it[scaleId + 3] = 0xff.toByte()
        }
        listOf(withoutInfo, duplicateInfo, unknownInfoChildSize).forEachIndexed { index, malformed ->
            assertThrows(ru.fsp.talent.core.ApiException::class.java) {
                WebmSeekableIndexer().index("session-malformed-$index", "capture", "camera", listOf(receipt(0, malformed))) { _, _ ->
                    ByteArrayInputStream(malformed)
                }
            }
        }
    }

    private fun receipt(index: Int, bytes: ByteArray): MediaFileReceipt = MediaFileReceipt(
        index, Path.of("/$index"), bytes.size.toLong(), sha256(bytes),
    )

    private fun split(bytes: ByteArray, pattern: List<Int>): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        var patternIndex = 0
        while (offset < bytes.size) {
            val size = minOf(pattern[patternIndex++ % pattern.size], bytes.size - offset)
            parts += bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        return parts
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        if (pattern.size > size) return -1
        for (index in 0..(size - pattern.size)) {
            if (copyOfRange(index, index + pattern.size).contentEquals(pattern)) return index
        }
        return -1
    }
}
