package dataserver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Arquivo local de um servidor de dados.
 * Cada número ocupa uma linha; a 1ª inserção vai para a linha 1.
 * Mantém um índice em memória (número -> linha) para a busca ser O(1).
 * O arquivo é recriado vazio a cada execução do sistema.
 */
public class FileStore {
    private final Path path;
    private final BufferedWriter writer;
    private final Map<Integer, Integer> index = new HashMap<>();
    private int lines = 0;

    public FileStore(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.toAbsolutePath().getParent());
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** Retorna a linha (1-based) onde n está, ou -1 se não existir. */
    public synchronized int find(int n) {
        Integer line = index.get(n);
        return line == null ? -1 : line;
    }

    /** Acrescenta n numa nova linha e devolve o número dessa linha. */
    public synchronized int append(int n) throws IOException {
        lines++;
        writer.write(Integer.toString(n));
        writer.newLine();
        writer.flush();
        index.put(n, lines);
        return lines;
    }

    /** Aplica uma escrita vinda do outro servidor, conferindo que a linha bate. */
    public synchronized void applyReplica(int n, int expectedLine) throws IOException {
        if (index.containsKey(n)) {
            if (index.get(n) != expectedLine)
                throw new IOException("Divergência: " + n + " já está na linha " + index.get(n)
                        + ", réplica esperava " + expectedLine);
            return; // replicação repetida: idempotente
        }
        int line = append(n);
        if (line != expectedLine)
            throw new IOException("Divergência: " + n + " ficou na linha " + line
                    + ", réplica esperava " + expectedLine);
    }

    public synchronized int size() { return lines; }

    public Path getPath() { return path; }

    public synchronized void close() throws IOException { writer.close(); }
}
