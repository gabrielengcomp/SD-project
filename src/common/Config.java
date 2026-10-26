package common;

/** Constantes de configuração compartilhadas por todos os componentes. */
public final class Config {
    private Config() {}

    public static final String HOST = "localhost";

    // Portas (todos em localhost, simulando máquinas distintas)
    public static final int LB_PORT = 5000;
    public static final int[] APP_PORTS = {6001, 6002, 6003};
    public static final int[] DATA_PORTS = {7001, 7002};

    // Cliente
    public static final int MAX_NUMBER = 1000;
    public static final int CLIENT_SLEEP_MIN_MS = 50;
    public static final int CLIENT_SLEEP_MAX_MS = 200;

    // Servidor de dados
    public static final int DATA_SLEEP_MIN_MS = 100;
    public static final int DATA_SLEEP_MAX_MS = 300;

    // Arquivos
    public static final String DATA_DIR = "data";
    public static final String LOG_DIR = "logs";
    public static final String STOP_FILE = "STOP"; // se existir, os clientes encerram com limpeza

    // Balanceador: falha rápida e circuit breaker
    public static final int LB_CONNECT_TIMEOUT_MS = 1000; // conectar a um app
    public static final int LB_READ_TIMEOUT_MS = 10000;   // esperar a resposta do app
    public static final long LB_DOWN_MS = 3000;           // tempo que um app falho fica fora da rotação

    // Cliente: reenvio com espera crescente
    public static final long CLIENT_RETRY_MIN_MS = 200;
    public static final long CLIENT_RETRY_MAX_MS = 2000;

    // Rede
    public static final int CONNECT_RETRIES = 20;
    public static final long CONNECT_RETRY_DELAY_MS = 250;
}