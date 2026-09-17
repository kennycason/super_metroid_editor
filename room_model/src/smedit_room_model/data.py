from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any, Sequence

import numpy as np
import torch
from torch.utils.data import Dataset

from .format import RoomGrid, load_rooms, resolve_block_types

BLOCK_TYPE_COUNT = 16
TILE_BITS_COUNT = 4096
BTS_COUNT = 256
BLOCK_MASK_TOKEN = BLOCK_TYPE_COUNT
TILE_MASK_TOKEN = TILE_BITS_COUNT
BTS_MASK_TOKEN = BTS_COUNT
TILESET_COUNT = 256
AREA_COUNT = 16
DETAIL_PAIR_BITS = 20
PASSABLE_TYPES = {0x0, 0x2, 0x4, 0x7, 0x9, 0xB, 0xC, 0xF}


def neighbor_solid_mask(
    resolved_types: Sequence[int], width: int, height: int, index: int
) -> int:
    """Encode the eight neighboring collision categories around one cell."""
    x = index % width
    y = index // width
    result = 0
    for bit, (dx, dy) in enumerate(
        ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1))
    ):
        nx = x + dx
        ny = y + dy
        solid = (
            nx < 0
            or nx >= width
            or ny < 0
            or ny >= height
            or resolved_types[ny * width + nx] not in PASSABLE_TYPES
        )
        if solid:
            result |= 1 << bit
    return result


def pack_detail_pair(word: int, bts: int) -> int:
    return ((word & 0xFFF) << 8) | (bts & 0xFF)


def pack_detail_adjacency(first: int, second: int) -> int:
    return (first << DETAIL_PAIR_BITS) | second


@dataclass(frozen=True)
class GenerationVocabulary:
    block_types_by_tileset: dict[str, list[int]]
    tile_bits_by_tileset: dict[str, list[int]]
    tile_bits_by_tileset_and_type: dict[str, list[int]]
    bts_by_block_type: dict[str, list[int]]
    detail_pairs_by_tileset_and_type: dict[str, list[int]]
    detail_pair_frequencies_by_tileset_and_type: dict[str, list[list[int]]]
    detail_pair_frequencies_by_context: dict[str, list[list[int]]]
    horizontal_detail_adjacencies_by_tileset: dict[str, list[int]]
    vertical_detail_adjacencies_by_tileset: dict[str, list[int]]

    def to_dict(self) -> dict[str, Any]:
        return {
            "blockTypesByTileset": self.block_types_by_tileset,
            "tileBitsByTileset": self.tile_bits_by_tileset,
            "tileBitsByTilesetAndType": self.tile_bits_by_tileset_and_type,
            "btsByBlockType": self.bts_by_block_type,
            "detailPairsByTilesetAndType": self.detail_pairs_by_tileset_and_type,
            "detailPairFrequenciesByTilesetAndType": self.detail_pair_frequencies_by_tileset_and_type,
            "detailPairFrequenciesByContext": self.detail_pair_frequencies_by_context,
            "horizontalDetailAdjacenciesByTileset": self.horizontal_detail_adjacencies_by_tileset,
            "verticalDetailAdjacenciesByTileset": self.vertical_detail_adjacencies_by_tileset,
        }

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "GenerationVocabulary":
        def lists(name: str) -> dict[str, list[int]]:
            raw = value.get(name, {})
            return {str(key): [int(item) for item in items] for key, items in raw.items()}

        def counted_lists(name: str) -> dict[str, list[list[int]]]:
            raw = value.get(name, {})
            return {
                str(key): [[int(item[0]), int(item[1])] for item in items]
                for key, items in raw.items()
            }

        return cls(
            block_types_by_tileset=lists("blockTypesByTileset"),
            tile_bits_by_tileset=lists("tileBitsByTileset"),
            tile_bits_by_tileset_and_type=lists("tileBitsByTilesetAndType"),
            bts_by_block_type=lists("btsByBlockType"),
            detail_pairs_by_tileset_and_type=lists("detailPairsByTilesetAndType"),
            detail_pair_frequencies_by_tileset_and_type=counted_lists(
                "detailPairFrequenciesByTilesetAndType"
            ),
            detail_pair_frequencies_by_context=counted_lists(
                "detailPairFrequenciesByContext"
            ),
            horizontal_detail_adjacencies_by_tileset=lists(
                "horizontalDetailAdjacenciesByTileset"
            ),
            vertical_detail_adjacencies_by_tileset=lists(
                "verticalDetailAdjacenciesByTileset"
            ),
        )

    def allowed_block_types(self, tileset: int) -> list[int]:
        return self.block_types_by_tileset.get(str(tileset), list(range(BLOCK_TYPE_COUNT)))

    def allowed_tile_bits(self, tileset: int, block_type: int) -> list[int]:
        exact = self.tile_bits_by_tileset_and_type.get(f"{tileset}:{block_type}")
        if exact:
            return exact
        return self.tile_bits_by_tileset.get(str(tileset), list(range(TILE_BITS_COUNT)))

    def allowed_bts(self, block_type: int) -> list[int]:
        return self.bts_by_block_type.get(str(block_type), [0])

    def allowed_detail_pairs(self, tileset: int, block_type: int) -> list[int]:
        pairs = self.detail_pairs_by_tileset_and_type.get(f"{tileset}:{block_type}")
        if pairs:
            return pairs
        return [
            (tile_bits << 8) | bts
            for tile_bits in self.allowed_tile_bits(tileset, block_type)
            for bts in self.allowed_bts(block_type)
        ]

    def ranked_detail_pairs(
        self, tileset: int, block_type: int, neighbor_mask: int | None = None
    ) -> list[tuple[int, int]]:
        if neighbor_mask is not None:
            exact = self.detail_pair_frequencies_by_context.get(
                f"{tileset}:{block_type}:{neighbor_mask}"
            )
            if exact:
                return [(pair, count) for pair, count in exact]
        fallback = self.detail_pair_frequencies_by_tileset_and_type.get(
            f"{tileset}:{block_type}"
        )
        if fallback:
            return [(pair, count) for pair, count in fallback]
        return [(pair, 1) for pair in self.allowed_detail_pairs(tileset, block_type)]

    def detail_adjacencies(self, tileset: int, vertical: bool) -> set[int]:
        source = (
            self.vertical_detail_adjacencies_by_tileset
            if vertical
            else self.horizontal_detail_adjacencies_by_tileset
        )
        return set(source.get(str(tileset), []))


def build_vocabulary(rooms: Sequence[RoomGrid]) -> GenerationVocabulary:
    types_by_tileset: dict[int, set[int]] = {}
    tiles_by_tileset: dict[int, set[int]] = {}
    tiles_by_key: dict[tuple[int, int], set[int]] = {}
    bts_by_type: dict[int, set[int]] = {}
    pairs_by_key: dict[tuple[int, int], set[int]] = {}
    pair_counts_by_key: dict[tuple[int, int], Counter[int]] = {}
    pair_counts_by_context: dict[tuple[int, int, int], Counter[int]] = {}
    horizontal_adjacencies: dict[int, set[int]] = {}
    vertical_adjacencies: dict[int, set[int]] = {}
    for room in rooms:
        types_by_tileset.setdefault(room.tileset, set()).update(room.block_types)
        room_tiles = tiles_by_tileset.setdefault(room.tileset, set())
        resolved_types = resolve_block_types(room.layer1_words, room.width, room.height)
        details = [pack_detail_pair(word, bts) for word, bts in zip(room.layer1_words, room.bts)]
        for index, (word, block_type, bts) in enumerate(
            zip(room.layer1_words, room.block_types, room.bts)
        ):
            tile_bits = word & 0xFFF
            detail_pair = details[index]
            room_tiles.add(tile_bits)
            tiles_by_key.setdefault((room.tileset, block_type), set()).add(tile_bits)
            bts_by_type.setdefault(block_type, set()).add(bts)
            pairs_by_key.setdefault((room.tileset, block_type), set()).add(detail_pair)
            pair_counts_by_key.setdefault((room.tileset, block_type), Counter())[detail_pair] += 1
            context = neighbor_solid_mask(resolved_types, room.width, room.height, index)
            pair_counts_by_context.setdefault(
                (room.tileset, block_type, context), Counter()
            )[detail_pair] += 1
            x = index % room.width
            y = index // room.width
            if x + 1 < room.width:
                horizontal_adjacencies.setdefault(room.tileset, set()).add(
                    pack_detail_adjacency(detail_pair, details[index + 1])
                )
            if y + 1 < room.height:
                vertical_adjacencies.setdefault(room.tileset, set()).add(
                    pack_detail_adjacency(detail_pair, details[index + room.width])
                )

    def ranked_counts(values: Counter[int]) -> list[list[int]]:
        return [[pair, count] for pair, count in sorted(values.items(), key=lambda item: (-item[1], item[0]))]

    return GenerationVocabulary(
        block_types_by_tileset={str(key): sorted(values) for key, values in types_by_tileset.items()},
        tile_bits_by_tileset={str(key): sorted(values) for key, values in tiles_by_tileset.items()},
        tile_bits_by_tileset_and_type={
            f"{tileset}:{block_type}": sorted(values)
            for (tileset, block_type), values in tiles_by_key.items()
        },
        bts_by_block_type={str(key): sorted(values) for key, values in bts_by_type.items()},
        detail_pairs_by_tileset_and_type={
            f"{tileset}:{block_type}": sorted(values)
            for (tileset, block_type), values in pairs_by_key.items()
        },
        detail_pair_frequencies_by_tileset_and_type={
            f"{tileset}:{block_type}": ranked_counts(values)
            for (tileset, block_type), values in pair_counts_by_key.items()
        },
        detail_pair_frequencies_by_context={
            f"{tileset}:{block_type}:{context}": ranked_counts(values)
            for (tileset, block_type, context), values in pair_counts_by_context.items()
        },
        horizontal_detail_adjacencies_by_tileset={
            str(tileset): sorted(values) for tileset, values in horizontal_adjacencies.items()
        },
        vertical_detail_adjacencies_by_tileset={
            str(tileset): sorted(values) for tileset, values in vertical_adjacencies.items()
        },
    )


def load_and_split_rooms(
    dataset_dir: str,
    validation_fraction: float,
    seed: int,
) -> tuple[list[RoomGrid], list[RoomGrid], dict[str, Any]]:
    rooms, manifest = load_rooms(dataset_dir)
    groups: dict[str, list[RoomGrid]] = {}
    for room in rooms:
        groups.setdefault(room.content_hash, []).append(room)
    keys = sorted(groups)
    rng = np.random.default_rng(seed)
    rng.shuffle(keys)
    if len(keys) < 2 or validation_fraction <= 0:
        return rooms, [], manifest
    validation_groups = max(1, round(len(keys) * validation_fraction))
    validation_groups = min(validation_groups, len(keys) - 1)
    validation_keys = set(keys[:validation_groups])
    train = [room for room in rooms if room.content_hash not in validation_keys]
    validation = [room for room in rooms if room.content_hash in validation_keys]
    return train, validation, manifest


class RoomCropDataset(Dataset[dict[str, torch.Tensor]]):
    """Deterministic-per-epoch random masked crops from complete room grids."""

    def __init__(
        self,
        rooms: Sequence[RoomGrid],
        crop_size: int = 32,
        samples_per_epoch: int = 4096,
        seed: int = 0,
    ) -> None:
        if not rooms:
            raise ValueError("RoomCropDataset requires at least one room")
        if crop_size < 16:
            raise ValueError("crop_size must be at least 16 blocks")
        self.rooms = list(rooms)
        self.crop_size = crop_size
        self.samples_per_epoch = samples_per_epoch
        self.seed = seed
        self.epoch = 0

    def set_epoch(self, epoch: int) -> None:
        self.epoch = epoch

    def __len__(self) -> int:
        return self.samples_per_epoch

    def __getitem__(self, index: int) -> dict[str, torch.Tensor]:
        mixed_seed = (self.seed + self.epoch * 1_000_003 + index * 97_409) & 0xFFFFFFFFFFFFFFFF
        rng = np.random.default_rng(mixed_seed)
        room = self.rooms[int(rng.integers(0, len(self.rooms)))]
        return self._crop(room, rng)

    def _crop(self, room: RoomGrid, rng: np.random.Generator) -> dict[str, torch.Tensor]:
        size = self.crop_size
        source_words = np.asarray(room.layer1_words, dtype=np.int64).reshape(room.height, room.width)
        source_types = np.asarray(room.block_types, dtype=np.int64).reshape(room.height, room.width)
        source_bts = np.asarray(room.bts, dtype=np.int64).reshape(room.height, room.width)

        crop_width = min(size, room.width)
        crop_height = min(size, room.height)
        door_cells = np.flatnonzero(source_types.reshape(-1) == 0x9)
        if door_cells.size and rng.random() < 0.4:
            door = int(door_cells[int(rng.integers(0, door_cells.size))])
            center_x = door % room.width + int(rng.integers(-6, 7))
            center_y = door // room.width + int(rng.integers(-6, 7))
            source_x = int(np.clip(center_x - crop_width // 2, 0, room.width - crop_width))
            source_y = int(np.clip(center_y - crop_height // 2, 0, room.height - crop_height))
        else:
            source_x = int(rng.integers(0, room.width - crop_width + 1))
            source_y = int(rng.integers(0, room.height - crop_height + 1))

        target_x = (size - crop_width) // 2
        target_y = (size - crop_height) // 2
        words = np.zeros((size, size), dtype=np.int64)
        block_types = np.zeros((size, size), dtype=np.int64)
        bts = np.zeros((size, size), dtype=np.int64)
        valid = np.zeros((size, size), dtype=np.bool_)
        destination = np.s_[target_y : target_y + crop_height, target_x : target_x + crop_width]
        source = np.s_[source_y : source_y + crop_height, source_x : source_x + crop_width]
        words[destination] = source_words[source]
        block_types[destination] = source_types[source]
        bts[destination] = source_bts[source]
        valid[destination] = True
        tile_bits = words & 0xFFF

        mask = self._make_mask(valid, rng)
        input_types = block_types.copy()
        input_tiles = tile_bits.copy()
        input_bts = bts.copy()
        input_types[mask] = BLOCK_MASK_TOKEN
        input_tiles[mask] = TILE_MASK_TOKEN
        input_bts[mask] = BTS_MASK_TOKEN

        yy, xx = np.mgrid[0:size, 0:size]
        absolute_x = source_x + xx - target_x
        absolute_y = source_y + yy - target_y
        x_position = absolute_x / max(room.width - 1, 1)
        y_position = absolute_y / max(room.height - 1, 1)
        coordinates = np.stack(
            (
                np.clip(x_position, 0.0, 1.0),
                np.clip(y_position, 0.0, 1.0),
                np.full_like(x_position, min(room.width / 160.0, 2.0)),
                np.full_like(y_position, min(room.height / 160.0, 2.0)),
            ),
            axis=0,
        ).astype(np.float32)
        coordinates[:, ~valid] = 0.0

        return {
            "input_block_types": torch.from_numpy(input_types),
            "input_tile_bits": torch.from_numpy(input_tiles),
            "input_bts": torch.from_numpy(input_bts),
            "target_block_types": torch.from_numpy(block_types),
            "target_tile_bits": torch.from_numpy(tile_bits),
            "target_bts": torch.from_numpy(bts),
            "tileset": torch.tensor(room.tileset, dtype=torch.long),
            "area": torch.tensor(room.area, dtype=torch.long),
            "coordinates": torch.from_numpy(coordinates),
            "mask": torch.from_numpy(mask),
            "valid": torch.from_numpy(valid),
        }

    @staticmethod
    def _make_mask(valid: np.ndarray, rng: np.random.Generator) -> np.ndarray:
        if rng.random() < 0.15:
            return valid.copy()
        ratio = float(rng.uniform(0.12, 0.72))
        if rng.random() < 0.2:
            # A minority of scattered masks retain single-cell denoising skill.
            mask = (rng.random(valid.shape) < ratio) & valid
        else:
            # Generation edits contiguous room regions, so most training masks
            # should have the same spatial character instead of salt-and-pepper noise.
            mask = np.zeros_like(valid)
            target = round(int(valid.sum()) * ratio)
            height, width = valid.shape
            attempts = 0
            while int(mask.sum()) < target and attempts < 48:
                attempts += 1
                rect_width = int(rng.integers(4, max(5, width * 3 // 4 + 1)))
                rect_height = int(rng.integers(4, max(5, height * 3 // 4 + 1)))
                x = int(rng.integers(0, max(1, width - rect_width + 1)))
                y = int(rng.integers(0, max(1, height - rect_height + 1)))
                available = [
                    (py, px)
                    for py in range(y, y + rect_height)
                    for px in range(x, x + rect_width)
                    if valid[py, px] and not mask[py, px]
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
                remaining = np.flatnonzero(valid & ~mask)
                rng.shuffle(remaining)
                mask.reshape(-1)[remaining[: target - int(mask.sum())]] = True
        if not mask.any():
            candidates = np.flatnonzero(valid)
            mask.reshape(-1)[int(candidates[int(rng.integers(0, len(candidates)))])] = True
        return mask
