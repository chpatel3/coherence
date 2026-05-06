/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.oracle.coherence.persistence.PersistenceManager;
import com.oracle.coherence.persistence.PersistenceException;
import com.oracle.coherence.persistence.PersistentStore;
import com.oracle.coherence.persistence.PersistentStoreInfo;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;

import com.tangosol.io.journal2.JournalCompactor;
import com.tangosol.io.journal2.PartitionJournalConfig;

import com.tangosol.persistence.AbstractPersistenceEnvironment;
import com.tangosol.persistence.AbstractPersistenceEnvironmentTest;
import com.tangosol.persistence.AbstractPersistenceManager;
import com.tangosol.persistence.AbstractPersistenceManager.AbstractPersistentStore;
import com.tangosol.persistence.CachePersistenceHelper;
import com.tangosol.persistence.GUIDHelper;
import com.tangosol.persistence.GUIDHelperTest;

import com.tangosol.net.Cluster;
import com.tangosol.net.Member;
import com.tangosol.net.PartitionedService;
import com.tangosol.net.ServiceInfo;

import com.tangosol.util.Binary;
import com.tangosol.util.LongArray;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JournalPersistenceEnvironment}.
 *
 * @author rl  2026.03.07
 * @since 26.04
 */
public class JournalPersistenceEnvironmentTest
        extends AbstractPersistenceEnvironmentTest
    {
    // ----- test lifecycle -------------------------------------------------

    @After
    public void cleanupBackupDirectory()
        {
        if (m_fileBackup != null)
            {
            try
                {
                FileHelper.deleteDir(m_fileBackup);
                }
            catch (IOException e)
                {
                // ignore
                }
            }
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractPersistenceEnvironment createPersistenceEnvironment()
            throws IOException
        {
        m_fileBackup = FileHelper.createTempDir();

        JournalPersistenceEnvironment env =
                new JournalPersistenceEnvironment(m_fileActive, m_fileBackup, null, m_fileSnapshot, m_fileTrash);
        env.setDaemonPool(m_pool);
        return env;
        }


    // ----- test methods ---------------------------------------------------

    /**
     * Verify active and backup managers are distinct with distinct directories.
     */
    @Test
    public void testOpenActiveAndBackupDistinctManagers()
        {
        PersistenceManager active = m_env.openActive();
        PersistenceManager backup = m_env.openBackup();

        assertNotNull(active);
        assertNotNull(backup);
        assertNotSame(active, backup);

        assertNotSame(((JournalPersistenceManager) active).getDataDirectory(),
                ((JournalPersistenceManager) backup).getDataDirectory());
        }

    /**
     * Verify snapshot journal files are hard-linked when supported.
     */
    @Test
    public void testSnapshotUsesHardLinks()
        {
        AbstractPersistenceEnvironment env = m_env;
        AbstractPersistenceManager manager = (AbstractPersistenceManager) env.openActive();
        AbstractPersistentStore      store   = (AbstractPersistentStore) manager.open(TEST_STORE_ID, null);

        store.ensureExtent(1L);
        for (int i = 0; i < 8; i++)
            {
            store.store(1L, toKey(0, i), value(64, (byte) (10 + i)), null);
            }

        env.createSnapshot(TEST_SNAPSHOT, manager);

        File       fileActiveStore   = store.getDataDirectory();
        File       fileSnapshotStore = new File(new File(m_fileSnapshot, TEST_SNAPSHOT), TEST_STORE_ID);
        List<File> listActive        = listJournalFiles(fileActiveStore);
        List<File> listSnapshot      = listJournalFiles(fileSnapshotStore);

        assertFalse(listActive.isEmpty());
        assertFalse("snapshot should contain at least one journal file", listSnapshot.isEmpty());

        Map<String, File> mapActive = new HashMap<>();
        for (File file : listActive)
            {
            mapActive.put(file.getName(), file);
            }

        boolean fCheckedAny = false;
        for (File fileSnapshot : listSnapshot)
            {
            File fileActive = mapActive.get(fileSnapshot.getName());
            if (fileActive == null)
                {
                continue;
                }

            Object oLinks;
            try
                {
                oLinks = Files.getAttribute(fileSnapshot.toPath(), "unix:nlink");
                }
            catch (UnsupportedOperationException | IllegalArgumentException e)
                {
                Assume.assumeTrue("filesystem does not support unix:nlink", false);
                return;
                }
            catch (IOException e)
                {
                throw new AssertionError("Unable to read hard link count for " + fileSnapshot, e);
                }

            assertTrue("snapshot journal file should have nlink >= 2: " + fileSnapshot,
                    ((Number) oLinks).intValue() >= 2);
            fCheckedAny = true;
            }

        assertTrue("expected at least one shared journal file between active and snapshot", fCheckedAny);
        }

    /**
     * Verify snapshot data survives compaction of the active store.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testSnapshotSurvivesActiveCompaction()
            throws IOException
        {
        File fileActive   = FileHelper.createTempDir();
        File fileBackup   = FileHelper.createTempDir();
        File fileSnapshot = FileHelper.createTempDir();
        File fileTrash    = FileHelper.createTempDir();

        JournalPersistenceEnvironment env = null;
        try
            {
            PartitionJournalConfig config = new PartitionJournalConfig()
                    .setMaximumFileSize(4096)
                    .setCheckpointBytesThreshold(0);

            env = new JournalPersistenceEnvironment(fileActive, fileBackup, null, fileSnapshot, fileTrash)
                    .setJournalConfig(config);
            env.setDaemonPool(m_pool);

            AbstractPersistenceManager manager = (AbstractPersistenceManager) env.openActive();
            String sStoreId = newStoreId(11);

            JournalPersistenceManager.JournalPersistentStore store =
                    (JournalPersistenceManager.JournalPersistentStore) manager.open(sStoreId, null);
            store.ensureExtent(1L);

            Map<Binary, Binary> mapExpected = new HashMap<>();
            for (int i = 0; i < 20; i++)
                {
                Binary binKey   = toKey(1, i);
                Binary binValue = value(200, (byte) i);
                store.store(1L, binKey, binValue, null);
                mapExpected.put(binKey, binValue);
                }

            List<File> listBeforeSnapshot = listJournalFiles(store.getDataDirectory());
            assertTrue("expected at least two active journal files before snapshot",
                    listBeforeSnapshot.size() >= 2);

            env.createSnapshot(TEST_SNAPSHOT, manager);

            for (Binary binKey : mapExpected.keySet())
                {
                store.erase(1L, binKey, null);
                }

            JournalCompactor compactor = new JournalCompactor(store.getJournal(), store.getTree());
            compactor.compact(store.getCheckpointFileNo(), 1.1);

            List<File> listAfterCompaction = listJournalFiles(store.getDataDirectory());
            assertTrue("active journal file count should not grow after compaction",
                    listAfterCompaction.size() <= listBeforeSnapshot.size());

            AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.openSnapshot(TEST_SNAPSHOT);
            try
                {
                AbstractPersistentStore snapshotStore =
                        (AbstractPersistentStore) snapshot.open(sStoreId, null);
                Map<Binary, Binary> mapActual = collectEntries(snapshotStore);

                assertEquals(mapExpected.size(), mapActual.size());
                assertEquals(mapExpected, mapActual);
                }
            finally
                {
                snapshot.release();
                }
            }
        finally
            {
            if (env != null)
                {
                env.release();
                }
            FileHelper.deleteDir(fileActive);
            FileHelper.deleteDir(fileBackup);
            FileHelper.deleteDir(fileSnapshot);
            FileHelper.deleteDir(fileTrash);
            }
        }

    /**
     * Verify snapshot preserves data across multiple stores.
     */
    @Test
    public void testMultiStoreSnapshotFidelity()
        {
        AbstractPersistenceEnvironment env     = m_env;
        AbstractPersistenceManager     manager = (AbstractPersistenceManager) env.openActive();

        String              sStore0 = newStoreId(1);
        String              sStore1 = newStoreId(2);
        String              sStore2 = newStoreId(3);
        Map<String, Map<Binary, Binary>> mapExpected = new HashMap<>();

        mapExpected.put(sStore0, new HashMap<>());
        mapExpected.put(sStore1, new HashMap<>());
        mapExpected.put(sStore2, new HashMap<>());

        AbstractPersistentStore store0 = (AbstractPersistentStore) manager.open(sStore0, null);
        store0.ensureExtent(1L);
        put(store0, mapExpected.get(sStore0), 1L, new byte[] {0, 1}, new byte[] {10});
        put(store0, mapExpected.get(sStore0), 1L, new byte[] {0, 2}, new byte[] {20});

        AbstractPersistentStore store1 = (AbstractPersistentStore) manager.open(sStore1, null);
        store1.ensureExtent(1L);
        put(store1, mapExpected.get(sStore1), 1L, new byte[] {1, 1}, new byte[] {30});

        AbstractPersistentStore store2 = (AbstractPersistentStore) manager.open(sStore2, null);
        store2.ensureExtent(1L);
        put(store2, mapExpected.get(sStore2), 1L, new byte[] {2, 1}, new byte[] {40});
        put(store2, mapExpected.get(sStore2), 1L, new byte[] {2, 2}, new byte[] {50});
        put(store2, mapExpected.get(sStore2), 1L, new byte[] {2, 3}, new byte[] {60});

        AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.createSnapshot(TEST_SNAPSHOT, manager);
        try
            {
            PersistentStoreInfo[] aInfos = snapshot.listStoreInfo();
            assertEquals(3, aInfos.length);

            Set<String> setActual = new HashSet<>();
            for (PersistentStoreInfo info : aInfos)
                {
                setActual.add(info.getId());
                }
            Set<String> setExpected = new HashSet<>(Arrays.asList(sStore0, sStore1, sStore2));
            assertEquals(setExpected, setActual);

            for (String sStoreId : setActual)
                {
                AbstractPersistentStore store = (AbstractPersistentStore) snapshot.open(sStoreId, null);
                Map<Binary, Binary> mapActual = collectEntries(store);

                assertEquals(mapExpected.get(sStoreId).size(), mapActual.size());
                assertEquals(mapExpected.get(sStoreId), mapActual);
                }
            }
        finally
            {
            snapshot.release();
            }
        }

    /**
     * Verify opening a snapshot recovers the correct point-in-time data.
     */
    @Test
    public void testSnapshotRecoveryProducesCorrectData()
        {
        AbstractPersistenceEnvironment env     = m_env;
        AbstractPersistenceManager     manager = (AbstractPersistenceManager) env.openActive();
        AbstractPersistentStore        store   = (AbstractPersistentStore) manager.open(TEST_STORE_ID, null);

        store.ensureExtent(1L);
        store.ensureExtent(2L);

        Map<Binary, Binary> mapExpected = new HashMap<>();
        Set<Binary>         setNewKeys  = new HashSet<>();

        for (int i = 0; i < 5; i++)
            {
            Binary binKey   = toKey(1, i);
            Binary binValue = value(8, (byte) (11 + i));
            store.store(1L, binKey, binValue, null);
            mapExpected.put(binKey, binValue);
            }

        for (int i = 0; i < 3; i++)
            {
            Binary binKey   = toKey(2, i);
            Binary binValue = value(8, (byte) (21 + i));
            store.store(2L, binKey, binValue, null);
            mapExpected.put(binKey, binValue);
            }

        AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.createSnapshot(TEST_SNAPSHOT, manager);
        snapshot.release();

        snapshot = (AbstractPersistenceManager) env.openSnapshot(TEST_SNAPSHOT);
        try
            {
            AbstractPersistentStore snapshotStore = (AbstractPersistentStore) snapshot.open(TEST_STORE_ID, null);

            assertEquals(mapExpected, collectEntries(snapshotStore));

            Set<Long> setExtents = toExtentSet(snapshotStore.extents());
            assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), setExtents);

            for (int i = 0; i < 2; i++)
                {
                Binary binKey   = toKey(3, i);
                Binary binValue = value(8, (byte) (31 + i));
                store.store(1L, binKey, binValue, null);
                setNewKeys.add(binKey);
                }

            Map<Binary, Binary> mapSnapshot = collectEntries(snapshotStore);
            assertEquals(mapExpected, mapSnapshot);
            for (Binary binKey : setNewKeys)
                {
                assertFalse(mapSnapshot.containsKey(binKey));
                }
            }
        finally
            {
            snapshot.release();
            }
        }

    /**
     * Reproducer for the sparse-snapshot validation gap left open by
     * {@code 9fc966e7}: sparse snapshot recovery can establish sparse context
     * from the snapshot root, and the snapshot-specific helpers accept an
     * empty-present partition store only when that sparse context is passed
     * explicitly.
     * <p>
     * OUTCOME 2026-04-25: commit 0 proved the strict helper path rejected this
     * fixture. Commit 2 moves the same fixture to the intended
     * sparse-context-permissive behavior while preserving the negative case
     * where the sparse root marker is not supplied.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testEmptyPresentSparseSnapshotStoreIsAcceptedBySnapshotHelpers()
            throws IOException
        {
        AbstractPersistenceEnvironment env = m_env;

        AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.createSnapshot(TEST_SNAPSHOT, null);
        snapshot.release();

        assertTrue(CachePersistenceHelper.isLocalSnapshotSparse(env, TEST_SNAPSHOT));

        String sStoreId = newStoreId(17);
        File   fileRoot = new File(m_fileSnapshot, TEST_SNAPSHOT);
        File   fileStore = new File(fileRoot, sStoreId);

        assertTrue("expected empty-present sparse snapshot store directory", fileStore.mkdir());

        snapshot = (AbstractPersistenceManager) env.openSnapshot(TEST_SNAPSHOT);
        try
            {
            PersistentStore<ReadBuffer> store = snapshot.open(sStoreId, null);

            assertTrue(store.isOpen());
            assertArrayEquals(new long[0], store.extents());

            PartitionedService service = mockPartitionedService();

            CachePersistenceHelper.validateForSnapshotRecovery(store, service, true);
            assertTrue(CachePersistenceHelper.getCacheNamesForSnapshotRecovery(store, true).isEmpty());
            CachePersistenceHelper.unsealForSnapshotRecovery(store, true);

            PersistenceException exceptionValidate = assertThrows(PersistenceException.class,
                    () -> CachePersistenceHelper.validateForSnapshotRecovery(store, service, false));
            assertTrue(exceptionValidate.getMessage().contains("missing internal extent"));

            IllegalArgumentException exceptionCacheNames = assertThrows(IllegalArgumentException.class,
                    () -> CachePersistenceHelper.getCacheNamesForSnapshotRecovery(store, false));
            assertTrue(exceptionCacheNames.getMessage().contains("unknown extent"));

            IllegalArgumentException exceptionUnseal = assertThrows(IllegalArgumentException.class,
                    () -> CachePersistenceHelper.unsealForSnapshotRecovery(store, false));
            assertTrue(exceptionUnseal.getMessage().contains("unknown extent"));
            }
        finally
            {
            snapshot.release();
            }
        }

    /**
     * Verify that a sparse journal snapshot produced at {@code 9fc966e7}, the
     * strict-validation baseline before the helper split, remains recoverable
     * after the active-vs-snapshot helper split. This pins the no-format-change
     * promise for sparse snapshots created earlier on this branch.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testSparseSnapshotCreatedAtStrictValidationBaselineRemainsRecoverable()
            throws IOException
        {
        AbstractPersistenceEnvironment env           = m_env;
        String                         sSnapshotName = "compat-9fc966e7";
        File                           fileSnapshot  = new File(m_fileSnapshot, sSnapshotName);

        extractBase64ZipResource(SPARSE_SNAPSHOT_9FC966E7_RESOURCE, fileSnapshot);

        assertTrue(CachePersistenceHelper.isLocalSnapshotSparse(env, sSnapshotName));

        AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.openSnapshot(sSnapshotName);
        try
            {
            PersistentStoreInfo[] aInfo = snapshot.listStoreInfo();
            assertEquals(1, aInfo.length);

            PersistentStore<ReadBuffer> store = snapshot.open(aInfo[0].getId(), null);

            CachePersistenceHelper.validateForSnapshotRecovery(store, mockPartitionedService(), true);

            LongArray<String> laCaches = CachePersistenceHelper.getCacheNamesForSnapshotRecovery(store, true);
            assertEquals("compat-cache", laCaches.get(1L));

            assertEquals(new Binary(new byte[] {42, 43, 44}),
                    store.load(1L, new Binary(new byte[] {9, 17})));
            }
        finally
            {
            snapshot.release();
            }
        }

    /**
     * Verify removing a snapshot cleans up directory state and open handles
     * can still be released safely.
     */
    @Test
    public void testRemoveSnapshotLifecycle()
        {
        AbstractPersistenceEnvironment env     = m_env;
        AbstractPersistenceManager     manager = (AbstractPersistenceManager) env.openActive();
        AbstractPersistentStore        store   = (AbstractPersistentStore) manager.open(TEST_STORE_ID, null);

        store.ensureExtent(1L);
        store.store(1L, toKey(1, 1), value(8, (byte) 44), null);

        AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.createSnapshot(TEST_SNAPSHOT, manager);
        try
            {
            AbstractPersistentStore snapshotStore = (AbstractPersistentStore) snapshot.open(TEST_STORE_ID, null);
            assertEquals(value(8, (byte) 44), snapshotStore.load(1L, toKey(1, 1)));
            }
        finally
            {
            snapshot.release();
            }

        assertEquals(1, env.listSnapshots().length);

        boolean fRemoved = env.removeSnapshot(TEST_SNAPSHOT);
        if (fRemoved)
            {
            assertEquals(0, env.listSnapshots().length);
            assertFalse(new File(m_fileSnapshot, TEST_SNAPSHOT).exists());
            }
        }

    /**
     * Verify an active store can be fully restored from a snapshot by
     * recreating it from the snapshot manager.
     */
    @Test
    public void testRestoreActiveStoreFromSnapshot()
        {
        AbstractPersistenceEnvironment env           = m_env;
        AbstractPersistenceManager     activeManager = (AbstractPersistenceManager) env.openActive();
        AbstractPersistentStore        activeStore   = (AbstractPersistentStore) activeManager.open(TEST_STORE_ID, null);

        activeStore.ensureExtent(1L);

        Map<Binary, Binary> mapExpected = new HashMap<>();
        put(activeStore, mapExpected, 1L, new byte[] {1, 1}, new byte[] {11});
        put(activeStore, mapExpected, 1L, new byte[] {1, 2}, new byte[] {22});

        AbstractPersistenceManager snapshot = (AbstractPersistenceManager) env.createSnapshot(TEST_SNAPSHOT, activeManager);
        try
            {
            activeManager.delete(TEST_STORE_ID, false);
            assertEquals(0, activeManager.listStoreInfo().length);

            AbstractPersistentStore snapshotStore = (AbstractPersistentStore) snapshot.open(TEST_STORE_ID, null);
            AbstractPersistentStore restoredStore = (AbstractPersistentStore) activeManager.open(TEST_STORE_ID, snapshotStore);

            assertEquals(mapExpected, collectEntries(restoredStore));
            }
        finally
            {
            snapshot.release();
            }
        }


    // ----- helpers -------------------------------------------------------

    /**
     * Create a GUID-formatted store id for a partition.
     *
     * @param nPartition  partition number
     *
     * @return store id
     */
    private String newStoreId(int nPartition)
        {
        return GUIDHelper.generateGUID(nPartition, 1L,
                new Date().getTime(), GUIDHelperTest.getMockMember(1));
        }

    /**
     * Create a two-byte key.
     *
     * @param nPrefix  key prefix
     * @param nIndex   key index
     *
     * @return binary key
     */
    private Binary toKey(int nPrefix, int nIndex)
        {
        return new Binary(new byte[] {(byte) nPrefix, (byte) nIndex});
        }

    /**
     * Create a fixed-size binary value.
     *
     * @param cb    value size
     * @param bSeed seed byte
     *
     * @return binary value
     */
    private Binary value(int cb, byte bSeed)
        {
        byte[] ab = new byte[cb];
        Arrays.fill(ab, bSeed);
        return new Binary(ab);
        }

    /**
     * Add an entry to the store and expected map.
     */
    private void put(AbstractPersistentStore store, Map<Binary, Binary> mapExpected,
            long lExtentId, byte[] abKey, byte[] abValue)
        {
        Binary binKey   = new Binary(abKey);
        Binary binValue = new Binary(abValue);

        store.store(lExtentId, binKey, binValue, null);
        mapExpected.put(binKey, binValue);
        }

    /**
     * Collect all store entries.
     *
     * @param store  the store to iterate
     *
     * @return key-to-value map
     */
    private Map<Binary, Binary> collectEntries(PersistentStore<ReadBuffer> store)
        {
        Map<Binary, Binary> map = new HashMap<>();
        store.iterate(new PersistentStore.Visitor<ReadBuffer>()
            {
            @Override
            public boolean visit(long lExtentId, ReadBuffer bufKey, ReadBuffer bufValue)
                {
                map.put(bufKey.toBinary(), bufValue.toBinary());
                return true;
                }
            });
        return map;
        }

    /**
     * Convert extent ids to a set.
     *
     * @param alExtentIds  extent ids
     *
     * @return extent id set
     */
    private Set<Long> toExtentSet(long[] alExtentIds)
        {
        Set<Long> set = new HashSet<>();
        for (long lExtentId : alExtentIds)
            {
            set.add(lExtentId);
            }
        return set;
        }

    /**
     * Create a mock partitioned service with the partition count expected by
     * the persistence helper metadata validation.
     *
     * @return mocked service
     */
    private PartitionedService mockPartitionedService()
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

    /**
     * Extract a base64-encoded zip resource into a directory.
     *
     * @param sResource   resource name
     * @param fileTarget  target directory
     *
     * @throws IOException on extraction failure
     */
    private void extractBase64ZipResource(String sResource, File fileTarget)
            throws IOException
        {
        byte[] abZip;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(sResource))
            {
            assertNotNull("Missing resource " + sResource, in);
            abZip = Base64.getMimeDecoder().decode(in.readAllBytes());
            }

        Path pathTarget = fileTarget.toPath().toAbsolutePath().normalize();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(abZip)))
            {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                {
                Path path = pathTarget.resolve(entry.getName()).normalize();
                if (!path.startsWith(pathTarget))
                    {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                    }

                if (entry.isDirectory())
                    {
                    Files.createDirectories(path);
                    }
                else
                    {
                    Files.createDirectories(path.getParent());
                    Files.copy(zip, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                zip.closeEntry();
                }
            }
        }

    /**
     * List journal files in a store directory.
     *
     * @param fileDir  store directory
     *
     * @return journal files
     */
    private List<File> listJournalFiles(File fileDir)
        {
        List<File> list = new ArrayList<>();
        File[] aFiles = fileDir.listFiles();
        if (aFiles != null)
            {
            for (File file : aFiles)
                {
                if (file.isDirectory())
                    {
                    list.addAll(listJournalFiles(file));
                    }
                else if (file.getName().matches("journal-\\d{6}\\.coh"))
                    {
                    list.add(file);
                    }
                }
            }
        list.sort((f1, f2) -> f1.getAbsolutePath().compareTo(f2.getAbsolutePath()));
        return list;
        }


    // ----- data members ---------------------------------------------------

    /**
     * Backup directory.
     */
    private File m_fileBackup;

    /**
     * Sparse snapshot fixture generated in a detached worktree at 9fc966e7.
     */
    private static final String SPARSE_SNAPSHOT_9FC966E7_RESOURCE =
            "persistence/journal/sparse-snapshot-9fc966e7.zip.base64";
    }
