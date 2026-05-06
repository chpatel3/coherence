/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.FileHelper;

import com.tangosol.util.Binary;
import com.tangosol.util.BinaryRadixTree;

import java.io.File;
import java.io.IOException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JournalCompactor}.
 *
 * @author rl  2026.03.07
 * @since 26.04
 */
public class JournalCompactorTest
    {
    @Before
    public void setup()
            throws IOException
        {
        m_fileDir = FileHelper.createTempDir();
        }

    @After
    public void cleanup()
        {
        if (m_journal != null)
            {
            m_journal.close();
            }

        if (m_fileDir != null)
            {
            FileHelper.deleteDirSilent(m_fileDir);
            }
        }

    @Test
    public void testCompactReclaimsSpaceAfterErase()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binVal1 = value(10, 20);
        Binary binVal2 = value(30, 20);

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binVal1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binVal2);

        tree.put(binKey1, lTicket1);
        tree.put(binKey2, lTicket2);

        int nFileToCompact = PersistentTicket.extractFileId(lTicket1);

        tree.remove(binKey1);
        journal.release(lTicket1);

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(Integer.MAX_VALUE, 0.99d);

        assertEquals(1, cCompacted);
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nFileToCompact).exists());

        long lTicketLive = tree.get(binKey2);
        assertNotEquals(0L, lTicketLive);
        assertEquals(binVal2, journal.read(lTicketLive).toBinary());
        }

    @Test
    public void testCompactEvacuatesOverwrittenEntries()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        Binary binKey      = new Binary(new byte[] {1});
        Binary binFillKey  = new Binary(new byte[] {9});
        Binary binValueOld = value(10, 20);
        Binary binValueNew = value(20, 20);
        Binary binFillVal  = value(30, 20);

        long lTicketOld = journal.append(JournalEntry.TYPE_STORE, binKey, binValueOld);
        long lTicketFill = journal.append(JournalEntry.TYPE_STORE, binFillKey, binFillVal);

        tree.put(binKey, lTicketOld);
        tree.put(binFillKey, lTicketFill);

        int nOldFileId = PersistentTicket.extractFileId(lTicketOld);

        long lTicketNew = journal.append(JournalEntry.TYPE_STORE, binKey, binValueNew);
        tree.put(binKey, lTicketNew);
        journal.release(lTicketOld);

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(Integer.MAX_VALUE, 0.99d);

        assertEquals(1, cCompacted);
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nOldFileId).exists());
        assertEquals(binValueNew, journal.read(tree.get(binKey)).toBinary());
        }

    @Test
    public void testTombstoneLifecycle()
            throws Exception
        {
        LifecycleResult resultPreserve = runTombstoneLifecycleScenario(JournalEntry.TYPE_ERASE, 0);
        assertEquals(1, resultPreserve.getCompacted());
        assertEquals(2, resultPreserve.getTombstoneCount());

        LifecycleResult resultDrop = runTombstoneLifecycleScenario(JournalEntry.TYPE_ERASE, 1);
        assertEquals(1, resultDrop.getCompacted());
        assertEquals(0, resultDrop.getTombstoneCount());
        }

    @Test
    public void testEraseExtentTombstoneLifecycle()
            throws Exception
        {
        LifecycleResult resultPreserve = runTombstoneLifecycleScenario(JournalEntry.TYPE_ERASE_EXTENT, 0);
        assertEquals(1, resultPreserve.getCompacted());
        assertEquals(2, resultPreserve.getTombstoneCount());

        LifecycleResult resultDrop = runTombstoneLifecycleScenario(JournalEntry.TYPE_ERASE_EXTENT, 1);
        assertEquals(1, resultDrop.getCompacted());
        assertEquals(0, resultDrop.getTombstoneCount());
        }

    @Test
    public void testCompactMultipleFiles()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        Binary binVal = value(50, 20);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binKey3 = new Binary(new byte[] {3});
        Binary binKey4 = new Binary(new byte[] {4});
        Binary binKey5 = new Binary(new byte[] {5});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binVal);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binVal);
        long lTicket3 = journal.append(JournalEntry.TYPE_STORE, binKey3, binVal);
        long lTicket4 = journal.append(JournalEntry.TYPE_STORE, binKey4, binVal);
        long lTicket5 = journal.append(JournalEntry.TYPE_STORE, binKey5, binVal);

        tree.put(binKey1, lTicket1);
        tree.put(binKey2, lTicket2);
        tree.put(binKey3, lTicket3);
        tree.put(binKey4, lTicket4);
        tree.put(binKey5, lTicket5);

        int nFile1 = PersistentTicket.extractFileId(lTicket1);
        int nFile2 = PersistentTicket.extractFileId(lTicket3);

        tree.remove(binKey1);
        journal.release(lTicket1);
        tree.remove(binKey3);
        journal.release(lTicket3);

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(Integer.MAX_VALUE, 0.99d);

        assertEquals(2, cCompacted);
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nFile1).exists());
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nFile2).exists());

        assertEquals(binVal, journal.read(tree.get(binKey2)).toBinary());
        assertEquals(binVal, journal.read(tree.get(binKey4)).toBinary());
        assertEquals(binVal, journal.read(tree.get(binKey5)).toBinary());
        }

    @Test
    public void testCompactHonorsMaxFilesPerPass()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        Binary binVal = value(40, 20);

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {1}), binVal);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {2}), binVal);
        long lTicket3 = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {3}), binVal);
        long lTicket4 = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {4}), binVal);

        tree.put(new Binary(new byte[] {1}), lTicket1);
        tree.put(new Binary(new byte[] {2}), lTicket2);
        tree.put(new Binary(new byte[] {3}), lTicket3);
        tree.put(new Binary(new byte[] {4}), lTicket4);

        int nFile1 = PersistentTicket.extractFileId(lTicket1);
        int nFile2 = PersistentTicket.extractFileId(lTicket3);

        tree.remove(new Binary(new byte[] {1}));
        journal.release(lTicket1);
        tree.remove(new Binary(new byte[] {3}));
        journal.release(lTicket3);

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(Integer.MAX_VALUE, 0.99d, 1);

        assertEquals(1, cCompacted);
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nFile1).exists()
                && JournalUtils.toJournalFile(m_fileDir, nFile2).exists());
        assertTrue(JournalUtils.toJournalFile(m_fileDir, nFile1).exists()
                || JournalUtils.toJournalFile(m_fileDir, nFile2).exists());
        }

    @Test
    public void testConcurrentCompactionAndWrites()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binVal1 = value(10, 20);
        Binary binVal2 = value(20, 20);

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binVal1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binVal2);

        tree.put(binKey1, lTicket1);
        tree.put(binKey2, lTicket2);

        int nOldFile = PersistentTicket.extractFileId(lTicket1);

        tree.remove(binKey1);
        journal.release(lTicket1);

        AtomicBoolean            fRun     = new AtomicBoolean(true);
        AtomicReference<Throwable> refError = new AtomicReference<>();

        Thread threadWriter = new Thread(() ->
            {
            for (int i = 0; i < 100 && fRun.get(); i++)
                {
                try
                    {
                    Binary binKey = new Binary(new byte[] {(byte) 0x7F, (byte) i});
                    Binary binVal = value(i, 20);
                    long   lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binVal);
                    tree.put(binKey, lTicket);
                    }
                catch (Throwable t)
                    {
                    refError.compareAndSet(null, t);
                    break;
                    }
                }
            });

        threadWriter.start();

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(Integer.MAX_VALUE, 0.99d);

        fRun.set(false);
        threadWriter.join(5000L);

        assertEquals(1, cCompacted);
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nOldFile).exists());
        assertNull(refError.get());

        AtomicReference<Throwable> refReadError = new AtomicReference<>();
        tree.visitAll(entry ->
            {
            if (refReadError.get() != null)
                {
                return;
                }

            try
                {
                assertNotNull(journal.read(entry.getValue()));
                }
            catch (Throwable t)
                {
                refReadError.compareAndSet(null, t);
                }
            });

        assertNull(refReadError.get());
        }

    @Test
    public void testCompactNoCandidate()
            throws Exception
        {
        PartitionJournal journal = newJournal(128);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binVal  = value(80, 20);

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binVal);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binVal);

        tree.put(binKey1, lTicket1);
        tree.put(binKey2, lTicket2);

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(Integer.MAX_VALUE, 0.1d);

        assertEquals(0, cCompacted);
        }

    @Test
    public void testCompactJournalWithOnlyTombstones()
            throws Exception
        {
        PartitionJournal journal = newJournal(80);
        BinaryRadixTree  tree    = new BinaryRadixTree();

        journal.append(JournalEntry.TYPE_ERASE, new Binary(new byte[] {1}), null);
        int nFileId = journal.getCurrentFileId();
        journal.forceRotate();

        JournalCompactor compactor = new JournalCompactor(journal, tree);
        int              cCompacted = compactor.compact(nFileId + 1, 1.1d);

        assertEquals(1, cCompacted);
        assertFalse(JournalUtils.toJournalFile(m_fileDir, nFileId).exists());
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Create and open a partition journal.
     *
     * @param cbMaxFile  maximum file size
     *
     * @return opened partition journal
     *
     * @throws Exception on error
     */
    private PartitionJournal newJournal(long cbMaxFile)
            throws Exception
        {
        PartitionJournalConfig config = new PartitionJournalConfig().setMaximumFileSize(cbMaxFile);

        m_journal = new PartitionJournal(m_fileDir, config);
        m_journal.open();
        return m_journal;
        }

    /**
     * Create deterministic binary value bytes.
     *
     * @param nSeed  seed value
     * @param cb     value length
     *
     * @return value bytes
     */
    private static Binary value(int nSeed, int cb)
        {
        byte[] ab = new byte[cb];
        for (int i = 0; i < cb; i++)
            {
            ab[i] = (byte) (nSeed + i);
            }
        return new Binary(ab);
        }

    /**
     * Count valid entries of a specific type across all journal files in a directory.
     *
     * @param dirPartition  partition directory
     * @param bType         entry type to count
     *
     * @return matching entry count
     *
     * @throws IOException on I/O failure
     */
    private static int countEntriesByType(File dirPartition, byte bType)
            throws IOException
        {
        int cEntries = 0;

        for (Integer nFileId : JournalUtils.discoverFileIds(dirPartition))
            {
            int[] acFile = {0};
            JournalUtils.scanFile(JournalUtils.toJournalFile(dirPartition, nFileId), nFileId, 0L,
                    (nScannedFileId, ofEntry, entry) ->
                        {
                        if (entry.isValid() && entry.getType() == bType)
                            {
                            acFile[0]++;
                            }
                        return true;
                        });
            cEntries += acFile[0];
            }

        return cEntries;
        }

    /**
     * Run one tombstone lifecycle scenario and return resulting counts.
     *
     * @param bTombstoneType      tombstone type (ERASE or ERASE_EXTENT)
     * @param nCheckpointDelta    delta added to compacted file id for checkpoint argument
     *
     * @return lifecycle result
     *
     * @throws Exception on error
     */
    private static LifecycleResult runTombstoneLifecycleScenario(byte bTombstoneType, int nCheckpointDelta)
            throws Exception
        {
        File fileDir = FileHelper.createTempDir();

        try
            {
            long cbMaxFile = bTombstoneType == JournalEntry.TYPE_ERASE ? 112 : 80;
            PartitionJournalConfig config = new PartitionJournalConfig().setMaximumFileSize(cbMaxFile);
            PartitionJournal       journal = new PartitionJournal(fileDir, config);
            journal.open();

            try
                {
                BinaryRadixTree tree = new BinaryRadixTree();

                Binary binKey = new Binary(new byte[] {1});
                Binary binVal = value(90, 20);

                long lStoreTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binVal);
                tree.put(binKey, lStoreTicket);

                if (bTombstoneType == JournalEntry.TYPE_ERASE)
                    {
                    journal.append(JournalEntry.TYPE_ERASE, binKey, null);
                    journal.append(JournalEntry.TYPE_ERASE, new Binary(new byte[] {2}), null);
                    }
                else if (bTombstoneType == JournalEntry.TYPE_ERASE_EXTENT)
                    {
                    journal.append(JournalEntry.TYPE_ERASE_EXTENT, null, null);
                    journal.append(JournalEntry.TYPE_ERASE_EXTENT, null, null);
                    }
                else
                    {
                    throw new IllegalArgumentException("unsupported tombstone type: " + bTombstoneType);
                    }

                tree.remove(binKey);
                journal.release(lStoreTicket);

                int nFileId = PersistentTicket.extractFileId(lStoreTicket);

                // Compact only operates on FULL files, so rotate after
                // preparing the tombstone scenario to make the source file a
                // stable compaction candidate independent of entry sizing.
                journal.forceRotate();

                JournalCompactor compactor = new JournalCompactor(journal, tree);
                int              cCompacted = compactor.compact(nFileId + nCheckpointDelta, 0.99d);

                int cTombstones = countEntriesByType(fileDir, bTombstoneType);
                return new LifecycleResult(cCompacted, cTombstones);
                }
            finally
                {
                journal.close();
                }
            }
        finally
            {
            FileHelper.deleteDirSilent(fileDir);
            }
        }


    // ----- inner class: LifecycleResult ----------------------------------

    /**
     * Tombstone lifecycle scenario result.
     */
    private static class LifecycleResult
        {
        /**
         * Construct result.
         *
         * @param cCompacted       compacted file count
         * @param cTombstoneCount  tombstone count
         */
        private LifecycleResult(int cCompacted, int cTombstoneCount)
            {
            m_cCompacted      = cCompacted;
            m_cTombstoneCount = cTombstoneCount;
            }

        /**
         * Return compacted file count.
         *
         * @return compacted file count
         */
        private int getCompacted()
            {
            return m_cCompacted;
            }

        /**
         * Return tombstone count.
         *
         * @return tombstone count
         */
        private int getTombstoneCount()
            {
            return m_cTombstoneCount;
            }

        /**
         * Compacted file count.
         */
        private final int m_cCompacted;

        /**
         * Tombstone count.
         */
        private final int m_cTombstoneCount;
        }


    // ----- data members ---------------------------------------------------

    /**
     * Partition temp directory.
     */
    private File m_fileDir;

    /**
     * Journal under test.
     */
    private PartitionJournal m_journal;
    }
