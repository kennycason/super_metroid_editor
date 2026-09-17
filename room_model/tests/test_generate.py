import unittest

import torch

from smedit_room_model.data import build_vocabulary
from smedit_room_model.format import RoomGrid, resolve_block_types
from smedit_room_model.generate import (
    candidate_score,
    calibrate_open_fraction,
    connectivity_metrics,
    detail_adjacency_fraction,
    dress_room_layout,
    initial_generation_mask,
    preserve_unchanged_structure_details,
    repair_room_layout,
    smooth_generated_structure,
    source_detail_grammar,
    usable_generated_door,
)


class RoomGenerationRepairTest(unittest.TestCase):
    width = 32
    height = 16
    air = 0x00FF
    solid = 0x8120

    def room(self) -> RoomGrid:
        words = [self.air] * (self.width * self.height)
        bts = [0] * len(words)
        for y in range(self.height):
            for x in range(self.width):
                if x <= 1 or x >= self.width - 2 or y <= 1 or y >= self.height - 2:
                    words[y * self.width + x] = self.solid
        for y in range(6, 10):
            words[y * self.width + 1] = 0x9040
        block_types = tuple((word >> 12) & 0xF for word in words)
        return RoomGrid(
            room_id=0x91F8,
            room_id_hex="0x91F8",
            handle="landingSite",
            name="Landing Site",
            area=0,
            area_name="Crateria",
            tileset=8,
            width_screens=2,
            height_screens=1,
            width=self.width,
            height=self.height,
            content_hash="test",
            layer1_words=tuple(words),
            block_types=block_types,
            resolved_block_types=block_types,
            bts=tuple(bts),
        )

    def test_repair_removes_interior_doors_and_connects_regions(self) -> None:
        room = self.room()
        words = list(room.layer1_words)
        bts = list(room.bts)
        for y in range(2, self.height - 2):
            words[y * self.width + 16] = self.solid
        words[6 * self.width + 12] = 0x9040
        words[7 * self.width + 12] = 0x9040

        repaired_words, repaired_bts, repair = repair_room_layout(room, words, bts, True)
        resolved = resolve_block_types(repaired_words, self.width, self.height)
        metrics = connectivity_metrics(resolved, self.width, self.height)

        self.assertEqual(1, repair["removedInvalidDoorGroups"])
        self.assertNotEqual(0x9, repaired_words[6 * self.width + 12] >> 12)
        self.assertEqual(1, metrics["passableComponents"])
        self.assertTrue(all(value in range(256) for value in repaired_bts))

    def test_repair_keeps_existing_door_cells_exact(self) -> None:
        room = self.room()
        words = [self.solid] * room.cell_count
        bts = [0x7F] * room.cell_count

        repaired_words, repaired_bts, _ = repair_room_layout(room, words, bts, True)

        for index, block_type in enumerate(room.block_types):
            if block_type == 0x9:
                self.assertEqual(room.layer1_words[index], repaired_words[index])
                self.assertEqual(room.bts[index], repaired_bts[index])

    def test_score_rewards_connected_door_reachable_candidates(self) -> None:
        weak = {
            "openCellFraction": 0.5,
            "passableComponents": 3,
            "largestPassableComponentFraction": 0.6,
            "doorReachableFraction": 0.5,
            "removedInvalidDoorGroups": 0,
        }
        strong = dict(weak)
        strong.update(
            passableComponents=1,
            largestPassableComponentFraction=1.0,
            doorReachableFraction=1.0,
        )

        self.assertGreater(candidate_score(strong, 0.5, 0.01), candidate_score(weak, 0.5, 0.01))

    def test_score_rewards_learned_detail_adjacency(self) -> None:
        noisy = {
            "openCellFraction": 0.5,
            "passableComponents": 1,
            "largestPassableComponentFraction": 1.0,
            "doorReachableFraction": 1.0,
            "removedInvalidDoorGroups": 0,
            "detailAdjacencyRetention": 0.4,
            "structureCoherence": 1.0,
        }
        coherent = dict(noisy, detailAdjacencyRetention=0.95)

        self.assertGreater(
            candidate_score(coherent, 0.3, 0.01),
            candidate_score(noisy, 0.3, 0.01),
        )

    def test_new_room_logits_are_calibrated_to_requested_open_fraction(self) -> None:
        logits = torch.zeros((64, 16), dtype=torch.float32)
        calibrated = calibrate_open_fraction(logits, list(range(16)), 0.7, 0.6)
        probabilities = (calibrated / 0.6).softmax(dim=1)
        passable = [0x0, 0x2, 0x4, 0x7, 0x9, 0xB, 0xC, 0xF]

        self.assertAlmostEqual(0.7, float(probabilities[:, passable].sum(dim=1).mean()), places=5)

    def test_remix_mask_uses_contiguous_regions(self) -> None:
        room = self.room()
        mask = initial_generation_mask(room, "remix", 0.25, True, 1337)
        isolated = 0
        for index in range(room.cell_count):
            if not mask.reshape(-1)[index]:
                continue
            x = index % room.width
            y = index // room.width
            neighbors = [
                mask[ny, nx]
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
                if 0 <= nx < room.width and 0 <= ny < room.height
            ]
            isolated += not any(neighbors)

        self.assertGreaterEqual(int(mask.sum()), round(room.cell_count * 0.25) - 4)
        self.assertEqual(0, isolated)

    def test_smoothing_removes_generated_collision_speckle(self) -> None:
        room = self.room()
        words = list(room.layer1_words)
        bts = list(room.bts)
        speckle = 8 * self.width + 10
        words[speckle] = self.solid

        smoothed_words, smoothed_bts, changed = smooth_generated_structure(
            room, words, bts, [speckle]
        )

        self.assertEqual(1, changed)
        self.assertEqual(0, smoothed_words[speckle] >> 12)
        self.assertEqual(0, smoothed_bts[speckle])

    def test_remix_preserves_art_when_collision_type_is_unchanged(self) -> None:
        room = self.room()
        words = list(room.layer1_words)
        bts = list(room.bts)
        same_type = 8 * self.width + 10
        changed_type = 8 * self.width + 11
        words[same_type] = 0x0012
        bts[same_type] = 0x20
        words[changed_type] = self.solid

        restored_words, restored_bts, restored = preserve_unchanged_structure_details(
            room, words, bts, [same_type, changed_type]
        )

        self.assertEqual(1, restored)
        self.assertEqual(room.layer1_words[same_type], restored_words[same_type])
        self.assertEqual(room.bts[same_type], restored_bts[same_type])
        self.assertEqual(self.solid, restored_words[changed_type])

    def test_context_dressing_improves_source_detail_adjacency(self) -> None:
        room = self.room()
        source_words = list(room.layer1_words)
        for index, word in enumerate(source_words):
            source_words[index] = (word & 0xF000) | (0x120 + index % 2)
        patterned = RoomGrid(
            **{
                **room.__dict__,
                "layer1_words": tuple(source_words),
                "block_types": tuple(word >> 12 for word in source_words),
                "resolved_block_types": tuple(resolve_block_types(source_words, self.width, self.height)),
            }
        )
        noisy_words = [(word & 0xF000) | 0x120 for word in source_words]
        mutable = [
            index for index, block_type in enumerate(patterned.block_types) if block_type != 0x9
        ]
        _, _, horizontal, vertical = source_detail_grammar(patterned)
        before = detail_adjacency_fraction(
            noisy_words,
            list(patterned.bts),
            self.width,
            self.height,
            horizontal,
            vertical,
            set(mutable),
        )

        dressed_words, dressed_bts, stats = dress_room_layout(
            patterned,
            noisy_words,
            list(patterned.bts),
            build_vocabulary([patterned]),
            mutable,
        )
        after = detail_adjacency_fraction(
            dressed_words,
            dressed_bts,
            self.width,
            self.height,
            horizontal,
            vertical,
            set(mutable),
        )

        self.assertGreater(after, before)
        self.assertGreater(stats["retiledCellCount"], 0)

    def test_generated_door_shape_must_be_small_and_edge_aligned(self) -> None:
        valid = {
            "edge": "left",
            "orientation": "vertical",
            "cellIndices": [self.width * y for y in range(4, 8)],
        }
        oversized = {
            "edge": "left",
            "orientation": "vertical",
            "cellIndices": [self.width * y for y in range(1, 15)],
        }

        self.assertTrue(usable_generated_door(valid, self.width))
        self.assertFalse(usable_generated_door(oversized, self.width))


if __name__ == "__main__":
    unittest.main()
