package io.github.iamthebarrow.nibtexa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.iamthebarrow.nibtexa.notebook.NotebookScreen
import io.github.iamthebarrow.nibtexa.ui.theme.NibTeXaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NibTeXaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NotebookScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
