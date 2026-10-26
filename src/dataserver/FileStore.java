package dataserver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arquivo local de um servidor de dados.
 * Cada número ocupa uma linha; a 1ª inserção vai para a linha 1.
 * Mantém um índice em memória (número -> linha) para a busca ser O(1).
 * O arquivo é recriado vazio a cada execução do sistema.
 */
public class FileStore {
    private final Path path;
    private BufferedWriter writer;
    private final Map<Integer, Integer> index = new HashMap<>();
    private final List<Integer> values = new ArrayList<>(); // valores na ordem das linhas
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
        values.add(n);
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

    /**
     * Desfaz a última inserção, se ela for n (usado quando a réplica falha). Reescreve o
     * arquivo sem a última linha. Retorna true se desfez; false se a última linha não é n
     * (nada a fazer: a inserção não chegou a ocorrer aqui).
     */
    public synchronized boolean rollbackLast(int n) throws IOException {
        if (values.isEmpty() || values.get(values.size() - 1) != n) return false;
        values.remove(values.size() - 1);
        index.remove(n);
        lines--;
        writer.close();
        List<String> out = new ArrayList<>();
        for (int v : values) out.add(Integer.toString(v));
        Files.write(path, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        return true;
    }

    public synchronized int size() { return lines; }

    public Path getPath() { return path; }

    public synchronized void close() throws IOException { writer.close(); }
}