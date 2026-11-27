package com.myhealth.data.share

/**
 * Where a `demo-…` library lives: the Firebase emulator on the development machine. Real
 * Firebase project ids can never start with `demo-`, so this never redirects a real library.
 */
expect fun firebaseEmulatorHost(): String

internal fun endpointsFor(projectId: String): FirebaseEndpoints =
    if (projectId.startsWith("demo-")) FirebaseEndpoints.emulator(firebaseEmulatorHost()) else FirebaseEndpoints.PRODUCTION
