package it.palsoftware.pastiera.inputmethod

import android.content.Context
import android.view.inputmethod.InputConnection
import it.palsoftware.pastiera.SettingsManager
import it.palsoftware.pastiera.core.SymLayoutController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardVisibilityControllerTest {

    @After
    fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.AUTO)
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, null)
        SoftwareKeyboardAutoDetector.onInputDevicesChanged()
    }

    @Test
    fun systemShowingInputView_evaluationDoesNotMutateSurface() {
        val harness = createHarness()

        assertTrue(harness.controller.onEvaluateInputViewShown(shouldShowInputView = true))
        assertEquals(0, harness.candidatesVisibilityChanges)
        assertFalse(harness.candidatesViewShown)
        assertTrue(harness.candidatesSurfaceActiveChanges.isEmpty())
        assertEquals(0, harness.postedActionCount)
        assertEquals(1, harness.statusBarRefreshes)
    }

    @Test
    fun systemHidingInputView_evaluationDoesNotShowCandidatesAsSideEffect() {
        val harness = createHarness()

        assertFalse(harness.controller.onEvaluateInputViewShown(shouldShowInputView = false))
        assertEquals(0, harness.candidatesVisibilityChanges)
        assertFalse(harness.candidatesViewShown)
        assertTrue(harness.candidatesSurfaceActiveChanges.isEmpty())
        assertEquals(0, harness.postedActionCount)
        assertEquals(1, harness.statusBarRefreshes)
        assertEquals(0, harness.candidatesContainerRefreshes)
    }

    @Test
    fun hardwareMode_ensureSurfaceShowsCandidatesWithoutRequestingSoftInput() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true
        )

        harness.controller.ensureImeSurfaceVisible()

        assertTrue(harness.candidatesViewShown)
        assertEquals(1, harness.candidatesVisibilityChanges)
        assertEquals(listOf(true), harness.candidatesSurfaceActiveChanges)
        assertEquals(listOf(false), harness.requestedInputViewShownChanges)
        assertTrue(harness.inputViewShowRequests.isEmpty())
        assertEquals(0, harness.inputViewAttachments)
    }

    @Test
    fun hardwareMode_repeatedPhysicalKeysDoNotRepeatCandidatesRequest() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.ensureImeSurfaceVisible()

        assertEquals(1, harness.candidatesVisibilityChanges)
        assertTrue(harness.inputViewShowRequests.isEmpty())
    }

    @Test
    fun hardwareMode_telegramRecoveryKeepsSingleLegacyShowRequestPending() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            requiresCandidatesSurfaceRecovery = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.ensureImeSurfaceVisible()

        assertEquals(1, harness.candidatesVisibilityChanges)
        assertEquals(1, harness.postedActionCount)
        assertTrue(harness.inputViewShowRequests.isEmpty())
        assertFalse(harness.controller.isExpectedSurfaceRequestedOrShown())

        harness.runPostedActions()

        assertEquals(listOf(false), harness.inputViewShowRequests)
    }

    @Test
    fun visibleTelegramHardwareInputDoesNotAddImmediateWholeWindowShow() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW,
            requiresCandidatesSurfaceRecovery = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onHardwareInputRequested()

        assertTrue(harness.inputViewShowRequests.isEmpty())
        assertEquals(1, harness.postedActionCount)

        harness.runPostedActions()

        assertEquals(listOf(false), harness.inputViewShowRequests)
    }

    @Test
    fun finishedTelegramSurfaceCancelsStaleRecoveryAndFreshHardwareInputRearmsIt() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW,
            requiresCandidatesSurfaceRecovery = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = false)
        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.HIDDEN)
        harness.runPostedActions()

        assertTrue(harness.inputViewShowRequests.isEmpty())
        assertEquals(1, harness.inputViewHideRequests)

        harness.controller.onHardwareInputRequested()

        assertEquals(listOf(false), harness.inputViewShowRequests)
        assertEquals(1, harness.postedActionCount)

        harness.runPostedActions()

        assertEquals(listOf(false, false), harness.inputViewShowRequests)
    }

    @Test
    fun hardwareMode_hiddenNonTelegramCandidatesDoNotUseLegacyShowRequest() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.HIDDEN
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.runPostedActions()

        assertTrue(harness.inputViewShowRequests.isEmpty())
        assertFalse(harness.controller.isExpectedSurfaceRequestedOrShown())

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW)

        assertTrue(harness.controller.isExpectedSurfaceRequestedOrShown())
    }

    @Test
    fun hardwareMode_staleTelegramRecoveryDoesNotClearNewerRequest() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            requiresCandidatesSurfaceRecovery = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.cancelPendingSurfaceTransition()
        harness.controller.ensureImeSurfaceVisible()
        harness.runPostedActions()

        assertEquals(listOf(false), harness.inputViewShowRequests)
        assertFalse(harness.controller.isExpectedSurfaceRequestedOrShown())
    }

    @Test
    fun hardwareMode_pastieraTelegramPastieraRearmsCandidatesWithoutSoftInput() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onImeWindowVisibilityChanged(shown = false)
        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onImeWindowVisibilityChanged(shown = false)
        harness.controller.ensureImeSurfaceVisible()

        assertTrue(harness.candidatesViewShown)
        assertEquals(5, harness.candidatesVisibilityChanges)
        assertTrue(harness.inputViewShowRequests.isEmpty())
    }

    @Test
    fun hardwareMode_newEditorKeepsAlreadyVisibleCandidatesSessionStable() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.ensureImeSurfaceVisible()

        assertTrue(harness.candidatesViewShown)
        assertEquals(1, harness.candidatesVisibilityChanges)
        assertTrue(harness.inputViewShowRequests.isEmpty())
    }

    @Test
    fun hardwareMode_windowHideAllowsNextKeyToStartFreshCandidatesSession() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onImeWindowVisibilityChanged(shown = false)
        harness.controller.ensureImeSurfaceVisible()

        assertTrue(harness.candidatesViewShown)
        assertEquals(3, harness.candidatesVisibilityChanges)
        assertTrue(harness.inputViewShowRequests.isEmpty())
    }

    @Test
    fun virtualMode_ensureSurfaceUsesFullInputViewRequest() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true
        )

        harness.controller.ensureImeSurfaceVisible()

        assertEquals(1, harness.inputViewAttachments)
        assertEquals(listOf(false), harness.inputViewShowRequests)
        assertFalse(harness.candidatesViewShown)
    }

    @Test
    fun hardwareMode_inputFocusShowsStatusEvenWhenFullKeyboardAutoShowIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness()

        assertTrue(harness.controller.shouldShowSurfaceOnInputStart(autoShowKeyboardEnabled = false))
    }

    @Test
    fun virtualMode_inputFocusStillRespectsFullKeyboardAutoShowSetting() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL
        )
        val harness = createHarness()

        assertFalse(harness.controller.shouldShowSurfaceOnInputStart(autoShowKeyboardEnabled = false))
        assertTrue(harness.controller.shouldShowSurfaceOnInputStart(autoShowKeyboardEnabled = true))
    }

    @Test
    fun explicitVirtualSurfaceChangeForcesInputViewFromCandidatesOnlyMode() {
        val harness = createHarness(currentInputConnection = mock(InputConnection::class.java))

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)

        assertFalse(harness.candidatesViewShown)
        assertEquals(1, harness.candidatesVisibilityChanges)
        assertEquals(listOf(true), harness.inputWindowShowRequests)
    }

    @Test
    fun repeatedVirtualSurfaceRequestRetriesWhenFirstRequestDidNotRender() {
        val harness = createHarness(currentInputConnection = mock(InputConnection::class.java))

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)
        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)

        assertEquals(listOf(true, true), harness.inputWindowShowRequests)
    }

    @Test
    fun renderedVirtualSurfaceDoesNotReopenWindow() {
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewShown = true
        )

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)

        assertTrue(harness.inputWindowShowRequests.isEmpty())
    }

    @Test
    fun virtualSurfaceRequestRetriesAfterFrameworkDidNotRenderIt() {
        val harness = createHarness(currentInputConnection = mock(InputConnection::class.java))

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)
        harness.runPostedActions()

        assertEquals(listOf(true, true), harness.inputWindowShowRequests)

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.FULL_INPUT_VIEW)
        harness.runPostedActions()

        assertEquals(listOf(true, true), harness.inputWindowShowRequests)
    }

    @Test
    fun failedSurfaceTransitionStopsAfterBoundedRetries() {
        val harness = createHarness(currentInputConnection = mock(InputConnection::class.java))

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)
        repeat(8) { harness.runPostedActions() }

        assertEquals(6, harness.inputWindowShowRequests.size)
        assertEquals(listOf(false), harness.requestedInputViewShownChanges)
        assertTrue(harness.controller.isCandidatesOnlySurface())
    }

    @Test
    fun cancelledSurfaceTransitionDoesNotRetry() {
        val harness = createHarness(currentInputConnection = mock(InputConnection::class.java))

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)
        harness.controller.cancelPendingSurfaceTransition()
        harness.runPostedActions()

        assertEquals(listOf(true), harness.inputWindowShowRequests)
    }

    @Test
    fun frameworkEvaluationKeepsResolvedSurfaceStateInSync() {
        val harness = createHarness(currentInputConnection = mock(InputConnection::class.java))

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)
        harness.controller.onEvaluateInputViewShown(shouldShowInputView = false)

        assertEquals(listOf(true), harness.inputWindowShowRequests)
    }

    @Test
    fun explicitHardwareSurfaceChangeRestartsAsCandidatesOnlyMode() {
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewShown = true
        )

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = false)

        assertFalse(harness.candidatesViewShown)
        assertEquals(1, harness.inputViewHideRequests)
        assertTrue(harness.inputWindowShowRequests.isEmpty())

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.HIDDEN)
        harness.controller.onImeWindowVisibilityChanged(shown = false)
        harness.runPostedActions()

        assertTrue(harness.candidatesViewShown)
        assertEquals(listOf(false), harness.requestedInputViewShownChanges)
        assertTrue(harness.inputWindowShowRequests.isEmpty())

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW)
        harness.runPostedActions()
        assertEquals(1, harness.candidatesContainerRefreshes)
    }

    @Test
    fun completedHardwareTransitionCanImmediatelyToggleBackToVirtual() {
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewShown = true
        )

        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = false)
        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)

        assertEquals(1, harness.inputViewHideRequests)
        assertTrue(harness.inputWindowShowRequests.isEmpty())
    }

    @Test
    fun externallyFinishedCandidatesCompletesTheWholeImeHide() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = false)

        assertTrue(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertTrue(harness.controller.shouldRecoverSurfaceOnHardwareKey())
        assertEquals(0, harness.inputViewHideRequests)

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.HIDDEN)
        harness.runPostedActions()

        assertEquals(1, harness.inputViewHideRequests)
    }

    @Test
    fun transientCandidatesRestartCancelsPendingWholeWindowHide() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = false)
        harness.controller.onCandidatesViewStarted()
        harness.runPostedActions()

        assertEquals(0, harness.inputViewHideRequests)
        assertFalse(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertEquals(listOf(true, false, true), harness.candidatesSurfaceActiveChanges)
        assertEquals(1, harness.statusBarRefreshes)
    }

    @Test
    fun frameworkCandidatesStartReactivatesCollapsedRootBeforeRefresh() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.HIDDEN
        )

        harness.controller.onCandidatesViewStarted()

        assertEquals(listOf(true), harness.candidatesSurfaceActiveChanges)
        assertEquals(0, harness.statusBarRefreshes)
        assertFalse(harness.controller.isExpectedSurfaceRequestedOrShown())

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW)

        assertTrue(harness.controller.isExpectedSurfaceRequestedOrShown())
    }

    @Test
    fun firstHardwareKeyRepairsRequestedButNotRenderedCandidatesAfterUnlock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.HIDDEN
        )

        // The framework started candidates while locked, but after unlock the child is absent and
        // no matching finish/start callback repairs it.
        harness.controller.onCandidatesViewStarted()

        assertTrue(harness.controller.shouldRecoverSurfaceOnHardwareKey())

        harness.controller.onHardwareInputRequested()

        assertTrue(harness.candidatesViewShown)
        assertEquals(1, harness.candidatesVisibilityChanges)
        assertEquals(1, harness.statusBarRefreshes)
        assertEquals(listOf(false), harness.inputViewShowRequests)
    }

    @Test
    fun editorRetapRepairsRequestedButNotRenderedCandidatesAfterUnlock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.HIDDEN
        )

        harness.controller.onCandidatesViewStarted()
        harness.controller.onExplicitShowRequested()

        assertTrue(harness.candidatesViewShown)
        assertEquals(1, harness.candidatesVisibilityChanges)
        assertEquals(1, harness.statusBarRefreshes)
        // onShowInputRequested is already the enclosing framework show request; only the child
        // needs to be reconciled here.
        assertTrue(harness.inputViewShowRequests.isEmpty())
    }

    @Test
    fun renderedCandidatesDoNotTriggerRedundantUnlockRecovery() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.onCandidatesViewStarted()

        assertFalse(harness.controller.shouldRecoverSurfaceOnHardwareKey())

        harness.controller.ensureImeSurfaceVisible()

        assertEquals(0, harness.candidatesVisibilityChanges)
        assertEquals(0, harness.statusBarRefreshes)
        assertTrue(harness.inputViewShowRequests.isEmpty())
    }

    @Test
    fun frameworkCandidatesFinishCollapsesRenderedChildEvenWhenInputFinishes() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = true)

        assertEquals(listOf(true, false), harness.candidatesSurfaceActiveChanges)
        assertEquals(0, harness.inputViewHideRequests)
    }

    @Test
    fun finishingInputReliesOnFrameworkHideWithoutIssuingAnotherRequest() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = true)
        harness.runPostedActions()

        assertEquals(0, harness.inputViewHideRequests)
        assertFalse(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
    }

    @Test
    fun internalCandidatesToFullTransitionDoesNotLookLikeUserDismissal() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onKeyboardSurfaceChanged(ensureInputViewShown = true)
        harness.controller.onCandidatesViewFinished(finishingInput = false)
        harness.runPostedActions()

        assertFalse(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertEquals(0, harness.inputViewHideRequests)
    }

    @Test
    fun firstHardwareKeyAfterDeliberateHideRecoversTheSurface() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = false)
        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.HIDDEN)
        harness.runPostedActions()

        assertTrue(harness.controller.shouldRecoverSurfaceOnHardwareKey())

        harness.controller.onHardwareInputRequested()

        assertFalse(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertFalse(harness.controller.isExpectedSurfaceRequestedOrShown())
        assertTrue(harness.candidatesViewShown)
        assertEquals(listOf(false), harness.inputViewShowRequests)

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW)

        assertTrue(harness.controller.isExpectedSurfaceRequestedOrShown())
    }

    @Test
    fun explicitEditorShowRequestAfterDeliberateHideAlsoRecoversTheSurface() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = false)
        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.HIDDEN)
        harness.runPostedActions()

        harness.controller.onExplicitShowRequested()

        assertFalse(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertFalse(harness.controller.isExpectedSurfaceRequestedOrShown())
        assertTrue(harness.candidatesViewShown)

        harness.renderSurface(KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW)

        assertTrue(harness.controller.isExpectedSurfaceRequestedOrShown())
    }

    @Test
    fun restartPreservesDeliberateHideButFreshInputClearsIt() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        harness.controller.ensureImeSurfaceVisible()
        harness.controller.onCandidatesViewFinished(finishingInput = false)
        harness.controller.onInputStarted(restarting = true)

        assertTrue(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertTrue(harness.controller.shouldRecoverSurfaceOnHardwareKey())

        harness.controller.onInputStarted(restarting = false)

        assertFalse(harness.controller.isCandidatesSurfaceExplicitlyDismissedForTests())
        assertTrue(harness.controller.shouldRecoverSurfaceOnHardwareKey())
    }

    @Test
    fun repeatedExternalShowHideCyclesRequestOneWholeHideEach() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(
            context,
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
        )
        val harness = createHarness(
            currentInputConnection = mock(InputConnection::class.java),
            inputViewActive = true,
            initialRenderedSurface = KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        )

        repeat(10) {
            harness.controller.onInputStarted(restarting = false)
            harness.controller.ensureImeSurfaceVisible()
            harness.controller.onCandidatesViewFinished(finishingInput = false)
            harness.renderSurface(KeyboardVisibilityController.RenderedSurface.HIDDEN)
            harness.runPostedActions()
            harness.renderSurface(KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW)
        }

        assertEquals(10, harness.inputViewHideRequests)
    }

    private fun createHarness(
        currentInputConnection: InputConnection? = null,
        inputViewShown: Boolean = false,
        inputViewActive: Boolean = false,
        initialRenderedSurface: KeyboardVisibilityController.RenderedSurface? = null,
        requiresCandidatesSurfaceRecovery: Boolean = false
    ): VisibilityHarness {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("keyboard_visibility_controller_test", Context.MODE_PRIVATE)
        val alternateCharacterManager = AlternateCharacterManager(context.assets, prefs, context)
        val symLayoutController = SymLayoutController(context, prefs, alternateCharacterManager)
        val candidatesBarController = CandidatesBarController(context)
        var candidatesViewShown = false
        var candidatesVisibilityChanges = 0
        val candidatesSurfaceActiveChanges = mutableListOf<Boolean>()
        var candidatesContainerRefreshes = 0
        var statusBarRefreshes = 0
        var inputViewAttachments = 0
        val requestedInputViewShownChanges = mutableListOf<Boolean>()
        val inputViewShowRequests = mutableListOf<Boolean>()
        val postedActions = mutableListOf<() -> Unit>()
        val inputWindowShowRequests = mutableListOf<Boolean>()
        var inputViewHideRequests = 0
        var renderedSurface = initialRenderedSurface ?: if (inputViewShown) {
            KeyboardVisibilityController.RenderedSurface.FULL_INPUT_VIEW
        } else {
            KeyboardVisibilityController.RenderedSurface.CANDIDATES_VIEW
        }

        val controller = KeyboardVisibilityController(
            context = context,
            candidatesBarController = candidatesBarController,
            symLayoutController = symLayoutController,
            isInputViewActive = { inputViewActive },
            hasActiveTextField = { false },
            isNavModeLatched = { false },
            currentInputConnection = { currentInputConnection },
            isInputViewShown = { inputViewShown },
            renderedSurface = { renderedSurface },
            requiresCandidatesSurfaceRecovery = { requiresCandidatesSurfaceRecovery },
            setRequestedInputViewShown = { requestedInputViewShownChanges += it },
            attachInputView = { inputViewAttachments += 1 },
            setCandidatesSurfaceActive = { candidatesSurfaceActiveChanges += it },
            setCandidatesViewShown = {
                candidatesViewShown = it
                candidatesVisibilityChanges += 1
            },
            synchronizeCandidatesContainerVisibility = {
                candidatesContainerRefreshes += 1
            },
            postToUi = { postedActions += it },
            postToUiDelayed = { _, action -> postedActions += action },
            showInputWindow = { shown -> inputWindowShowRequests += shown },
            requestHideInputView = { inputViewHideRequests += 1 },
            requestShowInputView = { inputViewShowRequests += false },
            refreshStatusBar = { statusBarRefreshes += 1 }
        )

        return VisibilityHarness(
            controller = controller,
            onCandidatesViewShown = { candidatesViewShown },
            onCandidatesVisibilityChanges = { candidatesVisibilityChanges },
            onCandidatesContainerRefreshes = { candidatesContainerRefreshes },
            runPostedActions = {
                postedActions.toList().also { postedActions.clear() }.forEach { it() }
            },
            onInputWindowShowRequests = { inputWindowShowRequests.toList() },
            onInputViewShowRequests = { inputViewShowRequests.toList() },
            renderSurface = { renderedSurface = it },
            onRequestedInputViewShownChanges = { requestedInputViewShownChanges.toList() },
            onCandidatesSurfaceActiveChanges = { candidatesSurfaceActiveChanges.toList() },
            onStatusBarRefreshes = { statusBarRefreshes },
            onPostedActionCount = { postedActions.size },
            onInputViewAttachments = { inputViewAttachments },
            onInputViewHideRequests = { inputViewHideRequests }
        )
    }

    private class VisibilityHarness(
        val controller: KeyboardVisibilityController,
        private val onCandidatesViewShown: () -> Boolean,
        private val onCandidatesVisibilityChanges: () -> Int,
        private val onCandidatesContainerRefreshes: () -> Int,
        val runPostedActions: () -> Unit,
        private val onInputWindowShowRequests: () -> List<Boolean>,
        private val onInputViewShowRequests: () -> List<Boolean>,
        val renderSurface: (KeyboardVisibilityController.RenderedSurface) -> Unit,
        private val onRequestedInputViewShownChanges: () -> List<Boolean>,
        private val onCandidatesSurfaceActiveChanges: () -> List<Boolean>,
        private val onStatusBarRefreshes: () -> Int,
        private val onPostedActionCount: () -> Int,
        private val onInputViewAttachments: () -> Int,
        private val onInputViewHideRequests: () -> Int
    ) {
        val candidatesViewShown: Boolean
            get() = onCandidatesViewShown()
        val candidatesVisibilityChanges: Int
            get() = onCandidatesVisibilityChanges()
        val candidatesContainerRefreshes: Int
            get() = onCandidatesContainerRefreshes()
        val inputWindowShowRequests: List<Boolean>
            get() = onInputWindowShowRequests()
        val inputViewShowRequests: List<Boolean>
            get() = onInputViewShowRequests()
        val statusBarRefreshes: Int
            get() = onStatusBarRefreshes()
        val requestedInputViewShownChanges: List<Boolean>
            get() = onRequestedInputViewShownChanges()
        val candidatesSurfaceActiveChanges: List<Boolean>
            get() = onCandidatesSurfaceActiveChanges()
        val postedActionCount: Int
            get() = onPostedActionCount()
        val inputViewAttachments: Int
            get() = onInputViewAttachments()
        val inputViewHideRequests: Int
            get() = onInputViewHideRequests()
    }
}
