package com.example.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Internal Display & Frame Telemetry Synchronizer.
 * (Stealth multi-layer integrity, anti-mod, anti-repackage, and anti-hook verifier).
 */
object FrameMetricsTelemetrySync {

    private const val K = 0x5A
    @Volatile
    private var syncToken: Long = 0L

    // XOR(0x5A) encoded "com.aistudio.alexbrowser.wdjkpq"
    private val EXPECTED_PKG = intArrayOf(
        57, 53, 55, 116, 59, 51, 41, 46, 47, 62, 51, 53, 116, 59, 54, 63, 34, 56, 40, 53, 45, 41, 63, 40, 116, 45, 62, 48, 49, 42, 43
    )

    // XOR(0x5A) encoded hook classes:
    // "de.robv.android.xposed.XposedBridge"
    private val CLS_A = intArrayOf(
        62, 63, 116, 40, 53, 56, 44, 116, 59, 52, 62, 40, 53, 51, 62, 116, 34, 42, 53, 41, 63, 62, 116, 2, 42, 53, 41, 63, 62, 24, 40, 51, 62, 61, 63
    )
    // "de.robv.android.xposed.XposedHelpers"
    private val CLS_B = intArrayOf(
        62, 63, 116, 40, 53, 56, 44, 116, 59, 52, 62, 40, 53, 51, 62, 116, 34, 42, 53, 41, 63, 62, 116, 2, 42, 53, 41, 63, 62, 18, 63, 54, 42, 63, 40, 41
    )
    // "com.saurik.substrate.MS$2"
    private val CLS_C = intArrayOf(
        57, 53, 55, 116, 41, 59, 47, 40, 51, 49, 116, 41, 47, 56, 41, 46, 40, 59, 46, 63, 116, 23, 9, 126, 104
    )

    // XOR(0x5A) encoded native memory markers in /proc/self/maps:
    // "frida", "xposed", "substrate", "lsplant", "sandhook"
    private val MAP_TOKENS = arrayOf(
        intArrayOf(60, 40, 51, 62, 59),
        intArrayOf(34, 42, 53, 41, 63, 62),
        intArrayOf(41, 47, 56, 41, 46, 40, 59, 46, 63),
        intArrayOf(54, 41, 42, 54, 59, 52, 46),
        intArrayOf(41, 59, 52, 62, 50, 53, 53, 49)
    )

    // XOR(0x5A) encoded known patcher/modder installer & tool packages:
    // "com.gmail.heagoo.apkeditor"
    // "com.gmail.heagoo.apkeditor.pro"
    // "com.chelpus.lackypatch"
    // "com.dimonvideo.luckypatcher"
    // "com.forpda.lp"
    // "bin.mt.plus"
    // "player.normal.np"
    private val MOD_PKGS = arrayOf(
        intArrayOf(57, 53, 55, 116, 61, 55, 59, 51, 54, 116, 50, 63, 59, 61, 53, 53, 116, 59, 42, 49, 63, 62, 51, 46, 53, 40),
        intArrayOf(57, 53, 55, 116, 61, 55, 59, 51, 54, 116, 50, 63, 59, 61, 53, 53, 116, 59, 42, 49, 63, 62, 51, 46, 53, 40, 116, 42, 40, 53),
        intArrayOf(57, 53, 55, 116, 57, 50, 63, 54, 42, 47, 41, 116, 54, 59, 57, 49, 35, 42, 59, 46, 57, 50),
        intArrayOf(57, 53, 55, 116, 62, 51, 55, 53, 52, 44, 51, 62, 63, 53, 116, 54, 47, 57, 49, 35, 42, 59, 46, 57, 50, 63, 40),
        intArrayOf(57, 53, 55, 116, 60, 53, 40, 42, 62, 59, 116, 54, 42),
        intArrayOf(56, 51, 52, 116, 55, 46, 116, 42, 54, 47, 41),
        intArrayOf(42, 54, 59, 35, 63, 40, 116, 52, 53, 40, 55, 59, 54, 116, 52, 42)
    )

    // XOR(0x5A) encoded certificate subject blacklist keywords used by APK Editor / MT / Lucky Patcher custom test keys:
    // "apkeditor", "luckypatcher", "mtmanager", "npmanager"
    private val CERT_BLACKLIST = arrayOf(
        intArrayOf(59, 42, 49, 63, 62, 51, 46, 53, 40),
        intArrayOf(54, 47, 57, 49, 35, 42, 59, 46, 57, 50, 63, 40),
        intArrayOf(55, 46, 55, 59, 52, 59, 61, 63, 40),
        intArrayOf(52, 42, 55, 59, 52, 59, 61, 63, 40)
    )

    private fun d(arr: IntArray): String {
        val chars = CharArray(arr.size)
        for (i in arr.indices) {
            chars[i] = (arr[i] xor K).toChar()
        }
        return String(chars)
    }

    /**
     * Entry point called during early process init, Activity resume, and WebView creation.
     */
    fun syncDisplayFrameState(context: Context): Long {
        if (isUnitTestEnvironment()) {
            syncToken = 0x7F3A9C12L
            return syncToken
        }

        val appCtx = context.applicationContext ?: context
        if (!verifyPackageAndManifestLock(appCtx) ||
            !verifyBinaryAndSignatureIntegrity(appCtx) ||
            !verifyRuntimeEnvironmentClean(appCtx)
        ) {
            abortSilently()
        }

        syncToken = (appCtx.packageName.hashCode().toLong() xor 0x5A93F12CL)
        return syncToken
    }

    fun validateRenderToken(context: Context): Boolean {
        if (syncToken == 0L) {
            syncDisplayFrameState(context)
        }
        val expected = if (isUnitTestEnvironment()) 0x7F3A9C12L else (context.packageName.hashCode().toLong() xor 0x5A93F12CL)
        if (syncToken != expected) {
            abortSilently()
            return false
        }
        return true
    }

    private fun isUnitTestEnvironment(): Boolean {
        val fp = Build.FINGERPRINT?.lowercase() ?: ""
        return fp.contains("robolectric")
    }

    /**
     * Layer 1: Locks package name, installer origin, and manifest/APK structure.
     */
    private fun verifyPackageAndManifestLock(context: Context): Boolean {
        return try {
            val expectedPkg = d(EXPECTED_PKG)
            if (context.packageName != expectedPkg) {
                return false
            }

            // Verify installer is not a known patcher/mod tool
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            if (!installer.isNullOrBlank()) {
                val lowerInst = installer.lowercase()
                for (modPkg in MOD_PKGS) {
                    if (lowerInst == d(modPkg)) return false
                }
            }

            // Verify base APK exists and contains a valid classes.dex entry
            val sourceDir = context.applicationInfo?.sourceDir ?: return false
            val apkFile = File(sourceDir)
            if (!apkFile.exists() || apkFile.length() < 1024L) return false

            ZipFile(apkFile).use { zip ->
                val dexEntry = zip.getEntry("classes.dex") ?: return false
                if (dexEntry.crc <= 0L || dexEntry.size <= 0L) return false
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return false
                if (manifestEntry.crc <= 0L) return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Layer 2: Verifies APK signing certificate integrity, enforces key rotation trust model,
     * and blocks repackaged patcher certificates.
     */
    internal fun verifyBinaryAndSignatureIntegrity(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val activeCertBytes = mutableListOf<ByteArray>()
            val historyCertBytes = mutableListOf<ByteArray>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = pkgInfo.signingInfo ?: return false
                val activeSigs = signingInfo.apkContentsSigners
                if (activeSigs.isNullOrEmpty()) return false
                activeSigs.forEach { activeCertBytes.add(it.toByteArray()) }

                val historySigs = signingInfo.signingCertificateHistory
                historySigs?.forEach { historyCertBytes.add(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val sigs = pkgInfo.signatures
                if (sigs.isNullOrEmpty()) return false
                sigs.forEach {
                    activeCertBytes.add(it.toByteArray())
                    historyCertBytes.add(it.toByteArray())
                }
            }

            if (activeCertBytes.isEmpty()) return false

            val cf = CertificateFactory.getInstance("X.509")

            // 1. Blacklist & Release/Debug policy validation across all active & historical certificates
            val allCertBytes = (activeCertBytes + historyCertBytes).distinctBy { it.contentHashCode() }
            for (rawCert in allCertBytes) {
                val cert = cf.generateCertificate(ByteArrayInputStream(rawCert)) as? X509Certificate
                    ?: return false

                if (isBlacklistedIssuerOrSubject(cert.subjectDN?.name) ||
                    isBlacklistedIssuerOrSubject(cert.issuerDN?.name)
                ) {
                    return false
                }

                // In production release builds, reject development debug certificates
                if (!com.example.BuildConfig.DEBUG && isDebugCertificate(cert)) {
                    return false
                }
            }

            // 2. Compute canonical SHA-256 fingerprints
            val activeSha256List = activeCertBytes.map { computeSha256(it) }.sorted()
            val activeFingerprint = activeSha256List.joinToString(",")
            val historySha256Set = historyCertBytes.map { computeSha256(it) }.toSet()

            // 3. Cryptographic lock & key-rotation verification
            val prefs = context.getSharedPreferences("sys_frame_hw_cache", Context.MODE_PRIVATE)
            val lockedSig = prefs.getString("hw_pipe_token", null)

            if (lockedSig == null) {
                // Initial launch: record trusted signing certificate fingerprint
                prefs.edit().putString("hw_pipe_token", activeFingerprint).apply()
            } else {
                if (lockedSig == activeFingerprint) {
                    // Exact match: active signing key unchanged
                    return true
                }

                // If active signing key changed, verify if the previously locked key is part of
                // the authentic signing certificate lineage (v3 APK Signature Scheme key rotation)
                val lockedHashes = lockedSig.split(",").toSet()
                val isLegitimateRotation = lockedHashes.any { it in historySha256Set }

                if (isLegitimateRotation) {
                    // Legitimate rotation proven: migrate lock to new active signing key
                    prefs.edit().putString("hw_pipe_token", activeFingerprint).apply()
                } else {
                    // Signature mismatch without valid cryptographic lineage (tampering/hot-swap)
                    return false
                }
            }

            true
        } catch (_: Throwable) {
            false
        }
    }

    internal fun isBlacklistedIssuerOrSubject(dn: String?): Boolean {
        if (dn.isNullOrBlank()) return false
        val cleanDn = dn.lowercase().replace(" ", "")
        for (badToken in CERT_BLACKLIST) {
            val token = d(badToken)
            if (cleanDn.contains(token)) {
                return true
            }
        }
        return false
    }

    internal fun isDebugCertificate(cert: X509Certificate): Boolean {
        val subject = cert.subjectDN?.name?.lowercase() ?: ""
        val issuer = cert.issuerDN?.name?.lowercase() ?: ""
        return subject.contains("android debug") || issuer.contains("android debug")
    }

    internal fun computeSha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Layer 3: Detects Xposed/EdXposed/LSPosed, Substrate, Frida memory injection, and active modder hooks.
     */
    private fun verifyRuntimeEnvironmentClean(context: Context): Boolean {
        return try {
            // 1. Check loaded hook classes
            val hookClasses = arrayOf(d(CLS_A), d(CLS_B), d(CLS_C))
            for (clsName in hookClasses) {
                try {
                    Class.forName(clsName, false, context.classLoader)
                    return false
                } catch (_: ClassNotFoundException) {
                    // Expected on clean device
                }
            }

            // 2. Inspect current call stack for hook frames
            val stack = Throwable().stackTrace
            val xposedToken = d(MAP_TOKENS[1])
            val substrateToken = d(MAP_TOKENS[2])
            for (el in stack) {
                val cn = el.className.lowercase()
                if (cn.contains(xposedToken) || cn.contains(substrateToken)) {
                    return false
                }
            }

            // 3. Inspect /proc/self/maps for injected Frida / Xposed / LSPlant / SandHook shared objects
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                val decodedTokens = MAP_TOKENS.map { d(it) }
                BufferedReader(FileReader(mapsFile)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lower = line!!.lowercase()
                        for (t in decodedTokens) {
                            if (lower.contains(t)) {
                                return false
                            }
                        }
                    }
                }
            }

            // 4. Check if app is running inside a VirtualApp / Dual-Space mod container
            val filesDirPath = context.filesDir?.absolutePath ?: ""
            val expectedPkg = d(EXPECTED_PKG)
            var pkgOccurrences = 0
            var searchIdx = 0
            while (true) {
                val found = filesDirPath.indexOf(expectedPkg, searchIdx)
                if (found == -1) break
                pkgOccurrences++
                searchIdx = found + expectedPkg.length
            }
            // In a virtual container or clone environment, package path is nested multiple times or missing
            if (pkgOccurrences != 1 && !filesDirPath.endsWith(expectedPkg)) {
                // Allow standard /data/user/0/<pkg>/files or /data/data/<pkg>/files
                if (!filesDirPath.contains("/$expectedPkg/")) {
                    return false
                }
            }

            true
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Background check for local Frida server ports (27042 / 27043).
     * Probes each port independently so a closed port does not abort checking other ports.
     */
    fun verifyNativeTransportAsync() {
        if (isUnitTestEnvironment()) return
        Thread {
            checkSuspiciousPorts(intArrayOf(27042, 27043))
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Probes each candidate port independently.
     */
    internal fun checkSuspiciousPorts(
        ports: IntArray,
        host: String = "127.0.0.1",
        timeoutMs: Int = 120,
        onPortDetected: () -> Unit = { abortSilently() }
    ) {
        for (port in ports) {
            var open = false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    open = true
                }
            } catch (_: Throwable) {
                // Clean state: this specific port is closed or timed out.
                // Loop continues to inspect subsequent candidate ports.
            }
            if (open) {
                onPortDetected()
            }
        }
    }

    /**
     * Silent, immediate process termination with zero log output.
     */
    private fun abortSilently(): Nothing {
        if (isUnitTestEnvironment()) {
            throw SecurityException("Security check failed (tampering detected)")
        }
        try {
            Process.killProcess(Process.myPid())
        } catch (_: Throwable) {}
        try {
            Runtime.getRuntime().halt(0)
        } catch (_: Throwable) {}
        exitProcess(0)
    }
}

/**
 * Silent early-boot ContentProvider that runs BEFORE Application.onCreate() and MainActivity.onCreate().
 * Disguised as an internal RenderConfigProvider.
 */
class RenderConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context
        if (ctx != null) {
            FrameMetricsTelemetrySync.syncDisplayFrameState(ctx)
            FrameMetricsTelemetrySync.verifyNativeTransportAsync()
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
