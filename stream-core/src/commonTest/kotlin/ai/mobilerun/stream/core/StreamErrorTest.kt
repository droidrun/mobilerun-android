// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalCoroutinesApi::class)

package ai.mobilerun.stream.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val URL = "wss://stream.test/a"

/** Collects every error of [h] from now on; the collector subscribes before any emission. */
private fun TestScope.collectErrors(h: Harness): List<StreamError> {
    val errors = mutableListOf<StreamError>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.errors.collect { errors += it } }
    return errors
}

private fun providerHarness(scope: TestScope, provider: StreamTokenProvider) =
    Harness(scope, StreamCredentials(URL, tokenProvider = provider))

class StreamErrorTest {
    @Test
    fun providerIsCalledForEveryAttemptAndNeverReusesAKey() = runTest {
        var minted = 0
        val h = providerHarness(this) { "key-${++minted}" }
        h.connect()
        h.failAndWaitBackoff(1)
        h.socket.serverCloses()
        h.settle()
        h.advance(ReconnectPolicy.delayMs(2, 0.5))
        h.engine.handleWake()
        h.settle()
        val tokens = h.signalling.sockets.map { it.credentials.token }
        assertEquals(3, tokens.size)
        assertEquals(tokens.size, minted)
        assertEquals(tokens.size, tokens.toSet().size, "a key must not be used for two sockets: $tokens")
    }

    @Test
    fun providerThrowReportsCredentialsUnavailable() = runTest {
        val h = providerHarness(this) { throw IllegalStateException("backend down") }
        val errors = collectErrors(h)
        h.engine.start()
        h.settle()
        assertEquals(1, errors.size)
        assertEquals(StreamErrorReason.CredentialsUnavailable, errors[0].reason)
        assertEquals(1, errors[0].attempt)
        assertFalse(errors[0].final)
        assertTrue(h.signalling.sockets.isEmpty())
    }

    @Test
    fun providerTimeoutReportsCredentialsUnavailable() = runTest {
        val h = providerHarness(this) { kotlinx.coroutines.awaitCancellation() }
        val errors = collectErrors(h)
        h.engine.start()
        h.settle()
        assertTrue(errors.isEmpty())
        h.advance(ReconnectPolicy.TOKEN_PROVIDER_TIMEOUT_MS)
        assertEquals(listOf(StreamErrorReason.CredentialsUnavailable), errors.map { it.reason })
    }

    @Test
    fun closeBeforeOpenReportsConnectionRefused() = runTest {
        val h = Harness(this)
        val errors = collectErrors(h)
        h.engine.start()
        h.settle()
        h.socket.serverCloses()
        h.settle()
        assertEquals(listOf(StreamErrorReason.ConnectionRefused), errors.map { it.reason })
    }

    @Test
    fun upgradeRejectionReportsTokenRejectedOnlyFor401() = runTest {
        val h = Harness(this)
        val errors = collectErrors(h)
        h.engine.start()
        h.settle()
        h.socket.serverRejects(401)
        h.settle()
        h.advance(ReconnectPolicy.delayMs(1, 0.5))
        h.socket.serverRejects(403)
        h.settle()
        h.advance(ReconnectPolicy.delayMs(2, 0.5))
        h.socket.serverRejects(null)
        h.settle()
        assertEquals(
            listOf(StreamErrorReason.TokenRejected, StreamErrorReason.ConnectionRefused, StreamErrorReason.ConnectionRefused),
            errors.map { it.reason },
        )
        assertEquals(listOf(1, 2, 3), errors.map { it.attempt })
    }

    @Test
    fun dropAfterOpenReportsDisconnected() = runTest {
        val h = Harness(this)
        val errors = collectErrors(h)
        h.connect()
        assertTrue(errors.isEmpty())
        h.socket.serverCloses()
        h.settle()
        assertEquals(listOf(StreamErrorReason.Disconnected), errors.map { it.reason })
    }

    @Test
    fun peerFailureAfterOpenReportsDisconnected() = runTest {
        val h = Harness(this)
        val errors = collectErrors(h)
        h.connect()
        h.peer.emitState(PeerConnectionState.Failed)
        h.settle()
        assertEquals(listOf(StreamErrorReason.Disconnected), errors.map { it.reason })
    }

    @Test
    fun finalIsSetOnlyWhenRetriesStopAndAFreshCycleReportsNothing() = runTest {
        val h = Harness(this)
        val errors = collectErrors(h)
        h.exhaustRetries()
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS + 1, errors.size)
        assertTrue(errors.dropLast(1).none { it.final })
        assertTrue(errors.last().final)
        assertTrue(errors.all { it.reason == StreamErrorReason.ConnectionRefused })

        val before = errors.size
        h.engine.retry()
        h.settle()
        assertEquals(before, errors.size, "starting a new cycle is not a failure")
    }

    @Test
    fun errorsNeverContainTheKeyOrTheUrl() = runTest {
        val h = providerHarness(this) { "dr_st_secretvalue" }
        val errors = collectErrors(h)
        h.engine.start()
        h.settle()
        h.socket.serverRejects(401)
        h.settle()
        val text = errors.joinToString { "${it.message} $it" }
        assertTrue(errors.isNotEmpty())
        assertFalse(text.contains("dr_st_secretvalue") || text.contains("stream.test"))
    }

    @Test
    fun staticTokenWarnsOncePerSessionAndProviderNever() = runTest {
        val legacy = Harness(this, StreamCredentials(URL, "static-secret"))
        legacy.engine.start()
        legacy.settle()
        legacy.failAndWaitBackoff(1)
        assertEquals(2, legacy.signalling.sockets.size)
        assertEquals(1, legacy.warnings.size)
        assertTrue(legacy.warnings[0].contains("deprecated"))
        assertFalse(legacy.warnings[0].contains("static-secret"))

        val modern = providerHarness(this) { "key" }
        modern.engine.start()
        modern.settle()
        assertTrue(modern.warnings.isEmpty())

        val noToken = Harness(this, StreamCredentials(URL))
        noToken.engine.start()
        noToken.settle()
        assertTrue(noToken.warnings.isEmpty())
    }
}
