package app.readingcapture.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 取り込み箱の鍵（GitHub のトークン）の置き場。
 *
 * 端末の鍵の金庫（Android Keystore）で作った暗号鍵で暗号化してから保存する。
 * 暗号鍵そのものは金庫から取り出せないので、アプリのデータを写し取られても鍵は読めない。
 * バックアップにも入れない（マニフェストでバックアップを切ってある）。
 */
class TokenVault(context: Context) {

    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /** 保存してある鍵。無い、または読めなくなっていれば null。 */
    fun load(): String? {
        val data = prefs.getString(KEY_DATA, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // 金庫の暗号鍵が作り直された、など。入れ直してもらう
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    val hasToken: Boolean get() = prefs.contains(KEY_DATA)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "inbox-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_DATA = "token"
        const val KEY_IV = "iv"
    }
}
