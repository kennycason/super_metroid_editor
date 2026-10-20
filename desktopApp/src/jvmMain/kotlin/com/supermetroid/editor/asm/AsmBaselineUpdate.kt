package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import java.io.File
import java.nio.file.Files

internal data class AsmBaselineFileUpdate(
    val fileId: String, val merge: AsmTextMerge, val changed: Boolean,
    val baselineChanged: Boolean = changed,
    val projectEdited: Boolean = false,
    val documentationOnly: Boolean = false,
)
internal data class AsmBaselineUpdateResult(val workspace: AsmProjectWorkspace, val backupDirectory: File)

/** A review draft owns only disposable staging. It cannot publish unresolved conflicts. */
internal class AsmBaselineUpdatePlan internal constructor(
    val projectFilePath: String,
    val previousRevision: String,
    val nextRevision: String,
    val files: List<AsmBaselineFileUpdate>,
    internal val staging: File,
    internal val before: Map<String, String>,
    internal val candidate: AsmSourceBaseline,
) {
    internal var consumed = false
    val conflictCount: Int get() = files.sumOf { it.merge.conflicts.size }
    val changedFiles: List<AsmBaselineFileUpdate> get() = files.filter { it.changed }
    val baselineChanges get() = files.filter { it.baselineChanged }
    val editedBankCount get() = files.count { it.projectEdited && !it.fileId.startsWith(ASM_PROJECT_MODULE_PREFIX) }
    val moduleCount get() = files.count { it.fileId.startsWith(ASM_PROJECT_MODULE_PREFIX) }
}

/** Explicit opt-in rebase. The running project is never used as a conflict-resolution scratch tree. */
internal class AsmBaselineUpdateRepository(
    private val projectRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val augmentation: AsmSourceAugmentation = AsmSourceAugmentation.bundled(),
    private val assets: List<AsmAssetRange> = AsmAssetManifest.loadBundled(),
    private val nativeSymbols: List<AsmNativeSymbol>? = null,
    private val validateCandidate: ((String, ByteArray) -> Unit)? = null,
) {
    fun prepare(projectFilePath: String, reference: AsmReferenceWorkspace): AsmBaselineUpdatePlan {
        val current = requireNotNull(projectRepository.load(projectFilePath)) { "Repair Project ASM before updating its baseline" }
        val previous = requireNotNull(AsmSourceBaseline.load(current.root))
        require(previous.reference.romSha256 == reference.metadata.romSha256) { "The new baseline targets a different base ROM" }
        val captured = capture(current.root)
        val before = captured.mapValues { AsmVerifiedCache.hash(it.value) }
        require(before["base/manifest.json"] == previous.id) { "The baseline changed while preparing the update. Preview again." }
        require(captured["modules/.order"]?.toString(Charsets.UTF_8)?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList().orEmpty() ==
            current.projectModuleFileIds.map { it.removePrefix(ASM_PROJECT_MODULE_PREFIX) }) { "Module order changed while preparing the update" }
        val parent = File(ProjectPaths.forProject(projectFilePath).cache, "asm-update-drafts").apply { mkdirs() }
        val staging = Files.createTempDirectory(parent.toPath(), "draft-").toFile()
        try {
            val candidateRoot = File(staging, "asm")
            captured.forEach { (id, bytes) -> File(candidateRoot, id).apply { parentFile.mkdirs(); writeBytes(bytes) } }
            val oldRoot = File(staging, "old/src")
            previous.extract(candidateRoot, oldRoot)
            // Only a private staging copy is removed. The live project remains pinned throughout review.
            check(File(candidateRoot, "base").deleteRecursively())
            val candidate = AsmSourceBaseline.create(candidateRoot, reference, augmentation, assets, nativeSymbols)
            require(previous.augmentationRevision != candidate.augmentationRevision || previous.sourceHashes != candidate.sourceHashes ||
                previous.reference.commit != candidate.reference.commit || previous.symbolNames != candidate.symbolNames) {
                "This project already has the current ASM baseline (${candidate.augmentationRevision})"
            }
            val newRoot = File(staging, "new/src")
            candidate.extract(candidateRoot, newRoot)
            val old = readAsmSourceTree(oldRoot)
            val updated = readAsmSourceTree(newRoot)
            require(old.keys == updated.keys) { "This update changes the ASM file inventory and needs an explicit source-file migration. Nothing was changed." }
            val authored = old.toMutableMap()
            captured.filterKeys { it.startsWith("overrides/src/") }.forEach { (path, bytes) ->
                    val id = path.removePrefix("overrides/src/")
                    require(id in old) { "Override $id has no old baseline source" }
                    authored[id] = bytes.toString(Charsets.UTF_8)
            }
            current.projectModuleFileIds.forEach { id -> authored[id] = captured.getValue("modules/${id.removePrefix(ASM_PROJECT_MODULE_PREFIX)}").toString(Charsets.UTF_8) }
            val (normalizedOld, normalizedProject) = normalizeNames(previous, candidate, old, authored, staging)
            val files = updated.map { (id, text) ->
                val baselineChanged = old.getValue(id) != text
                val projectEdited = authored.getValue(id) != old.getValue(id)
                AsmBaselineFileUpdate(id, AsmThreeWayMerge.merge(normalizedOld.getValue(id), normalizedProject.getValue(id), text),
                    baselineChanged || projectEdited, baselineChanged, projectEdited,
                    baselineChanged && asmBaselineDocumentationOnly(old.getValue(id), text))
            } + current.projectModuleFileIds.map { id ->
                AsmBaselineFileUpdate(id, AsmTextMerge(listOf(AsmMergePiece(normalizedProject.getValue(id)))),
                    normalizedProject.getValue(id) != authored.getValue(id), baselineChanged = false, projectEdited = true)
            }
            require(inventory(current.root) == before) { "Project ASM changed while preparing the update. Preview it again." }
            return AsmBaselineUpdatePlan(projectFilePath, previous.augmentationRevision, candidate.augmentationRevision, files, staging, before, candidate)
        } catch (problem: Exception) {
            staging.deleteRecursively()
            throw problem
        }
    }

    fun discard(plan: AsmBaselineUpdatePlan) {
        if (!plan.consumed) { plan.consumed = true; plan.staging.deleteRecursively() }
    }

    fun apply(plan: AsmBaselineUpdatePlan, resolutions: Map<String, Map<Int, String>>, romBytes: ByteArray,
              onProgress: (String) -> Unit = {}): AsmBaselineUpdateResult {
        require(!plan.consumed && plan.staging.isDirectory) { "This baseline-update preview has expired" }
        val root = requireNotNull(projectRepository.rootFor(plan.projectFilePath))
        require(inventory(root) == plan.before) { "Project ASM changed after this preview. Nothing was overwritten; preview the update again." }
        val romBody = if (romBytes.size % 0x8000 == 0x200) romBytes.copyOfRange(0x200, romBytes.size) else romBytes
        require(AsmVerifiedCache.hash(romBody) == plan.candidate.reference.romSha256) { "The loaded ROM does not match this baseline" }
        require(resolutions.keys.all { file -> plan.files.any { it.fileId == file } }) { "Unknown merge resolution file" }
        val resolved = plan.files.associate { file ->
            val choices = resolutions[file.fileId].orEmpty()
            require(choices.keys.all { it in file.merge.conflicts.indices }) { "Unknown merge conflict in ${file.fileId}" }
            file.fileId to requireNotNull(file.merge.resolved(choices)) { "Resolve all conflicts in ${file.fileId} before applying" }
        }
        val candidateRoot = File(plan.staging, "asm")
        require(AsmSourceBaseline.load(candidateRoot)?.id == plan.candidate.id) { "The candidate baseline changed after preview; preview again." }
        // Rebuild only the staging override ledger; modules and their metadata remain cloned verbatim.
        File(candidateRoot, "overrides/src").takeIf { it.exists() }?.let { check(it.deleteRecursively()) }
        resolved.forEach { (id, text) ->
            val target = if (id.startsWith(ASM_PROJECT_MODULE_PREFIX)) {
                File(candidateRoot, "modules/${id.removePrefix(ASM_PROJECT_MODULE_PREFIX)}")
            } else {
                if (AsmVerifiedCache.hash(text) == plan.candidate.sourceHashes[id]) return@forEach
                File(candidateRoot, "overrides/src/$id")
            }
            target.parentFile.mkdirs()
            target.writeText(text)
        }
        val expectedAuthored = inventory(candidateRoot)
        val candidatePath = File(plan.staging, "Candidate.smedit").path
        val candidateRepository = AsmProjectWorkspaceRepository(assets, emptyList(), augmentation, nativeSymbols)
        // Retries after a failed build must not reuse the preceding candidate's generated mirrors.
        candidateRepository.cacheFor(candidatePath)?.takeIf { it.exists() }?.let { check(it.deleteRecursively()) }
        val referenceRoot = File(plan.staging, "new")
        val reference = AsmReferenceWorkspace(referenceRoot, plan.candidate.reference,
            AsmSourceParser().parse(File(referenceRoot, "src"), File(referenceRoot, "data").apply { mkdirs() }, emptyList()))
        val candidateWorkspace = candidateRepository.initializeFromRom(candidatePath, reference, romBytes,
            requireExistingBaseline = true, onProgress = onProgress)
        onProgress("Validating the merged ASM build before installation…")
        if (validateCandidate != null) validateCandidate.invoke(candidatePath, romBytes)
        else {
            val compiled = AsmProjectCompiler(candidateRepository).compile(candidatePath, romBytes, onProgress = onProgress)
            require(AsmVerifiedCache.hash(compiled.referenceRomBody) == plan.candidate.reference.romSha256) {
                "The new immutable ASM baseline does not rebuild the expected base ROM"
            }
        }
        return projectRepository.withBaselineUpdateLock(plan.projectFilePath) {
            require(inventory(root) == plan.before) { "Project ASM changed during validation. Nothing was overwritten; preview again." }
            require(inventory(candidateRoot) == expectedAuthored) { "The candidate's authored source changed during validation" }
            require(plan.candidate.matches(File(candidateWorkspace.originalDirectory, "src"))) { "The candidate baseline changed during validation" }
            resolved.forEach { (id, text) ->
                require(File(candidateWorkspace.workingDirectory, "src/$id").readText() == text) { "The merged candidate changed during validation: $id" }
            }
            onProgress("Backing up and installing the validated ASM baseline…")
            val backup = try { AsmBaselineUpdateTransaction.install(plan.projectFilePath, plan.staging) }
            catch (problem: Exception) {
                plan.consumed = true // A partially moved draft must never be reused as a new source authority.
                throw IllegalStateException("ASM installation failed; recovery will restore the previous workspace. Reopen the project and preview again. Backups are in asm-backups and smedit/asm-update-backups.", problem)
            }
            plan.consumed = true
            AsmBaselineUpdateResult(requireNotNull(projectRepository.load(plan.projectFilePath)), backup)
        }
    }

    private fun normalizeNames(old: AsmSourceBaseline, updated: AsmSourceBaseline, base: Map<String, String>,
                               project: Map<String, String>, staging: File): Pair<Map<String, String>, Map<String, String>> {
        if (old.symbolNames == updated.symbolNames) return base to project
        fun image(name: String, texts: Map<String, String>): AsmSourceImage {
            val directory = File(staging, name)
            texts.forEach { (id, text) -> File(directory, id).apply { parentFile.mkdirs(); writeText(text) } }
            return AsmSourceImage.capture(directory)
        }
        val oldImage = image("binding-base", base)
        val renames = oldImage.index.labels.filter { label ->
            label.localScope == null && Regex("^\\s*${Regex.escape(label.name)}:")
                .containsMatchIn(asmExecutableCode(oldImage.index.file(label.fileId)!!.lines[label.lineIndex]))
        }.mapNotNull { label ->
            val next = updated.symbols.active(old.symbols.canonical(label.name))
            if (next == label.name) null else AsmSourceRename(label.name, next)
        }
        val recipe = AsmSourceAugmentation("baseline-name-migration", renames = renames)
        val native = old.bindings.nativeSymbols.map { it.copy(name = old.symbols.active(it.name)) }
        // Use the strict refactoring engine over the complete authored image, including custom modules.
        // Parameter/scope collisions or unclassified references fail closed rather than doing text replacement.
        return recipe.apply(oldImage, native).texts to recipe.apply(image("binding-project", project), native).texts
    }

    companion object {
        internal fun inventory(root: File): Map<String, String> = capture(root).mapValues { AsmVerifiedCache.hash(it.value) }

        private fun capture(root: File): Map<String, ByteArray> {
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "ASM update requires a real authored source directory" }
            var total = 0L
            var count = 0
            return root.walkTopDown().mapNotNull { file ->
                require(!Files.isSymbolicLink(file.toPath()) && (file.isDirectory || file.isFile)) { "ASM update cannot follow links or special files: $file" }
                if (!file.isFile) null else {
                    require(++count <= 16384 && file.length() <= 128L * 1024 * 1024) { "Authored ASM snapshot is too large" }
                    val bytes = file.inputStream().use { it.readNBytes(128 * 1024 * 1024 + 1) }
                    require(bytes.size <= 128 * 1024 * 1024) { "Authored ASM file is too large" }
                    total += bytes.size
                    require(total <= 256L * 1024 * 1024) { "Authored ASM snapshot is too large" }
                    file.relativeTo(root).invariantSeparatorsPath to bytes
                }
            }.toMap()
        }
    }
}

/** A lexical summary, not a claim of runtime equivalence; candidate compilation is still required. */
internal fun asmBaselineDocumentationOnly(before: String, after: String): Boolean {
    fun statements(text: String) = text.lineSequence().map { asmStatementSignature(asmExecutableCode(it)) }
        .filter(String::isNotBlank).toList()
    return statements(before) == statements(after)
}
