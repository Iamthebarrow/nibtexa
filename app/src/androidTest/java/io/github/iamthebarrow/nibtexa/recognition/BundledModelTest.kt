package io.github.iamthebarrow.nibtexa.recognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The model packed into the personal build (Phase 14): copied out of the APK into an empty
 * model folder once, byte for byte, and then loaded; a folder that already has a model is left
 * alone. Uses a scratch folder, never the app's own model. Skipped for an APK built without it.
 */
@RunWith(AndroidJUnit4::class)
class BundledModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "bundled-test-${System.nanoTime()}").apply { mkdirs() }
    private val target = File(root, "models/texteller-int8")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theBundledModelIsCopiedOutOnceAndLoads() {
        val bundled = BundledModel(context.assets, target)
        assumeTrue("this APK was built without the model", bundled.available)
        assertFalse(bundled.installed)

        assertTrue(bundled.installIfMissing())
        assertNull(bundled.progress.value) // done
        assertTrue(bundled.installed)
        for (name in OnnxTexTellerModel.REQUIRED_FILES) {
            val size = context.assets.open("${BundledModel.ASSET_FOLDER}/$name").use { input -> input.skip(Long.MAX_VALUE) }
            assertEquals(name, size, File(target, name).length())
        }
        assertFalse("a second start copies nothing", bundled.installIfMissing())

        // The source copies it on first use too, and the model loads.
        val fresh = File(root, "other/texteller-int8")
        val source = TexTellerSource(fresh, BundledModel(context.assets, fresh))
        assertTrue(source.warmUp())
        source.invalidate()
    }

    @Test
    fun aModelAlreadyInTheFolderIsLeftAlone() {
        val bundled = BundledModel(context.assets, target)
        assumeTrue("this APK was built without the model", bundled.available)
        target.mkdirs()
        OnnxTexTellerModel.REQUIRED_FILES.forEach { File(target, it).writeText("the user's own") }

        assertFalse(bundled.installIfMissing())
        OnnxTexTellerModel.REQUIRED_FILES.forEach { assertEquals("the user's own", File(target, it).readText()) }
    }
}
