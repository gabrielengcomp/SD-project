package dataserver;

import common.Config;
import common.Net;
import common.PrimeUtil;
import common.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Servidor de dados.  Uso: java dataserver.DataServer <id: 0 ou 1>
 *
 * Mensagens recebidas (uma por conexão):
 *   CHECK;n            (de um worker)   -> EXISTS;linha | INSERTED;linha
 *   MUTEX_REQ;ts;id    (do outro dados) -> MUTEX_OK (quando concede a vez)
 *   REPLICATE;n;linha  (do outro dados) -> REPLICATE_ACK
 *
 * Fluxo de um CHECK:
 *   1. dorme 100-300 ms
 *   2. entra na seção crítica distribuída (Ricart-Agrawala)
 *   3. verifica no arquivo; se não existe, insere e REPLICA no par, esperando o ACK
 *   4. sai da seção crítica e responde ao worker
 * Como a réplica termina antes da liberação, nenhuma outra requisição é atendida
 * enquanto os arquivos estão divergentes.
 */
public class DataServer {
    private static final Path PRIMOS_LOG = Path.of(Config.LOG_DIR, "primos.log");

    private final int id;
    private final int port;
    private final int peerPort;
    private final FileStore store;
    private final DistributedMutex mutex;

    public DataServer(int id) throws IOException {
        this.id = id;
        this.port = Config.DATA_PORTS[id];
        this.peerPort = Config.DATA_PORTS[1 - id];
        this.store = new FileStore(Path.of(Config.DATA_DIR, "data" + (id + 1) + ".txt"));
        this.mutex = new DistributedMutex(id, peerPort);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !(args[0].equals("0") || args[0].equals("1"))) {
            System.err.println("Uso: java dataserver.DataServer <0|1>");
            System.exit(1);
        }
        new DataServer(Integer.parseInt(args[0])).run();
    }

    public void run() throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(port)) {
            log("ouvindo na porta " + port + " | arquivo: " + store.getPath() + " | par: " + peerPort);
            while (true) {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        }
    }

    private void handle(Socket s) {
        try (s) {
            BufferedReader in = Net.reader(s);
            PrintWriter out = Net.writer(s);
            String line = in.readLine();
            if (line == null) return;
            String[] f = Protocol.parse(line, 3);
            switch (f[0]) {
                case Protocol.CHECK -> {
                    String r;
                    try {
                        r = handleCheck(Integer.parseInt(f[1]));
                    } catch (Exception e) { // responde ERR ao worker em vez de largar a conexão
                        log("falha em CHECK " + f[1] + ": " + e.getMessage());
                        r = Protocol.msg(Protocol.ERR, e.getMessage());
                    }
                    out.println(r);
                }
                case Protocol.MUTEX_REQ -> {
                    mutex.onPeerRequest(Long.parseLong(f[1]), Integer.parseInt(f[2]));
                    out.println(Protocol.MUTEX_OK);
                }
                case Protocol.REPLICATE -> {
                    store.applyReplica(Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                    log("réplica aplicada: " + f[1] + " na linha " + f[2]);
                    out.println(Protocol.REPLICATE_ACK);
                }
                case Protocol.UNDO -> {
                    boolean undone = store.rollbackLast(Integer.parseInt(f[1]));
                    if (undone) log("inserção de " + f[1] + " desfeita a pedido do par");
                    out.println(Protocol.UNDO_ACK);
                }
                default -> out.println(Protocol.msg(Protocol.ERR, "mensagem desconhecida"));
            }
        } catch (Exception e) {
            log("ERRO: " + e);
        }
    }

    private String handleCheck(int n) throws IOException, InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(
                Config.DATA_SLEEP_MIN_MS, Config.DATA_SLEEP_MAX_MS + 1));

        String result;
        mutex.acquire();
        try {
            int line = store.find(n);
            if (line > 0) {
                result = Protocol.msg(Protocol.EXISTS, line);
            } else {
                line = store.append(n);
                try {
                    replicate(n, line); // só segue depois do ACK do par
                } catch (IOException e) {
                    // Réplica falhou: desfaz a escrita local para os arquivos não divergirem.
                    store.rollbackLast(n);
                    desfazerNoPar(n);
                    log("réplica de " + n + " falhou; inserção desfeita (" + e.getMessage() + ")");
                    throw e;
                }
                registrarPrimo(n);  // ainda dentro da seção crítica: escritas no primos.log não se sobrepõem
                log("inseriu " + n + " na linha " + line + " (replicado)");
                result = Protocol.msg(Protocol.INSERTED, line);
            }
        } finally {
            mutex.release();
        }
        return result;
    }

    /**
     * Acrescenta n ao logs/primos.log se for primo. Só o servidor que fez a inserção
     * original chama isto (a réplica não), e o número só é inserido uma vez no sistema,
     * então o arquivo não tem repetição. A seção crítica distribuída impede que os dois
     * servidores escrevam ao mesmo tempo.
     */
    private void registrarPrimo(int n) throws IOException {
        if (!PrimeUtil.isPrime(n)) return;
        Files.createDirectories(PRIMOS_LOG.toAbsolutePath().getParent());
        Files.writeString(PRIMOS_LOG, n + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Melhor esforço: se o par chegou a aplicar a réplica e só o ACK se perdeu, manda desfazer
     * lá também. Se o par estiver fora do ar, ele não aplicou, e a falha é ignorada.
     */
    private void desfazerNoPar(int n) {
        try {
            Net.requestOnce(peerPort, Protocol.msg(Protocol.UNDO, n), 500, 2000);
        } catch (IOException ignored) { }
    }

    private void replicate(int n, int line) throws IOException {
        String resp = Net.request(peerPort, Protocol.msg(Protocol.REPLICATE, n, line));
        if (!Protocol.REPLICATE_ACK.equals(resp))
            throw new IOException("Réplica não confirmada: " + resp);
    }

    private void log(String msg) {
        System.out.println("[Data " + id + "] " + msg);
    }
}