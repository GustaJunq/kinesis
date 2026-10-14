package dev.kinesis.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kinesis.editor.ui.editor.EditorScreen
import dev.kinesis.editor.ui.home.HomeScreen
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KinesisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KinesisTheme {
                // Two destinations: the project list and the editor of one project.
                var openProject by rememberSaveable { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize().background(KColors.Background).safeDrawingPadding()) {
                    val id = openProject
                    if (id == null) {
                        HomeScreen(onOpen = { openProject = it })
                    } else {
                        BackHandler { openProject = null }
                        EditorScreen(projectId = id, onClose = { openProject = null })
                    }
                }
            }
        }
    }
}
