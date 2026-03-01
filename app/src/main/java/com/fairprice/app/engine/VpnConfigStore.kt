package com.fairprice.app.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.wireguard.config.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class VpnConfigSource {
    ASSET,
    USER,
}

@Serializable
data class VpnConfigMetadata(
    val id: String,
    val source: String,
    val displayName: String,
    val providerHint: String? = null,
    val enabled: Boolean = true,
    val contentSha256: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class VpnConfigRecord(
    val id: String,
    val source: VpnConfigSource,
    val displayName: String,
    val providerHint: String?,
    val enabled: Boolean,
)

interface VpnConfigStore {
    fun listUserConfigs(): List<VpnConfigRecord>
    fun listEnabledUserConfigs(): List<VpnConfigRecord>
    fun readUserConfigText(configId: String): Result<String>
    fun importUserConfig(displayName: String, rawConfigText: String): Result<VpnConfigRecord>
    fun setUserConfigEnabled(configId: String, enabled: Boolean): Result<Unit>
    fun getBaselineConfigId(): String?
    fun setBaselineConfigId(configId: String): Result<Unit>
}

class SecureVpnConfigStore(
    private val context: Context,
) : VpnConfigStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val storageDir: File by lazy {
        File(context.filesDir, STORAGE_DIR).also { if (!it.exists()) it.mkdirs() }
    }
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    override fun listUserConfigs(): List<VpnConfigRecord> {
        return readMetadata()
            .sortedBy { it.displayName.lowercase() }
            .map { meta ->
                VpnConfigRecord(
                    id = meta.id,
                    source = VpnConfigSource.USER,
                    displayName = meta.displayName,
                    providerHint = meta.providerHint,
                    enabled = meta.enabled,
                )
            }
    }

    override fun listEnabledUserConfigs(): List<VpnConfigRecord> {
        return listUserConfigs().filter { it.enabled }
    }

    override fun readUserConfigText(configId: String): Result<String> {
        return runCatching {
            require(configId.startsWith(USER_ID_PREFIX)) { "Config id is not a user config id." }
            val metadata = readMetadata().firstOrNull { it.id == configId }
                ?: error("User VPN config not found: $configId")
            val file = File(storageDir, "${metadata.id}.bin")
            if (!file.exists()) {
                error("Encrypted VPN config file is missing for id: ${metadata.id}")
            }
            val encryptedFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            encryptedFile.openFileInput().bufferedReader().use { it.readText() }
        }
    }

    override fun importUserConfig(displayName: String, rawConfigText: String): Result<VpnConfigRecord> {
        return runCatching {
            validateWireguardConfig(rawConfigText)
            val normalizedText = rawConfigText.trim()
            val sha = sha256(normalizedText)
            val existing = readMetadata().firstOrNull { it.contentSha256 == sha }
            if (existing != null) {
                return@runCatching VpnConfigRecord(
                    id = existing.id,
                    source = VpnConfigSource.USER,
                    displayName = existing.displayName,
                    providerHint = existing.providerHint,
                    enabled = existing.enabled,
                )
            }

            val id = USER_ID_PREFIX + UUID.randomUUID().toString()
            val safeName = displayName.ifBlank { "Imported VPN Config" }.take(80)
            val provider = detectProviderHint(safeName, normalizedText)
            val metadata = VpnConfigMetadata(
                id = id,
                source = VpnConfigSource.USER.name,
                displayName = safeName,
                providerHint = provider,
                enabled = true,
                contentSha256 = sha,
            )

            val targetFile = File(storageDir, "$id.bin")
            val encryptedFile = EncryptedFile.Builder(
                context,
                targetFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            encryptedFile.openFileOutput().bufferedWriter().use { out ->
                out.write(normalizedText)
            }

            val updated = readMetadata().toMutableList().apply { add(metadata) }
            writeMetadata(updated)

            if (getBaselineConfigId().isNullOrBlank()) {
                setBaselineConfigId(id).getOrThrow()
            }

            VpnConfigRecord(
                id = id,
                source = VpnConfigSource.USER,
                displayName = safeName,
                providerHint = provider,
                enabled = true,
            )
        }
    }

    override fun setUserConfigEnabled(configId: String, enabled: Boolean): Result<Unit> {
        return runCatching {
            val current = readMetadata().toMutableList()
            val index = current.indexOfFirst { it.id == configId }
            if (index == -1) error("User VPN config not found: $configId")
            val existing = current[index]
            current[index] = existing.copy(enabled = enabled)
            writeMetadata(current)
        }
    }

    override fun getBaselineConfigId(): String? {
        return prefs.getString(PREF_BASELINE_ID, null)
    }

    override fun setBaselineConfigId(configId: String): Result<Unit> {
        return runCatching {
            prefs.edit().putString(PREF_BASELINE_ID, configId).apply()
        }
    }

    private fun validateWireguardConfig(rawConfigText: String) {
        rawConfigText.byteInputStream().use { input ->
            Config.parse(input)
        }
    }

    private fun readMetadata(): List<VpnConfigMetadata> {
        val payload = prefs.getString(PREF_METADATA_JSON, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<VpnConfigMetadata>>(payload)
        }.getOrDefault(emptyList())
    }

    private fun writeMetadata(items: List<VpnConfigMetadata>) {
        val payload = json.encodeToString(items)
        prefs.edit().putString(PREF_METADATA_JSON, payload).apply()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun detectProviderHint(displayName: String, rawConfigText: String): String? {
        val combined = (displayName + "\n" + rawConfigText.take(300)).lowercase()
        return when {
            "proton" in combined -> "proton"
            "surfshark" in combined -> "surfshark"
            "mullvad" in combined -> "mullvad"
            "nord" in combined -> "nordvpn"
            else -> null
        }
    }

    companion object {
        private const val PREFS_NAME = "vpn_config_store"
        private const val PREF_METADATA_JSON = "user_config_metadata"
        private const val PREF_BASELINE_ID = "baseline_config_id"
        private const val STORAGE_DIR = "vpn_user_configs"
        private const val USER_ID_PREFIX = "user:"
    }
}

