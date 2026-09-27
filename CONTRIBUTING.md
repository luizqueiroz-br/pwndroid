# Como contribuir com o pwndroid

Obrigado pelo interesse! Este documento explica como montar o ambiente,
o fluxo de contribuição e as expectativas do projeto.

## Ambiente

- **JDK 17+** e **Android SDK** (via Android Studio ou `sdkmanager`)
- Dispositivo com root **não é necessário** para contribuir: todo o core
  (`:core:*`) é Kotlin JVM puro, testável no laptop com o `FakeRadioBackend`.
- Root só é necessário para validar backends de rádio reais (bettercap/nativo).

## Fluxo de trabalho

1. Escolha uma issue (as `good first issue` são as melhores para começar).
   Comente na issue que vai trabalhar nela para evitar duplicação.
2. Fork + branch descritivo: `feat/epoch-orchestrator`, `fix/pcap-writer-offset`, ...
3. Implemente com testes — código novo sem teste não é aceito no `:core:*`.
4. Rode localmente:
   ```bash
   ./gradlew detekt testDebugUnitTest assembleDebug
   ```
5. Abra o PR referenciando a issue (`Closes #123`), descrevendo *o que* e *por quê*.

## Padrões de código

- **Kotlin**, estilo oficial (`.editorconfig` do repo), `detekt` no CI como gate.
- Nenhuma lógica de negócio no módulo `:app` — UI é fino; cérebro vive no service.
- `RadioBackend` é a única fronteira com hardware; nada de chamadas de rádio
  fora de `:core:radio`.
- Módulos `:core:*` devem ser **Kotlin JVM puro** (sem imports de `android.*`),
  exceto os backends que precisam de APIs Android.
- Testes: Kotest/Turbine para Flows; fixtures de PCAP do pwnagotchi original
  para o pipeline de captura.
- Commits em português ou inglês, mensagem no imperativo ("Add EventBus").

## Testes em hardware

Se você tem um device rooted (Nexmon ou QCACLD) ou um ESP32, veja as issues
marcadas `spike` — são validações em hardware real que desbloqueiam épicos
inteiros. Reporte resultados na issue, mesmo negativos (a matriz de dispositivos
é colaborativa).

## Ética / uso responsável

Ferramentas deste projeto servem para **testes autorizados** (redes suas ou com
permissão por escrito). Contribuições que adicionem stealth/evasão de detecção
para uso contra terceiros não serão aceitas. Não publique dados capturados de
terceiros em issues/PRs (SSIDs, BSSIDs e senhas de redes alheias).

## Dúvidas?

Abra uma issue com a label `question` ou procure a issue de roadmap relevante.