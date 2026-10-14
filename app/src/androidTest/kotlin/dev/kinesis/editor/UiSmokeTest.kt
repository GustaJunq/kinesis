package dev.kinesis.editor

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the main flows through the real UI and logs screenshots. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { Shots.log(name, it, 420) }
    }

    private fun click(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(text).onFirst().performClick()
        rule.waitForIdle()
    }

    @Test
    fun createEditAndBrowse() {
        shot("ui-home")
        click("New project")
        click("Create")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Timeline")).fetchSemanticsNodes().isNotEmpty() }

        click("Add")
        click("Text")
        click("Add")
        click("Cube")
        click("Add")
        click("Star")
        shot("ui-editor-timeline")

        click("Properties")
        shot("ui-editor-properties")
        click("Layers")
        shot("ui-editor-layers")
        click("Timeline")

        click("Split")
        click("Duplicate")
        click("Group")
        rule.onNodeWithContentDescription("Undo").assertExists()
        shot("ui-editor-after-ops")
    }
}
