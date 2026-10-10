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

/**
 * SMEDIT-authored, practical introduction to the 65C816 and Asar syntax used by
 * the Super Metroid disassembly. This is intentionally a field guide rather
 * than a replacement for the complete CPU or assembler manuals.
 */
internal object AsmLibrary {
    private fun code(vararg lines: String) = lines.joinToString("\n")

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
                        "A dotted label belongs to the nearest preceding main label until another main label begins. Outside that scope, Super Metroid source refers to it through the combined main-and-local name. Do not casually add or remove the dot: that renames the symbol and can break every reference to it.",
                    ),
                    AsmCodeExample(
                        "A real Samus physics table",
                        code(
                            "SamusPhysicsConstants:",
                            "  .InitialYSpeeds_Jumping:",
                            "    dw \$04E0,\$01C0,\$02C0",
                            "",
                            "    LDA.W SamusPhysicsConstants_InitialYSpeeds_Jumping,X",
                        ),
                        ".InitialYSpeeds_Jumping is scoped beneath SamusPhysicsConstants, so its complete symbol is SamusPhysicsConstants_InitialYSpeeds_Jumping. The two spaces make the hierarchy easier to see but do not affect assembly.",
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
