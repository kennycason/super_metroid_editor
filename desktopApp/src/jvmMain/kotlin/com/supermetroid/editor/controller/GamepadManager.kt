package com.supermetroid.editor.controller

import com.studiohartman.jamepad.ControllerManager
import com.studiohartman.jamepad.ControllerState
import com.supermetroid.editor.libretro.LibretroConstants
import io.github.oshai.kotlinlogging.KotlinLogging

private val gamepadLog = KotlinLogging.logger {}

/**
 * Manages gamepad input via Jamepad (SDL2).
 *
 * Designed to be polled each frame from [EmulatorWorkspaceState.currentAction].
 * Maps SDL GameController buttons to SNES libretro joypad indices.
 *
 * SNES button layout (physical):
 *   ┌─────────────────────────┐
 *   │  L                   R  │
 *   │       ┌─┐     X         │
 *   │    ←──┼─┼──→ Y   A     │
 *   │       └─┘     B         │
 *   │   Select  Start         │
 *   └─────────────────────────┘
 *
 * SDL uses Xbox-style ABXY naming. For a Bluetooth SNES controller:
 *   SDL A → SNES B (east/right face button)
 *   SDL B → SNES A (south face button)
 *   SDL X → SNES Y (north face button)
 *   SDL Y → SNES X (west face button)
 *
 * However, SDL's GameController DB remaps controllers so that
 * SDL button names match *position* (A=south, B=east, X=west, Y=north).
 * For an SNES controller via Bluetooth, the DB should handle the mapping
 * so that pressing the SNES "B" button (bottom) reports as SDL "A" (south).
 *
 * We map to libretro's SNES layout:
 *   Index 0 = B      (SDL A = south)
 *   Index 1 = Y      (SDL X = west)
 *   Index 2 = Select (SDL Back)
 *   Index 3 = Start  (SDL Start)
 *   Index 4 = Up     (D-pad or left stick)
 *   Index 5 = Down
 *   Index 6 = Left
 *   Index 7 = Right
 *   Index 8 = A      (SDL B = east)
 *   Index 9 = X      (SDL Y = north)
 *   Index 10 = L     (SDL LB)
 *   Index 11 = R     (SDL RB)
 */
class GamepadManager {

    private var manager: ControllerManager? = null
    private var initialized = false
    private var disabled = false
    private var consecutiveErrors = 0

    /** Name of the connected controller, or null. */
    var controllerName: String? = null
        private set

    /** True if a gamepad is currently connected. */
    var isConnected: Boolean = false
        private set

    /** Latest status event message (connect/disconnect). Consumed by reading and clearing. */
    var statusEvent: String? = null

    /** Analog stick deadzone threshold. */
    private val stickDeadzone = 0.4f

    fun init() {
        if (initialized || disabled) return
        try {
            val mgr = ControllerManager()
            mgr.initSDLGamepad()
            manager = mgr
            initialized = true
            consecutiveErrors = 0
        } catch (e: Throwable) {
            gamepadLog.warn(e) { "[GamepadManager] Failed to init SDL gamepad: ${e.message}" }
            statusEvent = "Gamepad init failed: ${e.message}"
            disabled = true
        }
    }

    /**
     * Poll the first connected gamepad and return a 12-element SNES button array.
     * Returns null if no gamepad is connected or not initialized.
     * Must be called from the main/UI thread or a consistent polling thread.
     */
    fun poll(): List<Int>? {
        if (disabled) return null
        val mgr = manager ?: return null
        try {
            return pollInternal(mgr)
        } catch (e: Throwable) {
            consecutiveErrors++
            gamepadLog.warn(e) { "[GamepadManager] poll error #$consecutiveErrors: ${e.message}" }
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                gamepadLog.warn { "[GamepadManager] Too many errors, disabling gamepad support" }
                statusEvent = "Gamepad disabled due to repeated errors"
                disabled = true
                safeClose()
            }
            return null
        }
    }

    private fun pollInternal(mgr: ControllerManager): List<Int>? {
        mgr.update()
        consecutiveErrors = 0  // reset on successful update

        val state: ControllerState = mgr.getState(0)
        if (!state.isConnected) {
            if (isConnected) {
                val prevName = controllerName
                isConnected = false
                controllerName = null
                statusEvent = "Controller disconnected: $prevName"
                gamepadLog.info { "[GamepadManager] $statusEvent" }
            }
            return null
        }

        if (!isConnected) {
            isConnected = true
            controllerName = state.controllerType ?: "Gamepad"
            statusEvent = "Controller connected: $controllerName"
            gamepadLog.info { "[GamepadManager] $statusEvent" }
        }

        val buttons = MutableList(12) { 0 }

        // D-pad (buttons)
        if (state.dpadUp) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_UP] = 1
        if (state.dpadDown) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_DOWN] = 1
        if (state.dpadLeft) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_LEFT] = 1
        if (state.dpadRight) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_RIGHT] = 1

        // Left analog stick → D-pad (with deadzone)
        if (state.leftStickY < -stickDeadzone) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_UP] = 1
        if (state.leftStickY > stickDeadzone) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_DOWN] = 1
        if (state.leftStickX < -stickDeadzone) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_LEFT] = 1
        if (state.leftStickX > stickDeadzone) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_RIGHT] = 1

        // Face buttons: SDL → SNES (SNES BT controllers report A/B/X/Y directly)
        if (state.a) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_A] = 1
        if (state.b) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_B] = 1
        if (state.x) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_X] = 1
        if (state.y) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_Y] = 1

        // Shoulders
        if (state.lb) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_L] = 1
        if (state.rb) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_R] = 1

        // Start / Select
        if (state.start) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_START] = 1
        if (state.back) buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_SELECT] = 1

        // Cancel opposing D-pad directions
        if (buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_LEFT] == 1 &&
            buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_RIGHT] == 1) {
            buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_LEFT] = 0
            buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_RIGHT] = 0
        }
        if (buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_UP] == 1 &&
            buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_DOWN] == 1) {
            buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_UP] = 0
            buttons[LibretroConstants.RETRO_DEVICE_ID_JOYPAD_DOWN] = 0
        }

        return buttons
    }

    fun close() {
        safeClose()
    }

    private fun safeClose() {
        try {
            manager?.quitSDLGamepad()
        } catch (e: Throwable) {
            gamepadLog.warn(e) { "[GamepadManager] Error during close: ${e.message}" }
        }
        manager = null
        initialized = false
        isConnected = false
        controllerName = null
    }

    companion object {
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }
}
