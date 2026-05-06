/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.coherence.component.util.daemon.queueProcessor.service.grid.partitionedService;

import com.oracle.coherence.persistence.PersistentStore;

import com.tangosol.coherence.component.util.daemon.queueProcessor.service.grid.partitionedService.partitionedCache.Storage;

import com.tangosol.persistence.journal.JournalBackingMap;
import com.tangosol.persistence.journal.PersistentBackingMap;

import com.tangosol.util.Binary;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the dormant persistent-backup guard used by journal backup maps.
 *
 * @author Aleks Seovic  2026.04.26
 * @since 26.04
 */
public class PartitionedCachePersistentBackupGuardTest
    {
    @Test
    public void shouldUsePersistentBackingMapMarkerForBackupMaps()
        {
        TestStorage storage = new TestStorage();

        assertFalse(storage.isPersistentBackup());

        storage.setBackupMapForTest(new HashMap<>());

        assertFalse(storage.isPersistentBackup());

        storage.setBackupMapForTest(new PersistentBackupMap());

        assertTrue(storage.isPersistentBackup());
        assertTrue(PersistentBackingMap.class.isAssignableFrom(JournalBackingMap.class));
        }

    @Test
    public void shouldUseBackupPersistenceSideChannelByDefault()
        {
        TestStorage                       storage = newPersistentStorage();
        PartitionedCache.PartitionControl control = mock(PartitionedCache.PartitionControl.class);
        PersistentStore                   store   = mock(PersistentStore.class);
        Object                            token   = new Object();
        TestPartitionedCache              service = new TestPartitionedCache(storage, control);
        Binary                            key     = binary("key");
        Binary                            value   = binary("value");

        when(control.ensureOpenPersistentStore(isNull(), eq(true), eq(true))).thenReturn(store);
        when(store.begin(isNull(), same(control))).thenReturn(token);

        assertTrue(service.shouldPersistBackupFor(storage));

        service.persistBackupEntries(1L, Collections.singletonMap(key, value));

        verify(control).ensureBackupPersistentExtent(1L);
        verify(store).store(eq(1L), same(key), same(value), same(token));
        verify(store).commit(same(token));
        }

    @Test
    public void shouldSkipBackupPersistenceSideChannelForPersistentBackupMaps()
        {
        TestStorage                       storage = newPersistentStorage();
        PartitionedCache.PartitionControl control = mock(PartitionedCache.PartitionControl.class);
        TestPartitionedCache              service = new TestPartitionedCache(storage, control);

        storage.setBackupMapForTest(new PersistentBackupMap());

        assertFalse(service.shouldPersistBackupFor(storage));

        service.persistBackupEntries(1L, Collections.singletonMap(binary("key"), binary("value")));

        verifyNoInteractions(control);
        }

    // ----- helper methods -------------------------------------------------

    private static TestStorage newPersistentStorage()
        {
        TestStorage storage = new TestStorage();
        storage.setPersistentForTest(true);
        return storage;
        }

    private static Binary binary(String sValue)
        {
        return new Binary(sValue.getBytes(StandardCharsets.UTF_8));
        }

    // ----- helper classes -------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class TestPartitionedCache
            extends PartitionedCache
        {
        TestPartitionedCache(Storage storage, PartitionControl control)
            {
            super(null, null, false);

            m_storage = storage;
            m_control = control;
            }

        boolean shouldPersistBackupFor(Storage storage)
            {
            return shouldPersistBackup(storage);
            }

        void persistBackupEntries(long lCacheId, Map mapEntries)
            {
            persistBackup(lCacheId, mapEntries);
            }

        @Override
        public Storage getKnownStorage(long lCacheId)
            {
            return m_storage;
            }

        @Override
        public PartitionControl getPartitionControl(int nPartition)
            {
            return m_control;
            }

        @Override
        public Map splitKeysByPartition(Iterator iterKeys)
            {
            Map mapByPartition = new HashMap();
            Set setKeys        = new HashSet();

            while (iterKeys.hasNext())
                {
                setKeys.add(iterKeys.next());
                }

            mapByPartition.put(Integer.valueOf(0), setKeys);
            return mapByPartition;
            }

        private final Storage          m_storage;
        private final PartitionControl m_control;
        }

    private static class TestStorage
            extends Storage
        {
        TestStorage()
            {
            super(null, null, false);
            }

        void setPersistentForTest(boolean fPersistent)
            {
            setPersistent(fPersistent);
            }

        void setBackupMapForTest(Map mapBackup)
            {
            setBackupMap(mapBackup);
            }
        }

    private static class PersistentBackupMap
            extends HashMap
            implements PersistentBackingMap
        {
        }
    }
