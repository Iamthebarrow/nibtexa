package io.github.iamthebarrow.nibtexa.ink

import android.graphics.Matrix
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView

/**
 * Converts between page units and screen pixels for a [PageView] (Phase 17). Every layer of
 * the surface draws through it, and every touch is read through it, so zooming and moving the
 * page never changes the ink.
 */
internal class PageTransform(private val density: Float) {
    var view: PageView = PageView()
        private set

    /** Screen pixels per page unit. */
    val pixelsPerUnit: Float get() = density * view.scale

    /** Page units to screen pixels, for canvases. */
    val matrix = Matrix()

    fun set(value: PageView) {
        view = value
        matrix.setTranslate(-value.left, -value.top)
        matrix.postScale(pixelsPerUnit, pixelsPerUnit)
    }

    init {
        set(PageView())
    }

    fun pageX(px: Float): Float = px / pixelsPerUnit + view.left
    fun pageY(px: Float): Float = px / pixelsPerUnit + view.top
    fun screenX(x: Float): Float = (x - view.left) * pixelsPerUnit
    fun screenY(y: Float): Float = (y - view.top) * pixelsPerUnit
}
