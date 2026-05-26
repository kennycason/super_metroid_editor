# Feature 4: Room JSON Export/Import

## Goal
Export individual rooms as self-contained JSON files for sharing,
version control, and collaboration. Import rooms from JSON files.

## Format
```json
{
  "version": 1,
  "roomId": "91F8",
  "roomName": "Landing Site",
  "width": 9,
  "height": 5,
  "tileset": 0,
  "area": 0,
  "levelData": "<base64 of decompressed level data>",
  "scrollData": [1, 0, 1, 1, ...],
  "enemies": [
    {"species": "0xDCFF", "x": 100, "y": 200, "init": 0, "props": 0}
  ],
  "plms": [
    {"id": "0xC848", "x": 1, "y": 6, "param": "0x9006"}
  ],
  "doors": [
    {"destRoom": "0x92FD", "direction": 0, "capCode": 0, ...}
  ],
  "fx": {
    "type": "none",
    "surfaceStart": 0,
    ...
  },
  "music": "0x05"
}
```

## Where it lives
- MapCanvas toolbar — new export/import buttons (↓ export, ↑ import)
- File dialog for save/load location
- Export reads current room state (with edits applied)
- Import creates a new RoomEdits entry from the JSON

## Implementation
1. Add `RoomExportData` data class (serializable)
2. Add `exportRoomToJson(roomId)` to EditorState
3. Add `importRoomFromJson(json)` to EditorState
4. Add toolbar buttons to MapCanvas
5. Import applies as edit operations (undoable)

## Tests
- Export Landing Site → verify JSON has expected fields
- Export → Import roundtrip preserves tile data
- Import updates room dimensions if different
