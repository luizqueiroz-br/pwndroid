# Matriz de dispositivos

> ⚠️ **Aviso legal**: métodos com root (Nexmon, QCACLD, bettercap) destinam-se apenas a testes em redes próprias ou com autorização explícita.

Métodos de rádio: **passivo** (sem root), **bettercap** (binário ARM via root), **Nexmon** (Broadcom + Magisk), **QCACLD** (Qualcomm, `con_mode=4`), **ESP32** (via USB OTG, firmware em repo irmão).

| Chipset | Método | Monitor mode | Injeção (deauth/assoc) | Notas |
|---|---|---|---|---|
| Broadcom (Pixel 2–9, alguns OnePlus/Xiaomi) | Nexmon | ✅ (com Magisk + Nexmon patch) | ✅ (experimental) | Melhor caminho nativo; requires `needs-root` |
| Qualcomm (WLAN Atheros) | QCACLD `con_mode=4` | ✅ (frágil, perde Wi-Fi normal) | parcial | Frágil: pode derrubar a conectividade; `spike` |
| Qualquer (com root) | bettercap ARM | ✅ (driver em userland) | ✅ | Mesmo software do pwnagotchi original; binário empacotado no APK |
| Qualquer (sem root) | Passivo (WifiManager) | ❌ (scan API apenas) | ❌ | Sustenta v0.1: wardrive + tamagotchi; BLE via BluetoothLeScanner |
| Qualquer | ESP32 via OTG | ✅ (firmware) | ✅ | Sem root no Android; firmware próprio (repo irmão) |

## Resultados da comunidade

> Edite esta seção (ou abra PR) com o seu dispositivo. Formato: `Dispositivo | Android | Método | Funciona? | Notas`.

- *(nenhum resultado ainda — seja o primeiro!)*

## Como testar

1. Ative as opções de desenvolvedor e o modo de depuração USB.
2. Instale o APK (GitHub Releases) e rode o onboarding.
3. No app, veja qual backend o `BackendSelector` detectou (tela de diagnóstico).
4. Abra uma issue com o template `spike` descrevendo chipset (`adb shell getprop ro.board.platform`), Android version e resultado.