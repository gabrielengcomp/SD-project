#!/usr/bin/env bash
# Sobe o sistema inteiro: 2 dados, 3 apps, 1 balanceador, 2 clientes (processos independentes).
cd "$(dirname "$0")/.."
./scripts/build.sh || exit 1
rm -f STOP .pids
mkdir -p logs data

start() { # nome classe args...
  local name=$1; shift
  java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp out "$@" > "logs/$name.out" 2>&1 &
  echo $! >> .pids
  echo "  $name (pid $!)"
}

echo "Subindo servidores de dados..."
start data0 dataserver.DataServer 0
start data1 dataserver.DataServer 1
sleep 1
echo "Subindo servidores de aplicacao..."
start app0 appserver.AppServer 0
start app1 appserver.AppServer 1
start app2 appserver.AppServer 2
sleep 1
echo "Subindo balanceador..."
start lb loadbalancer.LoadBalancer
sleep 1
echo "Subindo clientes..."
start client1 client.Client 1
start client2 client.Client 2
echo
echo "Sistema rodando. Saidas em logs/*.out. Para encerrar e verificar: ./scripts/stop_all.sh"
