package br.com.luizqueiroz.pwndroid.ui.onboarding

import br.com.luizqueiroz.pwndroid.data.AppConfig

/**
 * Etapas do onboarding (issue #14), em ordem. Só o disclaimer é
 * bloqueante: as demais podem ser puladas ("Pular para o app").
 */
enum class OnboardingStep {
    /** Disclaimer legal com checkbox obrigatório. */
    DISCLAIMER,

    /** Permissão de localização (necessária para o scan Wi-Fi). */
    LOCATION,

    /** Isenção de otimização de bateria (FGS de longa duração). */
    BATTERY,

    /** Root opcional: detectado ou modo passivo explicado. */
    ROOT,
}

/**
 * Estado do fluxo de onboarding (issue #14): etapa corrente + resultado
 * de cada pergunta. Lógica pura e testável; a tela só renderiza e
 * repassa os callbacks (start/stop do serviço, persistência no
 * [ConfigStore]).
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.DISCLAIMER,
    val disclaimerAccepted: Boolean = false,
    val locationGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    /** Root detectado no device (Magisk/su) — apenas informativo na v0.1. */
    val rootAvailable: Boolean = false,
) {
    /** Etapas restantes a partir da atual. */
    val remainingSteps: List<OnboardingStep>
        get() = OnboardingStep.entries.drop(OnboardingStep.entries.indexOf(step))

    /** Última etapa do fluxo. */
    val isLastStep: Boolean get() = step == OnboardingStep.ROOT

    /**
     * "Pular para o app": disponível em qualquer etapa, MENOS no
     * disclaimer (único passo bloqueante da issue #14).
     */
    val canSkip: Boolean get() = disclaimerAccepted

    /** O botão "Continuar" está habilitado na etapa atual. */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.DISCLAIMER -> disclaimerAccepted
            else -> true
        }

    /** Marca o disclaimer como aceito (não reversível no fluxo). */
    fun acceptDisclaimer(): OnboardingUiState = copy(disclaimerAccepted = true)

    /** Registra o resultado do pedido de permissão de localização. */
    fun withLocation(granted: Boolean): OnboardingUiState = copy(locationGranted = granted)

    /** Registra o resultado do pedido de isenção de bateria. */
    fun withBattery(exempt: Boolean): OnboardingUiState = copy(batteryExempt = exempt)

    /**
     * Avança uma etapa; no fim do fluxo não faz nada (o host grava
     * `onboarded = true` e mostra o app).
     */
    fun advance(): OnboardingUiState = when (step) {
        OnboardingStep.DISCLAIMER -> copy(step = OnboardingStep.LOCATION)
        OnboardingStep.LOCATION -> copy(step = OnboardingStep.BATTERY)
        OnboardingStep.BATTERY -> copy(step = OnboardingStep.ROOT)
        OnboardingStep.ROOT -> this
    }

    companion object {
        /** Onboarding roda só na primeira execução (flag no DataStore). */
        fun shouldShowOnboarding(config: AppConfig): Boolean = !config.onboarded
    }
}
