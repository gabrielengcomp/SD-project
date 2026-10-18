package common;

public final class PrimeUtil {
    private PrimeUtil() {}

    /** Divisao por tentativa ate a raiz quadrada: suficiente para n <= 1000. */
    public static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n % 2 == 0) return n == 2;
        for (int d = 3; (long) d * d <= n; d += 2) {
            if (n % d == 0) return false;
        }
        return true;
    }
}
