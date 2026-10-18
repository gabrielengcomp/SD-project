package appserver;

import common.Config;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Servidor de aplicação.  Uso: java appserver.AppServer <id: 0, 1 ou 2>
 * Para cada requisição recebida do balanceador, abre uma nova thread Worker.
 */
public class AppServer {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Uso: java appserver.AppServer <0|1|2>");
            System.exit(1);
        }
        int id = Integer.parseInt(args[0]);
        int port = Config.APP_PORTS[id];

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[App " + id + "] ouvindo na porta " + port);
            while (true) {
                Socket s = server.accept();
                new Thread(new Worker(id, s), "worker-" + id).start();
            }
        }
    }
}
