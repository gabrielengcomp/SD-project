package client;

import common.Config;
import common.Net;
import common.Protocol;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cliente.  Uso: java client.Client <id: 1 ou 2>
 *
 * Loop: sorteia n em [1, 1000], registra n no log (uma linha por sorteio, com flush),
 * envia NUM;n ao balanceador, espera a resposta e dorme 50-200 ms.
 *
 * O cliente espera a resposta antes do próximo sorteio, então nenhuma requisição fica
 * "no ar" sem confirmação. Para encerrar sem perder nada, crie o arquivo STOP: o cliente
 * termina a requisição em andamento e sai.
 */
public class Client {
    private static final int MAX_ATTEMPTS = 3;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.err.println("Uso: java client.Client <id>");
            System.exit(1);
        }
        int id = Integer.parseInt(args[0]);
        Path logPath = Path.of(Config.LOG_DIR, "client" + id + ".log");
        Files.createDirectories(logPath.toAbsolutePath().getParent());

        try (BufferedWriter log = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            System.out.println("[Client " + id + "] iniciado; log em " + logPath);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            while (!Files.exists(Path.of(Config.STOP_FILE))) {
                int n = rnd.nextInt(1, Config.MAX_NUMBER + 1);

                log.write(Integer.toString(n));
                log.newLine();
                log.flush();

                send(id, n);

                Thread.sleep(rnd.nextInt(Config.CLIENT_SLEEP_MIN_MS, Config.CLIENT_SLEEP_MAX_MS + 1));
            }
            System.out.println("[Client " + id + "] arquivo STOP detectado; encerrando.");
        }
    }

    /** Envia n ao balanceador, repetindo em caso de falha (a operação é idempotente). */
    private static void send(int id, int n) throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String resp = Net.request(Config.LB_PORT, Protocol.msg(Protocol.NUM, n));
                String[] f = Protocol.parse(resp, 2);
                if (Protocol.OK.equals(f[0])) {
                    System.out.println("[Client " + id + "] " + f[1]);
                    return;
                }
                System.out.println("[Client " + id + "] erro para " + n + ": " + resp);
            } catch (IOException e) {
                System.out.println("[Client " + id + "] falha enviando " + n + " (tentativa "
                        + attempt + "): " + e.getMessage());
            }
            Thread.sleep(200);
        }
        System.out.println("[Client " + id + "] DESISTIU de " + n + " após " + MAX_ATTEMPTS + " tentativas");
    }
}
