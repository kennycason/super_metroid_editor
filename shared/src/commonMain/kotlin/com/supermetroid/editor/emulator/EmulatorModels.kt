package com.supermetroid.editor.emulator

import kotlinx.serialization.Serializable

@Serializable
data class EmulatorCapabilities(
    val backendName: String,
    val supportsFrames: Boolean = true,
    val supportsMemoryAccess: Boolean = false,
    val supportsSaveStates: Boolean = true,
    val supportsMultiPlayer: Boolean = false,
    val supportsRecording: Boolean = false,
    val supportsAgentControl: Boolean = false,
    /** True only for a core exposing SMEDIT's versioned instruction-debug ABI. */
    val supportsCpuDebugging: Boolean = false,
    val cpuDebugApiVersion: Int? = null,
)

@Serializable
enum class DebugHaltReason {
    NONE,
    STEP,
    BREAKPOINT,
    INSTRUCTION_LIMIT,
    FRAME_LIMIT,
    CPU_STOPPED,
    INVALID_REQUEST,
}

@Serializable
data class CpuDebugState(
    val pc: Int,
    val a: Int,
    val x: Int,
    val y: Int,
    val d: Int,
    val db: Int,
    val pb: Int,
    val s: Int,
    val p: Int,
    val memoryWidth: Int,
    val indexWidth: Int,
    val emulation: Boolean,
    val waitingForInterrupt: Boolean,
    val halted: Boolean,
    val cycles: Int,
    val verticalCounter: Int,
    val coreFrame: Long,
    val instructions: Long,
) {
    val carry: Boolean get() = p and 0x01 != 0
    val zero: Boolean get() = p and 0x02 != 0
    val irqDisabled: Boolean get() = p and 0x04 != 0
    val decimal: Boolean get() = p and 0x08 != 0
    val overflow: Boolean get() = p and 0x40 != 0
    val negative: Boolean get() = p and 0x80 != 0
}

@Serializable
data class DebugRunRequest(
    val addresses: List<Int> = emptyList(),
    val instructionLimit: Long = 1_000_000,
    val frameLimit: Int = 1,
    val input: EmulatorInput = EmulatorInput(),
)

@Serializable
data class DebugExecutionResult(
    val haltReason: DebugHaltReason,
    val cpu: CpuDebugState,
    val snapshot: GameSnapshot = GameSnapshot(),
)

@Serializable
data class SessionConfig(
    val romPath: String? = null,
    val stateName: String? = null,
    val playerCount: Int = 1,
    val navExportDir: String? = null,
    val controlMode: String = "manual",
    val selectedModel: String? = null,
)

@Serializable
data class SessionState(
    val active: Boolean = false,
    val paused: Boolean = true,
    val currentState: String? = null,
    val frameCounter: Int = 0,
    val recording: Boolean = false,
    val controlMode: String = "manual",
    val selectedModel: String? = null,
    val playerCount: Int = 1,
)

@Serializable
data class EmulatorInput(
    val buttons: List<Int> = List(12) { 0 },
    val repeat: Int = 1,
    val includeFrame: Boolean = true,
    val includeTrace: Boolean = true,
)

@Serializable
data class StepResult(
    val session: SessionState = SessionState(),
    val snapshot: GameSnapshot = GameSnapshot(),
    val states: List<StateInfo> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val recordingPath: String? = null,
    val message: String? = null,
)

@Serializable
data class GameSnapshot(
    val frameCounter: Int = 0,
    val playerCount: Int = 1,
    val roomId: Int? = null,
    val roomName: String? = null,
    val areaName: String? = null,
    val gameState: Int? = null,
    val health: Int? = null,
    val samusX: Int? = null,
    val samusY: Int? = null,
    val collectedItems: Int = 0,       // 0x7E09A4 bit flags
    val collectedBeams: Int = 0,       // 0x7E09A8 bit flags
    val maxHealth: Int? = null,
    val missiles: Int? = null,
    val maxMissiles: Int? = null,
    val superMissiles: Int? = null,
    val maxSuperMissiles: Int? = null,
    val powerBombs: Int? = null,
    val maxPowerBombs: Int? = null,
    val reserveEnergy: Int? = null,
    val maxReserveEnergy: Int? = null,
    val doorTransition: Boolean = false,
    val terminated: Boolean = false,
    val truncated: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameRgb24Base64: String? = null,
    val traceIncluded: Boolean = true,
    val controllerConnected: Boolean = false,
    val controllerName: String? = null,
    val lastAction: List<Int> = emptyList(),
    val lastRequestedAction: List<Int> = emptyList(),
    val lastActionPreSanitize: List<Int> = emptyList(),
    val lastActionSource: String = "manual",
    val lastModelActionIndex: Int? = null,
    val expectedTraceLabel: String? = null,
    val expectedTraceSource: String? = null,
    val pathProgress: Float = 0f,
    val pathProgressMax: Float = 0f,
    val pathCompletion: Float = 0f,
    val pathErrorPx: Float? = null,
    val pathBestErrorPx: Float? = null,
    val routeCompleted: Boolean = false,
    val recordedFrames: Int = 0,
    val expectedTrace: List<TracePoint> = emptyList(),
    val trace: List<TracePoint> = emptyList(),
)

@Serializable
data class TracePoint(
    val frame: Int,
    val roomId: Int,
    val x: Int,
    val y: Int,
)

@Serializable
data class StateInfo(
    val name: String,
    val path: String,
)

@Serializable
data class ModelInfo(
    val name: String,
    val path: String,
    val format: String,
)
