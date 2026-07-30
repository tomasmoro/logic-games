package com.kortexgames.app.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marcadores del contrato MVI. */
interface UiState
interface UiIntent
interface UiEffect

/**
 * Base MVI para toda la app.
 *
 *  - [state]  : StateFlow con el estado renderizable (fuente única de verdad).
 *  - [effect] : eventos one-shot (navegar, mostrar snackbar, disparar sonido...).
 *               Se modelan con un Channel para NO reemitirse en recomposición
 *               ni en cambios de configuración (a diferencia del state).
 *
 * El ciclo es unidireccional: UI -> onIntent(Intent) -> reduce -> new State.
 */
abstract class MviViewModel<I : UiIntent, S : UiState, E : UiEffect>(
    initialState: S,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect: Flow<E> = _effect.receiveAsFlow()

    protected val currentState: S get() = _state.value

    /** Punto de entrada único de la UI. */
    abstract fun onIntent(intent: I)

    /** Reduce el estado de forma atómica. */
    protected fun setState(reducer: S.() -> S) = _state.update(reducer)

    /** Emite un efecto one-shot. */
    protected fun sendEffect(effect: E) {
        viewModelScope.launch { _effect.send(effect) }
    }
}
