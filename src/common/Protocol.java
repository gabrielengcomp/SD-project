package common;

/**
 * Protocolo textual: uma mensagem por linha, campos separados por ;
 *
 *  Cliente -> LB -> App :  NUM;<n>
 *  App/LB -> Cliente    :  OK;<mensagem humana>   |  ERR;<motivo>
 *  App -> Data          :  CHECK;<n>
 *  Data -> App          :  EXISTS;<linha>  |  INSERTED;<linha>
 *  Data <-> Data        :  MUTEX_REQ;<ts>;<id>  ->  MUTEX_OK
 *                          REPLICATE;<n>;<linha> -> REPLICATE_ACK
 */
public final class Protocol {
    private Protocol() {}

    public static final String SEP = ";";

    public static final String NUM = "NUM";
    public static final String OK = "OK";
    public static final String ERR = "ERR";

    public static final String CHECK = "CHECK";
    public static final String EXISTS = "EXISTS";
    public static final String INSERTED = "INSERTED";

    public static final String MUTEX_REQ = "MUTEX_REQ";
    public static final String MUTEX_OK = "MUTEX_OK";
    public static final String REPLICATE = "REPLICATE";
    public static final String REPLICATE_ACK = "REPLICATE_ACK";

    public static String msg(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(SEP);
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Divide em no maximo limit campos (o ultimo pode conter ponto e virgula). */
    public static String[] parse(String line, int limit) {
        return line.split(SEP, limit);
    }
}
