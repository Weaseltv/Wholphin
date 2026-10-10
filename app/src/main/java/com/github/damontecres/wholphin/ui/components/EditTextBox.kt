package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextFieldDefaults.Container
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

/**
 * An input field for text customized for TV entry
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTextBox(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLines: Int = 1,
    isInputValid: (String) -> Boolean = { true },
    isPassword: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors =
        TextFieldDefaults.colors(
            unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
            errorTextColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    val textColor =
        when {
            !isInputValid(state.text.toString()) -> colors.errorTextColor
            focused -> colors.focusedTextColor
            !enabled -> colors.disabledTextColor
            !focused -> colors.unfocusedTextColor
            else -> colors.unfocusedTextColor
        }
    val lineLimits =
        remember(maxLines) {
            if (maxLines == 1) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine(
                    maxLines,
                    maxLines,
                )
            }
        }
    val neon = isWeaselTv()
    val decorator =
        remember(colors, focused, enabled, maxLines, isInputValid, leadingIcon, neon) {
            TextFieldDecorator { innerTextField: @Composable () -> Unit ->
                val containerColor =
                    when {
                        !isInputValid(state.text.toString()) -> colors.errorContainerColor
                        focused -> colors.focusedContainerColor
                        !enabled -> colors.disabledContainerColor
                        !focused -> colors.unfocusedContainerColor
                        else -> colors.unfocusedContainerColor
                    }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                containerColor,
                                shape =
                                    if (neon) {
                                        RectangleShape
                                    } else if (maxLines > 1) {
                                        RoundedCornerShape(8.dp)
                                    } else {
                                        CircleShape
                                    },
                            ).then(
                                if (neon) {
                                    Modifier.border(
                                        1.dp,
                                        if (!isInputValid(state.text.toString())) {
                                            MaterialTheme.colorScheme.error
                                        } else if (focused) {
                                            LocalNeonAccent.current
                                        } else {
                                            NeonBoard.Line2
                                        },
                                        RectangleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            ).padding(8.dp),
                ) {
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            leadingIcon?.invoke()
                            innerTextField.invoke()
                        }
                    }
                }
            }
        }

    if (isPassword) {
        BasicSecureTextField(
            state = state,
            modifier =
                modifier.defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                ),
            enabled = enabled,
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            textStyle = MaterialTheme.typography.bodyLarge.merge(textColor),
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor),
            decorator = decorator,
        )
    } else {
        BasicTextField(
            state = state,
            modifier =
                modifier.defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                ),
            enabled = enabled,
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            textStyle = MaterialTheme.typography.bodyLarge.merge(textColor),
            interactionSource = interactionSource,
            lineLimits = lineLimits,
            cursorBrush = SolidColor(colors.cursorColor),
            decorator = decorator,
        )
    }
}

/**
 * And [EditTextBox] styles for searches
 */
@Composable
fun SearchEditTextBox(
    state: TextFieldState,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    EditTextBox(
        state = state,
        modifier = modifier.searchDpadNavigation(readOnly),
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        onKeyboardAction = {
            onSearchClick.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                tint = if (isWeaselTv()) LocalNeonAccent.current else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        enabled = enabled,
        readOnly = readOnly,
        interactionSource = interactionSource,
    )
}

/**
 * A modified [BasicTextField] that looks & fits better with TV material controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    height: Dp = 40.dp,
    isInputValid: (String) -> Boolean = { true },
    supportingText: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors =
        TextFieldDefaults.colors(
            unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
            errorTextColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    val focused by interactionSource.collectIsFocusedAsState()
    val neon = isWeaselTv()
    val valid = isInputValid(value)
    val shape = if (neon) RectangleShape else CircleShape
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            modifier = modifier.defaultMinSize(minWidth = TextFieldDefaults.MinWidth, minHeight = height),
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodyLarge.merge(MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(colors.cursorColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            singleLine = true,
            visualTransformation =
                if (keyboardOptions.keyboardType == KeyboardType.Password ||
                    keyboardOptions.keyboardType == KeyboardType.NumberPassword
                ) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            decorationBox = { innerTextField ->
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = height)
                                .background(
                                    if (!valid) {
                                        colors.errorContainerColor
                                    } else if (!enabled) {
                                        colors.disabledContainerColor
                                    } else if (focused) {
                                        colors.focusedContainerColor
                                    } else {
                                        colors.unfocusedContainerColor
                                    },
                                    shape,
                                ).then(
                                    if (neon) {
                                        Modifier.border(
                                            1.dp,
                                            if (!valid) {
                                                MaterialTheme.colorScheme.error
                                            } else if (focused) {
                                                LocalNeonAccent.current
                                            } else {
                                                NeonBoard.Line2
                                            },
                                            shape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        leadingIcon?.invoke()
                        Box(Modifier.weight(1f, fill = false)) {
                            if (value.isEmpty()) placeholder?.invoke()
                            innerTextField()
                        }
                    }
                    supportingText?.invoke()
                }
            },
        )
    }
}

/**
 * And [EditTextBox] styles for searches
 */
@Composable
fun SearchEditTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    // Neon Board: the search strip is 56dp with the volt glyph.
    height: Dp = if (isWeaselTv()) 56.dp else 40.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    EditTextBox(
        value,
        onValueChange,
        modifier.searchDpadNavigation(readOnly),
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        keyboardActions =
            KeyboardActions(
                onSearch = {
                    onSearchClick.invoke()
                    this.defaultKeyboardAction(ImeAction.Search)
                },
            ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                tint = if (isWeaselTv()) LocalNeonAccent.current else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        enabled = enabled,
        readOnly = readOnly,
        height = height,
        interactionSource = interactionSource,
    )
}

/** Search fields navigate to results vertically, and to adjacent controls horizontally when not editing. */
@Composable
private fun Modifier.searchDpadNavigation(readOnly: Boolean): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                Key.DirectionLeft -> readOnly && focusManager.moveFocus(FocusDirection.Left)
                Key.DirectionRight -> readOnly && focusManager.moveFocus(FocusDirection.Right)
                else -> false
            }
        } else {
            false
        }
    }
}

@PreviewTvSpec
@Composable
private fun EditTextBoxPreview() {
    WholphinTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditTextBox(
                state = rememberTextFieldState("string"),
            )
            EditTextBox(
                value = "string",
                onValueChange = {},
            )
            SearchEditTextBox(
                state = rememberTextFieldState("search query"),
                onSearchClick = { },
            )
            EditTextBox(
                state = rememberTextFieldState("string\nstring\nstring"),
                maxLines = 5,
//                height = 88.dp,
            )
            EditTextBox(
                state = rememberTextFieldState("search"),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
                isInputValid = { true },
                modifier = Modifier.width(280.dp),
            )
            EditTextBox(
                state = rememberTextFieldState("password"),
                isPassword = true,
            )
        }
    }
}
