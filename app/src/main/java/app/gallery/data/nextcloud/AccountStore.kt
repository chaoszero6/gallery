package app.gallery.data.nextcloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Account(
    /** Server root, e.g. "https://cloud.example.com" (no trailing slash). */
    val server: String,
    val loginName: String,
    val appPassword: String,
    /** Real Nextcloud user id (can differ from the login name). */
    val uid: String,
    /** Memories base URL ending in "/", e.g. "https://cloud.example.com/apps/memories/". */
    val memoriesBase: String,
    val memoriesVersion: String = "",
    val displayName: String = "",
) {
    val apiBase: String get() = memoriesBase + "api/"
    val davFiles: String get() = "$server/remote.php/dav/files/${enc(uid)}"
    val davRoot: String get() = "$server/remote.php/dav"

    companion object {
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Stores the Nextcloud account, with the whole record encrypted by an Android Keystore key. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account

    fun save(a: Account) {
        prefs.edit().putString(KEY, encrypt(json.encodeToString(Account.serializer(), a))).apply()
        _account.value = a
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        _account.value = null
    }

    private fun load(): Account? = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(Account.serializer(), decrypt(it)) }
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val bytes = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }

    private companion object {
        const val KEY = "account"
        const val ALIAS = "gallery_account"
    }
}
