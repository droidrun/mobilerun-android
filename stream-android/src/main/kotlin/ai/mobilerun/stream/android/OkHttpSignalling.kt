// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.android

import ai.mobilerun.stream.core.SignallingListener
import ai.mobilerun.stream.core.SignallingTransport
import ai.mobilerun.stream.core.SignallingTransportFactory
import ai.mobilerun.stream.core.StreamCredentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/** The WebSocket URL with the token as the `token` query parameter (replacing an existing one). */
internal fun socketUrl(credentials: StreamCredentials): HttpUrl {
    // OkHttp only knows http(s); it upgrades to ws(s) itself.
    val httpUrl = credentials.url
        .replaceFirst(Regex("^wss:", RegexOption.IGNORE_CASE), "https:")
        .replaceFirst(Regex("^ws:", RegexOption.IGNORE_CASE), "http:")
        .toHttpUrl()
    if (credentials.token.isEmpty()) return httpUrl
    return httpUrl.newBuilder().setQueryParameter("token", credentials.token).build()
}

internal class OkHttpSignallingTransportFactory(private val client: OkHttpClient) : SignallingTransportFactory {
    override fun open(credentials: StreamCredentials, listener: SignallingListener): OkHttpSignallingTransport {
        val transport = OkHttpSignallingTransport(listener)
        transport.socket = client.newWebSocket(Request.Builder().url(socketUrl(credentials)).build(), transport)
        return transport
    }
}

/** A signalling WebSocket on OkHttp. After [close] or the first end event it reports nothing more. */
internal class OkHttpSignallingTransport(listener: SignallingListener) :
    WebSocketListener(), SignallingTransport {
    @Volatile lateinit var socket: WebSocket

    @Volatile private var listener: SignallingListener? = listener
    private val finished = AtomicBoolean(false)

    override fun send(text: String): Boolean = !finished.get() && socket.send(text)

    override fun close() {
        finished.set(true)
        // Preserve the protocol CLOSE frame without retaining the session during the handshake.
        listener = null
        socket.close(1000, null)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        if (!finished.get()) listener?.onOpen()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (!finished.get()) listener?.onMessage(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
        end { it.onClosed() }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end { it.onClosed() }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
        end { it.onFailure(response?.code) }

    private fun end(report: (SignallingListener) -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        val target = listener
        listener = null
        target?.let(report)
    }
}
