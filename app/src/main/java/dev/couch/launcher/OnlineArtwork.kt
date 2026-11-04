package dev.couch.launcher

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal object OnlineArtwork {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_MS = 4_000
    private const val MAX_REDIRECTS = 2

    /** Fetch only the image URL supplied by Android, without app credentials or disk caching. */
    fun read(raw: String, open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): ByteArray? {
        return runCatching {
            var url = URL(raw)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS.toLong())
            repeat(MAX_REDIRECTS + 1) {
                if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null) return null
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) return null
                val connection = open(url)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = remaining.toInt()
                    connection.readTimeout = remaining.toInt()
                    connection.useCaches = false
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            if (connection.contentLengthLong > MAX_BYTES) return null
                            return connection.inputStream.use { readBounded(it, deadline) }
                        }
                        301, 302, 303, 307, 308 -> {
                            val location = connection.getHeaderField("Location") ?: return null
                            url = URL(url, location)
                        }
                        else -> return null
                    }
                } finally {
                    connection.disconnect()
                }
            }
            null
        }.getOrNull()
    }

    private fun readBounded(input: InputStream, deadline: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (System.nanoTime() < deadline) {
            val count = input.read(buffer)
            if (count == -1) return output.toByteArray()
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
        return null
    }
}
