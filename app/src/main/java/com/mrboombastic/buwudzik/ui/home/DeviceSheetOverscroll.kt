package com.mrboombastic.buwudzik.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.mrboombastic.buwudzik.data.DeviceProfile

/**
 * Overscroll at the bottom of the home dashboard opens the device switcher sheet.
 * [available].y < 0 is upward overscroll past the scroll end.
 */
@Composable
fun rememberDeviceSheetOverscrollConnection(
    activeDevice: DeviceProfile?,
    dashboardScroll: ScrollState,
    sheetOverscrollThresholdPx: Float,
    onOpenDeviceSheet: () -> Unit,
): NestedScrollConnection {
    val homeOverscrollAcc = remember { mutableFloatStateOf(0f) }
    val openSheetLatest = rememberUpdatedState(onOpenDeviceSheet)
    return remember(activeDevice, sheetOverscrollThresholdPx, dashboardScroll) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (activeDevice == null) {
                    homeOverscrollAcc.floatValue = 0f
                    return Offset.Zero
                }
                if (dashboardScroll.maxValue <= 0) {
                    homeOverscrollAcc.floatValue = 0f
                    return Offset.Zero
                }
                if (dashboardScroll.canScrollForward) {
                    homeOverscrollAcc.floatValue = 0f
                    return Offset.Zero
                }
                if (available.y < 0f) {
                    homeOverscrollAcc.floatValue += -available.y
                    if (homeOverscrollAcc.floatValue >= sheetOverscrollThresholdPx) {
                        homeOverscrollAcc.floatValue = 0f
                        openSheetLatest.value.invoke()
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
        }
    }
}
