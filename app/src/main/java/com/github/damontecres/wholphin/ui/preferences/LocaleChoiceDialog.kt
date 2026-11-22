package com.github.damontecres.wholphin.ui.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopupContent
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.SelectedLeadingContent
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState
import java.util.Locale

@Composable
fun LocaleChoiceDialog(
    onDismissRequest: () -> Unit,
    viewModel: LocaleChoiceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(),
    ) {
        when (val st = state.loading) {
            is LoadingState.Error -> {
                ErrorMessage(st)
            }

            LoadingState.Loading,
            LoadingState.Pending,
            -> {
                LoadingPage()
            }

            LoadingState.Success -> {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
                val dialogItems =
                    remember(state.userLocale, state.availableLocales) {
                        val userLanguage = state.userLocale.toLanguageTag()
                        state.availableLocales.map { locale ->
                            val tag = locale.toLanguageTag()
                            DialogItem(
                                selected = tag == userLanguage,
                                leadingContent = {
                                    Box {
                                        SelectedLeadingContent(tag == userLanguage)
                                    }
                                },
                                headlineContent = {
                                    Text(locale.getDisplayName(locale))
                                },
                                supportingContent = {
                                    Text(locale.getDisplayName(Locale.ENGLISH))
                                },
                                dismissOnClick = true,
                                onClick = {
                                    viewModel.changeLocale(locale)
                                },
                            )
                        }
                    }
                DialogPopupContent(
                    title = stringResource(R.string.user_interface_language),
                    dialogItems = dialogItems,
                    waiting = false,
                    onDismissRequest = onDismissRequest,
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }
    }
}
