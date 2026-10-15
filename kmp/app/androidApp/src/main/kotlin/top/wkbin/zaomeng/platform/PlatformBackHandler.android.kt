package top.wkbin.zaomeng.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
