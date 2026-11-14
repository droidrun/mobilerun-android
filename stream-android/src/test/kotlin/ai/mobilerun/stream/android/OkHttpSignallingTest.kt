// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.StreamCredentials
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkHttpSignallingTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val factory = OkHttpSignallingTransportFactory(client)
    private val serverSide = LinkedBlockingQueue<String>()
    private var serverSocket: WebSocket? = null
    private val events = LinkedBlockingQueue<String>()
    private val transports = mutableListOf<OkHttpSignallingTransport>()

    private val listener = object : SignallingListener {
        override fun onOpen() { events += "open" }

        override fun onMessage(text: String) { events += "message:$text" }

        override fun onClosed() { events += "closed" }

        override fun onFailure(httpStatus: Int?) { events += "failure:$httpStatus" }
    }

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { serverSocket?.close(1000, null) }
        transports.forEach { it.close() }
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    private fun upgrade(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket = webSocket
                    onOpen(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) { serverSide += text }
            }),
        )
    }

    private fun credentials(token: String = "tok", path: String = "/stream") =
        StreamCredentials("ws://${server.hostName}:${server.port}$path", token)

    private fun open(c: StreamCredentials) = factory.open(c, listener).also { transports += it }

    private fun next(queue: LinkedBlockingQueue<String>): String? = queue.poll(5, TimeUnit.SECONDS)

    @Test
    fun tokenIsSentAsQueryParameter() {
        upgrade()
        open(credentials(path = "/stream?device=d1&token=old"))
        assertEquals("open", next(events))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/stream", request.requestUrl!!.encodedPath)
        assertEquals(listOf("tok"), request.requestUrl!!.queryParameterValues("token"))
        assertEquals("d1", request.requestUrl!!.queryParameter("device"))
    }

    @Test
    fun emptyTokenLeavesTheUrlAlone() {
        assertEquals("http://localhost/s?a=1", socketUrl(StreamCredentials("ws://localhost/s?a=1", "")).toString())
        assertEquals(
            "https://stream.example.test/s?token=abc%2B%3D",
            socketUrl(StreamCredentials("WSS://stream.example.test/s", "abc+=")).toString(),
        )
    }

    @Test
    fun framesFlowBothWaysAndAreOrdered() {
        upgrade { it.send("hello") }
        val transport = open(credentials())
        assertEquals("open", next(events))
        assertEquals("message:hello", next(events))
        assertTrue(transport.send("""{"type":"keepAlive"}"""))
        assertEquals("""{"type":"keepAlive"}""", next(serverSide))
    }

    @Test
    fun aRejectedTokenIsReportedWithItsStatus() {
        server.enqueue(MockResponse().setResponseCode(401))
        val transport = open(credentials())
        assertEquals("failure:401", next(events))
        assertFalse(transport.send("x"))
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aClosingServerIsReportedOnceAndSendsStop() {
        upgrade()
        val transport = open(credentials())
        assertEquals("open", next(events))
        serverSocket!!.close(1001, "going away")
        assertEquals("closed", next(events))
        assertFalse(transport.send("x"))
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun anAbruptDropIsReportedWithoutAStatus() {
        upgrade()
        val transport = open(credentials())
        assertEquals("open", next(events))
        transport.socket.cancel()
        assertEquals("failure:null", next(events))
    }

    @Test
    fun closeSilencesEveryFurtherEvent() {
        upgrade { it.send("late") }
        val transport = open(credentials())
        assertEquals("open", next(events))
        transport.close()
        assertFalse(transport.send("x"))
        // Whatever the server still sends or reports after our close is not delivered.
        repeat(3) { runCatching { serverSocket?.send("more") } }
        val remaining = generateSequence { events.poll(300, TimeUnit.MILLISECONDS) }.toList()
        assertTrue(remaining.none { it == "closed" || it.startsWith("failure") }, "events after close: $remaining")
    }

    @Test
    fun closeReleasesSocketWithoutWaitingForServerAck() {
        val terminated = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket = webSocket }
            // Deliberately leave a graceful CLOSE unanswered.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = Unit
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                terminated.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                terminated.countDown()
            }
        }))
        val transport = open(credentials())
        assertEquals("open", next(events))
        transport.close()
        assertTrue(terminated.await(2, TimeUnit.SECONDS), "close must release an unacknowledged socket promptly")
    }

    @Test
    fun anInvalidUrlFailsToOpen() {
        val result = runCatching { factory.open(StreamCredentials("not a url", "t"), listener) }
        assertTrue(result.isFailure)
    }
}
