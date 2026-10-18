package loadbalancer;

import common.Config;
import common.Net;
import common.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Balanceador de carga.  Uso: java loadbalancer.LoadBalancer
 *
 * Política: round-robin. As requisições têm custo homogêneo (primalidade de n <= 1000
 * + uma ida ao servidor de dados), então revezar os servidores de aplicação distribui
 * a carga de forma uniforme, sem estado nem consulta de carga (O(1), thread-safe com
 * AtomicInteger).
 *
 * Atua como proxy: repassa NUM;n ao servidor escolhido e devolve a resposta ao cliente.
 * Se o servidor escolhido estiver fora do ar, tenta o próximo.
 */
public class LoadBalancer {
    private static final AtomicInteger next = new AtomicInteger(0);
    private static final AtomicInteger[] served = {
            new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};

    public static void main(String[] args) throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(Config.LB_PORT)) {
            System.out.println("[LB] ouvindo na porta " + Config.LB_PORT + " (round-robin)");
            while (true) {
                Socket client = server.accept();
                pool.execute(() -> handle(client));
            }
        }
    }

    private static void handle(Socket client) {
        try (client) {
            BufferedReader in = Net.reader(client);
            PrintWriter out = Net.writer(client);
            String line = in.readLine();
            if (line == null) return;

            int n = Config.APP_PORTS.length;
            int start = Math.floorMod(next.getAndIncrement(), n);
            String resp = null;
            for (int attempt = 0; attempt < n && resp == null; attempt++) {
                int idx = (start + attempt) % n;
                try {
                    resp = Net.request(Config.APP_PORTS[idx], line);
                    int count = served[idx].incrementAndGet();
                    if (count % 50 == 0)
                        System.out.println("[LB] distribuição: App0=" + served[0] + " App1=" + served[1]
                                + " App2=" + served[2]);
                } catch (IOException e) {
                    System.out.println("[LB] App " + idx + " indisponível, tentando o próximo: " + e.getMessage());
                }
            }
            out.println(resp != null ? resp : Protocol.msg(Protocol.ERR, "nenhum servidor de aplicação disponível"));
        } catch (IOException e) {
            System.out.println("[LB] ERRO: " + e);
        }
    }
}
