package me.vibb.music.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.vibb.music.R
import me.vibb.music.logic.SPLASH_FADE_MS
import me.vibb.music.logic.SPLASH_MARK_DP
import me.vibb.music.logic.SPLASH_WORDMARK_BELOW_DP

/**
 * The app's own launch splash (logic/Splash.kt): the system splash's night ground and its mark at its place and size -
 * the whole window, under the bars, centred as Android centres the splash icon - and "vibb music" under the rings. It
 * covers the screens until [contentShown], then fades over them and is gone. Decorative: nothing for TalkBack.
 */
@Composable
fun LaunchSplash(contentShown: Boolean) {
    val alpha by animateFloatAsState(if (contentShown) 0f else 1f, tween(SPLASH_FADE_MS.toInt()), label = "splash")
    if (alpha == 0f) return
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(colorResource(R.color.vibb_night))) {
        Image(painterResource(R.drawable.splash_vibb_mark), null, Modifier.align(Alignment.Center).requiredSize(SPLASH_MARK_DP.dp))
        Image(painterResource(R.drawable.splash_wordmark), null, Modifier.align(Alignment.Center).offset(y = SPLASH_WORDMARK_BELOW_DP.dp))
    }
}
