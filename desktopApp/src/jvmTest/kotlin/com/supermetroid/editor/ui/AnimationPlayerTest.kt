package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AnimationPlayerTest {

    @Test
    fun `play restarts a completed one-shot animation`() {
        assertEquals(0, animationPlaybackStartFrame(frameCount = 7, loop = false, currentFrame = 6))
    }

    @Test
    fun `play resumes a paused one-shot animation`() {
        assertEquals(3, animationPlaybackStartFrame(frameCount = 7, loop = false, currentFrame = 3))
    }

    @Test
    fun `play preserves the current frame for looping animations`() {
        assertEquals(6, animationPlaybackStartFrame(frameCount = 7, loop = true, currentFrame = 6))
    }
}
