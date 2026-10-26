# Plano de apresentação (20 min, aula de 29/10)

A nota de apresentação (2 pontos) é clareza, mas o enunciado também exige explicar o código e justificar duas escolhas: a política de balanceamento e o mecanismo de consistência. O plano gasta o tempo em proporção ao peso na nota: consistência (3 pontos) ocupa 6 dos 20 minutos, e o balanceador (1 ponto), só 2.

## Estratégia geral

**Subam o sistema no início e deixem rodando enquanto falam.** Assim os logs e os arquivos já têm conteúdo quando vocês chegarem às demonstrações, e a verificação final fecha a apresentação com o sistema provado. Rodem com o terminal dividido em painéis:

```bash
./scripts/run_all.sh
tail -f logs/lb.out        # painel 1
tail -f logs/data0.out     # painel 2
tail -f logs/app0.out      # painel 3
```

**Divisão sugerida na dupla:**
- **Pessoa A:** abertura, fluxo, cliente, aplicação, balanceador (blocos 1 a 4).
- **Pessoa B:** consistência, robustez e verificação (blocos 5 a 7).
- **Os dois:** encerramento e perguntas (bloco 8). Cada um deve saber explicar a parte do outro, porque o professor pode perguntar a qualquer um.

## Roteiro por bloco

| # | Bloco | Tempo | Quem |
|---|---|---|---|
| 1 | Problema e arquitetura | 2 min | A |
| 2 | Fluxo de uma requisição e protocolo | 2 min | A |
| 3 | Cliente e servidor de aplicação | 2 min | A |
| 4 | Balanceador | 2 min | A |
| 5 | Consistência (o núcleo) | 6 min | B |
| 6 | Robustez | 2 min | B |
| 7 | Verificação e demonstração final | 2 min | B |
| 8 | Limitações e perguntas | 2 min | ambos |

### 1. Problema e arquitetura (2 min)
- **Mostrar:** diagrama `Clientes → Balanceador → 3 Apps → 2 Dados`, com a ligação entre os dois servidores de dados destacada.
- **Dizer:** o desafio não é calcular primalidade (isso é trivial); é manter duas cópias de um arquivo consistentes quando ambas aceitam escrita e o servidor de dados é sorteado. Tudo isso em processos independentes que só conversam por TCP.
- **Escolha a justificar:** todos os componentes são processos separados em portas diferentes, simulando máquinas distintas, como pede o enunciado.

### 2. Fluxo e protocolo (2 min)
- **Mostrar:** a tabela de mensagens (`NUM`, `CHECK`, `EXISTS/INSERTED`, `MUTEX_REQ`, `REPLICATE`) e, no painel do terminal, uma linha real de `logs/app0.out`.
- **Escolhas a justificar:**
  - **Protocolo textual, uma linha por mensagem:** fácil de depurar e de explicar; binário não traria ganho perceptível nesta escala.
  - **Conexão curta (abre, pergunta, lê a resposta, fecha):** elimina estado de sessão, então não há "conexão quebrada no meio" para tratar. O custo do handshake é irrelevante diante dos sleeps de 50 a 300 ms.
- **Arquivos:** `common/Protocol.java`, `common/Net.java`.

### 3. Cliente e servidor de aplicação (2 min)
- **Mostrar:** o laço do `Client.java` e o `Worker.java`.
- **Escolhas a justificar:**
  - **O cliente loga antes de enviar e espera a resposta:** dá confirmação ponta a ponta, e é isso que sustenta a garantia "nenhuma requisição perdida".
  - **Uma thread `Worker` por requisição:** exigência do enunciado (modelo *thread-per-request*); a thread só termina depois de imprimir.
  - **Primalidade por divisão até √n:** para n ≤ 1000 são no máximo cerca de 16 divisões, então algoritmos mais sofisticados não compensam.
  - **Sorteio do servidor de dados:** o enunciado dispensa política. Isso cria o cenário de concorrência que a próxima etapa resolve: o mesmo número pode chegar a servidores diferentes ao mesmo tempo.

### 4. Balanceador (2 min, vale 1 ponto)
- **Mostrar:** `LoadBalancer.java` (o `AtomicInteger` e a função `ordem`) e a linha `[LB] distribuição: App0=50 App1=50 App2=50` do painel.
- **Política: round-robin.** Justificativa: as requisições têm custo homogêneo (mesma primalidade, mesma ida ao servidor de dados), então revezar já é uma distribuição ótima. Não precisa de estado nem de consulta de carga, é O(1) e thread-safe. *Least-connections* só compensaria com custos variáveis ou servidores heterogêneos.
- **Proxy em vez de redirecionar o cliente:** o cliente só conhece o balanceador, o que preserva as camadas.
- **Circuit breaker:** o app que falha sai da rotação por 3 s (falha rápida com timeout de 1 s). Mostrar de forma opcional: `kill "$(sed -n 4p .pids)"` mata o app 1, e `grep "fora da rotação" logs/lb.out` mostra o balanceador reagindo sem perder requisições (leva 30 s se couber).
- **Limitação a admitir antes que perguntem:** o balanceador é ponto único de falha, como pede o enunciado (só há um).

### 5. Consistência (6 min, vale 3 pontos)
Este é o bloco mais importante; pratiquem-no mais.

**a) O problema (1 min).** Dois cenários de falha sem coordenação: *duplicata* (A e B recebem o mesmo número, ambos veem "não existe", ambos inserem) e *divergência de linhas* (inserções simultâneas na mesma linha). O enunciado ainda exige que nada seja atendido durante a consistência, então precisa de consistência forte.

**b) A escolha (1,5 min).** Exclusão mútua distribuída com Ricart-Agrawala protegendo a sequência *verifica, insere, replica*.
- **Mostrar:** a tabela de alternativas descartadas.

  | Alternativa | Por que não |
  |---|---|
  | Coordenador central | Ponto único de falha e um nó a mais fora da arquitetura pedida |
  | Token ring | Com 2 nós, o token ficaria passando à toa |
  | Primário-backup fixo | O servidor é sorteado, então o secundário viraria só um repassador |
  | 2PC, Paxos, Raft | Exagero para 2 nós sem falhas |
  | Lamport original | 3(N−1) mensagens; Ricart-Agrawala usa 2(N−1) |

**c) O mecanismo (2 min).** Desenhar o **diagrama de sequência** de dois pedidos simultâneos: ambos enviam `MUTEX_REQ` com `(timestamp, id)`, o de menor valor entra, o outro espera. Apontar no `DistributedMutex.java`:
- relógio de Lamport e o desempate por id (evita deadlock);
- a resposta adiada implementada como thread em `wait()` até a liberação;
- o semáforo local justo (um pedido por nó por vez).

**d) O código do servidor de dados (1 min).** Em `DataServer.handleCheck`, mostrar a ordem: `sleep`, `acquire`, `find`, `append`, `replicate` com ACK, `release` no `finally`.
- **Verificação dentro do lock:** evita a corrida de check-then-act.
- **Replicação síncrona antes de liberar:** a próxima verificação sempre vê o estado completo; é a escolha de consistência sobre latência.
- **Replicar a operação (linha nova), não o arquivo:** O(1) por escrita, e o número da linha permite detectar divergência na hora.
- **O sleep fica antes do lock**, para não segurar a seção crítica à toa.

**e) Corretude e custo (0,5 min).** Exclusão mútua (só entra com o `OK` do par), ausência de deadlock (ordem total por timestamp e id), ausência de inanição (o relógio cresce). Custo: serialização total das requisições e nenhuma tolerância a falha do par; dizer isso de forma proativa passa maturidade.

### 6. Robustez (2 min)
*(Só se vocês forem apresentar a branch `teste`; senão, pulem e ganhem tempo para o bloco 5.)*
- **Rollback da réplica:** se a réplica falhar, o servidor desfaz a escrita local e manda `UNDO` ao par. Sem isso, a falha deixaria os arquivos divergentes para sempre.
- **Cliente que reenvia até confirmar:** seguro porque a operação é idempotente (reenviar um número já inserido só devolve `EXISTS`).
- **`primos.log`:** lista só dos primos, sem repetição, escrita dentro da seção crítica para que as escritas nunca se sobreponham.
- **Limite honesto:** o rollback cobre o caso comum, não todos (o clássico problema dos dois generais).

### 7. Verificação e demonstração final (2 min)
```bash
./scripts/stop_all.sh
```
O `Verifier` mostra três verificações, e cada uma prova uma propriedade:
1. arquivos idênticos em valores e ordem: **consistência forte**;
2. sem repetidos: **exclusão mútua** funcionou;
3. distintos dos logs == distintos dos arquivos: **nenhuma requisição perdida**.

Terminar com `RESULTADO: OK` na tela. Se houver tempo, `cat data/data1.txt | head` e `cat logs/primos.log | head` mostram o resultado concreto.

### 8. Limitações e perguntas (2 min)
Citar vocês mesmos as limitações: balanceador como ponto único de falha, serialização total, servidor de dados reiniciado sozinho que sobe vazio, e nenhuma tolerância a queda do par. Admitir limite antes de ser perguntado costuma ser bem visto.

## Slides sugeridos (6 a 8, sem texto longo)
1. Arquitetura (diagrama)
2. Fluxo de uma requisição + tabela de mensagens
3. Balanceador: política e justificativa
4. O problema da consistência (cenário da duplicata)
5. Alternativas consideradas (tabela)
6. Diagrama de sequência do Ricart-Agrawala + réplica
7. Verificação: o que cada checagem prova
8. Limitações

## Perguntas prováveis (vale ensaiar a resposta curta)
- **Por que não um primário fixo?** O servidor é sorteado; a exclusão mútua simétrica trata os dois igualmente e não cria ponto único de falha no mecanismo.
- **Se dois pedidos chegam juntos?** Compara-se `(timestamp, id)`; o menor entra e o outro espera a liberação.
- **Por que replicação síncrona?** Para a próxima verificação sempre enxergar o estado completo, como o enunciado exige.
- **E se um servidor de dados cair?** O sistema bloqueia; tolerância a falha está fora do escopo, e a solução seria detecção por timeout ou um protocolo de consenso.
- **Por que round-robin?** Requisições homogêneas, então a divisão uniforme é ótima, com custo O(1) e sem estado.
- **Por que os arquivos guardam números não primos?** Porque o enunciado exige que todo número distinto dos logs esteja nos arquivos; a lista só de primos fica separada, no `primos.log`.

## Antes do dia
- Ensaiar cronometrado pelo menos uma vez; o bloco 5 costuma estourar.
- Rodar o sistema inteiro na máquina que será usada na sala, com o mesmo terminal e a mesma fonte grande.
- Ter um print do `RESULTADO: OK` e de uma execução prévia como plano B, caso a demonstração falhe.
- Alinhar com o professor se os arquivos devem guardar todos os números ou só os primos, para não ser pego de surpresa.

Se quiser, transformo esse roteiro em um documento para vocês editarem ou monto o diagrama de sequência do Ricart-Agrawala, que é o slide mais difícil de desenhar.
