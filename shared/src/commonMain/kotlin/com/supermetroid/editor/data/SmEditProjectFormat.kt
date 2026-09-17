package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Centralized project decoding so a missing schema marker is never mistaken for a new project. */
object SmEditProjectFormat {
    const val LEGACY_FORMAT_VERSION = 1

    fun decode(json: Json, text: String): SmEditProject {
        val root = json.parseToJsonElement(text).jsonObject
        val project = json.decodeFromJsonElement(SmEditProject.serializer(), root)
        project.projectFormatVersion = root["projectFormatVersion"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: LEGACY_FORMAT_VERSION
        require(project.projectFormatVersion in LEGACY_FORMAT_VERSION..SmEditProject.CURRENT_PROJECT_FORMAT_VERSION) {
            "Project format ${project.projectFormatVersion} is newer than this version of SMEDIT supports " +
                "(${SmEditProject.CURRENT_PROJECT_FORMAT_VERSION})"
        }
        return project
    }
}
