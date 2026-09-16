package com.example.features.calculator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.core.repository.CalculatorRepository

/**
 * CalculatorViewModel provides high-precision scientific calculations and operation state management
 * by extending ScientificCalculatorViewModel.
 */
class CalculatorViewModel(
    repository: CalculatorRepository
) : ScientificCalculatorViewModel(repository) {

    class Factory(private val repository: CalculatorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ScientificCalculatorViewModel::class.java)) {
                return ScientificCalculatorViewModel(repository) as T
            }
            return CalculatorViewModel(repository) as T
        }
    }
}
