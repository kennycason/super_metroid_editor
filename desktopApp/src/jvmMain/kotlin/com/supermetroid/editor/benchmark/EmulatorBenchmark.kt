package com.supermetroid.editor.benchmark

import com.supermetroid.editor.emulator.EmulatorBackend
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.LsnesBackend
import com.supermetroid.editor.emulator.RealtimePlayBackend
import com.supermetroid.editor.emulator.SessionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val emulatorBenchmarkLog = KotlinLogging.logger {}

private fun benchLog(message: Any? = "") {
    emulatorBenchmarkLog.info { message?.toString() ?: "" }
}

/**
 * Headless benchmark for the libretro (snes9x) emulator backend.
 *
 * Usage:
 *   ./gradlew :desktopApp:benchmark
 *
 * Environment:
 *   SMEDIT_ROM_PATH       - path to Super Metroid ROM (.sfc)
 *   SMEDIT_LIBRETRO_CORE  - (optional) explicit core path
 *   BENCH_WARMUP_FRAMES   - warmup frames (default 60)
 *   BENCH_FRAMES          - benchmark frames (default 600)
 *   BENCH_BACKENDS        - comma-separated list (default "libretro")
 */
fun main() {
    val romPath = System.getenv("SMEDIT_ROM_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        ?: run {
            // Search common locations relative to CWD or project root
            val candidates = listOf(
                "custom_integrations/SuperMetroid-Snes/rom.sfc",
                "../custom_integrations/SuperMetroid-Snes/rom.sfc",
            )
            // Also walk up from CWD looking for the integrations dir
            var dir = java.io.File(System.getProperty("user.dir"))
            var found: String? = null
            for (i in 0..4) {
                val f = java.io.File(dir, "custom_integrations/SuperMetroid-Snes/rom.sfc")
                if (f.exists()) { found = f.absolutePath; break }
                dir = dir.parentFile ?: break
            }
            found ?: candidates.firstOrNull { java.io.File(it).exists() }
            ?: run {
                emulatorBenchmarkLog.error { "Set SMEDIT_ROM_PATH to the Super Metroid ROM" }
                System.exit(1)
                ""
            }
        }

    val warmupFrames = System.getenv("BENCH_WARMUP_FRAMES")?.toIntOrNull() ?: 60
    val benchFrames = System.getenv("BENCH_FRAMES")?.toIntOrNull() ?: 600
    val backends = (System.getenv("BENCH_BACKENDS") ?: "libretro")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    benchLog("╔══════════════════════════════════════════════════╗")
    benchLog("║        Emulator Backend Benchmark                ║")
    benchLog("╠══════════════════════════════════════════════════╣")
    benchLog("║ ROM:     $romPath")
    benchLog("║ Warmup:  $warmupFrames frames")
    benchLog("║ Bench:   $benchFrames frames")
    benchLog("║ Backends: ${backends.joinToString(", ")}")
    benchLog("╚══════════════════════════════════════════════════╝")
    benchLog()

    val results = mutableListOf<BenchResult>()

    for (backendName in backends) {
        benchLog("── $backendName ──────────────────────────────────")
        try {
            val result = benchmarkBackend(backendName, romPath, warmupFrames, benchFrames)
            results.add(result)
            printResult(result)
        } catch (e: Exception) {
            benchLog("  FAILED: ${e.message}")
            emulatorBenchmarkLog.error(e) { "Benchmark failed for $backendName: ${e.message}" }
        }
        benchLog()
    }

    // Also run libretro with audio disabled for raw speed comparison
    if (backends.contains("libretro")) {
        benchLog("── libretro (no audio — raw speed) ───────────────")
        try {
            val result = benchmarkBackend("libretro-no-audio", romPath, warmupFrames, benchFrames)
            results.add(result)
            printResult(result)
        } catch (e: Exception) {
            benchLog("  FAILED: ${e.message}")
            emulatorBenchmarkLog.error(e) { "Benchmark failed for libretro-no-audio: ${e.message}" }
        }
        benchLog()
    }

    if (results.size >= 2) {
        benchLog("── Comparison (with-frame) ───────────────────────")
        val fastest = results.minByOrNull { it.avgStepMs }!!
        for (r in results) {
            val ratio = r.avgStepMs / fastest.avgStepMs
            val label = if (r == fastest) " ← fastest" else " (${String.format("%.1f", ratio)}x slower)"
            benchLog("  ${r.backend.padEnd(20)} ${String.format("%8.2f", r.avgStepMs)} ms/step  ${String.format("%7.1f", r.fps)} FPS$label")
        }

        val libretro = results.find { it.backend == "libretro" }
        if (libretro != null) {
            benchLog("  libretro (with audio pacing): ${String.format("%.1f", libretro.fps)} FPS — real-time sync via audio output")
        }
    }
}

data class BenchResult(
    val backend: String,
    val totalMs: Long,
    val frames: Int,
    val avgStepMs: Double,
    val minStepMs: Double,
    val maxStepMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val fps: Double,
)

fun benchmarkBackend(backendName: String, romPath: String, warmupFrames: Int, benchFrames: Int): BenchResult {
    val backend: EmulatorBackend = when (backendName) {
        "libretro" -> LibretroBackend()
        "libretro-no-audio" -> LibretroBackend(audioEnabledOverride = false)
        "lsnes-b25" -> LsnesBackend()
        else -> throw IllegalArgumentException("Unknown backend: $backendName")
    }

    return runBlocking {
        try {
            benchLog("  Connecting...")
            val caps = backend.connect()
            benchLog("${caps.backendName}")

            benchLog("  Starting session...")
            backend.startSession(SessionConfig(romPath = romPath))
            benchLog("ok")

            // Warmup
            benchLog("  Warming up ($warmupFrames frames)...")
            val noFrameInput = EmulatorInput(repeat = 1, includeFrame = false, includeTrace = false, includeWram = false)
            for (i in 0 until warmupFrames) {
                backend.step(noFrameInput)
            }
            benchLog("done")

            // Benchmark: step WITHOUT frame (pure emulation speed)
            benchLog("  Benchmarking no-frame ($benchFrames frames)...")
            val noFrameInputBench = EmulatorInput(repeat = 1, includeFrame = false, includeTrace = false, includeWram = false)
            val noFrameTimes = LongArray(benchFrames)

            val noFrameStart = System.nanoTime()
            for (i in 0 until benchFrames) {
                val t0 = System.nanoTime()
                backend.step(noFrameInputBench)
                noFrameTimes[i] = System.nanoTime() - t0
            }
            val noFrameNanos = System.nanoTime() - noFrameStart
            val noFrameMs = noFrameTimes.map { it / 1_000_000.0 }.sorted()
            benchLog("done")
            benchLog("  [no-frame] Avg: ${String.format("%.3f", noFrameMs.average())} ms  " +
                    "P50: ${String.format("%.3f", noFrameMs[noFrameMs.size / 2])} ms  " +
                    "FPS: ${String.format("%.1f", benchFrames.toDouble() / (noFrameNanos / 1_000_000_000.0))}")

            // Benchmark: step WITH frame (realistic workload)
            benchLog("  Benchmarking with-frame ($benchFrames frames)...")
            val withFrameInput = EmulatorInput(repeat = 1, includeFrame = true, includeTrace = false)
            val stepTimes = LongArray(benchFrames)

            val totalStart = System.nanoTime()
            for (i in 0 until benchFrames) {
                val t0 = System.nanoTime()
                backend.step(withFrameInput)
                stepTimes[i] = System.nanoTime() - t0
            }
            val totalNanos = System.nanoTime() - totalStart
            benchLog("done")

            val realtime = backend as? RealtimePlayBackend
            if (realtime != null) {
                benchLog("  Benchmarking realtime (1.0s wall)...")
                realtime.startRealtime(applyButtons = true)
                var startFrame = 0
                val readyDeadline = System.nanoTime() + 500_000_000L
                while (System.nanoTime() < readyDeadline && startFrame == 0) {
                    startFrame = realtime.pollRealtime()?.session?.frameCounter ?: 0
                    Thread.sleep(1)
                }
                val wallStart = System.nanoTime()
                var workerFrame = startFrame
                while (System.nanoTime() - wallStart < 1_000_000_000L) {
                    val live = realtime.pollRealtime()
                    if (live != null) workerFrame = live.session.frameCounter
                    Thread.sleep(1)
                }
                val wallSeconds = (System.nanoTime() - wallStart) / 1_000_000_000.0
                val delta = (workerFrame - startFrame).coerceAtLeast(0)
                realtime.stopRealtime()
                benchLog(
                    "  [realtime] frames=$delta in ${String.format("%.2f", wallSeconds)}s  " +
                        "FPS: ${String.format("%.1f", delta / wallSeconds)}",
                )
            }

            // Compute stats
            val stepMs = stepTimes.map { it / 1_000_000.0 }.sorted()
            val totalMs = totalNanos / 1_000_000
            BenchResult(
                backend = backendName,
                totalMs = totalMs,
                frames = benchFrames,
                avgStepMs = stepMs.average(),
                minStepMs = stepMs.first(),
                maxStepMs = stepMs.last(),
                p50Ms = stepMs[stepMs.size / 2],
                p95Ms = stepMs[(stepMs.size * 0.95).toInt()],
                p99Ms = stepMs[(stepMs.size * 0.99).toInt()],
                fps = benchFrames.toDouble() / (totalNanos / 1_000_000_000.0),
            )
        } finally {
            try { backend.close() } catch (_: Exception) {}
        }
    }
}

fun printResult(r: BenchResult) {
    benchLog("  Total:   ${r.totalMs} ms for ${r.frames} frames")
    benchLog("  Avg:     ${String.format("%.3f", r.avgStepMs)} ms/step")
    benchLog("  Min:     ${String.format("%.3f", r.minStepMs)} ms")
    benchLog("  Max:     ${String.format("%.3f", r.maxStepMs)} ms")
    benchLog("  P50:     ${String.format("%.3f", r.p50Ms)} ms")
    benchLog("  P95:     ${String.format("%.3f", r.p95Ms)} ms")
    benchLog("  P99:     ${String.format("%.3f", r.p99Ms)} ms")
    benchLog("  FPS:     ${String.format("%.1f", r.fps)}")
}
