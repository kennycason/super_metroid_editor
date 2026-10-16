package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One current project format, shared by desktop and CLI. No implicit beta migrations. */
object SmEditProjectFormat {
    private val json = Json { encodeDefaults = true }

    fun decode(text: String): SmEditProject {
        val root = json.parseToJsonElement(text).jsonObject
        val format = (root["projectFormat"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = (root["projectFormatVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(format == SmEditProject.PROJECT_FORMAT && version == SmEditProject.CURRENT_PROJECT_FORMAT_VERSION) {
            "Unsupported project format. Expected ${SmEditProject.PROJECT_FORMAT} version ${SmEditProject.CURRENT_PROJECT_FORMAT_VERSION}. " +
                "Older development projects must be updated explicitly; this editor does not migrate them on open."
        }
        for (key in listOf("projectId", "romPath")) {
            val value = root[key] as? JsonPrimitive
            require(value?.isString == true && value.content.isNotBlank()) { "$key must be an explicit nonblank string." }
        }
        return json.decodeFromJsonElement(SmEditProject.serializer(), root).also(::validate)
    }

    fun encode(project: SmEditProject): String {
        validate(project)
        return json.encodeToString(SmEditProject.serializer(), project)
    }

    private fun validate(project: SmEditProject) {
        require(project.projectFormat == SmEditProject.PROJECT_FORMAT &&
            project.projectFormatVersion == SmEditProject.CURRENT_PROJECT_FORMAT_VERSION) { "Unsupported project format." }
        require(project.projectId.isNotBlank()) { "Project identity must not be blank." }
        require(project.romPath.isNotBlank()) { "Project base ROM path must not be blank." }
    }
}
