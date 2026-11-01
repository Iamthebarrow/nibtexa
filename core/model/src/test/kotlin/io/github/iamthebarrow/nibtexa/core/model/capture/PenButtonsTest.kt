package io.github.iamthebarrow.nibtexa.core.model.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenButtonsTest {
    @Test
    fun `no button held uses the current tool`() {
        assertNull(PenButtons().held(mainHeld = false, secondHeld = false))
    }

    @Test
    fun `by default the main button erases and the second selects`() {
        assertEquals(PenButtonAction.ERASER, PenButtons().held(mainHeld = true, secondHeld = false))
        assertEquals(PenButtonAction.LASSO, PenButtons().held(mainHeld = false, secondHeld = true))
    }

    @Test
    fun `with both held the main button counts`() {
        assertEquals(PenButtonAction.ERASER, PenButtons().held(mainHeld = true, secondHeld = true))
    }

    @Test
    fun `a button set to nothing leaves the tool or the other button`() {
        val buttons = PenButtons(main = PenButtonAction.NOTHING, second = PenButtonAction.MOVE)
        assertNull(buttons.held(mainHeld = true, secondHeld = false))
        assertEquals(PenButtonAction.MOVE, buttons.held(mainHeld = true, secondHeld = true))
        assertNull(PenButtons(second = PenButtonAction.NOTHING).held(mainHeld = false, secondHeld = true))
    }

    @Test
    fun `a stored name that is missing or unknown falls back`() {
        assertEquals(PenButtonAction.SCRATCH, PenButtonAction.named("SCRATCH", PenButtonAction.ERASER))
        assertEquals(PenButtonAction.ERASER, PenButtonAction.named(null, PenButtonAction.ERASER))
        assertEquals(PenButtonAction.LASSO, PenButtonAction.named("GONE", PenButtonAction.LASSO))
    }
}
