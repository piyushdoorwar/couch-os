package dev.couch.launcher

import org.json.JSONObject
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

data class BacklightConfig(val host: String, val token: String, val certificate: String) {
    override fun toString() = "BacklightConfig(configured)"

    companion object {
        fun parse(json: JSONObject): BacklightConfig {
            val host = json.getString("host")
            val parts = host.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 } &&
                parts.joinToString(".") == host)
            require(parts[0] == 10 || parts[0] == 172 && parts[1]!! in 16..31 ||
                parts[0] == 192 && parts[1] == 168)
            val token = json.getString("token")
            require(token.matches(Regex("[A-Za-z0-9_-]{32,128}")))
            val certificate = json.getString("certificate")
            require(certificate.length in 1..4096)
            readCertificate(certificate)
            return BacklightConfig(host, token, certificate)
        }

        internal fun readCertificate(encoded: String) = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.getDecoder().decode(encoded).inputStream())
    }
}

/** Trust only the owner-provisioned certificate. Keep HTTPS hostname verification enabled. */
internal class BacklightClient(private val config: BacklightConfig) {
    private val tls by lazy {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("backlight", BacklightConfig.readCertificate(config.certificate))
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
    }

    fun state(): Boolean? = request(null)

    /** Read fresh state, then set an explicit value so HTTP retries cannot toggle twice. */
    fun toggle(): Boolean? = state()?.let { request(!it) }

    private fun request(on: Boolean?): Boolean? = runCatching {
        val connection = URL("https://${config.host}:8765/backlight").openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            connection.sslSocketFactory = tls
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            if (on != null) {
                val body = """{"on":$on}""".toByteArray()
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response).get("on") as? Boolean
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
