from __future__ import annotations

import argparse
import json
import sys


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="SMEDIT learned room-model tools")
    subparsers = parser.add_subparsers(dest="command", required=True)
    inspect_parser = subparsers.add_parser("inspect", help="validate and summarize a CLI training-data export")
    inspect_parser.add_argument("--dataset", required=True)
    subparsers.add_parser("train", help="train a masked categorical room model")
    subparsers.add_parser("generate", help="generate a room proposal")
    args, remaining = parser.parse_known_args(argv)

    if args.command == "inspect":
        from .format import inspect_dataset

        print(json.dumps(inspect_dataset(args.dataset), indent=2))
        return
    if args.command == "train":
        from .train import main as train_main

        train_main(remaining)
        return
    if args.command == "generate":
        from .generate import main as generate_main

        generate_main(remaining)
        return
    parser.error(f"unknown command: {args.command}")
    sys.exit(2)


if __name__ == "__main__":
    main()

