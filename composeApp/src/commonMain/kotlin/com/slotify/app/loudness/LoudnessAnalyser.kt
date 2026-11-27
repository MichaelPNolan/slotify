package com.slotify.app.loudness

/** Measure EBU R128 loudness of [path]. Platform failure → null. */
expect fun analyseFileLoudness(path: String): LoudnessResult?

/**
 * Loudness balance UI + DynamicsProcessing apply.
 * Desktop: false. Android: SDK_INT >= 28 (hide feature below API 28).
 */
expect fun supportsLoudnessBalance(): Boolean
