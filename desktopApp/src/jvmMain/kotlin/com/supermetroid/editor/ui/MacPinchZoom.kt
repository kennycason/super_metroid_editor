package com.supermetroid.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Window
import javax.swing.JComponent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

private val macPinchZoomLog = KotlinLogging.logger {}

/**
 * CompositionLocal for the AWT/Swing window so we can attach macOS magnification (pinch) listener.
 */
val LocalSwingWindow = staticCompositionLocalOf<Window?> { null }

private fun isMacOs(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("mac")

internal fun zoomAfterMagnification(
    currentZoom: Float,
    magnification: Double,
    minZoom: Float,
    maxZoom: Float,
): Float {
    val scale = 1.0 + magnification
    if (scale <= 0.0 || scale.isNaN() || scale.isInfinite()) {
        return currentZoom.coerceIn(minZoom, maxZoom)
    }
    return (currentZoom * scale.toFloat()).coerceIn(minZoom, maxZoom)
}

/**
 * Attaches macOS trackpad pinch-to-zoom (magnification): pinch out = zoom in, pinch in = zoom out (like iPhone camera).
 * No-op on non-Mac or if Apple API isn't available. Requires JVM args:
 * --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED
 * --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED
 */
@Composable
fun AttachMacPinchZoom(
    awtWindow: Window?,
    zoomState: MutableState<Float>,
    minZoom: Float = 0.25f,
    maxZoom: Float = 4f
) {
    if (!isMacOs() || awtWindow == null) return
    val rootPane: JComponent = (awtWindow as? RootPaneContainer)?.rootPane ?: return

    DisposableEffect(awtWindow, zoomState) {
        var listener: Any?
        try {
            @Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
            val listenerClass = Class.forName("com.apple.eawt.event.MagnificationListener")
            listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "magnify" && args != null && args.isNotEmpty()) {
                    val event = args[0]
                    val magnification = event.javaClass.getMethod("getMagnification").invoke(event) as Double
                    SwingUtilities.invokeLater {
                        zoomState.value = zoomAfterMagnification(
                            currentZoom = zoomState.value,
                            magnification = magnification,
                            minZoom = minZoom,
                            maxZoom = maxZoom
                        )
                    }
                }
                null
            }
            val gestureUtils = Class.forName("com.apple.eawt.event.GestureUtilities")
            val gestureListenerClass = Class.forName("com.apple.eawt.event.GestureListener")
            val addMethod = gestureUtils.getMethod("addGestureListenerTo", JComponent::class.java, gestureListenerClass)
            addMethod.invoke(null, rootPane, listener)
            macPinchZoomLog.info { "Attached macOS pinch zoom listener to ${rootPane.javaClass.name}" }
        } catch (_: ClassNotFoundException) {
            listener = null
            macPinchZoomLog.info { "macOS pinch zoom API is not available on this JVM" }
        } catch (e: Exception) {
            listener = null
            macPinchZoomLog.warn(e) { "Failed to attach macOS pinch zoom listener" }
        }
        onDispose {
            if (listener != null) {
                try {
                    val gestureUtils = Class.forName("com.apple.eawt.event.GestureUtilities")
                    val gestureListenerClass = Class.forName("com.apple.eawt.event.GestureListener")
                    val removeMethod = gestureUtils.getMethod("removeGestureListenerFrom", JComponent::class.java, gestureListenerClass)
                    removeMethod.invoke(null, rootPane, listener)
                } catch (_: Exception) { }
            }
        }
    }
}
