package com.myhealth.ui.settings

import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.Profile

/** ViewModel state for [SettingsScreen]: the profile row plus every [AppSettings] key (§4.2 Settings). */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val profile: Profile? = null,
    val settings: AppSettings = AppSettings(),
    /**
     * The current FTP estimate (P12.2/P12.4) computed **ignoring** `profile.ftpWattsManual`, so the
     * override field's hint always shows what the estimator would produce on its own rather than
     * echoing back the number the owner is about to type over.
     */
    val ftpEstimate: FtpEstimate? = null,
)
