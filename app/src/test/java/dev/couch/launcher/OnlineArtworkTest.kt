package dev.couch.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class OnlineArtworkTest {
    @Test fun loadsHttpsArtworkAcrossARelativeRedirect() {
        val urls = mutableListOf<String>()
        val connections = mutableListOf<Response>()
        val bytes = byteArrayOf(1, 2, 3)
        val result = OnlineArtwork.read("https://images.example/start") { url ->
            urls.add(url.toString())
            val response = if (url.path == "/start") Response(url, 302, location = "/poster")
                else Response(url, 200, body = bytes.inputStream())
            connections.add(response)
            response
        }
        assertArrayEquals(bytes, result)
        assertEquals(listOf("https://images.example/start", "https://images.example/poster"), urls)
        assertTrue(connections.all { it.disconnected && !it.instanceFollowRedirects && !it.useCaches })
    }

    @Test fun rejectsCleartextAndCredentialsBeforeConnecting() {
        var requests = 0
        val open: (URL) -> HttpURLConnection = { url ->
            requests++
            Response(url, 200, body = byteArrayOf(1).inputStream())
        }
        assertNull(OnlineArtwork.read("http://images.example/poster", open))
        assertNull(OnlineArtwork.read("https://name:password@images.example/poster", open))
        assertEquals(0, requests)
        assertNull(OnlineArtwork.read("https://images.example/start") { url ->
            requests++
            Response(url, 302, location = "http://images.example/poster")
        })
        assertEquals(1, requests)
    }

    @Test fun rejectsOversizedResponsesWithOrWithoutContentLength() {
        val bytes = ByteArray(4 * 1024 * 1024 + 1)
        assertNull(OnlineArtwork.read("https://images.example/poster") { url ->
            Response(url, 200, length = bytes.size.toLong())
        })
        assertNull(OnlineArtwork.read("https://images.example/poster") { url ->
            Response(url, 200, body = bytes.inputStream())
        })
    }

    private class Response(url: URL, private val status: Int, private val location: String? = null,
                           private val length: Long = -1, private val body: InputStream? = null) : HttpURLConnection(url) {
        var disconnected = false
            private set
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = location.takeIf { name == "Location" }
        override fun getInputStream(): InputStream = body ?: error("No body expected")
    }
}
