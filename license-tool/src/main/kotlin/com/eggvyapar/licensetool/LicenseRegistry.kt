package com.eggvyapar.licensetool

import com.eggbusinessmanager.domain.license.LicenseCrypto
import com.eggbusinessmanager.domain.license.LicenseFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

/** One customer's lifetime license. [issued] turns true when the first device key is handed out. */
@Serializable
data class LicenseRecord(
    val id: String,
    val customer: String,
    val shop: String,
    val phone: String = "",
    val maxDevices: Int,
    val createdAt: String,
    val issued: Boolean = false,
    val devices: List<DeviceRecord> = emptyList(),
    val notes: String = "",
)

/** A device activated under a license, with the key made for it. */
@Serializable
data class DeviceRecord(val deviceCode: String, val key: String, val issuedAt: String)

@Serializable
data class RegistryFile(val version: Int = 1, val licenses: List<LicenseRecord> = emptyList())

class LicenseToolException(message: String) : Exception(message)

/**
 * The owner's private files, by default in `~/.eggvyapar-license/`:
 * - `signing-key.pk8` — the ECDSA P-256 private key (PKCS#8, Base64). Never share it, never put it in the app.
 * - `public-key.txt` — the matching public key (X.509, Base64); this one goes into the app.
 * - `registry.json` — every license, customer, shop, device limit and issued device key.
 */
class LicenseHome(val dir: File) {
    val signingKeyFile = File(dir, "signing-key.pk8")
    val publicKeyFile = File(dir, "public-key.txt")
    val registryFile = File(dir, "registry.json")

    val initialized: Boolean get() = signingKeyFile.exists() && publicKeyFile.exists()

    /** Creates the key pair once; refuses to replace an existing one (keys already sold would stop working). */
    fun init(): String {
        if (initialized) throw LicenseToolException("A signing key already exists in $dir; it is never replaced.")
        dir.mkdirs()
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
        val pair = generator.generateKeyPair()
        val public = Base64.getEncoder().encodeToString(pair.public.encoded)
        signingKeyFile.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
        publicKeyFile.writeText(public)
        restrictToOwner(signingKeyFile)
        if (!registryFile.exists()) save(RegistryFile())
        return public
    }

    fun signingKey(): PrivateKey {
        if (!initialized) throw LicenseToolException("No signing key yet. Run: license-tool init")
        return LicenseCrypto.privateKey(signingKeyFile.readText())
    }

    fun publicKey(): String {
        if (!initialized) throw LicenseToolException("No signing key yet. Run: license-tool init")
        return publicKeyFile.readText().trim()
    }

    fun load(): RegistryFile = if (registryFile.exists()) JSON.decodeFromString(registryFile.readText()) else RegistryFile()

    /** Written to a temporary file first, so a crash can never leave a half-written registry. */
    fun save(registry: RegistryFile) {
        dir.mkdirs()
        val tmp = File(dir, "registry.json.tmp")
        tmp.writeText(JSON.encodeToString(RegistryFile.serializer(), registry))
        if (!tmp.renameTo(registryFile)) {
            registryFile.delete()
            check(tmp.renameTo(registryFile)) { "Could not write ${registryFile.path}" }
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private companion object {
        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** License operations on the registry: create, issue a device key within the limit, list and export. */
class LicenseRegistry(private val home: LicenseHome, private val random: SecureRandom = SecureRandom()) {

    fun create(
        customer: String,
        shop: String,
        maxDevices: Int = 1,
        phone: String = "",
        notes: String = "",
        now: Instant = Instant.now(),
    ): LicenseRecord {
        val problem = when {
            customer.isBlank() -> "Customer name is required."
            shop.isBlank() -> "Shop name is required."
            maxDevices !in 1..LicenseFormat.MAX_DEVICE_LIMIT -> "Max devices must be between 1 and ${LicenseFormat.MAX_DEVICE_LIMIT}."
            else -> null
        }
        problem?.let { throw LicenseToolException(it) }
        val registry = home.load()
        val taken = registry.licenses.map { it.id }.toSet()
        var id: String
        do {
            id = LicenseFormat.licenseIdText(ByteArray(LicenseFormat.LICENSE_ID_BYTES).also(random::nextBytes))
        } while (id in taken)
        val record = LicenseRecord(id, customer.trim(), shop.trim(), phone.trim(), maxDevices, now.toString(), notes = notes.trim())
        home.save(registry.copy(licenses = registry.licenses + record))
        return record
    }

    /**
     * The key for [deviceCode] under [licenseId]. The same device always gets its existing key again; a new
     * device is refused once the license's device limit is reached.
     */
    fun issue(licenseId: String, deviceCode: String, now: Instant = Instant.now()): DeviceRecord {
        val deviceHash = LicenseFormat.parseDeviceCode(deviceCode)
            ?: throw LicenseToolException("\"$deviceCode\" is not a device code (16 characters, like 7K2Q-M9XP-4RTD-H8WN).")
        val code = LicenseFormat.deviceCode(deviceHash)
        val registry = home.load()
        val record = find(registry, licenseId)
        record.devices.firstOrNull { it.deviceCode == code }?.let { return it }
        if (record.devices.size >= record.maxDevices) {
            throw LicenseToolException(
                "${record.id} (${record.customer}) already has ${record.devices.size} of ${record.maxDevices} device(s). " +
                    "Raise the limit with: license-tool set-limit ${record.id} <n>",
            )
        }
        val idBytes = checkNotNull(LicenseFormat.parseLicenseId(record.id))
        val payload = LicenseFormat.Payload(idBytes, record.maxDevices, deviceHash)
        val key = LicenseFormat.encodeKey(payload, LicenseCrypto.sign(home.signingKey(), LicenseFormat.signedBytes(payload)))
        val device = DeviceRecord(code, key, now.toString())
        val updated = record.copy(issued = true, devices = record.devices + device)
        home.save(registry.copy(licenses = registry.licenses.map { if (it.id == record.id) updated else it }))
        return device
    }

    /** Changes how many devices a license may have from now on; keys already issued keep working. */
    fun setLimit(licenseId: String, maxDevices: Int): LicenseRecord {
        val registry = home.load()
        val record = find(registry, licenseId)
        if (maxDevices !in record.devices.size.coerceAtLeast(1)..LicenseFormat.MAX_DEVICE_LIMIT) {
            throw LicenseToolException("The limit must be at least ${record.devices.size.coerceAtLeast(1)} (devices already issued).")
        }
        val updated = record.copy(maxDevices = maxDevices)
        home.save(registry.copy(licenses = registry.licenses.map { if (it.id == record.id) updated else it }))
        return updated
    }

    fun list(): List<LicenseRecord> = home.load().licenses

    fun get(licenseId: String): LicenseRecord = find(home.load(), licenseId)

    /** CSV for Excel: one row per license and device (licenses without devices get one row). */
    fun exportCsv(): String {
        val header = "License ID,Customer,Shop,Phone,Max devices,Issued,Created,Device code,Key issued at,License key,Notes"
        val rows = list().flatMap { r ->
            val base = listOf(r.id, r.customer, r.shop, r.phone, r.maxDevices.toString(), if (r.issued) "Yes" else "No", r.createdAt)
            if (r.devices.isEmpty()) {
                listOf(base + listOf("", "", "", r.notes))
            } else {
                r.devices.map { d -> base + listOf(d.deviceCode, d.issuedAt, d.key, r.notes) }
            }
        }
        return (listOf(header) + rows.map { row -> row.joinToString(",") { csv(it) } }).joinToString("\r\n") + "\r\n"
    }

    private companion object {
        val CSV_SPECIAL = setOf(',', '"', '\n', '\r')
    }

    private fun find(registry: RegistryFile, licenseId: String): LicenseRecord {
        val wanted = LicenseFormat.parseLicenseId(licenseId)?.let(LicenseFormat::licenseIdText)
            ?: throw LicenseToolException("\"$licenseId\" is not a license id (like L-7F3KQ2MZ).")
        return registry.licenses.firstOrNull { it.id == wanted } ?: throw LicenseToolException("No license $wanted in the registry.")
    }

    private fun csv(value: String): String {
        // Leading = + - @ would be run as a formula by Excel.
        val safe = if (value.firstOrNull() in setOf('=', '+', '@')) "'$value" else value
        return if (safe.any { it in CSV_SPECIAL }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
