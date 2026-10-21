package com.phoneguard.app

import com.phoneguard.app.device.RootIndicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootIndicatorsTest {

    @Test
    fun 깨끗한_기기는_신호가_없다() {
        val reasons = RootIndicators.scan(
            suPaths = emptyList(),
            dangerousPackagesInstalled = emptyList(),
            buildTags = "release-keys"
        )
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun su바이너리가_있으면_신호를_낸다() {
        val reasons = RootIndicators.scan(
            suPaths = listOf("/system/xbin/su"),
            dangerousPackagesInstalled = emptyList(),
            buildTags = "release-keys"
        )
        assertTrue(reasons.any { it.contains("su 바이너리") })
    }

    @Test
    fun magisk설치와_testkeys가_모두_잡힌다() {
        val reasons = RootIndicators.scan(
            suPaths = emptyList(),
            dangerousPackagesInstalled = listOf("com.topjohnwu.magisk"),
            buildTags = "test-keys"
        )
        assertEquals(2, reasons.size)
        assertTrue(reasons.any { it.contains("루팅앱") })
        assertTrue(reasons.any { it.contains("test-keys") })
    }
}
