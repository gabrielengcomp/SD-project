package loadbalancer;

import common.Config;
import common.Net;
import common.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Balanceador de carga.  Uso: java loadbalancer.LoadBalancer
 *
 * Política: round-robin. As requisições têm custo homogêneo (primalidade de n <= 1000
 * + uma ida ao servidor de dados), então revezar os servidores de aplicação distribui
 * a carga de forma uniforme, sem estado nem consulta de carga (O(1), thread-safe com
 * AtomicInteger).
 *
 * Tolerância a falhas (independe da política):
 *  - uma única tentativa por servidor, com timeout de conexão curto (falha rápida);
 *  - circuit breaker: um app que falha fica fora da rotação por LB_DOWN_MS; passado esse
 *    tempo ele volta a ser tentado. Apps marcados como fora só são usados se todos os outros falharem.
 *
 * Atua como proxy: repassa NUM;n ao servidor escolhido e devolve a resposta ao cliente.
 */
public class LoadBalancer {
    private static final AtomicInteger next = new AtomicInteger(0);
    private static final AtomicInteger[] served = {
            new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
    /** Instante (ms) até o qual cada app é considerado fora do ar; 0 = saudável. */
    private static final AtomicLongArray downUntil = new AtomicLongArray(Config.APP_PORTS.length);

    public static void main(String[] args) throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(Config.LB_PORT)) {
            System.out.println("[LB] ouvindo na porta " + Config.LB_PORT + " (round-robin + circuit breaker)");
            while (true) {
                Socket client = server.accept();
                pool.execute(() -> handle(client));
            }
        }
    }

    /** Ordem de tentativa: round-robin a partir de start, saudáveis primeiro e depois os marcados como fora. */
    private static List<Integer> ordem(int start) {
        int n = Config.APP_PORTS.length;
        long now = System.currentTimeMillis();
        List<Integer> ok = new ArrayList<>();
        List<Integer> down = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int idx = (start + k) % n;
            (downUntil.get(idx) > now ? down : ok).add(idx);
        }
        ok.addAll(down);
        return ok;
    }

    private static void handle(Socket client) {
        try (client) {
            BufferedReader in = Net.reader(client);
            PrintWriter out = Net.writer(client);
            String line = in.readLine();
            if (line == null) return;

            int start = Math.floorMod(next.getAndIncrement(), Config.APP_PORTS.length);
            String resp = null;
            for (int idx : ordem(start)) {
                try {
                    resp = Net.requestOnce(Config.APP_PORTS[idx], line,
                            Config.LB_CONNECT_TIMEOUT_MS, Config.LB_READ_TIMEOUT_MS);
                    downUntil.set(idx, 0); // respondeu: saudável
                    int count = served[idx].incrementAndGet();
                    if (count % 50 == 0)
                        System.out.println("[LB] distribuição: App0=" + served[0] + " App1=" + served[1]
                                + " App2=" + served[2]);
                    break;
                } catch (IOException e) {
                    downUntil.set(idx, System.currentTimeMillis() + Config.LB_DOWN_MS);
                    System.out.println("[LB] App " + idx + " fora da rotação por " + Config.LB_DOWN_MS
                            + " ms: " + e.getMessage());
                }
            }
            out.println(resp != null ? resp : Protocol.msg(Protocol.ERR, "nenhum servidor de aplicação disponível"));
        } catch (IOException e) {
            System.out.println("[LB] ERRO: " + e);
        }
    }
}