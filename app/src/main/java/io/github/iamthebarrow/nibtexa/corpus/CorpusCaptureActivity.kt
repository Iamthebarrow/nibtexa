package io.github.iamthebarrow.nibtexa.corpus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.iamthebarrow.nibtexa.canvas.CanvasScreen
import io.github.iamthebarrow.nibtexa.canvas.CanvasViewModel
import io.github.iamthebarrow.nibtexa.ui.theme.NibTeXaTheme

/**
 * Corpus capture (Phase 2): write each prompt and save it as an InkML sample. Separate from
 * the notebook, so samples never touch notes and notes never end up in the corpus.
 */
class CorpusCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NibTeXaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CanvasScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, CorpusCaptureActivity::class.java)

        /** The text trial prompt set (ADR-0007): plain-text lines for the handwriting recognizer. */
        fun textIntent(context: Context): Intent = intent(context).putExtra(CanvasViewModel.PROMPT_SET, CanvasViewModel.PROMPT_SET_TEXT)
    }
}
