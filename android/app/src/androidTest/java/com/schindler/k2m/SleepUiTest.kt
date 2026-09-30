package com.schindler.k2m

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Sleep tab and the two Settings switches, in the real app UI on the emulator. */
class SleepUiTest {
    @get:Rule(order = 0) val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun reset() {
        val p = Prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        p.sleepGenerate = false; p.sleepSend = false
    }

    @Test fun sleepTabExplainsCustomSleepScreen() {
        rule.onNodeWithText("Sleep").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Custom", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Custom", substring = true).onFirst().assertIsDisplayed()
        rule.onNodeWithText("Draw for all series").assertIsDisplayed()
        rule.onNodeWithText("Check X4").assertIsDisplayed()
    }

    @Test fun settingsSwitchesTogglePrefs() {
        val prefs = Prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        rule.onNodeWithText("Settings").performClick()
        val gen = "Draw a sleep screen when a series is converted"
        val send = "Send the sleep screen with a series' books"
        rule.onNodeWithText(gen).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(send).performScrollTo().assertIsDisplayed()

        toggleSwitchNextTo(gen)
        rule.waitForIdle()
        assertEquals(true, prefs.sleepGenerate)
        assertEquals(false, prefs.sleepSend)
        toggleSwitchNextTo(send)
        rule.waitForIdle()
        assertEquals(true, prefs.sleepSend)
        toggleSwitchNextTo(gen)
        rule.waitForIdle()
        assertEquals(false, prefs.sleepGenerate)
    }

    private fun toggleSwitchNextTo(label: String) {
        val labelBounds = rule.onNodeWithText(label).performScrollTo().fetchSemanticsNode().boundsInRoot
        val all = rule.onAllNodes(isToggleable())
        val nodes = all.fetchSemanticsNodes()
        val idx = nodes.indices.minByOrNull { Math.abs(nodes[it].boundsInRoot.center.y - labelBounds.center.y) }!!
        all[idx].performClick()
    }
}
