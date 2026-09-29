package com.schindler.k2m

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private fun info(code: Long, url: String) = UpdateInfo(code, "v$code", "sha", 1, url)

    @Test fun newestPicksHighestCodeAcrossSources() {
        assertEquals("github", Updater.newest(5, listOf(info(6, "server"), info(7, "github")))?.apkUrl)
    }

    @Test fun tiePrefersFirstSource() {
        assertEquals("server", Updater.newest(5, listOf(info(7, "server"), info(7, "github")))?.apkUrl)
    }

    @Test fun nothingNewerIsNull() {
        assertNull(Updater.newest(7, listOf(info(7, "server"), info(6, "github"))))
        assertNull(Updater.newest(7, emptyList()))
    }

    @Test fun onlyGithubStillUpdates() {
        assertEquals("github", Updater.newest(5, listOf(info(6, "github")))?.apkUrl)
    }
}
