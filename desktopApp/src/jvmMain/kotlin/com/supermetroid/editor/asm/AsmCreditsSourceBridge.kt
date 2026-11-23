package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectCreditsGroup
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.CreditsData
import com.supermetroid.editor.rom.RomParser
import java.io.File

/**
 * Strict, generated project module for custom end credits. Only the declaration
 * block is semantic input. The compressed tilemap, relocated instruction list,
 * and bank-$8B pointer are regenerated together and independently audited by the
 * normal project-module compiler.
 */
internal object AsmCreditsSourceModule {
    const val FILE = "project/smedit_credits.asm"
    const val VERSION = 1
    const val INST_LIST_SNES = 0x8CF3E9

    private const val SIGNATURE = "; SMEDIT-CREDITS-V1"
    private const val DECLARATIONS_BEGIN = "; --- SMEDIT credit declarations begin ---"
    private const val DECLARATIONS_END = "; --- SMEDIT credit declarations end ---"
    private const val GENERATED_BEGIN = "; --- generated credits payload begin (do not edit) ---"
    private const val GENERATED_END = "; --- generated credits payload end ---"
    private const val GROUP = "; CREDIT GROUP: "
    private const val NAME = "; CREDIT NAME: "

    fun parse(text: String, parser: RomParser): List<ProjectCreditsGroup> {
        require(text.lineSequence().count { it.trimEnd() == SIGNATURE } == 1) {
            "$FILE needs exactly one $SIGNATURE marker."
        }
        val declarationStart = text.indexOf(DECLARATIONS_BEGIN)
        val declarationEnd = text.indexOf(DECLARATIONS_END)
        val generatedStart = text.indexOf(GENERATED_BEGIN)
        val generatedEnd = text.indexOf(GENERATED_END)
        require(declarationStart >= 0 && declarationEnd > declarationStart &&
            generatedStart > declarationEnd && generatedEnd > generatedStart &&
            text.indexOf(DECLARATIONS_BEGIN, declarationStart + 1) < 0 &&
            text.indexOf(DECLARATIONS_END, declarationEnd + 1) < 0 &&
            text.indexOf(GENERATED_BEGIN, generatedStart + 1) < 0 &&
            text.indexOf(GENERATED_END, generatedEnd + 1) < 0
        ) { "$FILE has a missing, duplicate, or reordered managed block marker." }

        val body = text.substring(
            declarationStart + DECLARATIONS_BEGIN.length,
            declarationEnd,
        )
        val groups = mutableListOf<ProjectCreditsGroup>()
        body.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith(GROUP) -> {
                    val header = line.removePrefix(GROUP)
                    groups += ProjectCreditsGroup(header, mutableListOf())
                }
                line.startsWith(NAME) -> {
                    require(groups.isNotEmpty()) {
                        "$FILE declaration line ${index + 1} has a name before its group."
                    }
                    groups.last().names += line.removePrefix(NAME)
                }
                else -> error(
                    "$FILE declaration line ${index + 1} must use '$GROUP' or '$NAME'.",
                )
            }
        }
        val parsed = requireNotNull(CreditsData.parseCredits(parser)) {
            "The live ROM credits are unsupported; $FILE cannot be projected safely."
        }
        val model = groups.map { CreditsData.CreditGroup(it.header, it.names.toList()) }
        CreditsData.validateGroups(model, parsed.freeRowIndices.size).takeIf(List<String>::isNotEmpty)?.let {
            error(it.joinToString("; "))
        }
        return groups
    }

    fun render(parser: RomParser, groups: List<ProjectCreditsGroup>): String {
        val model = groups.map { CreditsData.CreditGroup(it.header, it.names.toList()) }
        val parsed = requireNotNull(CreditsData.parseCredits(parser)) {
            "The live ROM credits are unsupported; the managed source module cannot be generated."
        }
        CreditsData.validateGroups(model, parsed.freeRowIndices.size).takeIf(List<String>::isNotEmpty)?.let {
            error(it.joinToString("; "))
        }
        val export = if (model.isEmpty()) null else requireNotNull(
            CreditsData.buildCreditsExport(parser, model, INST_LIST_SNES),
        ) {
            "Credits exceed the native compressed tilemap envelope or bank-$8C instruction space."
        }
        return buildString {
            appendLine("; Project module: SMEDIT credits")
            appendLine("; SMEDIT description: Managed end-credit declarations for the Credits visual editor")
            appendLine("; This file is reciprocal with Text Editor > End Credits.")
            appendLine("; Edit only CREDIT GROUP / CREDIT NAME lines; Save regenerates the payload.")
            appendLine(SIGNATURE)
            appendLine(DECLARATIONS_BEGIN)
            groups.forEach { group ->
                append(GROUP).appendLine(group.header)
                group.names.forEach { name -> append(NAME).appendLine(name) }
            }
            appendLine(DECLARATIONS_END)
            appendLine()
            appendLine(GENERATED_BEGIN)
            if (export == null) {
                appendLine("; No custom credit groups. The native ROM data remains unchanged.")
            } else {
                appendLine("org \$${CreditsData.CREDITS_TILEMAP_SNES.hex(6)}")
                appendLine("SMEDIT_CreditsTilemap:")
                appendBytes(export.tilemapBytes)
                appendLine("warnpc \$${(CreditsData.CREDITS_TILEMAP_SNES + CreditsData.CREDITS_TILEMAP_CAPACITY).hex(6)}")
                appendLine()
                appendLine("org \$${INST_LIST_SNES.hex(6)}")
                appendLine("SMEDIT_CreditsInstructions:")
                appendBytes(export.instListBytes)
                appendLine("warnpc \$8D0000")
                appendLine()
                appendLine("org \$${CreditsData.CREDITS_OBJ_DEF_INSTLIST_SNES.hex(6)}")
                appendLine("    dw SMEDIT_CreditsInstructions")
            }
            appendLine(GENERATED_END)
        }
    }

    /** Cold/headless guard: persisted JSON and the authored module must identify
     * the same owner and declarations before assembly is allowed. */
    fun validateForBuild(project: SmEditProject, projectFilePath: String, parser: RomParser) {
        if (project.asmWorkspace.creditsSourceVersion == 0) return
        require(project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Source-authored credits require ASM source mode."
        }
        require(project.asmWorkspace.creditsSourceVersion == VERSION) {
            "Unsupported credits source version ${project.asmWorkspace.creditsSourceVersion}."
        }
        val workspace = requireNotNull(AsmProjectWorkspaceRepository().loadForBuild(projectFilePath)) {
            "Project ASM workspace is unavailable."
        }
        val module = File(workspace.workingDirectory, "src/$FILE")
        require(module.isFile && FILE !in workspace.disabledProjectModuleFileIds) {
            "Source-authored credits require the enabled $FILE module."
        }
        val text = module.readText()
        val groups = parse(text, parser)
        require(groups.sameCredits(project.creditsGroups)) {
            "Credits JSON and $FILE declarations disagree. Reopen the project and reconcile the source draft."
        }
        require(text == render(parser, groups)) {
            "$FILE has a stale or edited generated payload. Open it in ASM and Save before building."
        }
    }

    private fun StringBuilder.appendBytes(bytes: ByteArray) {
        bytes.asList().chunked(16).forEach { row ->
            append("    db ")
            appendLine(row.joinToString(",") { "\$${(it.toInt() and 0xFF).hex(2)}" })
        }
    }

    private fun Int.hex(width: Int): String = toString(16).uppercase().padStart(width, '0')
}

internal data class AsmCreditsVisualEdit(
    val beforeSource: String,
    val afterSource: String,
    val sourceChange: AsmSourceTextChange,
)

internal class AsmCreditsSourceBridge(
    private val workspace: AsmWorkspaceState,
    private val parser: RomParser,
    private val project: () -> SmEditProject,
    private val onChanged: () -> Unit,
    private val onProblem: (String?) -> Unit,
) {
    val installed: Boolean get() = AsmCreditsSourceModule.FILE in workspace.projectModuleFileIds
    val sourceFileIds: Set<String> get() = if (installed) setOf(AsmCreditsSourceModule.FILE) else emptySet()

    fun reconcile() {
        val owner = project()
        if (!installed) {
            require(owner.asmWorkspace.creditsSourceVersion == 0) {
                "Source-authored credits are missing ${AsmCreditsSourceModule.FILE}."
            }
            onProblem(null)
            return
        }
        require(AsmCreditsSourceModule.FILE !in workspace.disabledProjectModuleFileIds) {
            "${AsmCreditsSourceModule.FILE} must remain enabled while it owns end credits."
        }
        publish(workspace.projectSourceText(AsmCreditsSourceModule.FILE))
    }

    fun prepare(fileId: String, text: String): () -> String {
        if (fileId != AsmCreditsSourceModule.FILE) return { text }
        return {
            val groups = AsmCreditsSourceModule.parse(text, parser)
            AsmCreditsSourceModule.render(parser, groups)
        }
    }

    fun sourceChanged(fileId: String, text: String) {
        if (fileId != AsmCreditsSourceModule.FILE) return
        runCatching { publish(text) }
            .onFailure { onProblem(it.message ?: "Invalid credits source draft") }
    }

    fun applyVisualEdit(
        before: List<ProjectCreditsGroup>,
        after: List<ProjectCreditsGroup>,
    ): AsmCreditsVisualEdit {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Enable Project ASM source mode before authoring credits in ASM."
        }
        if (!installed) {
            workspace.ensureCreditsModule(AsmCreditsSourceModule.render(parser, before))
        }
        require(AsmCreditsSourceModule.FILE !in workspace.disabledProjectModuleFileIds) {
            "Enable ${AsmCreditsSourceModule.FILE} before editing credits."
        }
        val current = workspace.projectSourceText(AsmCreditsSourceModule.FILE)
        require(AsmCreditsSourceModule.parse(current, parser).sameCredits(before)) {
            "Credits source changed; reload the Credits editor before applying this edit."
        }
        val replacement = AsmCreditsSourceModule.render(parser, after)
        workspace.replaceProjectSource(AsmCreditsSourceModule.FILE, current, replacement)
        val change = requireNotNull(workspace.sourceUndoHead(AsmCreditsSourceModule.FILE)) {
            "Credits source history was not recorded."
        }
        owner.asmWorkspace.creditsSourceVersion = AsmCreditsSourceModule.VERSION
        onProblem(null)
        return AsmCreditsVisualEdit(current, replacement, change)
    }

    fun undo(expected: String, change: AsmSourceTextChange) {
        require(workspace.projectSourceText(AsmCreditsSourceModule.FILE) == expected &&
            workspace.sourceUndoHead(AsmCreditsSourceModule.FILE) === change
        ) { "Credits source changed; undo the newer edit in ASM first." }
        check(workspace.undoSourceEdit(AsmCreditsSourceModule.FILE)) {
            workspace.error ?: "Could not undo the credits source edit."
        }
    }

    fun redo(expected: String, change: AsmSourceTextChange) {
        require(workspace.projectSourceText(AsmCreditsSourceModule.FILE) == expected &&
            workspace.sourceRedoHead(AsmCreditsSourceModule.FILE) === change
        ) { "Credits source changed; redo this edit in ASM instead." }
        check(workspace.redoSourceEdit(AsmCreditsSourceModule.FILE)) {
            workspace.error ?: "Could not redo the credits source edit."
        }
    }

    private fun publish(text: String) {
        val groups = AsmCreditsSourceModule.parse(text, parser)
        val owner = project()
        val changed = !groups.sameCredits(owner.creditsGroups) ||
            owner.asmWorkspace.creditsSourceVersion != AsmCreditsSourceModule.VERSION
        if (changed) {
            owner.creditsGroups.clear()
            owner.creditsGroups.addAll(groups.deepCreditsCopy())
            owner.asmWorkspace.creditsSourceVersion = AsmCreditsSourceModule.VERSION
            onChanged()
        }
        onProblem(null)
    }
}

private fun List<ProjectCreditsGroup>.deepCreditsCopy(): List<ProjectCreditsGroup> =
    map { it.copy(names = it.names.toMutableList()) }

private fun List<ProjectCreditsGroup>.sameCredits(other: List<ProjectCreditsGroup>): Boolean =
    size == other.size && zip(other).all { (left, right) ->
        left.header == right.header && left.names == right.names
    }
