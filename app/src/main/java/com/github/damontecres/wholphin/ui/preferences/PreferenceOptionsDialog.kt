package com.github.damontecres.wholphin.ui.preferences

import android.view.Gravity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.BasicDialog

/** Music and guide options share the same bounded, naturally measured side panel. */
@Composable
fun PreferenceOptionsDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let {
            it.setGravity(Gravity.END)
            it.setDimAmount(0f)
        }
        Column(Modifier.width(320.dp).heightIn(max = 480.dp)) {
            PreferencePanelHeader(stringResource(R.string.view_options))
            content()
        }
    }
}
