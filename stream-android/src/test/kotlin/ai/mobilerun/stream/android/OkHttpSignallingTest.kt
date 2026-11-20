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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkHttpSignallingTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val factory = OkHttpSignallingTransportFactory(client)
    private val serverSide = LinkedBlockingQueue<String>()
    private var serverSocket: WebSocket? = null
    private var upgrading = false
    private val serverOpened = CountDownLatch(1)
    private val serverFinished = CountDownLatch(1)
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
        if (upgrading) assertTrue(serverOpened.await(5, TimeUnit.SECONDS), "server did not open")
        serverSocket?.close(1000, null)
        transports.forEach { it.close() }
        if (upgrading) assertTrue(serverFinished.await(5, TimeUnit.SECONDS), "server did not finish")
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    private fun upgrade(onOpen: (WebSocket) -> Unit = {}) {
        upgrading = true
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    onOpen(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) { serverSide += text }
            }),
        )
    }

    private open inner class ServerListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket = webSocket
            serverOpened.countDown()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            serverFinished.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            serverFinished.countDown()
        }
    }

    private fun credentials(token: String = "tok", path: String = "/stream") =
        StreamCredentials("ws://${server.hostName}:${server.port}$path", token)

    private fun open(c: StreamCredentials, target: SignallingListener = listener): OkHttpSignallingTransport =
        (factory.open(c, target) as OkHttpSignallingTransport).also { transports += it }

    private fun next(queue: LinkedBlockingQueue<String>): String? = queue.poll(5, TimeUnit.SECONDS)

    @Test
    fun tokenIsSentAsQueryParameter() {
        upgrade()
        open(credentials(token = " key +&= ", path = "/stream?device=d1&token=old&token=older"))
        assertEquals("open", next(events))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/stream", request.requestUrl!!.encodedPath)
        assertEquals(listOf(" key +&= "), request.requestUrl!!.queryParameterValues("token"))
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
        val opened = LinkedBlockingQueue<WebSocket>()
        upgrade { opened += it }
        val transport = open(credentials())
        assertEquals("open", next(events))
        assertNotNull(opened.poll(5, TimeUnit.SECONDS), "server socket did not open").close(1001, "going away")
        assertEquals("closed", next(events))
        assertFalse(transport.send("x"))
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun goingAwayCodeIsPropagatedExactlyOnce() {
        val opened = LinkedBlockingQueue<WebSocket>()
        upgrade { opened += it }
        val target = object : SignallingListener by listener {
            override fun onClosed(code: Int) { events += "closed:$code" }
        }
        val transport = open(credentials(), target)
        assertEquals("open", next(events))
        assertNotNull(opened.poll(5, TimeUnit.SECONDS)).close(1001, "going away")
        assertEquals("closed:1001", next(events))
        assertTrue(serverFinished.await(5, TimeUnit.SECONDS))
        assertFalse(transport.send("x"))
        assertTrue(events.isEmpty())
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
    fun closeSendsNormalFrameAndReleasesListenerWithoutServerAck() {
        val closing = LinkedBlockingQueue<Int>()
        upgrading = true
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ServerListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { super.onOpen(webSocket, response) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closing += code }
        }))
        val collected = java.lang.ref.ReferenceQueue<SignallingListener>()
        fun openListener(): java.lang.ref.WeakReference<SignallingListener> {
            val target = object : SignallingListener {
                override fun onOpen() { events += "open" }
                override fun onMessage(text: String) = Unit
                override fun onClosed() = Unit
                override fun onFailure(httpStatus: Int?) = Unit
            }
            val reference = java.lang.ref.WeakReference<SignallingListener>(target, collected)
            val transport = open(credentials(), target)
            assertEquals("open", next(events))
            transport.close()
            return reference
        }
        val reference = openListener()
        assertEquals(1000, closing.poll(2, TimeUnit.SECONDS))
        repeat(20) {
            System.gc()
            if (collected.remove(100) != null) return@repeat
        }
        assertNull(reference.get(), "unacknowledged close must not retain the listener/session")
    }

    @Test
    fun anInvalidUrlFailsToOpen() {
        val result = runCatching { factory.open(StreamCredentials("not a url", "t"), listener) }
        assertTrue(result.isFailure)
    }

    @Test
    fun closeWithoutServerCodeUsesNormalResponseAndReportsClosed() {
        val responses = LinkedBlockingQueue<Int>()
        upgrading = true
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ServerListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { super.onOpen(webSocket, response) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                responses += code
                webSocket.close(1000, null)
            }
        }))
        val transport = open(credentials())
        assertEquals("open", next(events))
        transport.onClosing(transport.socket, 1005, "")
        assertEquals("closed", next(events))
        assertEquals(1000, responses.poll(5, TimeUnit.SECONDS))
        assertFalse(transport.send("after close"))
        assertTrue(serverFinished.await(5, TimeUnit.SECONDS), "close handshake did not finish")
    }

}
