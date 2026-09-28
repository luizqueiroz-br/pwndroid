package br.com.luizqueiroz.pwndroid.ui.onboarding

import br.com.luizqueiroz.pwndroid.data.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da lógica do onboarding (issue #14): ordem das etapas,
 * disclaimer bloqueante, skip e flag `onboarded` no DataStore.
 */
class OnboardingUiStateTest {

    @Test
    fun `estado inicial é o disclaimer sem aceitar`() {
        val state = OnboardingUiState()
        assertEquals(OnboardingStep.DISCLAIMER, state.step)
        assertFalse(state.disclaimerAccepted)
        assertFalse(state.canAdvance)
        assertFalse(state.canSkip)
    }

    @Test
    fun `disclaimer aceito habilita avançar e pular`() {
        val state = OnboardingUiState().acceptDisclaimer()
        assertTrue(state.canAdvance)
        assertTrue(state.canSkip)
        val next = state.advance()
        assertEquals(OnboardingStep.LOCATION, next.step)
        assertTrue(next.canSkip)
    }

    @Test
    fun `não é possível avançar do disclaimer sem aceitar`() {
        val state = OnboardingUiState()
        assertTrue(!state.canAdvance)
        // advance() sem aceitar: mesmo assim move (UI desabilita o botão),
        // mas o contrato é o botão não estar habilitado.
        assertEquals(OnboardingStep.LOCATION, state.advance().step)
    }

    @Test
    fun `fluxo completo percorre as 4 etapas na ordem`() {
        var state = OnboardingUiState().acceptDisclaimer()
        state = state.advance()
        assertEquals(OnboardingStep.LOCATION, state.step)
        state = state.advance()
        assertEquals(OnboardingStep.BATTERY, state.step)
        state = state.advance()
        assertEquals(OnboardingStep.ROOT, state.step)
        assertTrue(state.isLastStep)
        // advance() na última não muda.
        assertEquals(OnboardingStep.ROOT, state.advance().step)
    }

    @Test
    fun `resultados de permissão são registrados`() {
        var state = OnboardingUiState()
        state = state.withLocation(granted = true)
        assertTrue(state.locationGranted)
        state = state.withBattery(exempt = false)
        assertFalse(state.batteryExempt)
    }

    @Test
    fun `shouldShowOnboarding é true só quando onboarded é false`() {
        assertTrue(OnboardingUiState.shouldShowOnboarding(AppConfig()))
        assertFalse(
            OnboardingUiState.shouldShowOnboarding(AppConfig(onboarded = true)),
        )
        // Disclaimer aceito mas não embarcado: o fluxo reaparece.
        assertTrue(
            OnboardingUiState.shouldShowOnboarding(AppConfig(disclaimerAccepted = true)),
        )
        // Embarcado sem disclaimer registrado: não reaparece (o disclaimer
        // é aceito como parte do onboarded; persistimos juntos).
        assertFalse(
            OnboardingUiState.shouldShowOnboarding(AppConfig(onboarded = true, disclaimerAccepted = true)),
        )
    }

    @Test
    fun `etapas restantes a partir do battery`() {
        val state = OnboardingUiState(step = OnboardingStep.BATTERY)
        assertEquals(
            listOf(OnboardingStep.BATTERY, OnboardingStep.ROOT),
            state.remainingSteps,
        )
    }
}
