#!/usr/bin/env bash
#
# Validação dos fixtures de PCAP da :core:capture (issue #25).
#
# Converte o pcap golden para o formato hashcat 22000 com o hcxpcapngtool
# e prova que o hash resultante é crackeável: hashcat -m 22000 recupera a
# senha conhecida do golden ("Induction") contra um potfile isolado.
#
# Uso local (o CI usa este mesmo script):
#   docker run --rm -v "$PWD":/work -w /work kalilinux/kali-rolling \
#     bash scripts/validate-pcap.sh
#
set -euo pipefail

PCAP="core/capture/src/test/resources/fixtures/wpa-Induction.pcap"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
HASH="$WORKDIR/golden.hc22000"
POTFILE="$WORKDIR/potfile"
WORDLIST="$WORKDIR/wordlist.txt"

fail() { echo "FALHA: $*" >&2; exit 1; }

command -v hcxpcapngtool >/dev/null || fail "hcxpcapngtool não instalado (pacote hcxtools)"
command -v hashcat >/dev/null || fail "hashcat não instalado"

[ -f "$PCAP" ] || fail "fixture não encontrado: $PCAP"

# 1. Conversão pcap -> hashcat 22000.
hcxpcapngtool -o "$HASH" "$PCAP" >"$WORKDIR/hcx.log" 2>&1 || fail "hcxpcapngtool não converteu o golden"
[ -s "$HASH" ] || fail "hash 22000 vazio — conversão não produziu EAPOL pairs"

# O hash precisa conter o MIC do golden (campo 1) — sanity check que
# independe da versão do hcxpcapngtool.
grep -q "a462a7029ad5ba30b6af0df391988e45" "$HASH" || fail "MIC do golden ausente no hash convertido"

# 2. hashcat -m 22000 recupera a senha conhecida do golden.
printf 'errado1\nerrado2\nInduction\nerrado3\n' >"$WORDLIST"
hashcat -m 22000 "$HASH" "$WORDLIST" \
  --potfile-path="$POTFILE" --force -D 1 --quiet
hashcat -m 22000 "$HASH" --potfile-path="$POTFILE" --show >"$WORKDIR/recovered.txt"

if grep -q ":Induction$" "$WORKDIR/recovered.txt"; then
    echo "OK: hash 22000 do golden é crackeável (MIC a462a7029ad5ba30b6af0df391988e45, senha 'Induction')"
else
    cat "$WORKDIR/recovered.txt" >&2 || true
    fail "hashcat não recuperou a senha 'Induction' do hash convertido"
fi
