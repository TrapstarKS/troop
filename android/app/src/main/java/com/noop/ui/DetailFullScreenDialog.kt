package com.noop.ui

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal fun DetailFullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        // Let the window supply measurement bounds. Compose 1.6 caps the alternate measurement
        // path at screenHeightDp, which excludes system bars even for an edge-to-edge window.
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val background = Palette.canvasBottom.toArgb()
        val light = Palette.isLight
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.attributes = window.attributes.apply {
                        setFitInsetsTypes(0)
                        setFitInsetsSides(0)
                    }
                }
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.setBackgroundDrawable(ColorDrawable(background))
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.navigationBarColor = background
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = light
            }
        }
        Box(Modifier.fillMaxSize().background(Palette.canvasGradient)
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            content()
        }
    }
}
