package com.slotify.app.platform

/**
 * Unicode NFKD normalization — shared text-matching primitive.
 * JVM/Android actuals use java.text.Normalizer.
 */
expect fun nfkd(input: String): String
