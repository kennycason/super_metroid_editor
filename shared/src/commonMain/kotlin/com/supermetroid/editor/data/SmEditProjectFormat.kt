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
        require(project.introSequence.scenes.size in 1..6) { "Intro sequence must contain 1–6 scenes." }
        require(project.introSequence.scenes.map { it.id }.all { it.isNotBlank() } &&
            project.introSequence.scenes.map { it.id }.distinct().size == project.introSequence.scenes.size) {
            "Intro scenes must have unique nonblank identities."
        }
        require(project.introSequence.scenes.all { it.durationFrames in 30..3600 }) {
            "Intro scene durations must be between 30 and 3600 frames."
        }
        require(project.introSequence.scenes.all { it.imageFrame >= 0 }) {
            "Intro scene image frame indexes must not be negative."
        }
        require(project.introSequence.scenes.all {
            it.fadeInFrames in 0..255 && it.fadeOutFrames in 0..255 &&
                it.typewriterFramesPerCharacter in 1..60 &&
                it.postRevealInputDelayFrames in 0..255 &&
                it.vignetteFrame >= 0 && it.vignetteFrameDelay in 1..255 &&
                it.vignetteLoopCount in 1..255 && it.vignetteDurationFrames in 30..3600 &&
                it.vignetteFadeInFrames in 0..255 && it.vignetteFadeOutFrames in 0..255 &&
                it.vignettePanStartX in -255..255 && it.vignettePanStartY in -255..255 &&
                it.vignettePanEndX in -255..255 && it.vignettePanEndY in -255..255
        }) { "Intro animation, fade, duration, or pan settings are outside their supported range." }
        require(project.introSequence.scenes.all {
            it.effectiveImageSource != IntroImageSource.UPLOADED_PNG || it.imagePngBase64.isNotBlank()
        }) {
            "An intro scene selected an uploaded PNG but contains no PNG data."
        }
        require(project.introSequence.scenes.all {
            it.effectiveVignetteSource != IntroImageSource.UPLOADED_PNG || it.vignetteImagePngBase64.isNotBlank()
        }) {
            "An intro vignette selected an uploaded PNG but contains no PNG data."
        }
    }
}
