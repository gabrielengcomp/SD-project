# Passo a passo da implementação – Projeto SD 2026/2

Documento vivo: cada etapa registra **o que foi feito, por quê e como validar**. Serve também de roteiro para a apresentação de 29/10.

## Visão geral

```
Cliente 1 ─┐                      ┌─ AppServer 0 ─┐
           ├─► Balanceador ──────►├─ AppServer 1 ─┼─► DataServer 0 ◄──► DataServer 1
Cliente 2 ─┘   (round-robin)      └─ AppServer 2 ─┘   (sorteado)     (mutex + réplica)
```

Todos os elementos são processos Java independentes, comunicando-se **apenas por sockets TCP** em `localhost`.

| Componente | Classe | Porta |
|---|---|---|
| Balanceador | `loadbalancer.LoadBalancer` | 5000 |
| Servidores de aplicação (3) | `appserver.AppServer <0\|1\|2>` | 6001–6003 |
| Servidores de dados (2) | `dataserver.DataServer <0\|1>` | 7001–7002 |
| Clientes (2) | `client.Client <id>` | – |

## Etapa 1 – Base comum (`src/common/`)

- `Config`: portas, intervalos de sleep, diretórios. Alterar uma porta ou intervalo é mudar um único arquivo.
- `Protocol`: mensagens textuais, uma por linha, campos separados por `;`.
- `Net`: `connect` com tentativas (os processos sobem em ordem, mas podem demorar), `request` (envia uma linha, lê uma linha).
- `PrimeUtil`: divisão por tentativa até √n (suficiente para n ≤ 1000).

**Protocolo**

| Trecho | Mensagem |
|---|---|
| Cliente → LB → App | `NUM;n` |
| App/LB → Cliente | `OK;<mensagem>` ou `ERR;<motivo>` |
| App → Data | `CHECK;n` |
| Data → App | `EXISTS;linha` ou `INSERTED;linha` |
| Data ↔ Data | `MUTEX_REQ;ts;id` → `MUTEX_OK` |
| Data ↔ Data | `REPLICATE;n;linha` → `REPLICATE_ACK` |

Cada interação usa uma conexão curta (abre, pergunta, lê a resposta, fecha), o que simplifica o código e evita estado de conexão.

## Etapa 2 – Servidor de dados, armazenamento (`FileStore`)

- Um arquivo por servidor (`data/data1.txt`, `data/data2.txt`), recriado vazio a cada execução; a 1ª inserção vai na linha 1.
- Índice em memória (`número → linha`) para a busca ser O(1). Cada escrita faz `flush` imediato.
- `applyReplica` confere se a linha da réplica bate com a local; se não bater, lança erro de divergência (detecta bugs de consistência cedo).

**Interpretação adotada:** os arquivos guardam **todos** os números sorteados (primos ou não). O enunciado exige que todo número distinto dos logs dos clientes esteja nos arquivos, e as mensagens do worker cobrem os casos "é" e "não é" primo.

## Etapa 3 – Consistência: exclusão mútua distribuída + replicação síncrona

Mecanismo escolhido: **Ricart-Agrawala para 2 nós** protegendo a sequência *verifica → insere → replica*.

Fluxo de um `CHECK;n` no servidor de dados:

1. Dorme 100–300 ms (como pede o enunciado).
2. `mutex.acquire()`: envia `MUTEX_REQ;ts;id` ao par e **bloqueia** até receber `MUTEX_OK`.
3. Dentro da seção crítica: procura `n`. Se existe → `EXISTS;linha`. Se não → insere, envia `REPLICATE;n;linha` ao par e **espera o ACK**.
4. `mutex.release()` e responde ao worker.

Detalhes do algoritmo (`DistributedMutex`):

- Relógio lógico de Lamport; prioridade = menor `(timestamp, id)`. O desempate por id evita deadlock quando os timestamps empatam.
- A resposta "adiada" do algoritmo é a própria thread de atendimento do par esperando em `wait/notifyAll` até o nó sair da seção crítica.
- Um `Semaphore` local (justo) garante que só uma requisição local por vez participe do protocolo.

Por que atende ao enunciado:

- **Sem corrida:** se dois workers enviam o mesmo número a servidores diferentes ao mesmo tempo, a verificação e a escrita ficam dentro do lock, então só um insere; o outro vê `EXISTS`.
- **Consistência antes de novas requisições:** a réplica termina (ACK) antes de liberar o lock, e nenhuma outra requisição, em nenhum dos dois servidores, entra na seção crítica antes disso.
- **Arquivos idênticos:** mesmos valores nas mesmas linhas, não só o mesmo conjunto.

Trade-off a citar na apresentação: serializar todas as requisições (inclusive as que só leem) reduz a vazão, mas o enunciado já impõe que nada seja atendido durante a consistência, e o gargalo dominante é o sleep de 100–300 ms. Uma otimização possível (não adotada): responder sem lock a números já presentes, já que o arquivo só cresce.

## Etapa 4 – Servidor de aplicação (`AppServer`, `Worker`)

- `AppServer` aceita conexões do balanceador e abre **uma nova thread `Worker` por requisição**.
- `Worker`: (1) verifica a primalidade, (2) sorteia um servidor de dados (sem política específica), (3) envia `CHECK;n` e espera, (4) imprime a mensagem do enunciado, (5) responde `OK;<mensagem>` e termina. A thread só termina depois de imprimir.

Mensagens impressas:
- `O número X é (não é) primo, não está presente no arquivo, e foi inserido na linha Y`
- `O número X é (não é) primo, e já se encontra no arquivo na linha Z`

## Etapa 5 – Balanceador (`LoadBalancer`)

- **Política: round-robin** com `AtomicInteger` (`floorMod` do contador). Justificativa: requisições de custo homogêneo (primalidade de n ≤ 1000 + uma ida ao servidor de dados), então revezar distribui a carga igualmente, sem estado nem consulta de carga; O(1) e thread-safe.
- Atua como **proxy**: repassa `NUM;n` ao app escolhido e devolve a resposta ao cliente.
- **Failover simples:** se o app escolhido estiver fora do ar, tenta o próximo.
- Imprime a distribuição acumulada a cada 50 requisições atendidas por servidor.

## Etapa 6 – Cliente (`Client`)

- Loop: sorteia `n` em [1, 1000], **grava `n` no log com flush**, envia ao balanceador, espera a resposta, dorme 50–200 ms.
- Esperar a resposta garante que nenhuma requisição fica sem confirmação; em caso de falha, reenvia até 3 vezes (a operação é idempotente).
- **Encerramento limpo:** o cliente checa a existência do arquivo `STOP` a cada iteração, termina a requisição em andamento e sai. Assim, ao derrubar o resto do sistema, nenhum número logado fica sem processar.

## Etapa 7 – Verificação (`tools.Verifier`)

Rodado com o sistema parado. Confere:

1. `data1.txt` e `data2.txt` idênticos (valores e ordem);
2. sem valores repetidos nos arquivos;
3. conjunto de números distintos dos logs dos clientes == conjunto dos arquivos de dados.

## Etapa 8 – Scripts

| Script | O que faz |
|---|---|
| `scripts/build.sh` | Compila tudo em `out/` |
| `scripts/run_all.sh` | Compila e sobe 2 dados, 3 apps, LB e 2 clientes em background (saídas em `logs/*.out`) |
| `scripts/stop_all.sh` | Cria `STOP`, espera os clientes, derruba o resto e roda o `Verifier` |

Sem bash (Windows puro): compile com `javac -encoding UTF-8 -d out (todos os .java de src)` e rode cada componente em um terminal, nesta ordem: `DataServer 0`, `DataServer 1`, `AppServer 0/1/2`, `LoadBalancer`, `Client 1`, `Client 2`, sempre com `java -cp out <classe> <args>`. Para parar, crie o arquivo `STOP` na pasta, aguarde os clientes encerrarem, feche os demais e rode `java -cp out tools.Verifier`.

## Etapa 9 – Testes realizados

| Teste | Resultado |
|---|---|
| Execução padrão (2 clientes, 30 s): 175 sorteios, 156 distintos | `RESULTADO: OK`, arquivos idênticos (156 linhas) |
| Estresse (6 clientes, ~25 s): 446 sorteios, 352 distintos | `RESULTADO: OK`, arquivos idênticos (352 linhas) |
| Distribuição do LB | `App0=50 App1=50 App2=50` |
| Erros nas saídas (`ERRO`/`DESISTIU`) | nenhum |

## Pendências e próximos passos

- [ ] Rodar um teste longo (alguns minutos) na máquina da dupla.
- [ ] Decidir se vale evoluir o balanceador para *least-connections* (ganho pequeno; round-robin já é justificável).
- [ ] Montar slides/diagrama (`docs/`) com: arquitetura, fluxo de uma requisição, balanceamento, consistência (diagrama de sequência do Ricart-Agrawala + réplica).
- [ ] Ensaiar a explicação do código (20 min) e dividir quem apresenta cada parte.
- [ ] Entregar o código no Teams até **28/10 às 23:59**.
