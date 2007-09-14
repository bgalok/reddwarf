/*
 * Copyright 2007 Sun Microsystems, Inc. All rights reserved
 */

package com.sun.sgs.impl.nio;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.sgs.nio.channels.CompletionHandler;
import com.sun.sgs.nio.channels.IoFuture;
import com.sun.sgs.nio.channels.ShutdownChannelGroupException;

class TPCChannelGroup
    extends AbstractAsyncChannelGroup
{
    /* Based on the Sun JDK ThreadPoolExecutor implementation. */

    /**
     * runState provides the main lifecyle control, taking on values:
     *
     *   RUNNING:  Accept new tasks and process queued tasks
     *   SHUTDOWN: Don't accept new tasks, but process queued tasks
     *   STOP:     Don't accept new tasks, don't process queued tasks,
     *             and interrupt in-progress tasks
     *   TERMINATED: Same as STOP, plus all threads have terminated
     *
     * The numerical order among these values matters, to allow
     * ordered comparisons. The runState monotonically increases over
     * time, but need not hit each state. The transitions are:
     *
     * RUNNING -> SHUTDOWN
     *    On invocation of shutdown(), perhaps implicitly in finalize()
     * (RUNNING or SHUTDOWN) -> STOP
     *    On invocation of shutdownNow()
     * SHUTDOWN -> TERMINATED
     *    When both queue and group are empty
     * STOP -> TERMINATED
     *    When group is empty
     */
    volatile int runState;
    static final int RUNNING    = 0;
    static final int SHUTDOWN   = 1;
    static final int STOP       = 2;
    static final int TERMINATED = 3;

    /**
     * Lock held on updates to runState and channel set.
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * Set containing all channels in group. Accessed only when
     * holding mainLock.
     */
    private final HashSet<AsyncOp<? extends SelectableChannel>> channels =
        new HashSet<AsyncOp<? extends SelectableChannel>>();

    /**
     * Current channel count, updated only while holding mainLock but
     * volatile to allow concurrent readability even during updates.
     */
    private volatile int groupSize = 0;

    /**
     * Creates a new AsyncChannelGroupImpl with the given provider and
     * executor service.
     *
     * @param provider the provider
     * @param executor the executor
     */
    TPCChannelGroup(AsyncProviderImpl provider, ExecutorService executor) {
        super(provider, executor);
    }

    @Override
    <T extends SelectableChannel> AsyncOp<T> registerChannel(T channel)
    throws IOException
    {
        mainLock.lock();
        try {
            if (isShutdown()) {
                forceClose(channel);
                throw new ShutdownChannelGroupException();
            }
            AsyncOp<T> op = new TPCAsyncOp<T>(channel);
            channels.add(op);
            ++groupSize;
            return op;
        } finally {
            mainLock.unlock();
        }
    }

    <T extends SelectableChannel> void channelClosed(AsyncOp<T> ops) {
        mainLock.lock();
        try {
            channels.remove(ops);
            --groupSize;
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    class TPCAsyncOp<T extends SelectableChannel> extends AsyncOp<T> {

        private final T channel;
        private volatile int pendingOps;

        TPCAsyncOp(T channel) {
            this.channel = channel;
        }

        T channel() {
            return channel;
        }

        TPCChannelGroup group() {
            return TPCChannelGroup.this;
        }

        public void close() throws IOException {
            try {
                super.close();
            } finally {
                channelClosed(this);
            }
        }

        boolean isPending(int op) {
            return (pendingOps & op) != 0;
        }

        void setOp(int op) {
            synchronized (this) {
                checkPending(op);
                pendingOps |= op;
            }
        }

        void clearOp(int op) {
            synchronized (this) {
                pendingOps &= ~op;
            }
        }

        <R, A> IoFuture<R, A>
        submit(int op,
               A attachment,
               CompletionHandler<R, ? super A> handler, 
               long timeout, 
               TimeUnit unit, 
               Callable<R> callable)
        {
            // TODO
            return null;
        }
    }

    void awaitSelectableOp(SelectableChannel channel, long timeout, int ops)
        throws IOException
    {
        if (timeout == 0)
            return;
/*
        Selector sel = getSelectorProvider().openSelector();
        channel.register(sel, ops);
        if (sel.select(timeout) == 0)
            throw new AbortedByTimeoutException();
*/
    }

    /* Termination support. */

    private void tryTerminate() {
        if (groupSize == 0) {
            int state = runState;
            if (state == STOP || state == SHUTDOWN) {
                runState = TERMINATED;
                termination.signalAll();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException
    {
        long nanos = unit.toNanos(timeout);
        mainLock.lock();
        try {
            for (;;) {
                if (runState == TERMINATED)
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isShutdown() {
        return runState != RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTerminated() {
        return runState == TERMINATED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TPCChannelGroup shutdown() {
        mainLock.lock();
        try {
            int state = runState;
            if (state < SHUTDOWN)
                runState = SHUTDOWN;

            tryTerminate();
            return this;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TPCChannelGroup shutdownNow() throws IOException
    {
        mainLock.lock();
        try {
            int state = runState;
            if (state < STOP)
                runState = STOP;

            for (AsyncOp<?> op : channels)
                forceClose(op);

            tryTerminate();
            return this;
        } finally {
            mainLock.unlock();
        }
    }

    private void forceClose(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignore) { }
    }

    /**
     * Invokes {@code shutdown} when this channel group is no longer
     * referenced.
     */
    @Override
    protected void finalize() {
        // TODO is this actually useful? -JM
        shutdown();
    }
}
