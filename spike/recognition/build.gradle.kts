// Phase 3 recognition spike: on-device ONNX Runtime check. Not a dependency of :app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.iamthebarrow.nibtexa.spike.recognition"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(libs.onnxruntime.android)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
