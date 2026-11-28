package com.fanhuatv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.security.SignatureGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val guard: SignatureGuard
) : ViewModel() {

    private val _result = MutableStateFlow<SignatureGuard.Result?>(null)
    val result: StateFlow<SignatureGuard.Result?> = _result.asStateFlow()

    init { recheck() }

    fun recheck() {
        viewModelScope.launch { _result.value = guard.verify() }
    }
}
