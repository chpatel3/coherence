/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.internal.net.queue;

import com.tangosol.internal.net.queue.model.QueueKey;

import com.tangosol.net.NamedBlockingQueue;
import com.tangosol.net.NamedMap;
import com.tangosol.util.MapEvent;
import com.tangosol.util.MapListener;

import com.tangosol.util.filter.AlwaysFilter;

import java.util.Collection;
import java.util.Objects;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link NamedBlockingQueue} implementation that wraps a {@link NamedCacheQueue}.
 *
 * @param <E> the type of elements held in this queue
 */
public class NamedCacheBlockingQueue<K extends QueueKey, E>
        extends WrapperNamedCacheQueue<K, E>
        implements NamedBlockingQueue<E>, MapListener<K, E>
    {
    /**
     * Create a {@link NamedCacheBlockingQueue} that delegates to
     * the specified {@link NamedCacheQueue}.
     *
     * @param sName     the name of the queue
     * @param delegate  the {@link NamedCacheQueue} to delegate to
     */
    public NamedCacheBlockingQueue(String sName, BaseNamedCacheQueue<K, E> delegate)
        {
        super(sName, delegate);
        delegate.getCache().addMapListener(this, AlwaysFilter.INSTANCE(), true);
        m_nHash = QueueKey.calculateQueueHash(delegate.getName());
        }

    // ----- BlockingQueue methods ------------------------------------------

    @Override
    public void release()
        {
        f_delegate.getCache().removeMapListener(this);
        super.release();
        }

    @Override
    public long append(E e, long timeout, TimeUnit unit) throws InterruptedException
        {
        long          nanos = unit.toNanos(timeout);
        ReentrantLock lock  = m_lock;
        lock.lockInterruptibly();
        try
            {
            long nId = append(e);
            while (nId < 0L)
                {
                if (nanos <= 0L)
                    {
                    return -1L;
                    }
                nanos = m_notFull.awaitNanos(nanos);
                nId = append(e);
              }
            return nId;
            }
        finally
            {
            lock.unlock();
            }
        }

    @Override
    public long appendLast(E e) throws InterruptedException
        {
        final ReentrantLock lock = m_lock;
        lock.lock();
        try
            {
            long nId = append(e);
            while (nId < 0L)
                {
                m_notFull.await();
                nId = append(e);
                }
            return nId;
            }
        finally
            {
            lock.unlock();
            }
        }


    @Override
    public void put(E e) throws InterruptedException
        {
        final ReentrantLock lock = m_lock;
        lock.lock();
        try
            {
            while (!offer(e))
                {
                m_notFull.await();
                }
            }
        finally
            {
            lock.unlock();
            }
        }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException
        {
        long          nanos = unit.toNanos(timeout);
        ReentrantLock lock  = m_lock;
        lock.lockInterruptibly();
        try
            {
            while (!offer(e))
                {
                if (nanos <= 0L)
                    {
                    return false;
                    }
                nanos = m_notFull.awaitNanos(nanos);
              }
            return true;
            }
        finally
            {
            lock.unlock();
            }
        }

    @Override
    public E take() throws InterruptedException
        {
        final ReentrantLock lock = m_lock;
        lock.lock();
        try
            {
            E x;
            while ( (x = poll()) == null)
                {
                m_notEmpty.await();
                }
            return x;
            }
        finally
            {
            lock.unlock();
            }
        }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException
        {
        long          nanos = unit.toNanos(timeout);
        ReentrantLock lock  = m_lock;
        lock.lockInterruptibly();
        try
            {
            E x;
            while ( (x = poll()) == null) {
                if (nanos <= 0L)
                    {
                    return null;
                    }
                nanos = m_notEmpty.awaitNanos(nanos);
                }
            return x;
            }
        finally
            {
            lock.unlock();
            }
        }

    @Override
    public int remainingCapacity()
        {
        // ToDo are we size limited, if so return something meaningful
        return Integer.MAX_VALUE;
        }

    @Override
    public int drainTo(Collection<? super E> c)
        {
        assertNotSameCollection(c, "Queue cannot be drained to the same underlying cache");

        int cPolled = 0;
        E   element = poll();
        while (element != null)
            {
            c.add(element);
            cPolled++;
            element = poll();
            }
        return cPolled;
        }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements)
        {
        assertNotSameCollection(c, "Queue cannot be drained to the same underlying cache");

        int cPolled  = 0;
        while (cPolled < maxElements)
            {
            E element = poll();
            if (element == null)
                {
                break;
                }
            c.add(element);
            cPolled++;
            }
        return cPolled;
        }

    // ----- MapListener methods --------------------------------------------

    @Override
    public void entryInserted(MapEvent<K, E> evt)
        {
        m_lock.lock();
        try
            {
            m_notEmpty.signal();
            }
        finally
            {
            m_lock.unlock();
            }
        }

    @Override
    public void entryUpdated(MapEvent<K, E> evt)
        {
        }

    @Override
    public void entryDeleted(MapEvent<K, E> evt)
        {
        m_lock.lock();
        try
            {
            m_notFull.signal();
            }
        finally
            {
            m_lock.unlock();
            }
        }

    // ----- helper methods -------------------------------------------------

    /**
     * Obtain the underlying cache.
     *
     * @return the underlying cache
     */
    public NamedMap<K, E> getCache()
        {
        return f_delegate.getCache();
        }

    private void assertNotSameCollection(Collection<?> c, String sMsg)
        {
        if (this.equals(Objects.requireNonNull(c)))
            {
            throw new IllegalArgumentException(sMsg);
            }
        f_delegate.assertNotSameCollection(c, sMsg);
        }

    // ----- data members ---------------------------------------------------

    /**
     * The main lock guarding all access.
     */
    final ReentrantLock m_lock = new ReentrantLock();

    /**
     * The Condition for waiting takes.
     */
    private final Condition m_notEmpty = m_lock.newCondition();

    /**
     * The Condition for waiting puts.
     */
    private final Condition m_notFull = m_lock.newCondition();

    /**
     * The hash of the queue.
     */
    private final int m_nHash;
    }
