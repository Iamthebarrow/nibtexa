package io.github.iamthebarrow.nibtexa.licences

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.ui.theme.NibTeXaTheme

/** One entry: what it covers, its licence, and the asset holding the full text. */
internal class Licence(val component: String, val licence: String, val asset: String)

/** The app's own licence and every third-party notice it must carry (docs/THIRD_PARTY.md). */
internal val LICENCES = listOf(
    Licence("NibTeXa", "GNU General Public License v3.0", "licences/nibtexa-GPL-3.0.txt"),
    Licence("Third-party components (list)", "versions and licences", "licences/third-party.md"),
    Licence(
        "AndroidX (including androidx.ink, Compose, Room, WebKit), Kotlin, kotlinx-serialization, JavaEWAH, commons-codec, JSpecify, JetBrains annotations",
        "Apache License 2.0",
        "licences/apache-2.0.txt",
    ),
    Licence("JGit", "Eclipse Distribution License 1.0 (BSD-3-Clause)", "licences/jgit-EDL-1.0.txt"),
    Licence("SLF4J API", "MIT License", "licences/slf4j-MIT.txt"),
    Licence("ONNX Runtime", "MIT License", "licences/onnxruntime-MIT.txt"),
    Licence("ONNX Runtime: components it includes", "third-party notices", "licences/onnxruntime-ThirdPartyNotices.txt"),
    Licence("MathLive (with the KaTeX fonts)", "MIT License", "mathedit/mathlive/LICENSE.txt"),
    Licence("Nunito (the app's typeface)", "SIL Open Font License 1.1", "licences/nunito-OFL.txt"),
    Licence("Baloo 2 (the NibTeXa title)", "SIL Open Font License 1.1", "licences/baloo2-OFL.txt"),
    Licence("Google ML Kit Digital Ink Recognition", "ML Kit Terms of Service: proprietary", "licences/mlkit-NOTICE.md"),
    Licence("NibTeXa's permission for ML Kit", "an addition to the GPL", "licences/licence-permission.md"),
    Licence("Privacy notes", "what leaves the device", "licences/privacy.md"),
)

/** Open-source licences (Phase 10): the list, and the full text of the one picked. */
class LicencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NibTeXaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    LicencesScreen(Modifier.padding(padding))
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LicencesActivity::class.java)
    }
}

@Composable
private fun LicencesScreen(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = open?.let(LICENCES::getOrNull)
    if (selected == null) {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(stringResource(R.string.licences_title), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.licences_intro), Modifier.padding(bottom = 8.dp))
            }
            items(LICENCES.size) { i ->
                val licence = LICENCES[i]
                Column(Modifier.fillMaxWidth().clickable { open = i }.padding(vertical = 12.dp)) {
                    Text(licence.component, style = MaterialTheme.typography.titleMedium)
                    Text(licence.licence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    } else {
        BackHandler { open = null }
        LicenceText(selected, onBack = { open = null }, modifier)
    }
}

@Composable
private fun LicenceText(licence: Licence, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val missing = stringResource(R.string.licences_missing, licence.asset)
    val lines = remember(licence.asset) {
        runCatching { context.assets.open(licence.asset).bufferedReader().use { it.readLines() } }.getOrElse { listOf(missing) }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.licences_back)) }
            Text(licence.component, style = MaterialTheme.typography.titleLarge)
            Text(licence.licence, Modifier.padding(bottom = 12.dp))
        }
        items(lines) { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}
