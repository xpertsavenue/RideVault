package io.github.xpertsavenue.ridevault.app

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Text mit Platzhaltern, der erst in der Oberfläche in der aktuellen Sprache aufgelöst wird. */
data class UiMsg(@StringRes val res: Int, val args: List<Any> = emptyList())

@Composable
fun UiMsg.text(): String = stringResource(res, *args.toTypedArray())
