package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomOverlapPolicy
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.rom.RomSegmentPlacement
import com.supermetroid.editor.rom.RomSegmentRequest
import com.supermetroid.editor.rom.RomVerifiedFreeRange
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Disposable cross-session cache for a fully protected ASM build base.
 *
 * The filename is the exact source/toolchain/ROM/project fingerprint used by
 * [ProjectFileService]. The envelope also records that dependency and hashes
 * its payload. Every decoded address/range is checked before it can become a
 * build input. A cache failure is always a miss; source compilation remains
 * the authority.
 */
internal object AsmPreparedBuildDiskCache {
    private const val MAGIC = 0x534D4143 // SMAC
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 128 * 1024 * 1024
    private const val MAX_ROM_BYTES = 5 * 1024 * 1024
    private const val MAX_COLLECTION = 1_000_000
    private const val MAX_STRING_BYTES = 32 * 1024 * 1024
    private val KEY = Regex("[0-9a-f]{64}")
    private val touchedDirectories = linkedSetOf<File>()

    fun load(projectFilePath: String, key: String): AsmCompiledBuildBase? = runCatching {
        require(KEY.matches(key))
        val directory = directory(projectFilePath)
        remember(directory)
        val target = File(directory, "prepared-$key.bin")
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        require(target.isFile && !Files.isSymbolicLink(target.toPath()))
        require(target.canonicalFile.parentFile == directory.canonicalFile)
        require(target.length() in 1..MAX_FILE_BYTES.toLong())
        DataInputStream(target.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            require(input.readString() == key)
            val payloadLength = input.readInt()
            require(payloadLength in 1..MAX_FILE_BYTES)
            val expectedHash = input.readByteArray(64)
            require(expectedHash.size == 32)
            val payload = ByteArray(payloadLength).also(input::readFully)
            require(input.read() == -1)
            require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(expectedHash))
            DataInputStream(ByteArrayInputStream(payload)).use { body ->
                readBase(body).also {
                    require(body.read() == -1)
                    validate(it)
                }
            }
        }
    }.getOrNull()

    fun store(projectFilePath: String, key: String, base: AsmCompiledBuildBase) {
        runCatching {
            require(KEY.matches(key))
            validate(base)
            val payload = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { writeBase(it, base) }
            }.toByteArray()
            require(payload.size <= MAX_FILE_BYTES)
            val directory = directory(projectFilePath)
            remember(directory)
            directory.mkdirs()
            require(!Files.isSymbolicLink(directory.toPath()))
            val target = File(directory, "prepared-$key.bin")
            require(target.canonicalFile.parentFile == directory.canonicalFile)
            val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
            try {
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeString(key)
                    output.writeInt(payload.size)
                    output.writeByteArray(MessageDigest.getInstance("SHA-256").digest(payload))
                    output.write(payload)
                }
                moveAtomically(temporary, target)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.matches(Regex("prepared-[0-9a-f]{64}\\.bin")) }
                .sortedWith(compareByDescending<File> { it == target }.thenByDescending(File::lastModified))
                .drop(2)
                .forEach(File::delete)
        }
    }

    private fun directory(projectFilePath: String): File =
        File(ProjectPaths.forProject(projectFilePath).asmBuild, "prepared")

    @Synchronized
    private fun remember(directory: File) {
        touchedDirectories += directory.absoluteFile.normalize()
    }

    /** Existing cold-build tests explicitly clear all process-local build caches. */
    @Synchronized
    internal fun clearForTests() {
        touchedDirectories.forEach { directory ->
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.matches(Regex("prepared-[0-9a-f]{64}\\.bin")) }
                .forEach(File::delete)
        }
        touchedDirectories.clear()
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun validate(base: AsmCompiledBuildBase) {
        require(base.romBytes.size in 1..MAX_ROM_BYTES && base.referenceRomBody.size in 1..MAX_ROM_BYTES)
        val headerSize = if (base.romBytes.size % 0x8000 == 0x200) 0x200 else 0
        val bodySize = base.romBytes.size - headerSize
        require(bodySize == base.referenceRomBody.size)
        require(base.referenceRomSha256.matches(Regex("[0-9a-f]{64}")))
        fun range(pc: Int, length: Int) = pc >= 0 && length >= 0 && pc.toLong() + length <= bodySize
        require(base.sourceOwnedRanges.all { range(it.pcOffset, it.length) })
        require(base.generatedAssetClaims.all { range(it.pcOffset, it.length) })
        require(base.generatedRoomClaims.all { range(it.pcOffset, it.length) })
        require(base.generatedPatchClaims.all { range(it.pcOffset, it.length) })
        fun matches(pc: Int, bytes: ByteArray): Boolean =
            base.romBytes.copyOfRange(headerSize + pc, headerSize + pc + bytes.size).contentEquals(bytes)
        require(base.generatedAssetClaims.all { matches(it.pcOffset, it.bytes) })
        require(base.generatedRoomClaims.all { matches(it.pcOffset, it.bytes) })
        require(base.generatedPatchClaims.all { matches(it.pcOffset, it.bytes) })
        require(base.preassembledAssetWrites.all { range(it.offset, it.bytes.size) })
        require(base.segmentPlacements.map { it.request.id }.distinct().size == base.segmentPlacements.size)
        require(base.segmentPlacements.all { range(it.allocation.canonicalPcOffset, it.allocation.size) })
        require(base.beamDamageLocations.values.all { it in 0 until bodySize })
        require(base.patchDataLocations.values.all { it.lowPc in 0 until bodySize && it.highPc in 0 until bodySize })
        require(base.nativePatchLocations.values.flatten().all { range(it.pc, it.size) })
        require(base.nativePatchWrites.values.flatten().all { range(it.pc, it.size) })
        require(base.verifiedFreeRanges.all { it.pcStart >= 0 && it.pcEndExclusive in (it.pcStart + 1)..bodySize })
        listOfNotNull(
            base.expectedFinalRomBytes,
            base.roomValidationRomBytes,
            base.curatedBaselineRomBody,
            base.checkedHookDemoBaselineRomBody,
        ).forEach { require(it.size == bodySize || it.size == base.romBytes.size) }
    }

    private fun writeBase(output: DataOutputStream, base: AsmCompiledBuildBase) = with(output) {
        writeByteArray(base.romBytes)
        writeByteArray(base.referenceRomBody)
        writeString(base.referenceRomSha256)
        writeList(base.sourceOwnedRanges) { range ->
            writeInt(range.pcOffset); writeInt(range.length); writeNullableString(range.sourceFileId)
        }
        writeList(base.generatedAssetClaims) { claim ->
            writeString(claim.owner); writeString(claim.label); writeString(claim.assetPath)
            writeInt(claim.pcOffset); writeByteArray(claim.bytes)
        }
        writeList(base.generatedRoomClaims) { claim ->
            writeString(claim.owner); writeString(claim.label); writeInt(claim.pcOffset); writeByteArray(claim.bytes)
        }
        writeStringSet(base.generatedRoomIds)
        writeStringIntMap(base.generatedNewRoomIds)
        writeString(base.generatedRoomSource)
        writeList(base.generatedPatchClaims) { claim ->
            writeString(claim.owner); writeString(claim.label); writeInt(claim.pcOffset); writeByteArray(claim.bytes)
        }
        writeList(base.preassembledAssetWrites) { writeIntent(it) }
        writeList(base.segmentPlacements) { writePlacement(it) }
        writeString(base.assemblerVersion)
        writeStringList(base.compilerOutput)
        writeByteArray(base.compilerSymbols)
        writeNullableString(base.expectedFinalParitySha256)
        writeNullableBytes(base.expectedFinalRomBytes)
        writeNullableBytes(base.roomValidationRomBytes)
        writeNullableInt(base.managedBank8FStart)
        writeStringIntMap(base.beamDamageLocations)
        writeMap(base.patchDataLocations, { writeString(it) }) { value ->
            writeInt(value.lowPc); writeInt(value.highPc)
        }
        writeMap(base.nativePatchLocations, { writeString(it) }) { values ->
            writeList(values) { value -> writeInt(value.pc); writeInt(value.size) }
        }
        writeMap(base.nativePatchWrites, { writeString(it) }) { values ->
            writeList(values) { value -> writeInt(value.pc); writeInt(value.size); writeInt(value.value) }
        }
        writeStringSet(base.curatedPatchIds)
        writeNullableBytes(base.curatedBaselineRomBody)
        writeNullableBytes(base.checkedHookDemoBaselineRomBody)
        writeList(base.verifiedFreeRanges) { range ->
            writeInt(range.bank); writeInt(range.pcStart); writeInt(range.pcEndExclusive)
            writeInt(range.fillByte); writeString(range.label)
        }
    }

    private fun readBase(input: DataInputStream): AsmCompiledBuildBase = with(input) {
        AsmCompiledBuildBase(
            romBytes = readByteArray(MAX_ROM_BYTES),
            referenceRomBody = readByteArray(MAX_ROM_BYTES),
            referenceRomSha256 = readString(),
            sourceOwnedRanges = readList { AsmSourceOwnedRange(readInt(), readInt(), readNullableString()) },
            generatedAssetClaims = readList {
                AsmGeneratedAssetClaim(readString(), readString(), readString(), readInt(), readByteArray(MAX_ROM_BYTES))
            },
            generatedRoomClaims = readList {
                AsmGeneratedRoomClaim(readString(), readString(), readInt(), readByteArray(MAX_ROM_BYTES))
            },
            generatedRoomIds = readStringSet(),
            generatedNewRoomIds = readStringIntMap(),
            generatedRoomSource = readString(),
            generatedPatchClaims = readList {
                AsmGeneratedPatchClaim(readString(), readString(), readInt(), readByteArray(MAX_ROM_BYTES))
            },
            preassembledAssetWrites = readList { readIntent() },
            segmentPlacements = readList { readPlacement() },
            assemblerVersion = readString(),
            compilerOutput = readStringList(),
            compilerSymbols = readByteArray(MAX_FILE_BYTES),
            expectedFinalParitySha256 = readNullableString(),
            expectedFinalRomBytes = readNullableBytes(MAX_ROM_BYTES),
            roomValidationRomBytes = readNullableBytes(MAX_ROM_BYTES),
            managedBank8FStart = readNullableInt(),
            beamDamageLocations = readStringIntMap(),
            patchDataLocations = readMap({ readString() }) { AsmPatchWordLocations(readInt(), readInt()) },
            nativePatchLocations = readMap({ readString() }) {
                readList { AsmDataPatchLocation(readInt(), readInt()) }
            },
            nativePatchWrites = readMap({ readString() }) {
                readList { AsmDataPatchWrite(readInt(), readInt(), readInt()) }
            },
            curatedPatchIds = readStringSet(),
            curatedBaselineRomBody = readNullableBytes(MAX_ROM_BYTES),
            checkedHookDemoBaselineRomBody = readNullableBytes(MAX_ROM_BYTES),
            verifiedFreeRanges = readList {
                RomVerifiedFreeRange(readInt(), readInt(), readInt(), readInt(), readString())
            },
        )
    }

    private fun DataOutputStream.writeRequest(request: RomSegmentRequest) {
        writeString(request.id); writeString(request.owner); writeInt(request.kind.ordinal); writeString(request.label)
        writeInt(request.size); writeInt(request.alignment); writeIntList(request.allowedBanks); writeNullableString(request.constraint)
    }

    private fun DataInputStream.readRequest(): RomSegmentRequest = RomSegmentRequest(
        id = readString(), owner = readString(), kind = readEnum<RomSegmentKind>(), label = readString(),
        size = readInt(), alignment = readInt(), allowedBanks = readIntList(), constraint = readNullableString(),
    ).validate()

    private fun DataOutputStream.writeAllocation(allocation: RomAllocation) {
        writeInt(allocation.bank); writeInt(allocation.pcOffset); writeInt(allocation.snesAddress); writeInt(allocation.size)
        writeString(allocation.label); writeNullableInt(allocation.fillByte); writeBoolean(allocation.request != null)
        allocation.request?.let { writeRequest(it) }; writeInt(allocation.canonicalPcOffset)
    }

    private fun DataInputStream.readAllocation(): RomAllocation = RomAllocation(
        bank = readInt(), pcOffset = readInt(), snesAddress = readInt(), size = readInt(), label = readString(),
        fillByte = readNullableInt(), request = if (readBoolean()) readRequest() else null, canonicalPcOffset = readInt(),
    )

    private fun DataOutputStream.writePlacement(placement: RomSegmentPlacement) {
        writeRequest(placement.request); writeAllocation(placement.allocation)
    }

    private fun DataInputStream.readPlacement(): RomSegmentPlacement = RomSegmentPlacement(readRequest(), readAllocation())

    private fun DataOutputStream.writeIntent(intent: RomWriteIntent) {
        writeString(intent.owner); writeString(intent.label); writeInt(intent.offset); writeIntList(intent.bytes)
        writeInt(intent.kind.ordinal); writeNullableIntList(intent.expectedBefore); writeBoolean(intent.preconditionRecommended)
        writeInt(intent.overlapPolicy.ordinal)
    }

    private fun DataInputStream.readIntent(): RomWriteIntent = RomWriteIntent(
        owner = readString(), label = readString(), offset = readInt(), bytes = readIntList(),
        kind = readEnum<RomWriteKind>(), expectedBefore = readNullableIntList(),
        preconditionRecommended = readBoolean(), overlapPolicy = readEnum<RomOverlapPolicy>(),
    )

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T =
        enumValues<T>().getOrNull(readInt()) ?: throw EOFException("Invalid cached enum")

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES)
        return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null); value?.let { writeString(it) }
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

    private fun DataOutputStream.writeByteArray(value: ByteArray) { writeInt(value.size); write(value) }
    private fun DataInputStream.readByteArray(max: Int): ByteArray {
        val length = readInt(); require(length in 0..max)
        return ByteArray(length).also(::readFully)
    }
    private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
        writeBoolean(value != null); value?.let { writeByteArray(it) }
    }
    private fun DataInputStream.readNullableBytes(max: Int): ByteArray? = if (readBoolean()) readByteArray(max) else null
    private fun DataOutputStream.writeNullableInt(value: Int?) { writeBoolean(value != null); value?.let(::writeInt) }
    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeIntList(values: List<Int>) {
        writeInt(values.size); values.forEach(::writeInt)
    }
    private fun DataInputStream.readIntList(): List<Int> {
        val size = readCount(); return List(size) { readInt() }
    }
    private fun DataOutputStream.writeNullableIntList(values: List<Int>?) {
        writeBoolean(values != null); values?.let { writeIntList(it) }
    }
    private fun DataInputStream.readNullableIntList(): List<Int>? = if (readBoolean()) readIntList() else null
    private fun DataOutputStream.writeStringList(values: List<String>) = writeList(values) { writeString(it) }
    private fun DataInputStream.readStringList(): List<String> = readList { readString() }
    private fun DataOutputStream.writeStringSet(values: Set<String>) = writeStringList(values.sorted())
    private fun DataInputStream.readStringSet(): Set<String> = readStringList().toSet()
    private fun DataOutputStream.writeStringIntMap(values: Map<String, Int>) =
        writeMap(values, { writeString(it) }, { writeInt(it) })
    private fun DataInputStream.readStringIntMap(): Map<String, Int> = readMap({ readString() }, { readInt() })

    private fun DataInputStream.readCount(): Int = readInt().also { require(it in 0..MAX_COLLECTION) }
    private fun <T> DataOutputStream.writeList(values: Collection<T>, writeValue: DataOutputStream.(T) -> Unit) {
        require(values.size <= MAX_COLLECTION); writeInt(values.size); values.forEach { writeValue(it) }
    }
    private fun <T> DataInputStream.readList(readValue: DataInputStream.() -> T): List<T> =
        List(readCount()) { readValue() }
    private fun <K, V> DataOutputStream.writeMap(
        values: Map<K, V>,
        writeKey: DataOutputStream.(K) -> Unit,
        writeValue: DataOutputStream.(V) -> Unit,
    ) {
        require(values.size <= MAX_COLLECTION); writeInt(values.size)
        values.toList().sortedBy { it.first.toString() }.forEach { (key, value) -> writeKey(key); writeValue(value) }
    }
    private fun <K, V> DataInputStream.readMap(
        readKey: DataInputStream.() -> K,
        readValue: DataInputStream.() -> V,
    ): Map<K, V> = buildMap {
        repeat(readCount()) {
            val key = readKey(); require(key !in this); put(key, readValue())
        }
    }
}
