from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict, deque
from pathlib import Path
from typing import Callable

import numpy as np
import torch

from . import MODEL_FORMAT, MODEL_FORMAT_VERSION
from .data import (
    BLOCK_MASK_TOKEN,
    BTS_MASK_TOKEN,
    PASSABLE_TYPES,
    TILE_MASK_TOKEN,
    GenerationVocabulary,
    neighbor_solid_mask,
    pack_detail_adjacency,
    pack_detail_pair,
)
from .format import RoomGrid, file_sha256, find_door_groups, resolve_block_types
from .model import RoomDenoiser, RoomModelConfig
from .train import choose_device

def sample_rows(logits: torch.Tensor, temperature: float) -> tuple[torch.Tensor, torch.Tensor]:
    if temperature <= 0:
        probabilities = logits.softmax(dim=1)
        confidence, samples = probabilities.max(dim=1)
        return samples, confidence
    probabilities = (logits / temperature).softmax(dim=1)
    samples = torch.multinomial(probabilities, 1).squeeze(1)
    confidence = probabilities.gather(1, samples[:, None]).squeeze(1)
    return samples, confidence


def sample_from_allowed(
    logits: torch.Tensor,
    allowed: list[int],
    temperature: float,
) -> tuple[torch.Tensor, torch.Tensor]:
    choices = torch.tensor(allowed, dtype=torch.long, device=logits.device)
    local_samples, confidence = sample_rows(logits.index_select(1, choices), temperature)
    return choices[local_samples], confidence


def calibrate_open_fraction(
    logits: torch.Tensor,
    allowed: list[int],
    target_open_fraction: float | None,
    temperature: float,
) -> torch.Tensor:
    """Apply one scalar logit bias so new-room samples honor a target density."""
    if target_open_fraction is None:
        return logits
    choices = torch.tensor(allowed, dtype=torch.long, device=logits.device)
    local = logits.index_select(1, choices)
    open_choices = torch.tensor(
        [block_type in PASSABLE_TYPES for block_type in allowed],
        dtype=torch.bool,
        device=logits.device,
    )
    if not open_choices.any() or open_choices.all():
        return logits
    scale = max(temperature, 1e-3)
    low = -16.0
    high = 16.0
    for _ in range(24):
        bias = (low + high) / 2.0
        biased = local + open_choices[None].to(local.dtype) * bias
        open_probability = (biased / scale).softmax(dim=1)[:, open_choices].sum(dim=1).mean()
        if float(open_probability) < target_open_fraction:
            low = bias
        else:
            high = bias
    result = logits.clone()
    open_indices = choices[open_choices]
    result[:, open_indices] += (low + high) / 2.0
    return result


def sample_detail_pairs(
    tile_logits: torch.Tensor,
    bts_logits: torch.Tensor,
    predicted_types: torch.Tensor,
    allowed_for_type: Callable[[int], list[int]],
    temperature: float,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    tile_samples = torch.empty_like(predicted_types)
    bts_samples = torch.empty_like(predicted_types)
    confidence = torch.empty(predicted_types.shape, dtype=tile_logits.dtype, device=tile_logits.device)
    for block_type_tensor in predicted_types.unique():
        block_type = int(block_type_tensor.item())
        positions = torch.nonzero(predicted_types == block_type_tensor, as_tuple=False).squeeze(1)
        allowed_pairs = torch.tensor(
            allowed_for_type(block_type), dtype=torch.long, device=tile_logits.device
        )
        allowed_tiles = allowed_pairs >> 8
        allowed_bts = allowed_pairs & 0xFF
        local_logits = tile_logits.index_select(0, positions).index_select(1, allowed_tiles)
        local_logits = local_logits + bts_logits.index_select(0, positions).index_select(1, allowed_bts)
        local_samples, local_confidence = sample_rows(local_logits, temperature)
        selected_pairs = allowed_pairs[local_samples]
        tile_samples[positions] = selected_pairs >> 8
        bts_samples[positions] = selected_pairs & 0xFF
        confidence[positions] = local_confidence
    return tile_samples, bts_samples, confidence


def coordinate_features(room: RoomGrid, device: torch.device) -> torch.Tensor:
    yy, xx = np.mgrid[0 : room.height, 0 : room.width]
    coordinates = np.stack(
        (
            xx / max(room.width - 1, 1),
            yy / max(room.height - 1, 1),
            np.full_like(xx, min(room.width / 160.0, 2.0), dtype=np.float64),
            np.full_like(yy, min(room.height / 160.0, 2.0), dtype=np.float64),
        ),
        axis=0,
    ).astype(np.float32)
    return torch.from_numpy(coordinates)[None].to(device)


def initial_generation_mask(
    room: RoomGrid,
    mode: str,
    strength: float,
    preserve_existing_doors: bool,
    seed: int,
) -> np.ndarray:
    rng = np.random.default_rng(seed)
    if mode == "new":
        mask = np.ones((room.height, room.width), dtype=np.bool_)
    else:
        mask = np.zeros((room.height, room.width), dtype=np.bool_)
        target = round(room.cell_count * strength)
        attempts = 0
        while int(mask.sum()) < target and attempts < 128:
            attempts += 1
            max_width = max(4, min(room.width, 24))
            max_height = max(4, min(room.height, 24))
            min_width = min(room.width, 5)
            min_height = min(room.height, 5)
            rect_width = int(rng.integers(min_width, max_width + 1))
            rect_height = int(rng.integers(min_height, max_height + 1))
            x = int(rng.integers(0, room.width - rect_width + 1))
            y = int(rng.integers(0, room.height - rect_height + 1))
            available = [
                (py, px)
                for py in range(y, y + rect_height)
                for px in range(x, x + rect_width)
                if not mask[py, px]
            ]
            remaining_count = target - int(mask.sum())
            if len(available) > remaining_count:
                center_x = x + (rect_width - 1) / 2.0
                center_y = y + (rect_height - 1) / 2.0
                available.sort(
                    key=lambda cell: (
                        abs(cell[1] - center_x) + abs(cell[0] - center_y),
                        cell[0],
                        cell[1],
                    )
                )
                available = available[:remaining_count]
            for py, px in available:
                mask[py, px] = True
        if int(mask.sum()) < target:
            remaining = np.flatnonzero(~mask.reshape(-1))
            rng.shuffle(remaining)
            mask.reshape(-1)[remaining[: target - int(mask.sum())]] = True
    if preserve_existing_doors:
        original_types = np.asarray(room.block_types, dtype=np.int64).reshape(room.height, room.width)
        mask[original_types == 0x9] = False
    if not mask.any():
        raise ValueError("generation mask is empty; increase --strength or disable door preservation")
    return mask


@torch.inference_mode()
def generate_room(
    model: RoomDenoiser,
    vocabulary: GenerationVocabulary,
    room: RoomGrid,
    device: torch.device,
    mode: str,
    strength: float,
    seed: int,
    steps: int,
    temperature: float,
    preserve_existing_doors: bool,
    allow_new_doors: bool,
    target_open_fraction: float | None,
) -> tuple[list[int], list[int], list[int], list[int]]:
    if room.tileset < 0 or room.tileset >= 256:
        raise ValueError(f"tileset {room.tileset} is outside the model range 0..255")
    if room.area < 0 or room.area >= 16:
        raise ValueError(f"area {room.area} is outside the model range 0..15")
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    original_words = np.asarray(room.layer1_words, dtype=np.int64).reshape(room.height, room.width)
    current_types = torch.from_numpy(
        np.asarray(room.block_types, dtype=np.int64).reshape(room.height, room.width).copy()
    ).to(device)
    current_tiles = torch.from_numpy((original_words & 0xFFF).copy()).to(device)
    current_bts = torch.from_numpy(
        np.asarray(room.bts, dtype=np.int64).reshape(room.height, room.width).copy()
    ).to(device)
    unresolved = torch.from_numpy(
        initial_generation_mask(room, mode, strength, preserve_existing_doors, seed)
    ).to(device)
    original_mask = unresolved.clone()
    valid = torch.ones((1, room.height, room.width), dtype=torch.bool, device=device)
    tileset = torch.tensor([room.tileset], dtype=torch.long, device=device)
    area = torch.tensor([room.area], dtype=torch.long, device=device)
    coordinates = coordinate_features(room, device)
    total = int(unresolved.sum().item())
    revealed = 0
    desired_open_cells = (
        round(room.cell_count * target_open_fraction)
        if target_open_fraction is not None
        else None
    )
    model.eval()

    for step in range(steps):
        input_types = current_types.clone()
        input_tiles = current_tiles.clone()
        input_bts = current_bts.clone()
        input_types[unresolved] = BLOCK_MASK_TOKEN
        input_tiles[unresolved] = TILE_MASK_TOKEN
        input_bts[unresolved] = BTS_MASK_TOKEN
        block_logits, tile_logits, bts_logits = model(
            input_types[None],
            input_tiles[None],
            input_bts[None],
            tileset,
            area,
            coordinates,
            unresolved[None],
            valid,
        )
        positions = torch.nonzero(unresolved.reshape(-1), as_tuple=False).squeeze(1)
        flat_block_logits = block_logits.permute(0, 2, 3, 1).reshape(-1, block_logits.shape[1])
        flat_tile_logits = tile_logits.permute(0, 2, 3, 1).reshape(-1, tile_logits.shape[1])
        flat_bts_logits = bts_logits.permute(0, 2, 3, 1).reshape(-1, bts_logits.shape[1])
        allowed_block_types = vocabulary.allowed_block_types(room.tileset)
        if not allow_new_doors:
            allowed_block_types = [block_type for block_type in allowed_block_types if block_type != 0x9]
        remaining_open_fraction = target_open_fraction
        if desired_open_cells is not None:
            resolved_mask = ~unresolved
            resolved_open_cells = sum(
                int(((current_types == block_type) & resolved_mask).sum().item())
                for block_type in PASSABLE_TYPES
            )
            remaining_open_fraction = max(
                0.01,
                min(0.99, (desired_open_cells - resolved_open_cells) / len(positions)),
            )
        position_block_logits = calibrate_open_fraction(
            flat_block_logits.index_select(0, positions),
            allowed_block_types,
            remaining_open_fraction,
            temperature,
        )
        predicted_types, type_confidence = sample_from_allowed(
            position_block_logits,
            allowed_block_types,
            temperature,
        )
        predicted_tiles, predicted_bts, detail_confidence = sample_detail_pairs(
            flat_tile_logits.index_select(0, positions),
            flat_bts_logits.index_select(0, positions),
            predicted_types,
            lambda block_type: vocabulary.allowed_detail_pairs(room.tileset, block_type),
            temperature,
        )
        confidence = type_confidence * 0.55 + detail_confidence * 0.45
        target_revealed = math.ceil(total * (step + 1) / steps)
        reveal_count = min(len(positions), max(1, target_revealed - revealed))
        if step == steps - 1 or reveal_count == len(positions):
            selected = torch.arange(len(positions), device=device)
        else:
            selected = torch.topk(confidence, reveal_count, sorted=False).indices
        selected_positions = positions[selected]
        flat_types = current_types.reshape(-1)
        flat_tiles = current_tiles.reshape(-1)
        flat_bts = current_bts.reshape(-1)
        flat_types[selected_positions] = predicted_types[selected]
        flat_tiles[selected_positions] = predicted_tiles[selected]
        flat_bts[selected_positions] = predicted_bts[selected]
        unresolved.reshape(-1)[selected_positions] = False
        revealed += len(selected_positions)

    if unresolved.any():
        raise RuntimeError("iterative decoding ended with unresolved cells")
    words = ((current_types << 12) | current_tiles).reshape(-1).cpu().tolist()
    bts = current_bts.reshape(-1).cpu().tolist()
    generated_indices = torch.nonzero(original_mask.reshape(-1), as_tuple=False).squeeze(1).cpu().tolist()
    changed_indices = [
        index
        for index in generated_indices
        if words[index] != room.layer1_words[index] or bts[index] != room.bts[index]
    ]
    return words, bts, generated_indices, changed_indices


def connectivity_metrics(resolved_types: list[int], width: int, height: int) -> dict[str, float | int]:
    passable = [value in PASSABLE_TYPES for value in resolved_types]
    seen = [False] * len(passable)
    component_sizes: list[int] = []
    for start, is_open in enumerate(passable):
        if not is_open or seen[start]:
            continue
        queue: deque[int] = deque([start])
        seen[start] = True
        size = 0
        while queue:
            current = queue.popleft()
            size += 1
            x, y = current % width, current // width
            for nx, ny in ((x, y - 1), (x, y + 1), (x - 1, y), (x + 1, y)):
                if nx < 0 or nx >= width or ny < 0 or ny >= height:
                    continue
                neighbor = ny * width + nx
                if passable[neighbor] and not seen[neighbor]:
                    seen[neighbor] = True
                    queue.append(neighbor)
        component_sizes.append(size)
    open_cells = sum(passable)
    largest = max(component_sizes, default=0)
    return {
        "openCellFraction": open_cells / len(passable),
        "passableComponents": len(component_sizes),
        "largestPassableComponentFraction": largest / open_cells if open_cells else 0.0,
    }


def passable_components(resolved_types: list[int], width: int, height: int) -> list[list[int]]:
    passable = [value in PASSABLE_TYPES for value in resolved_types]
    seen = [False] * len(passable)
    components: list[list[int]] = []
    for start, is_open in enumerate(passable):
        if not is_open or seen[start]:
            continue
        component: list[int] = []
        queue: deque[int] = deque([start])
        seen[start] = True
        while queue:
            current = queue.popleft()
            component.append(current)
            x, y = current % width, current // width
            for nx, ny in ((x, y - 1), (x, y + 1), (x - 1, y), (x + 1, y)):
                if nx < 0 or nx >= width or ny < 0 or ny >= height:
                    continue
                neighbor = ny * width + nx
                if passable[neighbor] and not seen[neighbor]:
                    seen[neighbor] = True
                    queue.append(neighbor)
        components.append(component)
    return components


def choose_air_word(room: RoomGrid, words: list[int], bts: list[int]) -> int:
    original = Counter(
        word
        for word, value in zip(room.layer1_words, room.bts)
        if (word >> 12) & 0xF == 0 and value == 0
    )
    if original:
        return original.most_common(1)[0][0]
    proposed = Counter(
        word for word, value in zip(words, bts) if (word >> 12) & 0xF == 0 and value == 0
    )
    return proposed.most_common(1)[0][0] if proposed else 0x00FF


def choose_solid_word(room: RoomGrid, words: list[int], bts: list[int]) -> int:
    original = Counter(
        word
        for word, value in zip(room.layer1_words, room.bts)
        if (word >> 12) & 0xF == 0x8 and value == 0
    )
    if original:
        return original.most_common(1)[0][0]
    proposed = Counter(
        word for word, value in zip(words, bts) if (word >> 12) & 0xF == 0x8 and value == 0
    )
    return proposed.most_common(1)[0][0] if proposed else 0x80FF


def smooth_generated_structure(
    room: RoomGrid,
    generated_words: list[int],
    generated_bts: list[int],
    generated_indices: list[int],
) -> tuple[list[int], list[int], int]:
    """Remove single-cell collision speckles without moving doors or room edges."""
    words = list(generated_words)
    bts = list(generated_bts)
    mutable = set(generated_indices)
    air_word = choose_air_word(room, words, bts)
    solid_word = choose_solid_word(room, words, bts)
    changed: set[int] = set()
    for _ in range(2):
        resolved = resolve_block_types(words, room.width, room.height)
        updates: list[tuple[int, int]] = []
        for index in mutable:
            x = index % room.width
            y = index // room.width
            if x == 0 or x == room.width - 1 or y == 0 or y == room.height - 1:
                continue
            if ((words[index] >> 12) & 0xF) == 0x9:
                continue
            is_open = resolved[index] in PASSABLE_TYPES
            same_category = 0
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    if dx == 0 and dy == 0:
                        continue
                    neighbor = (y + dy) * room.width + x + dx
                    if (resolved[neighbor] in PASSABLE_TYPES) == is_open:
                        same_category += 1
            if same_category <= 1:
                updates.append((index, solid_word if is_open else air_word))
        if not updates:
            break
        for index, replacement in updates:
            words[index] = replacement
            bts[index] = 0
            changed.add(index)
    return words, bts, len(changed)


def source_detail_grammar(
    room: RoomGrid,
) -> tuple[
    dict[tuple[int, int], Counter[int]],
    dict[int, Counter[int]],
    set[int],
    set[int],
]:
    resolved = resolve_block_types(room.layer1_words, room.width, room.height)
    details = [pack_detail_pair(word, value) for word, value in zip(room.layer1_words, room.bts)]
    by_context: dict[tuple[int, int], Counter[int]] = defaultdict(Counter)
    by_type: dict[int, Counter[int]] = defaultdict(Counter)
    horizontal: set[int] = set()
    vertical: set[int] = set()
    for index, (word, detail) in enumerate(zip(room.layer1_words, details)):
        block_type = (word >> 12) & 0xF
        context = neighbor_solid_mask(resolved, room.width, room.height, index)
        by_context[(block_type, context)][detail] += 1
        by_type[block_type][detail] += 1
        x = index % room.width
        y = index // room.width
        if x + 1 < room.width:
            horizontal.add(pack_detail_adjacency(detail, details[index + 1]))
        if y + 1 < room.height:
            vertical.add(pack_detail_adjacency(detail, details[index + room.width]))
    return by_context, by_type, horizontal, vertical


def dress_room_layout(
    room: RoomGrid,
    generated_words: list[int],
    generated_bts: list[int],
    vocabulary: GenerationVocabulary,
    mutable_indices: list[int],
) -> tuple[list[int], list[int], dict[str, int]]:
    """Retile collision output with context and adjacencies learned from real rooms."""
    words = list(generated_words)
    bts = list(generated_bts)
    mutable = set(mutable_indices)
    resolved = resolve_block_types(words, room.width, room.height)
    local_context, local_type, local_horizontal, local_vertical = source_detail_grammar(room)
    global_horizontal = vocabulary.detail_adjacencies(room.tileset, vertical=False)
    global_vertical = vocabulary.detail_adjacencies(room.tileset, vertical=True)
    completed = {
        index
        for index in range(room.cell_count)
        if index not in mutable or ((words[index] >> 12) & 0xF) == 0x9
    }
    retiled = 0
    context_matches = 0

    for index in range(room.cell_count):
        if index not in mutable or ((words[index] >> 12) & 0xF) == 0x9:
            continue
        block_type = (words[index] >> 12) & 0xF
        context = neighbor_solid_mask(resolved, room.width, room.height, index)
        local_exact = local_context.get((block_type, context))
        global_exact = vocabulary.detail_pair_frequencies_by_context.get(
            f"{room.tileset}:{block_type}:{context}"
        )
        counts: Counter[int] = Counter()
        if global_exact:
            counts.update({pair: count for pair, count in global_exact})
        if local_exact:
            counts.update({pair: count * 8 for pair, count in local_exact.items()})
        if counts:
            context_matches += 1
        else:
            counts.update(dict(vocabulary.ranked_detail_pairs(room.tileset, block_type)))
            counts.update({pair: count * 8 for pair, count in local_type.get(block_type, {}).items()})
        candidates = sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:32]
        if not candidates:
            completed.add(index)
            continue

        x = index % room.width
        y = index // room.width

        def candidate_score(item: tuple[int, int]) -> tuple[float, int, int]:
            detail, frequency = item
            score = math.log1p(frequency) * 1.4
            neighbors = (
                (index - 1, False, True, x > 0),
                (index + 1, False, False, x + 1 < room.width),
                (index - room.width, True, True, y > 0),
                (index + room.width, True, False, y + 1 < room.height),
            )
            for neighbor, vertical, neighbor_first, in_bounds in neighbors:
                if not in_bounds or neighbor not in completed:
                    continue
                neighbor_detail = pack_detail_pair(words[neighbor], bts[neighbor])
                adjacency = pack_detail_adjacency(
                    neighbor_detail if neighbor_first else detail,
                    detail if neighbor_first else neighbor_detail,
                )
                local = local_vertical if vertical else local_horizontal
                global_values = global_vertical if vertical else global_horizontal
                if adjacency in local:
                    score += 4.0
                score += 2.0 if adjacency in global_values else -1.0
            return score, frequency, -detail

        detail, _ = max(candidates, key=candidate_score)
        new_word = (block_type << 12) | (detail >> 8)
        new_bts = detail & 0xFF
        if words[index] != new_word or bts[index] != new_bts:
            words[index] = new_word
            bts[index] = new_bts
            retiled += 1
        completed.add(index)

    return words, bts, {
        "retiledCellCount": retiled,
        "contextMatchedCellCount": context_matches,
    }


def preserve_unchanged_structure_details(
    room: RoomGrid,
    generated_words: list[int],
    generated_bts: list[int],
    generated_indices: list[int],
) -> tuple[list[int], list[int], int]:
    """Keep source artwork when a remix did not change a cell's raw collision type."""
    words = list(generated_words)
    bts = list(generated_bts)
    restored = 0
    for index in generated_indices:
        if ((words[index] >> 12) & 0xF) != room.block_types[index]:
            continue
        if words[index] != room.layer1_words[index] or bts[index] != room.bts[index]:
            words[index] = room.layer1_words[index]
            bts[index] = room.bts[index]
            restored += 1
    return words, bts, restored


def door_group_bounds(group: dict[str, object], width: int) -> tuple[int, int, int, int]:
    cells = [int(value) for value in group["cellIndices"]]  # type: ignore[index]
    xs = [cell % width for cell in cells]
    ys = [cell // width for cell in cells]
    return min(xs), min(ys), max(xs), max(ys)


def usable_generated_door(group: dict[str, object], width: int) -> bool:
    cells = group["cellIndices"]
    if not isinstance(cells, list) or not 2 <= len(cells) <= 12:
        return False
    indices = [int(value) for value in cells]
    # The caller has the orientation and edge classification; span limits keep
    # a generated boundary wall from being mistaken for a usable door cluster.
    orientation = group["orientation"]
    edge = group["edge"]
    min_x, min_y, max_x, max_y = door_group_bounds(group, width)
    return (
        edge != "interior"
        and (
            (
                edge in ("left", "right")
                and orientation == "vertical"
                and max_x - min_x <= 1
                and max_y - min_y <= 7
            )
            or (
                edge in ("top", "bottom")
                and orientation == "horizontal"
                and max_y - min_y <= 1
                and max_x - min_x <= 7
            )
        )
    )


def carve_door_approach(
    group: dict[str, object],
    words: list[int],
    bts: list[int],
    width: int,
    height: int,
    air_word: int,
    protected_doors: set[int],
) -> None:
    min_x, min_y, max_x, max_y = door_group_bounds(group, width)
    edge = group["edge"]
    depth = 4
    if edge == "left":
        bounds = (max_x + 1, min_y - 1, max_x + depth, max_y + 1)
    elif edge == "right":
        bounds = (min_x - depth, min_y - 1, min_x - 1, max_y + 1)
    elif edge == "top":
        bounds = (min_x - 1, max_y + 1, max_x + 1, max_y + depth)
    elif edge == "bottom":
        bounds = (min_x - 1, min_y - depth, max_x + 1, min_y - 1)
    else:
        return
    x0, y0, x1, y1 = bounds
    for y in range(max(0, y0), min(height - 1, y1) + 1):
        for x in range(max(0, x0), min(width - 1, x1) + 1):
            index = y * width + x
            if index not in protected_doors and ((words[index] >> 12) & 0xF) != 0x9:
                words[index] = air_word
                bts[index] = 0


def connect_passable_regions(
    words: list[int],
    bts: list[int],
    width: int,
    height: int,
    air_word: int,
    protected_doors: set[int],
) -> int:
    changed: set[int] = set()
    # One iteration joins at least one component. Fully masked smoke models can
    # produce hundreds of tiny islands, so use a room-sized cap rather than
    # silently stopping with a mostly-connected layout.
    max_iterations = min(1024, max(64, width * height // 4))
    for _ in range(max_iterations):
        resolved = resolve_block_types(words, width, height)
        components = passable_components(resolved, width, height)
        if len(components) <= 1:
            break
        main = max(components, key=len)
        labels = [-1] * len(words)
        for component_index, component in enumerate(components):
            for cell in component:
                labels[cell] = component_index
        main_label = labels[main[0]]
        previous = [-2] * len(words)
        queue: deque[int] = deque()
        for cell in main:
            previous[cell] = -1
            queue.append(cell)
        target = -1
        while queue and target < 0:
            current = queue.popleft()
            x, y = current % width, current // width
            for nx, ny in ((x, y - 1), (x, y + 1), (x - 1, y), (x + 1, y)):
                if nx < 0 or nx >= width or ny < 0 or ny >= height:
                    continue
                neighbor = ny * width + nx
                if previous[neighbor] != -2:
                    continue
                # Do not route through the outside wall; boundary openings are
                # owned by door groups, not connectivity repair.
                if (nx == 0 or nx == width - 1 or ny == 0 or ny == height - 1) and labels[neighbor] < 0:
                    continue
                previous[neighbor] = current
                if labels[neighbor] >= 0 and labels[neighbor] != main_label:
                    target = neighbor
                    break
                queue.append(neighbor)
        if target < 0:
            break

        path: list[int] = []
        cursor = target
        while cursor >= 0:
            path.append(cursor)
            cursor = previous[cursor]
        for cell in path:
            x, y = cell % width, cell // width
            for dy in (0, 1):
                for dx in (0, 1):
                    nx, ny = x + dx, y + dy
                    if nx <= 0 or nx >= width - 1 or ny <= 0 or ny >= height - 1:
                        continue
                    index = ny * width + nx
                    if index in protected_doors or ((words[index] >> 12) & 0xF) == 0x9:
                        continue
                    if ((words[index] >> 12) & 0xF) not in PASSABLE_TYPES or bts[index] != 0:
                        words[index] = air_word
                        bts[index] = 0
                        changed.add(index)
    return len(changed)


def door_reachable_fraction(
    block_types: list[int], resolved_types: list[int], width: int, height: int
) -> float:
    doors = find_door_groups(block_types, width, height)
    if not doors:
        return 1.0
    components = passable_components(resolved_types, width, height)
    if not components:
        return 0.0
    main = set(max(components, key=len))
    reachable = sum(any(int(cell) in main for cell in group["cellIndices"]) for group in doors)
    return reachable / len(doors)


def structural_coherence_metrics(
    resolved_types: list[int], width: int, height: int
) -> dict[str, float | int]:
    passable = [value in PASSABLE_TYPES for value in resolved_types]
    transitions = 0
    edge_count = 0
    isolated = 0
    for index, is_open in enumerate(passable):
        x = index % width
        y = index // width
        if x + 1 < width:
            edge_count += 1
            transitions += passable[index + 1] != is_open
        if y + 1 < height:
            edge_count += 1
            transitions += passable[index + width] != is_open
        same_category = 0
        neighbor_count = 0
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dx == 0 and dy == 0:
                    continue
                nx = x + dx
                ny = y + dy
                if nx < 0 or nx >= width or ny < 0 or ny >= height:
                    continue
                neighbor_count += 1
                same_category += passable[ny * width + nx] == is_open
        if neighbor_count and same_category <= 1:
            isolated += 1

    solid_seen = [False] * len(passable)
    small_solid_cells = 0
    small_solid_components = 0
    for start, is_open in enumerate(passable):
        if is_open or solid_seen[start]:
            continue
        component: list[int] = []
        queue: deque[int] = deque([start])
        solid_seen[start] = True
        while queue:
            current = queue.popleft()
            component.append(current)
            x = current % width
            y = current // width
            for nx, ny in ((x, y - 1), (x, y + 1), (x - 1, y), (x + 1, y)):
                if nx < 0 or nx >= width or ny < 0 or ny >= height:
                    continue
                neighbor = ny * width + nx
                if not passable[neighbor] and not solid_seen[neighbor]:
                    solid_seen[neighbor] = True
                    queue.append(neighbor)
        if len(component) <= 4:
            small_solid_components += 1
            small_solid_cells += len(component)
    return {
        "passabilityTransitionFraction": transitions / edge_count if edge_count else 0.0,
        "isolatedCellFraction": isolated / len(passable) if passable else 0.0,
        "smallSolidIslandFraction": small_solid_cells / len(passable) if passable else 0.0,
        "smallSolidIslandCount": small_solid_components,
    }


def detail_adjacency_fraction(
    words: list[int] | tuple[int, ...],
    bts: list[int] | tuple[int, ...],
    width: int,
    height: int,
    horizontal_grammar: set[int],
    vertical_grammar: set[int],
    relevant_indices: set[int] | None = None,
) -> float:
    details = [pack_detail_pair(word, value) for word, value in zip(words, bts)]
    matching = 0
    considered = 0
    for index, detail in enumerate(details):
        x = index % width
        y = index // width
        if x + 1 < width and (
            relevant_indices is None or index in relevant_indices or index + 1 in relevant_indices
        ):
            considered += 1
            matching += pack_detail_adjacency(detail, details[index + 1]) in horizontal_grammar
        if y + 1 < height and (
            relevant_indices is None
            or index in relevant_indices
            or index + width in relevant_indices
        ):
            considered += 1
            matching += pack_detail_adjacency(detail, details[index + width]) in vertical_grammar
    return matching / considered if considered else 1.0


def repair_room_layout(
    room: RoomGrid,
    generated_words: list[int],
    generated_bts: list[int],
    preserve_existing_doors: bool,
) -> tuple[list[int], list[int], dict[str, int]]:
    words = list(generated_words)
    bts = list(generated_bts)
    before_words = list(words)
    before_bts = list(bts)
    air_word = choose_air_word(room, words, bts)
    original_doors = {
        index for index, word in enumerate(room.layer1_words) if ((word >> 12) & 0xF) == 0x9
    }
    if preserve_existing_doors:
        for index in original_doors:
            words[index] = room.layer1_words[index]
            bts[index] = room.bts[index]

    removed_groups = 0
    block_types = [(word >> 12) & 0xF for word in words]
    for group in find_door_groups(block_types, room.width, room.height):
        cells = {int(value) for value in group["cellIndices"]}
        if cells & original_doors:
            continue
        if not usable_generated_door(group, room.width):
            removed_groups += 1
            for index in cells:
                words[index] = air_word
                bts[index] = 0

    block_types = [(word >> 12) & 0xF for word in words]
    groups = find_door_groups(block_types, room.width, room.height)
    for group in groups:
        carve_door_approach(
            group, words, bts, room.width, room.height, air_word, original_doors
        )
    connectivity_carves = connect_passable_regions(
        words, bts, room.width, room.height, air_word, original_doors
    )
    repair_changes = sum(
        word != old_word or value != old_value
        for word, old_word, value, old_value in zip(words, before_words, bts, before_bts)
    )
    return words, bts, {
        "repairChangeCount": repair_changes,
        "connectivityCarveCount": connectivity_carves,
        "removedInvalidDoorGroups": removed_groups,
    }


def candidate_score(
    metrics: dict[str, float | int], changed_fraction: float, repair_fraction: float
) -> float:
    density = float(metrics["openCellFraction"])
    target_density = float(metrics.get("targetOpenCellFraction", 0.5))
    density_quality = max(0.0, 1.0 - abs(density - target_density) / 0.5)
    novelty = min(1.0, changed_fraction / 0.45)
    score = (
        float(metrics["largestPassableComponentFraction"]) * 20.0
        + (8.0 if int(metrics["passableComponents"]) <= 1 else 0.0)
        + float(metrics["doorReachableFraction"]) * 20.0
        + density_quality * 20.0
        + novelty * 3.0
        + float(metrics.get("detailAdjacencyRetention", 1.0)) ** 2 * 24.0
        + float(metrics.get("structureCoherence", 1.0)) ** 2 * 5.0
        - repair_fraction * 30.0
        - min(5, int(metrics["removedInvalidDoorGroups"])) * 4.0
    )
    return round(max(0.0, min(100.0, score)), 3)


def build_proposal(
    room: RoomGrid,
    vocabulary: GenerationVocabulary,
    checkpoint: dict[str, object],
    model_sha256: str,
    args: argparse.Namespace,
    seed: int,
    candidate_index: int,
    words: list[int],
    bts: list[int],
    generated_indices: list[int],
    repair: dict[str, int],
) -> dict[str, object]:
    block_types = [(word >> 12) & 0xF for word in words]
    resolved_types = resolve_block_types(words, room.width, room.height)
    door_groups = find_door_groups(block_types, room.width, room.height)
    changed_indices = [
        index
        for index in range(room.cell_count)
        if words[index] != room.layer1_words[index] or bts[index] != room.bts[index]
    ]
    candidate_structure = structural_coherence_metrics(resolved_types, room.width, room.height)
    source_connectivity = connectivity_metrics(
        list(room.resolved_block_types), room.width, room.height
    )
    source_structure = structural_coherence_metrics(
        list(room.resolved_block_types), room.width, room.height
    )
    transition_excess = max(
        0.0,
        float(candidate_structure["passabilityTransitionFraction"])
        - float(source_structure["passabilityTransitionFraction"]),
    )
    isolated_excess = max(
        0.0,
        float(candidate_structure["isolatedCellFraction"])
        - float(source_structure["isolatedCellFraction"]),
    )
    island_excess = max(
        0.0,
        float(candidate_structure["smallSolidIslandFraction"])
        - float(source_structure["smallSolidIslandFraction"]),
    )
    structure_coherence = max(
        0.0,
        1.0 - min(1.0, transition_excess / 0.08 + isolated_excess / 0.03 + island_excess / 0.025),
    )
    _, _, source_horizontal, source_vertical = source_detail_grammar(room)
    horizontal_grammar = vocabulary.detail_adjacencies(room.tileset, vertical=False) | source_horizontal
    vertical_grammar = vocabulary.detail_adjacencies(room.tileset, vertical=True) | source_vertical
    relevant = set(changed_indices)
    detail_fraction = detail_adjacency_fraction(
        words,
        bts,
        room.width,
        room.height,
        horizontal_grammar,
        vertical_grammar,
        relevant,
    )
    source_detail_fraction = detail_adjacency_fraction(
        room.layer1_words,
        room.bts,
        room.width,
        room.height,
        horizontal_grammar,
        vertical_grammar,
        relevant,
    )
    detail_retention = min(
        1.0,
        detail_fraction / source_detail_fraction if source_detail_fraction > 0.0 else 1.0,
    )
    metrics: dict[str, float | int] = {
        **connectivity_metrics(resolved_types, room.width, room.height),
        "targetOpenCellFraction": args.target_open_fraction
        if args.target_open_fraction is not None
        else float(source_connectivity["openCellFraction"]),
        **candidate_structure,
        "structureCoherence": structure_coherence,
        "detailAdjacencyFraction": detail_fraction,
        "sourceDetailAdjacencyFraction": source_detail_fraction,
        "detailAdjacencyRetention": detail_retention,
        "doorGroupCount": len(door_groups),
        "doorReachableFraction": door_reachable_fraction(
            block_types, resolved_types, room.width, room.height
        ),
        **repair,
    }
    metrics["score"] = candidate_score(
        metrics,
        changed_fraction=len(changed_indices) / room.cell_count,
        repair_fraction=int(repair["repairChangeCount"]) / room.cell_count,
    )
    return {
        "schemaVersion": 1,
        "kind": "smedit-room-proposal",
        "generator": {
            "modelFormat": checkpoint["format"],
            "modelFormatVersion": checkpoint["formatVersion"],
            "modelSha256": model_sha256,
            "mode": args.mode,
            "strength": args.strength,
            "seed": seed,
            "baseSeed": args.seed,
            "candidateIndex": candidate_index,
            "steps": args.steps,
            "temperature": args.temperature,
            "preservedExistingDoors": args.preserve_existing_doors,
            "connectivityRepair": args.repair_connectivity,
            "structureSmoothing": args.smooth_structure,
            "contextRetiling": args.retile,
            "allowedNewDoors": args.allow_new_doors,
            "targetOpenFraction": args.target_open_fraction,
        },
        "source": {
            "roomId": room.room_id,
            "roomIdHex": room.room_id_hex,
            "handle": room.handle,
            "name": room.name,
            "contentHash": room.content_hash,
        },
        "area": room.area,
        "areaName": room.area_name,
        "tileset": room.tileset,
        "widthScreens": room.width_screens,
        "heightScreens": room.height_screens,
        "widthBlocks": room.width,
        "heightBlocks": room.height,
        "layer1Words": words,
        "blockTypes": block_types,
        "resolvedBlockTypes": resolved_types,
        "bts": bts,
        "generatedCellIndices": generated_indices,
        "changedCellIndices": changed_indices,
        "doorGroups": door_groups,
        "metrics": metrics,
    }


def load_model(
    checkpoint_path: str | Path, device: torch.device
) -> tuple[RoomDenoiser, GenerationVocabulary, dict[str, object]]:
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    if checkpoint.get("format") != MODEL_FORMAT or checkpoint.get("formatVersion") != MODEL_FORMAT_VERSION:
        raise ValueError(f"unsupported model checkpoint: {checkpoint_path}")
    config = RoomModelConfig.from_dict(checkpoint["modelConfig"])
    model = RoomDenoiser(config)
    model.load_state_dict(checkpoint["modelState"])
    model.to(device)
    return model, GenerationVocabulary.from_dict(checkpoint["vocabulary"]), checkpoint


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Generate a lossless SMEDIT room-layout proposal")
    parser.add_argument("--model", required=True)
    parser.add_argument("--template", required=True, help="one room JSON from CLI training-data")
    parser.add_argument("--output", required=True)
    parser.add_argument("--mode", choices=("remix", "new"), default="remix")
    parser.add_argument("--strength", type=float, default=0.25, help="remix mask fraction, from 0 to 1")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--steps", type=int, default=8)
    parser.add_argument("--temperature", type=float, default=0.55)
    parser.add_argument("--device", default="auto")
    parser.add_argument(
        "--candidates",
        type=int,
        default=1,
        help="generate and rank this many candidates in one JSON bundle",
    )
    parser.add_argument(
        "--repair-connectivity",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="repair disconnected regions and malformed generated doors before ranking",
    )
    parser.add_argument(
        "--preserve-existing-doors",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="keep existing type-9 cells fixed while still allowing additional generated doors",
    )
    parser.add_argument(
        "--smooth-structure",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="remove isolated generated collision speckles before connectivity repair",
    )
    parser.add_argument(
        "--retile",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="dress generated collision with neighboring metatile patterns learned from real rooms",
    )
    parser.add_argument(
        "--allow-new-doors",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="allow type-9 door placeholders beyond the source room's existing doors",
    )
    parser.add_argument(
        "--target-open-fraction",
        type=float,
        default=None,
        help="new-room passable fraction; defaults to the template room's fraction",
    )
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    if not 0.0 <= args.strength <= 1.0:
        raise SystemExit("strength must be in [0, 1]")
    if args.steps <= 0:
        raise SystemExit("steps must be positive")
    if args.temperature < 0:
        raise SystemExit("temperature cannot be negative")
    if args.candidates <= 0 or args.candidates > 32:
        raise SystemExit("candidates must be in [1, 32]")
    if args.target_open_fraction is not None and not 0.05 <= args.target_open_fraction <= 0.95:
        raise SystemExit("target-open-fraction must be in [0.05, 0.95]")
    room = RoomGrid.load(args.template)
    if args.mode == "new" and args.target_open_fraction is None:
        args.target_open_fraction = float(
            connectivity_metrics(list(room.resolved_block_types), room.width, room.height)[
                "openCellFraction"
            ]
        )
    device = choose_device(args.device)
    model, vocabulary, checkpoint = load_model(args.model, device)
    model_sha256 = file_sha256(args.model)
    proposals: list[dict[str, object]] = []
    for candidate_index in range(args.candidates):
        candidate_seed = args.seed + candidate_index * 1_000_003
        words, bts, generated_indices, _ = generate_room(
            model=model,
            vocabulary=vocabulary,
            room=room,
            device=device,
            mode=args.mode,
            strength=args.strength,
            seed=candidate_seed,
            steps=args.steps,
            temperature=args.temperature,
            preserve_existing_doors=args.preserve_existing_doors,
            allow_new_doors=args.allow_new_doors,
            target_open_fraction=args.target_open_fraction if args.mode == "new" else None,
        )
        if args.smooth_structure:
            words, bts, smoothing_changes = smooth_generated_structure(
                room, words, bts, generated_indices
            )
        else:
            smoothing_changes = 0
        if args.repair_connectivity:
            words, bts, repair = repair_room_layout(
                room, words, bts, args.preserve_existing_doors
            )
        else:
            repair = {
                "repairChangeCount": 0,
                "connectivityCarveCount": 0,
                "removedInvalidDoorGroups": 0,
            }
        repair["structureSmoothingChangeCount"] = smoothing_changes
        if args.retile:
            if args.mode == "remix":
                words, bts, preserved_detail_cells = preserve_unchanged_structure_details(
                    room, words, bts, generated_indices
                )
                mutable_indices = [
                    index
                    for index in range(room.cell_count)
                    if ((words[index] >> 12) & 0xF) != room.block_types[index]
                ]
            else:
                preserved_detail_cells = 0
                mutable_indices = list(range(room.cell_count))
            words, bts, dressing = dress_room_layout(
                room, words, bts, vocabulary, mutable_indices
            )
        else:
            preserved_detail_cells = 0
            dressing = {"retiledCellCount": 0, "contextMatchedCellCount": 0}
        repair["preservedDetailCellCount"] = preserved_detail_cells
        repair.update(dressing)
        proposals.append(
            build_proposal(
                room=room,
                vocabulary=vocabulary,
                checkpoint=checkpoint,
                model_sha256=model_sha256,
                args=args,
                seed=candidate_seed,
                candidate_index=candidate_index,
                words=words,
                bts=bts,
                generated_indices=generated_indices,
                repair=repair,
            )
        )

    proposals.sort(
        key=lambda value: (
            -float(value["metrics"]["score"]),  # type: ignore[index]
            int(value["generator"]["seed"]),  # type: ignore[index]
        )
    )
    for rank, proposal in enumerate(proposals, start=1):
        proposal["rank"] = rank

    document: dict[str, object]
    if len(proposals) == 1:
        document = proposals[0]
    else:
        document = {
            "schemaVersion": 1,
            "kind": "smedit-room-proposal-bundle",
            "source": proposals[0]["source"],
            "candidateCount": len(proposals),
            "candidates": proposals,
        }
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(document, stream, indent=2)
        stream.write("\n")
    temporary.replace(output)
    best = proposals[0]
    best_metrics = best["metrics"]
    print(
        json.dumps(
            {
                "proposal": str(output),
                "candidates": len(proposals),
                "bestSeed": best["generator"]["seed"],  # type: ignore[index]
                "bestScore": best_metrics["score"],  # type: ignore[index]
                "changedCells": len(best["changedCellIndices"]),  # type: ignore[arg-type]
                **best_metrics,  # type: ignore[arg-type]
            }
        )
    )


if __name__ == "__main__":
    main()
