import unittest

import torch

from smedit_room_model.data import BLOCK_MASK_TOKEN, BTS_MASK_TOKEN, TILE_MASK_TOKEN
from smedit_room_model.model import RoomDenoiser, RoomModelConfig


class RoomModelTest(unittest.TestCase):
    def test_output_heads_preserve_grid_shape(self) -> None:
        model = RoomDenoiser(RoomModelConfig(width=16, depth=1))
        height, width = 16, 20
        outputs = model(
            torch.full((1, height, width), BLOCK_MASK_TOKEN, dtype=torch.long),
            torch.full((1, height, width), TILE_MASK_TOKEN, dtype=torch.long),
            torch.full((1, height, width), BTS_MASK_TOKEN, dtype=torch.long),
            torch.tensor([7], dtype=torch.long),
            torch.tensor([1], dtype=torch.long),
            torch.zeros((1, 4, height, width), dtype=torch.float32),
            torch.ones((1, height, width), dtype=torch.bool),
            torch.ones((1, height, width), dtype=torch.bool),
        )
        self.assertEqual((1, 16, height, width), tuple(outputs[0].shape))
        self.assertEqual((1, 4096, height, width), tuple(outputs[1].shape))
        self.assertEqual((1, 256, height, width), tuple(outputs[2].shape))


if __name__ == "__main__":
    unittest.main()

