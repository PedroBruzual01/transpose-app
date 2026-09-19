package com.realtimetranspose

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.realtimetranspose.audio.TransposePlayer
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
            FilePlayerScreen(
                onPickFile = {
                    filePickerLauncher.launch(arrayOf("audio/*"))
                },
            )
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
