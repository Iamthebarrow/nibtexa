import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// Your own testing build: -Pnibtexa.personal=true packs the math model into the APK from outside the repository. Never publish one
// (ADR-0003). It can't reach Google Play: a bundle refuses this flag.
val personalBuild = providers.gradleProperty("nibtexa.personal").orNull == "true"
if (personalBuild && gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }) {
    throw GradleException("-Pnibtexa.personal=true packs the recognition model, so it can't build a bundle for Google Play")
}

android {
    namespace = "io.github.iamthebarrow.nibtexa"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iamthebarrow.nibtexa"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing for sideloading (Phase 10). The key never enters the repo: it is read from
    // keystore.properties at the repo root (git-ignored) or from NIBTEXA_* environment variables.
    // Without either, the release APK is built unsigned. See docs/guide/INSTALLING.md.
    val signing = rootProject.file("keystore.properties").takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    fun signingValue(key: String, env: String): String? = signing?.getProperty(key) ?: System.getenv(env)
    val storePath = signingValue("storeFile", "NIBTEXA_STORE_FILE")
    signingConfigs {
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "NIBTEXA_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "NIBTEXA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "NIBTEXA_KEY_PASSWORD")
            }
        }
    }

    // Instrumented tests normally run against debug; -Pnibtexa.testRelease=true runs them against
    // the release build (on the x86_64 AVD, together with -Pnibtexa.releaseAbi=x86_64).
    if (providers.gradleProperty("nibtexa.testRelease").orNull == "true") testBuildType = "release"

    buildTypes {
        debug {
            // ONNX Runtime ships ~140 MB of native code for four ABIs. Debug builds keep the two
            // that matter for development (the x86_64 AVD and arm64 tablets) to keep installs
            // fast.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Sideloading onto a tablet: arm64 only, which every current Android tablet uses.
            // -Pnibtexa.releaseAbi=x86_64 builds the same release for the emulator instead.
            ndk { abiFilters += providers.gradleProperty("nibtexa.releaseAbi").orElse("arm64-v8a").get() }
            // R8 stays off: ONNX Runtime's native code dominates the size, and shrinking JGit,
            // ONNX Runtime and the WebView bridge would need keep rules tested on every change.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The bundled model (Phase 14) is copied out of the APK on first start; stored, not
        // deflated, it streams straight out and its size is known.
        noCompress += "onnx"
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
}

room {
    // Exported schemas are committed: they are the baseline for migration tests.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:maths"))
    implementation(project(":core:export"))
    implementation(project(":core:recognition"))
    implementation(project(":core:sync"))
    implementation(project(":feature:mathedit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.onnxruntime.android)
    // Reading handwritten words (ADR-0007): proprietary, under the additional permission in NOTICE.md.
    implementation(libs.mlkit.digital.ink)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility) // Accessibility Test Framework checks (Phase 8)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.jgit) // the sync tests edit the test remote "from elsewhere"

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/** Packages the licence texts shown on the "Open-source licences" screen (Phase 10). */
abstract class CopyLicences : DefaultTask() {
    /** Asset file name to source file. */
    @get:Input
    abstract val names: MapProperty<String, String>

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licences")
        dir.deleteRecursively()
        dir.mkdirs()
        for ((name, path) in names.get()) File(path).copyTo(dir.resolve(name))
    }
}

val licenceFiles = mapOf(
    "nibtexa-GPL-3.0.txt" to "LICENSE",
    "third-party.md" to "docs/THIRD_PARTY.md",
    "apache-2.0.txt" to "third_party/apache/LICENSE-2.0.txt",
    "jgit-EDL-1.0.txt" to "third_party/jgit/LICENSE",
    "slf4j-MIT.txt" to "third_party/slf4j/LICENSE.txt",
    "onnxruntime-MIT.txt" to "third_party/onnxruntime/LICENSE",
    "onnxruntime-ThirdPartyNotices.txt" to "third_party/onnxruntime/ThirdPartyNotices.txt",
    "nunito-OFL.txt" to "third_party/nunito/OFL.txt",
    "baloo2-OFL.txt" to "third_party/baloo2/OFL.txt",
    "mlkit-NOTICE.md" to "third_party/mlkit/NOTICE.md",
    "licence-permission.md" to "NOTICE.md",
    "privacy.md" to "docs/PRIVACY.md",
).mapValues { rootProject.file(it.value).path }

val copyLicences = tasks.register<CopyLicences>("copyLicences") {
    names.set(licenceFiles)
    sources.from(licenceFiles.values)
}

/**
 * Packs the recognition model into the APK (Phase 14, ADR-0003 amendment): only with
 * -Pnibtexa.personal=true, never in a public release. The files come from outside the repository
 * (`-Pnibtexa.modelDir`, default `../nibtexa-eval-data/texteller-int8`); without them the APK is
 * built without the model, and it can still be loaded in the app.
 */
abstract class CopyModel : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("texteller-int8")
        dir.deleteRecursively()
        dir.mkdirs()
        val files = sources.files.filter { it.isFile }
        if (files.size < MODEL_FILES.size) {
            logger.warn("NibTeXa: recognition model not found, building without it (see docs/guide/MODEL_SETUP.md)")
            return
        }
        files.forEach { it.copyTo(dir.resolve(it.name)) }
    }

    companion object {
        val MODEL_FILES = listOf("encoder_model.onnx", "decoder_model_merged.onnx", "vocab.json")
    }
}

val modelDir = providers.gradleProperty("nibtexa.modelDir").map { File(it) }
    .orElse(rootProject.layout.projectDirectory.dir("../nibtexa-eval-data/texteller-int8").asFile)

val copyModel = tasks.register<CopyModel>("copyModel") {
    sources.from(modelDir.map { dir -> CopyModel.MODEL_FILES.map(dir::resolve).filter(File::isFile) })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicences, CopyLicences::outputDir)
        if (personalBuild) variant.sources.assets?.addGeneratedSourceDirectory(copyModel, CopyModel::outputDir)
    }
}
