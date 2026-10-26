#!/usr/bin/env bash
# Encerramento limpo: para os clientes via STOP, espera o que esta em andamento, derruba o resto, verifica.
cd "$(dirname "$0")/.."
touch STOP
echo "STOP criado; aguardando clientes terminarem a requisicao em andamento..."
for i in $(seq 1 30); do
  if ! pgrep -f "client.Client" > /dev/null; then break; fi
  sleep 0.5
done
sleep 1
if [ -f .pids ]; then
  while read -r pid; do kill "$pid" 2>/dev/null; done < .pids
  rm -f .pids
fi
sleep 1
rm -f STOP
echo "Verificando consistencia..."
java -cp out tools.Verifier
