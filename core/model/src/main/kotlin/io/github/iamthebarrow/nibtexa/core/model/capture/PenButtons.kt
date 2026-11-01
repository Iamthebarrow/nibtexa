package io.github.iamthebarrow.nibtexa.core.model.capture

/** What a pen's button does while it is held as the pen touches the page. */
enum class PenButtonAction {
    ERASER,
    LASSO,
    MOVE,
    SCRATCH,
    NOTHING,
    ;

    companion object {
        /** The stored [name], or [default] if there is none or it isn't one any more. */
        fun named(name: String?, default: PenButtonAction): PenButtonAction = entries.firstOrNull { it.name == name } ?: default
    }
}

/** What each of a pen's buttons does. Many pens have only the main one. */
data class PenButtons(
    val main: PenButtonAction = PenButtonAction.ERASER,
    val second: PenButtonAction = PenButtonAction.LASSO,
) {
    /** What the buttons held now ask for, or null to use the current tool. With both held, the main one counts unless it does nothing. */
    fun held(mainHeld: Boolean, secondHeld: Boolean): PenButtonAction? = when {
        mainHeld && main != PenButtonAction.NOTHING -> main
        secondHeld && second != PenButtonAction.NOTHING -> second
        else -> null
    }
}
