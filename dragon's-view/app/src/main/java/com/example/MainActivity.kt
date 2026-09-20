package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.model.FileSource
import com.example.ui.browser.FolderBrowserScreen
import com.example.ui.home.HomeScreen
import com.example.ui.settings.AboutScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.DragonsViewTheme
import com.example.ui.theme.NearBlack
import com.example.ui.viewer.ViewerContainer

sealed interface Screen {
  object Home : Screen
  data class Viewer(val fileSource: FileSource) : Screen
  object FolderBrowser : Screen
  object Settings : Screen
  object About : Screen
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val initialUri = extractUriFromIntent(intent)

    setContent {
      DragonsViewTheme {
        DragonsViewApp(activity = this, initialUri = initialUri)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val uri = extractUriFromIntent(intent)
    if (uri != null) {
      try {
        val fileSource = FileSource.fromUri(this, uri)
        recreate()
      } catch (_: Exception) {}
    }
  }

  private fun extractUriFromIntent(intent: Intent?): Uri? {
    if (intent == null) return null
    if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
      return intent.data
    }
    if (intent.action == Intent.ACTION_SEND) {
      return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
    return null
  }
}

@Composable
fun DragonsViewApp(activity: ComponentActivity, initialUri: Uri?) {
  var currentScreen by remember {
    mutableStateOf<Screen>(
      if (initialUri != null) {
        try {
          Screen.Viewer(FileSource.fromUri(activity, initialUri))
        } catch (_: Exception) {
          Screen.Home
        }
      } else {
        Screen.Home
      }
    )
  }

  // Handle hardware / gesture back button
  BackHandler(enabled = currentScreen !is Screen.Home) {
    currentScreen = when (currentScreen) {
      is Screen.About -> Screen.Settings
      is Screen.Settings, is Screen.FolderBrowser, is Screen.Viewer -> Screen.Home
      Screen.Home -> Screen.Home
    }
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = NearBlack
  ) {
    when (val screen = currentScreen) {
      is Screen.Home -> {
        HomeScreen(
          onFileSelected = { uri ->
            try {
              val fileSource = FileSource.fromUri(activity, uri)
              currentScreen = Screen.Viewer(fileSource)
            } catch (_: Exception) {}
          },
          onOpenFolderBrowser = { currentScreen = Screen.FolderBrowser },
          onOpenSettings = { currentScreen = Screen.Settings }
        )
      }
      is Screen.Viewer -> {
        ViewerContainer(
          fileSource = screen.fileSource,
          onBack = { currentScreen = Screen.Home },
          onOpenInnerFile = { innerSource ->
            currentScreen = Screen.Viewer(innerSource)
          }
        )
      }
      is Screen.FolderBrowser -> {
        FolderBrowserScreen(
          onFileSelected = { uri ->
            try {
              val fileSource = FileSource.fromUri(activity, uri)
              currentScreen = Screen.Viewer(fileSource)
            } catch (_: Exception) {}
          },
          onBack = { currentScreen = Screen.Home }
        )
      }
      is Screen.Settings -> {
        SettingsScreen(
          onBack = { currentScreen = Screen.Home },
          onOpenAbout = { currentScreen = Screen.About }
        )
      }
      is Screen.About -> {
        AboutScreen(
          onBack = { currentScreen = Screen.Settings }
        )
      }
    }
  }
}

