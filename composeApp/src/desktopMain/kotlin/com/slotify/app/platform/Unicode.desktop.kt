package com.slotify.app.platform

actual fun nfkd(input: String): String =
    java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKD)
