package com.supermetroid.editor.data

/** Expected vanilla bytes for the editor's curated one-line hex patches. */
private val VANILLA_HEX_PATCH_BYTES = mapOf(
    0x81EB9 to listOf(0x04),
    0x83860 to listOf(0x3C),
    0x81F64 to listOf(0x30, 0x02),
    0x8054E to listOf(0xFF),
    0x81775 to listOf(0xFF),
    0x85396 to listOf(0xAD, 0xC0),
    0x8F66F to listOf(0x22, 0x53, 0xDE, 0x91),
    0x8F625 to listOf(0x23),
    0x81EA2 to listOf(0x1C),
    0x8267F to listOf(0x20, 0x64, 0x8E),
    0x81006 to listOf(0xFF, 0x00),
    0x81B2F to listOf(0x02),
    0x82445 to listOf(0xAD, 0x5E, 0x19),
    0x9826B to listOf(0xD0),
    0x838D4 to listOf(0x00),
    0x982F7 to listOf(0x22, 0xA9, 0xB6, 0x90),
    0x824F5 to listOf(0xAD, 0xD0, 0x0C),
    0x83EBF to listOf(0xCE),
    0x83EC4 to listOf(0xCE),
    0x8402E to listOf(0x8D),
    0x8F7D5 to listOf(0x16),
    0x82474 to listOf(0xD0),
    0x8178C to listOf(0x89),
    0x10BAF to listOf(0x22, 0x87, 0x86, 0xA0),
    0x20717 to listOf(0x22, 0x21, 0x87, 0x84),
    0x4079A to listOf(0x0A),
    0xDBDAA to listOf(0x01),
    0x10B61 to listOf(0x22, 0x85, 0x97, 0xA0),
    0x40B5F to listOf(0x22, 0xA2, 0xD5, 0x90),
    0x6E37D to listOf(0x21),
    0x23D58 to listOf(0xC9, 0x00, 0x02, 0xF0, 0x22),
)

/** Returns copies with expected-before bytes attached wherever they are known. */
fun withVanillaHexPatchPreconditions(patches: List<SmPatch>): List<SmPatch> =
    patches.map { patch ->
        patch.copy(
            writes = patch.writes.map { write ->
                val expected = VANILLA_HEX_PATCH_BYTES[write.offset.toInt()]
                    ?.takeIf { it.size == write.bytes.size }
                if (write.expectedBytes != null || expected == null) {
                    write.copy(bytes = write.bytes.toList(), expectedBytes = write.expectedBytes?.toList())
                } else {
                    write.copy(bytes = write.bytes.toList(), expectedBytes = expected)
                }
            }.toMutableList(),
            customItems = patch.customItems.map { it.copy() }.toMutableList(),
            compatibleRomHashes = patch.compatibleRomHashes.toMutableList(),
            resources = patch.resources.map { it.copy() }.toMutableList(),
        )
    }
