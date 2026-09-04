package com.supermetroid.editor.emulator

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LsnesBackendMailboxTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `connect succeeds with dummy smedit-lsnes-worker file`() = runBlocking {
        val worker = dummyWorkerFile()
        val backend = mailboxBackend(worker)
        try {
            val caps = backend.connect()
            assertTrue(backend.isConnected)
            assertTrue(caps.supportsMoviePlayback)
            assertTrue(caps.supportsLuaScripting)
            assertTrue(caps.backendName.contains("lsnes"))
        } finally {
            backend.close()
        }
    }

    @Test
    fun `startSession without movie uses rom flag and no positional lsmv`() = runBlocking {
        val harness = startHarness()
        try {
            harness.backend.connect()
            harness.backend.startSession(SessionConfig(romPath = harness.rom.absolutePath))
            val command = harness.lastCommand()
            // Live (no-movie) sessions use --rom= so construct_rom_nofile() sees a ROM.
            assertTrue(command.any { it.startsWith("--rom=") && !it.startsWith("--rom-a=") }) {
                "expected --rom= for no-movie sessions, got $command"
            }
            assertTrue(command.none { it.startsWith("--rom-a=") }) {
                "no-movie argv must not use --rom-a=, got $command"
            }
            assertTrue(command.none { !it.startsWith("-") && it.endsWith(".lsmv") }) {
                "no-movie argv must not include a positional .lsmv: $command"
            }
            assertTrue(command.any { it.startsWith("--lua=") })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startSession with movie uses rom-a and positional lsmv`() = runBlocking {
        val harness = startHarness()
        val movie = File(tempDir, "tape.lsmv").apply { writeText("PK") }
        try {
            harness.backend.connect()
            harness.backend.startSession(
                SessionConfig(romPath = harness.rom.absolutePath, moviePath = movie.absolutePath),
            )
            val command = harness.lastCommand()
            assertTrue(command.any { it.startsWith("--rom-a=") }) { "expected --rom-a=, got $command" }
            val positional = command.drop(1).filter { !it.startsWith("-") }
            assertTrue(positional.any { it.endsWith(".lsmv") && File(it).absolutePath == movie.absolutePath }) {
                "expected positional movie path ${movie.absolutePath} in $command"
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `step with applyButtons false is forwarded in inbox json`() = runBlocking {
        val harness = startHarness()
        try {
            harness.backend.connect()
            harness.backend.startSession(SessionConfig(romPath = harness.rom.absolutePath))
            harness.backend.step(EmulatorInput(applyButtons = false, includeFrame = false))
            val step = harness.inboxes.last { it.cmd == "step" }
            assertFalse(step.applyButtons)
            assertTrue(step.raw.contains("\"applyButtons\":false"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `load_script path is forwarded`() = runBlocking {
        val harness = startHarness()
        val script = File(tempDir, "extra.lua").apply { writeText("-- extra lua\nreturn true\n") }
        try {
            harness.backend.connect()
            harness.backend.startSession(
                SessionConfig(
                    romPath = harness.rom.absolutePath,
                    luaScriptPaths = listOf(script.absolutePath),
                ),
            )
            val load = harness.inboxes.last { it.cmd == "load_script" }
            assertTrue(load.path == script.absolutePath) {
                "expected load_script path ${script.absolutePath}, got ${load.path} from ${load.raw}"
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `worker death during wait throws with exited during`() = runBlocking {
        val worker = dummyWorkerFile()
        val backend = LsnesBackend(
            executableOverride = worker,
            stageRootOverride = File(tempDir, "sessions-dead"),
            timeoutMillis = 3_000L,
            processStarter = { DeadLsnesProcess() },
        )
        try {
            backend.connect()
            val error = assertThrows<IllegalStateException> {
                runBlocking {
                    backend.startSession(SessionConfig(romPath = dummyRom().absolutePath))
                }
            }
            assertTrue(error.message.orEmpty().contains("exited during")) {
                "expected 'exited during' in ${error.message}"
            }
        } finally {
            backend.close()
        }
    }

    private fun startHarness(): MailboxHarness {
        val worker = dummyWorkerFile()
        val rom = dummyRom()
        val commands = ConcurrentLinkedQueue<List<String>>()
        val inboxes = ConcurrentLinkedQueue<InboxCommand>()
        val backend = LsnesBackend(
            executableOverride = worker,
            stageRootOverride = File(tempDir, "sessions"),
            timeoutMillis = 5_000L,
            processStarter = { builder ->
                commands.add(builder.command().toList())
                val mailbox = File(
                    requireNotNull(builder.environment()["LSNES_WORKER_DIR"]) {
                        "LSNES_WORKER_DIR must be set on the worker ProcessBuilder"
                    },
                )
                FakeLsnesWorkerProcess(mailbox, inboxes).also { it.signalReady() }
            },
        )
        return MailboxHarness(backend, rom, commands, inboxes)
    }

    private fun dummyWorkerFile(): File {
        val worker = File(tempDir, "smedit-lsnes-worker")
        if (!worker.isFile) {
            worker.writeText("#!/bin/sh\nexit 0\n")
            worker.setExecutable(true)
            if (!worker.canExecute()) {
                Runtime.getRuntime().exec(arrayOf("chmod", "+x", worker.absolutePath)).waitFor()
            }
        }
        return worker
    }

    private fun dummyRom(): File {
        val rom = File(tempDir, "dummy.smc")
        if (!rom.isFile) rom.writeBytes(ByteArray(32))
        return rom
    }

    private fun mailboxBackend(worker: File): LsnesBackend = LsnesBackend(
        executableOverride = worker,
        stageRootOverride = File(tempDir, "sessions"),
        timeoutMillis = 5_000L,
        processStarter = { builder ->
            val mailbox = File(requireNotNull(builder.environment()["LSNES_WORKER_DIR"]))
            FakeLsnesWorkerProcess(mailbox, ConcurrentLinkedQueue()).also { it.signalReady() }
        },
    )

    private class MailboxHarness(
        val backend: LsnesBackend,
        val rom: File,
        private val commands: ConcurrentLinkedQueue<List<String>>,
        val inboxes: ConcurrentLinkedQueue<InboxCommand>,
    ) {
        fun lastCommand(): List<String> = commands.last()
        fun close() = backend.close()
    }
}

private data class InboxCommand(
    val raw: String,
    val cmd: String,
    val id: String,
    val applyButtons: Boolean,
    val path: String?,
)

private fun inertProcessHandle(): ProcessHandle {
    val command = if (System.getProperty("os.name", "").lowercase().contains("win")) {
        listOf("cmd", "/c", "exit", "0")
    } else {
        listOf("true")
    }
    return ProcessBuilder(command).start().also { it.waitFor() }.toHandle()
}

private class FakeLsnesWorkerProcess(
    private val mailboxDir: File,
    private val inboxes: ConcurrentLinkedQueue<InboxCommand>,
) : Process() {
    private val alive = AtomicBoolean(true)
    private val exitCode = AtomicInteger(0)
    private val frame = AtomicInteger(0)
    private val json = Json { ignoreUnknownKeys = true }
    private val thread = Thread(
        {
            try {
                while (alive.get() && !Thread.currentThread().isInterrupted) {
                    val inbox = File(mailboxDir, "inbox.json")
                    if (inbox.isFile) {
                        val text = runCatching { inbox.readText() }.getOrNull()
                        if (text != null) {
                            inbox.delete()
                            handleInbox(text)
                        }
                    } else {
                        Thread.sleep(2)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        },
        "fake-lsnes-worker",
    ).apply {
        isDaemon = true
        start()
    }

    fun signalReady() {
        File(mailboxDir, "outbox").mkdirs()
        File(mailboxDir, "outbox/done").writeText("ready\n")
    }

    private fun handleInbox(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        val cmd = obj["cmd"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val applyButtons = obj["applyButtons"]?.jsonPrimitive?.booleanOrNull ?: true
        val path = obj["path"]?.jsonPrimitive?.contentOrNull
        val includeWram = obj["includeWram"]?.jsonPrimitive?.booleanOrNull ?: true
        inboxes.add(
            InboxCommand(
                raw = text,
                cmd = cmd,
                id = id,
                applyButtons = applyButtons,
                path = path,
            ),
        )
        if (cmd == "step") frame.incrementAndGet()
        val outbox = File(mailboxDir, "outbox").apply { mkdirs() }
        File(outbox, "reply.json").writeText(
            """{"id":"$id","ok":true,"frame":${frame.get()},"width":256,"height":224}""",
        )
        if (includeWram) {
            File(outbox, "wram.bin").writeBytes(ByteArray(WRAM_SIZE))
        }
        if (cmd == "quit") {
            alive.set(false)
        }
        File(outbox, "done").writeText("ok\n")
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int {
        thread.join()
        return exitCode.get()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        thread.join(unit.toMillis(timeout))
        return !alive.get()
    }

    override fun exitValue(): Int {
        if (alive.get()) throw IllegalThreadStateException("process hasn't exited")
        return exitCode.get()
    }

    override fun destroy() {
        alive.set(false)
        thread.interrupt()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    override fun isAlive(): Boolean = alive.get()

    override fun toHandle(): ProcessHandle = handle

    companion object {
        private const val WRAM_SIZE = 0x2000
        private val handle: ProcessHandle by lazy { inertProcessHandle() }
    }
}

private class DeadLsnesProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 1
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
    override fun exitValue(): Int = 1
    override fun destroy() {}
    override fun destroyForcibly(): Process = this
    override fun isAlive(): Boolean = false
    override fun toHandle(): ProcessHandle = inertProcessHandle()
}
