package com.github.damontecres.wholphin.leanback.widget.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the old `RequestUiState` learned from the shared controls, for the shell's request chips (no-Compose UI):
 * how many CONTROLS dialogs are open ([openDialogs], the old `requestUi.dialogs`) and whether an editable text field
 * has focus ([textInputActive], the old `trackRequestTextInput`). The CONTROLS dialogs and [EditTextBox] report
 * themselves; the shell observes.
 */
object ControlTrackers {
    private val dialogs = MutableStateFlow(0)
    private val fields = MutableStateFlow<Set<Any>>(emptySet())

    /** Number of open CONTROLS dialogs */
    val openDialogs: StateFlow<Int> = dialogs.asStateFlow()

    private val textActive = MutableStateFlow(false)

    /** True while an editable text field has focus */
    val textInputActive: StateFlow<Boolean> = textActive.asStateFlow()

    fun dialogOpened() = dialogs.update { it + 1 }

    fun dialogClosed() = dialogs.update { (it - 1).coerceAtLeast(0) }

    /** A text field ([token]) gained or lost focus while editable */
    fun textField(
        token: Any,
        focusedAndEditable: Boolean,
    ) {
        fields.update { if (focusedAndEditable) it + token else it - token }
        textActive.value = fields.value.isNotEmpty()
    }
}
