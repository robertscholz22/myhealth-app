package com.myhealth.domain.engine.label

import java.text.Normalizer

internal actual fun String.normalizeNfkd(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
