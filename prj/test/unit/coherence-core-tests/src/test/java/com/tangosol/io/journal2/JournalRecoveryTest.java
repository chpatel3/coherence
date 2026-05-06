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
import java.io.RandomAccessFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JournalRecovery}.
 *
 * @author rl  2026.03.06
 * @since 26.04
 */
public class JournalRecoveryTest
    {
    private static final long TEST_EXTENT = 100L;

    @Before
    public void setup()
            throws IOException
        {
        m_fileDir = FileHelper.createTempDir();
        m_listJournals = new ArrayList<>();
        }

    @After
    public void cleanup()
        {
        if (m_listJournals != null)
            {
            for (PartitionJournal journal : m_listJournals)
                {
                if (journal != null)
                    {
                    journal.close();
                    }
                }
            }

        if (m_fileDir != null)
            {
            FileHelper.deleteDirSilent(m_fileDir);
            }
        }

    @Test
    public void testRecoverStoreAndErase()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binVal1 = new Binary(new byte[] {10});
        Binary binVal2 = new Binary(new byte[] {20, 21});

        journal.append(JournalEntry.TYPE_STORE, binKey1, binVal1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binVal2);
        journal.append(JournalEntry.TYPE_ERASE, binKey1, null);

        journal.close();

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertEquals(0L, tree.get(binKey1));
        assertNotEquals(0L, tree.get(binKey2));
        assertEquals(binVal2, journalRecover.read(tree.get(binKey2)).toBinary());

        assertEquals(3, result.getEntriesRecovered());
        assertTrue(result.getEntriesSkipped() >= 0);
        assertEquals(1, result.getExtentToKeys().get(TEST_EXTENT).size());
        assertTrue(result.getExtentToKeys().get(TEST_EXTENT).contains(binKey2));

        assertEquals(lTicket2, tree.get(binKey2));
        }

    @Test
    public void testRecoverFromCheckpointAndReplayTail()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binKey3 = new Binary(new byte[] {3});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {11}));
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {22}));

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey1, lTicket1);
        treeCheckpoint.put(binKey2, lTicket2);

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.append(JournalEntry.TYPE_ERASE, binKey1, null);
        journal.append(JournalEntry.TYPE_STORE, binKey3, new Binary(new byte[] {33, 34}));

        journal.close();

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertEquals(0L, tree.get(binKey1));
        assertNotEquals(0L, tree.get(binKey2));
        assertNotEquals(0L, tree.get(binKey3));

        assertEquals(4, result.getEntriesRecovered());
        assertFalse(result.getExtentToKeys().containsKey(999L));
        assertTrue(result.getExtentToKeys().get(TEST_EXTENT).contains(binKey2));
        assertTrue(result.getExtentToKeys().get(TEST_EXTENT).contains(binKey3));
        assertTrue(result.getTimings().hasCheckpoint());
        assertEquals(2, result.getTimings().getAppliedEntryCount());
        }

    @Test
    public void testIterateRecoveredEntriesWithoutCheckpoint()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1   = new Binary(new byte[] {1});
        Binary binKey2   = new Binary(new byte[] {2});
        Binary binKey3   = new Binary(new byte[] {3});
        Binary binValue1 = new Binary(new byte[] {11});
        Binary binValue2 = new Binary(new byte[] {22});
        Binary binValue3 = new Binary(new byte[] {33});

        journal.append(JournalEntry.TYPE_STORE, binKey1, binValue1);
        journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2);
        journal.append(JournalEntry.TYPE_STORE, binKey1, binValue3);
        journal.append(JournalEntry.TYPE_ERASE, binKey2, null);

        journal.close();

        Map<Binary, Binary> mapRecovered = new HashMap<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            mapRecovered.put(binKey, binValue);
            return true;
            });

        assertEquals(1, mapRecovered.size());
        assertEquals(binValue3, mapRecovered.get(binKey1));
        assertFalse(mapRecovered.containsKey(binKey2));
        assertFalse(mapRecovered.containsKey(binKey3));
        }

    @Test
    public void testIterateRecoveredEntriesFromCheckpointAndReplayTail()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1      = new Binary(new byte[] {1});
        Binary binKey2      = new Binary(new byte[] {2});
        Binary binKey3      = new Binary(new byte[] {3});
        Binary binValue1    = new Binary(new byte[] {11});
        Binary binValue2    = new Binary(new byte[] {22});
        Binary binValue2New = new Binary(new byte[] {23});
        Binary binValue3    = new Binary(new byte[] {33});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binValue1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2);

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey1, lTicket1);
        treeCheckpoint.put(binKey2, lTicket2);

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2New);
        journal.append(JournalEntry.TYPE_STORE, binKey3, binValue3);

        journal.close();

        Map<Binary, Binary> mapRecovered = new HashMap<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            mapRecovered.put(binKey, binValue);
            return true;
            });

        assertEquals(3, mapRecovered.size());
        assertEquals(binValue1, mapRecovered.get(binKey1));
        assertEquals(binValue2New, mapRecovered.get(binKey2));
        assertEquals(binValue3, mapRecovered.get(binKey3));
        }

    @Test
    public void testIterateRecoveredEntriesFromCheckpointWithoutTail()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1   = new Binary(new byte[] {1});
        Binary binKey2   = new Binary(new byte[] {2});
        Binary binValue1 = new Binary(new byte[] {11});
        Binary binValue2 = new Binary(new byte[] {22});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binValue1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2);

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey1, lTicket1);
        treeCheckpoint.put(binKey2, lTicket2);

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.close();

        Map<Binary, Binary> mapRecovered = new HashMap<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            mapRecovered.put(binKey, binValue);
            return true;
            });

        assertEquals(2, mapRecovered.size());
        assertEquals(binValue1, mapRecovered.get(binKey1));
        assertEquals(binValue2, mapRecovered.get(binKey2));
        }

    @Test
    public void testIterateRecoveredEntriesFromCheckpointWithoutTailAcrossMultipleFiles()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        Map<Binary, Binary> mapExpected = new HashMap<>();
        Map<Binary, Long>   mapTickets  = new HashMap<>();

        for (int i = 0; i < 6; i++)
            {
            Binary binKey   = new Binary(new byte[] {(byte) i});
            Binary binValue = new Binary(new byte[] {(byte) (11 + i), (byte) (21 + i), (byte) (31 + i)});
            long   lTicket  = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

            mapExpected.put(binKey, binValue);
            mapTickets.put(binKey, lTicket);
            }

        assertTrue(JournalUtils.discoverFileIds(m_fileDir).size() > 1);

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        for (Map.Entry<Binary, Long> entry : mapTickets.entrySet())
            {
            treeCheckpoint.put(entry.getKey(), entry.getValue().longValue());
            }

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.close();

        Map<Binary, Binary> mapRecovered = new HashMap<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            mapRecovered.put(binKey, binValue);
            return true;
            });

        assertEquals(mapExpected, mapRecovered);
        }

    @Test
    public void testIterateRecoveredEntriesFallsBackWhenCheckpointIsCorrupted()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1      = new Binary(new byte[] {1});
        Binary binKey2      = new Binary(new byte[] {2});
        Binary binValue1    = new Binary(new byte[] {11});
        Binary binValue2    = new Binary(new byte[] {22});
        Binary binValue2New = new Binary(new byte[] {23});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binValue1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2);

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey1, lTicket1);
        treeCheckpoint.put(binKey2, lTicket2);

        File fileCheckpoint = new File(m_fileDir, "checkpoint.coh");
        CheckpointFile.write(fileCheckpoint, journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2New);
        journal.close();

        try (RandomAccessFile raf = new RandomAccessFile(fileCheckpoint, "rw"))
            {
            raf.setLength(Math.max(1L, raf.length() / 2L));
            }

        Map<Binary, Binary> mapRecovered = new HashMap<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            mapRecovered.put(binKey, binValue);
            return true;
            });

        assertEquals(2, mapRecovered.size());
        assertEquals(binValue1, mapRecovered.get(binKey1));
        assertEquals(binValue2New, mapRecovered.get(binKey2));
        }

    @Test
    public void testIterateRecoveredEntriesSupportsEarlyTermination()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});

        journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {11}));
        journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {22}));
        journal.close();

        List<Binary> listVisited = new ArrayList<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            listVisited.add(binKey);
            return false;
            });

        assertEquals(1, listVisited.size());
        assertTrue(listVisited.contains(binKey1) || listVisited.contains(binKey2));
        }

    @Test
    public void testIterateRecoveredCheckpointBackedEntriesSupportsEarlyTermination()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {11}));
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {22}));

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey1, lTicket1);
        treeCheckpoint.put(binKey2, lTicket2);

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.close();

        List<Binary> listVisited = new ArrayList<>();
        JournalRecovery.iterateRecoveredEntries(m_fileDir, TEST_EXTENT, (binKey, binValue) ->
            {
            listVisited.add(binKey);
            return false;
            });

        assertEquals(1, listVisited.size());
        assertTrue(listVisited.contains(binKey1) || listVisited.contains(binKey2));
        }

    @Test
    public void testRecoverFromCheckpointRestoresTicketSizesWithoutHistoricalScan()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey = new Binary(new byte[] {4});
        long   lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, new Binary(new byte[] {4, 4, 4}));

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey, lTicket);

        int nFileId = journal.getCurrentFileId();

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                nFileId, journal.getCurrentOffset(), treeCheckpoint);

        journal.close();

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertEquals(0, result.getTimings().getAppliedEntryCount());
        assertEquals(0L, result.getTimings().getJournalReplayNanos());

        PartitionJournal.JournalFileInfo infoBefore = journalRecover.getFileInfo(nFileId);
        assertEquals(infoBefore.getSize(), infoBefore.getLiveBytes());

        journalRecover.release(tree.get(binKey));

        PartitionJournal.JournalFileInfo infoAfter = journalRecover.getFileInfo(nFileId);
        assertEquals(0L, infoAfter.getLiveBytes());
        }

    @Test
    public void testRecoverEraseExtent()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binKey3 = new Binary(new byte[] {3});

        journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {1}));
        journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {2}));
        journal.append(JournalEntry.TYPE_ERASE_EXTENT, null, null);
        journal.append(JournalEntry.TYPE_STORE, binKey3, new Binary(new byte[] {3}));

        journal.close();

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertEquals(0L, tree.get(binKey1));
        assertEquals(0L, tree.get(binKey2));
        assertNotEquals(0L, tree.get(binKey3));

        assertEquals(1, result.getExtentToKeys().get(TEST_EXTENT).size());
        assertTrue(result.getExtentToKeys().get(TEST_EXTENT).contains(binKey3));
        }

    @Test
    public void testRecoverTruncatedTailEntry()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});

        journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {1, 1}));
        journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {2, 2}));

        int nFileId = journal.getCurrentFileId();
        journal.close();

        File fileJournal = new File(m_fileDir, String.format("journal-%06d.coh", nFileId));
        try (RandomAccessFile raf = new RandomAccessFile(fileJournal, "rw"))
            {
            raf.setLength(Math.max(0L, raf.length() - 8L));
            }

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertNotEquals(0L, tree.get(binKey1));
        assertEquals(0L, tree.get(binKey2));
        assertTrue(result.getEntriesSkipped() >= 1);
        }

    @Test
    public void testRecoverSkipsCorruptedEntryByCrc()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});

        journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {9, 9}));
        journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {8, 8}));

        int nFileId = journal.getCurrentFileId();
        journal.close();

        File fileJournal = new File(m_fileDir, String.format("journal-%06d.coh", nFileId));
        try (RandomAccessFile raf = new RandomAccessFile(fileJournal, "rw"))
            {
            raf.seek(JournalEntry.OFFSET_TYPE + 2L);
            raf.writeByte(raf.readByte() ^ 0x01);
            }

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertEquals(0L, tree.get(binKey1));
        assertNotEquals(0L, tree.get(binKey2));
        assertTrue(result.getEntriesSkipped() >= 1);
        }

    @Test
    public void testRecoveryRecomputesLiveBytes()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey = new Binary(new byte[] {1});
        journal.append(JournalEntry.TYPE_STORE, binKey, new Binary(new byte[] {1, 2}));
        journal.append(JournalEntry.TYPE_STORE, binKey, new Binary(new byte[] {3, 4, 5}));

        int nFileId = journal.getCurrentFileId();
        journal.close();

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        PartitionJournal.JournalFileInfo info = journalRecover.getFileInfo(nFileId);
        assertTrue(info.getLiveBytes() > 0L);
        assertTrue(info.getLiveBytes() < info.getSize());
        assertEquals(new Binary(new byte[] {3, 4, 5}), journalRecover.read(tree.get(binKey)).toBinary());
        }

    @Test
    public void testRecoverLargeValue()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey = new Binary(new byte[] {1});
        byte[] abValue = new byte[32 * 1024];
        for (int i = 0; i < abValue.length; i++)
            {
            abValue[i] = (byte) (i & 0xFF);
            }
        Binary binValue = new Binary(abValue);

        journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

        journal.close();

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertNotEquals(0L, tree.get(binKey));
        assertEquals(binValue, journalRecover.read(tree.get(binKey)).toBinary());
        assertEquals(1, result.getEntriesRecovered());
        }

    @Test
    public void testRecoverAcrossMultipleJournalFiles()
            throws Exception
        {
        PartitionJournal journal = newJournal(80);
        List<Binary>     listKeys = new ArrayList<>();
        List<Binary>     listValues = new ArrayList<>();

        for (int i = 0; i < 5; i++)
            {
            Binary binKey   = new Binary(new byte[] {(byte) i});
            Binary binValue = new Binary(new byte[] {(byte) (10 + i), (byte) (20 + i), (byte) (30 + i)});

            journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
            listKeys.add(binKey);
            listValues.add(binValue);
            }

        assertTrue(JournalUtils.discoverFileIds(m_fileDir).size() > 1);
        journal.close();

        PartitionJournal journalRecover = reopenJournal(80);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertTrue(result.getTimings().getJournalFileCount() > 1);
        assertEquals(listKeys.size(), result.getExtentToKeys().get(TEST_EXTENT).size());

        for (int i = 0; i < listKeys.size(); i++)
            {
            assertEquals(listValues.get(i), journalRecover.read(tree.get(listKeys.get(i))).toBinary());
            }
        }

    @Test
    public void testRecoverMultipleExtentDirectoriesIndependently()
            throws Exception
        {
        File dirExtentOne = new File(m_fileDir, "extent-1");
        File dirExtentTwo = new File(m_fileDir, "extent-2");

        Binary binKey  = new Binary(new byte[] {9});
        Binary binVal1 = new Binary(new byte[] {11});
        Binary binVal2 = new Binary(new byte[] {22});

        PartitionJournal journalOne = newJournal(dirExtentOne, 1024 * 1024);
        PartitionJournal journalTwo = newJournal(dirExtentTwo, 1024 * 1024);

        journalOne.append(JournalEntry.TYPE_STORE, binKey, binVal1);
        journalTwo.append(JournalEntry.TYPE_STORE, binKey, binVal2);

        journalOne.close();
        journalTwo.close();

        PartitionJournal recoverOne = reopenJournal(dirExtentOne, 1024 * 1024);
        PartitionJournal recoverTwo = reopenJournal(dirExtentTwo, 1024 * 1024);

        BinaryRadixTree treeOne = new BinaryRadixTree();
        BinaryRadixTree treeTwo = new BinaryRadixTree();

        JournalRecovery.RecoveryResult resultOne = JournalRecovery.recover(dirExtentOne, recoverOne, treeOne, 101L);
        JournalRecovery.RecoveryResult resultTwo = JournalRecovery.recover(dirExtentTwo, recoverTwo, treeTwo, 202L);

        assertEquals(1, resultOne.getExtentToKeys().get(101L).size());
        assertEquals(1, resultTwo.getExtentToKeys().get(202L).size());
        assertEquals(binVal1, recoverOne.read(treeOne.get(binKey)).toBinary());
        assertEquals(binVal2, recoverTwo.read(treeTwo.get(binKey)).toBinary());
        }

    @Test
    public void testRecoverFromCheckpointWithCorruptedTail()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binKey3 = new Binary(new byte[] {3});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, new Binary(new byte[] {11}));

        BinaryRadixTree treeCheckpoint = new BinaryRadixTree();
        treeCheckpoint.put(binKey1, lTicket1);

        CheckpointFile.write(new File(m_fileDir, "checkpoint.coh"),
                journal.getCurrentFileId(), journal.getCurrentOffset(), treeCheckpoint);

        journal.append(JournalEntry.TYPE_STORE, binKey2, new Binary(new byte[] {22}));
        journal.append(JournalEntry.TYPE_STORE, binKey3, new Binary(new byte[] {33}));

        int nFileId = journal.getCurrentFileId();
        journal.close();

        File fileJournal = new File(m_fileDir, String.format("journal-%06d.coh", nFileId));
        try (RandomAccessFile raf = new RandomAccessFile(fileJournal, "rw"))
            {
            raf.setLength(Math.max(0L, raf.length() - 8L));
            }

        PartitionJournal journalRecover = reopenJournal(1024 * 1024);
        BinaryRadixTree  tree           = new BinaryRadixTree();

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(m_fileDir, journalRecover, tree, TEST_EXTENT);

        assertTrue(result.getTimings().hasCheckpoint());
        assertEquals(1, result.getTimings().getAppliedEntryCount());
        assertTrue(result.getEntriesSkipped() >= 1);
        assertNotEquals(0L, tree.get(binKey1));
        assertNotEquals(0L, tree.get(binKey2));
        assertEquals(0L, tree.get(binKey3));
        assertEquals(new Binary(new byte[] {11}), journalRecover.read(tree.get(binKey1)).toBinary());
        assertEquals(new Binary(new byte[] {22}), journalRecover.read(tree.get(binKey2)).toBinary());
        }

    private PartitionJournal newJournal(long cbMaxFile)
            throws Exception
        {
        return newJournal(m_fileDir, cbMaxFile);
        }

    private PartitionJournal reopenJournal(long cbMaxFile)
            throws Exception
        {
        return reopenJournal(m_fileDir, cbMaxFile);
        }

    private PartitionJournal newJournal(File fileDir, long cbMaxFile)
            throws Exception
        {
        PartitionJournalConfig config = new PartitionJournalConfig().setMaximumFileSize(cbMaxFile);

        PartitionJournal journal = new PartitionJournal(fileDir, config);
        journal.open();
        m_listJournals.add(journal);
        return journal;
        }

    private PartitionJournal reopenJournal(File fileDir, long cbMaxFile)
            throws Exception
        {
        PartitionJournalConfig config = new PartitionJournalConfig().setMaximumFileSize(cbMaxFile);

        PartitionJournal journal = new PartitionJournal(fileDir, config);
        journal.open();
        m_listJournals.add(journal);
        return journal;
        }

    private File m_fileDir;

    private List<PartitionJournal> m_listJournals;
    }
