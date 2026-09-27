# Protocolo ESP32 (stub)

> Página de referência para a issue #45/[#46] (backend ESP32 via USB OTG). **Stub** — será preenchida durante o design do firmware.

## Visão

Um ESP32 com firmware próprio vira a "placa de rádio" do pwndroid: monitor mode + injeção sem exigir root no Android. O app conversa por USB OTG serial (usb-serial-for-android), trocando frames binários (802.11 capturados) e comandos.

## Bocetos do protocolo

- **Transporte**: CDC-ACM a 921600 baud (ou USB bulk), framing com magic byte + length + CRC16.
- **Comandos**: `START_RECON`, `SET_CHANNEL`, `DEAUTH`, `ASSOC`, `STOP`, `PING`.
- **Eventos**: `AP_SEEN`, `STA_SEEN`, `EAPOL_FRAME`, `RAW_FRAME`, `ACK`.
- Frames EAPOL chegam crus; o `HandshakeSlicer` e o `PcapWriter` do pwndroid fazem o resto — o firmware nunca formata PCAP.

## Status

- [ ] Firmware v0 (esqueleto) — repo irmão
- [ ] Backend `esp32/` em `:core:radio` — issue #46
- [ ] Testes de integração com hardware real