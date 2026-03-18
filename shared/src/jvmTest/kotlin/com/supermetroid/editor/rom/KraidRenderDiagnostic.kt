package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class KraidRenderDiagnostic {
    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    @Test
    fun `render all kraid body views`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        for (def in KraidSpritemap.BODY_TILEMAPS) {
            val body = kraid.renderBodyTilemap(def) ?: continue
            val img = BufferedImage(body.width, body.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, body.width, body.height, body.pixels, 0, body.width)
            val safeName = def.name.replace(" ", "_").replace("(", "").replace(")", "").lowercase()
            ImageIO.write(img, "png", File("/tmp/kraid_${safeName}.png"))
        }
    }

    @Test
    fun `render kraid detail components`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val sb = StringBuilder()
        for (def in KraidSpritemap.BIGSPRMAP_COMPONENTS) {
            val sprite = kraid.renderBigSprmap(def)
            if (sprite == null) {
                sb.appendLine("${def.name}: FAILED to render")
                continue
            }
            sb.appendLine("${def.name}: ${sprite.width}x${sprite.height} (${sprite.entries.size} entries)")
            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            val safeName = def.name.replace(" ", "_").replace("(", "").replace(")", "").lowercase()
            ImageIO.write(img, "png", File("/tmp/kraid_${safeName}.png"))
        }

        File("/tmp/kraid_detail_info.txt").writeText(sb.toString())
    }

    @Test
    fun `render kraid full body nametable`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val full = kraid.renderFullBody()
        assertNotNull(full)
        val img = BufferedImage(full!!.width, full.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, full.width, full.height, full.pixels, 0, full.width)
        ImageIO.write(img, "png", File("/tmp/kraid_fullbody_nametable.png"))
    }
}
