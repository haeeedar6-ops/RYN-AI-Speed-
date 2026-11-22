package com.ryn.aispeed.feature.downloads.engine

import com.ryn.aispeed.core.common.ModuleEvent
import com.ryn.aispeed.core.common.ModuleEventBus
import com.ryn.aispeed.core.common.RynResult
import com.ryn.aispeed.core.common.runIsolated
import com.ryn.aispeed.core.database.DownloadDao
import com.ryn.aispeed.core.database.DownloadEntity
import com.ryn.aispeed.core.database.DownloadSegmentEntity
import com.ryn.aispeed.core.security.UrlAndPathValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Production Download Engine for Android:
 * - SSRF-safe probing with redirect re-validation
 * - Strict ETag / Last-Modified If-Range validation (never corrupts changed files)
 * - Dynamic Work-Stealing multi-connection execution
 * - Exponential backoff with jitter on 429/503/transient errors
 * - Atomic SHA-256 finalization
 */
class RynDownloadEngine(
    private val downloadDao: DownloadDao,
    private val eventBus: ModuleEventBus,
    private val rootDownloadDir: File,
    private val allowLocalNetwork: Boolean = false
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val globalLimiter = TokenBucketLimiter(0L)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setGlobalSpeedLimit(bytesPerSecond: Long) {
        globalLimiter.bytesPerSecond = bytesPerSecond.coerceAtLeast(0L)
    }

    suspend fun probeUrl(rawUrl: String): RynResult<ProbeResult> = runIsolated("PROBE_FAILED") {
        withContext(Dispatchers.IO) {
            var currentUrl = UrlAndPathValidator.validateDownloadUrl(rawUrl, allowLocalNetwork).toString()
            var hops = 0
            while (hops < 5) {
                val headReq = Request.Builder().url(currentUrl).head().build()
                httpClient.newCall(headReq).execute().use { resp ->
                    if (resp.code in setOf(301, 302, 303, 307, 308)) {
                        val location = resp.header("Location")
                            ?: throw IllegalStateException("Redirect without Location header")
                        currentUrl = UrlAndPathValidator.validateDownloadUrl(location, allowLocalNetwork).toString()
                        hops++
                        continue
                    }
                    if (resp.code == 401 || resp.code == 403) {
                        throw SecurityException("AUTH_OR_EXPIRED_LINK: HTTP ${resp.code}")
                    }
                    if (resp.code == 404 || resp.code == 410) {
                        throw IllegalStateException("RESOURCE_NOT_FOUND: HTTP ${resp.code}")
                    }
                    break
                }
            }

            // Confirm true Range support via GET Range: bytes=0-0
            val rangeReq = Request.Builder()
                .url(currentUrl)
                .header("Range", "bytes=0-0")
                .get()
                .build()

            httpClient.newCall(rangeReq).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw SecurityException("AUTH_OR_EXPIRED_LINK: HTTP ${resp.code}")
                }
                if (resp.code == 404 || resp.code == 410) {
                    throw IllegalStateException("RESOURCE_NOT_FOUND: HTTP ${resp.code}")
                }
                val supportsRanges = resp.code == 206
                val contentRange = resp.header("Content-Range")
                val totalSize = parseTotalSizeFromContentRange(contentRange)
                    ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
                val rawName = parseContentDispositionName(resp.header("Content-Disposition"))
                    ?: currentUrl.substringBefore('?').substringAfterLast('/').ifBlank { "download.bin" }
                val safeName = UrlAndPathValidator.sanitizeFileName(rawName)
                val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()

                ProbeResult(
                    finalUrl = currentUrl,
                    fileName = safeName,
                    category = DownloadPolicy.classifyCategory(safeName, mime),
                    mimeType = mime,
                    fileSize = totalSize,
                    supportsRanges = supportsRanges,
                    etag = resp.header("ETag"),
                    lastModified = resp.header("Last-Modified")
                )
            }
        }
    }

    suspend fun startDownload(
        rawUrl: String,
        expectedSha256: String? = null,
        maxConnectionsCap: Int = DownloadPolicy.DEFAULT_MAX_CONNECTIONS,
        perDownloadLimitBps: Long = 0L
    ): RynResult<DownloadEntity> = runIsolated("DOWNLOAD_FAILED") {
        val probe = when (val p = probeUrl(rawUrl)) {
            is RynResult.Success -> p.data
            is RynResult.Failure -> throw IllegalStateException("${p.code}: ${p.message}")
        }

        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val categoryDir = File(rootDownloadDir, probe.category.lowercase()).apply { mkdirs() }
        val finalFile = UrlAndPathValidator.resolveSafeTargetFile(categoryDir, probe.fileName)
        val tempFile = File(categoryDir, "${finalFile.name}${DownloadPolicy.SPARSE_TEMP_EXTENSION}")
        val connections = DownloadPolicy.computeOptimalConnections(
            probe.fileSize, probe.supportsRanges, maxConnectionsCap
        )

        var entity = DownloadEntity(
            id = id,
            url = rawUrl,
            finalUrl = probe.finalUrl,
            fileName = finalFile.name,
            category = probe.category,
            mimeType = probe.mimeType,
            fileSize = probe.fileSize,
            status = "DOWNLOADING",
            maxConnections = connections,
            speedLimitBps = perDownloadLimitBps,
            supportsRanges = if (probe.supportsRanges) 1 else 0,
            etag = probe.etag,
            lastModified = probe.lastModified,
            sha256Expected = expectedSha256,
            tempPath = tempFile.absolutePath,
            savePath = finalFile.absolutePath,
            createdAt = now,
            updatedAt = now
        )
        downloadDao.upsertDownload(entity)

        val itemLimiter = TokenBucketLimiter(perDownloadLimitBps)
        val ifRangeValidator = probe.etag ?: probe.lastModified

        val actualSha256 = withContext(Dispatchers.IO) {
            SparseFileWriter(tempFile, finalFile).use { writer ->
                if (probe.supportsRanges && probe.fileSize != null && probe.fileSize >= DownloadPolicy.MIN_RANGED_FILE_BYTES) {
                    writer.preallocate(probe.fileSize)
                    val initialSegments = buildInitialSegments(id, probe.fileSize, connections)
                    persistSegments(id, initialSegments)
                    val coordinator = SegmentCoordinator(id, initialSegments)

                    val jobs = (0 until connections).map { workerIdx ->
                        engineScope.async {
                            var seg: ActiveSegment? = initialSegments.getOrNull(workerIdx)
                            while (seg != null) {
                                downloadSegment(
                                    url = probe.finalUrl,
                                    segment = seg,
                                    ifRange = ifRangeValidator,
                                    writer = writer,
                                    itemLimiter = itemLimiter,
                                    downloadId = id,
                                    totalSize = probe.fileSize
                                )
                                seg = coordinator.stealWorkOrNull()
                            }
                        }
                    }
                    jobs.awaitAll()
                    persistSegments(id, coordinator.snapshot())
                } else {
                    downloadSingleStream(probe.finalUrl, writer, itemLimiter, id, probe.fileSize)
                }
                writer.finalizeAtomically(probe.fileSize, expectedSha256)
            }
        }

        entity = entity.copy(
            status = "COMPLETED",
            downloadedBytes = finalFile.length(),
            sha256Actual = actualSha256,
            updatedAt = Instant.now().toString()
        )
        downloadDao.upsertDownload(entity)
        entity
    }

    private suspend fun downloadSegment(
        url: String,
        segment: ActiveSegment,
        ifRange: String?,
        writer: SparseFileWriter,
        itemLimiter: TokenBucketLimiter,
        downloadId: String,
        totalSize: Long
    ) {
        var attempt = 0
        while (attempt <= 5) {
            segment.status = if (attempt == 0) "CONNECTING" else "RETRYING"
            val resumeOffset = segment.currentPosition()
            if (resumeOffset > segment.endByte) {
                segment.status = "COMPLETED"
                return
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=$resumeOffset-${segment.endByte}")
            if (!ifRange.isNullOrBlank()) {
                reqBuilder.header("If-Range", ifRange)
            }

            try {
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    when (resp.code) {
                        206 -> {
                            segment.status = "DOWNLOADING"
                            val body = resp.body ?: throw IllegalStateException("Empty response body")
                            val stream = body.byteStream()
                            val buf = ByteArray(32 * 1024)
                            var writePos = resumeOffset
                            var bytesSinceEmit = 0L

                            while (writePos <= segment.endByte) {
                                val maxRead = min(buf.size.toLong(), segment.endByte - writePos + 1L).toInt()
                                val read = stream.read(buf, 0, maxRead)
                                if (read == -1) break
                                globalLimiter.acquire(read)
                                itemLimiter.acquire(read)
                                writer.writeAt(writePos, buf, read)
                                writePos += read
                                segment.downloadedBytes.addAndGet(read.toLong())
                                bytesSinceEmit += read
                                if (bytesSinceEmit >= DownloadPolicy.CHECKPOINT_FLUSH_BYTES) {
                                    bytesSinceEmit = 0L
                                    eventBus.emit(ModuleEvent.DownloadProgress(downloadId, writePos, totalSize))
                                }
                            }

                            if (segment.currentPosition() > segment.endByte) {
                                segment.status = "COMPLETED"
                                return
                            }
                            throw IllegalStateException("Premature EOF on segment ${segment.index}")
                        }
                        200 -> throw SecurityException("SERVER_FILE_CHANGED_IF_RANGE: Server returned 200 instead of 206")
                        401, 403 -> throw SecurityException("AUTH_OR_EXPIRED_LINK: HTTP ${resp.code}")
                        404, 410 -> throw IllegalStateException("RESOURCE_NOT_FOUND: HTTP ${resp.code}")
                        429, 503 -> {
                            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: (1L shl attempt)
                            delay((retryAfterSec.coerceIn(1L, 60L) * 1000L) + Random.nextLong(100L, 600L))
                            attempt++
                        }
                        else -> throw IllegalStateException("Unexpected HTTP ${resp.code}")
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                segment.status = "FAILED"
                throw se
            } catch (e: Exception) {
                attempt++
                segment.retryCount = attempt
                if (attempt > 5) {
                    segment.status = "FAILED"
                    throw e
                }
                val backoffMs = min(60_000L, (1000L shl attempt)) + Random.nextLong(100L, 500L)
                delay(backoffMs)
            }
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        writer: SparseFileWriter,
        itemLimiter: TokenBucketLimiter,
        downloadId: String,
        totalSize: Long?
    ) {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty body")
            val buf = ByteArray(32 * 1024)
            var offset = 0L
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                globalLimiter.acquire(read)
                itemLimiter.acquire(read)
                writer.writeAt(offset, buf, read)
                offset += read
                eventBus.emit(ModuleEvent.DownloadProgress(downloadId, offset, totalSize))
            }
        }
    }

    private fun buildInitialSegments(downloadId: String, fileSize: Long, count: Int): List<ActiveSegment> {
        val chunkSize = fileSize / count
        return (0 until count).map { idx ->
            val start = idx * chunkSize
            val end = if (idx == count - 1) fileSize - 1L else ((idx + 1) * chunkSize) - 1L
            ActiveSegment(id = "${downloadId}_seg_$idx", index = idx, startByte = start, endByte = end)
        }
    }

    private suspend fun persistSegments(downloadId: String, segments: List<ActiveSegment>) {
        val now = Instant.now().toString()
        downloadDao.upsertSegments(
            segments.map {
                DownloadSegmentEntity(
                    id = it.id,
                    downloadId = downloadId,
                    segmentIndex = it.index,
                    startByte = it.startByte,
                    endByte = it.endByte,
                    downloadedBytes = it.downloadedBytes.get(),
                    status = it.status,
                    retryCount = it.retryCount,
                    updatedAt = now
                )
            }
        )
    }

    private fun parseTotalSizeFromContentRange(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    private fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return match?.groupValues?.getOrNull(1)?.trim()
    }
}
