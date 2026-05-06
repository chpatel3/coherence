/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.internal.util;

import com.oracle.coherence.persistence.PersistentStore;

import com.tangosol.io.ReadBuffer;

/**
 * PartitionedCacheComponent is an internal interface to expose internal
 * methods in the PartitionedCache TDE component.
 *
 * @author mf 2017.06.29
 * @since Coherence 14.1.1
 */
public interface PartitionedCacheComponent
    extends PartitionedServiceComponent
    {
    /**
     * Return the id for the specified cache.
     *
     * @param sName  the cache name
     *
     * @return the cache id
     */
    long getCacheId(String sName);

    /**
     * Return the persistent store for the specified partition.
     *
     * @param nPartition  the partition id
     *
     * @return the partition's persistent store, or {@code null} if none is available
     */
    PersistentStore<ReadBuffer> getPersistentStore(int nPartition);

    /**
     * Ensure the persistent store for the specified partition is open.
     *
     * @param nPartition  the partition id
     *
     * @return the partition's open persistent store, or {@code null} if none is available
     */
    PersistentStore<ReadBuffer> ensureOpenPersistentStore(int nPartition);

    /**
     * Return the backup persistent store for the specified partition.
     *
     * @param nPartition  the partition id
     *
     * @return the partition's backup persistent store, or {@code null} if none is available
     */
    PersistentStore<ReadBuffer> getBackupPersistentStore(int nPartition);

    /**
     * Ensure the backup persistent store for the specified partition is open.
     *
     * @param nPartition  the partition id
     *
     * @return the partition's open backup persistent store, or {@code null} if none is available
     */
    PersistentStore<ReadBuffer> ensureOpenBackupPersistentStore(int nPartition);

    /**
     * Return {@code true} iff active persistence is configured for this service.
     *
     * @return {@code true} iff active persistence is configured
     */
    boolean isActivePersistence();
    }
