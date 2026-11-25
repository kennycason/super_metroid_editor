package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.RomParser

private var environmentalSelection by mutableStateOf<Pair<String?, Int>>(null to 0)
internal fun selectEnvironmentalDamage(key: String?) { environmentalSelection = key to (environmentalSelection.second + 1) }

fun readEnvironmentalDamageRomRates(romParser: RomParser?): Map<String, Int> {
    val rom = try {
        romParser?.getRomData()
    } catch (_: Exception) {
        null
    }
    return EnvironmentalDamagePatch.FIELDS.associate { field ->
        val header = if (rom != null && rom.size % 0x8000 == 512) 512 else 0
        val low = rom?.readEnvironmentalWord(header + field.lowWordPc)
        val high = rom?.readEnvironmentalWord(header + field.highWordPc)
        val value = if (low != null && high != null) {
            EnvironmentalDamagePatch.decodeEnergyPerSecond(low, high)
        } else {
            field.defaultEnergyPerSecond
        }
        field.key to value
    }
}

private fun ByteArray.readEnvironmentalWord(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

@Composable
fun EnvironmentalDamageEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: (() -> Unit)? = null,
) {
    val sourceMode = editorState.asmNavigationEnabled
    val bridge = editorState.environmentalDamageBridge.takeIf { sourceMode }
    @Suppress("UNUSED_VARIABLE") val sourceRevision = bridge?.revision
    val romRates = remember(romParser) { readEnvironmentalDamageRomRates(romParser) }
    @Suppress("UNUSED_VARIABLE") val patchVersion = editorState.patchVersion
    val perAreaRates = patch.configData?.get(EnvironmentalDamagePatch.PER_AREA_RATES_KEY) == 1
    val sourceViews = if (sourceMode) EnvironmentalDamagePatch.FIELDS.associate { field ->
        field.key to (bridge?.value(patch, field.key)
            ?: com.supermetroid.editor.asm.AsmEnvironmentalDamageView(null, "—", "Wait for environmental source indexing."))
    } else emptyMap()

    fun currentValue(key: String): Int =
        patch.configData?.get(key) ?: romRates.getValue(key)

    fun apply(key: String, value: Int) {
        editorState.setPatchConfigData(
            patch.id,
            key,
            value.coerceIn(
                EnvironmentalDamagePatch.MIN_ENERGY_PER_SECOND,
                EnvironmentalDamagePatch.MAX_ENERGY_PER_SECOND,
            ),
        )
    }
    fun applyTourianOption(key: String, enabled: Boolean) {
        editorState.setPatchConfigData(
            patch.id,
            key,
            if (enabled) 1 else 0,
        )
    }
    fun applyPerAreaRates(enabled: Boolean) {
        editorState.setPatchConfigData(
            patch.id,
            EnvironmentalDamagePatch.PER_AREA_RATES_KEY,
            if (enabled) 1 else 0,
        )
    }
    fun scalarDisplay(field: EnvironmentalDamagePatch.Field): String =
        if (sourceMode) sourceViews[field.key]?.display ?: "—" else currentValue(field.key).toString()
    fun areaDisplay(area: EnvironmentalDamagePatch.RateRegion, field: EnvironmentalDamagePatch.Field): String =
        patch.configData?.get(EnvironmentalDamagePatch.areaRateKey(area, field))?.toString()
            ?: scalarDisplay(field)
    fun applyArea(area: EnvironmentalDamagePatch.RateRegion, field: EnvironmentalDamagePatch.Field, value: Int) {
        apply(EnvironmentalDamagePatch.areaRateKey(area, field), value)
    }
    fun openSource(key: String? = null) {
        if (editorState.openEnvironmentalDamageSource(key)) onNavigateToAsm?.invoke()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Environmental Damage", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Base energy drained each second. In per-area mode, a positive regional heat rate heats that whole region; 0 disables its added heat damage.",
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sourceMode) TextButton(onClick = { openSource() }, modifier = Modifier.testTag("environmental-asm")) { Text("ASM", maxLines = 1, softWrap = false) }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(
                onClick = {
                    if (sourceMode) runCatching { requireNotNull(bridge).resetToOriginal() }
                        .onFailure { editorState.postStatus(it.message ?: "Cannot reset environmental ASM.") }
                    else EnvironmentalDamagePatch.FIELDS.forEach { field ->
                        apply(field.key, field.defaultEnergyPerSecond)
                    }
                    applyPerAreaRates(false)
                    applyTourianOption(
                        EnvironmentalDamagePatch.TOURIAN_NO_HEAT_BABY_TO_MB_KEY,
                        EnvironmentalDamagePatch.DEFAULT_TOURIAN_NO_HEAT_BABY_TO_MB,
                    )
                    applyTourianOption(
                        EnvironmentalDamagePatch.TOURIAN_NO_HEAT_ESCAPE_KEY,
                        EnvironmentalDamagePatch.DEFAULT_TOURIAN_NO_HEAT_ESCAPE,
                    )
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(if (sourceMode) "Original ASM" else "Vanilla", fontSize = LocalEditorTheme.current.fontSize.value.body)
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Per-Area Rates", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Give each game region its own heat, lava, and acid rate. A positive heat rate automatically heats that region while preserving its existing room FX.",
                        fontSize = LocalEditorTheme.current.fontSize.value.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = perAreaRates,
                    onCheckedChange = ::applyPerAreaRates,
                    modifier = Modifier.testTag("environmental-per-area-rates"),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (!perAreaRates) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    EnvironmentalDamagePatch.FIELDS.forEachIndexed { index, field ->
                        val view = sourceViews[field.key]
                        EnvironmentalDamageRateRow(
                            key = field.key,
                            label = field.label,
                            suitBehavior = field.suitBehavior,
                            value = if (sourceMode) view?.value else currentValue(field.key),
                            display = if (sourceMode) view!!.display else currentValue(field.key).toString(),
                            enabled = view?.problem == null,
                            vanillaValue = field.defaultEnergyPerSecond,
                            onChange = { apply(field.key, it) },
                            onAsm = if (sourceMode) ({ openSource(field.key) }) else null,
                        )
                        view?.problem?.let { problem ->
                            Text(if (view.managedInAsm) "Managed in ASM: $problem" else "Read-only: $problem",
                                color = if (view.managedInAsm) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                fontSize = LocalEditorTheme.current.fontSize.value.detail)
                        }
                        if (view?.conflict == true) TextButton(onClick = {
                            runCatching { bridge?.useSourceValue(field.key) }.onFailure { editorState.postStatus(it.message ?: "Cannot use ASM values.") }
                        }, modifier = Modifier.testTag("environmental-use-asm-${field.key}")) { Text("Use ASM values") }
                        if (index != EnvironmentalDamagePatch.FIELDS.lastIndex) {
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Region", modifier = Modifier.width(116.dp), fontWeight = FontWeight.SemiBold)
                        EnvironmentalDamagePatch.FIELDS.forEach { field ->
                            Text(
                                field.label,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontSize = LocalEditorTheme.current.fontSize.value.body,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    EnvironmentalDamagePatch.DISPLAY_RATE_REGIONS.forEach { area ->
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("environmental-area-${area.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(area.label, modifier = Modifier.width(116.dp), fontSize = LocalEditorTheme.current.fontSize.value.body)
                            EnvironmentalDamagePatch.FIELDS.forEach { field ->
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    DamageRateInput(
                                        display = areaDisplay(area, field),
                                        enabled = true,
                                        onChange = { applyArea(area, field, it) },
                                        modifier = Modifier.testTag("environmental-area-${area.key}-${field.key}"),
                                        fieldWidth = 82.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Tourian Heat Safety", fontWeight = FontWeight.SemiBold)
                    Text(
                        "These exclusions apply whenever the Tourian heat rate is above 0.",
                        fontSize = LocalEditorTheme.current.fontSize.value.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TourianHeatOptionRow(
                        label = "No heat from Baby to Mother Brain",
                        detail = "Protect Big Boy/Baby Metroid, Seaweed, Recharge, Mother Brain, Eye Door, and Rinka Shaft.",
                        checked = EnvironmentalDamagePatch.tourianNoHeatBabyToMotherBrain(patch.configData.orEmpty()),
                        onCheckedChange = {
                            applyTourianOption(EnvironmentalDamagePatch.TOURIAN_NO_HEAT_BABY_TO_MB_KEY, it)
                        },
                        testTag = "environmental-tourian-no-heat-baby-mb",
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    TourianHeatOptionRow(
                        label = "No heat during escape",
                        detail = "Disable heat in all four timed Tourian escape rooms.",
                        checked = EnvironmentalDamagePatch.tourianNoHeatDuringEscape(patch.configData.orEmpty()),
                        onCheckedChange = {
                            applyTourianOption(EnvironmentalDamagePatch.TOURIAN_NO_HEAT_ESCAPE_KEY, it)
                        },
                        testTag = "environmental-tourian-no-heat-escape",
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            when {
                sourceMode && perAreaRates -> "Area overrides stay in project settings; inherited entries preserve both exact ASM source words."
                sourceMode -> "Rates accumulate every frame. Edits enable this patch automatically; disabling keeps custom values for later. Both words of each rate edit together."
                else -> "Rates are accumulated every frame, so low values still drain smoothly."
            },
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun TourianHeatOptionRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                detail,
                fontSize = LocalEditorTheme.current.fontSize.value.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EnvironmentalDamageRateRow(
    key: String,
    label: String,
    suitBehavior: String,
    value: Int?,
    display: String,
    enabled: Boolean,
    vanillaValue: Int,
    onChange: (Int) -> Unit,
    onAsm: (() -> Unit)?,
) {
    val requester = remember { BringIntoViewRequester() }
    val selection = environmentalSelection
    LaunchedEffect(selection) { if (selection.first == key) requester.bringIntoView() }
    Row(
        modifier = Modifier.bringIntoViewRequester(requester).fillMaxWidth()
            .background(if (selection.first == key) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(vertical = 10.dp).testTag("environmental-row-$key"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold)
                if (value == vanillaValue) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "VANILLA",
                        fontSize = LocalEditorTheme.current.fontSize.value.statusBar,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                suitBehavior,
                fontSize = LocalEditorTheme.current.fontSize.value.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (onAsm != null) TextButton(onClick = onAsm, modifier = Modifier.width(64.dp).testTag("environmental-asm-$key"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("ASM", maxLines = 1, softWrap = false) }
        DamageStepButton("−", enabled = enabled && value != null && value > EnvironmentalDamagePatch.MIN_ENERGY_PER_SECOND) {
            value?.let { onChange(it - 1) }
        }
        DamageRateInput(display = display, enabled = enabled, onChange = onChange,
            modifier = Modifier.testTag("environmental-rate-$key").onFocusChanged { if (it.isFocused) selectEnvironmentalDamage(key) })
        DamageStepButton("+", enabled = enabled && value != null && value < EnvironmentalDamagePatch.MAX_ENERGY_PER_SECOND) {
            value?.let { onChange(it + 1) }
        }
        Text(
            "energy/sec",
            fontSize = LocalEditorTheme.current.fontSize.value.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
    }
}

@Composable
private fun DamageRateInput(
    display: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fieldWidth: Dp = 96.dp,
) {
    var text by remember(display, enabled) { mutableStateOf(display) }
    BasicTextField(
        value = text,
        onValueChange = { candidate ->
            val filtered = candidate.filter(Char::isDigit).take(4)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it) }
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = LocalEditorTheme.current.fontSize.value.heading,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.width(fieldWidth),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                inner()
            }
        },
    )
}

@Composable
private fun DamageStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(28.dp).height(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(label, fontSize = LocalEditorTheme.current.fontSize.value.heading)
    }
}
