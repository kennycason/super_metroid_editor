package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmRomSpaceBank
import com.supermetroid.editor.asm.AsmRomSpacePlan
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.formatPcOffset
import com.supermetroid.editor.asm.formatSnesAddress

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AsmRomSpacePlannerCanvas(
    state: AsmWorkspaceState,
    plan: AsmRomSpacePlan?,
    planFits: Boolean,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    if (plan == null) {
        Column(modifier.fillMaxSize()) {
            AsmNavigationHeader(
                state = state,
                title = "ROM Space Planner",
                detail = "Allocator capacity and placements from the validated export transaction",
                trailing = "read-only",
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Refresh SMEDIT Result to calculate allocator space.", fontSize = fs.body)
            }
        }
        return
    }

    val activityBanks = remember(plan) {
        plan.banks.filter { it.consumedBytes > 0 || it.placements.isNotEmpty() }
    }
    val focusBanks = remember(plan, planFits) {
        if (planFits) activityBanks
        else plan.banks.filter { it.remainingAfter > 0 }.sortedByDescending(AsmRomSpaceBank::largestAfter)
    }
    val requestedBank = state.requestedRomSpaceBank
    var showAllBanks by remember(plan, planFits, requestedBank) {
        mutableStateOf(
            focusBanks.isEmpty() || requestedBank?.let { requested -> focusBanks.none { it.bank == requested } } == true,
        )
    }
    val displayedBanks = if (showAllBanks) plan.banks else focusBanks
    var selectedBankId by remember(plan, planFits, requestedBank) {
        mutableStateOf(
            requestedBank?.takeIf { requested -> plan.banks.any { it.bank == requested } }
                ?: focusBanks.firstOrNull()?.bank
                ?: plan.largestRemainingBank?.bank,
        )
    }
    LaunchedEffect(showAllBanks, displayedBanks, requestedBank) {
        if (displayedBanks.none { it.bank == selectedBankId }) selectedBankId = displayedBanks.firstOrNull()?.bank
    }
    val selectedBank = plan.banks.firstOrNull { it.bank == selectedBankId }
    val largest = plan.largestRemainingBank?.takeIf { it.largestAfter > 0 }

    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(
            state = state,
            title = "ROM Space Planner",
            detail = if (planFits) {
                "Exact placements from the same validated transaction used by Export ROM"
            } else {
                "Partial allocator state captured when the build stopped; no ROM was written"
            },
            trailing = if (planFits) "Plan fits · read-only" else "Plan blocked · read-only",
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SpaceFact("ROM size", "${plan.romBodySize / (1024 * 1024)} MiB")
                SpaceFact("Placements", "${plan.placements.size} · ${plannerBytes(plan.allocationBytes)}")
                SpaceFact("Consumed", plannerBytes(plan.consumedBytes))
                SpaceFact("Total remaining", plannerBytes(plan.remainingAfter))
                SpaceFact(
                    "Largest block",
                    largest?.let { "\$${it.bank.hex2()} · ${plannerBytes(it.largestAfter)}" } ?: "None",
                )
            }
        }
        Text(
            "Each LoROM bank is 32 KiB. Only source-declared free ranges and managed expanded banks are eligible. Total space may be fragmented; “largest block” is the biggest single allocation that currently fits.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        )
        Divider()
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(350.dp).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Banks", fontSize = fs.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = !showAllBanks,
                        onClick = { showAllBanks = false },
                        enabled = focusBanks.isNotEmpty(),
                        label = { Text(if (planFits) "Plan activity" else "Available", fontSize = fs.detail) },
                        modifier = Modifier.testTag("asm-space-active-banks"),
                    )
                    Spacer(Modifier.width(5.dp))
                    FilterChip(
                        selected = showAllBanks,
                        onClick = { showAllBanks = true },
                        label = { Text("All", fontSize = fs.detail) },
                        modifier = Modifier.testTag("asm-space-all-banks"),
                    )
                }
                if (displayedBanks.isEmpty()) {
                    Text(
                        if (planFits) "No shared allocator space is consumed by this plan."
                        else "No allocator-compatible contiguous space remains.",
                        fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(displayedBanks, key = AsmRomSpaceBank::bank) { bank ->
                            RomSpaceBankRow(bank, bank.bank == selectedBankId) { selectedBankId = bank.bank }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            if (selectedBank == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select All to inspect available banks.", fontSize = fs.body)
                }
            } else {
                RomSpaceBankDetails(selectedBank, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun RomSpaceBankRow(bank: AsmRomSpaceBank, selected: Boolean, onClick: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val ratio = if (bank.capacityBytes <= 0) 0f
    else (bank.remainingAfter.toFloat() / bank.capacityBytes).coerceIn(0f, 1f)
    val accent = when {
        bank.remainingAfter == 0 && bank.consumedBytes > 0 -> MaterialTheme.colorScheme.error
        bank.remainingAfter in 1 until ROM_SPACE_LOW_BYTES -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .testTag("asm-space-bank-${bank.bank.hex2()}"),
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("\$${bank.bank.hex2()}", fontFamily = FontFamily.Monospace, fontSize = fs.body,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(42.dp))
                Text(
                    "${plannerBytes(bank.capacityBytes)} · ${plannerBytes(bank.remainingAfter)} total · " +
                        "${plannerBytes(bank.largestAfter)} largest",
                    fontFamily = FontFamily.Monospace,
                    fontSize = fs.detail,
                    modifier = Modifier.weight(1f),
                )
                if (bank.placements.isNotEmpty()) Text(
                    "${bank.placements.size} placement${if (bank.placements.size == 1) "" else "s"}",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                if (ratio > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).background(accent))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RomSpaceBankDetails(bank: AsmRomSpaceBank, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bankEnd = bank.pcStart + bank.capacityBytes
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bank \$${bank.bank.hex2()}", fontSize = fs.heading, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatPcOffset(bank.pcStart)}–${formatPcOffset(bankEnd - 1)} · " +
                        "${bank.rangesAfter.size} usable block${if (bank.rangesAfter.size == 1) "" else "s"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${plannerBytes(bank.remainingAfter)} total · ${plannerBytes(bank.largestAfter)} largest",
                fontFamily = FontFamily.Monospace,
                fontSize = fs.body,
                fontWeight = FontWeight.SemiBold,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SpaceFact("Bank capacity", plannerBytes(bank.capacityBytes))
            SpaceFact("Fixed / protected", plannerBytes((bank.capacityBytes - bank.availableBefore).coerceAtLeast(0)))
            SpaceFact("Allocatable before", plannerBytes(bank.availableBefore))
            SpaceFact("Largest before", plannerBytes(bank.largestBefore))
            SpaceFact("Planned consumption", plannerBytes(bank.consumedBytes))
            SpaceFact("Dynamic placements", plannerBytes(bank.placements.sumOf { it.size }))
            SpaceFact("Allocatable after", plannerBytes(bank.remainingAfter))
            SpaceFact("Largest after", plannerBytes(bank.largestAfter))
        }
        Divider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item("ranges-title") {
                Text("Verified free-space blocks", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
            }
            if (bank.rangesAfter.isEmpty()) {
                item("ranges-empty") { Text("No allocator-compatible block remains", fontSize = fs.detail) }
            } else {
                items(bank.rangesAfter.size, key = { "range-${bank.rangesAfter[it].pcStart}" }) { index ->
                    val range = bank.rangesAfter[index]
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${formatPcOffset(range.pcStart)}–${formatPcOffset(range.endExclusive - 1)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = fs.detail,
                            modifier = Modifier.width(170.dp),
                        )
                        Text(plannerBytes(range.length), fontFamily = FontFamily.Monospace, fontSize = fs.detail,
                            modifier = Modifier.width(72.dp))
                        Text(range.label, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item("ranges-divider") { Divider() }
            item("placements-title") {
                Text("Allocator placements", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
            }
            if (bank.placements.isEmpty()) {
                item("placements-empty") {
                    Text(
                        if (bank.consumedBytes > 0) {
                            "No shared-allocator reservation. Fixed or non-allocator writes moved this bank’s safe boundary."
                        } else {
                            "No generated allocation was placed in this bank."
                        },
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(bank.placements, key = { "${it.pcOffset}:${it.size}:${it.label}" }) { placement ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(9.dp)) {
                            Text(
                                placement.label,
                                fontSize = fs.body,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatSnesAddress(placement.snesAddress)} · ${formatPcOffset(placement.pcOffset)} · ${plannerBytes(placement.size)}" +
                                    placement.owner?.let { " · $it" }.orEmpty(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = fs.detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (placement.segmentId != null) {
                                Text(
                                    buildString {
                                        append(placement.segmentKind ?: "segment")
                                        append(" · requested ").append(plannerBytes(placement.requestedSize))
                                        append(" · align ").append(placement.alignment)
                                        append(" · banks ").append(placement.allowedBanks.joinToString { "\$${it.hex2()}" })
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fs.detail,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                placement.constraint?.let { constraint ->
                                    Text(
                                        constraint,
                                        fontSize = fs.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item("owners-divider") { Divider(Modifier.padding(top = 4.dp)) }
            item("owners-title") {
                Text("Planned ownership in this bank", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
            }
            if (bank.owners.isEmpty()) {
                item("owners-empty") {
                    Text("No planned writes claim this bank.", fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(bank.owners, key = { it.owner }) { owner ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(owner.owner, fontFamily = FontFamily.Monospace, fontSize = fs.detail,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${owner.writes} write${if (owner.writes == 1) "" else "s"} · ${owner.kinds.joinToString("/")}",
                            fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceFact(label: String, value: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column {
        Text(label, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = fs.body, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

private fun plannerBytes(bytes: Int): String = formatAsmBackupSize(bytes.toLong())
private fun Int.hex2(): String = toString(16).uppercase().padStart(2, '0')
private const val ROM_SPACE_LOW_BYTES = 4096
