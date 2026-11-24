// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.ClientMessage
import ai.mobilerun.stream.protocol.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Called only on the engine's serial context. A binding belongs to exactly one viewer connection. */
internal class PublishBinding(
    val peer: PublishPeerTransport?,
    val connected: () -> Boolean,
    val send: (ClientMessage) -> Boolean,
)

internal class PublishController(
    private val scope: CoroutineScope,
    private val capabilities: DevicePublishCapabilities?,
    private val onError: (PublishFailure, String?) -> Unit,
    private val newId: () -> String = ::publishUuid,
) {
    private val mutableState = MutableStateFlow(PublishState())
    val state = mutableState.asStateFlow()
    private var operation: Operation? = null
    // Attachment and late detach of one capture must finish before the next capture attaches.
    private val senderMutex = Mutex()

    private class Operation(val binding: PublishBinding, val options: PublishOptions) {
        val result = CompletableDeferred<PublishState>()
        val kinds = if (options.video) listOf(PublishKind.Audio, PublishKind.Video) else listOf(PublishKind.Audio)
        var id: String? = null
        var capture: PublishCapture? = null
        var deadline: Job? = null
        var accepted = false
        var active = false
        var attached = false
    }

    fun start(options: PublishOptions, binding: PublishBinding?, closed: Boolean): CompletableDeferred<PublishState> {
        if (closed) return CompletableDeferred<PublishState>().also {
            it.completeExceptionally(PublishException(failure(StreamErrorReason.Disconnected, "session_closed")))
        }
        operation?.let {
            return rejected(StreamErrorReason.PublishFailed,
                if (it.active && it.attached) "already_publishing" else "publish_in_progress", preserve = true)
        }
        if (!options.audio) return rejected(StreamErrorReason.PublishFailed, "invalid_options")
        if (capabilities == null) return rejected(StreamErrorReason.PublishUnsupported, "capabilities_unavailable")
        if (!capabilities.supportsMicrophone || (options.video && !capabilities.cameraInjection)) {
            return rejected(StreamErrorReason.PublishUnsupported, "device_capability")
        }
        if (binding == null || !binding.connected()) return rejected(StreamErrorReason.Disconnected, "not_connected")
        val peer = binding.peer
        val requested = if (options.video) setOf(PublishKind.Audio, PublishKind.Video) else setOf(PublishKind.Audio)
        if (peer == null || !peer.publishKinds.containsAll(requested)) {
            return rejected(StreamErrorReason.PublishUnsupported, "local_media_support")
        }
        val op = Operation(binding, options)
        operation = op
        snapshot(op, PublishPhase.Capturing)
        scope.launch {
            // Capture may be non-cancellable. Keep the generation check and release late inputs.
            val capture = try {
                peer.capture(options) { scope.launch {
                    if (operation === op) fail(op, failure(StreamErrorReason.PublishFailed, "capture_unavailable"))
                } }
            } catch (e: Exception) {
                if (operation === op) fail(op, (e as? PublishException)?.failure
                    ?: failure(StreamErrorReason.PublishFailed, "capture_unavailable"))
                return@launch
            }
            if (operation !== op) {
                capture.stop()
                return@launch
            }
            op.capture = capture
            if (!binding.connected()) {
                fail(op, failure(StreamErrorReason.Disconnected, "not_connected"))
                return@launch
            }
            op.id = newId()
            snapshot(op, PublishPhase.Starting)
            arm(op, 5_000, StreamErrorReason.PublishUnsupported, "response_timeout")
            val sent = runCatching { binding.send(ClientMessage.PublishStart(op.id!!, op.kinds.map { it.wireValue })) }
                .getOrDefault(false)
            if (!sent) fail(op, failure(StreamErrorReason.Disconnected, "not_connected"))
        }
        return op.result
    }

    fun receive(binding: PublishBinding?, message: ServerMessage.PublishState) {
        val op = operation ?: return
        if (op.binding !== binding || op.id != message.mediaSessionId) return
        when (message.state) {
            "accepted", "active" -> {
                if (message.state == "active") {
                    op.active = true
                    op.deadline?.cancel()
                }
                if (!op.accepted) {
                    op.accepted = true
                    snapshot(op, PublishPhase.Accepted)
                    if (!op.active) arm(op, 15_000, StreamErrorReason.PublishTimeout, "active_timeout")
                    attach(op)
                }
                completeIfActive(op)
            }
            "rejected", "error", "stopped" -> {
                val reason = when (message.reason) {
                    "forbidden" -> StreamErrorReason.PublishNotAllowed
                    "unsupported" -> StreamErrorReason.PublishUnsupported
                    "busy" -> StreamErrorReason.PublishBusy
                    "connection_closed" -> StreamErrorReason.Disconnected
                    else -> StreamErrorReason.PublishFailed
                }
                fail(op, failure(reason,
                    if (message.reason == "stopped_by_client") "remote_stop" else null, message.reason))
            }
        }
    }

    private fun attach(op: Operation) {
        scope.launch {
            senderMutex.withLock {
                if (operation !== op) return@withLock
                val capture = op.capture ?: return@withLock
                try {
                    for (kind in op.kinds) {
                        if (operation !== op) break
                        capture.attach(kind)
                    }
                    if (operation !== op) {
                        // An attachment completing after cancellation must be undone on its original sender.
                        runCatching { capture.detach() }
                        runCatching { capture.stop() }
                        return@withLock
                    }
                    op.attached = true
                    completeIfActive(op)
                } catch (e: Exception) {
                    if (operation === op) fail(op, (e as? PublishException)?.failure
                        ?: failure(StreamErrorReason.PublishFailed, "attach_failed"))
                    else runCatching { capture.detach() }
                }
            }
        }
    }

    private fun completeIfActive(op: Operation) {
        if (operation !== op || !op.active || !op.attached || op.result.isCompleted) return
        snapshot(op, PublishPhase.Active)
        op.result.complete(state.value)
    }

    /** Synchronous local teardown never waits on acknowledgement or on a suspended attachment. */
    fun stop() {
        val op = operation ?: return
        teardown(op)
        snapshot(op, PublishPhase.Stopped, reason = "stopped_by_client")
        op.result.completeExceptionally(PublishException(failure(StreamErrorReason.PublishCancelled)))
    }

    fun cancelStart(result: CompletableDeferred<PublishState>) {
        if (operation?.result === result) stop()
    }

    fun disconnected(binding: PublishBinding?) {
        val op = operation ?: return
        if (op.binding === binding) fail(op, failure(StreamErrorReason.Disconnected))
    }

    private fun fail(op: Operation, failure: PublishFailure) {
        if (operation !== op) return
        teardown(op)
        snapshot(op, PublishPhase.Error, failure)
        onError(failure, op.id)
        op.result.completeExceptionally(PublishException(failure))
    }

    private fun teardown(op: Operation) {
        operation = null
        op.deadline?.cancel()
        op.capture?.let { capture ->
            runCatching { capture.stop() }
            runCatching { capture.detach() }
        }
        op.id?.let { id -> runCatching { op.binding.send(ClientMessage.StopPublish(id)) } }
    }

    private fun arm(op: Operation, ms: Long, reason: StreamErrorReason, detail: String) {
        op.deadline?.cancel()
        op.deadline = scope.launch {
            delay(ms)
            if (operation === op) fail(op, failure(reason, detail))
        }
    }

    private fun rejected(reason: StreamErrorReason, detail: String, preserve: Boolean = false): CompletableDeferred<PublishState> {
        val failure = failure(reason, detail)
        if (!preserve) mutableState.value = PublishState(PublishPhase.Error,
            reason = reason.wireValue, message = failure.message, detail = detail)
        onError(failure, null)
        return CompletableDeferred<PublishState>().also { it.completeExceptionally(PublishException(failure)) }
    }

    private fun snapshot(op: Operation, phase: PublishPhase, failure: PublishFailure? = null, reason: String? = null) {
        mutableState.value = PublishState(phase, op.id, op.kinds, failure?.reason?.wireValue ?: reason,
            failure?.message, failure?.detail, failure?.serverReason)
    }

    private fun failure(reason: StreamErrorReason, detail: String? = null, server: String? = null) =
        PublishFailure(reason, reason.diagnostic(), detail, server)
}

/** UUID v4 without a platform dependency. This is unrelated to the viewer SDP session id. */
private fun publishUuid(): String {
    val bytes = Random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    return bytes.mapIndexed { i, byte ->
        (if (i in listOf(4, 6, 8, 10)) "-" else "") + (byte.toInt() and 255).toString(16).padStart(2, '0')
    }.joinToString("")
}
