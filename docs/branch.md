# Robustez e tolerância a falhas

Este documento descreve as alterações da branch `robustez-failover`: o que mudou, por que mudou, como testar e quais limites continuam.

## Resumo

| # | Alteração | Problema que resolve | Arquivos |
|---|---|---|---|
| 1 | Desfazer a escrita local se a réplica falhar | Arquivos dos servidores de dados ficavam divergentes para sempre | `DataServer`, `FileStore`, `Protocol` |
| 2 | Failover rápido + circuit breaker no balanceador | Cada requisição enviada a um app caído esperava cerca de 5 s | `LoadBalancer`, `Net`, `Config` |
| 3 | Cliente reenvia até receber confirmação | Após 3 falhas o cliente desistia: requisição perdida | `Client`, `Config` |
| 4 | `logs/primos.log` em tempo real | Lista dos primos armazenados, sem repetição, atualizada durante a execução | `DataServer`, `run_all.sh` |

## 1. Rollback da réplica

**Antes:** o servidor de dados gravava a linha no arquivo local e só depois replicava para o par. Se a réplica falhasse, a linha ficava só em um arquivo. Quando o cliente repetia a requisição, o número podia cair no outro servidor e ser inserido em outra linha, e o `applyReplica` acusava divergência.

**Agora**, em `handleCheck` (dentro da seção crítica do Ricart-Agrawala):

1. `store.append(n)` grava localmente.
2. `replicate(n, line)` envia ao par e espera o ACK.
3. Se `replicate` lançar `IOException`:
   - `store.rollbackLast(n)` remove a última linha do arquivo e do índice (a linha é liberada para a próxima inserção);
   - `desfazerNoPar(n)` envia `UNDO;n` ao par, em melhor esforço (500 ms de conexão, 2 s de leitura). Cobre o caso em que o par aplicou a réplica e só o ACK se perdeu. Se o par estiver fora do ar, a falha é ignorada;
   - a exceção segue adiante e o servidor responde `ERR` ao worker (antes, a conexão era largada sem resposta).

**Novo no protocolo:** `UNDO;<n>` → `UNDO_ACK`. O par só desfaz se a última linha do arquivo dele for `n`, o que é seguro porque quem pediu o desfazer ainda segura a seção crítica.

## 2. Failover rápido no balanceador

**Política:** continua round-robin. O que mudou foi o tratamento de falhas, que independe da política.

- **Uma tentativa por servidor**, com timeout de conexão de 1 s (`LB_CONNECT_TIMEOUT_MS`) e de leitura de 10 s (`LB_READ_TIMEOUT_MS`). Antes eram 20 tentativas com 250 ms de intervalo (cerca de 5 s) por servidor caído.
- **Circuit breaker:** o app que falha fica fora da rotação por 3 s (`LB_DOWN_MS`). Passado esse tempo, volta a ser testado; se responder, fica saudável de novo.
- **Ordem de tentativa:** round-robin a partir do contador, com os apps saudáveis primeiro e os marcados como fora por último. Um app marcado só é usado se todos os outros falharem.
- Se nenhum app responder, o LB devolve `ERR;nenhum servidor de aplicação disponível`.
- `Net.requestOnce(port, linha, connectTimeoutMs, readTimeoutMs)` é a nova chamada de tentativa única, usada pelo LB e pelo desfazer da réplica. O `Net.request` antigo (com retentativas) continua sendo usado na subida do sistema.

## 3. Cliente que não desiste

`Client.send` reenvia a mesma requisição até receber `OK`, com espera crescente de 200 ms, dobrando até 2 s (`CLIENT_RETRY_MIN_MS` e `CLIENT_RETRY_MAX_MS`). Como a operação é idempotente (um número já inserido responde "já se encontra no arquivo"), repetir é seguro. Quando uma requisição precisa de mais de uma tentativa, o cliente registra `confirmado após N tentativas`.

A única saída sem confirmação é o arquivo `STOP` (encerramento do sistema), registrada no log do cliente.

## 4. `logs/primos.log` em tempo real

Depois de inserir um número novo e receber o ACK da réplica, o servidor de dados chama `registrarPrimo(n)`, que acrescenta `n` a `logs/primos.log` se ele for primo.

- **Sem repetição:** só o servidor que faz a inserção original escreve (a réplica não), e cada número é inserido uma única vez no sistema.
- **Sem escrita concorrente:** a escrita acontece dentro da seção crítica distribuída, então os dois servidores nunca escrevem ao mesmo tempo.
- **Ordem:** de inserção, não ordenada. Para ver ordenado: `sort -n logs/primos.log`. Para acompanhar ao vivo: `tail -f logs/primos.log`.
- O `run_all.sh` apaga o arquivo no início de cada execução.

## Como testar

Rode o sistema e use os comandos abaixo em outro terminal.

```bash
./scripts/run_all.sh
```

**Queda de um servidor de aplicação:**

```bash
pkill -f "appserver.AppServer 1"
grep "fora da rotação" logs/lb.out
```

Esperado: poucas linhas `[LB] App 1 fora da rotação por 3000 ms` e nenhuma requisição perdida.

**Queda do balanceador por alguns segundos:**

```bash
pkill -f "loadbalancer.LoadBalancer"
sleep 4
java -cp out loadbalancer.LoadBalancer > logs/lb2.out 2>&1 &
grep "confirmado após" logs/client*.out
```

Esperado: os clientes reenviam e confirmam depois da volta do LB. O `stop_all.sh` não conhece o PID do LB reiniciado, então encerre-o manualmente (`pkill -f "loadbalancer.LoadBalancer"`).

**Verificação final** (o `stop_all.sh` já roda o `Verifier`):

```bash
./scripts/stop_all.sh
```

Esperado: `RESULTADO: OK`.

### Resultados obtidos

| Cenário | Resultado |
|---|---|
| App 1 morto no meio da execução + LB derrubado por cerca de 4 s | `Verifier` OK: 114 linhas idênticas nos dois arquivos, 114 números distintos nos logs e nos arquivos |
| Mesma execução | `primos.log` igual ao conjunto de primos de `data1.txt`, sem duplicatas |
| Mesma execução | O LB marcou o App 1 como fora da rotação logo nas primeiras falhas; os clientes registraram `confirmado após N tentativas` |
| Réplica falhando (par simulado que fecha a conexão ao receber `REPLICATE`), duas tentativas do mesmo número | `data1.txt` com 0 linhas ao final; o servidor enviou `UNDO;5` ao par; a linha 1 foi reutilizada na segunda tentativa; resposta `ERR` ao worker nas duas |

## Limitações conhecidas

- **Reiniciar um servidor de dados apaga o arquivo dele:** o `FileStore` trunca o arquivo ao subir, então um servidor reiniciado sozinho fica vazio enquanto o outro mantém o conteúdo. Não há ressincronização. Para uma demonstração, não derrube e suba só um servidor de dados.
- **O rollback cobre o caso comum, não todos:** se um servidor morrer exatamente entre a escrita local e a réplica, e o `UNDO` também não chegar ao par, os arquivos podem divergir. É o problema clássico dos dois generais (não dá para ter certeza de que a última mensagem chegou).
- **Servidor de dados fora do ar:** a aquisição do mutex falha (após as retentativas de `Net.connect`, cerca de 5 s) antes de qualquer escrita, então nada é gravado, mas as requisições falham e os clientes ficam reenviando até o servidor voltar.
- **O balanceador continua sendo ponto único de falha**, como pede o enunciado (um balanceador só).
- **Sem timeout nas conexões entre apps e servidores de dados:** o timeout de leitura foi aplicado apenas ao caminho do LB e ao desfazer da réplica.