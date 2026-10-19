package com.supermetroid.editor.asm

import java.io.File

/** One complete source image shared by transformation, indexing, and provenance hashing. */
internal class AsmSourceImage private constructor(
    val texts: Map<String, String>,
    val index: AsmReferenceIndex,
) {
    companion object {
        fun capture(root: File, indexHint: AsmReferenceIndex? = null, rebuildIncompleteIndex: Boolean = false): AsmSourceImage {
            val texts = readAsmSourceTree(root)
            fun matches(index: AsmReferenceIndex) = index.files.size == texts.size &&
                index.files.map { it.id }.toSet() == texts.keys &&
                index.files.all { texts[it.id]?.reader()?.readLines() == it.lines }
            val reusable = indexHint?.takeIf(::matches)
            require(indexHint == null || reusable != null || rebuildIncompleteIndex) {
                "ASM source index is incomplete or changed after it was indexed"
            }
            val index = reusable ?: AsmSourceParser().parse(root, File(root.parentFile, "data"), emptyList(), sourceTexts = texts)
            require(matches(index)) { "ASM source inventory changed while capturing the baseline" }
            return AsmSourceImage(texts, index)
        }
    }
}
