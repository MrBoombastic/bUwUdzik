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
    devices: List<DeviceProfile>,
    dashboardScroll: ScrollState,
    sheetOverscrollThresholdPx: Float,
    onOpenDeviceSheet: () -> Unit,
): NestedScrollConnection {
    val openSheetLatest = rememberUpdatedState(onOpenDeviceSheet)
    // Increase threshold significantly (e.g. 1.5x) to prevent accidental triggers
    val effectiveThreshold = sheetOverscrollThresholdPx * 1.5f

    return remember<NestedScrollConnection>(
        activeDevice,
        devices.size,
        effectiveThreshold,
        dashboardScroll
    ) {
        val homeOverscrollAccum = mutableFloatStateOf(0f)
        // If the user was already scrolling the content in this gesture, 
        // we ignore any subsequent overscroll until they lift their finger.
        var ignoreUntilNextGesture = false

        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // If we consumed any upward scroll in this gesture, it was a "normal" scroll.
                // Block overscroll accumulation for the remainder of this gesture.
                if (consumed.y < 0f) {
                    ignoreUntilNextGesture = true
                    homeOverscrollAccum.floatValue = 0f
                }

                if (ignoreUntilNextGesture || activeDevice == null) {
                    homeOverscrollAccum.floatValue = 0f
                    return Offset.Zero
                }

                // Standard checks (already at bottom, dragging up)
                if (dashboardScroll.canScrollForward || available.y > 0f) {
                    homeOverscrollAccum.floatValue = 0f
                    return Offset.Zero
                }

                if (available.y < 0f && source == NestedScrollSource.UserInput) {
                    homeOverscrollAccum.floatValue += -available.y
                    if (homeOverscrollAccum.floatValue >= effectiveThreshold) {
                        homeOverscrollAccum.floatValue = 0f
                        ignoreUntilNextGesture = true // Prevent double triggers
                        openSheetLatest.value.invoke()
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                // Gesture ended, reset everything for the next one
                homeOverscrollAccum.floatValue = 0f
                ignoreUntilNextGesture = false
                return super.onPreFling(available)
            }
        }
    }
}
