package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser

const val RIDLEY_CONFIG_TYPE = "ridley"
const val DRAYGON_CONFIG_TYPE = "draygon"

data class BossBehaviorField(
    val key: String,
    val label: String,
    val snesAddress: Int,
    val defaultValue: Int,
    val unit: String = "",
    val signed: Boolean = false,
    val hex: Boolean = false,
    val writeSnesAddresses: List<Int> = listOf(snesAddress),
)

data class BossBehaviorSection(
    val title: String,
    val description: String,
    val color: Color,
    val fields: List<BossBehaviorField>,
)

data class BossBehaviorDefinition(
    val configType: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val headerColor: Color,
    val sections: List<BossBehaviorSection>,
)

private fun bossBehaviorField(
    key: String,
    label: String,
    snesAddress: Int,
    defaultValue: Int,
    unit: String = "",
    signed: Boolean = false,
    hex: Boolean = false,
    vararg additionalWriteAddresses: Int,
): BossBehaviorField =
    BossBehaviorField(
        key = key,
        label = label,
        snesAddress = snesAddress,
        defaultValue = defaultValue,
        unit = unit,
        signed = signed,
        hex = hex,
        writeSnesAddresses = (listOf(snesAddress) + additionalWriteAddresses.toList()).distinct(),
    )

val RIDLEY_BEHAVIOR = BossBehaviorDefinition(
    configType = RIDLEY_CONFIG_TYPE,
    title = "RIDLEY",
    subtitle = "Advanced Behavior Editor",
    description = "Edit Ridley's intro timers, arena bounds, Ceres attacks, Norfair swoop, hover, and pogo constants. HP and contact damage remain in Boss Stats.",
    headerColor = Color(0xFFB71C1C),
    sections = listOf(
        BossBehaviorSection(
            "Intro And Fade-In",
            "Immediate operands used by Ridley's Ceres/Norfair intro, fade-in, roar, and transition loops in bank A6.",
            Color(0xFFD32F2F),
            listOf(
                bossBehaviorField("ceres_intro_delay", "Ceres Intro Delay", 0xA6A367, 0x0200, unit = "frames"),
                bossBehaviorField("norfair_intro_delay", "Norfair Intro Delay", 0xA6A372, 0x00AA, unit = "frames"),
                bossBehaviorField("eye_fade_step_delay", "Eye Fade Step Delay", 0xA6A396, 0x0001, unit = "frames"),
                bossBehaviorField("body_fade_step_delay", "Body Fade Step Delay", 0xA6A3E6, 0x0002, unit = "frames"),
                bossBehaviorField("wait_to_roar_timer", "Wait To Roar", 0xA6A448, 0x0004, unit = "frames"),
                bossBehaviorField("ceres_roar_timer", "Ceres Roar Timer", 0xA6A472, 0x00FC, unit = "frames"),
                bossBehaviorField("norfair_background_fade_timer", "Norfair BG Fade Timer", 0xA6A486, 0x0002, unit = "frames"),
                bossBehaviorField("transition_wing_timer", "Transition Wing Timer", 0xA6A4BB, 0x0008, unit = "frames"),
            ),
        ),
        BossBehaviorSection(
            "Arena And Tail Damage",
            "Norfair spawn point, movement clamps, and tail damage setup values.",
            Color(0xFF795548),
            listOf(
                bossBehaviorField("norfair_initial_x", "Norfair Initial X", 0xA6A171, 0x0060, unit = "px"),
                bossBehaviorField("norfair_initial_y", "Norfair Initial Y", 0xA6A177, 0x018A, unit = "px"),
                bossBehaviorField("norfair_min_y", "Norfair Minimum Y", 0xA6A19E, 0x0040, unit = "px"),
                bossBehaviorField("norfair_max_y", "Norfair Maximum Y", 0xA6A1A5, 0x01A0, unit = "px"),
                bossBehaviorField("norfair_min_x", "Norfair Minimum X", 0xA6A1AC, 0x0040, unit = "px"),
                bossBehaviorField("norfair_max_x", "Norfair Maximum X", 0xA6A1B3, 0x00E0, unit = "px"),
                bossBehaviorField("norfair_tail_damage", "Norfair Tail Damage", 0xA6A1C1, 0x0078, unit = "damage"),
                bossBehaviorField("ceres_tail_damage", "Ceres Tail Damage", 0xA6A217, 0x000F, unit = "damage"),
            ),
        ),
        BossBehaviorSection(
            "Ceres Attacks",
            "Ceres-only hover, fireball, lunge, and liftoff constants.",
            Color(0xFF00897B),
            listOf(
                bossBehaviorField("ceres_liftoff_accel", "Liftoff Up Accel", 0xA6A6B4, 0xFFF0, unit = "subpx/frame", signed = true),
                bossBehaviorField("ceres_liftoff_decel", "Liftoff Down Accel", 0xA6A6CD, 0x0014, unit = "subpx/frame", signed = true),
                bossBehaviorField("ceres_hover_cooldown", "Hover Attack Cooldown", 0xA6A728, 0x007C, unit = "frames"),
                bossBehaviorField("ceres_hover_target_x", "Hover Target X", 0xA6A764, 0x00C0, unit = "px"),
                bossBehaviorField("ceres_hover_target_y", "Hover Target Y", 0xA6A767, 0x0064, unit = "px"),
                bossBehaviorField("ceres_fireball_target_y", "Fireball Target Y", 0xA6A7A5, 0x0058, unit = "px"),
                bossBehaviorField("ceres_fireball_timer", "Fireball Timer", 0xA6A7DA, 0x00E0, unit = "frames"),
                bossBehaviorField("ceres_forced_swoop_delay", "Forced Swoop Delay", 0xA6A7EE, 0x0030, unit = "frames"),
                bossBehaviorField("ceres_lunge_timer", "Lunge Setup Timer", 0xA6A849, 0x0040, unit = "frames"),
                bossBehaviorField("ceres_lunge_y_offset", "Lunge Y Offset", 0xA6A858, 0x0044, unit = "px"),
                bossBehaviorField("ceres_lunge_min_y", "Lunge Minimum Y", 0xA6A860, 0x0040, unit = "px"),
                bossBehaviorField("ceres_lunge_accel_factor", "Lunge Accel Factor", 0xA6A868, 0x000D),
            ),
        ),
        BossBehaviorSection(
            "Norfair Swoop And Hover",
            "Health thresholds, swoop phase timers/speeds, hover duration, and hover-to-pogo setup.",
            Color(0xFFE64A19),
            listOf(
                bossBehaviorField("norfair_low_health_threshold", "Low Health Threshold", 0xA6B35B, 0x3840, unit = "hp"),
                bossBehaviorField("norfair_secondary_low_health_threshold", "Secondary Low Health", 0xA6B382, 0x2328, unit = "hp"),
                bossBehaviorField("norfair_swoop_start_timer", "Swoop Start Timer", 0xA6B485, 0x0020, unit = "frames"),
                bossBehaviorField("norfair_swoop_down_timer", "Swoop Down Timer", 0xA6B4CA, 0x0014, unit = "frames"),
                bossBehaviorField("norfair_swoop_horizontal_timer", "Swoop Horizontal Timer", 0xA6B508, 0x0010, unit = "frames"),
                bossBehaviorField("norfair_swoop_up_timer", "Swoop Up Timer", 0xA6B54D, 0x0020, unit = "frames"),
                bossBehaviorField("norfair_swoop_decel_timer", "Swoop Decel Timer", 0xA6B58B, 0x0020, unit = "frames"),
                bossBehaviorField("norfair_swoop_down_speed", "Swoop Down Speed", 0xA6B4B0, 0x0480, unit = "speed"),
                bossBehaviorField("norfair_swoop_horizontal_speed", "Swoop Horizontal Speed", 0xA6B4EE, 0x0500, unit = "speed"),
                bossBehaviorField("norfair_swoop_up_speed", "Swoop Up Speed", 0xA6B533, 0x0300, unit = "speed"),
                bossBehaviorField("norfair_swoop_fast_up_speed", "Fast Up Speed", 0xA6B571, 0x0300, unit = "speed"),
                bossBehaviorField("norfair_swoop_decel_speed", "Decel Speed", 0xA6B59C, 0x01C0, unit = "speed"),
                bossBehaviorField("norfair_hover_timer_base", "Hover Timer Base", 0xA6B5E0, 0x0020, unit = "frames"),
                bossBehaviorField(
                    "norfair_hover_wall_timer",
                    "Hover Wall Timer",
                    0xA6B604,
                    0x0080,
                    unit = "frames",
                    additionalWriteAddresses = intArrayOf(0xA6B632),
                ),
                bossBehaviorField("norfair_hover_target_y_cap", "Hover Target Y Cap", 0xA6B645, 0x0160, unit = "px"),
                bossBehaviorField("norfair_hover_fireball_threshold", "Hover Fireball Threshold", 0xA6B670, 0x0080, hex = true),
            ),
        ),
        BossBehaviorSection(
            "Norfair Pogo",
            "Pogo setup, target Y, ascent duration, fall clamp, and fireball cadence constants.",
            Color(0xFF6D4C41),
            listOf(
                bossBehaviorField("pogo_tail_extension_speed", "Tail Extension Speed", 0xA6B68C, 0x00F0, unit = "speed"),
                bossBehaviorField("pogo_tail_angle", "Tail Segment Angle", 0xA6B693, 0x0010, hex = true),
                bossBehaviorField(
                    "pogo_target_y",
                    "Pogo Target Y",
                    0xA6B6AB,
                    0x0120,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA6B6BB, 0xA6B6E3),
                ),
                bossBehaviorField("pogo_wait_timer", "Pogo Wait Timer", 0xA6B6D8, 0x0020, unit = "frames"),
                bossBehaviorField("pogo_ascending_timer_base", "Ascending Timer Base", 0xA6B708, 0x0080, unit = "frames"),
                bossBehaviorField("pogo_max_down_velocity", "Max Down Velocity", 0xA6B723, 0x0600, unit = "speed"),
                bossBehaviorField("pogo_fireball_counter", "Fireball Counter", 0xA6B79A, 0x0002),
            ),
        ),
    ),
)

val DRAYGON_BEHAVIOR = BossBehaviorDefinition(
    configType = DRAYGON_CONFIG_TYPE,
    title = "DRAYGON",
    subtitle = "Advanced Behavior Editor",
    description = "Edit Draygon's intro, swoop, goop, grab, spiral, foam, and bubble constants from bank A5. HP and contact damage remain in Boss Stats.",
    headerColor = Color(0xFF1565C0),
    sections = listOf(
        BossBehaviorSection(
            "Intro And Swoop",
            "Startup delays, wall turret speed, calculated swoop table bounds, and arm timing checkpoints.",
            Color(0xFF1976D2),
            listOf(
                bossBehaviorField("intro_delay", "Intro Delay", 0xA58725, 0x0100, unit = "frames"),
                bossBehaviorField("intro_dance_duration", "Intro Dance Duration", 0xA58795, 0x04D0, unit = "frames"),
                bossBehaviorField("swoop_y_acceleration", "Swoop Y Acceleration", 0xA58784, 0x0018, unit = "subpx/frame"),
                bossBehaviorField("turret_projectile_speed", "Wall Turret Speed", 0xA587D5, 0x0003, unit = "px/frame"),
                bossBehaviorField("right_reset_x_offset", "Right Reset X Offset", 0xA58768, 0x02A0, unit = "px"),
                bossBehaviorField("swoop_initial_y", "Swoop Initial Y", 0xA58818, 0x0180, unit = "px"),
                bossBehaviorField("swoop_table_max_index", "Swoop Table Max Index", 0xA58863, 0x0800, hex = true),
                bossBehaviorField(
                    "arm_apex_index",
                    "Arm Apex Index",
                    0xA588BE,
                    0x0068,
                    hex = true,
                    additionalWriteAddresses = intArrayOf(0xA5895B, 0xA58A0D, 0xA58A9D),
                ),
            ),
        ),
        BossBehaviorSection(
            "Goop Runs",
            "Shared right/left goop-run setup values and oscillation constants mirrored across Draygon's phases.",
            Color(0xFF00838F),
            listOf(
                bossBehaviorField("goop_right_start_x", "Right Start X", 0xA58B0E, 0xFFB0, unit = "px", signed = true),
                bossBehaviorField(
                    "goop_start_y",
                    "Start Y",
                    0xA58B14,
                    0x0180,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58C96),
                ),
                bossBehaviorField(
                    "goop_x_speed",
                    "X Speed",
                    0xA58B20,
                    0x0001,
                    unit = "px/frame",
                    additionalWriteAddresses = intArrayOf(0xA58CA2),
                ),
                bossBehaviorField(
                    "goop_range_to_samus",
                    "Samus Range",
                    0xA58B64,
                    0x00D0,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58CE6),
                ),
                bossBehaviorField(
                    "goop_count",
                    "Goop Counter",
                    0xA58B6F,
                    0x0010,
                    additionalWriteAddresses = intArrayOf(0xA58CF1),
                ),
                bossBehaviorField(
                    "goop_y_radius",
                    "Y Oscillation Radius",
                    0xA58B77,
                    0x0020,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58BD6, 0xA58C37, 0xA58CF9, 0xA58D55, 0xA58DBB),
                ),
                bossBehaviorField(
                    "goop_base_y",
                    "Y Oscillation Base",
                    0xA58B86,
                    0x0180,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58BE5, 0xA58C46, 0xA58D08, 0xA58D64, 0xA58DCA),
                ),
                bossBehaviorField(
                    "goop_angle_step",
                    "Y Angle Step",
                    0xA58B91,
                    0x0001,
                    hex = true,
                    additionalWriteAddresses = intArrayOf(0xA58BF0, 0xA58C51, 0xA58D13, 0xA58D6F, 0xA58DD5),
                ),
                bossBehaviorField(
                    "goop_right_boundary",
                    "Right Offscreen Boundary",
                    0xA58C0F,
                    0x02A0,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58C70),
                ),
                bossBehaviorField(
                    "goop_left_boundary",
                    "Left Offscreen Boundary",
                    0xA58D8E,
                    0xFFB0,
                    unit = "px",
                    signed = true,
                    additionalWriteAddresses = intArrayOf(0xA58DF4),
                ),
            ),
        ),
        BossBehaviorSection(
            "Grab And Spiral",
            "Grab proximity, chase speed, spiral origin, center target, tail whip, and escape values.",
            Color(0xFF5E35B1),
            listOf(
                bossBehaviorField(
                    "grab_proximity",
                    "Grab Proximity",
                    0xA58E4B,
                    0x0008,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58E5B),
                ),
                bossBehaviorField("chase_speed", "Chase Speed", 0xA58E88, 0x0002, unit = "px/frame"),
                bossBehaviorField("spiral_initial_x", "Spiral Initial X", 0xA58EDC, 0x0100, unit = "px"),
                bossBehaviorField("spiral_initial_y", "Spiral Initial Y", 0xA58EE3, 0x0180, unit = "px"),
                bossBehaviorField("spiral_initial_angle", "Spiral Initial Angle", 0xA58EF5, 0x00C0, hex = true),
                bossBehaviorField("spiral_angle_delta", "Spiral Angle Delta", 0xA58EFC, 0x0800, hex = true),
                bossBehaviorField("center_target_x", "Center Target X", 0xA58F48, 0x0100, unit = "px"),
                bossBehaviorField("center_target_y", "Center Target Y", 0xA58F58, 0x0180, unit = "px"),
                bossBehaviorField(
                    "center_tolerance",
                    "Center Tolerance",
                    0xA58F4F,
                    0x0002,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA58F5F),
                ),
                bossBehaviorField("center_move_speed", "Center Move Speed", 0xA58F8C, 0x0002, unit = "px/frame"),
                bossBehaviorField("tail_whip_random_mask", "Tail Whip Random Mask", 0xA58FFF, 0x00FF, hex = true),
                bossBehaviorField("tail_whip_timer", "Tail Whip Timer", 0xA59004, 0x0040, unit = "frames"),
                bossBehaviorField("spiral_radius_increment", "Radius Increment", 0xA5907B, 0x2000, hex = true),
                bossBehaviorField("spiral_max_radius", "Max Radius", 0xA5908D, 0x00A0, unit = "px"),
                bossBehaviorField("spiral_angle_delta_decay", "Angle Delta Decay", 0xA59097, 0x0001, hex = true),
                bossBehaviorField("spiral_y_substep", "Y Substep", 0xA590B3, 0x4000, hex = true),
                bossBehaviorField("final_tail_whip_y_threshold", "Final Tail-Whip Y", 0xA590C5, 0x0040, unit = "px"),
                bossBehaviorField("fly_up_speed", "Fly-Up Speed", 0xA5915C, 0x0004, unit = "px/frame"),
            ),
        ),
        BossBehaviorSection(
            "Foam And Bubbles",
            "Frame masks and spawn offsets used by Draygon's foam and short breath-bubble routines.",
            Color(0xFF00ACC1),
            listOf(
                bossBehaviorField("foam_frequency_mask", "Foam Frequency Mask", 0xA59045, 0x0007, hex = true),
                bossBehaviorField(
                    "foam_x_offset",
                    "Foam X Offset",
                    0xA5904E,
                    0x0020,
                    unit = "px",
                    additionalWriteAddresses = intArrayOf(0xA5905D),
                ),
                bossBehaviorField("foam_y_offset", "Foam Y Offset", 0xA59066, 0x0010, unit = "px"),
                bossBehaviorField("breath_bubble_mask", "Breath Bubble Mask", 0xA59320, 0x007F, hex = true),
                bossBehaviorField("breath_bubble_x_offset", "Breath Bubble X Offset", 0xA59329, 0xFFF0, unit = "px", signed = true),
                bossBehaviorField("breath_bubble_y_offset", "Breath Bubble Y Offset", 0xA59332, 0xFFF0, unit = "px", signed = true),
            ),
        ),
    ),
)

val BOSS_BEHAVIOR_DEFINITIONS: List<BossBehaviorDefinition> = listOf(RIDLEY_BEHAVIOR, DRAYGON_BEHAVIOR)

val BOSS_BEHAVIOR_BY_CONFIG_TYPE: Map<String, BossBehaviorDefinition> =
    BOSS_BEHAVIOR_DEFINITIONS.associateBy { it.configType }

val BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE: Map<String, List<BossBehaviorField>> =
    BOSS_BEHAVIOR_DEFINITIONS.associate { it.configType to it.sections.flatMap { section -> section.fields } }

val ALL_RIDLEY_FIELDS: List<BossBehaviorField> = BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE.getValue(RIDLEY_CONFIG_TYPE)
val ALL_DRAYGON_FIELDS: List<BossBehaviorField> = BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE.getValue(DRAYGON_CONFIG_TYPE)

@Composable
fun BossBehaviorEditor(
    definition: BossBehaviorDefinition,
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val fields = BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE.getValue(definition.configType)
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in fields) {
            map[field.key] = stored?.get(field.key)
                ?: readBossBehaviorFromRom(romParser, field)
                ?: field.defaultValue
        }
        map
    }

    fun apply(field: BossBehaviorField, value: Int) {
        values[field.key] = value
        editorState.setPatchConfigData(patch.id, field.key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        BossBehaviorHeader(definition)
        Spacer(Modifier.height(20.dp))

        for ((idx, section) in definition.sections.withIndex()) {
            BossBehaviorSectionCard(section, values, ::apply)
            if (idx < definition.sections.lastIndex) Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                for (field in fields) {
                    val rom = readBossBehaviorFromRom(romParser, field) ?: field.defaultValue
                    apply(field, rom)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun BossBehaviorHeader(definition: BossBehaviorDefinition) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = definition.headerColor.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, definition.headerColor.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                definition.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = definition.headerColor.copy(red = (definition.headerColor.red + 0.35f).coerceAtMost(1f)),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                definition.subtitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                definition.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun BossBehaviorSectionCard(
    section: BossBehaviorSection,
    values: Map<String, Int>,
    onApply: (BossBehaviorField, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = section.color.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, section.color.copy(alpha = 0.20f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "\u25BC " else "\u25B6 ",
                    fontSize = 11.sp,
                    color = section.color,
                    modifier = Modifier.width(16.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        section.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!expanded) {
                        Text(
                            "${section.fields.size} fields",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    section.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Field",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Value",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text("", modifier = Modifier.width(72.dp))
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))

                for (field in section.fields) {
                    BossBehaviorFieldRow(
                        field = field,
                        value = values[field.key] ?: field.defaultValue,
                        onChange = { onApply(field, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BossBehaviorFieldRow(
    field: BossBehaviorField,
    value: Int,
    onChange: (Int) -> Unit,
) {
    val isModified = value != field.defaultValue
    val displayValue = if (field.signed && value > 32767) value - 65536 else value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            field.label,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
            color = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )

        if (field.hex) {
            BossBehaviorHexInput(value, onChange, Modifier.width(80.dp))
        } else if (field.signed) {
            BossBehaviorSignedInput(
                value = displayValue,
                onChange = { signed ->
                    val stored = if (signed < 0) signed + 65536 else signed
                    onChange(stored.coerceIn(0, 65535))
                },
                modifier = Modifier.width(80.dp),
            )
        } else {
            BossBehaviorIntInput(value, onChange, Modifier.width(80.dp))
        }

        val annotation = when {
            field.unit == "frames" -> "%.1fs".format(displayValue / 60.0)
            field.unit.isNotEmpty() -> field.unit
            else -> ""
        }
        Text(
            annotation,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp).padding(start = 8.dp),
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun BossBehaviorIntInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(0, 65535)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun BossBehaviorSignedInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') }.take(6)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(-32768, 32767)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun BossBehaviorHexInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.toString(16).uppercase().padStart(4, '0')) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(4)
            text = filtered.uppercase()
            filtered.toIntOrNull(16)?.let { onChange(it.coerceIn(0, 65535)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Row(
                modifier = modifier
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                inner()
            }
        },
    )
}

internal fun readBossBehaviorFromRom(romParser: RomParser?, field: BossBehaviorField): Int? {
    if (romParser == null) return null
    return try {
        val pc = romParser.snesToPc(field.snesAddress)
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
