package com.phoneguard.app.vault

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 비밀번호·메모 같은 민감 정보를 암호화해서 보관하는 금고.
 *
 * 왜 이렇게 만들었나:
 *  - 암호화 키는 안드로이드 KeyStore(하드웨어 보안 모듈, 가능 시)에 보관한다.
 *    → 앱을 뜯어봐도 키 원문이 파일에 없다.
 *  - 저장 자체는 EncryptedSharedPreferences로, 값과 키 이름을 모두 암호화한다.
 *  - setUserAuthenticationRequired=true 옵션으로 "기기 잠금 해제(지문/PIN) 후에만"
 *    키를 쓰도록 걸 수 있다(요구 시).
 *
 * 주의: Jetpack Security(EncryptedSharedPreferences)는 사용이 간단하지만
 * 세밀한 제어는 제한적이다. 학습이 더 깊어지면 KeyStore를 직접 다루는 쪽으로 확장하면 된다.
 */
class SecureVault(
    context: Context,
    fileName: String = "phone_guard_vault",
    requireUserAuth: Boolean = false
) {
    private val appContext = context.applicationContext

    private val masterKey: MasterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .apply {
            if (requireUserAuth) {
                // 잠금 해제 후 10초 동안만 키 사용 허용
                setUserAuthenticationRequired(true, 10)
            }
        }
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun keys(): Set<String> = prefs.all.keys

    fun clear() {
        prefs.edit().clear().apply()
    }
}
