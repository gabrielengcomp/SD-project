package appserver;

import common.Config;
import common.Net;
import common.PrimeUtil;
import common.Protocol;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thread worker: atende exatamente uma requisição.
 *  1. lê NUM;n do balanceador
 *  2. verifica a primalidade
 *  3. sorteia um servidor de dados, envia CHECK;n e espera a resposta
 *  4. imprime a mensagem do enunciado
 *  5. devolve OK;<mensagem> ao balanceador e termina
 */
public class Worker implements Runnable {
    private final int appId;
    private final Socket socket;

    public Worker(int appId, Socket socket) {
        this.appId = appId;
        this.socket = socket;
    }

    @Override
    public void run() {
        try (socket) {
            BufferedReader in = Net.reader(socket);
            PrintWriter out = Net.writer(socket);

            String line = in.readLine();
            if (line == null) return;
            String[] f = Protocol.parse(line, 2);
            if (!Protocol.NUM.equals(f[0])) {
                out.println(Protocol.msg(Protocol.ERR, "mensagem desconhecida"));
                return;
            }
            int n = Integer.parseInt(f[1]);

            // 1) primalidade primeiro
            boolean prime = PrimeUtil.isPrime(n);

            // 2) servidor de dados sorteado (sem política de balanceamento, como pede o enunciado)
            int dataIdx = ThreadLocalRandom.current().nextInt(Config.DATA_PORTS.length);
            String resp = Net.request(Config.DATA_PORTS[dataIdx], Protocol.msg(Protocol.CHECK, n));

            // 3) monta e imprime a mensagem
            String[] r = Protocol.parse(resp, 2);
            String primeStr = prime ? "é primo" : "não é primo";
            String message;
            if (Protocol.INSERTED.equals(r[0])) {
                message = "O número " + n + " " + primeStr
                        + ", não está presente no arquivo, e foi inserido na linha " + r[1];
            } else if (Protocol.EXISTS.equals(r[0])) {
                message = "O número " + n + " " + primeStr
                        + ", e já se encontra no arquivo na linha " + r[1];
            } else {
                throw new IllegalStateException("Resposta inesperada do servidor de dados: " + resp);
            }
            System.out.println("[App " + appId + " | Data " + dataIdx + "] " + message);

            // 4) responde ao balanceador/cliente só depois de imprimir
            out.println(Protocol.msg(Protocol.OK, message));
        } catch (Exception e) {
            System.out.println("[App " + appId + "] ERRO: " + e);
            try {
                new PrintWriter(socket.getOutputStream(), true)
                        .println(Protocol.msg(Protocol.ERR, e.getMessage()));
            } catch (Exception ignored) { }
        }
    }
}
