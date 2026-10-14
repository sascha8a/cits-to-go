package org.opentrafficmap.citstogo.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.opentrafficmap.citstogo.IntersectionRenderer
import org.opentrafficmap.citstogo.intersection.IntersectionKey
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.srem.SremProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercise real Material drawer arbitration, not just isolated slider value changes. */
class DrawerGestureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var drawer: DrawerState
    private val position = mutableStateOf(0f)
    private var finished = 0

    private fun content(child: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                drawer = rememberDrawerState(DrawerValue.Closed)
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = { ModalDrawerSheet { Text("Menu") } },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(120.dp).testTag("plain"))
                        child()
                    }
                }
            }
        }
    }

    @Composable
    private fun slider(enabled: Boolean, direction: DragConfirmDirection = DragConfirmDirection.LeftToRight) {
        DragConfirmSlider(
            position = position.value,
            onPositionChange = { position.value = it },
            enabled = enabled,
            direction = direction,
            trackColor = Color.Gray,
            fillColor = Color.Red,
            modifier = Modifier.fillMaxWidth().height(96.dp).testTag("slider"),
            onDragFinished = { finished++ },
            drawThumbContent = { _, _ -> },
        )
    }

    private fun swipeRight(tag: String, startFraction: Float = 0.02f) {
        compose.onNodeWithTag(tag).performTouchInput {
            swipe(Offset(width * startFraction, height / 2f), Offset(width * 0.98f, height / 2f), 500)
        }
        compose.waitForIdle()
    }

    private fun assertClosed() = compose.runOnIdle { assertTrue(drawer.isClosed) }

    @Test fun ordinaryContentStillOpensDrawer() {
        content { slider(true) }
        swipeRight("plain")
        compose.runOnIdle { assertTrue(drawer.isOpen) }
    }

    @Test fun enabledSliderMovesAndFinishesWithoutOpeningDrawer() {
        content { slider(true) }
        swipeRight("slider")
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertEquals(1f, position.value, 0.001f)
            assertEquals(1, finished)
        }
    }

    @Test fun disabledSliderReservesEntireTrackWithoutMovingOrFinishing() {
        content { slider(false) }
        swipeRight("slider")
        swipeRight("slider", 0.5f)
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertEquals(0f, position.value, 0f)
            assertEquals(0, finished)
        }
        swipeRight("plain")
        compose.runOnIdle { assertTrue(drawer.isOpen) }
    }

    @Test fun offThumbSwipeIsBlockedWithoutConfirming() {
        content { slider(true) }
        swipeRight("slider", 0.5f)
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertEquals(0f, position.value, 0f)
            assertEquals(0, finished)
        }
    }

    @Test fun disablingSliderDuringDragDoesNotReleaseGestureToDrawer() {
        val enabled = mutableStateOf(true)
        content { slider(enabled.value) }
        compose.onNodeWithTag("slider").performTouchInput {
            down(Offset(width * 0.02f, height / 2f))
            moveTo(Offset(width * 0.25f, height / 2f), 100)
        }
        compose.runOnIdle { enabled.value = false }
        compose.waitForIdle()
        val stoppedPosition = position.value
        compose.onNodeWithTag("slider").performTouchInput {
            moveTo(Offset(width * 0.98f, height / 2f), 400)
            up()
        }
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertEquals(stoppedPosition, position.value, 0f)
            assertEquals(0, finished)
        }
    }

    @Test fun rightToLeftSliderWorksInsidePager() {
        content {
            HorizontalPager(rememberPagerState(pageCount = { 2 })) {
                slider(true, DragConfirmDirection.RightToLeft)
            }
        }
        compose.onNodeWithTag("slider").performTouchInput {
            swipe(Offset(width * 0.98f, height / 2f), Offset(width * 0.02f, height / 2f), 500)
        }
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertEquals(1f, position.value, 0.001f)
            assertEquals(1, finished)
        }
    }

    @Test fun verticalSwipeOnDisabledSliderStillScrollsPage() {
        checkVerticalScroll(false)
    }

    @Test fun verticalSwipeOnEnabledSliderStillScrollsPage() {
        checkVerticalScroll(true)
    }

    private fun checkVerticalScroll(enabled: Boolean) {
        val scroll = androidx.compose.foundation.ScrollState(0)
        content {
            Column(Modifier.height(250.dp).verticalScroll(scroll)) {
                slider(enabled)
                Box(Modifier.height(800.dp))
            }
        }
        compose.onNodeWithTag("slider").performTouchInput {
            swipe(Offset(width / 2f, height * 0.9f), Offset(width / 2f, height * 0.1f), 500)
        }
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertTrue(scroll.value > 0)
        }
    }

    @Test fun pagerBoundaryDoesNotOpenDrawer() {
        content {
            HorizontalPager(rememberPagerState(pageCount = { 2 }), Modifier.testTag("pager")) {
                Box(Modifier.fillMaxWidth().height(120.dp))
            }
        }
        swipeRight("pager")
        assertClosed()
    }

    @Test fun horizontalTableBoundaryDoesNotOpenDrawer() {
        content {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("table")) {
                Box(Modifier.size(1000.dp, 120.dp))
            }
        }
        swipeRight("table")
        assertClosed()
    }

    @Test fun resizeHandleWinsOverDrawer() {
        var distance = 0f
        content {
            Box(Modifier.fillMaxWidth().height(40.dp).testTag("handle").pointerInput(Unit) {
                detectHorizontalDragGestures { _, delta -> distance += delta }
            })
        }
        swipeRight("handle")
        compose.runOnIdle {
            assertTrue(drawer.isClosed)
            assertTrue(distance > 0f)
        }
    }

    @Test fun actualIntersectionMapConsumesHorizontalSwipes() {
        val map = MapIntersection(
            key = IntersectionKey(null, 1), name = "Test", revision = 1,
            latitude = 0, longitude = 0, laneWidthCm = null,
            lanes = emptyList(), receivedAtMs = 0,
        )
        content {
            IntersectionRenderer(
                map = map, spat = null, currentPosition = null,
                sremProfile = SremProfile.PEDESTRIAN, selectedCrosswalkLaneIds = emptyList(),
                onSelectionChange = {}, modifier = Modifier.testTag("map"),
            )
        }
        swipeRight("map")
        assertClosed()
    }
}
