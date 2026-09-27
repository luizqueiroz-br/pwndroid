# Arquitetura

> ⚠️ **Aviso legal**: o pwndroid destina-se exclusivamente a testes de segurança em redes **próprias ou com autorização explícita**. Captura de handshakes de terceiros sem autorização é crime (art. 154-A CP / CFAA). O uso é de responsabilidade do usuário.

## Diagrama de módulos

```
pwndroid/
├── app/                      # UI Compose + DI + FGS wiring (o mais fino possível)
├── core/
│   ├── model/                # Kotlin puro: AccessPoint, Personality, PwnMode, RadioEvent
│   ├── common/               # dispatchers, logging, clock, EventBus
│   ├── radio/                # RadioBackend API + BackendSelector + FakeRadioBackend
│   │   ├── passive/          # WifiManager/BLE scan (sem root) — sustenta v0.1
│   │   ├── bettercap/        # binário bettercap ARM via root (REST/WS, igual ao original)
│   │   ├── native/           # Nexmon / QCACLD con_mode=4 via root (experimental)
│   │   └── esp32/            # OTG serial (experimental, firmware em repo irmão)
│   ├── brain/                # Brain API + ThompsonSamplingBrain (+ a2c/ opcional, flag-gated)
│   ├── mood/                 # porte de automata.py (lonely/bored/sad/angry/excited/grateful/happy)
│   ├── session/              # EpochOrchestrator (porte de agent.py)
│   ├── capture/              # PcapWriter (linktype 105), HandshakeSlicer, dedup
│   ├── identity/             # ed25519 + Keystore (pwngrid)
│   └── pwngrid/              # cliente oPwngrid
├── data/                     # Room (epochs/sightings/whitelist/brain arms) + DataStore
├── plugins/
│   ├── api/                  # Plugin/PluginHost/PluginContext (compile-time, Koin multibinding)
│   └── builtin/              # gps, wpa-sec, onlinehashcracking, wigle, discord
└── feature/
    ├── display/              # FaceRenderer Compose, 1 FPS, kaomoji + FaceState
    └── webapi/               # Ktor server CIO local, Basic Auth
```

## Justificativa das fronteiras

| Fronteira | Por quê |
|---|---|
| `RadioBackend` (`:core:radio`) | **Única fronteira com hardware**. Cérebro/orquestrador nunca tocam em WifiManager/su/bettercap — trocam eventos e operações suspend. Isso torna o cérebro testável no JVM puro e permite os 4 backends sem espalhar `if (root)`. |
| `:core:*` Kotlin JVM puro | Toda a lógica (cérebro, humores, orquestrador, captura) roda em testes JUnit sem device/emulador. O Android vive só em `:app`, `:data` e `:feature:*`. |
| `:app` fino | Nenhuma lógica de negócio; apenas wiring (DI, FGS, permissões, navegação). |
| `:data` único módulo Android | Room e DataStore precisam de Context; isolar aqui impede que Room "vaze" para o domínio. |
| `:plugins:api` separado do `:plugins:builtin` | O contrato do plugin é estável; plugins builtin evoluem mais rápido sem quebrar plugins de terceiros. |
| `:feature:display` / `:feature:webapi` | A UI do tamagotchi e a API web são consumidores do domínio, nunca fontes de verdade. |
| EventBus (`:core:common`) | Desacopla rádio → sessão → cérebro → humores → UI sem dependências diretas; Flux<*> com buffer. |

## Fluxo de uma época

1. `EpochOrchestrator` pede a personalidade ao `Brain` (`nextPersonality()`).
2. Fase RECON: backend emite `RadioEvent.ApSeen`/`StaSeen` no EventBus.
3. Cérebro elege o alvo (`selectTarget(candidates)`).
4. Fase INTERACT: `associate()` (PMKID) e `deauth()` conforme a personalidade.
5. Backend captura EAPOL, escreve PCAP (`ESSID_BSSID.pcap`, linktype 105) e emite `HandshakeDetected` com o caminho.
6. Fase REPORT: `reportEpochResult(EpochResult)` alimenta o aprendizado; `MoodAutomata` atualiza o humor.
