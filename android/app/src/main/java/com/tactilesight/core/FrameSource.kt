package com.tactilesight.core

/**
 * Where frames come from: bundled band captures now, the band itself over the
 * WebRTC data channel later (ADR-0009). Capture is **on-demand** — the band
 * sends a triplet in response to a request, nothing streams continuously.
 *
 * This is the whole seam. A screen that drives frames must depend on *this*
 * and nothing more, or swapping the implementation is not free — which is the
 * one thing this interface exists to guarantee.
 */
interface FrameSource {

    /** Grab the scene as it is right now. Throws if capture fails. */
    suspend fun capture(): Frame
}

/**
 * A source whose frames can be *browsed* — a fixed set you can pick from,
 * rather than whatever the sensor sees right now.
 *
 * Bundled captures are browsable; a live band is not, because there is no list
 * to choose from. Callers ask `is BrowsableFrameSource` and offer a picker only
 * when the answer is yes, so a non-browsable source drops in without the screen
 * changing.
 */
interface BrowsableFrameSource : FrameSource {

    /** Stable ids of every frame on offer, in display order. */
    val sceneIds: List<String>

    /** Which one [capture] will return. */
    var selectedIndex: Int

    /** Load a specific one without changing the selection. */
    suspend fun load(sceneId: String): Frame
}
