# Load Stations (AreaSave)

Super Metroid calls these records **load stations**. Older tools and parts of
SMEDIT call the same data **AreaSave** because indices `0..7` are used when a
save-station PLM resumes a file. The table is broader than saves: it also owns
elevator destinations, Ceres sequences, debug destinations, and a gunship
landing record. Treating every entry as spare save capacity corrupts those
engine-owned destinations.

## Source layout

`LoadStationListPointers` at `$80:C4B5` contains eight bank-`$80` pointers.
The final list ends at `$80:CD07`, immediately before
`SetDebugElevatorAsUsed`.

| Area | List | Entries | Save slots | Occupied saves | Other entries |
|---|---:|---:|---:|---:|---|
| Crateria | `$80:C4C5` | 19 | 8 | 2 | 8 elevators, 2 debug, 1 gunship landing |
| Brinstar | `$80:C5CF` | 19 | 8 | 5 | 8 elevators, 3 debug |
| Norfair | `$80:C6D9` | 23 | 8 | 6 | 8 elevators, 7 debug |
| Wrecked Ship | `$80:C81B` | 18 | 8 | 1 | 8 elevators, 2 debug |
| Maridia | `$80:C917` | 20 | 8 | 4 | 8 elevators, 4 debug |
| Tourian | `$80:CA2F` | 18 | 8 | 2 | 8 elevators, 2 debug |
| Ceres | `$80:CB2B` | 17 | — | — | 17 scripted Ceres entries |
| Debug | `$80:CC19` | 17 | — | — | 17 debug entries |

The complete retail table is 151 entries / 2,114 bytes. Areas 0–5 have 48
addressable save slots: 20 occupied and 28 blank. Nineteen occupied slots have
normal `$B76F` save-station PLMs. Crateria slot `0` is the intentional exception:
Landing Site/the gunship owns it without a normal save PLM.

## Fourteen-byte entry

| Offset | Size | Runtime field |
|---:|---:|---|
| `+0` | 2 | Room header pointer (bank `$8F`) |
| `+2` | 2 | Incoming door pointer (bank `$83`) |
| `+4` | 2 | Door BTS; used by the demo recorder and nonzero in seven special records |
| `+6` | 2 | Layer 1 / camera X |
| `+8` | 2 | Layer 1 / camera Y |
| `+A` | 2 | Samus Y relative to the top of the screen |
| `+C` | 2 | Samus X relative to screen center; runtime adds `$0080` |

`LoadFromLoadStation` at `$80:C437` multiplies the selected index by 14 and
loads these fields. The save PLM instruction at `$84:8CF1` performs
`AND #$0007`, so a normal save station can address only indices `0..7`.
Indices `8..15` in areas 0–5 belong to elevator loading, not additional save
slots. Later records and all Ceres/Debug records are special-purpose.

## SMEDIT ownership rules

- Adding a save station chooses a blank index in `0..7`. It considers both
  normal save PLMs and nonzero ROM load-station records occupied, so the
  gunship-owned Crateria slot can never be reused accidentally.
- Export may update an occupied slot only for the room that already owns it.
  Clearing a slot is likewise restricted to its current owner.
- A ninth save station is rejected. Raising this limit requires a deliberate
  engine/SRAM/file-select patch; moving or lengthening the table is insufficient.
- Cross-area room moves must create destination records and clear only the
  room's source records. Elevator, Ceres, debug, and gunship migrations remain
  separate engine-feature work.

`./gradlew parityLoadStations` generates
`parity/reports/load-stations.json`. The strict tests compare all pointers,
counts, fields, save PLM ownership, classifications, and nine direct engine
consumers against the pinned disassembly and rebuilt ROM. `parityReport`
includes this as check R-13.
