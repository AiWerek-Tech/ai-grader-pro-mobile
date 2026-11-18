package com.wmvaa.aigrader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsAndUpdateTest {

    @Test
    fun `isPrivateLanIp mengenali RFC1918 secara ketat dan menolak IP publik 172`() {
        assertTrue(PrefsManager.isPrivateLanIp("localhost"))
        assertTrue(PrefsManager.isPrivateLanIp("127.0.0.1"))
        assertTrue(PrefsManager.isPrivateLanIp("server-sekolah.local"))
        assertTrue(PrefsManager.isPrivateLanIp("192.168.1.25"))
        assertTrue(PrefsManager.isPrivateLanIp("10.0.0.1"))
        assertTrue(PrefsManager.isPrivateLanIp("172.16.0.1"))
        assertTrue(PrefsManager.isPrivateLanIp("172.31.255.254"))

        // IP publik di luar 172.16.0.0/12 wajib ditolak
        assertFalse(PrefsManager.isPrivateLanIp("172.15.255.1"))
        assertFalse(PrefsManager.isPrivateLanIp("172.32.0.1"))
        assertFalse(PrefsManager.isPrivateLanIp("172.217.160.14"))
        assertFalse(PrefsManager.isPrivateLanIp("app.wmvaa.id"))
        assertFalse(PrefsManager.isPrivateLanIp(""))
    }

    @Test
    fun `normalizeBaseUrl menormalkan domain publik, LAN port 3000, LAN port 80, dan deep link`() {
        assertEquals(
            "https://app.wmvaa.id/ai-grader",
            PrefsManager.normalizeBaseUrl("http://app.wmvaa.id/ai-grader/dashboard/analisis")
        )
        assertEquals(
            "http://192.168.1.10:3000",
            PrefsManager.normalizeBaseUrl("192.168.1.10")
        )
        assertEquals(
            "http://192.168.1.10:3000/ai-grader",
            PrefsManager.normalizeBaseUrl("192.168.1.10/ai-grader/login")
        )
        // Port 80 eksplisit pada LAN tidak boleh dipaksa menjadi :3000
        assertEquals(
            "http://192.168.1.10/ai-grader",
            PrefsManager.normalizeBaseUrl("http://192.168.1.10:80/ai-grader")
        )
        assertEquals(
            "https://app.wmvaa.id/ai-grader",
            PrefsManager.normalizeBaseUrl("wmvaa://grader/dashboard/ljk")
        )
    }

    @Test
    fun `extractPathOrNull memisahkan rute aplikasi dan query string`() {
        assertEquals(
            "/dashboard/ljk?kelas=10A",
            PrefsManager.extractPathOrNull("https://app.wmvaa.id/ai-grader/dashboard/ljk?kelas=10A")
        )
        assertEquals(
            "/login",
            PrefsManager.extractPathOrNull("192.168.1.50:3000/ai-grader/login")
        )
        assertNull(PrefsManager.extractPathOrNull("https://app.wmvaa.id/ai-grader"))
    }

    @Test
    fun `UpdateChecker isNewerVersion membandingkan semver dengan benar`() {
        assertTrue(UpdateChecker.isNewerVersion("2.0.9", "2.0.7"))
        assertTrue(UpdateChecker.isNewerVersion("v2.1.0", "2.0.9"))
        assertFalse(UpdateChecker.isNewerVersion("2.0.7", "2.0.7"))
        assertFalse(UpdateChecker.isNewerVersion("2.0.5", "2.0.7"))
    }
}
