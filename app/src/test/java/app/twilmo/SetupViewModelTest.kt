package app.twilmo

import app.twilmo.data.ConfigStore
import app.twilmo.data.SecretStore
import app.twilmo.domain.config.AuthorityKeys
import app.twilmo.domain.config.ConfigState
import app.twilmo.domain.config.SetupConfig
import app.twilmo.domain.config.SetupError
import app.twilmo.domain.config.SetupField
import app.twilmo.domain.config.StoredSecret
import app.twilmo.domain.config.configState
import app.twilmo.ui.SetupViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeConfigStore(initial: SetupConfig? = null) : ConfigStore {
        override val config = MutableStateFlow(initial)
        var saved: SetupConfig? = null
        override suspend fun save(config: SetupConfig) {
            saved = config
            this.config.value = config
        }
    }

    private class FakeSecretStore(initial: StoredSecret? = null) : SecretStore {
        var stored: StoredSecret? = initial
        override suspend fun readSecret(): StoredSecret? = stored
        override suspend fun writeSecret(secret: StoredSecret) {
            stored = secret
        }
    }

    private val existing = SetupConfig(
        endpointUrl = "https://example.twil.io/token",
        identity = "twilmo",
    )

    /** A stored secret bound to [existing]'s authority, as a real save writes it. */
    private fun storedFor(config: SetupConfig, secret: String) = StoredSecret(
        secret = secret,
        authorityKey = AuthorityKeys.authorityKey(config.endpointUrl, config.identity),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstRunLoadsEmptyFullForm() = runTest(dispatcher) {
        val viewModel = SetupViewModel(FakeConfigStore(), FakeSecretStore())
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertFalse(state.secretOnly)
        assertEquals("", state.endpointUrl)
    }

    @Test
    fun restoredConfigWithoutSecretEntersSecretOnlyMode() = runTest(dispatcher) {
        val viewModel = SetupViewModel(FakeConfigStore(existing), FakeSecretStore())
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.secretOnly)
        assertEquals(existing.endpointUrl, state.endpointUrl)
        assertEquals(existing.identity, state.identity)
    }

    @Test
    fun fullyConfiguredReopensAsFullFormPrefilled() = runTest(dispatcher) {
        val viewModel = SetupViewModel(
            FakeConfigStore(existing),
            FakeSecretStore(storedFor(existing, "old-secret")),
        )
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertFalse(state.secretOnly)
        assertEquals(existing.endpointUrl, state.endpointUrl)
    }

    @Test
    fun invalidFormSurfacesErrorsAndSavesNothing() = runTest(dispatcher) {
        val configStore = FakeConfigStore()
        val secretStore = FakeSecretStore()
        val viewModel = SetupViewModel(configStore, secretStore)
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged("http://insecure.example/token")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(SetupError.ENDPOINT_NOT_HTTPS, state.errors[SetupField.ENDPOINT])
        assertEquals(SetupError.MISSING, state.errors[SetupField.IDENTITY])
        assertEquals(SetupError.MISSING, state.errors[SetupField.SECRET])
        assertFalse(state.saved)
        assertEquals(null, configStore.saved)
        assertEquals(null, secretStore.stored)
    }

    @Test
    fun editingAFieldClearsItsErrorOnly() = runTest(dispatcher) {
        val viewModel = SetupViewModel(FakeConfigStore(), FakeSecretStore())
        testScheduler.advanceUntilIdle()
        viewModel.onSave()
        viewModel.onIdentityChanged("twilmo")
        val errors = viewModel.state.value.errors
        assertFalse(errors.containsKey(SetupField.IDENTITY))
        assertTrue(errors.containsKey(SetupField.SECRET))
    }

    @Test
    fun validFormSavesBothStoresTrimmed() = runTest(dispatcher) {
        val configStore = FakeConfigStore()
        val secretStore = FakeSecretStore()
        val viewModel = SetupViewModel(configStore, secretStore)
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged("  https://example.twil.io/token ")
        viewModel.onIdentityChanged(" twilmo ")
        viewModel.onSecretChanged("a-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        assertEquals(existing, configStore.saved)
        assertEquals("a-secret", secretStore.stored?.secret)
        // The secret is bound to the authority it was saved for, so a save
        // torn by process death leaves a detectable mismatch.
        assertEquals(
            AuthorityKeys.authorityKey(existing.endpointUrl, existing.identity),
            secretStore.stored?.authorityKey,
        )
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun secretOnlySaveWritesJustTheSecret() = runTest(dispatcher) {
        val configStore = FakeConfigStore(existing)
        val secretStore = FakeSecretStore()
        val viewModel = SetupViewModel(configStore, secretStore)
        testScheduler.advanceUntilIdle()
        viewModel.onSecretChanged("re-entered-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        assertEquals(null, configStore.saved)
        assertEquals("re-entered-secret", secretStore.stored?.secret)
        // Secret-only saves bind to the existing config's authority.
        assertEquals(
            AuthorityKeys.authorityKey(existing.endpointUrl, existing.identity),
            secretStore.stored?.authorityKey,
        )
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun persistenceFailureKeepsTheFormOpenAndSaysSo() = runTest(dispatcher) {
        val throwingSecretStore = object : SecretStore {
            override suspend fun readSecret(): StoredSecret? = null
            override suspend fun writeSecret(secret: StoredSecret) {
                throw IllegalStateException("keystore unavailable")
            }
        }
        val configStore = FakeConfigStore()
        val logged = mutableListOf<String>()
        val viewModel = SetupViewModel(configStore, throwingSecretStore, logged::add)
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("a-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertFalse(state.saved)
        assertTrue(state.saveFailed)
        // Config-first: the fresh config landed but the secret didn't, so
        // the persisted state reads as the honest "secret needed" for the
        // config the user typed — their work is preserved.
        assertEquals(existing, configStore.saved)
        // The reason is logged sanitized: the class, never a field value.
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("IllegalStateException"))
        assertFalse(logged.single().contains("a-secret"))
    }

    @Test
    fun failedEditOfAWorkingAccountRollsTheOldConfigBack() = runTest(dispatcher) {
        val configStore = FakeConfigStore(existing)
        val secretStore = object : SecretStore {
            var stored: StoredSecret? = storedFor(existing, "old-secret")
            override suspend fun readSecret(): StoredSecret? = stored
            override suspend fun writeSecret(secret: StoredSecret) {
                throw IllegalStateException("keystore unavailable")
            }
        }
        val logged = mutableListOf<String>()
        val viewModel = SetupViewModel(configStore, secretStore, logged::add)
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged("https://other.twil.io/token")
        viewModel.onIdentityChanged("twilmo")
        viewModel.onSecretChanged("new-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.saveFailed)
        assertFalse(state.saved)
        // The working account is intact: the config write is rolled back to
        // the old values, and the old secret (still bound to them) remains.
        assertEquals(existing, configStore.config.value)
        assertEquals("old-secret", secretStore.stored?.secret)
    }

    @Test
    fun failedRollbackStillLeavesAnHonestDetectableState() = runTest(dispatcher) {
        val configStore = object : ConfigStore {
            override val config = MutableStateFlow<SetupConfig?>(existing)
            var saves = 0
            override suspend fun save(config: SetupConfig) {
                saves++
                if (saves == 1) {
                    this.config.value = config // The new config lands...
                } else {
                    // ...and the rollback write fails too.
                    throw IllegalStateException("datastore unavailable")
                }
            }
        }
        val secretStore = object : SecretStore {
            var stored: StoredSecret? = storedFor(existing, "old-secret")
            override suspend fun readSecret(): StoredSecret? = stored
            override suspend fun writeSecret(secret: StoredSecret) {
                throw IllegalStateException("keystore unavailable")
            }
        }
        val logged = mutableListOf<String>()
        val viewModel = SetupViewModel(configStore, secretStore, logged::add)
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged("https://other.twil.io/token")
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("new-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.saveFailed)
        // New config + a secret bound to the old authority reads as the
        // honest "secret needed" — never a false Ready — so even a failed
        // rollback needs no further repair.
        assertTrue(
            configState(configStore.config.value, secretStore.stored)
                is ConfigState.SecretMissing,
        )
        assertTrue(logged.any { it.contains("restoring previous configuration failed") })
    }

    @Test
    fun unreadableStoresStillProduceAUsableFreshForm() = runTest(dispatcher) {
        val throwingConfigStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                throw IllegalStateException("datastore corrupted")
            }
            override suspend fun save(config: SetupConfig) = Unit
        }
        val logged = mutableListOf<String>()
        val viewModel = SetupViewModel(throwingConfigStore, FakeSecretStore(), logged::add)
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        // Degrades to a fresh, savable form instead of a dead blank screen —
        // but says loading failed, so it never poses as an ordinary first
        // run while settings may still exist underneath.
        assertTrue(state.loaded)
        assertTrue(state.loadFailed)
        assertFalse(state.secretOnly)
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("IllegalStateException"))
    }

    @Test
    fun retryAfterALoadFailureRecoversTheSavedValues() = runTest(dispatcher) {
        var failuresLeft = 1
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("datastore hiccup")
                }
                emit(existing)
            }
            override suspend fun save(config: SetupConfig) = Unit
        }
        val viewModel = SetupViewModel(configStore, FakeSecretStore(storedFor(existing, "old-secret"))) {}
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loadFailed)
        viewModel.onRetryLoad()
        testScheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertFalse(state.loadFailed)
        assertEquals(existing.endpointUrl, state.endpointUrl)
        assertEquals(existing.identity, state.identity)
    }

    @Test
    fun retryIsRefusedWhileASaveIsInFlight() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var collections = 0
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                collections++
                if (collections == 1) throw IllegalStateException("datastore hiccup")
                emit(existing)
            }
            override suspend fun save(config: SetupConfig) {
                gate.await() // Holds the save so retry can race it.
            }
        }
        val viewModel = SetupViewModel(configStore, FakeSecretStore()) {}
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loadFailed)
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("a-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle() // Save parked on the config gate.
        assertTrue(viewModel.state.value.saving)
        // The save itself read the flow once more for its rollback
        // snapshot; a retry mid-save must not add a third collection —
        // a reload would race the write transaction and repopulate the
        // fields from the old configuration.
        val collectionsMidSave = collections
        viewModel.onRetryLoad()
        testScheduler.advanceUntilIdle()
        assertEquals(collectionsMidSave, collections)
        assertEquals(existing.endpointUrl, viewModel.state.value.endpointUrl)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun aSecondRetryWhileOneIsReloadingIsRefused() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var collections = 0
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                collections++
                if (collections == 1) throw IllegalStateException("datastore hiccup")
                gate.await() // Holds the reload so a second retry can race it.
                emit(existing)
            }
            override suspend fun save(config: SetupConfig) = Unit
        }
        val viewModel = SetupViewModel(configStore, FakeSecretStore(storedFor(existing, "old-secret"))) {}
        testScheduler.advanceUntilIdle()
        viewModel.onRetryLoad()
        testScheduler.advanceUntilIdle() // First reload parked on the gate.
        // A second retry mid-reload must not start another load: two
        // reloads finishing out of order could overwrite typed text.
        viewModel.onRetryLoad()
        testScheduler.advanceUntilIdle()
        assertEquals(2, collections)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        assertEquals(existing.endpointUrl, viewModel.state.value.endpointUrl)
    }

    @Test
    fun fieldEditsAreRefusedWhileASaveIsInFlight() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val configStore = object : ConfigStore {
            override val config = MutableStateFlow<SetupConfig?>(null)
            override suspend fun save(config: SetupConfig) {
                gate.await()
            }
        }
        val viewModel = SetupViewModel(configStore, FakeSecretStore()) {}
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("a-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle() // Save parked on the config gate.
        // A queued input callback arriving after the form froze must not
        // land: a save would persist the captured values and close over
        // the late edit, silently discarding it.
        viewModel.onSecretChanged("late-edit")
        assertEquals("a-secret", viewModel.state.value.secret)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun saveIsRefusedWhileAReloadIsInFlight() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var failuresLeft = 1
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("datastore hiccup")
                }
                gate.await() // Holds the reload so a save can race it.
                emit(existing)
            }
            var saved: SetupConfig? = null
            override suspend fun save(config: SetupConfig) {
                saved = config
            }
        }
        val secretStore = FakeSecretStore()
        val viewModel = SetupViewModel(configStore, secretStore) {}
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("a-secret")
        viewModel.onRetryLoad()
        testScheduler.advanceUntilIdle() // Reload parked on the gate.
        // The mirror of retry-during-save: a save dispatched while the
        // reload is in flight must be refused, or the reload could replace
        // the very fields the save was meant to persist.
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        assertEquals(null, configStore.saved)
        assertEquals(null, secretStore.stored)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
    }

    @Test
    fun formDropsBackToNotLoadedWhileARetryReloads() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var failuresLeft = 1
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("datastore hiccup")
                }
                gate.await() // Holds the reload so the mid-flight state shows.
                emit(existing)
            }
            override suspend fun save(config: SetupConfig) = Unit
        }
        val viewModel = SetupViewModel(configStore, FakeSecretStore(storedFor(existing, "old-secret"))) {}
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        viewModel.onRetryLoad()
        testScheduler.advanceUntilIdle()
        // Mid-reload the form is not-loaded again, so the fields sit
        // disabled and typed text can't be silently replaced when the
        // stored values arrive.
        assertFalse(viewModel.state.value.loaded)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        assertEquals(existing.endpointUrl, viewModel.state.value.endpointUrl)
    }

    @Test
    fun unreadablePreviousConfigAbortsAnEditBeforeAnyWrite() = runTest(dispatcher) {
        var loads = 0
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                loads++
                if (loads == 1) {
                    emit(existing) // The form loads fine...
                } else {
                    // ...but the save-time snapshot read doesn't.
                    throw IllegalStateException("datastore hiccup")
                }
            }
            var saved: SetupConfig? = null
            override suspend fun save(config: SetupConfig) {
                saved = config
            }
        }
        var writes = 0
        val secretStore = object : SecretStore {
            override suspend fun readSecret(): StoredSecret? = storedFor(existing, "old-secret")
            override suspend fun writeSecret(secret: StoredSecret) {
                writes++
            }
        }
        val logged = mutableListOf<String>()
        val viewModel = SetupViewModel(configStore, secretStore, logged::add)
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("new-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        // Without a rollback snapshot the save must not mutate anything.
        assertTrue(viewModel.state.value.saveFailed)
        assertEquals(0, writes)
        assertEquals(null, configStore.saved)
        assertTrue(logged.any { it.contains("previous configuration") })
    }

    @Test
    fun cancellationMidSaveCompletesTheTransactionConsistently() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val configStore = FakeConfigStore(existing)
        val secretStore = object : SecretStore {
            var stored: StoredSecret? = storedFor(existing, "old-secret")
            override suspend fun readSecret(): StoredSecret? = stored
            override suspend fun writeSecret(secret: StoredSecret) {
                gate.await() // Holds the write so the test can cancel mid-way.
                stored = secret
            }
        }
        val viewModel = SetupViewModel(configStore, secretStore) {}
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged("https://other.twil.io/token")
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("new-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        // The config write landed; the secret write is parked on the gate.
        assertEquals("https://other.twil.io/token", configStore.saved?.endpointUrl)
        // The activity finishing cancels the scope mid-save. The mutation
        // phase is NonCancellable — cancellation can't tell which durable
        // writes already committed, so a guessed rollback could itself
        // break the pairing — and the transaction runs to completion.
        viewModel.viewModelScope.cancel()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        // Both halves of the new account are on disk: a consistent new
        // pair, never new config against the old secret or the reverse.
        assertEquals("new-secret", secretStore.stored?.secret)
        assertEquals("https://other.twil.io/token", configStore.saved?.endpointUrl)
    }

    @Test
    fun loadFailedFormSavesWithoutASnapshotAsPromised() = runTest(dispatcher) {
        // A persistently unreadable but still writable store: the flow
        // always throws (a wrong-typed value bypasses the corruption
        // handler), while save() works.
        val configStore = object : ConfigStore {
            override val config = kotlinx.coroutines.flow.flow<SetupConfig?> {
                throw IllegalStateException("wrong-typed preference")
            }
            var saved: SetupConfig? = null
            override suspend fun save(config: SetupConfig) {
                saved = config
            }
        }
        val secretStore = FakeSecretStore()
        val logged = mutableListOf<String>()
        val viewModel = SetupViewModel(configStore, secretStore, logged::add)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.loadFailed)
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("a-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle()
        // The form promised that saving overwrites the unreadable
        // settings; the save must honor that instead of aborting on the
        // same read failure that caused the promise — otherwise the user
        // is wedged forever.
        assertTrue(viewModel.state.value.saved)
        assertEquals(existing, configStore.saved)
        assertEquals("a-secret", secretStore.stored?.secret)
    }

    @Test
    fun aSecondSaveWhileOneIsInFlightIsRefused() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var configSaves = 0
        val configStore = object : ConfigStore {
            override val config = MutableStateFlow<SetupConfig?>(null)
            override suspend fun save(config: SetupConfig) {
                configSaves++
                gate.await()
            }
        }
        var secretWrites = 0
        val secretStore = object : SecretStore {
            override suspend fun readSecret(): StoredSecret? = null
            override suspend fun writeSecret(secret: StoredSecret) {
                secretWrites++
            }
        }
        val viewModel = SetupViewModel(configStore, secretStore) {}
        testScheduler.advanceUntilIdle()
        viewModel.onEndpointChanged(existing.endpointUrl)
        viewModel.onIdentityChanged(existing.identity)
        viewModel.onSecretChanged("a-secret")
        viewModel.onSave()
        testScheduler.advanceUntilIdle() // First save parked on the config gate.
        assertTrue(viewModel.state.value.saving)
        viewModel.onSecretChanged("edited-mid-save")
        viewModel.onSave() // Must be refused while the first is in flight.
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, secretWrites)
        assertEquals(1, configSaves)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun secretOnlySaveStillRefusesABlankSecret() = runTest(dispatcher) {
        val viewModel = SetupViewModel(FakeConfigStore(existing), FakeSecretStore())
        testScheduler.advanceUntilIdle()
        viewModel.onSave()
        assertEquals(
            SetupError.MISSING,
            viewModel.state.value.errors[SetupField.SECRET],
        )
    }
}
