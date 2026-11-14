// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.core

import ai.mobilerun.stream.conformance.fixtureObject
import ai.mobilerun.stream.protocol.DataChannels
import ai.mobilerun.stream.protocol.SignallingTiming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun example(file: String, index: Int = 0): JsonObject =
    fixtureObject("signalling/$file.json").getValue("examples").jsonArray[index].jsonObject.getValue("json").jsonObject

private val sessionId = example("client-keep-alive").getValue("sessionId").jsonPrimitive.content

/** Runs [block] concurrently; its failure is delivered by `await().getOrThrow()` instead of cancelling the test. */
private fun <T> TestScope.attempt(block: suspend () -> T) = async { runCatching { block() } }

/** Drives a [StreamSessionEngine] against fake transports on the virtual clock of [scope]. */
private class Harness(
    val scope: TestScope,
    first: StreamCredentials? = StreamCredentials("wss://stream.test/a", "token-a"),
) {
    val signalling = FakeSignalling { scope.currentTime }
    val peers = FakePeers()
    var credentialCalls = 0
    var next: StreamCredentials? = first

    /** Thrown by the provider once, then cleared. */
    var nextError: Exception? = null

    /** While set, the provider suspends until it completes (a slow credentials request). */
    var gate: CompletableDeferred<Unit>? = null
    val log = mutableListOf<String>()

    /** The scope the app hands to the engine; cancelling it must release everything, like close(). */
    val host = CoroutineScope(scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job]))

    val engine = StreamSessionEngine(
        scope = host,
        credentials = {
            credentialCalls += 1
            gate?.await()
            nextError?.let { nextError = null; throw it }
            next
        },
        signalling = signalling,
        peers = peers,
        random = { 0.5 },
        nowMs = { scope.currentTime },
        newSessionId = { sessionId },
        logger = { log += it },
    )

    val socket get() = signalling.sockets.last()
    val peer get() = peers.peers.last()
    val status get() = engine.status.value

    fun settle() = scope.runCurrent()

    suspend fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** Runs the happy path up to the answer; the peer connection is not connected yet. */
    suspend fun negotiate() {
        engine.start()
        settle()
        socket.serverOpens()
        settle()
        socket.serverSends(example("server-rtc-configuration").toString())
        settle()
        socket.serverSends(example("server-answer").toString())
        settle()
    }

    /** Runs the happy path to the connected state with both channels open. */
    suspend fun connect() {
        negotiate()
        peer.emitState(PeerConnectionState.Connected)
        peer.emitChannel(DataChannels.control, true)
        peer.emitChannel(DataChannels.hover, true)
        settle()
    }

    /** Sends `serverCloses` on the newest socket, then waits out the backoff for [attempt]. */
    suspend fun failAndWaitBackoff(attempt: Int) {
        socket.serverCloses()
        settle()
        advance(ReconnectPolicy.delayMs(attempt, 0.5))
    }
}

class StreamSessionEngineTest {
    @Test
    fun happyPathReplaysTheSequenceFixture() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()

        // 1. The socket is opened with the credentials, nothing is sent before it is open.
        assertEquals(1, h.signalling.sockets.size)
        assertEquals(StreamCredentials("wss://stream.test/a", "token-a"), h.socket.credentials)
        assertEquals(StreamStatus.Connecting, h.status)
        assertTrue(h.socket.sent.isEmpty())

        // 3. Open: requestRtcConfiguration, exactly the fixture frame.
        h.advance(300)
        h.socket.serverOpens()
        h.settle()
        assertEquals(listOf(example("client-request-rtc-configuration")), h.socket.sent.map { it.json })

        // 4-7. Configuration reply: peer connection with both channels, then the offer.
        h.advance(150)
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        val channelFixtures = fixtureObject("signalling/sequence.json").getValue("channels").jsonObject
        assertEquals(
            channelFixtures.values.map { it.jsonObject.getValue("id").jsonPrimitive.int },
            h.peer.channels.map { it.id },
        )
        for (channel in h.peer.channels) {
            val fixture = channelFixtures.getValue(channel.label).jsonObject
            assertEquals(fixture.getValue("ordered").jsonPrimitive.content.toBoolean(), channel.ordered)
            assertEquals(fixture["maxRetransmits"]?.jsonPrimitive?.content?.toIntOrNull(), channel.maxRetransmits)
        }
        assertEquals(2, h.peer.configuration.iceServers.size)
        assertEquals(listOf(false), h.peer.offers)
        val offer = h.socket.framesOf("offer").single()
        assertEquals("offer-sdp-1", offer.json.getValue("sdp").jsonPrimitive.content)
        assertEquals(sessionId, offer.json.getValue("sessionId").jsonPrimitive.content)

        // 8. A local candidate goes out at once, as the fixture frame.
        val c = example("client-candidate")
        h.peer.emitLocalCandidate(
            c.getValue("candidate").jsonPrimitive.content,
            c.getValue("sdpMid").jsonPrimitive.content,
            c.getValue("sdpMLineIndex").jsonPrimitive.int,
        )
        h.settle()
        assertEquals(c, h.socket.framesOf("candidate").single().json)

        // 10. Remote candidates before the answer are buffered; the answer flushes them in order.
        val first = example("server-candidate")
        val second = JsonObject(first + ("candidate" to JsonPrimitive("candidate:second")))
        h.socket.serverSends(first.toString())
        h.socket.serverSends(second.toString())
        h.settle()
        assertTrue(h.peer.remoteCandidates.isEmpty())
        h.socket.serverSends(example("server-answer").toString())
        h.settle()
        assertEquals(listOf(example("server-answer").getValue("sdp").jsonPrimitive.content), h.peer.remoteAnswers)
        assertEquals(
            listOf(first.getValue("candidate").jsonPrimitive.content, "candidate:second"),
            h.peer.remoteCandidates,
        )
        // Later candidates go straight through.
        h.socket.serverSends(first.toString())
        h.settle()
        assertEquals(3, h.peer.remoteCandidates.size)

        // 14. Connected.
        h.peer.emitState(PeerConnectionState.Connected)
        h.settle()
        assertEquals(StreamStatus.Connected, h.status)

        // 12-13. Control channel opens: URL first, then 12 requestFrame at +0, +125, ... +1375 ms.
        h.engine.openUrl("https://example.test/path?q=a%20b")
        h.settle()
        val opened = currentTime
        val sentBefore = h.socket.sent.size
        h.peer.emitChannel(DataChannels.control, true)
        h.settle()
        h.advance(1_375)
        val afterOpen = h.socket.sent.drop(sentBefore).filter { it.type != "keepAlive" }
        assertEquals(1 + SignallingTiming.REQUEST_FRAME_COUNT.toLong(), afterOpen.size.toLong())
        assertEquals(example("client-open-url"), afterOpen.first().json)
        val frames = afterOpen.drop(1)
        assertTrue(frames.all { it.json == example("client-request-frame") })
        assertEquals(
            (0 until SignallingTiming.REQUEST_FRAME_COUNT).map { opened + it * SignallingTiming.REQUEST_FRAME_INTERVAL_MS },
            frames.map { it.atMs },
        )

        // 15. keepAlive every 10 s from the start of the connection (not from the open).
        h.advance(30_000)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), h.socket.framesOf("keepAlive").map { it.atMs })
        assertTrue(h.socket.framesOf("keepAlive").all { it.json == example("client-keep-alive") })
        assertEquals(1, h.credentialCalls)
    }

    @Test
    fun openUrlBeforeTheControlChannelOpensIsSentOnceItOpens() = runTest {
        val h = Harness(this)
        h.engine.openUrl("https://example.test/x")
        h.negotiate()
        h.settle()
        assertTrue(h.socket.framesOf("openUrl").isEmpty())
        h.peer.emitState(PeerConnectionState.Connected)
        h.peer.emitChannel(DataChannels.control, true)
        h.settle()
        assertEquals("https://example.test/x", h.socket.framesOf("openUrl").single().json["url"]?.jsonPrimitive?.content)
    }

    // ---- handshake failures ----

    @Test
    fun webSocketThatDoesNotOpenInTimeFailsTheAttempt() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.advance(SignallingTiming.WS_OPEN_TIMEOUT_MS - 1)
        assertEquals(StreamStatus.Connecting, h.status)
        h.advance(1)
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun missingRtcConfigurationReplyFailsTheAttempt() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.advance(1_000)
        h.socket.serverOpens()
        h.settle()
        h.advance(SignallingTiming.RTC_CONFIG_TIMEOUT_MS - 1)
        assertEquals(StreamStatus.Connecting, h.status)
        h.advance(1)
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun invalidJsonWhileAwaitingTheConfigurationFailsTheAttempt() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.socket.serverOpens()
        h.settle()
        h.socket.serverSends("not json")
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun otherMessagesWhileAwaitingTheConfigurationAreIgnored() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.socket.serverOpens()
        h.settle()
        h.socket.serverSends(example("server-answer").toString())
        h.socket.serverSends(example("server-candidate").toString())
        h.settle()
        assertEquals(StreamStatus.Connecting, h.status)
        assertTrue(h.peers.peers.isEmpty())
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        assertEquals(1, h.peers.peers.size)
    }

    @Test
    fun invalidAndUnknownMessagesAfterTheHandlerIsAttachedAreDropped() = runTest {
        val h = Harness(this)
        h.connect()
        h.socket.serverSends("not json")
        h.socket.serverSends("""{"type":"somethingNew","x":1}""")
        h.socket.serverSends("""{"type":"answer"}""")
        h.settle()
        assertEquals(StreamStatus.Connected, h.status)
        assertEquals(1, h.signalling.sockets.size)
    }

    @Test
    fun negotiationThatThrowsFailsTheAttempt() = runTest {
        val h = Harness(this)
        h.peers.createError = IllegalStateException("no peer")
        h.engine.start()
        h.settle()
        h.socket.serverOpens()
        h.settle()
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    // ---- recovery ----

    @Test
    fun webSocketClosingAfterTheOpenRebuildsWholeConnection() = runTest {
        val h = Harness(this)
        h.connect()
        val firstSocket = h.socket
        val firstPeer = h.peer

        firstSocket.serverCloses()
        h.settle()
        // Even though the peer connection still says connected: the bridge ended the session.
        assertEquals(PeerConnectionState.Connected, firstPeer.connectionState)
        assertEquals(StreamStatus.Reconnecting(1), h.status)
        assertEquals(2, h.credentialCalls) // start + heal

        h.advance(ReconnectPolicy.delayMs(1, 0.5) - 1)
        assertEquals(1, h.signalling.sockets.size)
        h.advance(1)
        assertEquals(2, h.signalling.sockets.size)
        assertTrue(firstSocket.isClosed)
        assertTrue(firstPeer.isClosed)

        h.socket.serverOpens()
        h.settle()
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        assertEquals(2, h.peers.peers.size)
        h.peer.emitState(PeerConnectionState.Connected)
        h.settle()
        assertEquals(StreamStatus.Connected, h.status)
        assertEquals(2, h.credentialCalls) // no further heal
    }

    @Test
    fun webSocketClosingDuringNegotiationCannotBeCancelledByALaterConnected() = runTest {
        val h = Harness(this)
        h.negotiate()
        val doomed = h.socket
        doomed.serverCloses()
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)

        // The peer connection of the doomed attempt still gets there before the backoff ends.
        h.peer.emitState(PeerConnectionState.Connected)
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        assertEquals(2, h.signalling.sockets.size) // the rebuild still happened
        assertTrue(doomed.isClosed)
    }

    @Test
    fun webSocketClosingBeforeTheOpenFailsTheAttempt() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.socket.serverCloses()
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun peerConnectionFailedRebuildsWholeConnection() = runTest {
        val h = Harness(this)
        h.connect()
        val firstSocket = h.socket
        h.peer.emitState(PeerConnectionState.Failed)
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        assertEquals(2, h.signalling.sockets.size)
        assertTrue(firstSocket.isClosed)
        assertTrue(h.peers.peers.first().isClosed)
    }

    @Test
    fun peerConnectionClosedRebuildsWholeConnection() = runTest {
        val h = Harness(this)
        h.connect()
        h.peer.emitState(PeerConnectionState.Closed)
        h.settle()
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        assertEquals(2, h.signalling.sockets.size)
    }

    @Test
    fun peerConnectionDroppingAfterConnectedFails() = runTest {
        val h = Harness(this)
        h.connect()
        h.peer.emitState(PeerConnectionState.Disconnected)
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun peerConnectionStatesBeforeTheFirstConnectedAreNotFailures() = runTest {
        val h = Harness(this)
        h.negotiate()
        h.peer.emitState(PeerConnectionState.Connecting)
        h.peer.emitState(PeerConnectionState.Disconnected)
        h.settle()
        assertEquals(StreamStatus.Connecting, h.status)
    }

    @Test
    fun staleEventsOfAReplacedConnectionAreIgnored() = runTest {
        val h = Harness(this)
        h.connect()
        val oldSocket = h.socket
        val oldPeer = h.peer
        h.failAndWaitBackoff(1)
        assertEquals(2, h.signalling.sockets.size)
        // The old connection's callbacks fire late.
        oldPeer.emitState(PeerConnectionState.Failed)
        oldSocket.serverCloses()
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
        h.advance(1_000)
        // The second attempt is still running; the stale signals did not count as a new failure.
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun retryBudgetIsSpentThenRetryStartsAgain() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        for (attempt in 1..ReconnectPolicy.MAX_ATTEMPTS) {
            h.socket.serverCloses()
            h.settle()
            assertEquals(StreamStatus.Reconnecting(attempt), h.status)
            h.advance(ReconnectPolicy.delayMs(attempt, 0.5))
            assertEquals(attempt + 1, h.signalling.sockets.size)
        }
        h.socket.serverCloses()
        h.settle()
        assertEquals(StreamStatus.Unavailable, h.status)
        val before = h.signalling.sockets.size
        h.advance(120_000)
        assertEquals(before, h.signalling.sockets.size)

        h.engine.retry()
        h.settle()
        assertEquals(before + 1, h.signalling.sockets.size)
        assertEquals(StreamStatus.Reconnecting(0), h.status)
    }

    @Test
    fun hiddenHostPausesKeepAliveAndForegroundResumes() = runTest {
        val h = Harness(this)
        h.connect()
        h.engine.setHostVisible(false)
        h.settle()
        h.advance(20_000)
        assertTrue(h.socket.framesOf("keepAlive").isEmpty())
        h.engine.setHostVisible(true)
        h.settle()
        h.advance(10_000)
        assertEquals(1, h.socket.framesOf("keepAlive").size)
    }

    @Test
    fun wakeShortCircuitsBackoffAndNeverStartsOrRevivesClosedSession() = runTest {
        val h = Harness(this)
        h.engine.handleWake(); h.settle()
        assertTrue(h.signalling.sockets.isEmpty())
        h.connect()
        h.socket.serverCloses(); h.settle()
        val before = h.signalling.sockets.size
        h.engine.handleWake(); h.settle()
        assertEquals(before + 1, h.signalling.sockets.size)
        h.engine.close(); h.settle()
        h.engine.handleWake(); h.engine.setHostVisible(true); h.settle()
        h.advance(60_000)
        assertEquals(before + 1, h.signalling.sockets.size)
    }

    @Test
    fun networkWakeRevivesUnavailableSession() = runTest {
        val h = Harness(this)
        h.engine.start(); h.settle()
        for (attempt in 1..ReconnectPolicy.MAX_ATTEMPTS) {
            h.socket.serverCloses(); h.settle()
            h.advance(ReconnectPolicy.delayMs(attempt, 0.5))
        }
        h.socket.serverCloses(); h.settle()
        assertEquals(StreamStatus.Unavailable, h.status)
        val before = h.signalling.sockets.size
        h.engine.handleWake(); h.settle()
        assertEquals(before + 1, h.signalling.sockets.size)
    }

    // ---- credentials ----

    @Test
    fun rotatedTokenIsUsedByTheNextAttempt() = runTest {
        val h = Harness(this)
        h.connect()
        h.next = StreamCredentials("wss://stream.test/a", "token-b")
        h.failAndWaitBackoff(1)
        assertEquals(2, h.signalling.sockets.size)
        assertEquals("token-b", h.socket.credentials.token)
    }

    @Test
    fun rejectedTokenAsksTheProviderAgainAndRetriesWithTheNewOne() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        assertEquals(1, h.credentialCalls)
        h.next = StreamCredentials("wss://stream.test/a", "token-b")
        h.socket.serverRejects(401)
        h.settle()
        // One failure → one heal; the rejection must not ask twice.
        assertEquals(2, h.credentialCalls)
        assertEquals(StreamStatus.Reconnecting(1), h.status)
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        assertEquals("token-b", h.socket.credentials.token)

        // A second rejection in the same cycle: the controller does not heal again, the rejection does.
        h.next = StreamCredentials("wss://stream.test/a", "token-c")
        h.socket.serverRejects(401)
        h.settle()
        assertEquals(3, h.credentialCalls)
        h.advance(ReconnectPolicy.delayMs(2, 0.5))
        assertEquals("token-c", h.socket.credentials.token)
    }

    @Test
    fun otherHttpFailuresDoNotAskForCredentialsAgain() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.socket.serverRejects(404)
        h.settle()
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        h.socket.serverRejects(404)
        h.settle()
        assertEquals(2, h.credentialCalls) // start + one heal for the cycle
    }

    @Test
    fun credentialsThatAreNotReadyArePolled() = runTest {
        val h = Harness(this, first = null)
        h.engine.start()
        h.settle()
        h.advance(2 * CREDENTIALS_POLL_INTERVAL_MS)
        assertEquals(3, h.credentialCalls) // t = 0, 1 and 2 intervals
        assertTrue(h.signalling.sockets.isEmpty())
        assertEquals(StreamStatus.Connecting, h.status)
        h.next = StreamCredentials("wss://stream.test/a", "token-a")
        h.advance(CREDENTIALS_POLL_INTERVAL_MS)
        assertEquals(1, h.signalling.sockets.size)
        assertEquals(4, h.credentialCalls)
    }

    @Test
    fun credentialsArrivingLateRestartTheRunningAttemptButNotAWaitingOne() = runTest {
        val h = Harness(this)
        h.connect()
        // The heal of the first failure is slow: the backoff elapses first and the attempt starts
        // on the old credentials.
        h.gate = CompletableDeferred()
        h.next = StreamCredentials("wss://stream.test/a", "token-b")
        h.socket.serverCloses()
        h.settle()
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        assertEquals(2, h.signalling.sockets.size)
        assertEquals("token-a", h.socket.credentials.token)
        val running = h.socket

        // The fresh credentials arrive while that attempt negotiates: it restarts at once.
        h.gate!!.complete(Unit)
        h.settle()
        assertEquals(3, h.signalling.sockets.size)
        assertTrue(running.isClosed)
        assertEquals("token-b", h.socket.credentials.token)
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun credentialsArrivingDuringTheBackoffWaitAreHeldForTheScheduledAttempt() = runTest {
        val h = Harness(this)
        h.connect()
        h.gate = CompletableDeferred()
        h.next = StreamCredentials("wss://stream.test/a", "token-b")
        h.socket.serverCloses()
        h.settle()
        h.gate!!.complete(Unit)
        h.advance(ReconnectPolicy.delayMs(1, 0.5) - 1)
        assertEquals(1, h.signalling.sockets.size) // still waiting
        h.advance(1)
        assertEquals(2, h.signalling.sockets.size)
        assertEquals("token-b", h.socket.credentials.token)
    }

    // ---- refresh ----

    @Test
    fun rtcConfigurationRefreshRestartsIceWithoutInterruptingTheStream() = runTest {
        val h = Harness(this)
        h.connect()
        val refresh = example("server-rtc-configuration")
        h.socket.serverSends(refresh.toString())
        h.settle()
        assertEquals(1, h.peer.iceServerUpdates.size)
        assertEquals(2, h.peer.iceServerUpdates.single().size)
        assertEquals(listOf(false, true), h.peer.offers)
        val offers = h.socket.framesOf("offer")
        assertEquals(2, offers.size)
        assertEquals("offer-sdp-2", offers.last().json.getValue("sdp").jsonPrimitive.content)

        // Candidates after the restart offer wait for its answer.
        h.socket.serverSends(example("server-candidate").toString())
        h.settle()
        assertTrue(h.peer.remoteCandidates.isEmpty())
        h.socket.serverSends(example("server-answer").toString())
        h.settle()
        assertEquals(2, h.peer.remoteAnswers.size)
        assertEquals(1, h.peer.remoteCandidates.size)

        // Dips inside the 15 s grace window are suppressed, "failed" is not.
        h.peer.emitState(PeerConnectionState.Connecting)
        h.advance(SignallingTiming.ICE_RESTART_GRACE_MS - 1_000)
        assertEquals(StreamStatus.Connected, h.status)
        h.peer.emitState(PeerConnectionState.Connected)
        h.settle()
        assertEquals(StreamStatus.Connected, h.status)
        assertEquals(1, h.signalling.sockets.size)
    }

    @Test
    fun aDipThatOutlivesTheGraceWindowIsReported() = runTest {
        val h = Harness(this)
        h.connect()
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        h.peer.emitState(PeerConnectionState.Disconnected)
        h.settle()
        h.advance(SignallingTiming.ICE_RESTART_GRACE_MS - 1)
        assertEquals(StreamStatus.Connected, h.status)
        h.advance(1)
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun failedPassesThroughTheGraceWindow() = runTest {
        val h = Harness(this)
        h.connect()
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        h.peer.emitState(PeerConnectionState.Failed)
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun refreshWithoutIceServersIsIgnored() = runTest {
        val h = Harness(this)
        h.connect()
        h.socket.serverSends(example("server-rtc-configuration", 1).toString())
        h.settle()
        assertTrue(h.peer.iceServerUpdates.isEmpty())
        assertEquals(listOf(false), h.peer.offers)
    }

    @Test
    fun refreshWithAnEmptyIceServerListIsApplied() = runTest {
        val h = Harness(this)
        h.connect()
        h.socket.serverSends("""{"type":"rtcConfiguration","rtcConfiguration":{"iceServers":[]}}""")
        h.settle()
        assertEquals(listOf(emptyList()), h.peer.iceServerUpdates.map { it.map { server -> server.urls } })
        assertEquals(listOf(false, true), h.peer.offers)
    }

    @Test
    fun refreshWhoseConfigurationIsRejectedSendsNoOffer() = runTest {
        val h = Harness(this)
        h.connect()
        h.peer.iceServersAccepted = false
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        assertEquals(listOf(false), h.peer.offers)
        assertEquals(1, h.socket.framesOf("offer").size)
        // No grace window was opened.
        h.peer.emitState(PeerConnectionState.Disconnected)
        h.settle()
        assertEquals(StreamStatus.Reconnecting(1), h.status)
    }

    @Test
    fun aStaleAnswerIsIgnored() = runTest {
        val h = Harness(this)
        h.connect()
        h.peer.answerError = IllegalStateException("wrong state")
        h.socket.serverSends(example("server-answer").toString())
        h.settle()
        assertEquals(StreamStatus.Connected, h.status)
    }

    // ---- screenshots ----

    @Test
    fun screenshotReturnsTheDecodedImage() = runTest {
        val h = Harness(this)
        h.connect()
        val result = async { h.engine.screenshot() }
        h.settle()
        val request = h.socket.framesOf("screenshot").single().json
        val id = request.getValue("id").jsonPrimitive.content
        assertEquals(setOf("type", "id"), request.keys)
        h.socket.serverSends(
            JsonObject(example("server-screenshot") + ("id" to JsonPrimitive(id))).toString(),
        )
        h.settle()
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            result.await(),
        )
    }

    @Test
    fun screenshotErrorFailsTheCall() = runTest {
        val h = Harness(this)
        h.connect()
        val result = attempt { h.engine.screenshot() }
        h.settle()
        val id = h.socket.framesOf("screenshot").single().json.getValue("id").jsonPrimitive.content
        h.socket.serverSends(JsonObject(example("server-screenshot-error") + ("id" to JsonPrimitive(id))).toString())
        h.settle()
        val failure = assertFailsWith<ScreenshotFailedException> { result.await().getOrThrow() }
        assertEquals("capture failed", failure.message)
    }

    @Test
    fun screenshotTimesOutAfterTheTimeout() = runTest {
        val h = Harness(this)
        h.connect()
        val result = attempt { h.engine.screenshot(30.seconds) }
        h.settle()
        h.advance(29_999)
        assertFalse(result.isCompleted)
        h.advance(1)
        assertFailsWith<ScreenshotTimeoutException> { result.await().getOrThrow() }
        // A late reply for the forgotten id changes nothing.
        val id = h.socket.framesOf("screenshot").single().json.getValue("id").jsonPrimitive.content
        h.socket.serverSends(JsonObject(example("server-screenshot") + ("id" to JsonPrimitive(id))).toString())
        h.settle()
        assertEquals(StreamStatus.Connected, h.status)
    }

    @Test
    fun defaultScreenshotTimeoutIsThirtySeconds() = runTest {
        val h = Harness(this)
        h.connect()
        val result = attempt { h.engine.screenshot() }
        h.settle()
        h.advance(SignallingTiming.SCREENSHOT_TIMEOUT_MS - 1)
        assertFalse(result.isCompleted)
        h.advance(1)
        assertFailsWith<ScreenshotTimeoutException> { result.await().getOrThrow() }
    }

    @Test
    fun screenshotWithoutAnOpenSocketFailsAtOnce() = runTest {
        val h = Harness(this)
        assertFailsWith<StreamNotConnectedException> { h.engine.screenshot() }
        h.engine.start()
        h.settle()
        assertFailsWith<StreamNotConnectedException> { h.engine.screenshot() } // socket not open yet
    }

    @Test
    fun pendingScreenshotFailsWhenTheConnectionIsReplaced() = runTest {
        val h = Harness(this)
        h.connect()
        val result = attempt { h.engine.screenshot() }
        h.settle()
        h.failAndWaitBackoff(1)
        assertFailsWith<StreamDisconnectedException> { result.await().getOrThrow() }
    }

    // ---- sending ----

    @Test
    fun sendsAreDroppedWhileTheChannelsAreNotOpen() = runTest {
        val h = Harness(this)
        assertFalse(h.engine.sendControl(byteArrayOf(1)))
        h.negotiate()
        assertFalse(h.engine.sendControl(byteArrayOf(1)))
        assertFalse(h.engine.sendHover(byteArrayOf(2)))

        h.peer.emitChannel(DataChannels.control, true)
        h.settle()
        assertTrue(h.engine.sendControl(byteArrayOf(3)))
        assertFalse(h.engine.sendHover(byteArrayOf(4)))

        h.peer.emitChannel(DataChannels.control, false)
        h.peer.emitChannel(DataChannels.hover, true)
        h.settle()
        assertFalse(h.engine.sendControl(byteArrayOf(5)))
        assertTrue(h.engine.sendHover(byteArrayOf(6)))

        assertEquals(listOf(3), h.peer.controlSent.map { it.single().toInt() })
        assertEquals(listOf(6), h.peer.hoverSent.map { it.single().toInt() })
    }

    @Test
    fun hoverNeverFallsBackToControl() = runTest {
        val h = Harness(this)
        h.negotiate()
        h.peer.emitChannel(DataChannels.control, true)
        h.settle()
        assertFalse(h.engine.sendHover(byteArrayOf(9)))
        assertTrue(h.peer.controlSent.isEmpty())
    }

    @Test
    fun sendsAreDroppedAfterTheConnectionIsReplacedUntilTheNewOneOpens() = runTest {
        val h = Harness(this)
        h.connect()
        assertTrue(h.engine.sendControl(byteArrayOf(1)))
        h.peer.emitState(PeerConnectionState.Failed)
        h.settle()
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        assertFalse(h.engine.sendControl(byteArrayOf(2)))
    }

    // ---- lifecycle ----

    @Test
    fun closeReleasesEverythingAndStopsAllTimers() = runTest {
        val h = Harness(this)
        h.connect()
        val socket = h.socket
        val peer = h.peer
        val sentBefore = socket.sent.size
        val pending = attempt { h.engine.screenshot() }
        h.settle()

        h.engine.close()
        assertFalse(h.engine.sendControl(byteArrayOf(1))) // at once, before the teardown ran
        h.settle()
        assertTrue(socket.isClosed)
        assertTrue(peer.isClosed)
        assertFailsWith<StreamDisconnectedException> { pending.await().getOrThrow() }

        h.advance(120_000)
        assertEquals(sentBefore + 1, socket.sent.size) // only the screenshot request, no keepAlive
        assertEquals(1, h.signalling.sockets.size)
        h.engine.start()
        h.settle()
        assertEquals(1, h.signalling.sockets.size)
        h.engine.close() // idempotent
    }

    @Test
    fun cancellingTheHostScopeReleasesEverythingLikeClose() = runTest {
        val h = Harness(this)
        h.connect()
        val socket = h.socket
        val peer = h.peer
        val sentBefore = socket.sent.size
        assertTrue(h.engine.sendControl(byteArrayOf(1)))

        h.host.cancel() // no close()
        assertFalse(h.engine.sendControl(byteArrayOf(2)))
        assertFalse(h.engine.sendHover(byteArrayOf(3)))
        h.settle()
        assertTrue(socket.isClosed)
        assertTrue(peer.isClosed)

        h.advance(120_000)
        assertEquals(sentBefore, socket.sent.size) // no keepAlive, no frame requests
        assertEquals(1, h.signalling.sockets.size) // no reconnect
    }

    @Test
    fun cancellingTheHostScopeStopsPendingBackoffAndCredentialPolling() = runTest {
        val h = Harness(this)
        h.connect()
        h.socket.serverCloses()
        h.settle()
        h.host.cancel()
        h.advance(120_000)
        assertEquals(1, h.signalling.sockets.size)

        val idle = Harness(this, first = null)
        idle.engine.start()
        idle.settle()
        idle.host.cancel()
        val calls = idle.credentialCalls
        idle.advance(10 * CREDENTIALS_POLL_INTERVAL_MS)
        assertEquals(calls, idle.credentialCalls)
    }

    @Test
    fun closeWhileThePeerConnectionIsBeingCreatedClosesIt() = runTest {
        val h = Harness(this)
        h.engine.start()
        h.settle()
        h.socket.serverOpens()
        h.settle()
        // close() from another thread arrives while the factory is busy creating the peer connection.
        h.peers.onCreate = { h.engine.close() }
        h.socket.serverSends(example("server-rtc-configuration").toString())
        h.settle()
        assertEquals(1, h.peers.peers.size)
        assertTrue(h.peer.isClosed)
        assertTrue(h.socket.isClosed)
        assertTrue(h.socket.framesOf("offer").isEmpty())
    }

    @Test
    fun closeWhileTheSocketIsBeingOpenedClosesIt() = runTest {
        val h = Harness(this)
        h.signalling.onOpen = { h.engine.close() }
        h.engine.start()
        h.settle()
        assertEquals(1, h.signalling.sockets.size)
        assertTrue(h.socket.isClosed)
        h.advance(120_000)
        assertEquals(1, h.signalling.sockets.size)
    }

    @Test
    fun closeBeforeStartDoesNothing() = runTest {
        val h = Harness(this)
        h.engine.close()
        h.engine.start()
        h.settle()
        assertTrue(h.signalling.sockets.isEmpty())
        assertEquals(0, h.credentialCalls)
    }

    @Test
    fun credentialsAreNeverLogged() = runTest {
        val h = Harness(this)
        h.connect()
        h.failAndWaitBackoff(1)
        assertTrue(h.log.isNotEmpty())
        assertTrue(h.log.none { "token-a" in it || "stream.test" in it || "offer-sdp" in it })
        assertFalse("token-a" in StreamCredentials("wss://stream.test/a", "token-a").toString())
    }

    @Test
    fun aThrowingCredentialsProviderIsTreatedAsNotReady() = runTest {
        val h = Harness(this)
        h.nextError = IllegalStateException("offline")
        h.engine.start()
        h.settle()
        assertTrue(h.signalling.sockets.isEmpty())
        h.advance(CREDENTIALS_POLL_INTERVAL_MS)
        assertEquals(1, h.signalling.sockets.size)
    }

    @Test
    fun decodeDataUriHandlesBase64Payloads() {
        assertContentEquals(byteArrayOf(1, 2, 3), decodeDataUri("data:image/png;base64,AQID"))
        assertFailsWith<IllegalArgumentException> { decodeDataUri("https://example.test") }
    }
}
