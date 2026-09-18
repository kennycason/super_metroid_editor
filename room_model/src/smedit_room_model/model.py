from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
from torch import nn

from .data import AREA_COUNT, BLOCK_TYPE_COUNT, BTS_COUNT, TILE_BITS_COUNT, TILESET_COUNT


@dataclass(frozen=True)
class RoomModelConfig:
    width: int = 96
    depth: int = 8
    block_embedding: int = 12
    tile_embedding: int = 28
    bts_embedding: int = 12
    tileset_embedding: int = 12
    area_embedding: int = 8
    dropout: float = 0.05

    def to_dict(self) -> dict[str, int | float]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: dict[str, object]) -> "RoomModelConfig":
        return cls(**value)


class ResidualBlock(nn.Module):
    def __init__(self, width: int, dilation: int, dropout: float) -> None:
        super().__init__()
        groups = 8 if width % 8 == 0 else 1
        self.layers = nn.Sequential(
            nn.GroupNorm(groups, width),
            nn.SiLU(),
            nn.Conv2d(width, width, 3, padding=dilation, dilation=dilation),
            nn.Dropout2d(dropout),
            nn.GroupNorm(groups, width),
            nn.SiLU(),
            nn.Conv2d(width, width, 3, padding=dilation, dilation=dilation),
        )

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        return inputs + self.layers(inputs)


class RoomDenoiser(nn.Module):
    """Fully convolutional masked-token model with exact categorical output heads."""

    def __init__(self, config: RoomModelConfig) -> None:
        super().__init__()
        self.config = config
        self.block_embedding = nn.Embedding(BLOCK_TYPE_COUNT + 1, config.block_embedding)
        self.tile_embedding = nn.Embedding(TILE_BITS_COUNT + 1, config.tile_embedding)
        self.bts_embedding = nn.Embedding(BTS_COUNT + 1, config.bts_embedding)
        self.tileset_embedding = nn.Embedding(TILESET_COUNT, config.tileset_embedding)
        self.area_embedding = nn.Embedding(AREA_COUNT, config.area_embedding)
        input_width = (
            config.block_embedding
            + config.tile_embedding
            + config.bts_embedding
            + config.tileset_embedding
            + config.area_embedding
            + 6  # x/y, room width/height, masked, valid
        )
        self.stem = nn.Conv2d(input_width, config.width, 3, padding=1)
        dilations = (1, 2, 4, 8)
        self.blocks = nn.Sequential(
            *(ResidualBlock(config.width, dilations[index % len(dilations)], config.dropout) for index in range(config.depth))
        )
        self.final_norm = nn.GroupNorm(8 if config.width % 8 == 0 else 1, config.width)
        self.block_head = nn.Conv2d(config.width, BLOCK_TYPE_COUNT, 1)
        self.predicted_type_embedding = nn.Embedding(BLOCK_TYPE_COUNT, config.width)
        self.detail_block = ResidualBlock(config.width, 1, config.dropout)
        self.tile_head = nn.Conv2d(config.width, TILE_BITS_COUNT, 1)
        self.bts_head = nn.Conv2d(config.width, BTS_COUNT, 1)

    def forward(
        self,
        block_types: torch.Tensor,
        tile_bits: torch.Tensor,
        bts: torch.Tensor,
        tileset: torch.Tensor,
        area: torch.Tensor,
        coordinates: torch.Tensor,
        mask: torch.Tensor,
        valid: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        height, width = block_types.shape[-2:]
        block_features = self.block_embedding(block_types).permute(0, 3, 1, 2)
        tile_features = self.tile_embedding(tile_bits).permute(0, 3, 1, 2)
        bts_features = self.bts_embedding(bts).permute(0, 3, 1, 2)
        tileset_features = self.tileset_embedding(tileset)[:, :, None, None].expand(-1, -1, height, width)
        area_features = self.area_embedding(area)[:, :, None, None].expand(-1, -1, height, width)
        inputs = torch.cat(
            (
                block_features,
                tile_features,
                bts_features,
                tileset_features,
                area_features,
                coordinates,
                mask[:, None].float(),
                valid[:, None].float(),
            ),
            dim=1,
        )
        hidden = self.blocks(self.stem(inputs))
        hidden = torch.nn.functional.silu(self.final_norm(hidden))
        block_logits = self.block_head(hidden)

        type_probabilities = block_logits.softmax(dim=1)
        type_context = torch.einsum("bkhw,kc->bchw", type_probabilities, self.predicted_type_embedding.weight)
        detail_hidden = self.detail_block(hidden + type_context)
        return block_logits, self.tile_head(detail_hidden), self.bts_head(detail_hidden)
