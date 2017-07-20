/*
 * Copyright (c) 2010-2016 Khilan Gudka
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

package uk.ac.ic.doc.slurp.multilock;


import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;

public class MultiLock {

    // individual field masks
    static final long X_FIELD      = 0xFFFF000000000000L;
    static final long S_FIELD      = 0x0000FFFF00000000L;
    static final long IX_FIELD     = 0x00000000FFFF0000L;
    static final long IS_FIELD     = 0x000000000000FFFFL;
    static final long NON_X_FIELDS = ~X_FIELD;
    
    // bit patterns for inc/dec individual fields
    static final long X_UNIT  = 0x0001000000000000L;
    static final long S_UNIT  = 0x0000000100000000L;
    static final long IX_UNIT = 0x0000000000010000L;
    static final long IS_UNIT = 0x0000000000000001L;

    final MultiLock owner;
    final Sync sync;
    
    final ReadLock readLock;
    final WriteLock writeLock;
    
    public MultiLock(MultiLock o) {
        owner = o;
        sync = new Sync();
        readLock = new ReadLock();
        writeLock = new WriteLock();
    }
    
    class ReadLock implements Lock {
        
        public void lock() {
            lockRead();
        }

        public void unlock() {
            unlockRead();
        }        
        
        public void lockInterruptibly() throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public boolean tryLock() {
            throw new UnsupportedOperationException();
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            throw new UnsupportedOperationException();
        }

    }
    
    class WriteLock implements Lock {

        public void lock() {
            lockWrite();
        }

        public void unlock() {
            unlockWrite();
        }

        public void lockInterruptibly() throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public boolean tryLock() {
            throw new UnsupportedOperationException();
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            throw new UnsupportedOperationException();
        }

    }
    
    public Lock readLock() { return readLock; }
    
    public Lock writeLock() { return writeLock; }
    
    static class Sync extends AbstractQueuedLongSynchronizer {

        static long xCount(long c) { return (c & X_FIELD) >>> 48; }
        static long sCount(long c) { return (c & S_FIELD) >> 32; }
        static long ixCount(long c) { return (c & IX_FIELD) >> 16; }
        static long isCount(long c) { return c & IS_FIELD; }
        
        // store's per-thread state
        static class HoldCounter { 
            final long tid = Thread.currentThread().getId();
            long state = 0;
        }
        
        static class ThreadLocalHoldCounter extends ThreadLocal<HoldCounter> {
            @Override
            protected HoldCounter initialValue() {
                return new HoldCounter();
            }
        }
        
        final ThreadLocalHoldCounter holdCounts;
        
        HoldCounter cachedHoldCounter;
        
        Sync() {
            holdCounts = new ThreadLocalHoldCounter();
            setState(getState()); // ensures visibility of holdCounts
        }
        
        @Override
        protected boolean tryAcquire(long arg) {
            Thread current = Thread.currentThread();
            long c = getState();
            if (c != 0) {
                long x = c & X_FIELD;
                // (Note: if c != 0 and x == 0 then non-exclusive count != 0)
                if (x == 0) {
                    // Check non-exclusive counts are only for current.
                    // i.e. are we upgrading?
                    HoldCounter rh = cachedHoldCounter;
                    if (rh == null || rh.tid != current.getId())
                        rh = holdCounts.get();
                    long group = c - rh.state;
                    if ((group & NON_X_FIELDS) != 0) {
                        // current thread is not only non-exclusive user
                        return false;
                    }
                }
                else if (current != getExclusiveOwnerThread()) {
                    return false;
                }
            }
            if (!compareAndSetState(c, c + X_UNIT))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }
        
        @Override
        protected boolean tryRelease(long arg) {
            long nextc = getState() - arg;
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                throw new IllegalMonitorStateException();
            }
            if ((nextc & X_FIELD) == 0) {
                setExclusiveOwnerThread(null);
                setState(nextc);
                return true;
            }
            else {
                setState(nextc);
                return false;
            }
        }

        @Override
        protected long tryAcquireShared(long arg) {
            Thread current = Thread.currentThread();
            for (;;) {
                long c = getState();
                // someone else already is X
                if ((c & X_FIELD) != 0 &&
                    getExclusiveOwnerThread() != current)
                    return -1;
                // either no X or current is X
                if (getExclusiveOwnerThread() == current) {
                    if (updateState(c, arg, current)) {
                        return 0;
                    }
                }
                else if (arg == IS_UNIT) { // IS is compatible with S, IX, IS
                    if (updateState(c, arg, current)) {
                        return 1;
                    }
                }
                else if (arg == IX_UNIT) {
                    // two cases: S == 0 (ok) and S != 0 (thread check)
                    long s = c & S_FIELD;
                    if (s == 0) {
                        // ok
                        if (updateState(c, arg, current)) {
                            return 1;
                        }
                    }
                    else {
                        // Check if current thread is the only S
                        // TODO: optimise so that re-entrant IX locking is fast
                        HoldCounter rh = null; //cachedHoldCounter;
                        if (rh == null || rh.tid != current.getId())
                            rh = holdCounts.get();
                        long group = c - rh.state;
                        if ((group & S_FIELD) == 0) {
                            // current is only S
                            if (compareAndSetState(c, c + arg)) {
                                rh.state += arg;
                                cachedHoldCounter = rh;
                                return 1; // still return 1 because IS is always compatible
                            }                            
                        }
                    }
                }
                else if (arg == S_UNIT) {
                    // two cases: IX == 0 (ok) and IX != 0 (thread check)
                    long ix = c & IX_FIELD;
                    if (ix == 0) {
                        // ok
                        if (updateState(c, arg, current)) {
                            return 1;
                        }
                    }
                    else {
                        // check if current thread is the only IX
                        // TODO: optimise so that re-entrant S locking is fast
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != current.getId())
                            rh = holdCounts.get();
                        long group = c - rh.state;
                        if ((group & IX_FIELD) == 0) {
                            // current is only IX
                            if (compareAndSetState(c, c + arg)) {
                                rh.state += arg;
                                cachedHoldCounter = rh;
                                return 1; // still return 1 because IS is always compatible
                            }                            
                        }
                    }
                }
            }
        }
        
        private boolean updateState(long c, long arg, Thread current) {
            if (compareAndSetState(c, c + arg)) {
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != current.getId())
                    cachedHoldCounter = rh = holdCounts.get();
                rh.state += arg;
                return true;
            }
            return false;
        }
        
        @Override
        protected boolean tryReleaseShared(long arg) {
            HoldCounter rh = cachedHoldCounter;
            Thread current = Thread.currentThread();
            if (rh == null || rh.tid != current.getId())
                rh = holdCounts.get();
            rh.state -= arg;
            for (;;) {
                long c = getState();
                long nextc = c - arg;
                if (compareAndSetState(c, nextc)) {
                    if ((nextc & X_FIELD) == 0) {
                        if (arg == S_UNIT) {
                            return (nextc & S_FIELD) == 0;
                        }
                        if (arg == IS_UNIT) {
                            return (nextc & IS_FIELD) == 0;
                        }
                        if (arg == IX_UNIT) {
                            return (nextc & IX_FIELD) == 0;
                        }
                    }
                    else {
                        return false;
                    }
                }
            }
        }

        final int getIntentionReadLockCount() {
            return (int)isCount(getState());
        }

        final int getReadLockCount() {
            return (int)sCount(getState());
        }

        final int getIntentionWriteLockCount() {
            return (int)ixCount(getState());
        }

        final int getWriteLockCount() {
            return (int)xCount(getState());
        }

        final int getIntentionReadHoldCount() {
            if (getIntentionReadLockCount() == 0)
                return 0;

            final Thread current = Thread.currentThread();
            final HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return (int)isCount(rh.state);

            int count = (int)isCount(holdCounts.get().state);
            //if (count == 0) readHolds.remove();
            return count;
        }

        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            final Thread current = Thread.currentThread();
            final HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return (int)sCount(rh.state);

            int count = (int)sCount(holdCounts.get().state);
            //if (count == 0) readHolds.remove();
            return count;
        }

        final int getIntentionWriteHoldCount() {
            if (getIntentionWriteLockCount() == 0)
                return 0;

            final Thread current = Thread.currentThread();
            final HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return (int)ixCount(rh.state);

            int count = (int)ixCount(holdCounts.get().state);
            //if (count == 0) readHolds.remove();
            return count;
        }


        final int getWriteHoldCount() {
            if (getWriteLockCount() == 0)
                return 0;

            final Thread current = Thread.currentThread();
            final HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return (int)xCount(rh.state);

            int count = (int)xCount(holdCounts.get().state);
            //if (count == 0) readHolds.remove();
            return count;
        }
    }
    
    public boolean lockRead() {
        if (owner != null) {
            owner.lockIntentionRead();
        }
        sync.acquireShared(S_UNIT);
        return true;
    }
    
    public boolean lockWrite() {
        if (owner != null) {
            owner.lockIntentionWrite();
        }
        sync.acquire(X_UNIT);
        return true;
    }
    
    public boolean lockIntentionRead() {
        if (owner != null) {
            owner.lockIntentionRead();
        }
        sync.acquireShared(IS_UNIT);
        return true;
    }
    
    public boolean lockIntentionWrite() {
        if (owner != null) {
            owner.lockIntentionWrite();
        }
        sync.acquireShared(IX_UNIT);
        return true;
    }
 
    public void unlockRead() {
        sync.releaseShared(S_UNIT);
        if (owner != null) {
            owner.unlockIntentionRead();
        }
    }
    
    public void unlockWrite() {
        sync.release(X_UNIT);
        if (owner != null) {
            owner.unlockIntentionWrite();
        }        
    }
    
    public void unlockIntentionRead() {
        sync.releaseShared(IS_UNIT);
        if (owner != null) {
            owner.unlockIntentionRead();
        }        
    }
 
    public void unlockIntentionWrite() {
        sync.releaseShared(IX_UNIT);
        if (owner != null) {
            owner.unlockIntentionWrite();
        }        
    }

    /**
     * Queries the number of intention read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of intention read locks held
     */
    public int getIntentionReadLockCount() {
        return sync.getIntentionReadLockCount();
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * Queries the number of intention write locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of intention write locks held
     */
    public int getIntentionWriteLockCount() {
        return sync.getIntentionWriteLockCount();
    }

    /**
     * Queries the number of write locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of write locks held
     */
    public int getWriteLockCount() {
        return sync.getWriteLockCount();
    }

    /**
     * Queries the number of reentrant intention read holds on this lock by the
     * current thread.  A reader thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     *
     * @return the number of holds on the intention read lock by the current thread,
     *         or zero if the intention read lock is not held by the current thread
     */
    public int getIntentionReadHoldCount() {
        return sync.getIntentionReadHoldCount();
    }

    /**
     * Queries the number of reentrant read holds on this lock by the
     * current thread.  A reader thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     *
     * @return the number of holds on the read lock by the current thread,
     *         or zero if the read lock is not held by the current thread
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    /**
     * Queries the number of reentrant intention write holds on this lock by the
     * current thread.  A reader thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     *
     * @return the number of holds on the intention write lock by the current thread,
     *         or zero if the intention write lock is not held by the current thread
     */
    public int getIntentionWriteHoldCount() {
        return sync.getIntentionWriteHoldCount();
    }

    /**
     * Queries the number of reentrant write holds on this lock by the
     * current thread.  A reader thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     *
     * @return the number of holds on the write lock by the current thread,
     *         or zero if the write lock is not held by the current thread
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * Returns the thread id for the given thread.  We must access
     * this directly rather than via method Thread.getId() because
     * getId() is not final, and has been known to be overridden in
     * ways that do not preserve unique mappings.
     */
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            final Field singleoneInstanceField = Unsafe.class.getDeclaredField("theUnsafe");
            singleoneInstanceField.setAccessible(true);
            UNSAFE = (Unsafe) singleoneInstanceField.get(null);

            final Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                    (tk.getDeclaredField("tid"));
        } catch (final Exception e) {
            throw new Error(e);
        }
    }
}
