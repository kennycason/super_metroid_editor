package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.IntroAdvanceMode
import com.supermetroid.editor.data.IntroImageFit
import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroPortraitAnimation
import com.supermetroid.editor.data.IntroScene
import com.supermetroid.editor.data.IntroSceneLayout
import com.supermetroid.editor.data.IntroTextReveal
import com.supermetroid.editor.data.IntroTransition
import com.supermetroid.editor.data.IntroVignetteAnimation
import com.supermetroid.editor.data.IntroVignetteTiming
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.effectiveImageSource
import com.supermetroid.editor.data.effectiveVignetteSource
import com.supermetroid.editor.rom.IntroRomImageResolver
import com.supermetroid.editor.rom.IntroSceneCodec
import com.supermetroid.editor.rom.RomParser
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class IntroPreviewPhase { NARRATION, VIGNETTE }

@Composable
internal fun IntroSceneEditor(
    patch: SmPatch,
    editor: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(romParser, patch.id) { editor.initializeIntroSequenceFromRomIfPristine(romParser) }
    val revision = editor.introSequenceVersion
    val scenes = editor.project.introSequence.scenes
    var selectedId by remember { mutableStateOf(scenes.first().id) }
    if (scenes.none { it.id == selectedId }) selectedId = scenes.first().id
    val selected = scenes.first { it.id == selectedId }
    val selectedIndex = scenes.indexOfFirst { it.id == selectedId }
    val spriteEditRevision = editor.editVersion
    val spritePaletteRevision = editor.paletteVersion
    val asmVisualRevision = editor.asmVisualSourceRevision
    val effectiveParser = remember(romParser, spriteEditRevision, spritePaletteRevision, asmVisualRevision) {
        romParser?.let { runCatching { editor.nativeSprites.previewParser(it) }.getOrDefault(it) }
    }
    var phase by remember { mutableStateOf(IntroPreviewPhase.NARRATION) }
    var playing by remember { mutableStateOf(false) }
    var playingAll by remember { mutableStateOf(false) }
    var playhead by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    // Phase is a playback boundary. Restart the clock coroutine when Narration
    // advances to Image (or the user switches tabs) so it never retains the
    // completed narration phase's captured state.
    LaunchedEffect(playing, selected.id, revision, phase) {
        while (playing) {
            delay(100)
            playhead += 6
            if (playhead >= previewPhaseFrames(selected, phase)) {
                if (phase == IntroPreviewPhase.NARRATION && selected.effectiveVignetteSource != IntroImageSource.NONE) {
                    phase = IntroPreviewPhase.VIGNETTE
                    playhead = 0
                } else if (playingAll) {
                    val next = scenes.indexOfFirst { it.id == selected.id } + 1
                    if (next in scenes.indices) {
                        selectedId = scenes[next].id
                        phase = IntroPreviewPhase.NARRATION
                        playhead = 0
                    } else {
                        playing = false
                        playingAll = false
                    }
                } else {
                    playing = false
                }
            }
        }
    }

    Row(modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(198.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Story Frames", fontWeight = FontWeight.Bold)
            Text("${scenes.size} / 6 · narration plus optional image", color = MaterialTheme.colorScheme.onSurfaceVariant)
            scenes.forEachIndexed { index, scene ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedId = scene.id; phase = IntroPreviewPhase.NARRATION; playhead = 0
                        playing = false; playingAll = false
                    }
                        .then(if (scene.id == selectedId) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, INTRO_CARD_SHAPE) else Modifier),
                    color = if (scene.id == selectedId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = INTRO_CARD_SHAPE,
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(frameCardTitle(scene.name, index), fontWeight = FontWeight.SemiBold)
                        Text(sceneSummary(scene), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        editor.addIntroScene(selectedId)?.let {
                            selectedId = it.id; phase = IntroPreviewPhase.NARRATION; playhead = 0
                            playing = false; playingAll = false
                        }
                    },
                    enabled = scenes.size < 6,
                    modifier = Modifier.weight(1f).height(36.dp), shape = INTRO_BUTTON_SHAPE,
                    contentPadding = INTRO_COMPACT_BUTTON_PADDING,
                ) { Text("Add", maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = {
                        if (editor.removeIntroScene(selectedId)) {
                            selectedId = editor.project.introSequence.scenes.first().id
                            phase = IntroPreviewPhase.NARRATION; playhead = 0
                            playing = false; playingAll = false
                        }
                    },
                    enabled = scenes.size > 1,
                    modifier = Modifier.weight(1f).height(36.dp), shape = INTRO_BUTTON_SHAPE,
                    contentPadding = INTRO_COMPACT_BUTTON_PADDING,
                ) { Text("Remove", maxLines = 1, softWrap = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { editor.moveIntroScene(selectedId, -1) },
                    enabled = scenes.indexOfFirst { it.id == selectedId } > 0,
                    modifier = Modifier.weight(1f).height(36.dp), shape = INTRO_BUTTON_SHAPE,
                    contentPadding = INTRO_COMPACT_BUTTON_PADDING,
                ) { Text("Up") }
                OutlinedButton(
                    onClick = { editor.moveIntroScene(selectedId, 1) },
                    enabled = scenes.indexOfFirst { it.id == selectedId } < scenes.lastIndex,
                    modifier = Modifier.weight(1f).height(36.dp), shape = INTRO_BUTTON_SHAPE,
                    contentPadding = INTRO_COMPACT_BUTTON_PADDING,
                ) { Text("Down") }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PhaseButton("Narration", phase == IntroPreviewPhase.NARRATION) {
                    phase = IntroPreviewPhase.NARRATION; playhead = 0; playing = false; playingAll = false
                }
                // Keep Image reachable even when it is empty: its settings are
                // where the user selects a native image or imports a PNG.
                PhaseButton("Image", phase == IntroPreviewPhase.VIGNETTE) {
                    phase = IntroPreviewPhase.VIGNETTE; playhead = 0; playing = false; playingAll = false
                }
                OutlinedButton(
                    onClick = {
                        if (playing && !playingAll) playing = false
                        else { playingAll = false; playhead = 0; playing = true }
                    },
                    shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING,
                ) { Text(if (playing && !playingAll) "Pause" else "Play") }
                OutlinedButton(
                    onClick = {
                        if (playing && playingAll) {
                            playing = false
                            playingAll = false
                        } else {
                            selectedId = scenes.first().id
                            phase = IntroPreviewPhase.NARRATION
                            playhead = 0
                            playingAll = true
                            playing = true
                        }
                    },
                    shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING,
                ) { Text(if (playing && playingAll) "Pause All" else "Play All") }
            }
            Spacer(Modifier.height(8.dp))
            IntroPreview(
                selected, phase, playhead, playing, revision, effectiveParser,
                spriteEditRevision, spritePaletteRevision, Modifier.fillMaxWidth().weight(1f),
            )
        }

        Column(
            Modifier.width(348.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (phase == IntroPreviewPhase.NARRATION) "Narration settings" else "Image settings", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        if (editor.restoreIntroSequenceDefaults(romParser)) {
                            selectedId = editor.project.introSequence.scenes.first().id
                            phase = IntroPreviewPhase.NARRATION
                            playing = false
                            playingAll = false
                            message = "Restored the native six-page story, portrait animation, and four images."
                        }
                    },
                    shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_COMPACT_BUTTON_PADDING,
                ) { Text("Restore defaults", maxLines = 1, softWrap = false) }
            }
            Text(
                if (patch.enabled) "Exports as six story frames with their interleaved image phases."
                else "Draft mode — enable the patch to replace the native intro after the opening card.",
                color = if (patch.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = frameEditorName(selected.name, selectedIndex),
                onValueChange = { value -> editor.updateIntroScene(selected.id, "Rename intro story frame") { it.name = value.take(40) } },
                label = { Text("Frame name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            if (phase == IntroPreviewPhase.NARRATION) {
                NarrationSettings(selected, editor, effectiveParser) { message = it }
            } else {
                VignetteSettings(selected, editor, effectiveParser) { message = it }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "Native visuals reference the same ROM graphics and palettes shown in Sprites. Mother Brain and discovery are managed visual emulations of the original gameplay demos; delivery, examination, portrait eyes, panning, fades, and OBJ loops follow their bank-\$8B data.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editor.asmNavigationEnabled) Text(
                "ASM mode emits the checked hook, runtime, and generated phase payloads. Scene settings stay authoritative and disabling the patch restores the original dispatcher.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NarrationSettings(scene: IntroScene, editor: EditorState, parser: RomParser?, onMessage: (String?) -> Unit) {
    val source = scene.effectiveImageSource
    var sourceExpanded by remember(scene.id) { mutableStateOf(false) }
    Text("Background", fontWeight = FontWeight.SemiBold)
    SourceMenu(source, listOf(IntroImageSource.NONE) + IntroRomImageResolver.sources +
        listOfNotNull(IntroImageSource.UPLOADED_PNG.takeIf { scene.imagePngBase64.isNotBlank() }), parser, sourceExpanded,
        { sourceExpanded = it }) { selected ->
        editor.updateIntroScene(scene.id, "Change narration background") {
            it.imageSource = selected
            it.imageFrame = IntroRomImageResolver.defaultFrame(selected)
            if (selected == IntroImageSource.NONE) it.layout = IntroSceneLayout.TEXT_ONLY
            else if (it.layout == IntroSceneLayout.TEXT_ONLY) it.layout = IntroSceneLayout.TEXT_OVER_IMAGE
            if (selected == IntroImageSource.ROM_SAMUS_PORTRAIT && it.portraitAnimation == IntroPortraitAnimation.OFF) {
                it.portraitAnimation = IntroPortraitAnimation.NATIVE_BLINK
            }
        }
    }
    SettingChoices("Layout", IntroSceneLayout.entries, scene.layout, ::layoutLabel,
        enabled = { it == IntroSceneLayout.TEXT_ONLY || source != IntroImageSource.NONE }) {
        editor.updateIntroScene(scene.id, "Change narration layout") { card -> card.layout = it }
    }
    if (source == IntroImageSource.ROM_SAMUS_PORTRAIT) {
        SettingChoices("Portrait eyes", IntroPortraitAnimation.entries, scene.portraitAnimation, ::portraitLabel) {
            editor.updateIntroScene(scene.id, "Change portrait animation") { card -> card.portraitAnimation = it }
        }
        Text("Native blink: open 128f → half 10f → closed 10f → half 10f, then an 80/8/8/8 loop. Page 6 defaults to the deadpan track.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingChoices("Text reveal", IntroTextReveal.entries, scene.textReveal, { if (it == IntroTextReveal.NATIVE_TYPEWRITER) "Typewriter" else "Instant" }) {
        editor.updateIntroScene(scene.id, "Change narration text reveal") { card -> card.textReveal = it }
    }
    if (scene.textReveal == IntroTextReveal.NATIVE_TYPEWRITER) NumericSetting(
        "Frames per character", scene.typewriterFramesPerCharacter, 1..60,
        "Vanilla English text uses 5 frames.",
    ) { value -> editor.updateIntroScene(scene.id, "Change typewriter speed") { it.typewriterFramesPerCharacter = value } }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val file = chooseIntroPng("Import Narration Background PNG") ?: return@Button
            importPng(file, onMessage) { imported ->
                editor.updateIntroScene(scene.id, "Import narration image") {
                    it.imagePngBase64 = imported.base64; it.imageFileName = file.name; it.imageSha256 = imported.sha256
                    it.imageSource = IntroImageSource.UPLOADED_PNG
                    if (it.layout == IntroSceneLayout.TEXT_ONLY) it.layout = IntroSceneLayout.TEXT_OVER_IMAGE
                }
            }
        }, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text(if (scene.imagePngBase64.isBlank()) "Import PNG…" else "Replace PNG…") }
        OutlinedButton(onClick = {
            editor.updateIntroScene(scene.id, "Clear narration image") {
                it.imagePngBase64 = ""; it.imageFileName = ""; it.imageSha256 = ""; it.imageSource = IntroImageSource.NONE
                it.layout = IntroSceneLayout.TEXT_ONLY; it.portraitAnimation = IntroPortraitAnimation.OFF
            }
        }, enabled = source != IntroImageSource.NONE, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text("Clear") }
    }
    if (source != IntroImageSource.NONE) SettingChoices("Image fit", IntroImageFit.entries, scene.imageFit, ::enumLabel) {
        editor.updateIntroScene(scene.id, "Change narration image fit") { card -> card.imageFit = it }
    }
    SettingChoices("Background", BACKGROUND_COLORS, scene.backgroundColor, ::colorLabel) {
        editor.updateIntroScene(scene.id, "Change intro background") { card -> card.backgroundColor = it }
    }
    SettingChoices("Text color", TEXT_COLORS, scene.textColor, ::colorLabel) {
        editor.updateIntroScene(scene.id, "Change intro text color") { card -> card.textColor = it }
    }
    OutlinedTextField(
        value = scene.text,
        onValueChange = { value ->
            val cleaned = value.uppercase().filter { it == '\n' || it in ' '..'~' }.take(900)
            if (IntroSceneCodec.textLineCount(cleaned) <= IntroSceneCodec.MAX_TEXT_LINES) {
                editor.updateIntroScene(scene.id, "Edit intro narration") { it.text = cleaned }; onMessage(null)
            } else onMessage("Text must fit within ${IntroSceneCodec.MAX_TEXT_LINES} wrapped lines.")
        },
        label = { Text("Narration text") },
        supportingText = { Text("38 characters × ${IntroSceneCodec.MAX_TEXT_LINES} safe lines") },
        minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
    )
    SettingChoices("Advance", IntroAdvanceMode.entries, scene.advanceMode, ::advanceLabel) {
        editor.updateIntroScene(scene.id, "Change narration advance") { card -> card.advanceMode = it }
    }
    if (scene.advanceMode != IntroAdvanceMode.TIMED) NumericSetting(
        "Post-reveal input delay", scene.postRevealInputDelayFrames, 0..255,
        "Frames before any newly pressed control advances; vanilla uses 60.",
    ) { value ->
        editor.updateIntroScene(scene.id, "Change narration input delay") { it.postRevealInputDelayFrames = value }
    }
    NumericSetting("Hold duration", scene.durationFrames, 30..3600, "Frames; used by Timed/Either and frame preview.") {
        editor.updateIntroScene(scene.id, "Change narration duration") { card -> card.durationFrames = it }
    }
    SettingChoices("Transition", IntroTransition.entries, scene.transition, ::enumLabel) {
        editor.updateIntroScene(scene.id, "Change narration transition") { card -> card.transition = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumericSetting("Fade in", scene.fadeInFrames, 0..255, "frames", Modifier.weight(1f)) {
            editor.updateIntroScene(scene.id, "Change narration fade in") { card -> card.fadeInFrames = it }
        }
        NumericSetting("Fade out", scene.fadeOutFrames, 0..255, "frames", Modifier.weight(1f)) {
            editor.updateIntroScene(scene.id, "Change narration fade out") { card -> card.fadeOutFrames = it }
        }
    }
}

@Composable
private fun VignetteSettings(scene: IntroScene, editor: EditorState, parser: RomParser?, onMessage: (String?) -> Unit) {
    val source = scene.effectiveVignetteSource
    var sourceExpanded by remember(scene.id) { mutableStateOf(false) }
    Text("Following image", fontWeight = FontWeight.SemiBold)
    SourceMenu(source, listOf(IntroImageSource.NONE) + IntroRomImageResolver.vignetteSources +
        listOfNotNull(IntroImageSource.UPLOADED_PNG.takeIf { scene.vignetteImagePngBase64.isNotBlank() }), parser, sourceExpanded,
        { sourceExpanded = it }) { selected ->
        editor.updateIntroScene(scene.id, "Change intro image") {
            it.vignetteSource = selected
            it.vignetteFrame = IntroRomImageResolver.defaultFrame(selected)
        }
    }
    if (source == IntroImageSource.NONE) {
        Text("This frame advances directly to the next narration page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            val file = chooseIntroPng("Import Intro Image PNG") ?: return@Button
            importPng(file, onMessage) { imported ->
                editor.updateIntroScene(scene.id, "Import intro image") {
                    it.vignetteImagePngBase64 = imported.base64; it.vignetteImageFileName = file.name
                    it.vignetteImageSha256 = imported.sha256; it.vignetteSource = IntroImageSource.UPLOADED_PNG
                }
            }
        }, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text("Import PNG…") }
    } else {
        if (IntroRomImageResolver.isRomSource(source)) {
            SettingChoices("Animation", IntroVignetteAnimation.entries, scene.vignetteAnimation, { if (it == IntroVignetteAnimation.NATIVE) "Native loop" else "Static frame" }) {
                editor.updateIntroScene(scene.id, "Change image animation") { card -> card.vignetteAnimation = it }
            }
            val count = parser?.let { IntroRomImageResolver.frameCount(it, source) } ?: 0
            if (scene.vignetteAnimation == IntroVignetteAnimation.STATIC && count > 1) FramePicker(scene.vignetteFrame, count) { value ->
                editor.updateIntroScene(scene.id, "Change image frame") { it.vignetteFrame = value }
            }
            if (scene.vignetteAnimation == IntroVignetteAnimation.NATIVE) {
                SettingChoices("Frame timing", IntroVignetteTiming.entries, scene.vignetteTiming, {
                    if (it == IntroVignetteTiming.NATIVE) "Native timings" else "Uniform"
                }) {
                    editor.updateIntroScene(scene.id, "Change image timing") { card -> card.vignetteTiming = it }
                }
                if (scene.vignetteTiming == IntroVignetteTiming.UNIFORM) {
                    NumericSetting("Frame delay", scene.vignetteFrameDelay, 1..255, "frames") {
                        editor.updateIntroScene(scene.id, "Change image frame delay") { card -> card.vignetteFrameDelay = it }
                    }
                }
                NumericSetting("Loops", scene.vignetteLoopCount, 1..255, "Native delivery/exam: 10") {
                    editor.updateIntroScene(scene.id, "Change image loops") { card -> card.vignetteLoopCount = it }
                }
            }
            Text(nativeVignetteNote(source), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val file = chooseIntroPng("Import Intro Image PNG") ?: return@Button
                importPng(file, onMessage) { imported ->
                    editor.updateIntroScene(scene.id, "Import intro image") {
                        it.vignetteImagePngBase64 = imported.base64; it.vignetteImageFileName = file.name
                        it.vignetteImageSha256 = imported.sha256; it.vignetteSource = IntroImageSource.UPLOADED_PNG
                    }
                }
            }, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text(if (scene.vignetteImagePngBase64.isBlank()) "Import PNG…" else "Replace PNG…") }
            OutlinedButton(onClick = {
                editor.updateIntroScene(scene.id, "Clear intro image") {
                    it.vignetteImagePngBase64 = ""; it.vignetteImageFileName = ""; it.vignetteImageSha256 = ""
                    it.vignetteSource = IntroImageSource.NONE; it.vignetteFrame = 0
                }
            }, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text("Clear") }
        }
        if (source == IntroImageSource.UPLOADED_PNG) SettingChoices("Image fit", IntroImageFit.entries, scene.vignetteImageFit, ::enumLabel) {
            editor.updateIntroScene(scene.id, "Change image fit") { card -> card.vignetteImageFit = it }
        }
        Text("Pan (BG scroll pixels)", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericSetting("Start X", scene.vignettePanStartX, -255..255, "", Modifier.weight(1f)) { v -> editor.updateIntroScene(scene.id, "Change image pan") { it.vignettePanStartX = v } }
            NumericSetting("Start Y", scene.vignettePanStartY, -255..255, "", Modifier.weight(1f)) { v -> editor.updateIntroScene(scene.id, "Change image pan") { it.vignettePanStartY = v } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericSetting("End X", scene.vignettePanEndX, -255..255, "", Modifier.weight(1f)) { v -> editor.updateIntroScene(scene.id, "Change image pan") { it.vignettePanEndX = v } }
            NumericSetting("End Y", scene.vignettePanEndY, -255..255, "", Modifier.weight(1f)) { v -> editor.updateIntroScene(scene.id, "Change image pan") { it.vignettePanEndY = v } }
        }
        SettingChoices("Advance", IntroAdvanceMode.entries, scene.vignetteAdvanceMode, ::advanceLabel) {
            editor.updateIntroScene(scene.id, "Change image advance") { card -> card.vignetteAdvanceMode = it }
        }
        NumericSetting("Duration", scene.vignetteDurationFrames, 30..3600, "frames") {
            editor.updateIntroScene(scene.id, "Change image duration") { card -> card.vignetteDurationFrames = it }
        }
        SettingChoices("Transition", IntroTransition.entries, scene.vignetteTransition, ::enumLabel) {
            editor.updateIntroScene(scene.id, "Change image transition") { card -> card.vignetteTransition = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumericSetting("Fade in", scene.vignetteFadeInFrames, 0..255, "frames", Modifier.weight(1f)) {
                editor.updateIntroScene(scene.id, "Change image fade in") { card -> card.vignetteFadeInFrames = it }
            }
            NumericSetting("Fade out", scene.vignetteFadeOutFrames, 0..255, "frames", Modifier.weight(1f)) {
                editor.updateIntroScene(scene.id, "Change image fade out") { card -> card.vignetteFadeOutFrames = it }
            }
        }
    }
}

@Composable
private fun IntroPreview(
    scene: IntroScene,
    phase: IntroPreviewPhase,
    playhead: Int,
    playing: Boolean,
    revision: Int,
    parser: RomParser?,
    spriteEditRevision: Int,
    spritePaletteRevision: Int,
    modifier: Modifier,
) {
    val vignette = phase == IntroPreviewPhase.VIGNETTE
    val source = if (vignette) scene.effectiveVignetteSource else scene.effectiveImageSource
    val fadeIn = if (vignette) scene.vignetteFadeInFrames else scene.fadeInFrames
    val fadeOut = if (vignette) scene.vignetteFadeOutFrames else scene.fadeOutFrames
    val contentPlayhead = (playhead - fadeIn).coerceAtLeast(0)
    val duration = previewContentFrames(scene, phase)
    val sourceFrameCount = if (vignette && parser != null && IntroRomImageResolver.isRomSource(source)) {
        IntroRomImageResolver.frameCount(parser, source)
    } else 0
    val frameDurations = if (vignette && parser != null && sourceFrameCount > 0 &&
        scene.vignetteTiming == IntroVignetteTiming.NATIVE
    ) {
        IntroRomImageResolver.frameDurations(parser, source).takeIf { it.size == sourceFrameCount }
    } else null
    val effectiveDurations = frameDurations ?: List(sourceFrameCount.coerceAtLeast(1)) { scene.vignetteFrameDelay.coerceAtLeast(1) }
    val panFrames = effectiveDurations.sum().coerceAtLeast(1)
    val animationTick = contentPlayhead.coerceAtMost(panFrames * scene.vignetteLoopCount.coerceAtLeast(1) - 1)
    val cycleTick = animationTick.mod(panFrames)
    val animationFrame = effectiveDurations.runningFold(0, Int::plus).drop(1).indexOfFirst { cycleTick < it }
        .coerceAtLeast(0)
    val progress = if (vignette && sourceFrameCount > 0 && scene.vignetteAnimation == IntroVignetteAnimation.NATIVE) {
        (contentPlayhead.toFloat() / panFrames).coerceIn(0f, 1f)
    } else 0f
    val frame = if (vignette) {
        if (scene.vignetteAnimation == IntroVignetteAnimation.STATIC) scene.vignetteFrame
        else if (sourceFrameCount > 0) {
            animationFrame.coerceAtMost(sourceFrameCount - 1)
        } else 0
    } else portraitFrame(scene, contentPlayhead)
    val panX = if (vignette) lerp(scene.vignettePanStartX, scene.vignettePanEndX, progress) else 0
    val panY = if (vignette) lerp(scene.vignettePanStartY, scene.vignettePanEndY, progress) else 0
    val visibleCharacters = if (!vignette && scene.textReveal == IntroTextReveal.NATIVE_TYPEWRITER &&
        (playing || playhead > 0)
    ) {
        (contentPlayhead / scene.typewriterFramesPerCharacter.coerceAtLeast(1)).coerceIn(0, scene.text.length)
    } else scene.text.length
    val result = remember(
        scene.id, revision, phase, frame, panX, panY, visibleCharacters,
        parser, spriteEditRevision, spritePaletteRevision,
    ) {
        runCatching {
            val card = if (vignette) vignetteCard(scene) else {
                scene.copy(text = IntroSceneCodec.maskTextForReveal(scene.text, visibleCharacters))
            }
            val romImage = if (parser != null && IntroRomImageResolver.isRomSource(source)) {
                if (!vignette && source == IntroImageSource.ROM_SAMUS_PORTRAIT) {
                    IntroRomImageResolver.renderPortrait(parser, scene.portraitAnimation, frame)
                } else IntroRomImageResolver.render(parser, source, frame, panX, panY)
            } else null
            val compiled = IntroSceneCodec.compile(card, romImage)
            compiled to IntroSceneCodec.decode(compiled).toComposeImageBitmap()
        }
    }
    val fadeAlpha = when {
        !playing -> 0f
        playhead < fadeIn && fadeIn > 0 -> 1f - playhead.toFloat() / fadeIn
        playhead > fadeIn + duration && fadeOut > 0 -> (playhead - fadeIn - duration).toFloat() / fadeOut
        else -> 0f
    }.coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${if (vignette) "Image" else "Narration"} · exact 256×224 SNES preview", fontWeight = FontWeight.Bold)
        Box(
            Modifier.fillMaxWidth().aspectRatio(256f / 224f).background(Color.Black).border(1.dp, MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
        ) {
            if (source == IntroImageSource.NONE && (vignette || scene.layout != IntroSceneLayout.TEXT_ONLY)) {
                Text(if (vignette) "No image for this story frame" else "No image selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else result.getOrNull()?.second?.let { bitmap ->
                Image(bitmap, "Intro ${phase.name.lowercase()} preview", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
            }
            if (fadeAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fadeAlpha)))
        }
        result.getOrNull()?.first?.let { compiled ->
            Text("${compiled.tileCount} / 1024 tiles · ${compiled.payload.size} bytes${if (vignette) " · pan $panX,$panY" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        result.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun vignetteCard(scene: IntroScene): IntroScene = scene.copy(
    text = "",
    imagePngBase64 = scene.vignetteImagePngBase64,
    imageFileName = scene.vignetteImageFileName,
    imageSha256 = scene.vignetteImageSha256,
    imageSource = scene.effectiveVignetteSource,
    imageFrame = scene.vignetteFrame,
    imageFit = scene.vignetteImageFit,
    layout = IntroSceneLayout.IMAGE_ONLY,
)

private fun portraitFrame(scene: IntroScene, playhead: Int): Int = when (scene.portraitAnimation) {
    IntroPortraitAnimation.NATIVE_BLINK -> {
        val cycle = playhead.mod(256)
        when {
            cycle < 128 -> 0
            cycle < 138 -> 1
            cycle < 148 -> 2
            cycle < 158 -> 1
            cycle < 238 -> 0
            cycle < 246 -> 1
            cycle < 254 -> 2
            else -> 1
        }
    }
    IntroPortraitAnimation.DEADPAN -> 3
    IntroPortraitAnimation.STATIC_CLOSED -> 2
    else -> 0
}

private fun previewContentFrames(scene: IntroScene, phase: IntroPreviewPhase): Int = when (phase) {
    IntroPreviewPhase.VIGNETTE -> scene.vignetteDurationFrames
    IntroPreviewPhase.NARRATION -> {
        val revealFrames = if (scene.textReveal == IntroTextReveal.NATIVE_TYPEWRITER) {
            scene.text.length * scene.typewriterFramesPerCharacter.coerceAtLeast(1)
        } else 0
        val readyFrames = revealFrames + if (scene.advanceMode == IntroAdvanceMode.TIMED) 0 else scene.postRevealInputDelayFrames
        maxOf(scene.durationFrames, readyFrames)
    }
}

private fun previewPhaseFrames(scene: IntroScene, phase: IntroPreviewPhase): Int {
    val fadeIn = if (phase == IntroPreviewPhase.VIGNETTE) scene.vignetteFadeInFrames else scene.fadeInFrames
    val transition = if (phase == IntroPreviewPhase.VIGNETTE) scene.vignetteTransition else scene.transition
    val fadeOut = if (transition == IntroTransition.FADE) {
        if (phase == IntroPreviewPhase.VIGNETTE) scene.vignetteFadeOutFrames else scene.fadeOutFrames
    } else 0
    return fadeIn + previewContentFrames(scene, phase) + fadeOut
}

@Composable
private fun SourceMenu(
    selected: IntroImageSource,
    values: List<IntroImageSource>,
    parser: RomParser?,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onSelect: (IntroImageSource) -> Unit,
) {
    Box {
        OutlinedButton(onClick = { onExpanded(true) }, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) {
            Text(IntroRomImageResolver.label(selected), maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            values.distinct().forEach { source ->
                DropdownMenuItem(
                    text = { Text(IntroRomImageResolver.label(source)) },
                    enabled = !IntroRomImageResolver.isRomSource(source) || parser != null,
                    onClick = { onExpanded(false); onSelect(source) },
                )
            }
        }
    }
}

@Composable
private fun PhaseButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text(label) }
    else OutlinedButton(onClick = onClick, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) { Text(label) }
}

@Composable
private fun FramePicker(frame: Int, count: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onSelect((frame - 1).coerceAtLeast(0)) }, enabled = frame > 0, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_COMPACT_BUTTON_PADDING) { Text("Previous") }
        Text("Frame ${frame.coerceIn(0, count - 1) + 1} / $count")
        OutlinedButton(onClick = { onSelect((frame + 1).coerceAtMost(count - 1)) }, enabled = frame < count - 1, shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_COMPACT_BUTTON_PADDING) { Text("Next") }
    }
}

@Composable
private fun NumericSetting(
    label: String,
    value: Int,
    range: IntRange,
    supporting: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw -> raw.filterIndexed { index, c -> c.isDigit() || c == '-' && index == 0 }.toIntOrNull()?.let { onChange(it.coerceIn(range)) } },
        label = { Text(label) },
        supportingText = supporting.takeIf(String::isNotBlank)?.let { { Text(it) } },
        singleLine = true, modifier = modifier,
    )
}

@Composable
private fun <T> SettingChoices(
    label: String,
    values: List<T>,
    selected: T,
    text: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        values.chunked(3).forEach { choices ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { value ->
                    if (value == selected) Button(onClick = { onSelect(value) }, enabled = enabled(value), shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) {
                        Text(text(value), maxLines = 1, softWrap = false)
                    } else OutlinedButton(onClick = { onSelect(value) }, enabled = enabled(value), shape = INTRO_BUTTON_SHAPE, contentPadding = INTRO_BUTTON_PADDING) {
                        Text(text(value), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

private fun sceneSummary(scene: IntroScene): String {
    val narration = when {
        scene.effectiveImageSource == IntroImageSource.ROM_SAMUS_PORTRAIT -> "Portrait"
        scene.layout == IntroSceneLayout.TEXT_ONLY -> "Text"
        else -> "Image + text"
    }
    val vignette = scene.effectiveVignetteSource.takeUnless { it == IntroImageSource.NONE }?.let(IntroRomImageResolver::label)
    return if (vignette == null) narration else "$narration + $vignette"
}

private fun frameCardTitle(name: String, index: Int): String {
    val number = index + 1
    val generated = name.isBlank() || name == "Scene $number" || name == "Story beat $number" || name == "Story frame $number"
    return if (generated) "Story Frame $number" else "$number. $name"
}

private fun frameEditorName(name: String, index: Int): String =
    if (name == "Story beat ${index + 1}") "Story frame ${index + 1}" else name

private fun layoutLabel(layout: IntroSceneLayout): String = when (layout) {
    IntroSceneLayout.TEXT_ONLY -> "Text"
    IntroSceneLayout.IMAGE_ONLY -> "Image"
    IntroSceneLayout.TEXT_OVER_IMAGE -> "Image + text"
    IntroSceneLayout.TEXT_PANEL_BOTTOM -> "Bottom panel"
    IntroSceneLayout.TEXT_PANEL_TOP -> "Top panel"
}

private fun portraitLabel(value: IntroPortraitAnimation): String = when (value) {
    IntroPortraitAnimation.NATIVE_BLINK -> "Native blink"
    IntroPortraitAnimation.DEADPAN -> "Deadpan"
    IntroPortraitAnimation.STATIC_OPEN -> "Open"
    IntroPortraitAnimation.STATIC_CLOSED -> "Closed"
    IntroPortraitAnimation.OFF -> "No eye track"
}

private fun nativeVignetteNote(source: IntroImageSource): String = when (source) {
    IntroImageSource.ROM_MOTHER_BRAIN -> "Native pattern: the room BG stays fixed while Samus and the Mother Brain 1/2/3/2 OBJ track animate. The original also runs demo input, rinkas, hit flashes, explosions, and screen shake."
    IntroImageSource.ROM_METROID_EGG, IntroImageSource.ROM_EGG_HATCHING -> "Native pattern: the room BG stays fixed while Samus, the egg hatch track, and then the confused baby OBJ animate. The original also runs demo input and collision data."
    IntroImageSource.ROM_BABY_DELIVERED -> "Native pattern: the scientist BG pans 32→0 X while the baby OBJ independently loops frames 1/2/3/2."
    IntroImageSource.ROM_BABY_EXAMINED -> "Native pattern: the lab BG pans −24→8 Y while the baby OBJ independently loops frames 1/2/3/2."
    else -> ""
}

private fun advanceLabel(mode: IntroAdvanceMode): String = when (mode) {
    IntroAdvanceMode.BUTTON -> "Button"
    IntroAdvanceMode.TIMED -> "Timed"
    IntroAdvanceMode.EITHER -> "Either"
}

private fun enumLabel(value: Enum<*>): String = value.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun chooseIntroPng(title: String): File? {
    val chooser = JFileChooser().apply { dialogTitle = title; fileFilter = FileNameExtensionFilter("PNG Images", "png") }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun importPng(file: File, onMessage: (String?) -> Unit, save: (IntroSceneCodec.ImportedPng) -> Unit) {
    runCatching { IntroSceneCodec.importPng(file.readBytes()) }
        .onSuccess { save(it); onMessage("Imported ${file.name} (${it.width}×${it.height}).") }
        .onFailure { onMessage(it.message) }
}

private fun lerp(start: Int, end: Int, progress: Float): Int = (start + (end - start) * progress).roundToInt()

private val BACKGROUND_COLORS = listOf(0xFF000000.toInt(), 0xFF08182C.toInt(), 0xFF102018.toInt())
private val TEXT_COLORS = listOf(0xFFE8F8E8.toInt(), 0xFF80F0A0.toInt(), 0xFFFFD070.toInt())
private fun colorLabel(argb: Int): String = when (argb) {
    0xFF000000.toInt() -> "Black"
    0xFF08182C.toInt() -> "Navy"
    0xFF102018.toInt() -> "Forest"
    0xFFE8F8E8.toInt() -> "White"
    0xFF80F0A0.toInt() -> "Green"
    0xFFFFD070.toInt() -> "Amber"
    else -> "Custom"
}

private val INTRO_BUTTON_SHAPE = RoundedCornerShape(4.dp)
private val INTRO_CARD_SHAPE = RoundedCornerShape(5.dp)
private val INTRO_COMPACT_BUTTON_PADDING = PaddingValues(horizontal = 7.dp, vertical = 3.dp)
private val INTRO_BUTTON_PADDING = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
