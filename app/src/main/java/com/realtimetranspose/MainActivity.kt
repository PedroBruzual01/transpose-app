package com.realtimetranspose

import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.realtimetranspose.audio.TransposePlayer
import com.realtimetranspose.ui.BrowserScreen
import com.realtimetranspose.ui.FilePlayerScreen
import com.realtimetranspose.ui.theme.NocturneColors
import com.realtimetranspose.ui.theme.NocturneMaterialColorScheme
import com.realtimetranspose.ui.theme.NocturneType

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

        // The app is dark-themed end to end (Nocturne), but the manifest's base
        // theme is a plain Light one, so the system status/nav bars default to
        // light-appearance scrims that read as a grey strip against our dark
        // background. SystemBarStyle.dark(...) makes both bars transparent
        // (our own Compose background shows through) with light system icons,
        // matching the rest of the app instead of contrasting with it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

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

/**
 * Tab shell — see docs/design/design_handoff_transpose_ui/README.md
 * "Cabecera (común)". Tab selection and the Browser tab's whole WebView
 * state must survive rotation (MainActivity handles config changes itself,
 * see AndroidManifest.xml); rememberSaveable is defense-in-depth for
 * process death, where that doesn't help.
 */
@Composable
private fun AppRoot(onPickFile: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Files", "Browser")

    MaterialTheme(colorScheme = NocturneMaterialColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = NocturneColors.bg) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                NocturneHeader()
                NocturneTabs(selectedIndex = tab, titles = titles, onSelect = { tab = it })
                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        0 -> FilePlayerScreen(onPickFile = onPickFile)
                        1 -> BrowserScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun NocturneHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 16.dp, end = 16.dp)) {
        Text("TRANSPOSE", style = NocturneType.wordmark, color = NocturneColors.textMuted)
    }
}

@Composable
private fun NocturneTabs(selectedIndex: Int, titles: List<String>, onSelect: (Int) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val tabWidth = maxWidth / titles.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = tween(200),
            label = "tabIndicator",
        )

        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                titles.forEachIndexed { index, title ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title,
                            style = NocturneType.body13,
                            color = if (selected) NocturneColors.accent else NocturneColors.textMuted,
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NocturneColors.divider))
        }

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .height(2.dp)
                .align(Alignment.BottomStart)
                .background(NocturneColors.accent),
        )
    }
}
