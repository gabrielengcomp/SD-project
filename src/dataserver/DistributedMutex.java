package dataserver;

import common.Net;
import common.Protocol;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * Exclusão mútua distribuída de Ricart-Agrawala, para 2 nós.
 *
 * - Relógio lógico de Lamport; a prioridade é o par (timestamp, id): menor vence.
 * - Para entrar na seção crítica, o nó envia MUTEX_REQ ao par e BLOQUEIA lendo a
 *   resposta na mesma conexão. O par só responde MUTEX_OK quando concede a vez:
 *   a resposta "adiada" do algoritmo é simplesmente a thread de atendimento do
 *   par esperando (wait/notifyAll) até a liberação.
 * - Um semáforo local serializa as threads do próprio servidor: no máximo uma
 *   requisição local participa do protocolo por vez.
 */
public class DistributedMutex {
    private final int myId;
    private final int peerPort;

    private final Semaphore local = new Semaphore(1, true);
    private final Object lock = new Object();

    private long clock = 0;
    private long myTs = 0;
    private boolean requesting = false;
    private boolean inCS = false;

    public DistributedMutex(int myId, int peerPort) {
        this.myId = myId;
        this.peerPort = peerPort;
    }

    /** Entra na seção crítica distribuída (bloqueia até conseguir). */
    public void acquire() throws IOException, InterruptedException {
        local.acquire();
        try {
            long ts;
            synchronized (lock) {
                clock++;
                myTs = clock;
                ts = myTs;
                requesting = true;
            }
            // Bloqueia até o par conceder (MUTEX_OK). Se o par também quer e tem prioridade,
            // ele só responde depois de sair da seção crítica dele.
            String resp = Net.request(peerPort, Protocol.msg(Protocol.MUTEX_REQ, ts, myId));
            if (!Protocol.MUTEX_OK.equals(resp))
                throw new IOException("Resposta inesperada ao MUTEX_REQ: " + resp);
            synchronized (lock) {
                requesting = false;
                inCS = true;
            }
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                requesting = false;
                inCS = false;
                lock.notifyAll();
            }
            local.release();
            throw e;
        }
    }

    /** Sai da seção crítica e libera o par (que pode estar esperando a resposta). */
    public void release() {
        synchronized (lock) {
            inCS = false;
            lock.notifyAll();
        }
        local.release();
    }

    /**
     * Trata um MUTEX_REQ vindo do par. Retorna quando a vez pode ser concedida
     * (o chamador então escreve MUTEX_OK no socket).
     */
    public void onPeerRequest(long peerTs, int peerId) throws InterruptedException {
        synchronized (lock) {
            clock = Math.max(clock, peerTs) + 1;
            while (inCS || (requesting && iHavePriority(peerTs, peerId))) {
                lock.wait();
            }
        }
    }

    private boolean iHavePriority(long peerTs, int peerId) {
        return myTs < peerTs || (myTs == peerTs && myId < peerId);
    }
}
