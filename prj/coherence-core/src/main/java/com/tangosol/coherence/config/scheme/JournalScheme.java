/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.coherence.config.scheme;

import com.oracle.coherence.persistence.PersistentStore;

import com.tangosol.config.expression.ParameterResolver;

import com.tangosol.internal.util.PartitionedCacheComponent;

import com.tangosol.io.ReadBuffer;

import com.tangosol.net.BackingMapManager;
import com.tangosol.net.BackingMapManagerContext;
import com.tangosol.net.CacheService;
import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.partition.PartitionSplittingBackingMap;
import com.tangosol.net.security.StorageAccessAuthorizer;

import com.tangosol.persistence.journal.JournalBackingMap;
import com.tangosol.persistence.journal.PersistentBackingMap;

import java.util.Map;

/**
 * A persistent journal-backed backing map scheme.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class JournalScheme
        extends AbstractLocalCachingScheme<Map>
    {
    @Override
    public Map realizeMap(ParameterResolver resolver, Dependencies dependencies)
        {
        validate(resolver);

        BackingMapManagerContext context = dependencies.getBackingMapManagerContext();
        if (context == null)
            {
            return new JournalBackingMap();
            }

        CacheService service = context.getCacheService();
        if (!(service instanceof PartitionedCacheComponent))
            {
            throw new IllegalStateException("journal-scheme requires a partitioned cache service");
            }

        PartitionedCacheComponent servicePartitioned = (PartitionedCacheComponent) service;
        boolean fBackup = dependencies.isBackup();
        if (!fBackup && !servicePartitioned.isActivePersistence())
            {
            throw new IllegalStateException(
                    "journal-scheme requires active persistence (active or active-backup) to be configured on the distributed scheme");
            }

        return new JournalPartitionSplittingBackingMap(new JournalPartitionBackingMapManager(
                dependencies.getConfigurableCacheFactory(),
                context,
                servicePartitioned,
                dependencies.getCacheName(),
                fBackup),
                dependencies.getCacheName());
        }

    // ----- inner class: JournalPartitionBackingMapManager -----------------

    /**
     * Partition-aware journal map that marks itself as directly mutating the journal store.
     */
    protected static class JournalPartitionSplittingBackingMap
            extends PartitionSplittingBackingMap
            implements PersistentBackingMap
        {
        /**
         * Create a journal-backed partition splitting map.
         *
         * @param bmm    the partition backing map manager
         * @param sName  the cache name
         */
        protected JournalPartitionSplittingBackingMap(BackingMapManager bmm, String sName)
            {
            super(bmm, sName);
            }
        }

    /**
     * BackingMapManager used by {@link PartitionSplittingBackingMap} to create
     * per-partition persistent backing-map views.
     */
    protected static class JournalPartitionBackingMapManager
            implements BackingMapManager
        {
        /**
         * Create a manager for partition-scoped journal maps belonging to a single cache.
         *
         * @param ccf      the owning cache factory
         * @param context  the backing map manager context
         * @param service  the partitioned cache service
         * @param sCache   the cache name
         */
        protected JournalPartitionBackingMapManager(ConfigurableCacheFactory ccf,
                BackingMapManagerContext context, PartitionedCacheComponent service, String sCache)
            {
            this(ccf, context, service, sCache, false);
            }

        /**
         * Create a manager for partition-scoped journal maps belonging to a single cache.
         *
         * @param ccf      the owning cache factory
         * @param context  the backing map manager context
         * @param service  the partitioned cache service
         * @param sCache   the cache name
         * @param fBackup  {@code true} to realize backup maps over backup stores
         */
        protected JournalPartitionBackingMapManager(ConfigurableCacheFactory ccf,
                BackingMapManagerContext context, PartitionedCacheComponent service, String sCache, boolean fBackup)
            {
            f_ccf       = ccf;
            m_context   = context;
            f_service   = service;
            f_sCache    = sCache;
            f_lExtentId = service.getCacheId(sCache);
            f_fBackup   = fBackup;
            }

        @Override
        public void init(BackingMapManagerContext context)
            {
            m_context = context;
            }

        @Override
        public ConfigurableCacheFactory getCacheFactory()
            {
            return f_ccf;
            }

        @Override
        public BackingMapManagerContext getContext()
            {
            return m_context;
            }

        @Override
        public Map instantiateBackingMap(String sName)
            {
            long lExtentId = f_lExtentId;
            if (lExtentId == 0L)
                {
                throw new IllegalStateException("cache id not assigned for " + f_sCache);
                }

            int nPartition = parsePartition(sName);
            JournalBackingMap map = new JournalBackingMap();
            map.setStore(getPersistentStore(nPartition));
            map.setBackup(f_fBackup);
            map.setPartitionedCacheService(f_service);
            map.setPartition(nPartition);
            map.setExtentId(lExtentId);
            map.setCacheName(f_sCache);
            return map;
            }

        @Override
        public boolean isBackingMapPersistent(String sName)
            {
            // JournalBackingMap mutates the persistent store directly, so enabling the
            // persistence side-channel would append the same mutation twice.
            return false;
            }

        @Override
        public boolean isBackingMapSlidingExpiry(String sName)
            {
            return false;
            }

        @Override
        public StorageAccessAuthorizer getStorageAccessAuthorizer(String sName)
            {
            return null;
            }

        @Override
        public void releaseBackingMap(String sName, Map map)
            {
            if (map instanceof JournalBackingMap)
                {
                JournalBackingMap mapJournal = (JournalBackingMap) map;
                mapJournal.setStore(null);
                mapJournal.setPartitionedCacheService(null);
                mapJournal.setPartition(-1);
                }
            }

        /**
         * Return the persistent store backing the partition map being realized.
         *
         * @param nPartition  the partition id
         *
         * @return the backing persistent store
         */
        protected PersistentStore<ReadBuffer> getPersistentStore(int nPartition)
            {
            if (!f_fBackup)
                {
                return f_service.getPersistentStore(nPartition);
                }

            try
                {
                return f_service.getBackupPersistentStore(nPartition);
                }
            catch (IllegalStateException e)
                {
                throw new IllegalStateException(
                        "journal-scheme backup maps require backup persistence to be configured on the distributed scheme", e);
                }
            }

        /**
         * Parse the partition suffix from the backing map name.
         *
         * @param sName  the partition-specific cache name
         *
         * @return the partition id
         */
        protected int parsePartition(String sName)
            {
            int of = sName.lastIndexOf('-');
            if (of < 0 || of == sName.length() - 1)
                {
                throw new IllegalArgumentException("partition suffix missing from backing map name: " + sName);
                }

            try
                {
                return Integer.parseInt(sName.substring(of + 1));
                }
            catch (NumberFormatException e)
                {
                throw new IllegalArgumentException(
                        "non-numeric partition suffix in backing map name: " + sName, e);
                }
            }

        /**
         * The owning cache factory.
         */
        protected final ConfigurableCacheFactory f_ccf;

        /**
         * The partitioned cache service.
         */
        protected final PartitionedCacheComponent f_service;

        /**
         * The cache name for this map.
         */
        protected final String f_sCache;

        /**
         * The fixed cache-id/extent-id for this cache.
         */
        protected final long f_lExtentId;

        /**
         * {@code true} iff this manager realizes backup maps over backup stores.
         */
        protected final boolean f_fBackup;

        /**
         * The backing map manager context.
         */
        protected BackingMapManagerContext m_context;
        }
    }
