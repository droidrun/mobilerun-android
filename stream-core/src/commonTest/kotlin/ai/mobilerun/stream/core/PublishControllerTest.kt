// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.ClientMessage
import ai.mobilerun.stream.protocol.ServerFrame
import ai.mobilerun.stream.protocol.ServerMessage
import ai.mobilerun.stream.protocol.SignallingPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

internal class FakePublishCapture : PublishCapture {
    val attached = mutableListOf<PublishKind>()
    var stops = 0
    var detaches = 0
    var attachGate: CompletableDeferred<Unit>? = null
    var attachError: Exception? = null
    var failingKind: PublishKind? = null
    override suspend fun attach(kind: PublishKind) {
        withContext(NonCancellable) { attachGate?.await() }
        if (kind == failingKind) error("capture attachment failed")
        attachError?.let { throw it }
        attached += kind
    }
    override fun detach() { detaches++ }
    override fun stop() { stops++ }
}

private class PublishHarness(scope: TestScope,
    capabilities: DevicePublishCapabilities? = DevicePublishCapabilities(true, true, "direct"),
) {
    val capture = FakePublishCapture()
    var captureCount = 0
    var captureGate: CompletableDeferred<Unit>? = null
    var captureError: Exception? = null
    var supported = setOf(PublishKind.Audio, PublishKind.Video)
    var connected = true
    var sendErrorFor: String? = null
    var permissionCalls = 0
    var permissionGate: CompletableDeferred<Unit>? = null
    var ended: (() -> Unit)? = null
    val outgoing = mutableListOf<ClientMessage>()
    val errors = mutableListOf<PublishFailure>()
    private var sequence = 0
    val controller = PublishController(scope.backgroundScope, capabilities,
        { failure, _ -> errors += failure }, { "11111111-1111-4111-8111-${(++sequence).toString().padStart(12, '0')}" })
    val binding = PublishBinding(object : PublishPeerTransport {
        override val publishKinds get() = supported
        override suspend fun capture(options: PublishOptions, onEnded: () -> Unit): PublishCapture {
            captureCount++
            ended = onEnded
            withContext(NonCancellable) { captureGate?.await() }
            captureError?.let { throw it }
            return capture
        }
    }, { connected }, {
        if ((it is ClientMessage.PublishStart && sendErrorFor == "start") ||
            (it is ClientMessage.StopPublish && sendErrorFor == "stop")) error("socket write failed")
        outgoing += it
        true
    })
    val state get() = controller.state.value
    fun start(video: Boolean = false) = controller.start(PublishOptions(true, video), binding, false) {
        permissionCalls++
        permissionGate?.await()
    }
    fun frame(json: String) {
        val parsed = ServerMessage.parse(json, SignallingPhase.Established)
        if (parsed is ServerFrame.Message && parsed.message is ServerMessage.PublishState) {
            controller.receive(binding, parsed.message as ServerMessage.PublishState)
        }
    }
    fun server(state: String, reason: String? = null) = frame(
        """{"type":"publishState","mediaSessionId":"${this.state.mediaSessionId}","state":"$state","reason":${reason?.let { "\"$it\"" } ?: "null"}}""")
}

class PublishControllerTest {
    @Test fun acceptedThenActiveAttachesInOrderAndReturnsActive() = runTest {
        val h = PublishHarness(this)
        val start = h.start(video = true)
        runCurrent()
        assertEquals(PublishPhase.Starting, h.state.phase)
        assertEquals(emptyList(), h.capture.attached)
        assertEquals(Json.parseToJsonElement("""{"type":"publishStart","mediaSessionId":"11111111-1111-4111-8111-000000000001","kinds":["audio","video"]}"""),
            Json.parseToJsonElement(h.outgoing.single().toJson()))
        h.server("accepted")
        runCurrent()
        assertEquals(listOf(PublishKind.Audio, PublishKind.Video), h.capture.attached)
        assertFalse(start.isCompleted)
        h.server("active")
        assertEquals(PublishPhase.Active, start.await().phase)
        h.server("accepted")
        h.server("active")
        runCurrent()
        assertEquals(2, h.capture.attached.size)
        h.controller.stop()
        assertTrue(h.capture.stops > 0)
        assertTrue(h.capture.detaches > 0)
        assertEquals("stopped_by_client", h.state.reason)
        assertTrue(h.errors.isEmpty())
    }

    @Test fun activeBeforeAttachmentFinishesIsLatched() = runTest {
        val h = PublishHarness(this)
        h.capture.attachGate = CompletableDeferred()
        val start = h.start()
        runCurrent()
        h.server("active")
        runCurrent()
        assertEquals(PublishPhase.Capturing, h.state.phase)
        assertFalse(start.isCompleted)
        h.capture.attachGate!!.complete(Unit)
        runCurrent()
        assertEquals(PublishPhase.Active, start.await().phase)
    }

    @Test fun forbiddenPreservesViewerWithoutPermissionOrCapture() = runTest {
        val h = PublishHarness(this)
        val start = h.start()
        runCurrent()
        h.server("rejected", "forbidden")
        val failure = assertFailsWith<PublishException> { start.await() }.failure
        assertEquals(StreamErrorReason.PublishNotAllowed, failure.reason)
        assertEquals("publish_not_allowed", h.state.reason)
        assertEquals("forbidden", h.state.serverReason)
        assertTrue(h.connected)
        assertEquals(emptyList(), h.capture.attached)
        assertEquals(0, h.captureCount)
        assertEquals(0, h.permissionCalls)
        h.server("rejected", "forbidden")
        assertEquals(1, h.errors.size)
        assertEquals(1, h.outgoing.filterIsInstance<ClientMessage.StopPublish>().size)
    }

    @Test fun silenceUsesFiveSecondUnsupportedDeadline() = runTest {
        val h = PublishHarness(this)
        val start = h.start()
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(start.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(StreamErrorReason.PublishUnsupported, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertEquals("response_timeout", h.state.detail)
        assertEquals(0, h.captureCount)
        assertEquals(0, h.permissionCalls)
        assertEquals(emptyList(), h.capture.attached)
    }

    @Test fun duplicateAcceptedDoesNotExtendActiveDeadline() = runTest {
        val h = PublishHarness(this)
        val start = h.start()
        runCurrent()
        h.server("accepted")
        runCurrent()
        advanceTimeBy(29_999)
        h.server("accepted")
        assertFalse(start.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(StreamErrorReason.PublishTimeout, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertEquals("active_timeout", h.state.detail)
        assertTrue(h.capture.stops > 0)
    }

    @Test fun serverFailuresNormalizeWithOriginalReason() = runTest {
        listOf("busy" to StreamErrorReason.PublishBusy, "unsupported" to StreamErrorReason.PublishUnsupported,
            "inject_failed" to StreamErrorReason.PublishFailed, "source_lost" to StreamErrorReason.PublishFailed,
            "connection_closed" to StreamErrorReason.Disconnected, "unknown_reason" to StreamErrorReason.PublishFailed).forEach { (wire, expected) ->
            val h = PublishHarness(this)
            val start = h.start()
            runCurrent()
            h.server("error", wire)
            assertEquals(expected, assertFailsWith<PublishException> { start.await() }.failure.reason)
            assertEquals(wire, h.state.serverReason)
            assertEquals(0, h.captureCount)
        }
    }

    @Test fun permissionDeniedIsDistinctFromServerDenial() = runTest {
        val h = PublishHarness(this)
        h.captureError = PublishException(PublishFailure(StreamErrorReason.PermissionDenied, "permission", "capture_permission"))
        val start = h.start()
        runCurrent()
        assertEquals(0, h.permissionCalls)
        h.server("accepted")
        runCurrent()
        assertEquals(StreamErrorReason.PermissionDenied, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertEquals("capture_permission", h.state.detail)
        assertEquals(1, h.outgoing.filterIsInstance<ClientMessage.StopPublish>().size)
    }

    @Test fun stopDuringCaptureReleasesLateInputAndSignalsStop() = runTest {
        val h = PublishHarness(this)
        h.captureGate = CompletableDeferred()
        val start = h.start()
        runCurrent()
        assertEquals(0, h.permissionCalls)
        h.server("accepted")
        runCurrent()
        h.controller.stop()
        assertEquals(StreamErrorReason.PublishCancelled, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertEquals(PublishPhase.Stopped, h.state.phase)
        h.captureGate!!.complete(Unit)
        runCurrent()
        assertTrue(h.capture.stops > 0)
        assertEquals(1, h.outgoing.filterIsInstance<ClientMessage.StopPublish>().size)
        assertTrue(h.errors.isEmpty())
    }

    @Test fun terminalWhileAttachingCannotBeResurrectedByLateCompletion() = runTest {
        val h = PublishHarness(this)
        h.capture.attachGate = CompletableDeferred()
        val start = h.start()
        runCurrent()
        h.server("accepted")
        runCurrent()
        h.server("error", "source_lost")
        assertFailsWith<PublishException> { start.await() }
        assertTrue(h.capture.stops > 0)
        val detaches = h.capture.detaches
        h.capture.attachGate!!.complete(Unit)
        runCurrent()
        assertTrue(h.capture.detaches > detaches)
        assertEquals(PublishPhase.Error, h.state.phase)
    }

    @Test fun disconnectStopsActiveCaptureAndNeverResumes() = runTest {
        val h = PublishHarness(this)
        val start = h.start()
        runCurrent()
        h.server("active")
        runCurrent()
        start.await()
        h.controller.disconnected(h.binding)
        assertEquals("disconnected", h.state.reason)
        assertTrue(h.capture.stops > 0)
        assertTrue(h.capture.detaches > 0)
        h.connected = true
        h.server("active")
        runCurrent()
        assertEquals(1, h.captureCount)
        assertEquals(PublishPhase.Error, h.state.phase)
    }

    @Test fun invalidAudioRequestNeverCapturesEvenWithAllCapabilitiesAndReadiness() = runTest {
        val h = PublishHarness(this)
        val result = h.controller.start(PublishOptions(false, true), h.binding, false)
        assertEquals("invalid_options", assertFailsWith<PublishException> { result.await() }.failure.detail)
        runCurrent()
        assertEquals(0, h.captureCount)
        assertTrue(h.outgoing.isEmpty())
    }

    @Test fun capabilityAndLocalSupportGuardsRunBeforeCapture() = runTest {
        listOf(null to "capabilities_unavailable",
            DevicePublishCapabilities(false, true, "direct") to "device_capability",
            DevicePublishCapabilities(true, true, "relay") to "device_capability").forEach { (caps, detail) ->
            val h = PublishHarness(this, caps)
            assertEquals(detail, assertFailsWith<PublishException> { h.start().await() }.failure.detail)
            assertEquals(0, h.captureCount)
        }
        val h = PublishHarness(this)
        h.supported = setOf(PublishKind.Audio)
        assertEquals("local_media_support", assertFailsWith<PublishException> { h.start(true).await() }.failure.detail)
        assertEquals(0, h.captureCount)
        val audio = h.start()
        runCurrent()
        h.server("active")
        runCurrent()
        assertEquals(listOf(PublishKind.Audio), audio.await().kinds)
    }

    @Test fun duplicateStartsLeaveOriginalOperationUntouched() = runTest {
        val h = PublishHarness(this)
        val first = h.start()
        runCurrent()
        val id = h.state.mediaSessionId
        assertEquals("publish_in_progress", assertFailsWith<PublishException> { h.start().await() }.failure.detail)
        assertEquals(id, h.state.mediaSessionId)
        h.server("active")
        runCurrent()
        first.await()
        assertEquals("already_publishing", assertFailsWith<PublishException> { h.start().await() }.failure.detail)
        assertEquals(PublishPhase.Active, h.state.phase)
        assertEquals(1, h.captureCount)
    }

    @Test fun malformedForeignAndUnknownFramesDoNotAcceptOperation() = runTest {
        val h = PublishHarness(this)
        val start = h.start()
        runCurrent()
        h.frame("""{"type":"publishState","mediaSessionId":"foreign","state":"active"}""")
        h.server("future_state")
        h.frame("""{"type":"publishState","mediaSessionId":1,"state":"active"}""")
        h.frame("""{"type":"publishState","mediaSessionId":"${h.state.mediaSessionId}","state":"rejected","reason":123}""")
        assertEquals(StreamErrorReason.PublishFailed, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertNull(h.state.serverReason)
        assertTrue(h.capture.attached.isEmpty())
    }
    @Test fun failedCameraAttachmentCleansUpAlreadyAttachedAudio() = runTest {
        val h = PublishHarness(this)
        h.capture.failingKind = PublishKind.Video
        val start = h.start(true)
        runCurrent()
        h.server("active")
        runCurrent()
        assertEquals("attach_failed", assertFailsWith<PublishException> { start.await() }.failure.detail)
        assertEquals(listOf(PublishKind.Audio), h.capture.attached)
        assertTrue(h.capture.stops > 0)
        assertTrue(h.capture.detaches > 0)
        assertEquals(PublishPhase.Error, h.state.phase)
    }

    @Test fun endedInputTerminatesWholeActivePublication() = runTest {
        val h = PublishHarness(this)
        val start = h.start(true)
        runCurrent()
        h.server("active")
        runCurrent()
        start.await()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(PublishPhase.Active, h.state.phase)
        h.ended!!.invoke()
        runCurrent()
        assertEquals("capture_unavailable", h.state.detail)
        assertTrue(h.capture.stops > 0)
        assertTrue(h.capture.detaches > 0)
        assertEquals(1, h.errors.size)
    }

    @Test fun explicitRestartUsesFreshIdAndIgnoresOldConnectionAndId() = runTest {
        val h = PublishHarness(this)
        val first = h.start()
        runCurrent()
        val oldId = h.state.mediaSessionId
        h.controller.stop()
        assertFailsWith<PublishException> { first.await() }
        val stopped = h.state
        h.controller.stop()
        assertSame(stopped, h.state)
        val second = h.start()
        runCurrent()
        assertNotEquals(oldId, h.state.mediaSessionId)
        h.frame("""{"type":"publishState","mediaSessionId":"$oldId","state":"active"}""")
        val message = ServerMessage.parse("""{"type":"publishState","mediaSessionId":"${h.state.mediaSessionId}","state":"active"}""",
            SignallingPhase.Established) as ServerFrame.Message
        h.controller.receive(PublishBinding(null, { true }, { true }), message.message as ServerMessage.PublishState)
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(PublishPhase.Starting, h.state.phase)
        h.controller.stop()
        assertFailsWith<PublishException> { second.await() }
    }

    @Test fun permissionPromptStartsOnlyAfterAcceptanceAndHasThirtySeconds() = runTest {
        val h = PublishHarness(this)
        h.permissionGate = CompletableDeferred()
        val start = h.start(true)
        runCurrent()
        assertEquals(0, h.permissionCalls)
        assertEquals(0, h.captureCount)
        advanceTimeBy(4_999)
        h.server("accepted")
        runCurrent()
        assertEquals(1, h.permissionCalls)
        assertEquals(0, h.captureCount)
        advanceTimeBy(29_999)
        runCurrent()
        assertFalse(start.isCompleted)
        h.permissionGate!!.complete(Unit)
        h.server("active")
        runCurrent()
        assertEquals(PublishPhase.Active, start.await().phase)
        assertEquals(1, h.captureCount)
    }

    @Test fun timeoutDuringPermissionPromptSendsStopAndPreventsLateCapture() = runTest {
        val h = PublishHarness(this)
        h.permissionGate = CompletableDeferred()
        val start = h.start()
        h.server("accepted")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(StreamErrorReason.PublishTimeout, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertEquals(1, h.outgoing.filterIsInstance<ClientMessage.StopPublish>().size)
        h.permissionGate!!.complete(Unit)
        runCurrent()
        assertEquals(0, h.captureCount)
        assertEquals(PublishPhase.Error, h.state.phase)
    }

    @Test fun throwingStartSendReportsDisconnectedWithoutPermissionOrCapture() = runTest {
        val h = PublishHarness(this)
        h.sendErrorFor = "start"
        val start = h.start()
        runCurrent()
        assertEquals(StreamErrorReason.Disconnected, assertFailsWith<PublishException> { start.await() }.failure.reason)
        assertEquals("disconnected", h.state.reason)
        assertEquals(1, h.errors.size)
        assertEquals(0, h.captureCount)
        assertEquals(0, h.permissionCalls)
    }

    @Test fun throwingStopSendReportsDisconnectedAndReleasesActiveCaptureOnce() = runTest {
        val h = PublishHarness(this)
        val start = h.start()
        h.server("active")
        runCurrent()
        start.await()
        h.sendErrorFor = "stop"
        h.controller.stop()
        assertEquals("disconnected", h.state.reason)
        assertTrue(h.capture.stops > 0)
        assertTrue(h.capture.detaches > 0)
        assertEquals(1, h.errors.size)
        h.controller.stop()
        assertEquals(1, h.errors.size)
    }

}
