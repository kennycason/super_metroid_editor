package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Centralized decoding and compatibility checks for native `.smedit` project files. */
object SmEditProjectFormat {
    /** Markerless beta files predate explicit schema versioning but remain readable. */
    const val PRE_VERSIONED_FILE_FORMAT = 1

    fun decode(json: Json, text: String): SmEditProject {
        val root = json.parseToJsonElement(text).jsonObject
        val project = json.decodeFromJsonElement(SmEditProject.serializer(), root)
        project.projectFormatVersion = root["projectFormatVersion"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: PRE_VERSIONED_FILE_FORMAT
        require(project.projectFormatVersion in PRE_VERSIONED_FILE_FORMAT..SmEditProject.CURRENT_PROJECT_FORMAT_VERSION) {
            val relation = if (project.projectFormatVersion > SmEditProject.CURRENT_PROJECT_FORMAT_VERSION) {
                "newer than"
            } else {
                "older than"
            }
            "Project schema ${project.projectFormatVersion} is $relation this version of SMEDIT supports " +
                "(${PRE_VERSIONED_FILE_FORMAT}..${SmEditProject.CURRENT_PROJECT_FORMAT_VERSION})"
        }
        return project
    }
}
