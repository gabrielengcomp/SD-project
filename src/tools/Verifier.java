package tools;

import common.Config;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Verificação ao final da execução (rodar com o sistema já parado).  Uso: java tools.Verifier
 *
 *  1. data1.txt e data2.txt são idênticos (mesmos valores nas mesmas linhas)
 *  2. não há valores repetidos nos arquivos de dados
 *  3. o conjunto de números distintos dos logs dos clientes == conjunto dos arquivos de dados
 */
public class Verifier {
    public static void main(String[] args) throws IOException {
        List<String> d1 = lines(Path.of(Config.DATA_DIR, "data1.txt"));
        List<String> d2 = lines(Path.of(Config.DATA_DIR, "data2.txt"));

        Set<String> clients = new TreeSet<>();
        int totalDraws = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(Config.LOG_DIR), "client*.log")) {
            for (Path p : ds) {
                List<String> l = lines(p);
                totalDraws += l.size();
                clients.addAll(l);
                System.out.println("  " + p.getFileName() + ": " + l.size() + " sorteios");
            }
        }

        boolean ok = true;

        boolean identical = d1.equals(d2);
        System.out.println("[1] data1 == data2 (valores e ordem): " + mark(identical)
                + " (" + d1.size() + " vs " + d2.size() + " linhas)");
        ok &= identical;

        boolean noDup = new HashSet<>(d1).size() == d1.size() && new HashSet<>(d2).size() == d2.size();
        System.out.println("[2] sem valores repetidos nos arquivos: " + mark(noDup));
        ok &= noDup;

        Set<String> data = new TreeSet<>(d1);
        Set<String> missing = new TreeSet<>(clients);
        missing.removeAll(data);
        Set<String> extra = new TreeSet<>(data);
        extra.removeAll(clients);
        boolean same = missing.isEmpty() && extra.isEmpty();
        System.out.println("[3] distintos nos logs (" + clients.size() + ") == distintos nos arquivos ("
                + data.size() + "): " + mark(same) + "  [total sorteado: " + totalDraws + "]");
        if (!missing.isEmpty()) System.out.println("    nos logs mas FALTANDO nos arquivos: " + missing);
        if (!extra.isEmpty()) System.out.println("    nos arquivos mas fora dos logs: " + extra);
        ok &= same;

        System.out.println(ok ? "\nRESULTADO: OK" : "\nRESULTADO: FALHOU");
        System.exit(ok ? 0 : 1);
    }

    private static List<String> lines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String s : Files.readAllLines(p)) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String mark(boolean b) { return b ? "OK" : "FALHOU"; }
}
