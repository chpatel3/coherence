/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.internal.net.queue;

import com.tangosol.internal.net.queue.model.QueueKey;
import com.tangosol.net.NamedCache;
import com.tangosol.net.NamedDeque;
import com.tangosol.net.NamedQueue;

public interface NamedCacheDequeBuilder
        extends NamedQueueBuilder
    {
    /**
     * Return the cache name to use for a given collection name.
     *
     * @param sName  the name of the collection
     *
     * @return the cache name to use for the given collection name
     */
    String getCacheName(String sName);

    /**
     * Return the collection name from a given cache name.
     *
     * @param sCacheName  the name of the cache
     *
     * @return the collection name from the given cache name
     */
    String getCollectionName(String sCacheName);

    <E> NamedDeque<E> build(String sName, NamedCache<QueueKey, E> cache);

    // ----- inner class: DefaultNamedCacheDequeBuilder ---------------------

    /**
     * The default implementation of {@link NamedCacheDequeBuilder}.
     */
    class DefaultNamedCacheDequeBuilder
            implements NamedCacheDequeBuilder
        {
        @Override
        public String getCacheName(String sName)
            {
            return sName;
            }

        @Override
        public String getCollectionName(String sCacheName)
            {
            return sCacheName;
            }

        @Override
        public <E> NamedDeque<E> build(String sName, NamedCache<QueueKey, E> cache)
            {
            return new NamedCacheDeque<>(sName, cache);
            }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean realizes(Class<? extends NamedQueue> clz)
            {
            return clz.isAssignableFrom(NamedCacheDeque.class);
            }
        }

    // ----- data members ---------------------------------------------------

    /**
     * The singleton instance of the default {@link NamedCacheDequeBuilder}.
     */
    NamedCacheDequeBuilder DEFAULT = new DefaultNamedCacheDequeBuilder();
    }
