package app.twilmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Activity-scoped holder that gives each setup visit its own view model
 * store. The setup view model survives rotation within a visit (the holder
 * itself is activity-scoped), but ending the visit clears the store — so
 * plaintext secret text and stale form state don't accumulate in the
 * activity's store across visits, and each visit starts from a fresh load.
 */
class SetupVisitOwner : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /** Clears the current visit's view models; the next visit starts fresh. */
    fun endVisit() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}
