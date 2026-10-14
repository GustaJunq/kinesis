package dev.kinesis.editor

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kinesis.editor.ui.editor.EditorScreen
import dev.kinesis.editor.ui.home.HomeScreen
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KinesisTheme

class MainActivity : ComponentActivity() {
    /** A .knsproj handed to us by another app, waiting to be imported by the home screen. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming = projectUri(intent)
        setContent {
            KinesisTheme {
                // Two destinations: the project list and the editor of one project.
                var openProject by rememberSaveable { mutableStateOf<String?>(null) }
                val pending = incoming
                if (pending != null && openProject != null) openProject = null
                Box(Modifier.fillMaxSize().background(KColors.Background).safeDrawingPadding()) {
                    val id = openProject
                    if (id == null) {
                        HomeScreen(
                            onOpen = { openProject = it },
                            incoming = pending,
                            onIncomingHandled = { incoming = null },
                        )
                    } else {
                        BackHandler { openProject = null }
                        EditorScreen(projectId = id, onClose = { openProject = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        projectUri(intent)?.let { incoming = it }
    }

    private fun projectUri(intent: Intent?): Uri? {
        val i = intent ?: return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }
}
