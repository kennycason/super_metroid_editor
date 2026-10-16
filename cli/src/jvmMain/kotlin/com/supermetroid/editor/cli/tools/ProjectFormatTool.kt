package com.supermetroid.editor.cli.tools

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmEditProjectFormat
import kotlinx.serialization.json.*
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Offline maintenance only. OPEN and the build pipeline never invoke this converter. */
internal data class ProjectNormalization(val file: File, val before: String, val after: String)

internal fun planProjectNormalization(file: File): ProjectNormalization {
    require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.extension == "smedit") { "Select a real .smedit file: $file" }
    val before = file.readText()
    val original = Json.parseToJsonElement(before).jsonObject
    val format = original["projectFormat"]
    require(format == null || format == JsonPrimitive(SmEditProject.PROJECT_FORMAT)) { "Unknown project format: $file" }
    val oldVersion = original["projectFormatVersion"]
    require(oldVersion == null || oldVersion is JsonPrimitive && !oldVersion.isString && oldVersion.intOrNull in 1..21) {
        "Unknown development format version: $file"
    }
    if (format != null) SmEditProjectFormat.decode(before) // Never relabel an unknown future format.
    val oldId = original["projectId"]
    require(oldId == null || oldId == JsonNull || oldId is JsonPrimitive && oldId.isString) {
        "Project identity must be a string, not a value to discard: $file"
    }
    val prepared = original.toMutableMap().apply {
        put("projectFormat", JsonPrimitive(SmEditProject.PROJECT_FORMAT))
        put("projectFormatVersion", JsonPrimitive(SmEditProject.CURRENT_PROJECT_FORMAT_VERSION))
        if ((get("projectId") as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) {
            put("projectId", JsonPrimitive(UUID.nameUUIDFromBytes(file.canonicalPath.toByteArray()).toString()))
        }
    }
    // Strict decoding refuses unknown fields and invalid enums; no ignore/coercion/drop pass.
    val project = SmEditProjectFormat.decode(JsonObject(prepared).toString())
    val after = SmEditProjectFormat.encode(project)
    val normalized = Json.parseToJsonElement(after).jsonObject
    fun requirePreserved(old: JsonElement, current: JsonElement?, path: String) {
        when (old) {
            is JsonObject -> {
                require(current is JsonObject) { "Object changed at $path" }
                old.forEach { (key, value) -> requirePreserved(value, current[key], "$path.$key") }
            }
            is JsonArray -> {
                require(current is JsonArray && current.size == old.size) { "List membership changed at $path" }
                old.forEachIndexed { index, value -> requirePreserved(value, current[index], "$path[$index]") }
            }
            else -> {
                val equalNumber = old is JsonPrimitive && current is JsonPrimitive && !old.isString && !current.isString &&
                    runCatching { BigDecimal(old.content).compareTo(BigDecimal(current.content)) == 0 }.getOrDefault(false)
                require(old == current || equalNumber) { "Value changed at $path" }
            }
        }
    }
    // Only the format envelope and a genuinely missing project ID may change.
    original.filterKeys { it !in setOf("projectFormat", "projectFormatVersion") }.forEach { (key, value) ->
        if (key != "projectId" || (value as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
            requirePreserved(value, normalized[key], key)
    }
    require(SmEditProjectFormat.encode(SmEditProjectFormat.decode(after)) == after) { "Normalization is not stable: $file" }
    return ProjectNormalization(file, before, after)
}

internal fun applyProjectNormalizations(plans: List<ProjectNormalization>, log: (String) -> Unit): File? {
    val changed = plans.filter { it.before != it.after }
    if (changed.isEmpty()) return null
    require(changed.map { it.file.canonicalPath }.distinct().size == changed.size) { "Duplicate project targets." }
    changed.forEach { require(it.file.readText() == it.before) { "Project changed since preview: ${it.file}" } }
    val recovery = Files.createTempDirectory("smedit-project-baseline-").toFile()
    changed.forEachIndexed { index, plan ->
        val backup = File(recovery, "$index-${plan.file.name}")
        plan.file.copyTo(backup, overwrite = false)
        check(backup.readText() == plan.before) { "Recovery copy verification failed: ${plan.file}" }
    }
    log("Recovery copies: ${recovery.absolutePath}")
    changed.forEach { plan ->
        require(plan.file.readText() == plan.before) { "Project changed during normalization: ${plan.file}" }
        val staging = Files.createTempFile(plan.file.absoluteFile.parentFile.toPath(), ".smedit-normalize-", ".tmp")
        try {
            Files.writeString(staging, plan.after)
            Files.move(staging, plan.file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staging) }
        log("Updated ${plan.file}")
    }
    return recovery
}

fun main(args: Array<String>) {
    require(args.count { it in setOf("--apply", "--check") } <= 1) { "Choose preview, --check or --apply." }
    require(args.filter { it.startsWith("--") }.all { it in setOf("--apply", "--check") }) { "Unknown normalization option." }
    val targets = args.filterNot { it.startsWith("--") }.map(::File)
    require(targets.isNotEmpty()) { "Supply project files or a projects directory." }
    // A collection contains one project per child folder. Historical archives/backups stay untouched.
    val files = targets.flatMap { target -> if (target.isDirectory)
        target.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .flatMap { it.listFiles().orEmpty().filter { file -> file.extension == "smedit" } }
        else listOf(target)
    }.sortedBy { it.absolutePath }
    require(files.isNotEmpty()) { "No active project files found." }
    val plans = files.map(::planProjectNormalization) // Validate every input before touching any file.
    plans.forEach { println("${if (it.before == it.after) "Current" else "Would update"} ${it.file}") }
    when {
        "--apply" in args -> applyProjectNormalizations(plans, ::println)
        "--check" in args -> require(plans.all { it.before == it.after }) { "Project files need explicit normalization." }
        else -> println("Preview only. Close the editor and repeat with --apply to write these changes.")
    }
}
