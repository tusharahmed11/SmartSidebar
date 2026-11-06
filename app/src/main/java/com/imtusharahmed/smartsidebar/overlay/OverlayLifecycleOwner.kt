package com.imtusharahmed.smartsidebar.overlay

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

fun ComposeView.initLifecycle(
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner
) {
    setViewTreeLifecycleOwner(lifecycleOwner)
    setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
    setViewTreeViewModelStoreOwner(savedStateRegistryOwner as? ViewModelStoreOwner)
}

class OverlayLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private var isRestored = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun performRestore(savedState: Bundle? = null) {
        if (!isRestored) {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(savedState)
            isRestored = true
        }
    }

    fun attachToComposeView(composeView: ComposeView) {
        performRestore(null)
        composeView.initLifecycle(
            lifecycleOwner = this,
            savedStateRegistryOwner = this
        )
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun moveToState(state: Lifecycle.State) {
        performRestore(null)
        lifecycleRegistry.currentState = state
    }

    fun clear() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
        }
        store.clear()
    }
}

