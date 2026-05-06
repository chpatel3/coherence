/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence;

import com.oracle.coherence.common.base.Collector;
import com.oracle.coherence.persistence.OfflinePersistenceInfo;
import com.oracle.coherence.persistence.PersistenceException;
import com.oracle.coherence.persistence.PersistenceManager;
import com.oracle.coherence.persistence.PersistentStore;
import com.oracle.coherence.persistence.PersistenceTools;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;

import com.tangosol.net.Cluster;
import com.tangosol.net.Member;
import com.tangosol.net.PartitionedService;
import com.tangosol.net.ServiceInfo;

import com.tangosol.persistence.bdb.BerkeleyDBManager;

import com.tangosol.util.Base;
import com.tangosol.util.Binary;
import com.tangosol.util.ExternalizableHelper;
import com.tangosol.util.LongArray;
import com.tangosol.util.MapTrigger;
import com.tangosol.util.SimpleMapEntry;
import com.tangosol.util.SparseArray;

import com.tangosol.util.comparator.SafeComparator;

import com.tangosol.util.extractor.ReflectionExtractor;

import com.tangosol.util.filter.AlwaysFilter;
import com.tangosol.util.filter.FilterTrigger;

import java.io.File;
import java.io.IOException;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for CachePersistenceHelper.
 *
 * @author jh  2013.03.01
 */
public class CachePersistenceHelperTest
    {

    // ----- test lifecycle -------------------------------------------------

    @Before
    public void setupTest()
            throws IOException
        {
        m_file    = FileHelper.createTempDir();
        m_manager = new BerkeleyDBManager(m_file, null, null);
        m_store   = m_manager.open(AbstractPersistentStoreTest.TEST_STORE_ID, null);

        // populate the store with some dummy data
        m_store.ensureExtent(1L);
        m_store.store(1L, TEST_KEY_1, TEST_VALUE_1, null);
        m_store.store(1L, TEST_KEY_2, TEST_VALUE_2, null);
        m_store.store(1L, TEST_KEY_3, TEST_VALUE_3, null);
        }

    @After
    public void teardownTest()
        {
        m_manager.delete(AbstractPersistentStoreTest.TEST_STORE_ID, false);
        m_manager.release();
        try
            {
            FileHelper.deleteDir(m_file);
            }
        catch (IOException e)
            {
            // ignore
            }
        }

    // ----- test methods ---------------------------------------------------

    @Test
    public void testEnsurePersistenceException()
        {
        final PersistenceException e = new PersistenceException("error");

        PersistenceException ee = CachePersistenceHelper.ensurePersistenceException(e);
        assertEquals(e, ee);

        ee = CachePersistenceHelper.ensurePersistenceException(null);
        assertNull(ee.getMessage());
        assertNull(ee.getCause());

        final RuntimeException eCause = new RuntimeException("cause");
        ee = CachePersistenceHelper.ensurePersistenceException(eCause);
        assertEquals(eCause.toString(), ee.getMessage());
        assertEquals(eCause, ee.getCause());
        }

    @Test
    public void testEnsurePersistenceExceptionWithMessage()
        {
        final PersistenceException e = new PersistenceException("error");

        PersistenceException ee = CachePersistenceHelper.ensurePersistenceException(e, null);
        assertEquals(e, ee);

        ee = CachePersistenceHelper.ensurePersistenceException(null, null);
        assertNull(ee.getMessage());
        assertNull(ee.getCause());

        ee = CachePersistenceHelper.ensurePersistenceException(null, "test");
        assertEquals("test", ee.getMessage());
        assertNull(ee.getCause());

        final RuntimeException eCause = new RuntimeException("cause");
        ee = CachePersistenceHelper.ensurePersistenceException(eCause, null);
        assertEquals(eCause.toString(), ee.getMessage());
        assertEquals(eCause, ee.getCause());

        ee = CachePersistenceHelper.ensurePersistenceException(eCause, "test");
        assertEquals("test", ee.getMessage());
        assertEquals(eCause, ee.getCause());
        }

    @Test
    public void testSealAndValidate()
        {
        PartitionedService service = mock(PartitionedService.class);
        ServiceInfo        info    = mock(ServiceInfo.class);
        Cluster            cluster = mock(Cluster.class);
        Member             member  = mock(Member.class);

        when(service.getPartitionCount()).thenReturn(257);
        when(service.getInfo()).thenReturn(info);
        when(info.getServiceVersion(member)).thenReturn("12.1.3");
        when(service.getCluster()).thenReturn(cluster);
        when(cluster.getLocalMember()).thenReturn(member);

        try
            {
            CachePersistenceHelper.validateForActiveRecovery(m_store, service);
            fail("Expected validation error did not occur");
            }
        catch (PersistenceException e)
            {
            }

        CachePersistenceHelper.seal(m_store, service, /*oToken*/ null);
        CachePersistenceHelper.validateForActiveRecovery(m_store, service);
        // this method returning without throwing an exception is itself an assertion
        }

    @Test
    public void testStoreAndGetCacheNames()
        {
        PartitionedService service = mock(PartitionedService.class);
        ServiceInfo        info    = mock(ServiceInfo.class);
        Cluster            cluster = mock(Cluster.class);
        Member             member  = mock(Member.class);

        when(service.getPartitionCount()).thenReturn(257);
        when(service.getInfo()).thenReturn(info);
        when(info.getServiceVersion(member)).thenReturn("12.1.3");
        when(service.getCluster()).thenReturn(cluster);
        when(cluster.getLocalMember()).thenReturn(member);

        CachePersistenceHelper.seal(m_store, service, /*oToken*/ null);
        assertTrue(CachePersistenceHelper.getCacheNamesForActiveRecovery(m_store).isEmpty());

        LongArray la = new SparseArray();
        la.set(1L, "Test");

        CachePersistenceHelper.storeCacheNames(m_store, la);
        assertEquals(la, CachePersistenceHelper.getCacheNamesForActiveRecovery(m_store));
        }

    @Test
    public void testActiveRecoveryValidateRejectsOpenEmptyStore()
        {
        PartitionedService service = mockPartitionedService();
        String sStoreId = "active-empty-validate-store";
        PersistentStore<ReadBuffer> store = m_manager.open(sStoreId, null);
        try
            {
            assertValidateRejectsMissingMetadata(store, service);
            }
        finally
            {
            closeAndDeleteStore(sStoreId);
            }
        }

    @Test
    public void testActiveRecoveryGetCacheNamesRejectsOpenEmptyStore()
        {
        String sStoreId = "active-empty-caches-store";
        PersistentStore<ReadBuffer> store = m_manager.open(sStoreId, null);
        try
            {
            assertGetCacheNamesRejectsMissingMetadata(store);
            }
        finally
            {
            closeAndDeleteStore(sStoreId);
            }
        }

    @Test
    public void testActiveRecoveryUnsealRejectsOpenEmptyStore()
        {
        String sStoreId = "active-empty-unseal-store";
        PersistentStore<ReadBuffer> store = m_manager.open(sStoreId, null);
        try
            {
            assertUnsealRejectsMissingMetadata(store);
            }
        finally
            {
            closeAndDeleteStore(sStoreId);
            }
        }

    @Test
    public void testSnapshotRecoveryAcceptsOpenEmptyStoreWithSparseMarker()
        {
        PartitionedService service = mockPartitionedService();
        String sStoreId = "snapshot-empty-sparse-store";
        PersistentStore<ReadBuffer> store = m_manager.open(sStoreId, null);
        try
            {
            assertArrayEquals(new long[0], store.extents());

            CachePersistenceHelper.validateForSnapshotRecovery(store, service, true);
            assertTrue(CachePersistenceHelper.getCacheNamesForSnapshotRecovery(store, true).isEmpty());
            CachePersistenceHelper.unsealForSnapshotRecovery(store, true);
            }
        finally
            {
            closeAndDeleteStore(sStoreId);
            }
        }

    @Test
    public void testSnapshotRecoveryRejectsOpenEmptyStoreWithoutSparseMarker()
        {
        PartitionedService service = mockPartitionedService();
        String sStoreId = "snapshot-empty-non-sparse-store";
        PersistentStore<ReadBuffer> store = m_manager.open(sStoreId, null);
        try
            {
            assertArrayEquals(new long[0], store.extents());
            assertValidateForSnapshotRecoveryRejectsMissingMetadata(store, service, false);
            assertGetCacheNamesForSnapshotRecoveryRejectsMissingMetadata(store, false);
            assertUnsealForSnapshotRecoveryRejectsMissingMetadata(store, false);
            }
        finally
            {
            closeAndDeleteStore(sStoreId);
            }
        }

    @Test
    public void testMoveExtentsSkipsMissingMetaExtents()
        {
        PersistentStore store = mock(PersistentStore.class);

        when(store.containsExtent(17L)).thenReturn(true);
        when(store.containsExtent(-17L)).thenReturn(true);

        CachePersistenceHelper.moveExtents(store, 17L, 23L);

        verify(store).moveExtents(new long[] {17L, -17L}, new long[] {23L, -23L});
        verify(store, times(CachePersistenceHelper.RESERVED_META_EXTENTS + 1)).containsExtent(anyLong());
        verifyNoMoreInteractions(store);
        }

    @Test
    public void testPersistenceVisitor()
        {
        TestVisitor visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        }

    protected void testPersistenceVisitor(TestVisitor visitor)
        {
        m_store.iterate(CachePersistenceHelper.instantiatePersistenceVisitor(visitor));
        assertEquals(3, visitor.f_mapEntries.size());
        assertEquals(new SimpleMapEntry(TEST_KEY_1, TEST_VALUE_1),
                visitor.f_mapEntries.get(TEST_KEY_1));
        assertEquals(new SimpleMapEntry(TEST_KEY_2, TEST_VALUE_2),
                visitor.f_mapEntries.get(TEST_KEY_2));
        assertEquals(new SimpleMapEntry(TEST_KEY_3, TEST_VALUE_3),
                visitor.f_mapEntries.get(TEST_KEY_3));
        }

    protected PartitionedService mockPartitionedService()
        {
        PartitionedService service = mock(PartitionedService.class);
        ServiceInfo        info    = mock(ServiceInfo.class);
        Cluster            cluster = mock(Cluster.class);
        Member             member  = mock(Member.class);

        when(service.getPartitionCount()).thenReturn(257);
        when(service.getInfo()).thenReturn(info);
        when(info.getServiceVersion(member)).thenReturn("12.1.3");
        when(service.getCluster()).thenReturn(cluster);
        when(cluster.getLocalMember()).thenReturn(member);

        return service;
        }

    protected void assertValidateRejectsMissingMetadata(PersistentStore<ReadBuffer> store, PartitionedService service)
        {
        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> CachePersistenceHelper.validateForActiveRecovery(store, service));

        assertTrue(exception.getMessage().contains("missing internal extent"));
        }

    protected void assertGetCacheNamesRejectsMissingMetadata(PersistentStore<ReadBuffer> store)
        {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CachePersistenceHelper.getCacheNamesForActiveRecovery(store));

        assertTrue(exception.getMessage().contains("unknown extent identifier"));
        }

    protected void assertUnsealRejectsMissingMetadata(PersistentStore<ReadBuffer> store)
        {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CachePersistenceHelper.unsealForActiveRecovery(store));

        assertTrue(exception.getMessage().contains("unknown extent identifier"));
        }

    protected void assertValidateForSnapshotRecoveryRejectsMissingMetadata(PersistentStore<ReadBuffer> store,
            PartitionedService service, boolean fSparseSnapshot)
        {
        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> CachePersistenceHelper.validateForSnapshotRecovery(store, service, fSparseSnapshot));

        assertTrue(exception.getMessage().contains("missing internal extent"));
        }

    protected void assertGetCacheNamesForSnapshotRecoveryRejectsMissingMetadata(PersistentStore<ReadBuffer> store,
            boolean fSparseSnapshot)
        {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CachePersistenceHelper.getCacheNamesForSnapshotRecovery(store, fSparseSnapshot));

        assertTrue(exception.getMessage().contains("unknown extent identifier"));
        }

    protected void assertUnsealForSnapshotRecoveryRejectsMissingMetadata(PersistentStore<ReadBuffer> store,
            boolean fSparseSnapshot)
        {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CachePersistenceHelper.unsealForSnapshotRecovery(store, fSparseSnapshot));

        assertTrue(exception.getMessage().contains("unknown extent identifier"));
        }

    protected void closeAndDeleteStore(String sStoreId)
        {
        m_manager.close(sStoreId);
        m_manager.delete(sStoreId, false);
        }

    @Test
    public void testListenerLifecycle()
        {
        TestVisitor visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertTrue(visitor.f_mapListeners.isEmpty());

        CachePersistenceHelper.registerListener(m_store, 1L, TEST_KEY_2, 2L, true, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(1, visitor.f_mapListeners.size());
        assertArrayEquals(new Object[] { Long.valueOf(2L), Boolean.TRUE },
                visitor.f_mapListeners.get(TEST_KEY_2));

        CachePersistenceHelper.unregisterListener(m_store, 1L, TEST_KEY_2, 2L, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapListeners.size());

        CachePersistenceHelper.registerListener(m_store, 1L, TEST_KEY_2, 2L, true, null);
        CachePersistenceHelper.unregisterListeners(m_store, 1L);
        Base.sleep(2000L);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapListeners.size());

        CachePersistenceHelper.registerListener(m_store, 1L, TEST_KEY_2, 2L, false, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(1, visitor.f_mapListeners.size());
        assertArrayEquals(new Object[] { Long.valueOf(2L), Boolean.FALSE },
                visitor.f_mapListeners.get(TEST_KEY_2));

        CachePersistenceHelper.unregisterListener(m_store, 1L, TEST_KEY_2, 2L, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapListeners.size());

        CachePersistenceHelper.registerListener(m_store, 1L, TEST_KEY_2, 2L, false, null);
        CachePersistenceHelper.unregisterListeners(m_store, 1L);
        Base.sleep(2000L);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapListeners.size());
        }

    @Test
    public void testLockLifecycle()
        {
        TestVisitor visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertTrue(visitor.f_mapLocks.isEmpty());

        CachePersistenceHelper.registerLock(m_store, 1L, TEST_KEY_2, 2L, 3L, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(1, visitor.f_mapLocks.size());
        assertArrayEquals(new Object[] { Long.valueOf(2L), Long.valueOf(3L) },
                visitor.f_mapLocks.get(TEST_KEY_2));

        CachePersistenceHelper.unregisterLock(m_store, 1L, TEST_KEY_2, 2L, 3L, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapLocks.size());

        CachePersistenceHelper.registerLock(m_store, 1L, TEST_KEY_2, 2L, 3L, null);
        CachePersistenceHelper.unregisterLocks(m_store, 1L);
        Base.sleep(2000L);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapLocks.size());
        }

    @Test
    public void testIndexLifecycle()
        {
        Binary binExtractor  = ExternalizableHelper.toBinary(new ReflectionExtractor("toString"));
        Binary binComparator = ExternalizableHelper.toBinary(SafeComparator.INSTANCE);

        TestVisitor visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertTrue(visitor.f_mapIndices.isEmpty());

        CachePersistenceHelper.registerIndex(m_store, 1L, binExtractor, binComparator, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(1, visitor.f_mapIndices.size());
        assertEquals(binComparator, visitor.f_mapIndices.get(binExtractor));

        CachePersistenceHelper.unregisterIndex(m_store, 1L, binExtractor, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapIndices.size());

        CachePersistenceHelper.registerIndex(m_store, 1L, binExtractor, binComparator, null);
        CachePersistenceHelper.unregisterIndices(m_store, 1L);
        Base.sleep(2000L);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapIndices.size());
        }

    @Test
    public void testTriggerLifecycle()
        {
        MapTrigger trigger = new FilterTrigger(AlwaysFilter.INSTANCE);
        Binary     binTrigger  = ExternalizableHelper.toBinary(trigger);

        TestVisitor visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertTrue(visitor.f_mapTriggers.isEmpty());

        CachePersistenceHelper.registerTrigger(m_store, 1L, binTrigger, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(1, visitor.f_mapTriggers.size());
        assertEquals(binTrigger, visitor.f_mapTriggers.get(binTrigger));

        CachePersistenceHelper.unregisterTrigger(m_store, 1L, binTrigger, null);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapTriggers.size());

        CachePersistenceHelper.registerTrigger(m_store, 1L, binTrigger, null);
        CachePersistenceHelper.unregisterTriggers(m_store, 1L);
        Base.sleep(2000L);
        visitor = new TestVisitor();
        testPersistenceVisitor(visitor);
        assertEquals(0, visitor.f_mapTriggers.size());
        }

    @Test
    public void testMetadata()
        {
        File fileData  = null;
        File fileTrash = null;

        try
            {
            fileData  = FileHelper.createTempDir();
            fileTrash = FileHelper.createTempDir();

            AbstractPersistenceManager manager = new TestPersistenceManager(fileData, fileTrash, "name");
            Properties            propMetadata = manager.getMetadata();
            manager.writeMetadata(fileData);

            assertTrue(CachePersistenceHelper.isMetadataComplete(propMetadata));
            assertTrue(CachePersistenceHelper.isMetadataCompatible(propMetadata,  0, "MY-FORMAT", 0));
            assertFalse(CachePersistenceHelper.isMetadataCompatible(propMetadata, 0, "MY_FORMAT2", 0));
            assertFalse(CachePersistenceHelper.isMetadataCompatible(propMetadata, 0, "MY-FORMAT", 1));
            assertTrue(CachePersistenceHelper.isMetadataCompatible(propMetadata, 1, "MY-FORMAT", 0));

            propMetadata.setProperty(CachePersistenceHelper.META_STORAGE_FORMAT, "");
            assertFalse(CachePersistenceHelper.isMetadataComplete(propMetadata));
            }
        catch (Exception e)
            {
            fail(e.getMessage());
            }
        finally
            {
            deleteDir(fileData);
            deleteDir(fileTrash);
            }
        }

    protected void deleteDir(File fileDir)
        {
        try
            {
           FileHelper.deleteDir(fileDir);
            }
        catch (IOException ioe)
            {
            // ignore
            }
        }

    // ----- inner class: TestPersistenceManager ----------------------------

    public static class TestPersistenceManager extends AbstractPersistenceManager
        {

        public TestPersistenceManager(File fileData, File fileTrash, String sName)
                 throws IOException
            {
            super(fileData, fileTrash, sName);
            }

        @Override
        protected int getImplVersion()
            {
            return 0;
            }

        @Override
        protected String getStorageFormat()
            {
            return "MY-FORMAT";
            }

        @Override
        protected int getStorageVersion()
            {
            return 0;
            }

        @Override
        protected AbstractPersistentStore instantiatePersistentStore(String sId)
            {
            return null;
            }

        @Override
        protected PersistenceTools instantiatePersistenceTools(OfflinePersistenceInfo offlinePersistenceInfo)
            {
            return null;
            }

        public PersistentStore open(String sId, PersistentStore storeFrom)
            {
            return null;
            }

        public PersistentStore open(String sId, PersistentStore storeFrom, Collector collector)
            {
            return null;
            }

        @Override
        public PersistenceTools getPersistenceTools()
            {
            return null;
            }
        }

    // ----- inner class: TestVisitor ---------------------------------------

    public static class TestVisitor
            implements CachePersistenceHelper.Visitor
        {
        @Override
        public boolean visitCacheEntry(long lOldCacheId, Binary binKey, Binary binValue)
            {
            assertEquals(1L, lOldCacheId);
            f_mapEntries.put(binKey, new SimpleMapEntry(binKey, binValue));
            return true;
            }

        @Override
        public boolean visitListener(long lOldCacheId, Binary binKey, long lListenerId, boolean fLite)
            {
            assertEquals(1L, lOldCacheId);
            f_mapListeners.put(binKey,
                    new Object[] { Long.valueOf(lListenerId), Boolean.valueOf(fLite) });
            return true;
            }

        @Override
        public boolean visitLock(long lOldCacheId, Binary binKey, long lHolderId, long lHolderThreadId)
            {
            assertEquals(1L, lOldCacheId);
            f_mapLocks.put(binKey,
                    new Object[] { Long.valueOf(lHolderId), Long.valueOf(lHolderThreadId) });
            return true;
            }

        @Override
        public boolean visitIndex(long lOldCacheId, Binary binExtractor, Binary binComparator)
            {
            assertEquals(1L, lOldCacheId);
            f_mapIndices.put(binExtractor, binComparator);
            return true;
            }

        @Override
        public boolean visitTrigger(long lOldCacheId, Binary binTrigger)
            {
            assertEquals(1L, lOldCacheId);
            f_mapTriggers.put(binTrigger, binTrigger);
            return true;
            }

        protected final Map<Binary, Map.Entry<Binary, Binary>> f_mapEntries
                = new HashMap<Binary, Map.Entry<Binary, Binary>>();

        protected final Map<Binary, Object[]> f_mapListeners
                = new HashMap<Binary, Object[]>();

        protected final Map<Binary, Object[]> f_mapLocks
                = new HashMap<Binary, Object[]>();

        protected final Map<Binary, Binary> f_mapIndices
                = new HashMap<Binary, Binary>();

        protected final Map<Binary, Binary> f_mapTriggers
                = new HashMap<Binary, Binary>();
        }

    // ----- constants ------------------------------------------------------

    public static final Binary TEST_KEY_1 = ExternalizableHelper.toBinary("test key 1");
    public static final Binary TEST_KEY_2 = ExternalizableHelper.toBinary("test key 2");
    public static final Binary TEST_KEY_3 = ExternalizableHelper.toBinary("test key 3");

    public static final Binary TEST_VALUE_1 = ExternalizableHelper.toBinary("test value 1");
    public static final Binary TEST_VALUE_2 = ExternalizableHelper.toBinary("test value 2");
    public static final Binary TEST_VALUE_3 = ExternalizableHelper.toBinary("test value 3");

    // ----- data members ---------------------------------------------------

    protected File m_file;
    protected PersistenceManager<ReadBuffer> m_manager;
    protected PersistentStore<ReadBuffer> m_store;
    }
