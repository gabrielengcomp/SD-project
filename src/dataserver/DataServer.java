package dataserver;

import common.Config;
import common.Net;
import common.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
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
                case Protocol.CHECK -> out.println(handleCheck(Integer.parseInt(f[1])));
                case Protocol.MUTEX_REQ -> {
                    mutex.onPeerRequest(Long.parseLong(f[1]), Integer.parseInt(f[2]));
                    out.println(Protocol.MUTEX_OK);
                }
                case Protocol.REPLICATE -> {
                    store.applyReplica(Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                    log("réplica aplicada: " + f[1] + " na linha " + f[2]);
                    out.println(Protocol.REPLICATE_ACK);
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
                replicate(n, line); // só segue depois do ACK do par
                log("inseriu " + n + " na linha " + line + " (replicado)");
                result = Protocol.msg(Protocol.INSERTED, line);
            }
        } finally {
            mutex.release();
        }
        return result;
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
