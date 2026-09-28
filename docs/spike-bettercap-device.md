# Spike #22 — bettercap ARM em device rooted (go/no-go do v0.2)

> ⚠️ **Aviso legal**: os passos abaixo destinam-se a testes em redes **próprias ou com autorização explícita**.

## Status

**API/CLI validados contra bettercap real** (parte local do spike); **device físico pendente** — siga o checklist abaixo e registre o resultado.

## Achados (validados contra bettercap v2.41.7 real)

A parte da amarração (REST + WebSocket) foi validada contra um bettercap de verdade em Linux (mesma API que o ARM executa):

1. **Flags do processo**: a v2.41 não tem flags `-api-rest*`. A API sobe via:
   ```
   bettercap -iface <iface> -no-colors -no-history \
     -eval "set api.rest.address 127.0.0.1; set api.rest.port 8081; \
            set api.rest.username pwndroid; set api.rest.password pwndroid; \
            set api.rest.websocket true; set wifi.handshakes.file '<path>'; api.rest on"
   ```
   - `api.rest.address` aceita **apenas IP** (regex `^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$`); a porta é separada (`api.rest.port`).
2. **`/api/events` só é WebSocket com `set api.rest.websocket true`** (default `false` = streaming HTTPS de um array de eventos). O cliente Ktor WS do pwndroid exige o modo WS.
3. **Sessão** (`GET /api/session`): APs em `wifi.aps`; **clientes aninhados** em cada AP (`clients`), sem lista plana de STAs. O campo de essid do AP serializa como **`hostname`** (`ESSID()` → `endpoint.Hostname`).
4. **Evento `wifi.client.handshake`**: `{"file", "new_packets", "ap": "BSSID", "station": "MAC", "half", "full", "pmkid"}` — `ap`/`station` são **strings MAC**, `pmkid` é bytes (null quando PMKID attack não aplicável). Payload aninhado em `data` do frame WS.
5. **Eventos de handshake só são emitidos** quando há pacotes novos e (`pmkid` != null ∨ half-handshake de STA ≠ própria ∨ full handshake) — PMKID de todos zeros é descartado pelo bettercap.
6. Frames WS do `/api/events`: `{"tag", "time", "data"}`; ping do server a cada ~54 s (responda pong — o Ktor responde automaticamente).

Todos os 4 desvios contra o código da #20/#21 foram **corrigidos e cobertos por testes** (fixtures no formato real).

## Checklist device (preencha e abra a issue com o resultado)

```
# 1. root + binário
adb push bettercap-arm64 /data/local/tmp/pwndroid/bettercap   # via app: automático
adb shell "su -c 'ls -la /data/local/tmp/pwndroid/bettercap'"
adb shell "su -c 'getenforce'"                                 # Enforcing?

# 2. monitor mode
adb shell "su -c 'iw dev'"                                     # iface base
adb shell "su -c 'iw dev wlan0 interface add mon0 type monitor'"
adb shell "su -c 'ip link set mon0 up'"

# 3. bettercap + recon
adb shell "su -c '/data/local/tmp/pwndroid/bettercap -iface mon0 -no-colors -no-history \
  -eval \"set api.rest.address 127.0.0.1; set api.rest.port 8081; \
  set api.rest.username pwndroid; set api.rest.password pwndroid; \
  set api.rest.websocket true; api.rest on; wifi.recon on\"'"
# → APs aparecem em GET /api/session (wifi.aps)? (ap: curl -u pwndroid:pwndroid
#   http://127.0.0.1:8082/api/session via adb forward tcp:8082 tcp:8081)

# 4. deauth num alvo de teste (REDE PRÓPRIA!)
#    su -c '...' -eval "...; wifi.recon.channel 6; wifi.deauth <STA-MAC>"
# → evento wifi.client.handshake no /api/events?

# 5. pcap legível
adb shell "su -c 'ls -la /data/local/tmp/pwndroid/handshakes/'"
adb shell "su -c 'cat <pcap> | head -c 4 | xxd'"               # magic a1b2c3d4?
```

| Item | Resultado | Notas |
|---|---|---|
| binário executa (`-version`) | ⬜ | SELinux? |
| monitor mode (`mon0` up) | ⬜ | chipset/Nexmon? |
| `wifi.recon on` vê APs | ⬜ | qtde de APs em 60 s |
| `wifi.deauth` gera handshake | ⬜ | rede própria |
| pcap legível no app | ⬜ | permissões? |

## Matriz de dispositivos

Registre em [docs/matriz-de-dispositivos.md](matriz-de-dispositivos.md) — seção "Resultados da comunidade".

