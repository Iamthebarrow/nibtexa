package io.github.iamthebarrow.nibtexa.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.iamthebarrow.nibtexa.ui.theme.NibTeXaTheme

/** Git sync for one booklet (Phase 7). Started from the notebook once its edits are saved. */
class SyncActivity : ComponentActivity() {

    private val viewModel: SyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookletId = intent.getStringExtra(EXTRA_BOOKLET) ?: return finish()
        // The pushed export includes the PDF (Phase 12), which is typeset in this window.
        viewModel.pdfRenderer = { BookletPdf(this).render(it) }
        viewModel.open(bookletId)
        enableEdgeToEdge()
        setContent {
            NibTeXaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SyncScreen(viewModel, Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.pdfRenderer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_BOOKLET = "booklet"

        fun intent(context: Context, bookletId: String): Intent =
            Intent(context, SyncActivity::class.java).putExtra(EXTRA_BOOKLET, bookletId)
    }
}
