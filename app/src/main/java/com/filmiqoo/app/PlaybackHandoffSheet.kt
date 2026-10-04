package com.filmiqoo.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun PlaybackHandoffSheet(
    repository:PlaybackHandoffRepository,
    mediaVersionId:String,
    positionMs:Long,
    onDismiss:()->Unit
) {
    val context=LocalContext.current
    val castController=remember {
        FilmiqooCastController(context.applicationContext)
    }

    DisposableEffect(castController) {
        onDispose { castController.close() }
    }

    PlaybackDeviceHubSheet(
        castController=castController,
        repository=repository,
        authenticated=true,
        mediaVersionId=mediaVersionId,
        positionMs=positionMs,
        onRequireAuth={},
        onDismiss=onDismiss
    )
}
