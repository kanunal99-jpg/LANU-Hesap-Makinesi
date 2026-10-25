package com.example.features.communication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.delivery.DeliveryCoordinator
import com.example.core.delivery.DeliveryOptions
import com.example.core.delivery.DeliverySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DeliveryCenterViewModel(private val coordinator: DeliveryCoordinator) : ViewModel() {
    val snapshot = coordinator.store.changes().stateIn(viewModelScope,
        SharingStarted.WhileSubscribed(5000L), DeliverySnapshot(available = false))
    val jobs = coordinator.workStates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setOptions(options: DeliveryOptions) = action { coordinator.setOptions(options) }
    fun checkNow() = action { coordinator.checkNow() }
    fun reconcile() = action { coordinator.reconcile() }

    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _error.value = "Arka plan işlemi hazırlanamadı. Uygulama açıkken yenileyebilir veya yeniden deneyebilirsiniz."
            } finally { _busy.value = false }
        }
    }

    class Factory(private val coordinator: DeliveryCoordinator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DeliveryCenterViewModel::class.java))
            return DeliveryCenterViewModel(coordinator) as T
        }
    }
}
