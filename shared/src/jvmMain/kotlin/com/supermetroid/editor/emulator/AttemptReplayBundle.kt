package com.supermetroid.editor.emulator

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LoadedReplayBundle(
    val manifest: ReplayManifest,
    val log: AttemptLog,
    val initialState: ByteArray,
    val finalState: ByteArray? = null,
    val sourcePath: String,
) {
    val title: String get() = manifest.title.ifBlank { "Super Metroid replay" }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedReplayBundle) return false
        return manifest == other.manifest &&
            log == other.log &&
            initialState.contentEquals(other.initialState) &&
            (finalState == null && other.finalState == null ||
                finalState != null && other.finalState != null &&
                finalState.contentEquals(other.finalState)) &&
            sourcePath == other.sourcePath
    }

    override fun hashCode(): Int {
        var result = manifest.hashCode()
        result = 31 * result + log.hashCode()
        result = 31 * result + initialState.contentHashCode()
        result = 31 * result + (finalState?.contentHashCode() ?: 0)
        result = 31 * result + sourcePath.hashCode()
        return result
    }
}

object AttemptReplayBundle {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun companionStateFile(logFile: File): File =
        File(logFile.parentFile, "${logFile.nameWithoutExtension}.state")

    fun companionFinalStateFile(logFile: File): File =
        File(logFile.parentFile, "${logFile.nameWithoutExtension}.final.state")

    fun bundleFileForLog(logFile: File): File =
        File(logFile.parentFile, "${logFile.nameWithoutExtension}.${AttemptReplayBundleFiles.EXTENSION}")

    fun buildManifest(
        log: AttemptLog,
        title: String = "Super Metroid replay",
        description: String = "",
        createdAt: String = timestampNow(),
    ): ReplayManifest {
        val roomIds = log.frames
            .mapNotNull { it.frameState.roomId }
            .distinct()
            .map { "0x${it.toString(16).uppercase()}" }
        return ReplayManifest(
            title = title,
            description = description,
            createdAt = createdAt,
            frameCount = log.frameCount,
            roomIds = roomIds,
            emulatorCore = normalizeEmulatorCoreIdentity(log.emulatorCore),
            romHash = log.romHash,
            initialStateHash = log.initialStateHash,
            finalStateHash = log.finalStateHash,
        )
    }

    fun write(
        bundleFile: File,
        log: AttemptLog,
        initialState: ByteArray,
        manifest: ReplayManifest,
        finalState: ByteArray? = null,
    ): File {
        require(initialState.isNotEmpty()) { "Initial savestate is empty" }
        require(log.frames.isNotEmpty()) { "Attempt log has no frames" }
        require(Sha256.hex(initialState) == log.initialStateHash) {
            "Savestate hash does not match attempt log initialStateHash"
        }
        if (log.finalStateHash.isNotBlank()) {
            require(finalState != null && finalState.isNotEmpty()) {
                "Attempt log expects a final savestate but none was provided"
            }
            require(Sha256.hex(finalState) == log.finalStateHash) {
                "Final savestate hash does not match attempt log finalStateHash"
            }
        }

        bundleFile.parentFile?.mkdirs()
        ZipOutputStream(bundleFile.outputStream()).use { zip ->
            writeZipEntry(zip, AttemptReplayBundleFiles.MANIFEST, json.encodeToString(manifest))
            writeZipEntry(zip, AttemptReplayBundleFiles.ATTEMPT, json.encodeToString(log))
            writeZipEntry(zip, AttemptReplayBundleFiles.STATE, initialState)
            if (finalState != null) {
                writeZipEntry(zip, AttemptReplayBundleFiles.FINAL_STATE, finalState)
            }
        }
        return bundleFile
    }

    fun writeFromRecording(
        logFile: File,
        title: String? = null,
        description: String = "",
    ): File {
        val log = json.decodeFromString<AttemptLog>(logFile.readText())
        val stateFile = companionStateFile(logFile)
        require(stateFile.isFile) { "Companion savestate not found: ${stateFile.absolutePath}" }
        val initialState = stateFile.readBytes()
        val finalStateFile = companionFinalStateFile(logFile)
        val finalState = finalStateFile.takeIf { it.isFile }?.readBytes()
        val manifest = buildManifest(
            log = log,
            title = title ?: defaultTitle(logFile),
            description = description,
        )
        return write(bundleFileForLog(logFile), log, initialState, manifest, finalState)
    }

    fun read(path: File): LoadedReplayBundle {
        require(path.isFile) { "Replay bundle not found: ${path.absolutePath}" }
        return when (path.extension.lowercase()) {
            AttemptReplayBundleFiles.EXTENSION -> readZipBundle(path)
            "json" -> readLooseRecording(path)
            else -> throw IllegalArgumentException(
                "Unsupported replay file type: ${path.name}. Use .${AttemptReplayBundleFiles.EXTENSION} or .json",
            )
        }
    }

    private fun readZipBundle(bundleFile: File): LoadedReplayBundle {
        ZipFile(bundleFile).use { zip ->
            val manifest = readZipText(zip, AttemptReplayBundleFiles.MANIFEST)
                ?.let { json.decodeFromString<ReplayManifest>(it) }
                ?: throw IllegalArgumentException("Missing ${AttemptReplayBundleFiles.MANIFEST} in ${bundleFile.name}")
            val attemptName = manifest.attemptLogFile.ifBlank { AttemptReplayBundleFiles.ATTEMPT }
            val stateName = manifest.initialStateFile.ifBlank { AttemptReplayBundleFiles.STATE }
            val finalStateName = manifest.finalStateFile.ifBlank { AttemptReplayBundleFiles.FINAL_STATE }
            val attemptText = readZipText(zip, attemptName)
                ?: throw IllegalArgumentException("Missing $attemptName in ${bundleFile.name}")
            val initialState = readZipBytes(zip, stateName)
                ?: throw IllegalArgumentException("Missing $stateName in ${bundleFile.name}")
            val log = json.decodeFromString<AttemptLog>(attemptText)
            val finalState = readZipBytes(zip, finalStateName)
            validateLoaded(log, initialState, finalState, manifest)
            return LoadedReplayBundle(
                manifest = manifest,
                log = log,
                initialState = initialState,
                finalState = finalState,
                sourcePath = bundleFile.absolutePath,
            )
        }
    }

    private fun readLooseRecording(logFile: File): LoadedReplayBundle {
        val log = json.decodeFromString<AttemptLog>(logFile.readText())
        val stateFile = companionStateFile(logFile)
        require(stateFile.isFile) { "Companion savestate not found: ${stateFile.absolutePath}" }
        val initialState = stateFile.readBytes()
        val finalStateFile = companionFinalStateFile(logFile)
        val finalState = finalStateFile.takeIf { it.isFile }?.readBytes()
        val manifest = buildManifest(log = log, title = defaultTitle(logFile))
        validateLoaded(log, initialState, finalState, manifest)
        return LoadedReplayBundle(
            manifest = manifest,
            log = log,
            initialState = initialState,
            finalState = finalState,
            sourcePath = logFile.absolutePath,
        )
    }

    private fun validateLoaded(
        log: AttemptLog,
        initialState: ByteArray,
        finalState: ByteArray?,
        manifest: ReplayManifest,
    ) {
        require(log.version == AttemptLog.CURRENT_VERSION) {
            "Unsupported attempt log version: ${log.version}"
        }
        require(Sha256.hex(initialState) == log.initialStateHash) {
            "Savestate hash does not match attempt log"
        }
        if (log.finalStateHash.isNotBlank()) {
            require(finalState != null) { "Attempt log expects a final savestate but none was loaded" }
            require(Sha256.hex(finalState) == log.finalStateHash) {
                "Final savestate hash does not match attempt log"
            }
        }
        if (manifest.frameCount > 0 && manifest.frameCount != log.frameCount) {
            throw IllegalArgumentException(
                "Manifest frameCount (${manifest.frameCount}) does not match log (${log.frameCount})",
            )
        }
    }

    private fun readZipText(zip: ZipFile, entryName: String): String? {
        val entry = zip.getEntry(entryName) ?: return null
        return zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    private fun readZipBytes(zip: ZipFile, entryName: String): ByteArray? {
        val entry = zip.getEntry(entryName) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, text: String) {
        writeZipEntry(zip, name, text.encodeToByteArray())
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun defaultTitle(logFile: File): String =
        logFile.nameWithoutExtension.replace('_', ' ')

    private fun timestampNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date())
}
