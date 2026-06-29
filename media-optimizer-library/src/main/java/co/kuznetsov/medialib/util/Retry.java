package co.kuznetsov.medialib.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a block of work with a fixed delay between attempts.
 *
 * <p>Usage:
 * <pre>{@code
 * // Finite retries — propagates the last exception if all attempts fail
 * String result = Retry.withRetries(10, 120_000, () -> callClaudeApi());
 *
 * // Infinite retries — blocks until success
 * Retry.untilSuccess(1_000, () -> writeToDynamo());
 * }</pre>
 */
public final class Retry {

    private static final Logger LOG = LoggerFactory.getLogger(Retry.class);

    private Retry() {
    }

    /**
     * Functional interface for work that may throw any exception.
     *
     * @param <T> return type
     */
    @FunctionalInterface
    public interface Work<T> {
        T execute() throws Exception;
    }

    /**
     * Functional interface for work that returns no value and may throw any exception.
     */
    @FunctionalInterface
    public interface VoidWork {
        void execute() throws Exception;
    }

    /**
     * Executes {@code work} up to {@code maxAttempts} times, sleeping {@code delayMillis}
     * between attempts. If all attempts fail, the last exception is rethrown.
     *
     * @param <T>         return type
     * @param maxAttempts total number of attempts (must be >= 1)
     * @param delayMillis milliseconds to sleep between attempts
     * @param work        the operation to execute
     * @return the result of the first successful attempt
     * @throws Exception the exception from the last failed attempt
     */
    public static <T> T withRetries(int maxAttempts, long delayMillis, Work<T> work) throws Exception {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return work.execute();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts) {
                    LOG.warn("Attempt {}/{} failed: {} — retrying in {}ms",
                            attempt, maxAttempts, e.getMessage(), delayMillis);
                    sleepUninterruptibly(delayMillis);
                } else {
                    LOG.error("All {} attempt(s) failed. Last error: {}", maxAttempts, e.getMessage());
                }
            }
        }
        throw last;
    }

    /**
     * Executes {@code work} up to {@code maxAttempts} times, sleeping {@code delayMillis}
     * between attempts. If all attempts fail, the last exception is rethrown.
     *
     * @param maxAttempts total number of attempts (must be >= 1)
     * @param delayMillis milliseconds to sleep between attempts
     * @param work        the operation to execute
     * @throws Exception the exception from the last failed attempt
     */
    public static void withRetries(int maxAttempts, long delayMillis, VoidWork work) throws Exception {
        withRetries(maxAttempts, delayMillis, () -> {
            work.execute();
            return null;
        });
    }

    /**
     * Executes {@code work} indefinitely until it succeeds, sleeping {@code delayMillis}
     * between attempts. Only an {@link InterruptedException} stops the loop.
     *
     * @param delayMillis milliseconds to sleep between attempts
     * @param work        the operation to execute
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    public static void untilSuccess(long delayMillis, VoidWork work) throws InterruptedException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                work.execute();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                LOG.warn("Attempt {} failed: {} — retrying in {}ms", attempt, e.getMessage(), delayMillis);
                Thread.sleep(delayMillis);
            }
        }
    }

    /**
     * Executes {@code work} indefinitely until it succeeds, sleeping {@code delayMillis}
     * between attempts. Only an {@link InterruptedException} stops the loop.
     *
     * @param <T>         return type
     * @param delayMillis milliseconds to sleep between attempts
     * @param work        the operation to execute
     * @return the result of the first successful attempt
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    public static <T> T untilSuccess(long delayMillis, Work<T> work) throws InterruptedException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return work.execute();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                LOG.warn("Attempt {} failed: {} — retrying in {}ms", attempt, e.getMessage(), delayMillis);
                Thread.sleep(delayMillis);
            }
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
