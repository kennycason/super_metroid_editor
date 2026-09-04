package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LsnesDiscoveryTest {

    @Test
    fun `candidatePaths on linux contains tools lsnes-smedit bin worker`() {
        val paths = LsnesDiscovery.candidatePaths(
            osName = "Linux",
            userHome = "/home/smedit",
            userDir = "/repo/super_metroid_editor",
            resourcesDir = "/opt/smedit/resources",
            path = "/usr/local/bin:/usr/bin",
        )
        val normalized = paths.map { it.replace('\\', '/') }
        assertTrue(normalized.any { it.endsWith("tools/lsnes-smedit/bin/smedit-lsnes-worker") }) {
            "expected repo worker path in $normalized"
        }
        assertTrue(normalized.any { it.endsWith("/opt/smedit/resources/smedit-lsnes-worker") })
        assertTrue(normalized.any { it.endsWith("/home/smedit/.smedit/lsnes/smedit-lsnes-worker") })
        assertTrue(normalized.any { it.endsWith("/usr/local/bin/smedit-lsnes-worker") })
        assertTrue(normalized.none { it.endsWith(".exe") })
        assertTrue(normalized.all { File(it).name == "smedit-lsnes-worker" })
    }

    @Test
    fun `candidatePaths on windows contains worker exe and LOCALAPPDATA-style path`() {
        val paths = LsnesDiscovery.candidatePaths(
            osName = "Windows 11",
            userHome = "C:\\Users\\smedit",
            userDir = "C:\\src\\super_metroid_editor",
            resourcesDir = "C:\\Program Files\\SMEDIT\\app",
            path = "C:\\Windows\\System32",
        )
        val normalized = paths.map { it.replace('\\', '/') }
        assertTrue(normalized.any { it.endsWith("tools/lsnes-smedit/bin/smedit-lsnes-worker.exe") }) {
            "expected Windows repo worker path in $normalized"
        }
        assertTrue(normalized.all { File(it).name.equals("smedit-lsnes-worker.exe", ignoreCase = true) }) {
            "Windows candidates must use smedit-lsnes-worker.exe, got $normalized"
        }

        val localAppData = System.getenv("LOCALAPPDATA")
        val localStyleSuffix = "SMEDIT/lsnes/smedit-lsnes-worker.exe"
        if (localAppData != null) {
            assertTrue(normalized.any { it.endsWith(localStyleSuffix) }) {
                "expected LOCALAPPDATA-style path ending in $localStyleSuffix, got $normalized"
            }
        } else {
            val localStyle = File("C:\\Users\\smedit\\AppData\\Local", localStyleSuffix)
            assertTrue(localStyle.path.replace('\\', '/').endsWith(localStyleSuffix)) {
                "Windows discovery uses File(LOCALAPPDATA, SMEDIT/lsnes/smedit-lsnes-worker.exe)"
            }
        }
    }

    @Test
    fun `isWorkerExecutable accepts smedit worker names and rejects stock lsnes`() {
        assertTrue(LsnesDiscovery.isWorkerExecutable(File("smedit-lsnes-worker")))
        assertTrue(LsnesDiscovery.isWorkerExecutable(File("smedit-lsnes-worker.exe")))
        assertTrue(LsnesDiscovery.isWorkerExecutable(File("/opt/smedit/smedit-lsnes-worker")))
        assertTrue(LsnesDiscovery.isWorkerExecutable(File("SMEDIT/smedit-lsnes-worker.exe")))
        assertFalse(LsnesDiscovery.isWorkerExecutable(File("lsnes")))
        assertFalse(LsnesDiscovery.isWorkerExecutable(File("lsnes-bsnes.exe")))
        assertFalse(LsnesDiscovery.isWorkerExecutable(File("lsnes-smedit")))
        assertFalse(LsnesDiscovery.isWorkerExecutable(File("/usr/bin/lsnes")))
        assertFalse(LsnesDiscovery.isWorkerExecutable(File("lsnes/lsnes-bsnes.exe")))
    }
}
