package common;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Utilitarios de socket TCP. */
public final class Net {
    private Net() {}

    /** Conecta com tentativas, para tolerar componentes que ainda estao subindo. */
    public static Socket connect(int port) throws IOException {
        IOException last = null;
        for (int i = 0; i < Config.CONNECT_RETRIES; i++) {
            try {
                return new Socket(Config.HOST, port);
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(Config.CONNECT_RETRY_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }

    public static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream()));
    }

    public static PrintWriter writer(Socket s) throws IOException {
        return new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true); // autoflush
    }

    /** Envia uma linha e le uma linha de resposta (requisicao/resposta em conexao curta). */
    public static String request(int port, String line) throws IOException {
        try (Socket s = connect(port)) {
            PrintWriter out = writer(s);
            BufferedReader in = reader(s);
            out.println(line);
            String resp = in.readLine();
            if (resp == null) throw new IOException("Conexao encerrada sem resposta (porta " + port + ")");
            return resp;
        }
    }

    /**
     * Uma única tentativa, com timeouts: falha rápido em vez de insistir (usado pelo balanceador
     * para detectar servidor fora do ar, e pelo desfazer da réplica).
     */
    public static String requestOnce(int port, String line, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(Config.HOST, port), connectTimeoutMs);
            s.setSoTimeout(readTimeoutMs);
            PrintWriter out = writer(s);
            BufferedReader in = reader(s);
            out.println(line);
            String resp = in.readLine(); // SocketTimeoutException (IOException) se estourar o tempo
            if (resp == null) throw new IOException("Conexao encerrada sem resposta (porta " + port + ")");
            return resp;
        }
    }
}