package io.github.tengigabytes.gymnotus.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.tengigabytes.gymnotus.R
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADB client identity of this app. The key must be the same for pairing and for the connection that follows, so
 * it is generated once and kept in the app's private files.
 */
private class ConnectionManager(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val keyFile = File(context.filesDir, "adb_key.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val name = X500Name("CN=Gymnotus")
            val notBefore = Date()
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(SecureRandom().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(SIGNATURE)))
                set("subject", CertificateSubjectName(name))
                set("issuer", CertificateIssuerName(name))
                set("key", CertificateX509Key(pair.public))
                set("validity", CertificateValidity(notBefore, Date(notBefore.time + VALIDITY_MS)))
            }
            key = pair.private
            certificate = X509CertImpl(info).apply { sign(key, SIGNATURE) }
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey() = key

    override fun getCertificate() = certificate

    override fun getDeviceName() = "Gymnotus"

    private companion object {
        const val SIGNATURE = "SHA512withRSA"
        const val VALIDITY_MS = 10L * 365 * 24 * 3600 * 1000
    }
}

private const val CONNECT_TIMEOUT_MS = 10_000L
private const val DISCOVERY_TIMEOUT_MS = 8_000L
private const val LOCAL_NETWORK_PERMISSION_SDK = 37

// adbd listens on every interface; loopback keeps the whole exchange on the device.
private const val LOOPBACK = "127.0.0.1"

/** @property granted whether the permission is held after the attempt */
data class GrantResult(val granted: Boolean, val message: String)

/**
 * Lets the app grant itself a permission whose protection level includes "development", without a computer:
 * it pairs with the phone's own Wireless debugging as an ADB client and runs `pm grant` through it.
 */
object SelfAdb {
    /**
     * @param pairingCode the six digits shown by "Pair device with pairing code"
     * @param pairingPort the port shown in that dialog, or null to find it by mDNS
     * @param connectPort the port on the Wireless debugging screen, or null to find it by mDNS
     */
    suspend fun pairAndGrant(
        context: Context,
        permission: String,
        pairingCode: String,
        pairingPort: Int? = null,
        connectPort: Int? = null,
    ): GrantResult = withContext(Dispatchers.IO) {
        var step = "starting"
        try {
            val manager = ConnectionManager(context)
            step = "finding the pairing port"
            val pairPort = pairingPort ?: discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING)
                ?: return@withContext GrantResult(false, context.getString(R.string.adb_no_pairing_port))
            step = "pairing on port $pairPort"
            if (!manager.pair(LOOPBACK, pairPort, pairingCode)) {
                return@withContext GrantResult(false, context.getString(R.string.adb_pair_refused))
            }
            step = "finding the connect port"
            val port = connectPort ?: discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT)
                ?: return@withContext GrantResult(false, context.getString(R.string.adb_no_connect_port))
            step = "connecting on port $port"
            if (!manager.connect(LOOPBACK, port)) {
                return@withContext GrantResult(false, context.getString(R.string.adb_connect_failed, port))
            }
            step = "running pm grant"
            val output = StringBuilder()
            manager.use {
                // The trailing echo marks the end: the command has finished once it shows up.
                it.openStream("shell:pm grant ${context.packageName} $permission 2>&1; echo exit=\$?").use { stream ->
                    val reader = stream.openInputStream().bufferedReader()
                    try {
                        while (!output.contains("exit=")) output.append(reader.readLine() ?: break).append('\n')
                    } catch (e: IOException) {
                        // adbd closing the stream surfaces as "Stream closed" rather than end of input.
                    }
                }
            }
            if (isGranted(context, permission)) {
                GrantResult(true, context.getString(R.string.adb_success))
            } else {
                GrantResult(false, context.getString(R.string.adb_grant_no_effect, output.trim()))
            }
        } catch (e: Exception) {
            // What counts is whether the permission is held, not how the connection ended.
            if (isGranted(context, permission)) {
                GrantResult(true, context.getString(R.string.adb_success))
            } else {
                // The step is an English technical phrase, like the exception text next to it.
                GrantResult(false, context.getString(R.string.adb_failed, step, e.toString()))
            }
        }
    }

    private fun isGranted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** First port announced for an ADB mDNS service on this device, or null if none shows up in time. */
    private suspend fun discover(context: Context, serviceType: String): Int? {
        // Never ask without the permission: the system would put its service picker on top of Settings, and the
        // pairing dialog underneath cancels itself when that happens.
        if (!canDiscover(context)) return null
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { awaitPort(context, serviceType) }
    }

    /** Before Android 17 (API 37) mDNS discovery needs no permission, and this one does not exist. */
    fun canDiscover(context: Context): Boolean = Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_SDK ||
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private suspend fun awaitPort(context: Context, serviceType: String): Int =
        suspendCancellableCoroutine { cont ->
            lateinit var mdns: AdbMdns
            mdns = AdbMdns(context, serviceType) { _, port ->
                if (port > 0 && cont.isActive) {
                    mdns.stop()
                    cont.resume(port)
                }
            }
            cont.invokeOnCancellation { mdns.stop() }
            mdns.start()
        }
}
