package uk.ac.ic.doc.slurp.multilock;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;
import static uk.ac.ic.doc.slurp.multilock.LockMode.*;

/**
 * Tests which check the compatibility of lock modes.
 *
 * For MultiLock the compatibility matrix is as described in the
 * 1975 paper by Gray et al. "Granularity of Locks in a Shared Data Base".
 *
 * |----------------------------------|
 * |           COMPATIBILITY          |
 * |----------------------------------|
 * |     | IS  | IX  | S   | SIX | X  |
 * |----------------------------------|
 * | IS  | YES | YES | YES | YES | NO |
 * | IX  | YES | YES | NO  | NO  | NO |
 * | S   | YES | NO  | YES | NO  | NO |
 * | SIX | YES | NO  | NO  | NO  | NO |
 * | X   | NO  | NO  | NO  | NO  | NO |
 * |----------------------------------|
 *
 * Compatibility is asserted by having a thread t1 acquire
 * a lock mode, and thread t2 acquire another lock mode. Both
 * threads must acquire their lock modes within a defined
 * timeout for the lock modes to be considered compatible.
 *
 * @author Adam Retter <adam@evolvedbinary.com>
 */
public class CompatibilityTest {

    private static final long LOCK_ACQUISITION_TIMEOUT = 20;        // TODO(AR) this might need to be longer on slower machines...

    @Test
    public void compatible_IS_IS() throws InterruptedException, ExecutionException {
        assertCompatible(IS, IS);
    }

    @Test
    public void compatible_IS_IX() throws InterruptedException, ExecutionException {
        assertCompatible(IS, IX);
    }

    @Test
    public void compatible_IS_S() throws InterruptedException, ExecutionException {
        assertCompatible(IS, S);
    }

    @Test
    public void compatible_IS_SIX() throws InterruptedException, ExecutionException {
        assertCompatible(IS, SIX);
    }

    @Test
    public void compatible_IS_X() throws InterruptedException, ExecutionException {
        assertNotCompatible(IS, X);
    }

    @Test
    public void compatible_IX_IS() throws InterruptedException, ExecutionException {
        assertCompatible(IX, IS);
    }

    @Test
    public void compatible_IX_IX() throws InterruptedException, ExecutionException {
        assertCompatible(IX, IX);
    }

    @Test
    public void compatible_IX_S() throws InterruptedException, ExecutionException {
        assertNotCompatible(IX, S);
    }

    @Test
    public void compatible_IX_SIX() throws InterruptedException, ExecutionException {
        assertNotCompatible(IX, SIX);
    }

    @Test
    public void compatible_IX_X() throws InterruptedException, ExecutionException {
        assertNotCompatible(IX, X);
    }

    @Test
    public void compatible_S_IS() throws InterruptedException, ExecutionException {
        assertCompatible(S, IS);
    }

    @Test
    public void compatible_S_IX() throws InterruptedException, ExecutionException {
        assertNotCompatible(S, IX);
    }

    @Test
    public void compatible_S_S() throws InterruptedException, ExecutionException {
        assertCompatible(S, S);
    }

    @Test
    public void compatible_S_SIX() throws InterruptedException, ExecutionException {
        assertNotCompatible(S, SIX);
    }

    @Test
    public void compatible_S_X() throws InterruptedException, ExecutionException {
        assertNotCompatible(S, X);
    }

    @Test
    public void compatible_SIX_IS() throws InterruptedException, ExecutionException {
        assertCompatible(SIX, IS);
    }

    @Test
    public void compatible_SIX_IX() throws InterruptedException, ExecutionException {
        assertNotCompatible(SIX, IX);
    }

    @Test
    public void compatible_SIX_S() throws InterruptedException, ExecutionException {
        assertNotCompatible(SIX, S);
    }

    @Test
    public void compatible_SIX_SIX() throws InterruptedException, ExecutionException {
        assertNotCompatible(SIX, SIX);
    }

    @Test
    public void compatible_SIX_X() throws InterruptedException, ExecutionException {
        assertNotCompatible(SIX, X);
    }

    @Test
    public void compatible_X_IS() throws InterruptedException, ExecutionException {
        assertNotCompatible(X, IS);
    }

    @Test
    public void compatible_X_IX() throws InterruptedException, ExecutionException {
        assertNotCompatible(X, IX);
    }

    @Test
    public void compatible_X_S() throws InterruptedException, ExecutionException {
        assertNotCompatible(X, S);
    }

    @Test
    public void compatible_X_SIX() throws InterruptedException, ExecutionException {
        assertNotCompatible(X, SIX);
    }

    @Test
    public void compatible_X_X() throws InterruptedException, ExecutionException {
        assertNotCompatible(X, X);
    }

    private static void assertCompatible(final LockMode mode1, final LockMode mode2) throws InterruptedException, ExecutionException {
        final List<Future<Boolean>> futures = checkCompatibility(mode1, mode2);
        for (final Future<Boolean> future : futures) {
            assertTrue(future.isDone());
            assertFalse(future.isCancelled());

            assertTrue(future.get());
        }
    }

    private static void assertNotCompatible(final LockMode mode1, final LockMode mode2) throws InterruptedException, ExecutionException {
        final List<Future<Boolean>> futures = checkCompatibility(mode1, mode2);
        for (final Future<Boolean> future : futures) {
            assertTrue(future.isDone());

            assertTrue(future.isCancelled());
        }
    }

    private static List<Future<Boolean>> checkCompatibility(final LockMode mode1, final LockMode mode2) throws InterruptedException {
        final MultiLock multiLock = new MultiLock(null);

        final CountDownLatch latch = new CountDownLatch(2);

        final Callable<Boolean> thread1 = new LockAcquirer(multiLock, mode1, latch);
        final Callable<Boolean> thread2 = new LockAcquirer(multiLock, mode2, latch);

        final ExecutorService executorService = Executors.newFixedThreadPool(2);
        return executorService.invokeAll(Arrays.asList(thread1, thread2), LOCK_ACQUISITION_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    private static class LockAcquirer implements Callable<Boolean> {
        private final MultiLock multiLock;
        private final LockMode lockMode;
        private final CountDownLatch latch;

        public LockAcquirer(final MultiLock multiLock, final LockMode lockMode, final CountDownLatch latch) {
            this.multiLock = multiLock;
            this.lockMode = lockMode;
            this.latch = latch;
        }

        @Override
        public Boolean call() throws Exception {
            final boolean lockResult = lockMode.lock(multiLock);

            latch.countDown();
            latch.await();

            return lockResult;
        }
    }
}