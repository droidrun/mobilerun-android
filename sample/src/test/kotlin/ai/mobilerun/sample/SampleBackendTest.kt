// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.sample

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SampleBackendTest {
    @Test fun discoveryBindsDeviceAndMintsOnlyWhenCallbackRuns() = runBlocking {
        val requests = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/devices/device-a") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI} " +
                exchange.requestHeaders.getFirst("Cache-Control")
            val response = """{"id":"device-a","state":"ready","streamUrl":"wss://stream.test/a"}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.createContext("/api/v1/devices/device-a/stream-key") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI} " +
                exchange.requestHeaders.getFirst("Cache-Control")
            val response = """{"key":"key-a","keyId":"id-a","expiresAt":"later"}"""
            exchange.sendResponseHeaders(201, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val config = parseLaunchConfig(
                """{"helperBaseUrl":"http://127.0.0.1:${server.address.port}","deviceId":"device-a"}""",
            )
            assertNull(config.credentials)
            val target = config.backend!!.credentials()!!
            assertEquals("wss://stream.test/a", target.url)
            assertEquals("", target.token)
            assertEquals(listOf("GET /api/v1/devices/device-a no-store"), requests.toList())
            assertEquals("key-a", target.tokenProvider!!())
            assertEquals("key-a", target.tokenProvider!!())
            assertEquals(listOf(
                "GET /api/v1/devices/device-a no-store",
                "POST /api/v1/devices/device-a/stream-key no-store",
                "POST /api/v1/devices/device-a/stream-key no-store",
            ), requests.toList())
            assertEquals("", target.token)
            assertTrue(target.toString().contains("<redacted>"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun refusalIsGenericAndDoesNotFollowRedirects() = runBlocking {
        val requests = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            exchange.responseHeaders.add("Location", "/private-key")
            val response = "private-key"
            exchange.sendResponseHeaders(302, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val backend = SampleBackend("http://127.0.0.1:${server.address.port}", "device-a")
            val error = assertFailsWith<IllegalStateException> { backend.mintStreamKey() }
            assertEquals("Sample backend request failed", error.message)
            assertEquals(setOf("Sample backend request failed"),
                generateSequence(error as Throwable) { it.cause }.map { it.message }.toSet())
            assertEquals(listOf("/api/v1/devices/device-a/stream-key"), requests.toList())
        } finally {
            server.stop(0)
        }
    }
}
