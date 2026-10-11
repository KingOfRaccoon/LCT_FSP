package ru.fsp.talent.proctoring

import ru.fsp.talent.core.ApiException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Semaphore

private fun unsupported(): Nothing = throw ApiException(422, "VALIDATION_ERROR", "Recording is not a supported seekable WebM")

private fun vintLength(first: Int): Int? {
    if (first == 0) return null
    return Integer.numberOfLeadingZeros(first) - 24 + 1
}

internal data class MediaFileReceipt(val sequence: Int, val path: Path, val byteCount: Long, val sha256: String)

internal data class SeekableWebmIndex(
    val prefix: ByteArray,
    val rawStart: Long,
    val rawBytes: Long,
    val durationTicks: Double,
    val cueCount: Int,
) {
    val totalBytes: Long get() = prefix.size + rawBytes - rawStart
}

/** Builds a small virtual WebM header; frame payloads remain in their private receipt files. */
internal class WebmSeekableIndexer(
    private val maxInputBytes: Long = 256L * 1024 * 1024,
    private val maxPrefixBytes: Int = 1024 * 1024,
    private val maxCuePoints: Int = 20_000,
    private val maxElements: Int = 1_000_000,
    private val cacheLimitBytes: Int = 16 * 1024 * 1024,
) {
    private val scanSlots = Semaphore(2, true)
    private val cache = object : LinkedHashMap<String, SeekableWebmIndex>(16, 0.75f, true) {}
    private var cacheBytes = 0
    private var cacheEpoch = 0L

    fun index(
        sessionId: String,
        captureId: String,
        channel: String,
        files: List<MediaFileReceipt>,
        openAuthorized: (Path, Long) -> InputStream,
    ): SeekableWebmIndex {
        val rawBytes = files.sumOf { it.byteCount }
        if (files.isEmpty() || files.size > ProctoringService.MAX_CHUNK_SEQUENCE + 1 || rawBytes !in 1..maxInputBytes) unsupported()
        val key = cacheKey(sessionId, captureId, channel, files)
        val epoch = synchronized(cache) {
            cache[key]?.let { return it }
            cacheEpoch
        }
        if (!scanSlots.tryAcquire()) {
            throw ApiException(429, "DEPENDENCY_UNAVAILABLE", "Recording index is busy; retry playback", retryable = true)
        }
        try {
            synchronized(cache) { cache[key]?.let { return it } }
            val result = scan(files, rawBytes, openAuthorized)
            if (result.prefix.size <= maxPrefixBytes) synchronized(cache) {
                if (cacheEpoch == epoch) {
                    cache.remove(key)?.let { cacheBytes -= it.prefix.size }
                    cache[key] = result
                    cacheBytes += result.prefix.size
                    val iterator = cache.entries.iterator()
                    while ((cacheBytes > cacheLimitBytes || cache.size > 32) && iterator.hasNext()) {
                        val entry = iterator.next()
                        cacheBytes -= entry.value.prefix.size
                        iterator.remove()
                    }
                }
            }
            return result
        } finally {
            scanSlots.release()
        }
    }

    fun evictSession(sessionId: String) = synchronized(cache) {
        cacheEpoch++
        val prefix = "$sessionId:"
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                cacheBytes -= entry.value.prefix.size
                iterator.remove()
            }
        }
    }

    private fun scan(files: List<MediaFileReceipt>, rawBytes: Long,
                     openAuthorized: (Path, Long) -> InputStream): SeekableWebmIndex {
        val input = SegmentedInput(files, openAuthorized)
        input.use {
            val ebml = it.nextHeader() ?: unsupported()
            if (ebml.id != EBML || ebml.size == null) unsupported()
            val ebmlBytes = it.readElement(ebml, maxPrefixBytes)
            val segment = it.nextHeader() ?: unsupported()
            if (segment.id != SEGMENT) unsupported()
            val segmentDataStartOriginal = it.position
            if (segment.size != null && segment.size != rawBytes - segmentDataStartOriginal) unsupported()
            val segmentHeader = segment.idBytes + unknownSize(8)
            val topLevelPrefix = mutableListOf<ByteArray>()
            var storedPrefixBytes = ebmlBytes.size + segmentHeader.size
            var info: ByteArray? = null
            var timecodeScale = DEFAULT_TIMECODE_SCALE
            var rawDefaultDurations = emptyMap<Long, Long>()
            var videoTracks = emptySet<Long>()
            var sawInfo = false
            var sawTracks = false
            var firstClusterStart: Long? = null
            val cues = mutableListOf<Cue>()
            val lastVideoFrames = mutableMapOf<Long, VideoFrameEnd>()
            var elementCount = 0

            while (true) {
                val header = it.nextHeader() ?: break
                if (++elementCount > maxElements) unsupported()
                if (header.id == CLUSTER) {
                    if (firstClusterStart == null) firstClusterStart = header.start
                    val defaultIntervals = rawDefaultDurations.mapValues { (_, nanos) -> (nanos.toDouble() / timecodeScale).toLong().coerceAtLeast(1) }
                    val cluster = scanCluster(it, header, maxElements - elementCount, videoTracks, defaultIntervals, lastVideoFrames)
                    elementCount += cluster.elements
                    cluster.cue?.let { cue ->
                        if (cues.size >= maxCuePoints) unsupported()
                        cues += cue.copy(rawClusterOffset = header.start - firstClusterStart!!)
                    }
                    continue
                }
                val isLevelOne = header.id in LEVEL_ONE_IDS
                if (firstClusterStart != null) {
                    if (!isLevelOne) unsupported()
                    if (header.id == CUES || header.id == SEEK_HEAD) unsupported()
                    it.skipElement(header, rawBytes)
                    continue
                }
                if (header.size == null) unsupported()
                val elementBytes = it.readElement(header, maxPrefixBytes)
                when (header.id) {
                    SEEK_HEAD, CUES -> Unit // Existing offsets are stale once a virtual prefix is inserted.
                    CRC32 -> Unit // The enclosing Segment changes, so its old checksum is invalid.
                    INFO -> {
                        if (sawInfo) unsupported()
                        sawInfo = true
                        val parsed = rewriteInfo(elementBytes)
                        info = parsed.first
                        timecodeScale = parsed.second
                        storedPrefixBytes += parsed.first.size
                        if (storedPrefixBytes > maxPrefixBytes) unsupported()
                    }
                    TRACKS -> {
                        if (sawTracks) unsupported()
                        sawTracks = true
                        val parsed = parseTracks(elementBytes)
                        videoTracks = parsed.first
                        rawDefaultDurations = parsed.second
                        storedPrefixBytes += elementBytes.size
                        if (storedPrefixBytes > maxPrefixBytes) unsupported()
                        topLevelPrefix += elementBytes
                    }
                    else -> {
                        storedPrefixBytes += elementBytes.size
                        if (storedPrefixBytes > maxPrefixBytes) unsupported()
                        topLevelPrefix += elementBytes
                    }
                }
            }

            val rawStart = firstClusterStart ?: unsupported()
            if (!sawInfo || !sawTracks || cues.isEmpty() || videoTracks.isEmpty()) unsupported()
            val fallbackTicks = DEFAULT_FRAME_INTERVAL_NS.toDouble() / timecodeScale
            val durationTicks = lastVideoFrames.values.maxOfOrNull { frame ->
                frame.timestamp + (frame.durationTicks ?: fallbackTicks)
            } ?: unsupported()
            val finalInfo = rewriteInfoDuration(info ?: unsupported(), durationTicks)
            val segmentDataStart = ebmlBytes.size + segmentHeader.size
            // SeekHead points to Cues; cue positions point just beyond Cues at the first cluster bytes.
            var cuesElement = encodeElement(CUES, encodeCues(cues, 0))
            val seekHeadLength = encodeElement(SEEK_HEAD, encodeSeekHead(0)).size.toLong()
            val cuesPosition = seekHeadLength + finalInfo.size + topLevelPrefix.sumOf { it.size }
            val firstClusterPosition = cuesPosition + cuesElement.size
            val seekHead = encodeElement(SEEK_HEAD, encodeSeekHead(cuesPosition))
            cuesElement = encodeElement(CUES, encodeCues(cues, firstClusterPosition))
            val outputPrefix = ByteArrayOutputStream().apply {
                write(ebmlBytes)
                write(segmentHeader)
                write(seekHead)
                write(finalInfo)
                topLevelPrefix.forEach(::write)
                write(cuesElement)
            }.toByteArray()
            val segmentBodySize = outputPrefix.size.toLong() - segmentDataStart + rawBytes - rawStart
            if (outputPrefix.size > maxPrefixBytes || segmentDataStart > rawStart || segmentBodySize !in 1 until ((1L shl 56) - 1)) unsupported()
            val fixedSegmentSize = knownSize8(segmentBodySize)
            val segmentSizeOffset = ebmlBytes.size + segment.idBytes.size
            fixedSegmentSize.copyInto(outputPrefix, segmentSizeOffset)
            return SeekableWebmIndex(outputPrefix, rawStart, rawBytes, durationTicks, cues.size)
        }
    }

    private fun scanCluster(input: SegmentedInput, cluster: ElementHeader, remainingElements: Int,
                            videoTracks: Set<Long>, defaultIntervals: Map<Long, Long>,
                            lastVideoFrames: MutableMap<Long, VideoFrameEnd>): ClusterResult {
        val end = cluster.size?.let { cluster.dataStart + it }
        if (end != null && end > input.totalBytes) unsupported()
        var timecode = 0L
        var hasTimecode = false
        var blockNumber = 0L
        var cue: Cue? = null
        var elements = 0
        while (end == null || input.position < end) {
            val child = input.nextHeader() ?: return if (end == null) ClusterResult(elements, cue)
                else throw EOFException("Truncated WebM cluster")
            if (++elements > remainingElements) unsupported()
            if (child.id in LEVEL_ONE_IDS) {
                if (end != null) unsupported()
                input.pending = child
                return ClusterResult(elements, cue)
            }
            val size = child.size ?: unsupported()
            when (child.id) {
                TIMECODE -> {
                    if (hasTimecode) unsupported()
                    if (size !in 1..8) unsupported()
                    timecode = input.readUnsigned(size.toInt())
                    hasTimecode = true
                }
                SIMPLE_BLOCK -> {
                    if (!hasTimecode) unsupported()
                    blockNumber++
                    val block = input.readBlockHeader(size)
                    val timestamp = checkedTimestamp(timecode, block.relativeTimecode)
                    if (block.track in videoTracks) {
                        if (block.laced) unsupported()
                        recordVideoFrame(lastVideoFrames, block.track, timestamp.toDouble(), defaultIntervals[block.track]?.toDouble())
                        if (cue == null && block.keyframe) cue = Cue(maxOf(0, timestamp), block.track, blockNumber, 0)
                    }
                }
                BLOCK_GROUP -> {
                    if (!hasTimecode) unsupported()
                    val groupEnd = checkedElementEnd(child.dataStart, size)
                    if (groupEnd > input.totalBytes || end != null && groupEnd > end) unsupported()
                    var block: BlockInfo? = null
                    var hasReference = false
                    var blockDuration: Long? = null
                    while (input.position < groupEnd) {
                        val nested = input.nextHeader() ?: throw EOFException("Truncated WebM block group")
                        if (++elements > remainingElements || nested.size == null || checkedElementEnd(nested.dataStart, nested.size) > groupEnd) unsupported()
                        when (nested.id) {
                            BLOCK -> {
                                if (block != null) unsupported()
                                block = input.readBlockHeader(nested.size)
                            }
                            BLOCK_DURATION -> {
                                if (blockDuration != null) unsupported()
                                if (nested.size !in 1..8) unsupported()
                                blockDuration = input.readUnsigned(nested.size.toInt())
                            }
                            REFERENCE_BLOCK -> { hasReference = true; input.skipExactly(nested.size) }
                            else -> input.skipExactly(nested.size)
                        }
                    }
                    if (input.position != groupEnd) unsupported()
                    block?.let {
                        blockNumber++
                        val timestamp = checkedTimestamp(timecode, it.relativeTimecode)
                        if (it.track in videoTracks) {
                            if (it.laced) unsupported()
                            recordVideoFrame(lastVideoFrames, it.track, timestamp.toDouble(),
                                blockDuration?.toDouble() ?: defaultIntervals[it.track]?.toDouble())
                            if (cue == null && !hasReference) cue = Cue(maxOf(0, timestamp), it.track, blockNumber, 0)
                        }
                    }
                }
                else -> input.skipExactly(size)
            }
            if (end != null && input.position > end) unsupported()
        }
        return ClusterResult(elements, cue)
    }

    private fun rewriteInfo(bytes: ByteArray): Pair<ByteArray, Long> {
        val cursor = MemoryCursor(bytes)
        val info = cursor.next() ?: unsupported()
        if (info.id != INFO || info.size != cursor.remaining.toLong()) unsupported()
        val children = mutableListOf<ByteArray>()
        var scale = DEFAULT_TIMECODE_SCALE
        var hasScale = false
        while (cursor.remaining > 0) {
            val element = cursor.next() ?: unsupported()
            val raw = cursor.lastElementBytes
            when (element.id) {
                TIMECODE_SCALE -> {
                    if (hasScale) unsupported()
                    scale = cursor.readUnsignedPayload(element)
                    hasScale = true
                    children += raw
                }
                DURATION, CRC32 -> cursor.consumePayload(element)
                else -> { children += raw; cursor.consumePayload(element) }
            }
        }
        if (scale <= 0) unsupported()
        val content = ByteArrayOutputStream().apply {
            children.forEach(::write)
            if (!hasScale) write(encodeElement(TIMECODE_SCALE, unsignedBytes(scale)))
        }.toByteArray()
        return encodeElement(INFO, content) to scale
    }

    private fun parseTracks(bytes: ByteArray): Pair<Set<Long>, Map<Long, Long>> {
        val root = MemoryCursor(bytes)
        val tracks = root.next() ?: unsupported()
        if (tracks.id != TRACKS) unsupported()
        val payload = root.readPayloadBytes(tracks)
        val cursor = MemoryCursor(payload)
        val videoTracks = mutableSetOf<Long>()
        val seenTrackNumbers = mutableSetOf<Long>()
        val durations = mutableMapOf<Long, Long>()
        while (cursor.remaining > 0) {
            val entry = cursor.next() ?: unsupported()
            if (entry.id != TRACK_ENTRY) { cursor.consumePayload(entry); continue }
            val entryPayload = cursor.readPayloadBytes(entry)
            val children = MemoryCursor(entryPayload)
            var trackNumber: Long? = null
            var trackType: Long? = null
            var durationNanos: Long? = null
            while (children.remaining > 0) {
                val child = children.next() ?: unsupported()
                when (child.id) {
                    TRACK_NUMBER -> trackNumber = children.readUnsignedPayload(child)
                    TRACK_TYPE -> trackType = children.readUnsignedPayload(child)
                    DEFAULT_DURATION -> durationNanos = children.readUnsignedPayload(child)
                    else -> children.consumePayload(child)
                }
            }
            val number = trackNumber ?: unsupported()
            val type = trackType ?: unsupported()
            if (number <= 0 || !seenTrackNumbers.add(number) || type <= 0) unsupported()
            if (type == VIDEO_TRACK_TYPE) {
                videoTracks += number
                durationNanos?.takeIf { it > 0 }?.let { durations[number] = it }
            }
        }
        return videoTracks to durations
    }

    private fun rewriteInfoDuration(bytes: ByteArray, durationTicks: Double): ByteArray {
        val cursor = MemoryCursor(bytes)
        cursor.next() ?: unsupported()
        val children = ByteArrayOutputStream()
        while (cursor.remaining > 0) {
            val element = cursor.next() ?: unsupported()
            if (element.id != DURATION && element.id != CRC32) children.write(cursor.lastElementBytes)
            cursor.consumePayload(element)
        }
        children.write(encodeElement(DURATION, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(durationTicks).array()))
        return encodeElement(INFO, children.toByteArray())
    }

    private fun encodeCues(cues: List<Cue>, firstClusterPosition: Long): ByteArray =
        cues.map { cue ->
            val clusterPosition = firstClusterPosition + cue.rawClusterOffset
            val positions = encodeElement(CUE_TRACK, unsignedBytes(cue.track)) +
                encodeElement(CUE_CLUSTER_POSITION, fixedUnsignedBytes(clusterPosition, 8)) +
                encodeElement(CUE_BLOCK_NUMBER, unsignedBytes(cue.blockNumber))
            encodeElement(CUE_POINT, encodeElement(CUE_TIME, unsignedBytes(cue.time)) + encodeElement(CUE_TRACK_POSITIONS, positions))
        }.fold(ByteArrayOutputStream()) { output, element -> output.apply { write(element) } }.toByteArray()

    private fun encodeSeekHead(cuesPosition: Long): ByteArray {
        val seekId = encodeElement(SEEK_ID, idBytes(CUES))
        val seekPosition = encodeElement(SEEK_POSITION, fixedUnsignedBytes(cuesPosition, 8))
        return encodeElement(SEEK, seekId + seekPosition)
    }

    private fun cacheKey(session: String, capture: String, channel: String, files: List<MediaFileReceipt>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$session:$capture:$channel\n".toByteArray())
        files.forEach { digest.update("${it.sequence}:${it.byteCount}:${it.sha256}\n".toByteArray()) }
        return "$session:${digest.digest().joinToString("") { "%02x".format(it) }}"
    }

    private fun checkedTimestamp(clusterTimecode: Long, relativeTimecode: Int): Long = try {
        Math.addExact(clusterTimecode, relativeTimecode.toLong())
    } catch (_: ArithmeticException) { unsupported() }

    private fun checkedElementEnd(start: Long, size: Long): Long = try {
        Math.addExact(start, size)
    } catch (_: ArithmeticException) { unsupported() }

    private fun recordVideoFrame(frames: MutableMap<Long, VideoFrameEnd>, track: Long, timestamp: Double, duration: Double?) {
        if (frames[track]?.timestamp?.let { it > timestamp } != true) frames[track] = VideoFrameEnd(timestamp, duration)
    }

    private data class Cue(val time: Long, val track: Long, val blockNumber: Long, val rawClusterOffset: Long)
    private data class VideoFrameEnd(val timestamp: Double, val durationTicks: Double?)
    private data class BlockInfo(val track: Long, val relativeTimecode: Int, val keyframe: Boolean, val laced: Boolean)
    private data class ClusterResult(val elements: Int, val cue: Cue?)
    private data class ElementHeader(val id: Long, val size: Long?, val idBytes: ByteArray, val sizeBytes: ByteArray,
                                    val start: Long, val dataStart: Long)

    private class SegmentedInput(
        private val files: List<MediaFileReceipt>,
        private val openAuthorized: (Path, Long) -> InputStream,
    ) : InputStream() {
        private var fileIndex = 0
        private var current: InputStream? = null
        private var currentRemaining = 0L
        private val skipBuffer = ByteArray(16 * 1024)
        val totalBytes: Long = files.sumOf { it.byteCount }
        var position: Long = 0
            private set
        var pending: ElementHeader? = null

        override fun read(): Int {
            val stream = streamForRead() ?: return -1
            val value = stream.read()
            if (value < 0) throw EOFException("Private media file ended before its receipt length")
            position++; currentRemaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val stream = streamForRead() ?: return -1
            val boundedLength = minOf(length.toLong(), currentRemaining).toInt()
            val read = stream.read(buffer, offset, boundedLength)
            if (read < 0) throw EOFException("Private media file ended before its receipt length")
            if (read > 0) { position += read; currentRemaining -= read }
            return read
        }

        override fun close() { current?.close(); current = null }
        fun nextHeader(): ElementHeader? {
            pending?.let { pending = null; return it }
            val start = position
            val first = read()
            if (first < 0) return null
            val idFirst = first.toInt()
            val idLength = vintLength(idFirst) ?: unsupported()
            if (idLength > 4) unsupported()
            val idBytes = byteArrayOf(first.toByte()) + readExact(idLength - 1)
            val id = idBytes.fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
            val sizeFirst = read()
            if (sizeFirst < 0) throw EOFException("Truncated WebM element size")
            val sizeLength = vintLength(sizeFirst) ?: unsupported()
            val sizeBytes = byteArrayOf(sizeFirst.toByte()) + readExact(sizeLength - 1)
            val marker = 1 shl (8 - sizeLength)
            val payload = sizeBytes[0].toInt() and 0xff and marker.inv()
            var size = payload.toLong()
            for (index in 1 until sizeBytes.size) size = (size shl 8) or (sizeBytes[index].toLong() and 0xff)
            val unknown = size == ((1L shl (7 * sizeLength)) - 1)
            return ElementHeader(id, if (unknown) null else size, idBytes, sizeBytes, start, position)
        }

        fun readElement(header: ElementHeader, maxBytes: Int): ByteArray {
            val size = header.size ?: unsupported()
            if (size > maxBytes || size > Int.MAX_VALUE) unsupported()
            val content = readExact(size.toInt())
            return header.idBytes + header.sizeBytes + content
        }

        fun skipElement(header: ElementHeader, max: Long) {
            val size = header.size ?: unsupported()
            if (size > max) unsupported()
            skipExactly(size)
        }

        fun skipExactly(count: Long) {
            if (count < 0) unsupported()
            var remaining = count
            while (remaining > 0) {
                val stream = streamForRead() ?: throw EOFException("Truncated WebM element")
                val skipped = stream.skip(minOf(remaining, currentRemaining))
                if (skipped > 0) { position += skipped; currentRemaining -= skipped; remaining -= skipped; continue }
                val read = read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Truncated WebM element")
                remaining -= read
            }
        }

        fun readUnsigned(size: Int): Long {
            if (size !in 1..8) unsupported()
            var value = 0L
            repeat(size) {
                val next = read(); if (next < 0) throw EOFException("Truncated WebM integer")
                value = (value shl 8) or next.toLong()
                if (value < 0) unsupported()
            }
            return value
        }

        fun readBlockHeader(size: Long): BlockInfo {
            if (size < 4) unsupported()
            val trackFirst = read()
            if (trackFirst < 0) throw EOFException("Truncated WebM block")
            val width = vintLength(trackFirst) ?: unsupported()
            if (width > 8 || width + 3L > size) unsupported()
            val trackBytes = byteArrayOf(trackFirst.toByte()) + readExact(width - 1)
            val marker = 1 shl (8 - width)
            var track = (trackBytes[0].toInt() and 0xff and marker.inv()).toLong()
            for (index in 1 until trackBytes.size) track = (track shl 8) or (trackBytes[index].toLong() and 0xff)
            if (track <= 0) unsupported()
            val relative = ((read().also { if (it < 0) throw EOFException() } shl 8) or read().also { if (it < 0) throw EOFException() }).toShort().toInt()
            val flags = read()
            if (flags < 0) throw EOFException("Truncated WebM block")
            skipExactly(size - width - 3L)
            return BlockInfo(track, relative, flags and 0x80 != 0, flags and 0x06 != 0)
        }

        private fun readExact(length: Int): ByteArray {
            if (length < 0) unsupported()
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = read(result, offset, length - offset)
                if (count < 0) throw EOFException("Truncated WebM header")
                offset += count
            }
            return result
        }

        private fun streamForRead(): InputStream? {
            while (true) {
                val stream = current ?: nextStream() ?: return null
                if (currentRemaining > 0) return stream
                if (stream.read() >= 0) unsupported()
                stream.close()
                current = null
            }
        }

        private fun nextStream(): InputStream? {
            if (fileIndex >= files.size) return null
            val receipt = files[fileIndex++]
            currentRemaining = receipt.byteCount
            return BufferedInputStream(openAuthorized(receipt.path, receipt.byteCount), 64 * 1024).also { current = it }
        }
    }

    private data class MemoryElement(val id: Long, val size: Long)
    private class MemoryCursor(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset
        var lastElementBytes: ByteArray = byteArrayOf()
            private set
        fun next(): MemoryElement? {
            if (remaining == 0) return null
            val start = offset
            val idHeader = readVint(true) ?: return null
            if (idHeader.width > 4) unsupported()
            val sizeHeader = readVint(false) ?: unsupported()
            if (sizeHeader.value == ((1L shl (7 * sizeHeader.width)) - 1) || sizeHeader.value > remaining) unsupported()
            val payloadStart = offset
            val payloadEnd = payloadStart + sizeHeader.value.toInt()
            val element = MemoryElement(idHeader.value, sizeHeader.value)
            offset = payloadStart
            lastElementBytes = bytes.copyOfRange(start, payloadEnd)
            // Leave the cursor at the payload; readUnsignedPayload or consumePayload advances it.
            return element
        }
        fun readUnsignedPayload(element: MemoryElement): Long {
            if (element.size !in 1..8) unsupported()
            var value = 0L
            repeat(element.size.toInt()) {
                value = (value shl 8) or (bytes[offset++].toLong() and 0xff)
                if (value < 0) unsupported()
            }
            return value
        }
        fun readPayloadBytes(element: MemoryElement): ByteArray {
            if (element.size > Int.MAX_VALUE) unsupported()
            val result = bytes.copyOfRange(offset, offset + element.size.toInt())
            offset += element.size.toInt()
            return result
        }
        fun consumePayload(element: MemoryElement) { offset += element.size.toInt() }
        private data class Vint(val value: Long, val width: Int)
        private fun readVint(id: Boolean): Vint? {
            if (offset >= bytes.size) return null
            val first = bytes[offset++].toInt() and 0xff
            val width = vintLength(first) ?: unsupported()
            if (width > remaining + 1) unsupported()
            var value = if (id) first.toLong() else (first and ((1 shl (8 - width)) - 1)).toLong()
            repeat(width - 1) { value = (value shl 8) or (bytes[offset++].toLong() and 0xff) }
            return Vint(value, width)
        }
    }

    private fun encodeElement(id: Long, content: ByteArray): ByteArray = idBytes(id) + encodeSize(content.size.toLong()) + content
    private fun idBytes(id: Long): ByteArray {
        val length = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(length) { index -> (id ushr (8 * (length - index - 1))).toByte() }
    }
    private fun encodeSize(size: Long): ByteArray {
        for (width in 1..8) {
            val max = (1L shl (7 * width)) - 2
            if (width == 8 || size <= max) {
                val bytes = ByteArray(width)
                var value = size
                for (index in width - 1 downTo 0) { bytes[index] = value.toByte(); value = value ushr 8 }
                bytes[0] = (bytes[0].toInt() or (1 shl (8 - width))).toByte()
                return bytes
            }
        }
        unsupported()
    }
    private fun unknownSize(width: Int): ByteArray = ByteArray(width) { index -> if (index == 0) (1 shl (8 - width) or ((1 shl (8 - width)) - 1)).toByte() else 0xff.toByte() }
    private fun knownSize8(size: Long): ByteArray = byteArrayOf(0x01) + fixedUnsignedBytes(size, 7)
    private fun fixedUnsignedBytes(value: Long, width: Int): ByteArray {
        if (value < 0 || width !in 1..8) unsupported()
        return ByteArray(width) { index -> (value ushr (8 * (width - index - 1))).toByte() }
    }
    private fun unsignedBytes(value: Long): ByteArray {
        if (value < 0) unsupported()
        val width = maxOf(1, (64 - java.lang.Long.numberOfLeadingZeros(value) + 7) / 8)
        return ByteArray(width) { index -> (value ushr (8 * (width - index - 1))).toByte() }
    }

    private companion object {
        const val EBML = 0x1A45DFA3L
        const val SEGMENT = 0x18538067L
        const val SEEK_HEAD = 0x114D9B74L
        const val INFO = 0x1549A966L
        const val TRACKS = 0x1654AE6BL
        const val CUES = 0x1C53BB6BL
        const val CLUSTER = 0x1F43B675L
        const val TIMECODE = 0xE7L
        const val SIMPLE_BLOCK = 0xA3L
        const val BLOCK_GROUP = 0xA0L
        const val BLOCK = 0xA1L
        const val REFERENCE_BLOCK = 0xFBL
        const val BLOCK_DURATION = 0x9BL
        const val TRACK_ENTRY = 0xAEL
        const val TRACK_NUMBER = 0xD7L
        const val TRACK_TYPE = 0x83L
        const val DEFAULT_DURATION = 0x23E383L
        const val VIDEO_TRACK_TYPE = 1L
        const val TIMECODE_SCALE = 0x2AD7B1L
        const val DURATION = 0x4489L
        const val CRC32 = 0xBFL
        const val CUE_POINT = 0xBBL
        const val CUE_TIME = 0xB3L
        const val CUE_TRACK_POSITIONS = 0xB7L
        const val CUE_TRACK = 0xF7L
        const val CUE_CLUSTER_POSITION = 0xF1L
        const val CUE_BLOCK_NUMBER = 0x5378L
        const val SEEK = 0x4DBBL
        const val SEEK_ID = 0x53ABL
        const val SEEK_POSITION = 0x53ACL
        const val DEFAULT_TIMECODE_SCALE = 1_000_000L
        const val DEFAULT_FRAME_INTERVAL_NS = 100_000_000L
        val LEVEL_ONE_IDS = setOf(0x114D9B74L, 0x1549A966L, 0x1654AE6BL, 0x1C53BB6BL, 0x1941A469L, 0x1043A770L, 0x1254C367L, CLUSTER)
    }
}
