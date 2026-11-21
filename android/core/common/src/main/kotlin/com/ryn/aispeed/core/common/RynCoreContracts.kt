package com.ryn.aispeed.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fault-isolated result wrapper. Feature engines never throw unhandled exceptions across boundaries.
 */
sealed interface RynResult<out T> {
    data class Success<T>(val data: T) : RynResult<T>
    data class Failure(
        val code: String,
        val message: String,
        val cause: Throwable? = null
    ) : RynResult<Nothing>
}

/**
 * Executes a module operation inside a fault-isolation boundary while preserving coroutine cancellation.
 */
suspend inline fun <T> runIsolated(
    errorCode: String,
    crossinline block: suspend () -> T
): RynResult<T> = try {
    RynResult.Success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    RynResult.Failure(
        code = errorCode,
        message = t.message ?: "Unexpected module error",
        cause = t
    )
}

sealed interface ModuleEvent {
    data class ModuleDegraded(val module: String, val errorCode: String, val reason: String) : ModuleEvent
    data class NetworkStateChanged(val online: Boolean, val vpnActive: Boolean) : ModuleEvent
    data class DownloadProgress(val downloadId: String, val downloadedBytes: Long, val totalBytes: Long?) : ModuleEvent
}

class ModuleEventBus {
    private val _events = MutableSharedFlow<ModuleEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ModuleEvent> = _events.asSharedFlow()

    fun emit(event: ModuleEvent) {
        _events.tryEmit(event)
    }
}
