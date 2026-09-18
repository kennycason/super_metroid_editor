import unittest

from smedit_room_model.format import RoomGrid, find_door_groups, resolve_block_types


class RoomFormatTest(unittest.TestCase):
    def test_resolves_horizontal_and_vertical_extensions(self) -> None:
        words = [
            0x8120,
            0x5000,
            0x0000,
            0x0000,
            0xD000,
            0x0000,
        ]
        self.assertEqual([8, 8, 0, 0, 8, 0], resolve_block_types(words, 3, 2))

    def test_finds_edge_door_clusters(self) -> None:
        values = [0] * 24
        values[0] = 9
        values[4] = 9
        values[8] = 9
        groups = find_door_groups(values, 4, 6)
        self.assertEqual(1, len(groups))
        self.assertEqual("left", groups[0]["edge"])
        self.assertEqual("vertical", groups[0]["orientation"])

    def test_rejects_inconsistent_block_types(self) -> None:
        value = {
            "schemaVersion": 1,
            "roomId": 1,
            "roomIdHex": "0x1",
            "area": 0,
            "tileset": 0,
            "widthScreens": 1,
            "heightScreens": 1,
            "widthBlocks": 1,
            "heightBlocks": 1,
            "contentHash": "test",
            "layer1Words": [0x8120],
            "blockTypes": [0],
            "resolvedBlockTypes": [8],
            "bts": [0],
        }
        with self.assertRaisesRegex(ValueError, "blockTypes"):
            RoomGrid.from_dict(value)


if __name__ == "__main__":
    unittest.main()

