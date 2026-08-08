/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.AppListenerService

@Composable
fun CameraControlRoute(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding =
        if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding =
        if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        CameraControlScreen(
            currentCameraAction = state.cameraAction,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            onSelect = { action ->
                if (action != null && !isAppListenerServiceEnabled(context)) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                viewModel.setCameraAction(action)
            }
        )
    }
}

@Composable
fun CameraControlScreen(
    currentCameraAction: StemPressType?,
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 16.dp,
    onSelect: (StemPressType?) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState)
            .padding(top = 8.dp)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Text(
            text = stringResource(R.string.camera_control_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        StyledList {
            StyledListItem(
                name = stringResource(R.string.off),
                selected = currentCameraAction == null,
                onClick = { onSelect(null) }
            )
            StyledListItem(
                name = stringResource(R.string.press_once),
                selected = currentCameraAction == StemPressType.SINGLE_PRESS,
                onClick = { onSelect(StemPressType.SINGLE_PRESS) }
            )
            StyledListItem(
                name = stringResource(R.string.press_and_hold_airpods),
                selected = currentCameraAction == StemPressType.LONG_PRESS,
                onClick = { onSelect(StemPressType.LONG_PRESS) }
            )
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

private fun isAppListenerServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices =
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val serviceComponent = ComponentName(context, AppListenerService::class.java)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == serviceComponent.packageName &&
            it.resolveInfo.serviceInfo.name == serviceComponent.className
    }
}
