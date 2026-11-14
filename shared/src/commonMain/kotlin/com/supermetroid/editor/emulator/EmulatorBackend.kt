package com.supermetroid.editor.emulator

import java.io.Closeable

interface EmulatorBackend : Closeable {
    val name: String
    val isConnected: Boolean
    suspend fun connect(): EmulatorCapabilities
    suspend fun disconnect()
    suspend fun startSession(config: SessionConfig): StepResult
    suspend fun closeSession(): StepResult
    suspend fun step(input: EmulatorInput): StepResult
    suspend fun snapshot(): GameSnapshot
    suspend fun saveState(name: String)
    suspend fun loadState(name: String): StepResult
    suspend fun listStates(): List<StateInfo>
    suspend fun readMemory(address: Int, size: Int): ByteArray
    suspend fun writeMemory(address: Int, data: ByteArray)

    /** CPU debugging is an optional SMEDIT extension, not a libretro promise. */
    suspend fun debugCpuState(): CpuDebugState = unsupportedCpuDebugging()
    suspend fun stepInstruction(): DebugExecutionResult = unsupportedCpuDebugging()
    suspend fun runUntil(request: DebugRunRequest): DebugExecutionResult = unsupportedCpuDebugging()
    suspend fun readBusMemory(address: Int, size: Int): ByteArray = unsupportedCpuDebugging()

    private fun unsupportedCpuDebugging(): Nothing =
        throw UnsupportedOperationException("This emulator backend does not support CPU debugging")
}
