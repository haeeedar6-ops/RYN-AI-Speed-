package com.ryn.aispeed.feature.downloads.engine

import com.ryn.aispeed.core.common.ModuleEvent
import com.ryn.aispeed.core.common.ModuleEventBus
import com.ryn.aispeed.core.common.RynResult
import com.ryn.aispeed.core.common.runIsolated
import com.ryn.aispeed.core.database.DownloadDao
import com.ryn.aispeed.core.database.DownloadEntity
import com.ryn.aispeed.core.database.DownloadSegmentEntity
import com.ryn.aispeed.core.security.LogRedactor
import com.ryn.aispeed.core.security.UrlAndPathValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * DNS resolver that refuses private/loopback/reserved targets at CONNECTION time.
 * Validating the host once up-front is not enough: a hostile DNS server can answer with a public
 * address for the check and a private one for the real connection (DNS rebinding).
 */
private class SsrfSafeDns(private val allowLocalNetwork: Boolean) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (!allowLocalNetwork && addresses.any { UrlAndPathValidator.isBlockedAddress(it) }) {
            throw UnknownHostException("SSRF_BLOCKED: Private or loopback target blocked")
        }
        return addresses
    }
}

/**
 * Production Download Engine for Android:
 * - SSRF-safe probing with redirect re-validation and DNS-rebinding protection
 * - Strict strong-ETag / Last-Modified If-Range validation (never corrupts changed files)
 * - Dynamic Work-Stealing multi-connection execution (siblings are cancelled when one worker fails)
 * - Exponential backoff with jitter on 429/503/transient errors (fatal 4xx are never retried)
 * - Atomic SHA-256 finalization, never overwrites an existing user file
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
        .dns(SsrfSafeDns(allowLocalNetwork))
        .build()

    private val globalLimiter = TokenBucketLimiter(0L)

    // Downloads are intentionally detached from the caller: rotating the screen or leaving the
    // UI must not abort a half-written file. Workers inside one download are fully structured.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setGlobalSpeedLimit(bytesPerSecond: Long) {
        globalLimiter.bytesPerSecond = bytesPerSecond.coerceAtLeast(0L)
    }

    suspend fun probeUrl(rawUrl: String): RynResult<ProbeResult> = runIsolated("PROBE_FAILED") {
        withContext(Dispatchers.IO) {
            var currentUrl = UrlAndPathValidator.validateDownloadUrl(rawUrl, allowLocalNetwork).toString()
            var hops = 0
            var probed: ProbeResult? = null

            // One ranged GET (bytes=0-0) tells us everything: redirects, size, Range support, validators.
            while (probed == null) {
                ensureActive()
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    when (resp.code) {
                        301, 302, 303, 307, 308 -> {
                            hops++
                            if (hops > MAX_REDIRECTS) {
                                throw IllegalStateException("TOO_MANY_REDIRECTS: more than $MAX_REDIRECTS redirects")
                            }
                            val location = resp.header("Location")
                                ?: throw IllegalStateException("Redirect without Location header")
                            val next = resp.request.url.resolve(location)
                                ?: throw IllegalStateException("Invalid redirect Location")
                            currentUrl = UrlAndPathValidator.validateDownloadUrl(next.toString(), allowLocalNetwork).toString()
                        }
                        401, 403 -> throw SecurityException("AUTH_OR_EXPIRED_LINK: HTTP ${resp.code}")
                        404, 410 -> throw IllegalStateException("RESOURCE_NOT_FOUND: HTTP ${resp.code}")
                        in 200..299 -> probed = buildProbeResult(currentUrl, resp)
                        else -> throw IllegalStateException("Unexpected HTTP ${resp.code}")
                    }
                    Unit
                }
            }
            checkNotNull(probed)
        }
    }

    private fun buildProbeResult(finalUrl: String, resp: Response): ProbeResult {
        val partial = resp.code == 206
        // A 206 whose total is unknown ("bytes 0-0/*") must NOT fall back to Content-Length (that is 1 byte).
        val totalSize: Long? = if (partial) {
            parseTotalSizeFromContentRange(resp.header("Content-Range"))
        } else {
            resp.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
        }
        val rawName = parseContentDispositionName(resp.header("Content-Disposition"))
            ?: finalUrl.substringBefore('?').substringAfterLast('/').ifBlank { "download.bin" }
        val safeName = UrlAndPathValidator.sanitizeFileName(rawName)
        val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()

        return ProbeResult(
            finalUrl = finalUrl,
            fileName = safeName,
            category = DownloadPolicy.classifyCategory(safeName, mime),
            mimeType = mime,
            fileSize = totalSize,
            supportsRanges = partial,
            etag = resp.header("ETag"),
            lastModified = resp.header("Last-Modified")
        )
    }

    suspend fun startDownload(
        rawUrl: String,
        expectedSha256: String? = null,
        maxConnectionsCap: Int = DownloadPolicy.DEFAULT_MAX_CONNECTIONS,
        perDownloadLimitBps: Long = 0L
    ): RynResult<DownloadEntity> = runIsolated("DOWNLOAD_FAILED") {
        engineScope.async {
            runDownload(rawUrl, expectedSha256, maxConnectionsCap, perDownloadLimitBps)
        }.await()
    }

    private suspend fun runDownload(
        rawUrl: String,
        expectedSha256: String?,
        maxConnectionsCap: Int,
        perDownloadLimitBps: Long
    ): DownloadEntity {
        val probe = when (val p = probeUrl(rawUrl)) {
            is RynResult.Success -> p.data
            is RynResult.Failure -> throw IllegalStateException("${p.code}: ${p.message}")
        }

        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val categoryDir = File(rootDownloadDir, probe.category.lowercase()).apply { mkdirs() }
        val finalFile = claimUniqueTarget(categoryDir, probe.fileName)
        val tempFile = tempFileFor(finalFile)
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

        try {
            downloadDao.upsertDownload(entity)

            val itemLimiter = TokenBucketLimiter(perDownloadLimitBps)
            // If-Range only works with STRONG validators; a weak ETag (W/"...") would make servers answer
            // 200 and we would wrongly report "file changed". Fall back to Last-Modified in that case.
            val ifRangeValidator = probe.etag?.takeUnless { it.startsWith("W/") } ?: probe.lastModified

            val actualSha256 = withContext(Dispatchers.IO) {
                SparseFileWriter(tempFile, finalFile).use { writer ->
                    val fileSize = probe.fileSize
                    if (probe.supportsRanges && fileSize != null && fileSize >= DownloadPolicy.MIN_RANGED_FILE_BYTES) {
                        writer.preallocate(fileSize)
                        val initialSegments = buildInitialSegments(id, fileSize, connections)
                        persistSegments(id, initialSegments)
                        val coordinator = SegmentCoordinator(id, initialSegments)

                        // coroutineScope = structured: the first failing worker cancels all siblings.
                        coroutineScope {
                            (0 until connections).map { workerIdx ->
                                async {
                                    var seg: ActiveSegment? = initialSegments.getOrNull(workerIdx)
                                    while (seg != null) {
                                        downloadSegment(
                                            url = probe.finalUrl,
                                            segment = seg,
                                            ifRange = ifRangeValidator,
                                            writer = writer,
                                            itemLimiter = itemLimiter,
                                            downloadId = id,
                                            totalSize = fileSize
                                        )
                                        seg = coordinator.stealWorkOrNull()
                                    }
                                }
                            }.awaitAll()
                        }
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
            return entity
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                runCatching { tempFile.delete() }
                runCatching {
                    downloadDao.upsertDownload(entity.copy(status = "CANCELED", updatedAt = Instant.now().toString()))
                }
            }
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { tempFile.delete() }
                runCatching {
                    downloadDao.upsertDownload(
                        entity.copy(
                            status = "FAILED",
                            lastErrorCode = "DOWNLOAD_FAILED",
                            lastErrorMessage = LogRedactor.redact(e.message ?: e.javaClass.simpleName),
                            updatedAt = Instant.now().toString()
                        )
                    )
                }
            }
            throw e
        }
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
        while (attempt <= MAX_SEGMENT_RETRIES) {
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

                            while (true) {
                                // endByte can shrink while we run (work-stealing): read it once per iteration.
                                val end = segment.endByte
                                if (writePos > end) break
                                currentCoroutineContext().ensureActive()
                                val maxRead = min(buf.size.toLong(), end - writePos + 1L).toInt()
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
                        404, 410 -> throw FatalHttpException("RESOURCE_NOT_FOUND: HTTP ${resp.code}")
                        429, 503 -> {
                            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: (1L shl attempt)
                            delay((retryAfterSec.coerceIn(1L, 60L) * 1000L) + Random.nextLong(100L, 600L))
                            attempt++
                        }
                        else -> throw IllegalStateException("Unexpected HTTP ${resp.code}")
                    }
                    Unit
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                segment.status = "FAILED"
                throw se
            } catch (fe: FatalHttpException) {
                segment.status = "FAILED"
                throw fe
            } catch (e: Exception) {
                attempt++
                segment.retryCount = attempt
                if (attempt > MAX_SEGMENT_RETRIES) {
                    segment.status = "FAILED"
                    throw e
                }
                val backoffMs = min(60_000L, (1000L shl attempt)) + Random.nextLong(100L, 500L)
                delay(backoffMs)
            }
        }
        // All attempts consumed by throttling (429/503) without a single success.
        segment.status = "FAILED"
        throw IllegalStateException("Segment ${segment.index} failed: server kept throttling the request")
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
            if (resp.code == 401 || resp.code == 403) throw SecurityException("AUTH_OR_EXPIRED_LINK: HTTP ${resp.code}")
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty body")
            val buf = ByteArray(32 * 1024)
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
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

    /**
     * Atomically reserves a free "<name>.ryntmp" and returns the matching final file ("name", "name (1)", ...).
     * Guarantees an existing user file (or a parallel download of the same name) is never overwritten.
     */
    private fun claimUniqueTarget(dir: File, fileName: String): File {
        val first = UrlAndPathValidator.resolveSafeTargetFile(dir, fileName)
        val stem = first.nameWithoutExtension
        val ext = first.extension.let { if (it.isEmpty()) "" else ".$it" }
        var candidate = first
        var counter = 0
        while (true) {
            val free = !candidate.exists() && tempFileFor(candidate).createNewFile()
            if (free) return candidate
            counter++
            if (counter > MAX_DUPLICATE_NAMES) {
                throw IllegalStateException("TOO_MANY_DUPLICATE_FILE_NAMES: $fileName")
            }
            candidate = UrlAndPathValidator.resolveSafeTargetFile(dir, "$stem ($counter)$ext")
        }
    }

    private fun tempFileFor(finalFile: File): File =
        File(finalFile.parentFile, "${finalFile.name}${DownloadPolicy.SPARSE_TEMP_EXTENSION}")

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

    /** Non-retryable HTTP failure (e.g. 404/410): retrying would only waste time and battery. */
    private class FatalHttpException(message: String) : RuntimeException(message)

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_SEGMENT_RETRIES = 5
        const val MAX_DUPLICATE_NAMES = 9_999
    }
}
