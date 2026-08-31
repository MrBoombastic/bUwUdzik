package com.mrboombastic.buwudzik.ui.screens

internal fun normalizeAuthTokenInput(value: String): String =
    value.filterNot(Char::isWhitespace)

internal fun isValidAuthTokenInput(value: String): Boolean =
    normalizeAuthTokenInput(value).matches(Regex("^[0-9a-fA-F]{32}$"))
