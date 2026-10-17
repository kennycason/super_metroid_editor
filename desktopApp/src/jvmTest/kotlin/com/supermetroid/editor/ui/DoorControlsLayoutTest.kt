package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.asm.*
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.awt.Canvas
import java.io.File
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DoorControlsLayoutTest {
    @TempDir lateinit var directory: File

    @Test
    fun `long connection and destination names fit selected controls and wider menus at every font size`() {
        val fixture = fixture(asm = false)
        val rooms = fixture.rooms.map { it.copy(name = "An especially long Pink Brinstar Hopper Room destination — ${it.name}") }
        for (font in FontSize.entries) withScene(font = font, content = {
            tilePanel(fixture, rooms, initialBts = 0x80)
        }) { scene, render ->
            assertTrue(nodes(scene).none { text(it).startsWith("Connection 1 →") }, "Stored connection is an advanced detail")
            val destination = nodes(scene).single { text(it).startsWith("An especially long") }
            assertFits(destination, "$font selected destination room")
            assertTrue(layout(destination).lineCount > 1, "Exercise wrapping, not just short labels")
            val heading = nodes(scene).single { text(it) == "Door Connection" }
            val roomField = tagged(scene, "door-room-picker")
            val doorField = tagged(scene, "door-destination-picker")
            val travel = nodes(scene).single { text(it) == "Travel:" }
            assertTrue(heading.boundsInRoot.bottom <= roomField.boundsInRoot.top)
            assertTrue(roomField.boundsInRoot.bottom <= doorField.boundsInRoot.top)
            assertTrue(doorField.boundsInRoot.bottom <= travel.boundsInRoot.top)
            assertTrue(nodes(scene).none { text(it) == "Screen col:" || text(it) == "Screen row:" })
            click(nodes(scene).single { text(it) == "▸ Advanced" }); render()
            assertTrue(nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
                .config[SemanticsActions.ScrollBy].action!!.invoke(0f, 250f))
            render()
            val selected = nodes(scene).single { text(it).startsWith("Connection 1 →") }
            assertFits(selected, "$font selected connection")

            click(selected); render()
            val menuLabels = nodes(scene).filter { text(it).startsWith("Connection ") && it.id != selected.id }
                .filter { clickable(it).boundsInRoot.width >= 380 }
            assertEquals(fixture.editor.doorEntries.size, menuLabels.size, "Every connection appears in the wider menu")
            menuLabels.forEach { assertFits(it, "$font menu ${text(it)}") }
            val rows = menuLabels.sortedBy { it.boundsInRoot.top }
            rows.zipWithNext().forEach { (first, second) ->
                assertTrue(first.boundsInRoot.bottom <= second.boundsInRoot.top, "Connection labels must not overlap")
            }
            assertTrue(nodes(scene).none { text(it) == "Use existing connection…" })
        }
    }

    @Test
    fun `room browsing changing cancelling and empty destinations never edit until an actual door is chosen`() {
        val fixture = fixture(asm = false)
        val original = fixture.editor.doorEntries.toList()
        val door = original.first()
        val choices = destinationChoices(fixture, door.direction)
        val target = choices.first { it.second.size > 1 }
        val other = choices.first { it.first != target.first && it.second.isNotEmpty() }
        val empty = choices.first { it.second.isEmpty() }
        val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
        val dirty = fixture.editor.dirty
        val history = fixture.editor.undoVersion
        val level = assertNotNull(fixture.editor.workingLevelData).copyOf()
        withScene(content = { tilePanel(fixture) }) { scene, render ->
            fun assertUnchanged() {
                assertEquals(original, fixture.editor.doorEntries)
                assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
                assertEquals(dirty, fixture.editor.dirty)
                assertEquals(history, fixture.editor.undoVersion)
                assertContentEquals(level, fixture.editor.workingLevelData)
            }
            click(tagged(scene, "door-room-picker")); render()
            searchRooms(scene, target.first.name); render()
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0,
                AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.CHAR_UNDEFINED))); render()
            assertUnchanged()

            chooseRoom(scene, render, target.first)
            assertUnchanged()
            assertTrue(nodes(scene).any { text(it) == "Cancel room choice" })
            chooseRoom(scene, render, other.first)
            assertUnchanged()
            assertTrue(descendants(tagged(scene, "door-room-picker")).any { text(it) == other.first.name })
            click(nodes(scene).single { text(it) == "Cancel room choice" }); render()
            assertUnchanged()
            assertFalse(nodes(scene).any { text(it) == "Cancel room choice" })

            chooseRoom(scene, render, empty.first)
            assertTrue(tagged(scene, "door-destination-picker").config.contains(SemanticsProperties.Disabled))
            assertTrue(nodes(scene).any { text(it).startsWith("No compatible ") })
            assertUnchanged()
            click(nodes(scene).single { text(it) == "Cancel room choice" }); render()

            chooseRoom(scene, render, target.first)
            click(tagged(scene, "door-destination-picker")); render()
            val opening = target.second.last()
            click(nodes(scene).last { text(it) == doorDestinationLabel(opening.direction, opening.screenX, opening.screenY) })
            render()
            val updated = fixture.editor.doorEntries.first()
            assertEquals(opening.roomId, updated.destRoomPtr)
            assertEquals(opening.screenX, updated.screenX)
            assertEquals(opening.screenY, updated.screenY)
            assertEquals(opening.doorCapCode, updated.doorCapCode)
            assertEquals(original.drop(1), fixture.editor.doorEntries.drop(1), "Other connections must not change")
            assertContentEquals(level, fixture.editor.workingLevelData)
            assertTrue(fixture.editor.dirty)
            assertFalse(nodes(scene).any { text(it) == "Cancel room choice" }, "A committed choice is no longer a draft")
        }
    }

    @Test
    fun `destination drafts reset on source selection state data changes and unavailable controls and reject stale popup actions`() {
        val fixture = fixture(asm = false)
        fixture.editor.switchRoomState(0, fixture.parser)
        val current = mutableStateOf(fixture.editor.doorEntries.first())
        val selection = mutableStateOf(0)
        val enabled = mutableStateOf(true)
        val selected = mutableListOf<DoorwayOpening>()
        val target = destinationChoices(fixture, current.value.direction).first { it.second.isNotEmpty() }
        val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
        withScene(content = {
            Column { DoorDestinationPicker(current.value, fixture.room.roomId, current.value.direction,
                fixture.rooms, fixture.parser, fixture.editor, enabled.value,
                selectionKey = selection.value, onDoorwaySelected = { selected += it }) }
        }) { scene, render ->
            fun stage() { chooseRoom(scene, render, target.first); assertTrue(nodes(scene).any { text(it) == "Cancel room choice" }) }
            fun assertCleared() { assertFalse(nodes(scene).any { text(it) == "Cancel room choice" }); assertTrue(selected.isEmpty()) }
            stage(); selection.value++; render(); assertCleared()
            stage(); fixture.editor.switchRoomState(1, fixture.parser); render(); assertCleared()
            stage(); current.value = current.value.copy(screenX = current.value.screenX + 1); render(); assertCleared()
            stage(); enabled.value = false; render(); assertCleared()
            assertTrue(tagged(scene, "door-room-picker").config.contains(SemanticsProperties.Disabled))
            enabled.value = true; render()
            stage(); click(tagged(scene, "door-destination-picker")); render()
            val opening = target.second.first()
            val staleAction = clickable(nodes(scene).last { text(it) == doorDestinationLabel(opening.direction, opening.screenX, opening.screenY) })
                .config[SemanticsActions.OnClick].action!!
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
            val otherDoor = fixture.editor.doorEntries[1]
            fixture.editor.updateDoor(1, otherDoor.copy(distFromDoor = otherDoor.distFromDoor xor 1))
            val afterExternalEdit = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
            staleAction.invoke() // Even a callback retained across an external edit may not apply its old snapshot.
            render(); assertCleared()
            assertEquals(afterExternalEdit, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
        }
    }

    @Test
    fun `browsing a room in ASM mode never adopts source increments its revision or changes build status`() = runBlocking {
        val fixture = fixture(asm = true)
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Native rooms\n")
        File(src, DOOR_SOURCE_FILE).writeText("; Native doors\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
                "test", "base.sfc", "ab".repeat(32), fixture.parser.getRomData().size, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Doors.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        fixture.editor.initForProject(file, fixture.editor.project)
        fixture.editor.bindRoomSources(AsmPlmSourceCatalog("", macros, emptyMap(), fixture.parser, emptyMap()), workspace)
        fixture.editor.loadRoom(fixture.room.roomId, fixture.parser, fixture.room)
        val target = destinationChoices(fixture, fixture.editor.doorEntries.first().direction).first { it.second.isNotEmpty() }
        val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
        val source = workspace.projectSourceText(DOOR_SOURCE_FILE)
        val roomSource = workspace.projectSourceText(PLM_SOURCE_FILE)
        val revision = workspace.sourceBufferRevision
        val buildRequired = workspace.sourceBuildRequired
        val dirty = fixture.editor.dirty
        withScene(content = { tilePanel(fixture) }) { scene, render ->
            chooseRoom(scene, render, target.first)
            assertTrue(nodes(scene).any { text(it) == "Cancel room choice" })
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
            assertEquals(source, workspace.projectSourceText(DOOR_SOURCE_FILE))
            assertEquals(roomSource, workspace.projectSourceText(PLM_SOURCE_FILE))
            assertEquals(revision, workspace.sourceBufferRevision)
            assertEquals(buildRequired, workspace.sourceBuildRequired)
            assertEquals(dirty, fixture.editor.dirty)
            click(nodes(scene).single { text(it) == "Cancel room choice" }); render()
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
            assertEquals(revision, workspace.sourceBufferRevision)
        }
    }

    @Test
    fun `an unlinked new room doorway only allocates and relinks its whole opening after selecting a destination door`() {
        val fixture = fixture(asm = false)
        val definition = fixture.editor.createNewRoom(NewRoomCreationRequest("Door Selection UI", false,
            area = 0, mapX = 2, mapY = 2, width = 1, height = 1, tileset = 0), fixture.parser)
        val workspace = fixture.editor.prepareWorkspaceParser(fixture.parser)
        val room = assertNotNull(workspace.readRoomHeader(definition.previewRoomId))
        fixture.editor.loadRoom(room.roomId, workspace, room)
        repeat(4) { fixture.editor.setTileProperties(15, it, 0x9, 127) }
        val target = fixture.rooms.firstNotNullOf { info ->
            fixture.editor.effectiveDoorwayOpenings(info.getRoomIdAsInt(), 0, workspace).firstOrNull()?.let { info to it }
        }
        val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
        val level = assertNotNull(fixture.editor.workingLevelData).copyOf()
        withScene(content = {
            TilePropertiesPanel(15, 1, 0, 0x9, 127, editorState = fixture.editor, romParser = workspace,
                rooms = fixture.rooms, roomHeader = room, roomId = room.roomId, emulatorConnected = false,
                onMoveSamusHere = null, onDismiss = {})
        }) { scene, render ->
            assertTrue(tagged(scene, "door-destination-picker").config.contains(SemanticsProperties.Disabled))
            chooseRoom(scene, render, target.first)
            assertTrue(definition.doors.isEmpty())
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
            assertContentEquals(level, fixture.editor.workingLevelData)
            click(tagged(scene, "door-destination-picker")); render()
            val opening = target.second
            click(nodes(scene).last { text(it) == doorDestinationLabel(opening.direction, opening.screenX, opening.screenY) }); render()
            assertEquals(opening.roomId, fixture.editor.doorEntries.single().destRoomPtr)
            assertEquals(opening.doorCapCode, fixture.editor.doorEntries.single().doorCapCode)
            assertEquals(1, definition.doors.size)
            repeat(4) { assertEquals(0, fixture.editor.readBts(15, it)) }
            assertFalse(nodes(scene).any { text(it) == "Cancel room choice" })
        }
    }

    @Test
    fun `door source link is inline and managed arrival creation is only under Advanced in ASM mode`() {
        for (asm in listOf(false, true)) {
            val fixture = fixture(asm)
            val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
            val dirty = fixture.editor.dirty
            val actions = mutableListOf<String>()
            withScene(content = {
                tilePanel(fixture, onSource = { actions += "source:$it" },
                    onArrival = { index, create, _ -> actions += "arrival:$index:$create" })
            }) { scene, render ->
                val heading = nodes(scene).single { text(it) == "Door Connection" }
                val inlineLink = nodes(scene).singleOrNull {
                    text(it) == "ASM" && abs(it.boundsInRoot.center.y - heading.boundsInRoot.center.y) < 2
                }
                assertEquals(asm, inlineLink != null)
                assertTrue(nodes(scene).none { text(it) == "Door data ASM" })
                assertTrue(nodes(scene).none { text(it) == "Create managed arrival ASM…" })
                if (inlineLink != null) click(inlineLink)
                click(nodes(scene).single { text(it) == "▸ Advanced" }); render()
                val create = nodes(scene).singleOrNull { text(it) == "Create managed arrival ASM…" }
                assertEquals(asm, create != null)
                if (create != null) click(create)
                assertEquals(if (asm) listOf("source:0", "arrival:0:true") else emptyList(), actions)
                assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
                assertEquals(dirty, fixture.editor.dirty, "Expanding and navigating must not edit the project")
            }
        }
    }

    @Test
    fun `Doors starts collapsed in both modes and each newly selected room while ROM overview has no structural actions`() {
        for (asm in listOf(false, true)) {
            val fixture = fixture(asm)
            val selectedRoom = mutableStateOf(fixture.room.roomId)
            val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
            withScene(content = {
                Column { RoomDoorsSection(selectedRoom.value, fixture.parser, fixture.editor, fixture.rooms, {}, {}) }
            }) { scene, render ->
                fun heading() = nodes(scene).single { text(it) == "Doors" }
                assertTrue(nodes(scene).none { text(it).startsWith("Travels ") })
                click(heading()); render()
                assertEquals(fixture.editor.doorEntries.size, nodes(scene).count { text(it).startsWith("Travels ") })
                assertEquals(asm, nodes(scene).any { text(it) == "Add connection" })
                assertEquals(asm, nodes(scene).any { text(it) == "Remove" })
                assertEquals(asm, nodes(scene).any { text(it) == "ASM" })
                click(heading()); render()
                assertTrue(nodes(scene).none { text(it).startsWith("Travels ") })
                click(heading()); render()
                selectedRoom.value = 0x91F8
                fixture.editor.loadRoom(0x91F8, fixture.parser, assertNotNull(fixture.parser.readRoomHeader(0x91F8)))
                render()
                assertTrue(nodes(scene).none { text(it).startsWith("Travels ") }, "Selecting another room starts collapsed")
                assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
            }
        }
    }

    @Test
    fun `a room with zero doors still has a collapsed expandable Doors section`() {
        val fixture = fixture(asm = false)
        val definition = fixture.editor.createNewRoom(
            NewRoomCreationRequest("Empty Doors UI", false, area = 0, mapX = 2, mapY = 2,
                width = 1, height = 1, tileset = 0), fixture.parser)
        val workspace = fixture.editor.prepareWorkspaceParser(fixture.parser)
        fixture.editor.loadRoom(definition.previewRoomId, workspace,
            assertNotNull(workspace.readRoomHeader(definition.previewRoomId)))
        val before = Json.encodeToString(SmEditProject.serializer(), fixture.editor.project)
        withScene(content = {
            Column { RoomDoorsSection(definition.previewRoomId, workspace, fixture.editor, fixture.rooms, null, null) }
        }) { scene, render ->
            assertTrue(nodes(scene).any { text(it) == "Doors" })
            assertTrue(nodes(scene).none { text(it) == "No door connections." })
            click(nodes(scene).single { text(it) == "Doors" }); render()
            assertTrue(nodes(scene).any { text(it) == "No door connections." })
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), fixture.editor.project))
        }
    }

    @Test
    fun `room overview orders FX Doors Screen Scrolls then Space Usage`() {
        val fixture = fixture(asm = false)
        withScene(height = 4000, content = {
            RoomPropertiesPanel(fixture.room, fixture.parser, fixture.editor)
        }) { scene, _ ->
            val headings = listOf("FX Data", "Doors", "Screen Scrolls", "Space Usage").map { label ->
                nodes(scene).first { text(it) == label }
            }
            headings.zipWithNext().forEach { (first, second) ->
                assertTrue(first.boundsInRoot.bottom < second.boundsInRoot.top,
                    "${text(first)} must precede ${text(second)}")
            }
            assertTrue(nodes(scene).none { text(it).startsWith("Travels ") })
        }
    }

    private data class Fixture(val parser: RomParser, val editor: EditorState, val room: Room, val rooms: List<RoomInfo>)

    private fun fixture(asm: Boolean): Fixture {
        val parser = TestRomHelper.requireRomParser()
        val room = assertNotNull(parser.readRoomHeader(0x9879))
        val editor = EditorState().apply {
            testMode = true
            project.asmWorkspace.enabled = asm
            project.asmWorkspace.buildMode = if (asm) ProjectRomBuildMode.ASM_SOURCE else ProjectRomBuildMode.PATCHED_ROM
            loadRoom(room.roomId, parser, room)
        }
        assertTrue(editor.doorEntries.size >= 2)
        return Fixture(parser, editor, room, parser.roomCatalog.rooms)
    }

    private fun destinationChoices(fixture: Fixture, direction: Int) = fixture.rooms
        .filter { it.getRoomIdAsInt() != fixture.room.roomId && it.getRoomIdAsInt() != fixture.editor.doorEntries.first().destRoomPtr }
        .map { it to fixture.editor.effectiveDoorwayOpenings(it.getRoomIdAsInt(), direction, fixture.parser) }

    private fun searchRooms(scene: ComposeScene, query: String) {
        val search = nodes(scene).single { it.config.contains(SemanticsProperties.EditableText) }
        assertTrue(search.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(query)))
    }

    private fun chooseRoom(scene: ComposeScene, render: () -> Unit, room: RoomInfo) {
        click(tagged(scene, "door-room-picker")); render()
        searchRooms(scene, room.name); render()
        click(nodes(scene).last { text(it) == room.name }); render()
    }

    @Composable
    private fun tilePanel(fixture: Fixture, rooms: List<RoomInfo> = fixture.rooms, initialBts: Int = 0,
        onSource: ((Int) -> Unit)? = null, onArrival: ((Int, Boolean, Int?) -> Unit)? = null) {
        TilePropertiesPanel(0, 0, 0, 0x9, initialBts, editorState = fixture.editor, romParser = fixture.parser,
            rooms = rooms, roomHeader = fixture.room, roomId = fixture.room.roomId, emulatorConnected = false,
            onMoveSamusHere = null, onNavigateToDoorSource = onSource, onNavigateToDoorArrival = onArrival, onDismiss = {})
    }

    private fun withScene(font: FontSize = FontSize.MEDIUM, height: Int = 1000,
        content: @Composable () -> Unit, check: (ComposeScene, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(600, height)
        try {
            scene.constraints = Constraints.fixed(600, height)
            val settings = EditorThemeState().apply { fontSize.value = font }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(typography = font.materialTypography()) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
            var time = System.nanoTime()
            val render = { repeat(8) { time += 16_666_667; scene.render(surface.canvas, time) }; Unit }
            render()
            check(scene, render)
        } finally { scene.close(); surface.close() }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun clickable(node: SemanticsNode): SemanticsNode =
        if (node.config.contains(SemanticsActions.OnClick)) node else clickable(assertNotNull(node.parent, "Missing action for ${text(node)}"))
    private fun click(node: SemanticsNode) { assertTrue(clickable(node).config[SemanticsActions.OnClick].action!!.invoke()) }
    private fun layout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }
    private fun assertFits(node: SemanticsNode, message: String) {
        val result = layout(node)
        assertFalse(result.hasVisualOverflow, "$message: text layout overflow")
        assertTrue(node.boundsInRoot.height >= result.size.height - 1, "$message: clipped by its parent")
    }
}
