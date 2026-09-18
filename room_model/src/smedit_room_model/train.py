from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path
from typing import Iterable

import numpy as np
import torch
import torch.nn.functional as functional
from torch.utils.data import DataLoader

from . import MODEL_FORMAT, MODEL_FORMAT_VERSION
from .data import RoomCropDataset, build_vocabulary, load_and_split_rooms
from .model import RoomDenoiser, RoomModelConfig


def choose_device(requested: str) -> torch.device:
    if requested != "auto":
        return torch.device(requested)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def move_batch(batch: dict[str, torch.Tensor], device: torch.device) -> dict[str, torch.Tensor]:
    return {key: value.to(device) for key, value in batch.items()}


def room_loss(
    outputs: tuple[torch.Tensor, torch.Tensor, torch.Tensor],
    batch: dict[str, torch.Tensor],
) -> tuple[torch.Tensor, dict[str, float]]:
    block_logits, tile_logits, bts_logits = outputs
    active = batch["mask"] & batch["valid"]
    active_float = active.float()
    target_types = batch["target_block_types"]

    # Air and ordinary solids dominate the ROM. Keep doors, slopes, extensions,
    # hazards, and nonzero BTS loud enough to survive that class imbalance.
    cell_weight = torch.ones_like(active_float)
    cell_weight = torch.where(target_types == 0x9, cell_weight * 5.0, cell_weight)
    cell_weight = torch.where(target_types == 0x1, cell_weight * 4.0, cell_weight)
    unusual = (target_types != 0x0) & (target_types != 0x8)
    cell_weight = torch.where(unusual, cell_weight * 1.8, cell_weight)
    cell_weight = torch.where(batch["target_bts"] != 0, cell_weight * 1.7, cell_weight)
    denominator = (active_float * cell_weight).sum().clamp_min(1.0)

    def masked_cross_entropy(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
        values = functional.cross_entropy(logits, target, reduction="none")
        return (values * active_float * cell_weight).sum() / denominator

    block = masked_cross_entropy(block_logits, target_types)
    tile = masked_cross_entropy(tile_logits, batch["target_tile_bits"])
    bts = masked_cross_entropy(bts_logits, batch["target_bts"])
    total = block + tile * 0.7 + bts * 0.45
    metrics = {
        "loss": float(total.detach().cpu()),
        "block": float(block.detach().cpu()),
        "tile": float(tile.detach().cpu()),
        "bts": float(bts.detach().cpu()),
    }
    return total, metrics


def average_metrics(values: Iterable[dict[str, float]]) -> dict[str, float]:
    collected = list(values)
    if not collected:
        return {}
    return {key: sum(item[key] for item in collected) / len(collected) for key in collected[0]}


@torch.no_grad()
def evaluate(
    model: RoomDenoiser,
    loader: DataLoader[dict[str, torch.Tensor]],
    device: torch.device,
) -> dict[str, float]:
    model.eval()
    metrics = []
    for batch in loader:
        batch = move_batch(batch, device)
        outputs = model(
            batch["input_block_types"],
            batch["input_tile_bits"],
            batch["input_bts"],
            batch["tileset"],
            batch["area"],
            batch["coordinates"],
            batch["mask"],
            batch["valid"],
        )
        _, batch_metrics = room_loss(outputs, batch)
        metrics.append(batch_metrics)
    return average_metrics(metrics)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Train the SMEDIT masked categorical room model")
    parser.add_argument("--dataset", required=True, help="directory produced by CLI training-data")
    parser.add_argument("--output", required=True, help="checkpoint path (.pt)")
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--samples-per-epoch", type=int, default=2048)
    parser.add_argument("--crop-size", type=int, default=32)
    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--validation-fraction", type=float, default=0.12)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--device", default="auto", help="auto, cpu, cuda, mps, or another torch device")
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--model-width", type=int, default=96)
    parser.add_argument("--model-depth", type=int, default=8)
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    if args.epochs <= 0 or args.batch_size <= 0 or args.samples_per_epoch <= 0:
        raise SystemExit("epochs, batch-size, and samples-per-epoch must be positive")
    if not 0.0 <= args.validation_fraction < 1.0:
        raise SystemExit("validation-fraction must be in [0, 1)")

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    train_rooms, validation_rooms, manifest = load_and_split_rooms(
        args.dataset, args.validation_fraction, args.seed
    )
    vocabulary = build_vocabulary(train_rooms)
    train_dataset = RoomCropDataset(
        train_rooms,
        crop_size=args.crop_size,
        samples_per_epoch=args.samples_per_epoch,
        seed=args.seed,
    )
    train_loader = DataLoader(
        train_dataset,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.workers,
        pin_memory=torch.cuda.is_available(),
    )
    validation_loader = None
    if validation_rooms:
        validation_dataset = RoomCropDataset(
            validation_rooms,
            crop_size=args.crop_size,
            samples_per_epoch=max(256, min(1024, len(validation_rooms) * 16)),
            seed=args.seed + 10_000,
        )
        validation_loader = DataLoader(
            validation_dataset,
            batch_size=args.batch_size,
            shuffle=False,
            num_workers=args.workers,
        )

    device = choose_device(args.device)
    config = RoomModelConfig(width=args.model_width, depth=args.model_depth)
    model = RoomDenoiser(config).to(device)
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=args.learning_rate, weight_decay=args.weight_decay
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)
    parameter_count = sum(parameter.numel() for parameter in model.parameters())
    print(
        json.dumps(
            {
                "device": str(device),
                "parameters": parameter_count,
                "trainingRooms": len(train_rooms),
                "validationRooms": len(validation_rooms),
                "cropSize": args.crop_size,
            }
        ),
        flush=True,
    )

    history: list[dict[str, object]] = []
    best_loss = math.inf
    best_epoch = 0
    best_state: dict[str, torch.Tensor] | None = None
    for epoch in range(args.epochs):
        train_dataset.set_epoch(epoch)
        model.train()
        train_metrics = []
        for batch in train_loader:
            batch = move_batch(batch, device)
            optimizer.zero_grad(set_to_none=True)
            outputs = model(
                batch["input_block_types"],
                batch["input_tile_bits"],
                batch["input_bts"],
                batch["tileset"],
                batch["area"],
                batch["coordinates"],
                batch["mask"],
                batch["valid"],
            )
            loss, metrics = room_loss(outputs, batch)
            if not torch.isfinite(loss):
                raise RuntimeError(f"non-finite training loss at epoch {epoch + 1}")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            train_metrics.append(metrics)
        scheduler.step()

        train_average = average_metrics(train_metrics)
        validation_average = (
            evaluate(model, validation_loader, device) if validation_loader is not None else {}
        )
        record: dict[str, object] = {
            "epoch": epoch + 1,
            "learningRate": scheduler.get_last_lr()[0],
            "train": train_average,
            "validation": validation_average,
        }
        history.append(record)
        print(json.dumps(record), flush=True)
        selection_metrics = validation_average if validation_average else train_average
        selection_loss = float(selection_metrics["loss"])
        if selection_loss < best_loss:
            best_loss = selection_loss
            best_epoch = epoch + 1
            best_state = {
                key: value.detach().cpu().clone() for key, value in model.state_dict().items()
            }

    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    checkpoint = {
        "format": MODEL_FORMAT,
        "formatVersion": MODEL_FORMAT_VERSION,
        "datasetSchemaVersion": int(manifest["schemaVersion"]),
        "modelConfig": config.to_dict(),
        "modelState": best_state
        if best_state is not None
        else {key: value.detach().cpu() for key, value in model.state_dict().items()},
        "vocabulary": vocabulary.to_dict(),
        "training": {
            "seed": args.seed,
            "cropSize": args.crop_size,
            "epochs": args.epochs,
            "parameterCount": parameter_count,
            "selectedEpoch": best_epoch,
            "selectedLoss": best_loss,
            "selectionMetric": "validation.loss" if validation_rooms else "train.loss",
            "trainRoomIds": [room.room_id for room in train_rooms],
            "validationRoomIds": [room.room_id for room in validation_rooms],
            "history": history,
        },
    }
    temporary = output.with_suffix(output.suffix + ".tmp")
    torch.save(checkpoint, temporary)
    temporary.replace(output)
    if not math.isfinite(history[-1]["train"]["loss"]):
        raise RuntimeError("final checkpoint has a non-finite loss")
    print(
        json.dumps(
            {
                "checkpoint": str(output),
                "bytes": output.stat().st_size,
                "selectedEpoch": best_epoch,
                "selectedLoss": best_loss,
            }
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
