# SMEDIT learned room generator

This is the first experimental learned-generation path for SMEDIT. It trains a small masked,
categorical convolutional model on room grids exported from a ROM supplied by the user. It does
not contain or download ROM data.

The model learns three values for every 16x16 block:

- raw block type (`0..15`), including slopes, extensions, hazards, and type-9 door tiles;
- the complete lower 12 bits of the layer-1 word (metatile plus horizontal/vertical flips);
- the complete BTS byte, including slope shape and other block-type-specific metadata.

The output is a proposal JSON file that SMEDIT can validate, preview, and apply as one undoable
operation. It deliberately does not create door PLMs/covers, destinations, save stations, or room
states. Those remain deterministic editor work.

## 1. Export training data

From the repository root, keep the dataset and checkpoints in the module's ignored working
directories. Assigning the ROM path through a single-quoted shell variable also handles spaces and
the `!` in common Super Metroid filenames correctly in zsh:

```bash
ROM='/absolute/path/to/Super Metroid (JU) [!].smc'
DATASET="$PWD/room_model/datasets/vanilla"

./gradlew :cli:runCli -Pargs="--rom '$ROM' training-data -o '$DATASET'"
```

The export is versioned and lossless for layer 1 + BTS. Duplicate room contents carry the same
content hash so they cannot accidentally leak across the training and validation sets.

## 2. Install the local trainer

Python 3.10+ is required. PyTorch may be installed with a platform-specific command first if you
need CUDA or another accelerator.

```bash
python3.12 -m venv .venv-room-model
source .venv-room-model/bin/activate
python -m pip install -e room_model
```

The package requires Python 3.10 or newer. Python 3.12 is the version used for the reference run
below.

## 3. Inspect and train

Run a dataset check before committing time to training:

```bash
smedit-room-model inspect --dataset "$DATASET"
```

The vanilla JU reference export contains 262 usable rooms, 241 unique room contents, 450,048
blocks, 599 door groups, and 17,740 blocks with nonzero BTS.

Train the coherence-oriented 20-epoch checkpoint:

```bash
CHECKPOINT="$PWD/room_model/checkpoints/coherent-v2.pt"

smedit-room-train \
  --dataset "$DATASET" \
  --output "$CHECKPOINT" \
  --epochs 20 \
  --samples-per-epoch 2048 \
  --batch-size 8 \
  --device auto
```

Training uses mostly contiguous 32x32 masks that match the regions edited during remix generation,
biases some crops toward doors, and sometimes masks the entire crop. Losses for slopes, doors, and
nonzero BTS are up-weighted so common air/solid tiles do not erase rare structural details. The
checkpoint also stores collision-context frequencies and horizontal/vertical detail adjacencies
learned from real rooms. `--device auto` selects CUDA, Apple MPS, or CPU in that order.

The trainer saves the epoch with the best validation loss rather than blindly saving the final
epoch. In the September 11, 2026 reference run on Apple MPS, the 2.11M-parameter model used 232
training rooms and 30 validation rooms. Epoch 12 was selected with validation loss 4.211; the full
run ended at 2.225 train / 4.282 validation after epoch 20. The resulting checkpoint was 8.9 MiB.

## 4. Generate evaluation bundles

Start with `remix`, not `new`, and compare several rooms with different dimensions and tilesets.
The recommended 0.25 strength edits contiguous regions instead of scattering independent changes
across the entire room. Context retiling, collision smoothing, existing-door preservation, and
new-door suppression are enabled by default. These are the exact commands used by the reference
run:

```bash
smedit-room-generate \
  --model "$CHECKPOINT" \
  --template "$DATASET/rooms/room_92fd.json" \
  --output /tmp/room_92fd_coherent_v2.json \
  --mode remix \
  --strength 0.25 \
  --candidates 6 \
  --steps 8 \
  --temperature 0.55 \
  --seed 1337

smedit-room-generate \
  --model "$CHECKPOINT" \
  --template "$DATASET/rooms/room_9ad9.json" \
  --output /tmp/room_9ad9_coherent_v2.json \
  --mode remix \
  --strength 0.25 \
  --candidates 6 \
  --steps 8 \
  --temperature 0.55 \
  --seed 1337

smedit-room-generate \
  --model "$CHECKPOINT" \
  --template "$DATASET/rooms/room_a253.json" \
  --output /tmp/room_a253_coherent_v2.json \
  --mode remix \
  --strength 0.25 \
  --candidates 6 \
  --steps 8 \
  --temperature 0.55 \
  --seed 1337
```

The templates are Parlor and Alcatraz (`0x92FD`), Green Brinstar Main Shaft (`0x9AD9`), and Red
Tower (`0xA253`). The reference results were:

| Room | Best seed | Score | Changed blocks | Pattern edges | Repair changes | Doors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Parlor and Alcatraz | 1337 | 82.417 | 450 | 63.2% | 49 | 7 |
| Green Brinstar Main Shaft | 1337 | 84.928 | 272 | 70.9% | 103 | 10 |
| Red Tower | 5001352 | 83.832 | 190 | 74.7% | 70 | 5 |

Every best candidate had one passable component and every source door reachable before import. The
strict **Pattern edges** measure counts changed-cell edges whose exact metatile/BTS pairing occurred
in the learned tileset grammar or source room. Lower values are now penalized and shown as warnings
instead of receiving a deceptively high connectivity-only score.

For the first visual test, use an even smaller Red Tower remix:

```bash
smedit-room-generate \
  --model "$CHECKPOINT" \
  --template "$DATASET/rooms/room_a253.json" \
  --output /tmp/room_a253_conservative_v2.json \
  --mode remix \
  --strength 0.15 \
  --candidates 6 \
  --steps 8 \
  --temperature 0.45 \
  --seed 1337
```

That reference bundle changes 185 blocks in its top candidate, compared with 1,104 in the original
0.55-strength Red Tower experiment.

### Generate a new layout from existing door anchors

`remix` is source-conditioned inpainting, so its candidates should look like variations of the
existing room. Use `new` when the collision layout itself should be replaced:

```bash
smedit-room-generate \
  --model "$CHECKPOINT" \
  --template "$DATASET/rooms/room_a253.json" \
  --output /tmp/room_a253_new_v2.json \
  --mode new \
  --candidates 12 \
  --steps 12 \
  --temperature 0.45 \
  --seed 424242
```

The same checkpoint, template, options, and seed always reproduce the same bundle. Candidate zero
uses the base seed; later candidates use `baseSeed + candidateIndex * 1,000,003`. In `new` mode the
template still supplies the room ID, dimensions, area, tileset, and existing type-9 door cells. The
generator calibrates open space to the template room's passable fraction by default; override it
with `--target-open-fraction 0.45` when a denser or airier room is desired.

The tested Red Tower bundle contains 12 distinct collision layouts. Its top candidate uses seed
5,424,257, changes 1,973 blocks, finishes 57.0% open after repair against a 52.6% target, retains all
five source door groups, reaches every door from the main region, and has 96.9% learned pattern-edge
compatibility.

This constrains a new layout to an existing room slot; it does not create a room header or door
destinations. Custom doors enabled with `--allow-new-doors` remain layout placeholders and still
need matching PLMs, covers, and destinations in SMEDIT.

`remix` now retains the original artwork and BTS whenever a cell's generated collision type remains
unchanged. `new` masks the full room. Existing door tiles are preserved by default in either mode,
and new type-9 placeholders are suppressed unless `--allow-new-doors` is passed. Use
`--no-preserve-existing-doors --allow-new-doors` only when deliberately exploring an entirely new
boundary layout.

The generator masks impossible detail values using combinations observed for the selected tileset
and block type. It removes isolated collision speckles, repairs connectivity, opens door approaches,
and retiles changed structure using source-room and learned neighboring patterns. Results are ranked
using connectivity, door reachability, structural coherence, detail adjacency, density, novelty,
and repair cost. A single candidate still produces the original `smedit-room-proposal` document;
two or more candidates produce a
`smedit-room-proposal-bundle` containing the ranked proposals.

## 5. Preview and apply in SMEDIT

Load the proposal's source room and tileset in the desktop editor, open the biome generator panel,
and select **Import candidate JSON…**. The button becomes available after SMEDIT learns a safe live
tileset profile. SMEDIT then performs a second safety pass using live editor state:

- the proposal room ID, dimensions, tileset, arrays, and derived block types must match;
- existing door tiles and fixtures are restored verbatim;
- PLMs, elevators, enemies, the Landing Site ship, and metadata-sensitive cells are protected;
- invalid generated doors are removed and remaining passable regions are connected;
- changed plain terrain is redressed with the live ROM profile, which filters placeholder metatiles;
- candidates are re-ranked and shown as selectable collision-layout previews.

Choose a preview and select **Apply selected candidate**. The gallery remains open after applying,
so another preview can be selected and applied immediately for A/B comparison. Each switch is a
normal project operation; Ctrl+Z walks back through the previously applied candidates. If the room
was edited outside the reviewed gallery, switching is refused until the bundle is re-imported. Any
added door groups remain explicit layout placeholders; the preview warns that they still need door
PLMs, covers, and destinations before the room is ready for gameplay.

The candidate score is now sensitive to structural fragmentation and source-pattern edge matches,
but it remains an approximation rather than an artistic judgment. Review the rendered room for
coherent large-scale shapes, intentional slopes and platforms, believable tile motifs, and sensible
door approaches.

## 6. Regression checks

Run both model and editor tests after changing the format, repair rules, model, or importer:

```bash
source .venv-room-model/bin/activate
python -m unittest discover -s room_model/tests -v

./gradlew :shared:jvmTest :desktopApp:jvmTest :cli:compileKotlinJvm
git diff --check
```

The current suite covers lossless format validation, extension-block resolution, door grouping,
contiguous masking, collision smoothing, contextual dressing, generated-door shape checks,
connectivity repair, coherence ranking, enemy safety halos, model output dimensions, single and
bundled proposal decoding, live editor preparation, stale-preview rejection, and undoable apply.
