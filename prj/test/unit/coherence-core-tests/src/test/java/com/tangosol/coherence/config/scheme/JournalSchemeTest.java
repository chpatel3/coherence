/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.coherence.config.scheme;

import com.oracle.coherence.persistence.PersistentStore;

import com.tangosol.coherence.config.builder.MapBuilder;

import com.tangosol.config.expression.NullParameterResolver;

import com.tangosol.internal.util.PartitionedCacheComponent;

import com.tangosol.io.ReadBuffer;

import com.tangosol.net.BackingMapManagerContext;
import com.tangosol.net.CacheService;
import com.tangosol.net.partition.PartitionAwareBackingMap;

import com.tangosol.persistence.journal.JournalBackingMap;
import com.tangosol.persistence.journal.PersistentBackingMap;

import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JournalScheme}.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class JournalSchemeTest
    {
    @Test
    public void testRealizeMapReturnsJournalBackingMap()
        {
        JournalScheme scheme = new JournalScheme();
        MapBuilder.Dependencies dependencies = new MapBuilder.Dependencies(null, null, null,
                "test-cache", "LocalCache");

        Map map = scheme.realizeMap(new NullParameterResolver(), dependencies);

        assertTrue(map instanceof JournalBackingMap);
        }

    @Test
    @SuppressWarnings("unchecked")
    public void testRealizeMapReturnsPartitionAwareBackingMapWhenContextPresent()
        {
        JournalScheme scheme = new JournalScheme();

        PersistentStore<ReadBuffer> store    = mock(PersistentStore.class);
        PartitionedCacheComponent    service  = (PartitionedCacheComponent)
                mock(CacheService.class, withSettings().extraInterfaces(PartitionedCacheComponent.class));
        CacheService                 cacheService = (CacheService) service;
        BackingMapManagerContext     context  = mock(BackingMapManagerContext.class);

        when(service.isActivePersistence()).thenReturn(true);
        when(service.getPartitionCount()).thenReturn(257);
        when(service.getCacheId("test-cache")).thenReturn(17L);
        when(service.getPersistentStore(3)).thenReturn(store);
        when(context.getCacheService()).thenReturn(cacheService);

        MapBuilder.Dependencies dependencies = new MapBuilder.Dependencies(null, context, null,
                "test-cache", "DistributedCache");

        Map map = scheme.realizeMap(new NullParameterResolver(), dependencies);

        assertTrue(map instanceof PartitionAwareBackingMap);
        assertTrue(map instanceof PersistentBackingMap);

        PartitionAwareBackingMap pabm = (PartitionAwareBackingMap) map;
        pabm.createPartition(3);

        Map mapPartition = pabm.getPartitionMap(3);
        assertTrue(mapPartition instanceof JournalBackingMap);
        assertSame(store, ((JournalBackingMap) mapPartition).getStore());
        assertEquals(17L, ((JournalBackingMap) mapPartition).getExtentId());
        verify(service).getPersistentStore(3);
        verify(service, never()).ensureOpenPersistentStore(3);
        verify(service, never()).getBackupPersistentStore(3);
        verify(service, never()).ensureOpenBackupPersistentStore(3);
        }

    @Test
    @SuppressWarnings("unchecked")
    public void testBackupRealizeMapUsesBackupStore()
        {
        JournalScheme scheme = new JournalScheme();

        PersistentStore<ReadBuffer> store    = mock(PersistentStore.class);
        PartitionedCacheComponent   service  = (PartitionedCacheComponent)
                mock(CacheService.class, withSettings().extraInterfaces(PartitionedCacheComponent.class));
        CacheService                cacheService = (CacheService) service;
        BackingMapManagerContext    context  = mock(BackingMapManagerContext.class);

        when(service.getPartitionCount()).thenReturn(257);
        when(service.getCacheId("test-cache")).thenReturn(17L);
        when(service.getBackupPersistentStore(3)).thenReturn(store);
        when(context.getCacheService()).thenReturn(cacheService);

        MapBuilder.Dependencies dependencies = new MapBuilder.Dependencies(null, context, null,
                "test-cache", "DistributedCache");
        dependencies.setBackup(true);

        Map map = scheme.realizeMap(new NullParameterResolver(), dependencies);

        assertTrue(map instanceof PartitionAwareBackingMap);
        assertTrue(map instanceof PersistentBackingMap);

        PartitionAwareBackingMap pabm = (PartitionAwareBackingMap) map;
        pabm.createPartition(3);

        Map mapPartition = pabm.getPartitionMap(3);
        assertTrue(mapPartition instanceof JournalBackingMap);
        assertSame(store, ((JournalBackingMap) mapPartition).getStore());
        assertEquals(17L, ((JournalBackingMap) mapPartition).getExtentId());
        verify(service).getBackupPersistentStore(3);
        verify(service, never()).getPersistentStore(3);
        verify(service, never()).ensureOpenPersistentStore(3);
        }

    @Test
    public void testRealizeMapRejectsMissingActivePersistence()
        {
        JournalScheme scheme = new JournalScheme();

        PartitionedCacheComponent service = (PartitionedCacheComponent)
                mock(CacheService.class, withSettings().extraInterfaces(PartitionedCacheComponent.class));
        CacheService             cacheService = (CacheService) service;
        BackingMapManagerContext context = mock(BackingMapManagerContext.class);

        when(service.isActivePersistence()).thenReturn(false);
        when(context.getCacheService()).thenReturn(cacheService);

        MapBuilder.Dependencies dependencies = new MapBuilder.Dependencies(null, context, null,
                "test-cache", "DistributedCache");

        try
            {
            scheme.realizeMap(new NullParameterResolver(), dependencies);
            throw new AssertionError("expected IllegalStateException");
            }
        catch (IllegalStateException e)
            {
            assertTrue(e.getMessage().contains("journal-scheme requires active persistence"));
            }
        }

    @Test
    public void testBackupRealizeMapRejectsMissingBackupPersistence()
        {
        JournalScheme scheme = new JournalScheme();

        PartitionedCacheComponent service = (PartitionedCacheComponent)
                mock(CacheService.class, withSettings().extraInterfaces(PartitionedCacheComponent.class));
        CacheService             cacheService = (CacheService) service;
        BackingMapManagerContext context = mock(BackingMapManagerContext.class);

        when(service.getPartitionCount()).thenReturn(257);
        when(service.getCacheId("test-cache")).thenReturn(17L);
        when(service.getBackupPersistentStore(3)).thenThrow(new IllegalStateException("backup persistence missing"));
        when(context.getCacheService()).thenReturn(cacheService);

        MapBuilder.Dependencies dependencies = new MapBuilder.Dependencies(null, context, null,
                "test-cache", "DistributedCache");
        dependencies.setBackup(true);

        Map map = scheme.realizeMap(new NullParameterResolver(), dependencies);

        try
            {
            ((PartitionAwareBackingMap) map).createPartition(3);
            throw new AssertionError("expected IllegalStateException");
            }
        catch (IllegalStateException e)
            {
            assertTrue(e.getMessage().contains("backup persistence"));
            }
        }

    @Test
    public void testJournalBackingMapIsNotMarkedPersistent()
        {
        JournalScheme.JournalPartitionBackingMapManager manager =
                new JournalScheme.JournalPartitionBackingMapManager(null, null,
                        mock(PartitionedCacheComponent.class), "test-cache");

        assertFalse(manager.isBackingMapPersistent("test-cache-3"));
        }

    @Test
    public void testParsePartitionRejectsNonNumericSuffix()
        {
        PartitionedCacheComponent service = mock(PartitionedCacheComponent.class);
        when(service.getCacheId("test-cache")).thenReturn(17L);

        JournalScheme.JournalPartitionBackingMapManager manager =
                new JournalScheme.JournalPartitionBackingMapManager(null, null, service, "test-cache");

        try
            {
            manager.parsePartition("test-cache-abc");
            throw new AssertionError("expected IllegalArgumentException");
            }
        catch (IllegalArgumentException e)
            {
            assertTrue(e.getMessage().contains("non-numeric partition suffix"));
            }
        }

    @Test
    @SuppressWarnings("unchecked")
    public void testReleaseBackingMapClearsJournalReferences()
        {
        JournalScheme.JournalPartitionBackingMapManager manager =
                new JournalScheme.JournalPartitionBackingMapManager(null, null,
                        mock(PartitionedCacheComponent.class), "test-cache");

        JournalBackingMap map = new JournalBackingMap();
        PersistentStore<ReadBuffer> store = mock(PersistentStore.class);
        PartitionedCacheComponent service = mock(PartitionedCacheComponent.class);

        map.setStore(store);
        map.setPartitionedCacheService(service);
        map.setPartition(3);

        manager.releaseBackingMap("test-cache-3", map);

        assertNull(map.getStore());
        assertNull(map.get(new Object()));
        verifyNoInteractions(service);
        }
    }
