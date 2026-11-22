package com.ryn.aispeed.feature.downloads.engine

import com.ryn.aispeed.core.security.UrlAndPathValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Shared Download Engine policy constants matching shared/contracts/download_contract.json.
 */
object DownloadPolicy {
    const val MIN_RANGED_FILE_BYTES = 2L * 1024L * 1024L          // 2 MB
    const val WORK_STEALING_MIN_REMAINING = 4L * 1024L * 1024L    // 4 MB
    const val DEFAULT_MAX_CONNECTIONS = 8
    const val HARD_MAX_CONNECTIONS = 16
    const val CHECKPOINT_FLUSH_BYTES = 512L * 1024L               // 512 KB
    const val DISK_SAFETY_BUFFER_BYTES = 50L * 1024L * 1024L      // 50 MB
    const val SPARSE_TEMP_EXTENSION = ".ryntmp"

    fun computeOptimalConnections(fileSize: Long?, supportsRanges: Boolean, userCap: Int): Int {
        if (!supportsRanges || fileSize == null || fileSize < MIN_RANGED_FILE_BYTES) return 1
        val cap = userCap.coerceIn(1, HARD_MAX_CONNECTIONS)
        val recommended = when {
            fileSize < 20L * 1024L * 1024L -> 2
            fileSize < 100L * 1024L * 1024L -> 4
            fileSize < 500L * 1024L * 1024L -> 6
            else -> DEFAULT_MAX_CONNECTIONS
        }
        return min(recommended, cap)
    }

    fun classifyCategory(fileName: String, mimeType: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "mov", "webm") -> "VIDEO"
            mime.startsWith("audio/") || ext in setOf("mp3", "flac", "wav", "aac", "ogg", "m4a") -> "AUDIO"
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "svg") -> "IMAGES"
            ext in setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2") -> "ARCHIVES"
            ext in setOf("apk", "exe", "msi", "msix", "dmg", "deb", "iso") -> "SOFTWARE"
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv") -> "DOCUMENTS"
            else -> "OTHER"
        }
    }
}

data class ProbeResult(
    val finalUrl: String,
    val fileName: String,
    val category: String,
    val mimeType: String?,
    val fileSize: Long?,
    val supportsRanges: Boolean,
    val etag: String?,
    val lastModified: String?
)

data class ActiveSegment(
    val id: String,
    val index: Int,
    val startByte: Long,
    @Volatile var endByte: Long,
    val downloadedBytes: AtomicLong = AtomicLong(0L),
    @Volatile var status: String = "PENDING",
    @Volatile var retryCount: Int = 0
) {
    fun currentPosition(): Long = startByte + downloadedBytes.get()
    fun remainingBytes(): Long = (endByte - currentPosition() + 1L).coerceAtLeast(0L)
}

/**
 * Dynamic Work-Stealing Coordinator: when a fast worker finishes early, it splits the
 * largest remaining active segment (>= 4 MB left) in half to eliminate tail-latency stalls.
 */
class SegmentCoordinator(
    private val downloadId: String,
    initialSegments: List<ActiveSegment>
) {
    private val mutex = Mutex()
    private val segments = initialSegments.toMutableList()
    private var nextIndex = initialSegments.size

    suspend fun snapshot(): List<ActiveSegment> = mutex.withLock { segments.toList() }

    suspend fun stealWorkOrNull(): ActiveSegment? = mutex.withLock {
        val candidate = segments
            .filter { it.status == "DOWNLOADING" && it.remainingBytes() >= DownloadPolicy.WORK_STEALING_MIN_REMAINING }
            .maxByOrNull { it.remainingBytes() }
            ?: return null

        val currentPos = candidate.currentPosition()
        val oldEnd = candidate.endByte
        val remaining = oldEnd - currentPos + 1L
        if (remaining < DownloadPolicy.WORK_STEALING_MIN_REMAINING) return null

        val mid = currentPos + (remaining / 2L)
        candidate.endByte = mid

        val stolen = ActiveSegment(
            id = "${downloadId}_seg_${nextIndex}",
            index = nextIndex++,
            startByte = mid + 1L,
            endByte = oldEnd,
            status = "PENDING"
        )
        segments.add(stolen)
        return stolen
    }
}

/**
 * Non-blocking async Token-Bucket bandwidth limiter (0 = unlimited).
 */
class TokenBucketLimiter(@Volatile var bytesPerSecond: Long = 0L) {
    private val mutex = Mutex()
    private var availableTokens: Double = 0.0
    private var lastRefillNanos: Long = System.nanoTime()

    suspend fun acquire(bytes: Int) {
        val rate = bytesPerSecond
        if (rate <= 0L || bytes <= 0) return

        var sleepMs = 0L
        mutex.withLock {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0
            lastRefillNanos = now
            availableTokens = min(rate.toDouble(), availableTokens + elapsedSec * rate.toDouble())
            availableTokens -= bytes.toDouble()
            if (availableTokens < 0.0) {
                sleepMs = ((-availableTokens / rate.toDouble()) * 1000.0).toLong().coerceAtLeast(1L)
            }
        }
        if (sleepMs > 0L) delay(sleepMs)
    }
}

/**
 * Pre-allocated sparse .ryntmp writer using thread-safe positional FileChannel.write(buf, offset)
 * and atomic SHA-256 finalization.
 */
class SparseFileWriter(
    private val tempFile: File,
    private val finalFile: File
) : AutoCloseable {
    private val raf: RandomAccessFile
    private val channel: FileChannel

    init {
        tempFile.parentFile?.mkdirs()
        raf = RandomAccessFile(tempFile, "rw")
        channel = raf.channel
    }

    fun preallocate(totalBytes: Long) {
        val freeBytes = (tempFile.parentFile ?: tempFile).usableSpace
        if (freeBytes < totalBytes + DownloadPolicy.DISK_SAFETY_BUFFER_BYTES) {
            throw IllegalStateException("INSUFFICIENT_DISK_SPACE: Required ${totalBytes + DownloadPolicy.DISK_SAFETY_BUFFER_BYTES} bytes, available $freeBytes")
        }
        if (raf.length() != totalBytes) {
            raf.setLength(totalBytes)
        }
    }

    fun writeAt(offset: Long, data: ByteArray, length: Int) {
        val buffer = ByteBuffer.wrap(data, 0, length)
        var pos = offset
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, pos)
            pos += written
        }
    }

    fun finalizeAtomically(expectedSize: Long?, expectedSha256: String?): String {
        channel.force(true)
        close()

        if (expectedSize != null && tempFile.length() != expectedSize) {
            throw IllegalStateException("IO_WRITE_ERROR: File size mismatch (${tempFile.length()} != $expectedSize)")
        }

        val actualSha256 = computeSha256(tempFile)
        if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw SecurityException("CHECKSUM_MISMATCH: Expected $expectedSha256, got $actualSha256")
        }

        val safeFinal = UrlAndPathValidator.resolveSafeTargetFile(
            finalFile.parentFile ?: throw SecurityException("UNSAFE_FILENAME: Missing parent"),
            finalFile.name
        )
        if (!tempFile.renameTo(safeFinal)) {
            tempFile.copyTo(safeFinal, overwrite = true)
            tempFile.delete()
        }
        return actualSha256
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
}
