package cn.mudlife.app.api

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTransportTest {
    @Test(timeout = 8000) fun temporaryServiceFailureDoesNotReplayValvePost() {
        val calls = AtomicInteger()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                try {
                    while (!server.isClosed) server.accept().use { peer ->
                        val reader = peer.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume the request headers. */ }
                        calls.incrementAndGet()
                        peer.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n" +
                            "Retry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    }
                } catch (e: SocketException) { if (!server.isClosed) throw e }
            }
            try {
                val body = SingleSendBody(FormBody.Builder().add("sn", "fixture").build())
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").post(body).build()
                OkHttpClient.Builder().retryOnConnectionFailure(false).build().newCall(request).execute().use {
                    assertEquals(503, it.code)
                }
                assertEquals(1, calls.get())
            } finally {
                server.close()
                worker.join(1000)
            }
        }
    }
}
