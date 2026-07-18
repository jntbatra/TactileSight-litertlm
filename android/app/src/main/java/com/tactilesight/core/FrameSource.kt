package com.tactilesight.core

/**
 * Where frames come from: the phone camera now, the band over the data channel
 * later (ADR-0009), bundled test scenes in between (issue #3). Capture is
 * on-demand — nothing streams continuously.
 */
interface FrameSource {

    /** Grab the scene as it is right now. Throws if capture fails. */
    suspend fun capture(): Frame
}
