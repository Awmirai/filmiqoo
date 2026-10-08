package com.filmiqoo.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** No context, player or credentials: retain only navigation values across activity recreation. */
class CinemaNavigationState:ViewModel() {
    val overlay=mutableStateOf<OverlayRoute?>(null)
    val backStack=mutableStateListOf<OverlayRoute>()
}
