# Projeto SD 2026/2 – Cliente/Servidor em camadas, exclusão mútua distribuída e replicação

Java + sockets TCP. 2 clientes, 1 balanceador (round-robin), 3 servidores de aplicação, 2 servidores de dados
com consistência por Ricart-Agrawala + replicação síncrona.

## Como rodar (Linux/macOS/WSL/Git Bash, JDK 17+)

    ./scripts/run_all.sh     # compila e sobe tudo (saídas em logs/*.out)
    ./scripts/stop_all.sh    # encerra com limpeza e verifica a consistência

## Estrutura

    src/common        Config, Protocol, Net, PrimeUtil
    src/client        Client
    src/loadbalancer  LoadBalancer
    src/appserver     AppServer, Worker
    src/dataserver    DataServer, FileStore, DistributedMutex
    src/tools         Verifier
    scripts/          build.sh, run_all.sh, stop_all.sh
    data/             data1.txt, data2.txt (gerados)
    logs/             client*.log (sorteios) e *.out (saída dos processos)
    docs/             PASSO_A_PASSO.md (decisões, protocolo, testes)

Detalhes de projeto e justificativas: `docs/PASSO_A_PASSO.md`.
