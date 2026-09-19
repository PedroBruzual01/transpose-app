package com.realtimetranspose

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.realtimetranspose.audio.TransposePlayer
import com.realtimetranspose.ui.BrowserScreen
import com.realtimetranspose.ui.FilePlayerScreen

class MainActivity : ComponentActivity() {

    // Storage Access Framework — no storage permission needed, the user picks
    // exactly one file and we get a scoped content:// Uri for it.
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            TransposePlayer.load(this, uri, queryDisplayName(uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppRoot(onPickFile = { filePickerLauncher.launch(arrayOf("audio/*")) })
        }
    }

    override fun onDestroy() {
        TransposePlayer.stop()
        super.onDestroy()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } finally {
            cursor?.close()
        }
    }
}

/** Tab shell: Files (Modo A, working) / Browser (Modo B, Fase 0 spike screen). */
@Composable
private fun AppRoot(onPickFile: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Files", "Browser")

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> FilePlayerScreen(onPickFile = onPickFile)
                    1 -> BrowserScreen()
                }
            }
        }
    }
}
