package com.myhealth.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.repository.CycleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Backs [MoreScreen] (§4.1): the only dynamic bit of the navigation hub is whether the
 * cycle-tracker entry is shown, gated by [CycleRepository.isTrackingEnabled] (PLAN §5 P11.3). */
class MoreViewModel(cycleRepo: CycleRepository) : ViewModel() {

    val cycleTrackingEnabled: StateFlow<Boolean> =
        cycleRepo.isTrackingEnabled().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
