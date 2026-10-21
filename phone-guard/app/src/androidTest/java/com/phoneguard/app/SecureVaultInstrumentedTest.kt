package com.phoneguard.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoneguard.app.vault.SecureVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 비밀 금고를 "실제 기기/에뮬레이터"에서 검증하는 계측 테스트.
 * 여기서는 단순히 저장/조회만이 아니라, 디스크에 평문이 남지 않는지도 확인한다.
 *
 * 실행: 폰/에뮬레이터 연결 후
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SecureVaultInstrumentedTest {

    private lateinit var vault: SecureVault
    private val fileName = "test_vault_${System.currentTimeMillis()}"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        vault = SecureVault(ctx, fileName = fileName)
        vault.clear()
    }

    @Test
    fun 저장한_비밀을_다시_읽을_수_있다() {
        vault.put("bank_pin", "0417-secret")
        assertEquals("0417-secret", vault.get("bank_pin"))
        assertTrue(vault.keys().contains("bank_pin"))
    }

    @Test
    fun 삭제하면_사라진다() {
        vault.put("memo", "hello")
        vault.remove("memo")
        assertNull(vault.get("memo"))
    }

    @Test
    fun 디스크에_평문이_남지_않는다() {
        val secret = "PLAINTEXT_SHOULD_NOT_APPEAR_9f3a"
        vault.put("secret_key_name", secret)

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // EncryptedSharedPreferences 는 shared_prefs/<fileName>.xml 에 저장된다.
        val prefsFile = File(ctx.filesDir.parentFile, "shared_prefs/$fileName.xml")
        assertTrue("prefs 파일이 생성돼야 함", prefsFile.exists())

        val raw = prefsFile.readText()
        // 값뿐 아니라 키 이름(secret_key_name)도 암호화돼야 한다.
        assertFalse("값이 평문으로 보이면 안 됨", raw.contains(secret))
        assertFalse("키 이름이 평문으로 보이면 안 됨", raw.contains("secret_key_name"))
    }
}
