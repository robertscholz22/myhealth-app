package com.myhealth.domain.engine.label

/** Unicode compatibility decomposition (NFKD), so diacritics become separate combining marks. */
internal expect fun String.normalizeNfkd(): String
