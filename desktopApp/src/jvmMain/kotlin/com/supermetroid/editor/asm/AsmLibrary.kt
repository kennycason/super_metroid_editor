package com.supermetroid.editor.asm

internal enum class AsmInstructionCategory(val displayName: String) {
    LOAD_STORE("Load & store"),
    TRANSFER("Transfer"),
    ARITHMETIC("Arithmetic"),
    LOGIC("Logic & bits"),
    BRANCH("Branch & call"),
    STACK("Stack"),
    FLAGS("Flags & CPU state"),
    SYSTEM("System"),
}

internal data class AsmCodeExample(
    val title: String,
    val code: String,
    val explanation: String,
)

internal data class AsmLibrarySection(
    val title: String,
    val paragraphs: List<String>,
    val example: AsmCodeExample? = null,
    val table: AsmLibraryTable? = null,
)

internal data class AsmLibraryTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val monospaceColumns: Set<Int> = headers.indices.toSet(),
    val columnWeights: List<Float> = List(headers.size) { 1f },
)

internal data class AsmLibraryGuide(
    val id: String,
    val title: String,
    val summary: String,
    val sections: List<AsmLibrarySection>,
    val keywords: Set<String> = emptySet(),
)

internal data class AsmInstructionInfo(
    val mnemonic: String,
    val name: String,
    val summary: String,
    val flags: String = "—",
    val category: AsmInstructionCategory,
    val commonForms: List<String>,
    val example: AsmCodeExample,
    val practicalNote: String,
)

internal data class AsmDirectiveInfo(
    val name: String,
    val form: String,
    val summary: String,
    val practicalNote: String,
)

/**
 * SMEDIT-authored, practical introduction to the 65C816 and Asar syntax used by
 * the Super Metroid disassembly. This is intentionally a field guide rather
 * than a replacement for the complete CPU or assembler manuals.
 */
internal object AsmLibrary {
    private fun code(vararg lines: String) = lines.joinToString("\n")

    private fun directive(name: String, form: String, summary: String, practicalNote: String) =
        AsmDirectiveInfo(name, form, summary, practicalNote)

    val directives: Map<String, AsmDirectiveInfo> = listOf(
        directive("org", "org <address>", "Move Asar's output cursor to a SNES address or resolved label.",
            "org does not allocate or reserve the destination. The bytes emitted afterward still need one verified owner."),
        directive("warnpc", "warnpc <limit>", "Fail assembly if the output cursor advances beyond the supplied boundary.",
            "warnpc bounds growth but does not reserve the unused part of its envelope. SMEDIT's ownership and collision checks remain authoritative."),
        directive("incsrc", "incsrc <source path>", "Assemble another source file at this point.",
            "Include order is semantic. Go to Definition on the path opens the included source in SMEDIT."),
        directive("incbin", "incbin <binary path>", "Copy bytes from a binary file into the output.",
            "The complete inserted byte count consumes ROM space even though the source line is short."),
        directive("lorom", "lorom", "Select LoROM address-to-file mapping for the assembled image.",
            "Super Metroid uses LoROM. Project modules should inherit the project mapping instead of switching it."),
        directive("hirom", "hirom", "Select HiROM address-to-file mapping for the assembled image.",
            "HiROM is not the Super Metroid project mapping; changing it inside a module invalidates address assumptions."),
        directive("math", "math <mode>", "Configure Asar's expression-evaluation compatibility mode.",
            "Changing math mode can change existing expressions. Prefer explicit parentheses in project code."),
        directive("reset", "reset <setting>", "Reset supported assembler state to its default.",
            "This is global build state, not a CPU instruction. Avoid changing inherited state in a standalone module."),
        directive("print", "print <values...>", "Write a build-time message or expression value to assembler output.",
            "print is diagnostic only and emits no runtime instruction bytes."),
        directive("assert", "assert <condition>[, <message>]", "Stop assembly unless a build-time condition is true.",
            "Use assertions to pin addresses and assumptions; they complement, but do not replace, SMEDIT's output-level checks."),
        directive("if", "if <condition>", "Begin a conditional assembly branch.",
            "The condition is evaluated while building; only the selected branch emits bytes."),
        directive("else", "else", "Select the fallback branch of a conditional assembly block.",
            "An else belongs to the current if/elseif block and must end with endif."),
        directive("elseif", "elseif <condition>", "Test another build-time condition after a false if branch.",
            "Module-order-dependent conditions are rejected when they make audited output unstable."),
        directive("endif", "endif", "End a conditional assembly block.",
            "Keep nested build-time branches visibly indented even though indentation has no assembler meaning."),
        directive("while", "while <condition>", "Repeat an assembly-time block while its condition remains true.",
            "This expands source during assembly; ensure the loop changes its condition and has an intentional output bound."),
        directive("endwhile", "endwhile", "End an assembly-time while block.",
            "The complete expanded output, not the source line count, determines space usage."),
        directive("macro", "macro <name>(<parameters>)", "Define reusable source expansion.",
            "A macro call can emit many bytes or directives. Navigate to the macro definition when its ownership is unclear."),
        directive("endmacro", "endmacro", "End a macro definition.",
            "Macro bodies emit nothing until invoked."),
        directive("namespace", "namespace <name|off>", "Place following symbols in a named assembler namespace.",
            "Changing namespace changes exported symbol names and can break references outside the block."),
        directive("struct", "struct <name>", "Begin a named field-layout definition.",
            "Struct definitions describe offsets; instances or data directives are what ultimately emit or address bytes."),
        directive("endstruct", "endstruct", "End a struct field-layout definition.",
            "Keep field widths explicit so later offsets remain reviewable."),
        directive("db", "db <byte values...>", "Emit one byte per value.",
            "Values must fit the intended eight-bit representation; strings and lists may emit multiple bytes."),
        directive("dw", "dw <word values...>", "Emit one little-endian 16-bit word per value.",
            "A dw pointer contains only 16 address bits and normally depends on an implied bank."),
        directive("dl", "dl <long values...>", "Emit one little-endian 24-bit value per item.",
            "Use dl for full SNES addresses only where the consuming format expects three bytes."),
        directive("dd", "dd <double-word values...>", "Emit one little-endian 32-bit value per item.",
            "The 65816 is not a 32-bit CPU; dd is a data-layout directive."),
        directive("fill", "fill <count>[, <value>]", "Emit a repeated byte pattern for a requested count.",
            "Large fill counts consume real output space and are included in collision ownership."),
        directive("fillbyte", "fillbyte <value>", "Choose the byte used by subsequent fill operations.",
            "This changes assembler state; keep the intended fill close to the operation that consumes it."),
        directive("pad", "pad <address>", "Advance to an address while emitting the configured pad byte across the gap.",
            "pad writes the entire gap. It is not equivalent to claiming only the final address."),
        directive("padbyte", "padbyte <value>", "Choose the byte used by subsequent pad operations.",
            "Padding is emitted output and can overwrite data if its range is not explicitly owned."),
        directive("skip", "skip <count>", "Advance the current position by a byte count for layout purposes.",
            "Confirm whether the surrounding construct is defining layout or ROM output before treating skipped space as free."),
        directive("base", "base <address|off>", "Change the logical address used for labels without simply relocating output.",
            "base deliberately separates logical and physical positions. Use it only when the mapping contract is understood."),
        directive("table", "table <path>[, <mode>]", "Load a character-to-byte translation table for string data.",
            "The table affects later string encoding until cleared or replaced."),
        directive("cleartable", "cleartable", "Remove the active character translation table.",
            "Strings assembled afterward return to the assembler's normal encoding behavior."),
    ).associateBy(AsmDirectiveInfo::name)

    val directiveNames: Set<String> = directives.keys

    fun directive(name: String?): AsmDirectiveInfo? = name?.lowercase()?.let(directives::get)

    private fun directiveTable(vararg names: String) = AsmLibraryTable(
        headers = listOf("Directive", "Common form", "Purpose"),
        rows = names.map { name -> directives.getValue(name).let { listOf(it.name, it.form, it.summary) } },
        monospaceColumns = setOf(0, 1),
        columnWeights = listOf(0.55f, 1.25f, 2.2f),
    )

    private fun numberBaseRow(value: Int, meaning: String = "") = listOf(
        value.toString(),
        "\$${value.toString(16).uppercase().padStart(2, '0')}",
        "%${value.toString(2).padStart(8, '0')}",
        meaning,
    )

    val guides: List<AsmLibraryGuide> = listOf(
        AsmLibraryGuide(
            id = "reading-source",
            title = "Reading Super Metroid source",
            summary = "The few syntax rules that make a bank file readable instead of intimidating.",
            keywords = setOf("syntax", "comment", "label", "directive", "asar"),
            sections = listOf(
                AsmLibrarySection(
                    "Code, labels, and comments",
                    listOf(
                        "A label names an address. Instructions below it execute until control flow jumps, branches, or returns. A semicolon starts a comment; the CPU never sees comments or label names.",
                        "Indented words such as LDA and JSL are CPU instructions. Asar directives such as org, db, dw, dl, incbin, and macro tell the assembler how to build bytes; they are not CPU instructions.",
                    ),
                    AsmCodeExample(
                        "A small routine",
                        code(
                            "ClearCounter:",
                            "    STZ.W \$0A00    ; Store zero in RAM",
                            "    RTS            ; Return to the JSR caller",
                        ),
                        "ClearCounter is a label. STZ and RTS become machine code; the comment does not.",
                    ),
                ),
                AsmLibrarySection(
                    "Local labels and constants",
                    listOf(
                        "Asar local labels beginning with a dot belong to the preceding main label, so many routines can each have their own .Loop or .Done. Named constants are substituted while assembling and do not occupy runtime memory unless data is explicitly emitted.",
                    ),
                    AsmCodeExample(
                        "A local loop",
                        code(
                            "!iterations = 4",
                            "RunFourTimes:",
                            "    LDX.W #!iterations",
                            ".Loop",
                            "    JSR DoOneThing",
                            "    DEX",
                            "    BNE .Loop",
                            "    RTS",
                        ),
                        "The assembler resolves !iterations and .Loop before producing the ROM bytes.",
                    ),
                ),
                AsmLibrarySection(
                    "Reading Super Metroid's label hierarchy",
                    listOf(
                        "The dot is the scope marker. Indentation and capitalization are only source-formatting conventions: a flush-left CapitalLabel is commonly a main label, while an indented .DottedLabel is commonly one of its children, but the leading dot is what changes the assembled symbol.",
                        "A dotted label belongs to the nearest preceding main label until another main label begins. Outside that scope, Super Metroid source refers to it through Asar's exported Main_Local name: for example, .DisableAudio beneath DebugConst becomes DebugConst_DisableAudio. Do not casually add or remove the dot: that renames the symbol and can break every reference to it.",
                        "The Samus jump table follows the same rule: .InitialYSpeeds_Jumping beneath SamusPhysicsConstants is exported as SamusPhysicsConstants_InitialYSpeeds_Jumping.",
                        "More dots create deeper scopes. If .EquipmentBitmasks contains ..boots, the exported name is EquipmentScreenData_EquipmentBitmasks_boots. From inside EquipmentScreenData, Asar also accepts the flattened local spelling .EquipmentBitmasks_boots. SMEDIT treats all of these spellings as one definition and one usage set.",
                        "Struct fields use a dot in their public name instead: Enemy.properties or Enemy[1].properties both refer to the .properties field declared by struct Enemy. The bracket chooses an array entry; it is not part of the field's symbol name.",
                    ),
                    AsmCodeExample(
                        "Scoped and nested table labels",
                        code(
                            "EquipmentScreenData:",
                            "  .EquipmentBitmasks:",
                            "  ..boots:",
                            "    dw \$0100,\$0200,\$2000",
                            "",
                            "    LDA.W EquipmentScreenData_EquipmentBitmasks_boots,Y",
                        ),
                        "..boots is nested beneath .EquipmentBitmasks, so Asar exports the complete underscore-separated name. The two-space indentation makes the hierarchy readable but does not control it; the dots do.",
                    ),
                ),
                AsmLibrarySection(
                    "Bank edits versus project modules",
                    listOf(
                        "Edit a vanilla bank when you are changing source that already exists, such as a physics table or an instruction in a known routine. Create a Project Module when you are adding a new hook, routine, table, or shared constants that should remain clearly separate from the pinned disassembly.",
                        "SMEDIT includes enabled project modules after the vanilla source in the exact top-to-bottom order shown in the Source sidebar. Disable temporarily removes a module from the generated gateway without deleting its source or its position. Reordering modules changes build order; it does not automatically allocate ROM space, so a module must still use an intentional org/freespace strategy and connect new code to the engine.",
                    ),
                    AsmCodeExample(
                        "A standalone project module",
                        code(
                            "; modules/example_hook.asm",
                            "org \$90FF00              ; verified free-space target",
                            "ExampleHook:",
                            "    LDA.W SamusYSpeed",
                            "    RTL",
                        ),
                        "The module owns its source file and compiled ROM bytes. A separate hook at the intended call site is still required before this routine can execute.",
                    ),
                ),
                AsmLibrarySection(
                    "Directives, macros, and included data",
                    listOf(
                        "Directives describe the build. org changes the output address, db/dw/dl emit data, incsrc includes more source, and incbin inserts an existing binary asset. None of them execute at runtime.",
                        "A macro call such as %WritePointer(...) expands into source defined elsewhere. Read the macro definition when its arguments or emitted layout are not obvious; the call itself may represent many bytes or instructions.",
                    ),
                    AsmCodeExample(
                        "Build-time source",
                        code(
                            "org \$808000",
                            "HandlerTable:",
                            "    dw LocalHandler       ; 16-bit pointer",
                            "    dl LongHandler        ; 24-bit pointer",
                            "Tiles:",
                            "    incbin \"data/Tiles.bin\"",
                        ),
                        "These lines place pointers and asset bytes in the ROM; the CPU executes them only if code later treats those bytes as instructions.",
                    ),
                ),
                AsmLibrarySection(
                    "Disassembly addresses",
                    listOf(
                        "Super Metroid source commonly ends a line with an address comment such as ;808123;. That records where the vanilla instruction assembled and is extremely useful when comparing source with a ROM or debugger.",
                        "The comment is still only a comment. If writable source later grows or relocates, the assembler does not update a hand-written address comment automatically.",
                    ),
                    AsmCodeExample(
                        "Source and recorded address",
                        code(
                            "LoadRoom:",
                            "    JSL LoadRoomState     ;808123;",
                            "    RTS                   ;808127;",
                        ),
                        "The labels drive assembly. The trailing addresses document the original layout.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "asar-directives",
            title = "Asar directives",
            summary = "Build-time commands that place output, emit data, include files, and control assembly without executing on the SNES CPU.",
            keywords = directives.keys + setOf("placement", "boundary", "include", "conditional", "macro", "data"),
            sections = listOf(
                AsmLibrarySection(
                    "Placement, limits, and emitted space",
                    listOf(
                        "org changes where following bytes are written; it neither searches for space nor reserves it. warnpc adds a hard upper boundary, but the unused portion before that boundary is not automatically owned. In SMEDIT, verified free-space ranges establish eligibility and compiled ownership establishes who actually emitted each byte.",
                        "fill and pad emit bytes. skip and base alter layout/address behavior and deserve special review. For checked hooks, keep the generated org, boundary labels, continuation jump, and warnpc intact.",
                    ),
                    example = AsmCodeExample(
                        "A bounded checked allocation",
                        code(
                            "org \$E08002",
                            "Helper:",
                            "    ; emitted code and data",
                            "Helper_End:",
                            "warnpc \$E08402",
                        ),
                        "The envelope is 1,024 bytes, but ownership ends at Helper_End; warnpc prevents overflow rather than reserving the unused tail.",
                    ),
                    table = directiveTable("org", "warnpc", "base", "skip", "fill", "fillbyte", "pad", "padbyte", "lorom", "hirom"),
                ),
                AsmLibrarySection(
                    "Data, includes, and translation tables",
                    listOf(
                        "db, dw, dl, and dd emit one-, two-, three-, and four-byte values. incsrc expands another assembly file, while incbin copies the contents of a binary asset directly into the ROM image.",
                        "A short directive can emit a large payload. Space accounting follows compiled bytes, not the number of visible source lines.",
                    ),
                    example = AsmCodeExample(
                        "Data widths and included assets",
                        code(
                            "db \$7F",
                            "dw \$1234",
                            "dl \$90A000",
                            "incbin \"data/Tiles.bin\"",
                        ),
                        "The numeric values occupy 1 + 2 + 3 bytes; the binary contributes its complete file length.",
                    ),
                    table = directiveTable("db", "dw", "dl", "dd", "incsrc", "incbin", "table", "cleartable"),
                ),
                AsmLibrarySection(
                    "Build logic, reusable source, and diagnostics",
                    listOf(
                        "Conditionals and while loops run while assembling, not during gameplay. Macros expand reusable source. Namespaces and structs organize symbols and layouts. assert and print make assumptions visible during a build.",
                        "SMEDIT audits final module composition as well as isolated module output, so conditional or order-dependent module behavior cannot silently acquire unowned writes.",
                    ),
                    example = AsmCodeExample(
                        "Pin a build-time assumption",
                        code(
                            "assert NativeEntry == \$81A0A8, \"Unexpected hook address\"",
                            "if !FeatureEnabled",
                            "    db \$01",
                            "else",
                            "    db \$00",
                            "endif",
                        ),
                        "assert emits no bytes. Exactly one conditional branch emits one byte.",
                    ),
                    table = directiveTable(
                        "if", "elseif", "else", "endif", "while", "endwhile", "macro", "endmacro",
                        "namespace", "struct", "endstruct", "assert", "print", "math", "reset",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "values-and-data",
            title = "Numbers, strings, booleans, and data",
            summary = "How source notation becomes bytes in the ROM or values in CPU registers.",
            keywords = setOf("hex", "binary", "decimal", "string", "boolean", "db", "dw", "dl", "endian"),
            sections = listOf(
                AsmLibrarySection(
                    "Number notation",
                    listOf(
                        "Asar writes hexadecimal with a \$ prefix, binary with %, and decimal without a prefix. These are only different ways to write the same number: \$10, %00010000, and 16 are equal.",
                        "A leading # means immediate data: LDA #\$10 loads the value 16. Without #, LDA \$10 reads memory through a direct-page address. That one character changes the meaning completely.",
                    ),
                    AsmCodeExample(
                        "Hex, binary, and decimal are equal",
                        code(
                            "!hexValue     = \$10",
                            "!binaryValue  = %00010000",
                            "!decimalValue = 16",
                            "LDA.B #!binaryValue      ; A = 16",
                        ),
                        "The notation changes readability, not the value. Binary is especially useful when individual bits have names.",
                    ),
                ),
                AsmLibrarySection(
                    "Immediate values versus memory",
                    listOf(
                        "A leading # means use the operand itself. Without #, the same-looking number participates in an address calculation and the CPU reads memory there.",
                        "This is one of the easiest mistakes to spot in a patch: LDA #\$10 and LDA \$10 are completely different operations.",
                    ),
                    AsmCodeExample(
                        "One character changes the operation",
                        code(
                            "LDA.B #\$10     ; A = 16",
                            "LDA.B \$10      ; A = byte at direct-page address \$10",
                            "LDA.L \$7E0010  ; A = byte at explicit WRAM address \$7E:0010",
                        ),
                        "# selects immediate addressing; .B/.W/.L selects the encoded operand length or address form.",
                    ),
                ),
                AsmLibrarySection(
                    "Data declarations and byte order",
                    listOf(
                        "db, dw, and dl emit byte, 16-bit word, and 24-bit long values. The 65816 is little-endian, so multi-byte values are stored least-significant byte first.",
                        "Strings are assembler data, not a CPU type. Asar can emit characters with db, but a game may use its own character table instead of ordinary ASCII.",
                    ),
                    AsmCodeExample(
                        "Emitted ROM bytes",
                        code(
                            "db \$12             ; 12",
                            "db %10100001        ; A1",
                            "dw \$1234           ; 34 12",
                            "dl \$7E1234         ; 34 12 7E",
                        ),
                        "The source reads naturally; the ROM stores raw bytes in little-endian order.",
                    ),
                ),
                AsmLibrarySection(
                    "Strings, terminators, and pointers",
                    listOf(
                        "A quoted string asks the assembler to emit character codes. Runtime code still needs a convention for finding its end: a zero byte, an explicit length, or a fixed-size field are common choices.",
                        "Super Metroid frequently uses custom tile or font indexes rather than ordinary display text, so always inspect the reader and active character table before editing a string-like blob.",
                    ),
                    AsmCodeExample(
                        "A pointer table and zero-terminated text",
                        code(
                            "MessagePointers:",
                            "    dw ReadyMessage",
                            "    dw ErrorMessage",
                            "ReadyMessage:",
                            "    db \"READY\", \$00",
                            "ErrorMessage:",
                            "    db \"ERROR\", \$00",
                        ),
                        "dw stores addresses, while db stores the character bytes and terminators those addresses lead to.",
                    ),
                ),
                AsmLibrarySection(
                    "Booleans and bit fields",
                    listOf(
                        "The CPU has no Boolean type. Code usually treats zero as false and nonzero as true, or packs several independent on/off values into the bits of one byte or word.",
                        "AND tests or clears selected bits, ORA sets bits, and EOR toggles bits. BIT is useful when only the resulting flags matter.",
                    ),
                    AsmCodeExample(
                        "Test one flag bit",
                        code(
                            "LDA.W !roomFlags",
                            "BIT.W #\$0004",
                            "BEQ .NotSet       ; Z=1 means the selected bit was absent",
                        ),
                        "The value is a word, but bit 2 acts like one Boolean field.",
                    ),
                ),
                AsmLibrarySection(
                    "Set, clear, and toggle named bits",
                    listOf(
                        "Binary constants make bit intent visible. ORA sets selected bits, AND with an inverted mask clears them, and EOR toggles them. The stored byte remains an ordinary integer.",
                    ),
                    AsmCodeExample(
                        "Three Boolean operations",
                        code(
                            "!doorOpen = %00000001",
                            "!alarmOn  = %00000100",
                            "LDA.B !state",
                            "ORA.B #!doorOpen        ; set bit 0",
                            "AND.B #%11111011        ; clear bit 2",
                            "EOR.B #!alarmOn         ; toggle bit 2",
                            "STA.B !state",
                        ),
                        "The masks say exactly which independent flags may change.",
                    ),
                ),
                AsmLibrarySection(
                    "Signed values are still bit patterns",
                    listOf(
                        "There is no separate signed storage type. The same byte can mean 255 unsigned or -1 signed; the instruction and surrounding comparison decide the interpretation.",
                        "Negative values use two's-complement representation. The N flag merely reflects the result's top bit—it does not permanently mark a variable as signed.",
                    ),
                    AsmCodeExample(
                        "Two's-complement data",
                        code(
                            "db -1       ; FF",
                            "db -2       ; FE",
                            "dw -16      ; F0 FF",
                            "LDA.B #\$FF  ; the same bits can be 255 or -1",
                        ),
                        "Signedness is a reading convention layered over the stored bits.",
                    ),
                ),
                AsmLibrarySection(
                    "Tables and byte offsets",
                    listOf(
                        "Tables are consecutive data entries. X and Y indexes are byte offsets, so a word table advances by two and a long-pointer table advances by three.",
                    ),
                    AsmCodeExample(
                        "Read the third word",
                        code(
                            "DamageTable:",
                            "    dw 10, 20, 40, 80",
                            "LDX.W #\$0004          ; entry 2 × 2 bytes",
                            "LDA.W DamageTable,X    ; A = 40",
                        ),
                        "The third entry begins four bytes after the table label.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "quick-reference-tables",
            title = "Quick-reference tables",
            summary = "Common values, widths, flags, control flow, and Super Metroid's interrupt vectors at a glance.",
            keywords = setOf(
                "table", "decimal", "hex", "binary", "bits", "flags", "status", "vector",
                "interrupt", "nmi", "irq", "reset", "brk", "cop", "jsr", "jsl", "rts", "rtl", "rti",
            ),
            sections = listOf(
                AsmLibrarySection(
                    title = "Decimal, Asar hex, and Asar binary",
                    paragraphs = listOf(
                        "Every row is the same integer written three ways. Super Metroid source normally uses Asar's \$ prefix for hexadecimal and % prefix for binary; unprefixed digits are decimal. Eight binary digits make the individual bits of one byte easy to count.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Decimal", "Hex", "Binary", "Common use"),
                        rows = listOf(
                            numberBaseRow(0, "zero / false"),
                            numberBaseRow(1, "bit 0 / true"),
                            numberBaseRow(2, "bit 1"),
                            numberBaseRow(3),
                            numberBaseRow(4, "bit 2"),
                            numberBaseRow(5),
                            numberBaseRow(6),
                            numberBaseRow(7),
                            numberBaseRow(8, "bit 3"),
                            numberBaseRow(9),
                            numberBaseRow(10),
                            numberBaseRow(11),
                            numberBaseRow(12),
                            numberBaseRow(13),
                            numberBaseRow(14),
                            numberBaseRow(15, "low nibble mask"),
                            numberBaseRow(16, "bit 4"),
                            numberBaseRow(31, "low 5-bit mask"),
                            numberBaseRow(32, "bit 5 / M flag mask"),
                            numberBaseRow(63, "low 6-bit mask"),
                            numberBaseRow(64, "bit 6"),
                            numberBaseRow(127, "low 7-bit mask"),
                            numberBaseRow(128, "bit 7 / sign bit"),
                            numberBaseRow(255, "full byte mask"),
                        ),
                        monospaceColumns = setOf(0, 1, 2),
                        columnWeights = listOf(0.7f, 0.7f, 1.25f, 1.6f),
                    ),
                ),
                AsmLibrarySection(
                    title = "Storage widths and emitted data",
                    paragraphs = listOf(
                        "These are storage and address widths, not a promise about the CPU's current A/X/Y width. db, dw, and dl emit little-endian bytes; instruction suffixes select an encoding and must still agree with runtime M/X state.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Width", "Bits", "Unsigned range", "Asar data", "Typical role"),
                        rows = listOf(
                            listOf("Byte", "8", "0..255 / \$00..\$FF", "db", "flags, indexes, hardware values"),
                            listOf("Word", "16", "0..65,535 / \$0000..\$FFFF", "dw", "A/X/Y values, offsets, near pointers"),
                            listOf("Long", "24", "0..16,777,215 / \$000000..\$FFFFFF", "dl", "full SNES addresses / long pointers"),
                        ),
                        monospaceColumns = setOf(1, 2, 3),
                        columnWeights = listOf(0.7f, 0.5f, 1.55f, 0.7f, 1.8f),
                    ),
                ),
                AsmLibrarySection(
                    title = "Processor status flags",
                    paragraphs = listOf(
                        "P is usually written as NVMXDIZC from bit 7 to bit 0. REP clears selected bits and SEP sets them. The separate E flag selects emulation mode and is exchanged with carry by XCE; it is not one of these eight stored P bits.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Bit", "Mask", "Flag", "Meaning when set"),
                        rows = listOf(
                            listOf("7", "\$80", "N", "negative / result top bit is 1"),
                            listOf("6", "\$40", "V", "signed overflow"),
                            listOf("5", "\$20", "M", "8-bit accumulator and memory operations"),
                            listOf("4", "\$10", "X", "8-bit X and Y indexes"),
                            listOf("3", "\$08", "D", "BCD decimal arithmetic enabled"),
                            listOf("2", "\$04", "I", "maskable IRQ disabled"),
                            listOf("1", "\$02", "Z", "result was zero"),
                            listOf("0", "\$01", "C", "carry set / no borrow / XCE input"),
                        ),
                        monospaceColumns = setOf(0, 1, 2),
                        columnWeights = listOf(0.5f, 0.65f, 0.5f, 2.4f),
                    ),
                ),
                AsmLibrarySection(
                    title = "Calls, jumps, and matching returns",
                    paragraphs = listOf(
                        "A return instruction must match the kind of return address already on the stack. JMP and JML are transfers, not calls, so they do not push a new return address.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Entry", "Reach", "Pushes return", "Matching exit"),
                        rows = listOf(
                            listOf("JSR", "same program bank", "16-bit PC", "RTS"),
                            listOf("JSL", "any bank", "24-bit PBR:PC", "RTL"),
                            listOf("Interrupt", "hardware vector", "P and return address", "RTI"),
                            listOf("JMP", "same program bank", "nothing", "destination owns flow"),
                            listOf("JML", "any bank", "nothing", "destination owns flow"),
                        ),
                        monospaceColumns = setOf(0, 3),
                        columnWeights = listOf(0.7f, 1.25f, 1.35f, 1.35f),
                    ),
                ),
                AsmLibrarySection(
                    title = "Super Metroid interrupt vectors",
                    paragraphs = listOf(
                        "The ROM header and vectors are authored at the FastROM mirror \$80:FFC0; the CPU vector window is mirrored at \$00:FFE0..FFFF. Each vector stores only a 16-bit handler offset. RESET begins in emulation mode, while Super Metroid switches to native mode during Boot and uses the native NMI and IRQ entries during normal play.",
                        "Reserved slots are named in the disassembly for a complete header layout, but the CPU does not dispatch a distinct event through them.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Mode", "Vector", "CPU event", "Vanilla target"),
                        rows = listOf(
                            listOf("Native", "\$80:FFE4", "COP", "Crash_Handler"),
                            listOf("Native", "\$80:FFE6", "BRK", "Crash_Handler"),
                            listOf("Native", "\$80:FFE8", "ABORT", "Crash_Handler"),
                            listOf("Native", "\$80:FFEA", "NMI", "NMI"),
                            listOf("Native", "\$80:FFEC", "Reserved", "Crash_Handler"),
                            listOf("Native", "\$80:FFEE", "IRQ", "IRQ"),
                            listOf("Emulation", "\$80:FFF4", "COP", "Crash_Handler"),
                            listOf("Emulation", "\$80:FFF6", "Reserved", "Crash_Handler"),
                            listOf("Emulation", "\$80:FFF8", "ABORT", "Crash_Handler"),
                            listOf("Emulation", "\$80:FFFA", "NMI", "Crash_Handler"),
                            listOf("Emulation", "\$80:FFFC", "RESET", "Boot"),
                            listOf("Emulation", "\$80:FFFE", "IRQ / BRK", "Crash_Handler"),
                        ),
                        monospaceColumns = setOf(1, 2, 3),
                        columnWeights = listOf(0.9f, 1f, 1f, 1.45f),
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "cpu-model",
            title = "65816 mental model",
            summary = "Registers, banks, and the processor state you need while following a routine.",
            keywords = setOf("register", "accumulator", "index", "stack", "bank", "dbr", "pbr", "direct page"),
            sections = listOf(
                AsmLibrarySection(
                    "The working registers",
                    listOf(
                        "A is the accumulator used by most arithmetic and memory operations. X and Y are commonly indexes, counters, or offsets. S is the stack pointer and D is the direct-page base.",
                        "P holds processor flags. DBR supplies the bank for many data accesses, while PBR is the bank currently executing code. The full SNES address is bank:offset, such as \$7E:1234.",
                    ),
                    AsmCodeExample(
                        "A typical division of labor",
                        code(
                            "LDX.W !enemyIndex",
                            "LDA.W !enemyHealth,X",
                            "BEQ .AlreadyDead",
                            "DEC A",
                            "STA.W !enemyHealth,X",
                        ),
                        "X selects an enemy record, A carries its health value, and the status flags feed the branch.",
                    ),
                ),
                AsmLibrarySection(
                    "An 8/16-bit CPU with 24-bit addresses",
                    listOf(
                        "The 65816 is not a 24-bit arithmetic CPU. A, X, and Y operate as 8- or 16-bit registers, while its address space is 24 bits. Long addressing includes a bank byte; ordinary absolute addressing relies on the current bank context.",
                        "When reading unfamiliar code, track register width and bank context before deciding what an operand means.",
                    ),
                ),
                AsmLibrarySection(
                    "Program bank versus data bank",
                    listOf(
                        "PBR supplies the bank for fetched instructions and near jumps/calls. DBR supplies the bank for most 16-bit absolute data operands. This is why code can execute in bank \$A0 while LDA.W SomeTable reads a table in a deliberately selected data bank.",
                        "A common prologue temporarily makes DBR equal to the current program bank. PHB preserves the caller's DBR, PHK pushes PBR, and PLB installs that byte as DBR.",
                    ),
                    AsmCodeExample(
                        "Use the current code bank for data",
                        code(
                            "PHB                ; save caller's DBR",
                            "PHK                ; push this routine's PBR",
                            "PLB                ; DBR = PBR",
                            "LDA.W LocalTable,X",
                            "PLB                ; restore caller's DBR",
                            "RTL",
                        ),
                        "The two PLB instructions do different jobs because the stack contents changed between them.",
                    ),
                ),
                AsmLibrarySection(
                    "Direct page is a movable fast window",
                    listOf(
                        "A .B memory operand contains only an 8-bit offset. The CPU adds the D register to find the effective 16-bit address. Super Metroid gives frequently used temporary variables direct-page names such as DP_Temp12.",
                        "Changing D can make compact code fast, but it also changes the meaning of every direct-page access until D is restored.",
                    ),
                    AsmCodeExample(
                        "Temporarily move direct page",
                        code(
                            "PHD",
                            "REP #\$20",
                            "LDA.W #\$0A00",
                            "TCD",
                            "LDA.B \$12          ; reads \$0A12",
                            "PLD",
                        ),
                        "PHD/PLD brackets the change so the caller's direct-page base survives.",
                    ),
                ),
                AsmLibrarySection(
                    "Recognize major SNES address regions",
                    listOf(
                        "Banks \$7E–\$7F are work RAM. LoROM source commonly executes from the upper halves of ROM banks such as \$80:8000. Registers around \$2100 control the PPU when addressed through a hardware-mapped bank.",
                        "Mirrors make some addresses reachable through more than one bank. Prefer named labels and inspect the current DBR rather than guessing from a four-digit operand alone.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "widths-and-suffixes",
            title = "8/16-bit state and .B/.W/.L",
            summary = "Separate the CPU's register width from the assembler's chosen instruction encoding.",
            keywords = setOf("rep", "sep", "m flag", "x flag", ".b", ".w", ".l", "width"),
            sections = listOf(
                AsmLibrarySection(
                    "REP and SEP change CPU state",
                    listOf(
                        "The M flag controls accumulator width and the X flag controls X/Y width. REP clears selected status bits; SEP sets them. REP #\$20 makes A 16-bit, while SEP #\$20 makes A 8-bit. REP/SEP #\$10 do the same for X and Y.",
                        "Width is runtime state. A subroutine may inherit it from its caller unless its calling convention or setup makes it explicit.",
                    ),
                    AsmCodeExample(
                        "Load a 16-bit value, then return to 8-bit A",
                        code(
                            "REP #\$20        ; M=0: 16-bit accumulator",
                            "LDA.W #\$1234   ; two-byte immediate",
                            "STA.W \$0A00",
                            "SEP #\$20        ; M=1: 8-bit accumulator",
                        ),
                        "The suffix tells Asar what to encode; REP and SEP tell the CPU how to interpret it.",
                    ),
                ),
                AsmLibrarySection(
                    "Asar's length suffixes",
                    listOf(
                        ".B, .W, and .L force byte, word, or long instruction forms when the encoding could be ambiguous. They are assembler instructions about emitted length—not separate 65816 operations.",
                        "For an immediate load, .B or .W selects one or two immediate bytes. For a memory address, .B commonly selects direct page, .W selects a 16-bit absolute address, and .L selects a 24-bit long address when that instruction supports one.",
                        ".L does not mean 32-bit arithmetic. It normally means an encoding containing a 24-bit address.",
                    ),
                    AsmCodeExample(
                        "Three address sizes",
                        code(
                            "LDA.B \$12        ; direct page / one address byte",
                            "LDA.W \$1234      ; absolute / two address bytes",
                            "LDA.L \$7E1234    ; absolute long / three address bytes",
                        ),
                        "All three load A; they differ in how the source address is encoded and resolved.",
                    ),
                ),
                AsmLibrarySection(
                    "Change A and X/Y together or separately",
                    listOf(
                        "The mask can change both width flags at once. REP #\$30 makes A, X, and Y 16-bit; SEP #\$30 makes all three 8-bit. Masks #\$20 and #\$10 change only one side.",
                        "An 8-bit write to A changes its low byte while the hidden high byte remains in the 16-bit C register. X and Y behave differently: switching them to 8-bit clears their high bytes.",
                    ),
                    AsmCodeExample(
                        "A 16-bit table loop with an 8-bit payload",
                        code(
                            "REP #\$30        ; 16-bit A, X, and Y",
                            "LDX.W #\$0000",
                            "SEP #\$20        ; A becomes 8-bit; X/Y stay 16-bit",
                            ".Loop",
                            "    LDA.B Table,X",
                            "    STA.B !output,X",
                            "    INX",
                            "    CPX.W #\$0100",
                            "    BNE .Loop",
                        ),
                        "Mixed width is normal: the payload is a byte while the index can traverse more than 256 bytes.",
                    ),
                ),
                AsmLibrarySection(
                    "The dangerous immediate-width mismatch",
                    listOf(
                        "For immediate A/X/Y operations, the CPU decides operand width from M or X. Asar only emits the bytes requested by the suffix. If those disagree, the CPU consumes the wrong number of bytes and starts interpreting operand data as opcodes.",
                        "This is why explicit suffixes are valuable but not sufficient: you must also understand the runtime width state.",
                    ),
                    AsmCodeExample(
                        "Do not pair these states",
                        code(
                            "SEP #\$20          ; A is 8-bit",
                            "LDA.W #\$1234     ; WRONG: CPU consumes only \$34",
                            "; \$12 becomes the next opcode and execution derails",
                        ),
                        "The source can assemble successfully while still being invalid for the CPU state.",
                    ),
                ),
                AsmLibrarySection(
                    "Preserve a caller's unknown status",
                    listOf(
                        "PHP pushes P, including M and X. PLP restores it. This is useful when a helper needs a known width internally but promises not to change the caller's flags.",
                        "After PLP, the very next immediate instruction must already match the restored state; the assembler does not track that state for you.",
                    ),
                    AsmCodeExample(
                        "A width-safe helper",
                        code(
                            "PHP",
                            "REP #\$30",
                            "LDA.W !value",
                            "LDX.W !index",
                            "JSR DoWork16Bit",
                            "PLP",
                            "RTS",
                        ),
                        "This restores all status flags, not only M and X, so document whether the caller expected arithmetic flags as outputs.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "addressing-modes",
            title = "Addressing modes and pointers",
            summary = "Read operands as recipes for finding data, not merely as numbers.",
            keywords = setOf("immediate", "absolute", "long", "indirect", "indexed", "pointer", "direct page"),
            sections = listOf(
                AsmLibrarySection(
                    "Common forms",
                    listOf(
                        "Immediate #value uses the value itself. addr reads or writes memory. addr,X and addr,Y add an index. Parentheses dereference a 16-bit pointer; brackets dereference a 24-bit long pointer.",
                        "Direct-page operands are compact and fast but depend on D. Stack-relative operands such as offset,S address temporary values near the current stack pointer.",
                    ),
                    AsmCodeExample(
                        "Several ways to load A",
                        code(
                            "LDA.W #\$0040     ; immediate value",
                            "LDA.W Table,X    ; indexed table entry",
                            "LDA.B (\$12),Y    ; 16-bit pointer from direct page, then add Y",
                            "LDA.B [\$20],Y    ; 24-bit pointer from direct page, then add Y",
                        ),
                        "The punctuation describes each address calculation.",
                    ),
                ),
                AsmLibrarySection(
                    "Indexed tables use byte offsets",
                    listOf(
                        "addr,X and addr,Y add the full index to the base address. The CPU does not know the table's element size, so code increments by 1 for bytes, 2 for words, and 3 for packed 24-bit pointers.",
                    ),
                    AsmCodeExample(
                        "Walk a word table",
                        code(
                            "LDX.W #\$0000",
                            ".Loop",
                            "    LDA.W HealthTable,X",
                            "    JSR ProcessHealth",
                            "    INX",
                            "    INX",
                            "    CPX.W #HealthTableEnd-HealthTable",
                            "    BNE .Loop",
                            "HealthTable:",
                            "    dw 10, 20, 40, 80",
                            "HealthTableEnd:",
                        ),
                        "X advances twice because each entry emitted by dw occupies two bytes.",
                    ),
                ),
                AsmLibrarySection(
                    "16-bit pointers versus 24-bit pointers",
                    listOf(
                        "Parentheses around a direct-page pointer read a 16-bit address; the data bank supplies the bank. Brackets read a full 24-bit address, so the pointer itself includes the bank.",
                        "The ,Y suffix applies Y after dereferencing and is common for walking a structure or stream through a pointer.",
                    ),
                    AsmCodeExample(
                        "Follow two kinds of pointer",
                        code(
                            "LDY.W #\$0006",
                            "LDA.B (\$12),Y    ; pointer at D+\$12, data from DBR:address+Y",
                            "LDA.B [\$20],Y    ; long pointer at D+\$20, explicit bank+address+Y",
                        ),
                        "The brackets make the target independent of DBR, but the pointer storage still lives in direct page.",
                    ),
                ),
                AsmLibrarySection(
                    "Stack-relative arguments",
                    listOf(
                        "offset,S addresses bytes relative to the current stack pointer. (offset,S),Y first reads a pointer from the stack-relative location and then indexes through it.",
                        "These forms are compact for temporary arguments, but every push changes S. Count the return address and saved registers before assigning names to offsets.",
                    ),
                    AsmCodeExample(
                        "Read a stacked temporary",
                        code(
                            "PHA",
                            "LDA.B \$03,S      ; read relative to the current stack layout",
                            "JSR UseValue",
                            "PLA",
                        ),
                        "The meaning of \$03,S depends entirely on what the caller and callee have already pushed.",
                    ),
                ),
                AsmLibrarySection(
                    "Bank boundaries matter",
                    listOf(
                        "A 16-bit absolute operand cannot choose a new bank. A 24-bit long operand can. Indexed accesses that cross a bank boundary may wrap or cost extra cycles depending on the addressing mode.",
                        "When a table may outgrow a bank, treat its placement and pointer type as part of the data format rather than a cosmetic source-code choice.",
                    ),
                    AsmCodeExample(
                        "Implicit and explicit banks",
                        code(
                            "LDA.W \$1234      ; DBR supplies the bank",
                            "LDA.L \$7E1234    ; bank \$7E is encoded explicitly",
                        ),
                        "The two operands can refer to the same byte only when DBR is already \$7E.",
                    ),
                ),
                AsmLibrarySection(
                    "LoROM addresses and file offsets",
                    listOf(
                        "A debugger and ASM source usually write a SNES CPU address as bank:offset, such as \$8F:805A. A hex editor usually writes the ROM file offset instead; for that example the unheadered PC offset is 0x07805A. They identify the same byte through the LoROM mapping.",
                        "For SMEDIT's canonical \$80–\$FF ROM addresses, each bank contributes its upper \$8000 bytes. The conversion is PC = (bank & \$7F) × \$8000 + (offset - \$8000). A copier header, if present on an external ROM file, adds \$200 to file offsets; SMEDIT normalizes it away before parsing.",
                        "A 16-bit operand such as \$805A is not a complete ROM address because its bank comes from CPU state or source placement. The Atlas only links a full 24-bit operand or an address explicitly anchored by org, an authored section, or a recorded disassembly address.",
                    ),
                    AsmCodeExample(
                        "One location, two coordinate systems",
                        code(
                            "org \$8F805A             ; SNES \$8F:805A = PC 0x07805A",
                            "RoomData:",
                            "    dw \$1234",
                            "PointerTable:",
                            "    dl RoomData         ; emits a complete 24-bit pointer",
                        ),
                        "Search the ASM workspace for \$8F:805A or PC:07805A. Exact source anchors and extracted assets appear separately; a nearby anchor is labeled as context, not claimed as the exact byte.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "flags-and-branches",
            title = "Flags, comparisons, and loops",
            summary = "Most conditions are a flag-setting instruction followed by a short branch.",
            keywords = setOf("flag", "branch", "loop", "compare", "zero", "carry", "negative", "overflow"),
            sections = listOf(
                AsmLibrarySection(
                    "The flags you see constantly",
                    listOf(
                        "Z says a result was zero, N copies the result's top bit, C represents carry/no-borrow or a shifted-out bit, and V reports signed overflow. CMP, CPX, and CPY update flags as if a subtraction occurred without keeping the result.",
                        "BEQ/BNE inspect Z, BCC/BCS inspect C, BMI/BPL inspect N, and BVC/BVS inspect V. Branches are short relative jumps, so they are ideal for nearby conditions and loops.",
                    ),
                    AsmCodeExample(
                        "Count down to zero",
                        code(
                            "LDX.W #\$0004",
                            ".Loop",
                            "    JSR ProcessEntry",
                            "    DEX             ; updates Z",
                            "    BNE .Loop       ; repeat while X is not zero",
                        ),
                        "BNE consumes the Z flag produced by DEX; it does not compare X by itself.",
                    ),
                ),
                AsmLibrarySection(
                    "Equality and unsigned ordering",
                    listOf(
                        "After CMP, Z=1 means A was equal to the operand. Carry is set when A was greater than or equal in an unsigned comparison, and clear when A was lower.",
                        "BEQ/BNE can be read as equal/not-equal after a compare. BCC often means unsigned-below, while BCS often means unsigned-at-least.",
                    ),
                    AsmCodeExample(
                        "Classify an unsigned value",
                        code(
                            "REP #\$20",
                            "CMP.W #100",
                            "BCC .Below100",
                            "BEQ .Exactly100",
                            "; otherwise A is above 100",
                        ),
                        "CMP computes A - 100 only for flags; A itself remains unchanged.",
                    ),
                ),
                AsmLibrarySection(
                    "Signed comparisons need N and V together",
                    listOf(
                        "BMI only tests the result's top bit. After a subtraction that overflows, N alone can give the wrong signed ordering. Correct signed less-than logic considers N xor V.",
                        "Super Metroid often avoids a general signed compare by arranging ranges, testing a sign directly where overflow is impossible, or using known fixed-point bounds. Confirm the input range before treating BMI as “less than.”",
                    ),
                ),
                AsmLibrarySection(
                    "Flags are short-lived data flow",
                    listOf(
                        "Most arithmetic, loads, transfers, and increments replace at least N or Z. A branch usually belongs to the nearest preceding instruction that writes the flag it reads.",
                        "Moving an unrelated flag-setting instruction between CMP and BEQ silently changes the condition even though the branch text still looks sensible.",
                    ),
                    AsmCodeExample(
                        "Keep the producer next to the consumer",
                        code(
                            "LDA.W !health",
                            "CMP.W #\$0000",
                            "BEQ .Dead          ; consumes CMP's Z",
                            "JSR UpdateEnemy",
                        ),
                        "Treat CMP + branch as one logical unit while reviewing edits.",
                    ),
                ),
                AsmLibrarySection(
                    "Carry connects multi-word arithmetic",
                    listOf(
                        "ADC includes C as an input and SBC uses inverted borrow. Clear carry before a fresh addition and set carry before a fresh subtraction; leave it alone between low-word and high-word operations.",
                    ),
                    AsmCodeExample(
                        "Add one to a 32-bit value",
                        code(
                            "CLC",
                            "LDA.W !valueLow",
                            "ADC.W #\$0001",
                            "STA.W !valueLow",
                            "LDA.W !valueHigh",
                            "ADC.W #\$0000      ; includes carry from the low word",
                            "STA.W !valueHigh",
                        ),
                        "The second ADC propagates overflow from the low 16 bits.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "calls-and-stack",
            title = "Calls, returns, and the stack",
            summary = "Match call distance, return type, and register preservation.",
            keywords = setOf("jsr", "jsl", "rts", "rtl", "stack", "push", "pull", "calling convention"),
            sections = listOf(
                AsmLibrarySection(
                    "Near and long calls",
                    listOf(
                        "JSR calls within the current program bank and returns with RTS. JSL records a full 24-bit return address and returns with RTL. Mixing these pairs corrupts the stack and usually crashes quickly.",
                        "PHA/PLA, PHX/PLX, and PHY/PLY preserve working registers. PHP/PLP preserves status flags, including width state. Push and pull in reverse order.",
                    ),
                    AsmCodeExample(
                        "Preserve the caller's registers",
                        code(
                            "PHA",
                            "PHX",
                            "JSL SomeRoutineInAnotherBank",
                            "PLX",
                            "PLA",
                            "RTS",
                        ),
                        "The inner routine returns with RTL; this wrapper eventually returns to its own JSR caller with RTS.",
                    ),
                ),
                AsmLibrarySection(
                    "Calling conventions are local agreements",
                    listOf(
                        "The CPU defines where a return address goes, but it does not define function arguments or preserved registers. Super Metroid routines commonly accept values in A/X/Y, direct-page temporaries, or known WRAM fields.",
                        "Read the routine's parameter comment and several call sites. A register that looks like an input at one call may merely be leftover state unless the callee actually reads it before replacing it.",
                    ),
                    AsmCodeExample(
                        "Registers in, carry flag out",
                        code(
                            "LDA.W #\$0040      ; amount",
                            "LDX.W !enemyIndex ; target",
                            "JSL ApplyDamage",
                            "BCS .Rejected     ; this routine documents C as its result",
                        ),
                        "This convention is meaningful only because ApplyDamage and its callers agree on it.",
                    ),
                ),
                AsmLibrarySection(
                    "Balance the stack in reverse order",
                    listOf(
                        "The stack is last-in, first-out. Every path to a return must remove the same saved values in reverse order. A branch that skips one PLA or PLB shifts the return address and usually crashes.",
                        "Register width affects PHA/PLA and PHX/PLX sizes, which is another reason to establish or preserve M/X before designing a stack frame.",
                    ),
                    AsmCodeExample(
                        "A balanced long routine",
                        code(
                            "PHP",
                            "REP #\$30",
                            "PHB",
                            "PHX",
                            "JSR LocalWork",
                            "PLX",
                            "PLB",
                            "PLP",
                            "RTL",
                        ),
                        "The pulls exactly reverse the pushes; RTL then finds the long return address where expected.",
                    ),
                ),
                AsmLibrarySection(
                    "Tail jumps do not add another return",
                    listOf(
                        "A routine can JMP or JML to another routine after finishing its own work. The destination eventually returns directly to the original caller because no new return address was pushed.",
                        "This is useful for shared endings and dispatch, but the jump distance and final return type must still match the return address already on the stack.",
                    ),
                    AsmCodeExample(
                        "Share an RTS ending",
                        code(
                            "HandleOpenDoor:",
                            "    LDA.W #\$0001",
                            "    STA.W !doorState",
                            "    JMP FinishDoorUpdate",
                            "FinishDoorUpdate:",
                            "    JSR DrawDoor",
                            "    RTS",
                        ),
                        "FinishDoorUpdate returns to HandleOpenDoor's original JSR caller.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "snes-runtime",
            title = "SNES runtime patterns",
            summary = "Why game code queues work, writes hardware registers carefully, and uses DMA.",
            keywords = setOf("snes", "nmi", "vblank", "ppu", "dma", "hdma", "wram", "register"),
            sections = listOf(
                AsmLibrarySection(
                    "Frames, VBlank, and hardware",
                    listOf(
                        "Much of Super Metroid updates game state in WRAM, then copies prepared graphics or register values during VBlank. NMI marks that safe display interval. Writing VRAM or certain PPU state at arbitrary times can produce corruption or be ignored.",
                        "DMA efficiently transfers blocks such as tiles, palettes, and tilemaps. HDMA performs small synchronized transfers across scanlines for gradients, windowing, and other effects.",
                    ),
                    AsmCodeExample(
                        "Queue work instead of touching VRAM immediately",
                        code(
                            "LDA.W #TileData",
                            "STA.W !vramQueueSource",
                            "LDA.W #\$0400",
                            "STA.W !vramQueueSize",
                            "LDA.B #\$01",
                            "STA.B !vramUpdatePending",
                        ),
                        "Gameplay code prepares a request in RAM; the NMI-side consumer performs the hardware transfer during VBlank.",
                    ),
                ),
                AsmLibrarySection(
                    "Memory-mapped I/O looks like ordinary memory",
                    listOf(
                        "The CPU communicates with the PPU, APU ports, controllers, timers, and DMA channels by loading and storing special addresses. The instruction is ordinary LDA/STA; the address determines that hardware reacts.",
                        "Register width matters. Many PPU registers are eight bits, and some 16-bit values are written as two ordered byte writes to the same port.",
                    ),
                    AsmCodeExample(
                        "Write the display-control register",
                        code(
                            "SEP #\$20",
                            "LDA.B #\$0F",
                            "STA.W \$2100       ; INIDISP: display on, maximum brightness",
                        ),
                        "This assumes DBR addresses a hardware-mapped bank. Named register labels are preferable when available.",
                    ),
                ),
                AsmLibrarySection(
                    "A useful reading habit",
                    listOf(
                        "When a routine writes an address in banks \$00–\$3F or \$80–\$BF, determine whether it is ordinary mapped memory or an SNES hardware register. When it builds a table in WRAM, look for the later NMI or DMA consumer before assuming the change appears immediately.",
                    ),
                ),
                AsmLibrarySection(
                    "DMA is a configured copy engine",
                    listOf(
                        "A DMA setup chooses a transfer mode, destination hardware register, source bank/address, and byte count, then enables a channel. The CPU pauses while normal DMA runs, but the transfer is far faster than a software byte loop.",
                        "HDMA uses per-scanline tables and runs during horizontal blanking. Its table format and indirect/direct mode must match the channel setup exactly.",
                    ),
                ),
                AsmLibrarySection(
                    "Read game code as a frame pipeline",
                    listOf(
                        "A useful mental sequence is input → simulation → object/enemy updates → queued rendering/audio work → NMI hardware commit. Individual Super Metroid banks split that work across dispatch tables and state machines, but the dependency still flows across the frame.",
                        "When an edit seems to have no visual effect, search for the later copier, queue consumer, or cached mirror rather than assuming the write failed.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "hooks-and-overrides",
            title = "Hooks, overrides, and free space",
            summary = "How project modules safely replace native bytes, redirect execution, and place new code without accidental collisions.",
            keywords = setOf(
                "hook", "detour", "override", "org", "freespace", "module", "collision",
                "jsr", "jsl", "jmp", "jml", "rts", "rtl", "warnpc", "displaced instruction",
            ),
            sections = listOf(
                AsmLibrarySection(
                    "Four different kinds of source change",
                    listOf(
                        "An org directive only moves Asar's output cursor; it does not reserve or allocate space. What follows determines the kind of edit. A direct data override replaces table bytes, an in-place code patch replaces instructions and continues nearby, a hook redirects control to another routine, and a pointer/table redirection changes which routine data-driven code selects.",
                        "All four are binary overrides mechanically. A collision is different: two independent owners claim the same bytes without one explicit contract. An intentional hook is not a collision merely because it replaces native code.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Pattern", "Typical source", "Meaning"),
                        rows = listOf(
                            listOf("Data override", "org … / dw …", "Replace fixed data; no control transfer"),
                            listOf("In-place patch", "org … / LDA …", "Replace code inside the available envelope"),
                            listOf("Hook / detour", "org … / JML Helper", "Leave native flow for custom code"),
                            listOf("Pointer redirect", "org … / dw Handler", "Select a different routine through data"),
                        ),
                        monospaceColumns = setOf(1),
                        columnWeights = listOf(0.8f, 1.0f, 1.8f),
                    ),
                ),
                AsmLibrarySection(
                    "The complete hook shape",
                    listOf(
                        "A hook has two pieces: a small control-transfer instruction at a verified native entry, and custom code in verified free space. The custom code must preserve the native contract—register widths, flags, registers, stack balance, data bank, displaced work, and the exact continuation or return path expected by callers.",
                        "Code placed in free space without a call, jump, pointer, or dispatcher entry is unreachable. Conversely, a jump at a native address without a valid destination destroys the original path. Treat the two pieces as one reviewed change.",
                    ),
                    AsmCodeExample(
                        "A same-bank detour",
                        code(
                            "org NativeEntry",
                            "    JMP.W CustomHelper",
                            "",
                            "org VerifiedFreeSpace",
                            "CustomHelper:",
                            "    ; preserve the native contract",
                            "    JMP.W NativeContinuation",
                        ),
                        "The first JMP makes the helper reachable; the second tail-jumps back into the native routine without adding a return address.",
                    ),
                ),
                AsmLibrarySection(
                    "Overwrite complete instructions",
                    listOf(
                        "A hook envelope must cover whole native instructions and be at least as large as the chosen transfer. JSR.W and JMP.W use three bytes; JSL.L and JML.L use four. Never cut through an operand merely because the byte count is convenient.",
                        "If the transfer is shorter than the complete envelope, explicitly fill or replace the remaining bytes. Any overwritten instruction that callers still require is displaced work: execute an equivalent instruction in the helper before continuing. The checked demo replaces one complete three-byte LDA and deliberately repeats that LDA in its helper.",
                    ),
                    AsmCodeExample(
                        "Displace, then resume",
                        code(
                            "; Native entry originally contains: LDA.W Energy (3 bytes)",
                            "org LowEnergyCheck",
                            "    JMP.W CustomThreshold       ; also 3 bytes",
                            "CustomThreshold:",
                            "    LDA.W Energy                ; displaced instruction",
                            "    JMP.W LowEnergyCheck+$0008 ; chosen continuation",
                        ),
                        "Equal instruction sizes make this envelope exact, but the continuation still must be derived from the native control flow rather than guessed.",
                    ),
                ),
                AsmLibrarySection(
                    "Match transfer distance and return type",
                    listOf(
                        "JSR/JMP encode a 16-bit destination and stay in the current program bank. JSL/JML encode a 24-bit destination and can cross banks. Calls push a return address; jumps do not. The routine that eventually returns must match the call that created the active return address.",
                        "A helper reached by JSR normally ends in RTS; a helper reached by JSL normally ends in RTL. A helper reached by JMP or JML often tail-jumps to native code, allowing the native RTS or RTL to return to the original caller. Do not add RTS merely because the helper looks like a subroutine.",
                    ),
                    table = AsmLibraryTable(
                        headers = listOf("Transfer", "Reach", "Pushes return?", "Normal matching end"),
                        rows = listOf(
                            listOf("JSR.W", "Same bank", "Yes, 16-bit", "RTS"),
                            listOf("JSL.L", "Any ROM bank", "Yes, 24-bit", "RTL"),
                            listOf("JMP.W", "Same bank", "No", "Tail path owns return"),
                            listOf("JML.L", "Any ROM bank", "No", "Tail path owns return"),
                        ),
                        monospaceColumns = setOf(0, 3),
                        columnWeights = listOf(0.7f, 1.0f, 1.0f, 1.4f),
                    ),
                ),
                AsmLibrarySection(
                    "Free space is a checked range, not a magic label",
                    listOf(
                        "A Freespace_* label documents where unused bytes began in the pinned vanilla source. It does not stop another module from choosing the same bytes, and it does not move automatically when custom code grows. Module order can make a later org silently overwrite an earlier allocation unless SMEDIT has an explicit checked contract.",
                        "Keep same-bank helpers below the bank boundary and place warnpc immediately after the owned payload. warnpc makes Asar fail when growth reaches the limit. SMEDIT's checked modules additionally compare a build with the module disabled, proving the proposed helper bytes were still free after earlier modules assembled.",
                    ),
                    AsmCodeExample(
                        "Bound one bank-local allocation",
                        code(
                            "org Freespace_Bank90_F63A",
                            "CustomHelper:",
                            "    ; code",
                            "CustomHelper_End:",
                            "warnpc $918000",
                        ),
                        "The labels expose the exact owned span; warnpc prevents the payload from crossing the end of bank $90.",
                    ),
                ),
                AsmLibrarySection(
                    "Module order and collisions",
                    listOf(
                        "Enabled Project Modules assemble from top to bottom after the pinned source. That order is meaningful: a later org can replace bytes emitted earlier. Reordering is not allocation, dependency resolution, or automatic conflict merging.",
                        "SMEDIT now compiles each enabled module independently against one module-free baseline. That makes overlapping module ownership visible even when both modules emit the same replacement bytes. A module also cannot silently overwrite a project bank edit or editor-generated asset/room/patch span.",
                        "The final ordered build must reproduce every independently audited module byte-for-byte and may not gain an unclaimed write through read-dependent or conditional interaction with another module. Module order remains useful for explicit include order, but it cannot be used to hide an overwrite.",
                        "A checked hook adds a stricter contract: it fails closed when its native bytes changed, its helper left verified free space, a boundary label moved, its displaced prefix or final continuation changed, or it emits outside the declared hook/helper spans.",
                    ),
                ),
                AsmLibrarySection(
                    "Create a checked hook from native source",
                    listOf(
                        "Build the project and Space Planner, then right-click the first native instruction and choose Create Checked Hook. SMEDIT collects complete contiguous instructions until a four-byte JML fits, rejects unsafe control flow and interior labels, and prefers a verified expanded-bank block for the helper.",
                        "The generated Project Module contains the detour, displaced instructions, an obvious custom-code area, the exact continuation jump, boundary labels, warnpc, and a compact safety metadata comment. Keep that metadata and the structural lines intact; edit only the custom area unless you intend to recreate the hook.",
                        "The Space Planner lists total verified bytes and the largest contiguous block separately. A hook or allocation must fit one listed block; fragmented totals are never treated as one address range.",
                    ),
                    AsmCodeExample(
                        "Generated shape",
                        code(
                            "; @smedit-checked-hook-v1 ...",
                            "org NativeInstruction",
                            "Generated_Hook:",
                            "    JML.L Generated_Helper",
                            "",
                            "org VerifiedExpandedSpace",
                            "Generated_Helper:",
                            "    ; displaced instructions",
                            "    ; custom code",
                            "    JML.L ExactContinuation",
                            "Generated_Helper_End:",
                            "warnpc VerifiedLimit",
                        ),
                        "Every build proves this source still encodes the reviewed shape and claims both complete spans.",
                    ),
                ),
                AsmLibrarySection(
                    "How the Objectives pause-screen module is hooked",
                    listOf(
                        "The bundled Objectives example uses intentional org-based detours, not accidental address collisions. At $82:90FF it replaces the native PHP / PHB / PHK / PLB prologue with JML Objectives_MainPauseRoutine; the relocated routine repeats that displaced setup before continuing and eventually returns with RTL.",
                        "At $82:934B it replaces the native LDA.W PauseMenu_MenuMode with JMP.W Objectives_DrawPauseMenuDuringFadeOut. That replacement follows the surrounding routine's contract and returns with RTS. Its main payload currently lives at $82:F836 and is bounded by warnpc $838000.",
                        "This module is bank-$82-dependent: it uses bank-local JSR/JMP calls and 16-bit handler pointers. Moving only its payload to an expanded bank would break those assumptions. A real relocation would require reviewing and converting every bank-relative edge, not concatenating the source elsewhere.",
                    ),
                ),
                AsmLibrarySection(
                    "Review a hook at three layers",
                    listOf(
                        "First, inspect source structure: complete instruction envelope, displaced work, register/stack contract, explicit continuations, and bounded placement. Second, inspect the compiled ROM: exact transfer opcode/operand, exact helper span, no unexpected output, and owner-aware overlap checks. Third, test runtime behavior on every branch, including the unmodified path.",
                        "Compilation proves that labels resolve and instructions encode. A checked contract proves the reviewed structural assumptions still hold. Emulator testing proves the behavior. None of those layers replaces the others.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "checked-hook-demo",
            title = "Worked example: checked low-energy hook",
            summary = "A line-by-line tour of the bundled disabled module that changes the warning threshold through a verified bank-$90 detour.",
            keywords = setOf(
                "checked hook demo", "low energy", "threshold", "bank 90", "project module",
                "continuation", "freespace", "validation", "example",
            ),
            sections = listOf(
                AsmLibrarySection(
                    "What the demo changes",
                    listOf(
                        "Vanilla LowEnergyCheck warns when Energy is below $001F (31). The demo changes only that decision to below $0033 (51). It leaves the native warning flag and sound-queue logic in place, so both outcomes rejoin the original routine.",
                        "New projects receive checked_hook_demo.asm as a disabled Project Module. Disabled means it emits no bytes and changes no gameplay. Enable it explicitly in Project Modules when you want to study or test the hook.",
                    ),
                    AsmCodeExample(
                        "The one intended tuning value",
                        code(
                            "!SMEDIT_CheckedHookDemoThreshold = $0033 ; 51 energy",
                            "",
                            "LDA.W Energy",
                            "CMP.W #!SMEDIT_CheckedHookDemoThreshold",
                        ),
                        "Change the literal to experiment with another threshold; keep the checked gate and boundary labels intact.",
                    ),
                ),
                AsmLibrarySection(
                    "The native instruction envelope",
                    listOf(
                        "LowEnergyCheck starts at $90:EA7F with LDA.W Energy, a complete three-byte instruction. JMP.W is also three bytes, so the demo replaces exactly that instruction—no operand fragment, padding, or neighboring instruction is left behind.",
                        "The module asserts the expected native entry and low-energy label at assembly time. SMEDIT then independently verifies the resolved symbols and compiled JMP bytes before accepting the build.",
                    ),
                    AsmCodeExample(
                        "The checked entry detour",
                        code(
                            "org LowEnergyCheck",
                            "SMEDIT_CheckedHookDemo_Hook:",
                            "    JMP.W SMEDIT_CheckedHookDemo_Threshold",
                        ),
                        "Because the helper stays in bank $90, a three-byte bank-local jump is sufficient.",
                    ),
                ),
                AsmLibrarySection(
                    "The helper repeats displaced work",
                    listOf(
                        "The hook overwrote LDA.W Energy, so the helper performs that load first. CMP sets the flags and BMI selects the low-energy path. The helper does not call the native routine and does not push another return address.",
                        "Both branches use JMP.W tail transfers. The original LowEnergyCheck was entered through JSR by its callers, so the original routine's final RTS still consumes that original return address exactly once.",
                    ),
                    AsmCodeExample(
                        "Decision and tail paths",
                        code(
                            "SMEDIT_CheckedHookDemo_Threshold:",
                            "    LDA.W Energy",
                            "    CMP.W #!SMEDIT_CheckedHookDemoThreshold",
                            "    BMI .lowEnergy",
                            "    JMP.W LowEnergyCheck+$0008",
                            ".lowEnergy:",
                            "    JMP.W LowEnergyCheck_lowEnergy",
                            "SMEDIT_CheckedHookDemo_Threshold_End:",
                        ),
                        "Healthy energy resumes at $90:EA87, after vanilla's old compare/branch. Low energy resumes at $90:EA98, the native low-energy handler.",
                    ),
                ),
                AsmLibrarySection(
                    "Why the continuation is +$0008",
                    listOf(
                        "Vanilla's LDA.W Energy, CMP.W #$001F, and BMI .lowEnergy occupy eight bytes from $90:EA7F through $90:EA86. A healthy result must therefore resume at $90:EA87, where the native code reads CriticalEnergyWarning.",
                        "The low branch uses the named LowEnergyCheck_lowEnergy symbol rather than repeating its numeric address in the jump. Both destinations remain checked because a label resolving successfully does not by itself prove it still describes the reviewed control-flow point.",
                    ),
                ),
                AsmLibrarySection(
                    "Placement and boundaries",
                    listOf(
                        "The helper begins at Freespace_Bank90_F63A, the pinned disassembly's trailing free-space label. SMEDIT requires both helper boundary labels to resolve in bank $90, requires the end to remain below $91:8000, and proves the complete span was \$FF in both vanilla and the build with this demo disabled.",
                        "warnpc $918000 is the assembler-side guard. The _End label is the ownership-side guard: it lets SMEDIT claim the full payload and reject output elsewhere. Keep both even when changing the helper body.",
                    ),
                    AsmCodeExample(
                        "A visible allocation envelope",
                        code(
                            "org Freespace_Bank90_F63A",
                            "SMEDIT_CheckedHookDemo_Threshold:",
                            "    ; helper body",
                            "SMEDIT_CheckedHookDemo_Threshold_End:",
                            "warnpc $918000",
                        ),
                        "The span is readable in source, measurable in symbols, and bounded during assembly.",
                    ),
                ),
                AsmLibrarySection(
                    "What SMEDIT proves before export",
                    listOf(
                        "The checked build verifies the native LowEnergyCheck source is unchanged, the reviewed native labels still resolve at $90:EA7F and $90:EA98, the entry contains JMP.W to the helper, and the helper remains in verified bank-$90 free space.",
                        "It compiles a second snapshot with only this demo gated off. That detects an earlier module changing the native LDA or occupying the helper range. The demo must be the last enabled Project Module, generated assets/rooms/patches may not overlap either span, and the module may emit no bytes outside its hook and helper boundaries.",
                        "This tutorial contract permits changing the threshold word, but it deliberately rejects structural rewrites of the helper. Copy or rename it as an ordinary authored module before experimenting beyond that reviewed shape; then you own the CPU-width, flags, registers, stack balance, and runtime-branch analysis.",
                    ),
                ),
                AsmLibrarySection(
                    "Enable and test it",
                    listOf(
                        "Open ASM → Source → Project Modules, select checked_hook_demo.asm, and enable it. Keep it last among enabled modules. Build the SMEDIT result; a contract violation stops safely with a specific hook, placement, ordering, or overlap error.",
                        "In the emulator, lower energy to 50: the warning should now activate even though vanilla would wait until 30. Raise energy to 51 or more: the warning should clear through the original healthy path. Also verify ordinary pause/gameplay flow, then disable the module and confirm vanilla's below-31 behavior returns.",
                        "Use the ROM-space and ownership views to inspect the three-byte native hook and the small bank-$90 helper allocation. A successful build should attribute both complete spans to Project module checked_hook_demo.asm.",
                    ),
                ),
            ),
        ),
        AsmLibraryGuide(
            id = "samus-jump-edit",
            title = "First project edit: Samus's jump",
            summary = "Trace a real Super Metroid behavior from caller to data, make a safe project override, and define a byte-exact test plan.",
            keywords = setOf(
                "super metroid", "samus", "jump", "physics", "project", "edit", "bank 90",
                "fixed point", "velocity", "table", "spf", "compile", "verify",
            ),
            sections = listOf(
                AsmLibrarySection(
                    "Start with behavior, not a magic address",
                    listOf(
                        "Suppose you want Samus's ordinary jump to launch higher. Open Source, choose Bank \$90, and search for InitialYSpeeds_Jumping. That label leads to the physics table; Find Usages or a source search then leads back to Make_Samus_Jump, the code that consumes it.",
                        "This order matters: inspect the reader before changing its data. It tells you how each entry is selected, which related values travel together, and whether another path bypasses the table.",
                    ),
                    AsmCodeExample(
                        "The normal-jump reader",
                        code(
                            "LDA.W SamusPhysicsConstants_InitialYSubSpeeds_Jumping,X",
                            "STA.W SamusYSubSpeed",
                            "LDA.W SamusPhysicsConstants_InitialYSpeeds_Jumping,X",
                            "STA.W SamusYSpeed",
                        ),
                        "Make_Samus_Jump loads the fractional and whole-number halves of one initial vertical velocity.",
                    ),
                ),
                AsmLibrarySection(
                    "Read the selection logic",
                    listOf(
                        "Immediately above those loads, the routine assigns X = \$0000 for normal gravity, X = \$0002 for submerged water, or X = \$0004 for acid/lava. Each entry is a two-byte word, so those byte offsets select the first, second, or third word.",
                        "The Hi-Jump Boots branch reads InitialYSpeeds_HiJumpJumping and InitialYSubSpeeds_HiJumpJumping instead. Editing the ordinary table therefore changes a no-Hi-Jump launch, not every kind of jump in the game.",
                    ),
                    AsmCodeExample(
                        "X selects the environment",
                        code(
                            ".normalGravity:",
                            "    LDX.W #\$0000    ; first word: air",
                            ".submergedInWater:",
                            "    LDX.W #\$0002    ; second word: water",
                            ".submergedInAcidLava:",
                            "    LDX.W #\$0004    ; third word: acid/lava",
                        ),
                        "These are byte offsets into a word table, not three arbitrary mode numbers.",
                    ),
                ),
                AsmLibrarySection(
                    "One velocity is stored in two words",
                    listOf(
                        "The source expresses launch velocity as an 8.8 fixed-point value. On NTSC, \$04E0 means 4 + \$E0/256 = 4.875 pixels per frame. The assembler expressions split it into SamusYSpeed and SamusYSubSpeed, which the runtime stores separately.",
                        "Change the matching entry in both rows. Editing only the whole-number row or only the fractional row creates a different value than the source appears to describe. !SPF is 1 for NTSC and 6/5 for PAL, so retaining the expression also retains the project's regional timing adjustment.",
                    ),
                    AsmCodeExample(
                        "Vanilla ordinary-jump tables at \$90:9EB9",
                        code(
                            ".InitialYSpeeds_Jumping:",
                            "    dw \$04E0*!SPF/\$100,\$01C0*!SPF/\$100,\$02C0*!SPF/\$100",
                            ".InitialYSubSpeeds_Jumping:",
                            "    dw \$04E0*!SPF*\$100,\$01C0*!SPF*\$100,\$02C0*!SPF*\$100",
                        ),
                        "The columns are air, submerged water, and acid/lava. The paired rows are the whole and fractional halves.",
                    ),
                ),
                AsmLibrarySection(
                    "Make a conservative project override",
                    listOf(
                        "Code examples on this Library page are read-only teaching material. To edit the real file, choose Source at the top of the ASM sidebar, enable Project ASM from the bottom, and open Project (editable). Enabling Project ASM selects it automatically; Original (read-only) remains available for comparison.",
                        "Project source is directly editable: open Bank \$90 / bank_90.asm, search for InitialYSpeeds_Jumping, click into the source text, replace the first \$04E0 in both ordinary-jump rows with \$0600, then press Cmd/Ctrl+S or use Save. There is no separate per-file Edit button.",
                        "Original (read-only) remains an immutable baseline. Project owns the saved override and marks the source as edited, so you can compare the two views or restore the original without redownloading the disassembly.",
                    ),
                    AsmCodeExample(
                        "Use the vanilla Hi-Jump launch value for ordinary air jumps",
                        code(
                            ".InitialYSpeeds_Jumping:",
                            "    dw \$0600*!SPF/\$100,\$01C0*!SPF/\$100,\$02C0*!SPF/\$100",
                            ".InitialYSubSpeeds_Jumping:",
                            "    dw \$0600*!SPF*\$100,\$01C0*!SPF*\$100,\$02C0*!SPF*\$100",
                        ),
                        "Only the first column changes. \$0600 is 6.0 pixels per frame and already serves as vanilla's normal-gravity Hi-Jump launch value.",
                    ),
                ),
                AsmLibrarySection(
                    "Know exactly what this does",
                    listOf(
                        "This raises the initial upward velocity of ordinary, no-Hi-Jump jumps in air. It does not alter water or acid/lava entries, Hi-Jump Boots, wall jumps, gravity/acceleration, jump-button duration, or horizontal speed.",
                        "Using an existing vanilla value is a useful first experiment: it proves the edit path with a physically reasonable constant before you invent a more extreme value. Change one concept at a time so emulator behavior and ROM diffs stay explainable.",
                    ),
                ),
                AsmLibrarySection(
                    "Verify the edit at every layer",
                    listOf(
                        "Enabling Project ASM selects ASM source as the build base. A clean workspace may temporarily return to Loaded ROM, but the first source edit selects ASM source again. Loaded ROM is unavailable while saved or unsaved source edits exist, so an export cannot silently omit this change.",
                        "Build SMEDIT Result in the ROM view before exporting. The source edit should appear under asm-source:project and change only the owned words beginning at SNES \$90:9EB9 and \$90:9EBF. The normal conflict checks reject a GUI patch that tries to own different values at those same bytes.",
                        "Finally, test the output in the emulator: ordinary air jumps should be stronger while Hi-Jump and liquid behavior remain distinct. An unchanged source tree must still reproduce the baseline ROM.",
                    ),
                    AsmCodeExample(
                        "Expected NTSC value split",
                        code(
                            "; \$04E0 -> whole \$0004, fraction \$E000",
                            "; \$0600 -> whole \$0006, fraction \$0000",
                            "; Verify both words change together; reject unrelated ROM differences.",
                        ),
                        "A visible gameplay change is useful evidence, but a small, owner-aware byte diff is the safety proof.",
                    ),
                ),
                AsmLibrarySection(
                    "Continue the investigation",
                    listOf(
                        "Nearby tables cover Hi-Jump, wall-jump, knockback, and other vertical velocities. Follow each label to its reader instead of assuming the same column meanings everywhere. Air acceleration is a separate control: launch velocity determines the start of the arc, while acceleration determines how quickly upward motion slows and reverses.",
                        "The reusable workflow is: name the behavior, find the data, inspect every reader, identify coupled fields and table indices, make the narrowest project edit, predict the exact binary difference, test behavior, and keep an immediate restore path.",
                    ),
                ),
            ),
        ),
    )

    fun guide(id: String?): AsmLibraryGuide? = guides.firstOrNull { it.id == id }

    fun instructionPageId(mnemonic: String): String = "instruction:${mnemonic.uppercase()}"

    fun mnemonicFromPageId(pageId: String): String? = pageId
        .takeIf { it.startsWith("instruction:") }
        ?.substringAfter(':')
        ?.takeIf { AsmInstructionReference.find(it) != null }
}

/** Complete mnemonic-level 65C816 reference used by the Library and source links. */
internal object AsmInstructionReference {
    private fun code(vararg lines: String) = lines.joinToString("\n")

    private fun i(mnemonic: String, name: String, summary: String, flags: String = "—") =
        AsmInstructionInfo(
            mnemonic = mnemonic,
            name = name,
            summary = summary,
            flags = flags,
            category = categoryFor(mnemonic),
            commonForms = formsFor(mnemonic),
            example = exampleFor(mnemonic),
            practicalNote = noteFor(mnemonic),
        )

    val instructions: Map<String, AsmInstructionInfo> = listOf(
        i("ADC", "Add with carry", "Add memory and carry to the accumulator.", "N V Z C"),
        i("AND", "Logical AND", "AND memory with the accumulator.", "N Z"),
        i("ASL", "Arithmetic shift left", "Shift left; the top bit enters carry and zero enters bit 0.", "N Z C"),
        i("BCC", "Branch if carry clear", "Branch when C = 0."), i("BCS", "Branch if carry set", "Branch when C = 1."),
        i("BEQ", "Branch if equal", "Branch when Z = 1."), i("BMI", "Branch if minus", "Branch when N = 1."),
        i("BNE", "Branch if not equal", "Branch when Z = 0."), i("BPL", "Branch if plus", "Branch when N = 0."),
        i("BRA", "Branch always", "Unconditional 8-bit relative branch."),
        i("BRK", "Software break", "Enter the software interrupt handler.", "I D"),
        i("BRL", "Branch always long", "Unconditional 16-bit relative branch."),
        i("BVC", "Branch if overflow clear", "Branch when V = 0."), i("BVS", "Branch if overflow set", "Branch when V = 1."),
        i("BIT", "Bit test", "Test accumulator bits against memory.", "N V Z"),
        i("CLC", "Clear carry", "Set C = 0.", "C"), i("CLD", "Clear decimal", "Set D = 0.", "D"),
        i("CLI", "Clear interrupt disable", "Set I = 0.", "I"), i("CLV", "Clear overflow", "Set V = 0.", "V"),
        i("CMP", "Compare accumulator", "Subtract for flags without storing a result.", "N Z C"),
        i("COP", "Coprocessor interrupt", "Enter the COP software interrupt handler.", "I D"),
        i("CPX", "Compare X", "Compare memory with X.", "N Z C"), i("CPY", "Compare Y", "Compare memory with Y.", "N Z C"),
        i("DEC", "Decrement", "Subtract one from memory or accumulator.", "N Z"),
        i("DEX", "Decrement X", "Subtract one from X.", "N Z"), i("DEY", "Decrement Y", "Subtract one from Y.", "N Z"),
        i("EOR", "Exclusive OR", "XOR memory with the accumulator.", "N Z"),
        i("INC", "Increment", "Add one to memory or accumulator.", "N Z"),
        i("INX", "Increment X", "Add one to X.", "N Z"), i("INY", "Increment Y", "Add one to Y.", "N Z"),
        i("JML", "Jump long", "Jump to a 24-bit address."), i("JMP", "Jump", "Jump to the target address."),
        i("JSL", "Jump to subroutine long", "Call a subroutine with a 24-bit return address."),
        i("JSR", "Jump to subroutine", "Call a subroutine in the current program bank."),
        i("LDA", "Load accumulator", "Load memory into the accumulator.", "N Z"),
        i("LDX", "Load X", "Load memory into X.", "N Z"), i("LDY", "Load Y", "Load memory into Y.", "N Z"),
        i("LSR", "Logical shift right", "Shift right; bit 0 enters carry and zero enters the top bit.", "N Z C"),
        i("MVN", "Block move next", "Move bytes while incrementing X and Y."),
        i("MVP", "Block move previous", "Move bytes while decrementing X and Y."),
        i("NOP", "No operation", "Consume time without changing machine state."),
        i("ORA", "Logical OR", "OR memory with the accumulator.", "N Z"),
        i("PEA", "Push effective address", "Push a 16-bit immediate value."),
        i("PEI", "Push effective indirect", "Push the 16-bit value addressed through direct page."),
        i("PER", "Push effective relative", "Push the effective 16-bit relative address."),
        i("PHA", "Push accumulator", "Push accumulator."), i("PHB", "Push data bank", "Push data-bank register."),
        i("PHD", "Push direct page", "Push direct-page register."), i("PHK", "Push program bank", "Push program-bank register."),
        i("PHP", "Push processor status", "Push processor status."), i("PHX", "Push X", "Push X."), i("PHY", "Push Y", "Push Y."),
        i("PLA", "Pull accumulator", "Pull accumulator.", "N Z"), i("PLB", "Pull data bank", "Pull data-bank register.", "N Z"),
        i("PLD", "Pull direct page", "Pull direct-page register.", "N Z"), i("PLP", "Pull processor status", "Pull processor status."),
        i("PLX", "Pull X", "Pull X.", "N Z"), i("PLY", "Pull Y", "Pull Y.", "N Z"),
        i("REP", "Reset status bits", "Clear selected processor-status bits."),
        i("ROL", "Rotate left", "Rotate left through carry.", "N Z C"), i("ROR", "Rotate right", "Rotate right through carry.", "N Z C"),
        i("RTI", "Return from interrupt", "Restore status and return from an interrupt."),
        i("RTL", "Return long", "Return from JSL."), i("RTS", "Return", "Return from JSR."),
        i("SBC", "Subtract with borrow", "Subtract memory and inverse carry from accumulator.", "N V Z C"),
        i("SEC", "Set carry", "Set C = 1.", "C"), i("SED", "Set decimal", "Set D = 1.", "D"),
        i("SEI", "Set interrupt disable", "Set I = 1.", "I"), i("SEP", "Set status bits", "Set selected processor-status bits."),
        i("STA", "Store accumulator", "Store accumulator to memory."), i("STP", "Stop processor", "Stop the processor until reset."),
        i("STX", "Store X", "Store X to memory."), i("STY", "Store Y", "Store Y to memory."), i("STZ", "Store zero", "Store zero to memory."),
        i("TAX", "Transfer accumulator to X", "Copy accumulator to X.", "N Z"),
        i("TAY", "Transfer accumulator to Y", "Copy accumulator to Y.", "N Z"),
        i("TCD", "Transfer accumulator to direct page", "Copy 16-bit accumulator to direct page.", "N Z"),
        i("TCS", "Transfer accumulator to stack", "Copy 16-bit accumulator to stack pointer."),
        i("TDC", "Transfer direct page to accumulator", "Copy direct page to accumulator.", "N Z"),
        i("TRB", "Test and reset bits", "Test accumulator bits, then clear them in memory.", "Z"),
        i("TSB", "Test and set bits", "Test accumulator bits, then set them in memory.", "Z"),
        i("TSC", "Transfer stack to accumulator", "Copy stack pointer to accumulator.", "N Z"),
        i("TSX", "Transfer stack to X", "Copy stack pointer to X.", "N Z"),
        i("TXA", "Transfer X to accumulator", "Copy X to accumulator.", "N Z"),
        i("TXS", "Transfer X to stack", "Copy X to stack pointer."), i("TXY", "Transfer X to Y", "Copy X to Y.", "N Z"),
        i("TYA", "Transfer Y to accumulator", "Copy Y to accumulator.", "N Z"), i("TYX", "Transfer Y to X", "Copy Y to X.", "N Z"),
        i("WAI", "Wait for interrupt", "Pause execution until an interrupt."),
        i("WDM", "Reserved", "Reserved two-byte instruction."), i("XBA", "Exchange accumulator bytes", "Swap accumulator high and low bytes.", "N Z"),
        i("XCE", "Exchange carry and emulation", "Swap carry with the emulation-mode flag.", "C"),
    ).associateBy { it.mnemonic }

    fun find(mnemonic: String?): AsmInstructionInfo? = mnemonic?.let { instructions[it.uppercase()] }

    private fun categoryFor(mnemonic: String): AsmInstructionCategory = when (mnemonic) {
        "LDA", "LDX", "LDY", "STA", "STX", "STY", "STZ", "MVN", "MVP" -> AsmInstructionCategory.LOAD_STORE
        "TAX", "TAY", "TCD", "TCS", "TDC", "TSC", "TSX", "TXA", "TXS", "TXY", "TYA", "TYX", "XBA" -> AsmInstructionCategory.TRANSFER
        "ADC", "SBC", "CMP", "CPX", "CPY", "DEC", "DEX", "DEY", "INC", "INX", "INY" -> AsmInstructionCategory.ARITHMETIC
        "AND", "ASL", "BIT", "EOR", "LSR", "ORA", "ROL", "ROR", "TRB", "TSB" -> AsmInstructionCategory.LOGIC
        "BCC", "BCS", "BEQ", "BMI", "BNE", "BPL", "BRA", "BRL", "BVC", "BVS", "JML", "JMP", "JSL", "JSR", "RTI", "RTL", "RTS" -> AsmInstructionCategory.BRANCH
        "PEA", "PEI", "PER", "PHA", "PHB", "PHD", "PHK", "PHP", "PHX", "PHY", "PLA", "PLB", "PLD", "PLP", "PLX", "PLY" -> AsmInstructionCategory.STACK
        "CLC", "CLD", "CLI", "CLV", "REP", "SEC", "SED", "SEI", "SEP", "XCE" -> AsmInstructionCategory.FLAGS
        else -> AsmInstructionCategory.SYSTEM
    }

    private fun formsFor(mnemonic: String): List<String> = when (mnemonic) {
        "LDA", "ADC", "AND", "CMP", "EOR", "ORA", "SBC" -> listOf("$mnemonic #value", "$mnemonic addr", "$mnemonic addr,X", "$mnemonic (dp),Y", "$mnemonic [dp],Y")
        "LDX" -> listOf("LDX #value", "LDX addr", "LDX addr,Y")
        "LDY" -> listOf("LDY #value", "LDY addr", "LDY addr,X")
        "STA" -> listOf("STA addr", "STA addr,X", "STA (dp),Y", "STA [dp],Y")
        "STX", "STY", "STZ" -> listOf("$mnemonic addr", "$mnemonic addr,X")
        "ASL", "LSR", "ROL", "ROR", "DEC", "INC" -> listOf("$mnemonic A", "$mnemonic addr", "$mnemonic addr,X")
        "BIT" -> listOf("BIT #value", "BIT addr", "BIT addr,X")
        "TRB", "TSB" -> listOf("$mnemonic addr")
        "CPX", "CPY" -> listOf("$mnemonic #value", "$mnemonic addr")
        "BCC", "BCS", "BEQ", "BMI", "BNE", "BPL", "BRA", "BVC", "BVS" -> listOf("$mnemonic nearbyLabel")
        "BRL" -> listOf("BRL label")
        "JMP" -> listOf("JMP addr", "JMP (addr)", "JMP [addr]", "JMP (addr,X)")
        "JML" -> listOf("JML long", "JML [addr]")
        "JSR" -> listOf("JSR addr", "JSR (addr,X)")
        "JSL" -> listOf("JSL long")
        "MVN", "MVP" -> listOf("$mnemonic sourceBank,destinationBank")
        "PEA" -> listOf("PEA value")
        "PEI" -> listOf("PEI (dp)")
        "PER" -> listOf("PER label")
        "REP", "SEP" -> listOf("$mnemonic #mask")
        "BRK", "COP", "WDM" -> listOf("$mnemonic #signature")
        else -> listOf(mnemonic)
    }

    private fun exampleFor(mnemonic: String): AsmCodeExample = when (mnemonic) {
        "LDA" -> AsmCodeExample("Load a room value", code("REP #\$20", "LDA.W \$0A00"), "Loads a 16-bit value from memory into A and updates N/Z.")
        "LDX", "LDY" -> AsmCodeExample("Initialize an index", code("$mnemonic.W #\$0008"), "Loads a 16-bit loop counter or table offset.")
        "STA", "STX", "STY" -> AsmCodeExample("Write a value", code("$mnemonic.W \$0A00"), "Stores the selected register without changing arithmetic flags.")
        "STZ" -> AsmCodeExample("Clear memory", code("STZ.W \$0A00"), "Writes zero directly without first loading A.")
        "ADC" -> AsmCodeExample("Add with a clean carry", code("CLC", "ADC.W #\$0010"), "CLC makes this a plain addition instead of adding an incoming carry.")
        "SBC" -> AsmCodeExample("Subtract without borrow", code("SEC", "SBC.W #\$0010"), "The 65816 uses inverted borrow semantics, so SEC normally precedes a fresh subtraction.")
        "CMP", "CPX", "CPY" -> AsmCodeExample("Compare and branch", code("$mnemonic.W #\$0004", "BEQ .Matched"), "The compare keeps the register unchanged and produces flags for the branch.")
        "INC", "INX", "INY" -> AsmCodeExample("Advance a counter", code(mnemonic, "BNE .Continue"), "The increment updates N/Z, which the following branch can consume.")
        "DEC", "DEX", "DEY" -> AsmCodeExample("Countdown loop", code(".Loop", "    $mnemonic", "    BNE .Loop"), "A common compact loop when the value begins above zero.")
        "AND" -> AsmCodeExample("Keep selected bits", code("AND.W #\$00FF"), "Clears every bit in A except the low byte.")
        "ORA" -> AsmCodeExample("Set selected bits", code("ORA.W #\$0004"), "Forces bit 2 on while preserving the other bits.")
        "EOR" -> AsmCodeExample("Toggle selected bits", code("EOR.W #\$0004"), "Flips bit 2 and preserves the other bits.")
        "BIT" -> AsmCodeExample("Test a flag", code("BIT.W #\$0004", "BEQ .BitWasClear"), "Tests masked bits without replacing A.")
        "TRB" -> AsmCodeExample("Clear masked bits", code("LDA.W #\$0004", "TRB.W \$0A00"), "Tests A against memory, then clears those A-selected bits in memory.")
        "TSB" -> AsmCodeExample("Set masked bits", code("LDA.W #\$0004", "TSB.W \$0A00"), "Tests A against memory, then sets those A-selected bits in memory.")
        "ASL" -> AsmCodeExample("Double an unsigned value", code("ASL A"), "Shifts left once; the former top bit is preserved in carry.")
        "LSR" -> AsmCodeExample("Halve an unsigned value", code("LSR A"), "Shifts right once; the former bit 0 is preserved in carry.")
        "ROL", "ROR" -> AsmCodeExample("Rotate through carry", code(mnemonic + " A"), "Carry participates as an extra bit, which is useful for multi-word shifts.")
        "BCC", "BCS", "BEQ", "BMI", "BNE", "BPL", "BVC", "BVS" -> AsmCodeExample("Conditional branch", code("$mnemonic .Target", "NOP", ".Target"), "Branches only when its processor-flag condition is true.")
        "BRA", "BRL" -> AsmCodeExample("Unconditional branch", code("$mnemonic .Done", "db \$00", ".Done"), "Always continues at the relative target; BRL has the larger range.")
        "JSR" -> AsmCodeExample("Call within a bank", code("JSR UpdateObject", "...", "UpdateObject:", "    RTS"), "JSR and RTS must be paired.")
        "JSL" -> AsmCodeExample("Call across banks", code("JSL \$82ABCD", "...", "    RTL"), "JSL stores a long return address and must eventually return with RTL.")
        "JMP", "JML" -> AsmCodeExample("Tail jump", code("$mnemonic NextHandler"), "Transfers control without adding a return address to the stack.")
        "RTS" -> AsmCodeExample("Return from JSR", code("JSR LocalRoutine", "...", "LocalRoutine:", "    RTS"), "Pulls the 16-bit return address created by JSR.")
        "RTL" -> AsmCodeExample("Return from JSL", code("JSL LongRoutine", "...", "    RTL"), "Pulls the 24-bit return address created by JSL.")
        "RTI" -> AsmCodeExample("Return from an interrupt", code("RTI"), "Restores processor status and the interrupted address; it is not a normal subroutine return.")
        "PHA", "PHX", "PHY", "PHB", "PHD", "PHK", "PHP" -> AsmCodeExample("Preserve state", code(mnemonic, "; work that may change the value"), "Pushes the selected value so it can be restored later.")
        "PLA", "PLX", "PLY", "PLB", "PLD", "PLP" -> AsmCodeExample("Restore state", code(mnemonic), "Pulls the most recently stacked value of the matching size.")
        "PEA" -> AsmCodeExample("Push a word constant", code("PEA.W \$1234"), "Pushes the immediate 16-bit value onto the stack.")
        "PEI" -> AsmCodeExample("Push through direct page", code("PEI.B (\$12)"), "Reads a 16-bit value through direct page and pushes it.")
        "PER" -> AsmCodeExample("Push a relative address", code("PER.W Data", "...", "Data:"), "Pushes the effective 16-bit address of a nearby label.")
        "REP" -> AsmCodeExample("Select 16-bit accumulator", code("REP #\$20", "LDA.W #\$1234"), "Clears M, so A and its immediate operands are 16-bit.")
        "SEP" -> AsmCodeExample("Select 8-bit accumulator", code("SEP #\$20", "LDA.B #\$12"), "Sets M, so A and its immediate operands are 8-bit.")
        "CLC", "SEC", "CLD", "SED", "CLI", "SEI", "CLV" -> AsmCodeExample("Set explicit CPU state", code(mnemonic), "Changes one processor flag directly; nearby arithmetic or interrupt code often explains why.")
        "TAX", "TAY", "TCD", "TCS", "TDC", "TSC", "TSX", "TXA", "TXS", "TXY", "TYA", "TYX" -> AsmCodeExample("Move between registers", code(mnemonic), "Copies the source register to the destination without reading memory.")
        "XBA" -> AsmCodeExample("Swap accumulator bytes", code("REP #\$20", "XBA"), "Exchanges A's high and low bytes; N/Z reflect the new low byte.")
        "XCE" -> AsmCodeExample("Enter native mode", code("CLC", "XCE"), "On startup, exchanging a clear carry into E leaves emulation mode and enables native 65816 behavior.")
        "MVN", "MVP" -> AsmCodeExample("Move a block", code("LDA.W #\$00FF", "LDX.W #Source", "LDY.W #Destination", "$mnemonic \$80,\$7E"), "Moves A+1 bytes; MVN increments and MVP decrements X/Y.")
        "NOP" -> AsmCodeExample("Deliberate empty instruction", code("NOP"), "Consumes space and cycles without changing normal program state.")
        "WAI" -> AsmCodeExample("Wait for an interrupt", code("WAI"), "Stops instruction execution until an interrupt wakes the CPU.")
        "STP" -> AsmCodeExample("Stop until reset", code("STP"), "Halts the CPU; ordinary game code almost never wants this.")
        "BRK", "COP" -> AsmCodeExample("Software exception", code("$mnemonic #\$00"), "Vectors through the corresponding software-interrupt handler.")
        "WDM" -> AsmCodeExample("Reserved opcode", code("WDM #\$00"), "Reserved by the CPU; usually encountered as deliberate metadata or debugging convention.")
        else -> AsmCodeExample("Basic use", code(mnemonic), "Executes $mnemonic with its implied operands.")
    }

    private fun noteFor(mnemonic: String): String = when (mnemonic) {
        "ADC" -> "Carry is an input as well as an output. Use CLC first unless you intentionally continue a multi-byte addition."
        "SBC" -> "Carry means no borrow. Use SEC before a fresh subtraction unless you intentionally continue a multi-byte subtraction."
        "CMP", "CPX", "CPY" -> "Equality uses Z. Unsigned greater/less comparisons use C; signed comparisons need more care with N and V."
        "REP", "SEP" -> "These change runtime width state. Make the calling convention explicit or restore the caller's status with PHP/PLP."
        "JSR" -> "Use RTS to return. A JSR target normally lives in the same program bank."
        "JSL" -> "Use RTL to return. This is the ordinary call when the target can be in another bank."
        "RTS", "RTL", "RTI" -> "Return instructions are not interchangeable; each expects a different stack layout."
        "PLP" -> "This can change accumulator and index widths immediately, so the following instruction must match the restored state."
        "MVN", "MVP" -> "A contains count minus one. Bank operands and the direction of X/Y movement are easy sources of off-by-one bugs."
        "BRK", "COP", "STP", "WDM" -> "Uncommon in normal Super Metroid gameplay code; inspect the surrounding convention before editing it."
        else -> "Check the surrounding REP/SEP state, data bank, and operand addressing mode before changing the encoded length."
    }
}
