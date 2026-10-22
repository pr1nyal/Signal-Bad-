package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.VoiceCallScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.LiveCallViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: LiveCallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            var showSplash by rememberSaveable { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(1400L)
                showSplash = false
            }

            LaunchedEffect(settings.wakeLock) {
                if (settings.wakeLock) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(settings.isDarkTheme) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !settings.isDarkTheme
                controller.isAppearanceLightNavigationBars = !settings.isDarkTheme
            }

            MyApplicationTheme(darkTheme = settings.isDarkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    VoiceCallScreen(viewModel = viewModel)

                    AnimatedVisibility(
                        visible = showSplash,
                        exit = fadeOut(animationSpec = tween(durationMillis = 450))
                    ) {
                        AppStartupSplashScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppStartupSplashScreen() {
    var startAnim by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        startAnim = true
    }
    val scale by animateFloatAsState(
        targetValue = if (startAnim) 1.0f else 0.88f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "splash_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_app_emblem),
                contentDescription = "Gemini Live Icon",
                modifier = Modifier
                    .size(168.dp)
                    .scale(scale)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "GEMINI LIVE",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
