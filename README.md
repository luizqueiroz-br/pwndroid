# pwndroid

> (⓿_⓿) — Um [pwnagotchi](https://pwnagotchi.org/) para Android.

**pwndroid** é um tamagotchi hacker que roda no seu Android: ele fareja redes Wi-Fi,
captura handshakes WPA (compatíveis com hashcat), aprende com o ambiente e
se alimenta das redes que você pwna — com humores, personalidade e um rostinho
que reage a tudo, igual ao original para Raspberry Pi.

```
   (◕‿◕)  epoch 42 | ch 6 | 7 APs | 3 hs | mood: excited
```

## ⚠️ Aviso legal

Este projeto é destinado **exclusivamente para testes de segurança autorizados** —
por exemplo, em redes que **você possui** ou nas quais tem **permissão por escrito**
para testar. Capturar handshakes de redes de terceiros sem autorização é crime na
maioria das jurisdições (art. 154-A do Código Penal no Brasil, CFAA nos EUA,
Computer Misuse Act no Reino Unido, entre outras).

Os autores não se responsabilizam pelo uso indevido. **Você é o único responsável
por cumprir as leis do seu país.**

Funcionalidades que exigem root (monitor mode, deauth, associação) podem
**anular a garantia, instabilidade ou brickar o dispositivo**. Use por sua conta e risco.

## Como funciona

O pwnagotchi original é dividido em *cérebro* (Python) e *rádio* (bettercap),
comunicação via REST/WebSocket. O pwndroid mantém essa separação com uma
camada de abstração `RadioBackend`:

```
┌────────────┐   RadioEvent (Flow)   ┌──────────────────┐
│ RadioBackend│ ───────────────────▶ │ EpochOrchestrator │◀──▶ Brain (Thompson/A2C)
│  (4 impls)  │ ◀─────────────────── │  + MoodAutomata   │        │
└────────────┘    ops suspend        └────────┬─────────┘        ▼
                                            │            Personality
                                   PluginHost / EventBus
                                            │
                        Compose UI ── FaceRenderer ── Ktor Web API
```

### Backends de rádio

| Backend | Root? | Capacidade |
|---|---|---|
| `PassiveBackend` | ❌ | Wardriving (scan Wi-Fi + GPS), BLE scanning |
| `BettercapBackend` | ✅ | Recon, assoc (PMKID), deauth, captura EAPOL — melhorcap ARM empacotado |
| `NativeBackend` | ✅ | Nexmon (Broadcom/Cypress) ou truque QCACLD `con_mode` (Qualcomm) |
| `Esp32Backend` | ❌* | ESP32 externo via USB OTG faz o rádio; o app é cérebro + UI |

*o ESP32 precisa de firmware próprio (repo irmão `pwndroid-esp32`).

### Cérebro plugável

- **Thompson Sampling** (padrão): bandit bayesiano leve por parâmetro da
  personalidade (`recon_time`, `min_rssi`, canais...), roda em qualquer celular.
- **A2C** (experimental, flag-gated): porta da ideia do original — MLP treinado
  no próprio device, com fallback automático para Thompson.

### Saída compatível com o pwnagotchi

- PCAPs `ESSID_BSSID.pcap` (full/half handshake + PMKID) prontos para
  `hcxpcapngtool` / `hashcat -m 22000`.
- Cliente **oPwngrid** (api.pwnagotchi.ai): identidade ed25519, inbox, `report_ap` —
  interoperável com a comunidade Pi existente.

### Validação dos fixtures de PCAP

O golden `core/capture/src/test/resources/fixtures/wpa-Induction.pcap` é validado
a cada push no job `pcap-validation` do CI: o `hcxpcapngtool` converte o pcap
para o formato hashcat 22000 e o `hashcat -m 22000` recupera a senha conhecida
(`Induction`) de um potfile isolado. Qualquer regressão no formato do pcap
falha o CI. Para rodar a mesma validação localmente:

```bash
docker run --rm -v "$PWD":/work -w /work kalilinux/kali-rolling \
  bash -c "apt-get update -qq && apt-get install -y -qq hcxtools hashcat >/dev/null && bash scripts/validate-pcap.sh"
```

## Dispositivos suportados (modo root)

Monitor mode no Android depende do chipset — verifique antes:

- **Nexmon** (Broadcom/Cypress): Pixel 1–9, Nexus 5/5X/6/6P, Galaxy S4–S20/S22+
  e outros — [seemoo-lab/nexmon](https://github.com/seemoo-lab/nexmon) (alguns exigem SELinux permissivo)
- **Qualcomm QCACLD**: truque `con_mode` durante firmware reload (frágil, device-specific)
- **Qualquer device** com root: via backend bettercap ou ESP32 externo

A matriz detalhada de dispositivos testados fica na
[wiki](https://github.com/luizqueiroz-br/pwndroid/wiki).

## Build

```bash
git clone https://github.com/luizqueiroz-br/pwndroid.git
cd pwndroid
./gradlew assembleDebug   # requer JDK 17+
```

Distribuição via **GitHub Releases** e **F-Droid** (a Play Store proíbe apps com
funcionalidade de deauth).

## Roadmap

| Marco | Nome | Conteúdo |
|---|---|---|
| v0.1 | Wardrive & Tamagotchi | App sem root: wardriving, humores, rosto, FGS |
| v0.2 | Root + Captura | bettercap/native, captura de handshakes, Thompson |
| v0.3 | Plugins & Grid & Web UI | plugins, oPwngrid, API web local, ESP32 |
| v1.0 | AI + Release | A2C experimental, F-Droid, docs |

Detalhes nas [issues](https://github.com/luizqueiroz-br/pwndroid/issues) e
[milestones](https://github.com/luizqueiroz-br/pwndroid/milestones).

## Documentação

- [Arquitetura](docs/arquitetura.md) — módulos e fronteiras
- [Matriz de dispositivos](docs/matriz-de-dispositivos.md) — chipsets × métodos, resultados da comunidade
- [Protocolo ESP32](docs/protocolo-esp32.md) — stub do protocolo serial OTG

## Contribuindo

Veja [CONTRIBUTING.md](CONTRIBUTING.md). Issues marcadas com
`good first issue` são ótimos pontos de partida. Este projeto segue o
[Código de Conduta](CODE_OF_CONDUCT.md).

## Créditos e licença

- Inspirado no [pwnagotchi](https://github.com/evilsocket/pwnagotchi) de
  [evilsocket](https://github.com/evilsocket) (GPL-3.0) e no ecossistema
  [Pwnagotchi-Unofficial](https://github.com/Pwnagotchi-Unofficial).
- Rádio: [bettercap](https://github.com/bettercap/bettercap) (GPL-3.0),
  [Nexmon](https://github.com/seemoo-lab/nexmon).

Licença: **GPL-3.0** — veja [LICENSE](LICENSE).