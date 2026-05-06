/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;

import com.tangosol.util.Binary;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link PartitionJournal}.
 *
 * @author rl  2026.03.05
 * @since 26.04
 */
public class PartitionJournalTest
    {
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
    public void testAppendReadRoundTrip()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey   = new Binary(new byte[] {1, 2});
        Binary binValue = new Binary(new byte[] {10, 11, 12});

        long       lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        ReadBuffer bufRead = journal.read(lTicket);

        assertEquals(binValue, bufRead.toBinary());
        assertEquals(journal.getCurrentFileId(), PersistentTicket.extractFileId(lTicket));
        assertEquals(0L, PersistentTicket.extractEntryOffset(lTicket));
        assertEquals(binKey.length(), PersistentTicket.extractKeyLength(lTicket));
        }

    @Test
    public void testAppendReadRoundTripWithBufferedAndStreamingPaths()
            throws Exception
        {
        PartitionJournal journal = newJournal(256 * 1024);

        Binary binSmallKey   = new Binary(new byte[] {1, 2});
        Binary binSmallValue = new Binary(new byte[128]);
        Binary binLargeKey   = new Binary(new byte[] {3, 4});
        Binary binLargeValue = new Binary(new byte[40 * 1024]);
        Binary binSmallKey2  = new Binary(new byte[] {5, 6});
        Binary binSmallValue2 = new Binary(new byte[64]);

        long t1 = journal.append(JournalEntry.TYPE_STORE, binSmallKey, binSmallValue);
        long t2 = journal.append(JournalEntry.TYPE_STORE, binLargeKey, binLargeValue);
        long t3 = journal.append(JournalEntry.TYPE_STORE, binSmallKey2, binSmallValue2);

        assertEquals(binSmallValue, journal.read(t1).toBinary());
        assertEquals(binLargeValue, journal.read(t2).toBinary());
        assertEquals(binSmallValue2, journal.read(t3).toBinary());
        }

    @Test
    public void testAppendBatchRoundTripWithBufferedAndStreamingPaths()
            throws Exception
        {
        PartitionJournal journal = newJournal(new PartitionJournalConfig()
                .setMaximumFileSize(512 * 1024)
                .setBlockSize(4 * 1024));

        Binary binSmallKey1   = new Binary(new byte[] {1});
        Binary binSmallValue1 = new Binary(new byte[128]);
        Binary binSmallKey2   = new Binary(new byte[] {2});
        Binary binSmallValue2 = new Binary(new byte[96]);
        Binary binLargeKey    = new Binary(new byte[] {3});
        Binary binLargeValue  = new Binary(new byte[40 * 1024]);
        Binary binSmallKey3   = new Binary(new byte[] {4});
        Binary binSmallValue3 = new Binary(new byte[64]);

        List<PartitionJournal.AppendRequest> listRequest = new ArrayList<>();
        listRequest.add(new PartitionJournal.AppendRequest(JournalEntry.TYPE_STORE, binSmallKey1, binSmallValue1));
        listRequest.add(new PartitionJournal.AppendRequest(JournalEntry.TYPE_STORE, binSmallKey2, binSmallValue2));
        listRequest.add(new PartitionJournal.AppendRequest(JournalEntry.TYPE_STORE, binLargeKey, binLargeValue));
        listRequest.add(new PartitionJournal.AppendRequest(JournalEntry.TYPE_STORE, binSmallKey3, binSmallValue3));

        long[] alTicket = journal.appendBatch(listRequest);

        assertEquals(4, alTicket.length);
        assertEquals(binSmallValue1, journal.read(alTicket[0]).toBinary());
        assertEquals(binSmallValue2, journal.read(alTicket[1]).toBinary());
        assertEquals(binLargeValue, journal.read(alTicket[2]).toBinary());
        assertEquals(binSmallValue3, journal.read(alTicket[3]).toBinary());
        assertTrue(PersistentTicket.extractEntryOffset(alTicket[1])
                > PersistentTicket.extractEntryOffset(alTicket[0]));
        assertTrue(PersistentTicket.extractEntryOffset(alTicket[2])
                > PersistentTicket.extractEntryOffset(alTicket[1]));
        assertTrue(PersistentTicket.extractEntryOffset(alTicket[3])
                > PersistentTicket.extractEntryOffset(alTicket[2]));
        }

    @Test
    public void testAppendBatchWithFileRotationMidBatch()
            throws Exception
        {
        PartitionJournal journal = newJournal(new PartitionJournalConfig()
                .setMaximumFileSize(80)
                .setBlockSize(16));

        List<PartitionJournal.AppendRequest> listRequest = new ArrayList<>();
        List<Binary>                         listValue   = new ArrayList<>();

        for (int i = 0; i < 3; i++)
            {
            Binary binKey   = new Binary(new byte[] {(byte) (i + 1)});
            Binary binValue = new Binary(new byte[20 + i]);

            listRequest.add(new PartitionJournal.AppendRequest(JournalEntry.TYPE_STORE, binKey, binValue));
            listValue.add(binValue);
            }

        long[] alTicket = journal.appendBatch(listRequest);

        assertEquals(3, alTicket.length);
        assertTrue(PersistentTicket.isValid(alTicket[0]));
        assertTrue(PersistentTicket.isValid(alTicket[1]));
        assertTrue(PersistentTicket.isValid(alTicket[2]));
        assertTrue(PersistentTicket.extractFileId(alTicket[1]) > PersistentTicket.extractFileId(alTicket[0]));

        for (int i = 0; i < alTicket.length; i++)
            {
            assertEquals(listValue.get(i), journal.read(alTicket[i]).toBinary());
            }
        }

    @Test
    public void testAppendBatchEmpty()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        assertEquals(0, journal.appendBatch(Collections.emptyList()).length);
        }

    @Test
    public void testFileRotationWhenFull()
            throws Exception
        {
        PartitionJournal journal = newJournal(64);

        Binary binKey   = new Binary(new byte[] {1});
        Binary binValue = new Binary(new byte[24]);

        journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

        int nFile1 = journal.getCurrentFileId();

        journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

        int nFile2 = journal.getCurrentFileId();
        assertTrue(nFile2 > nFile1);
        assertEquals(PartitionJournal.FileState.FULL, journal.getFileInfo(nFile1).getState());
        }

    @Test
    public void testForceRotate()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        int nFileBefore = journal.getCurrentFileId();
        journal.forceRotate();
        int nFileAfter = journal.getCurrentFileId();

        assertTrue(nFileAfter > nFileBefore);
        assertEquals(PartitionJournal.FileState.FULL, journal.getFileInfo(nFileBefore).getState());
        assertEquals(PartitionJournal.FileState.APPENDING, journal.getFileInfo(nFileAfter).getState());
        }

    @Test
    public void testReleaseAndCompactionCandidate()
            throws Exception
        {
        PartitionJournal journal = newJournal(64);

        Binary binKey   = new Binary(new byte[] {1});
        Binary binValue = new Binary(new byte[20]);

        long t1 = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

        int nFile1 = journal.getCurrentFileId();

        Binary binKey2 = new Binary(new byte[] {2});
        journal.append(JournalEntry.TYPE_STORE, binKey2, binValue);

        assertEquals(PartitionJournal.FileState.FULL, journal.getFileInfo(nFile1).getState());

        journal.release(t1);

        List<PartitionJournal.JournalFileInfo> list = journal.getCompactionCandidates(0.95d);
        assertFalse(list.isEmpty());
        assertEquals(nFile1, list.get(0).getFileId());
        }

    @Test
    public void testReopenDiscoversExistingFiles()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary      binKey   = new Binary(new byte[] {8});
        Binary      binValue = new Binary(new byte[] {9, 10, 11});
        long        ticket   = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        int         nFile    = journal.getCurrentFileId();

        journal.close();

        PartitionJournal journal2 = new PartitionJournal(m_fileDir, new PartitionJournalConfig().setMaximumFileSize(1024 * 1024));
        journal2.open();

        assertEquals(nFile, journal2.getCurrentFileId());
        assertEquals(binValue, journal2.read(ticket).toBinary());
        }

    @Test
    public void testReleaseAfterReopenReadsMetadataFromJournal()
            throws Exception
        {
        PartitionJournal journal = newJournal(64);

        Binary binKey1  = new Binary(new byte[] {1});
        Binary binKey2  = new Binary(new byte[] {2});
        Binary binValue = new Binary(new byte[20]);

        long t1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binValue);
        int  nFile1 = journal.getCurrentFileId();

        journal.append(JournalEntry.TYPE_STORE, binKey2, binValue);
        journal.close();

        PartitionJournal journal2 = new PartitionJournal(m_fileDir,
                new PartitionJournalConfig().setMaximumFileSize(64));
        journal2.open();
        m_listJournals.add(journal2);

        journal2.release(t1);

        List<PartitionJournal.JournalFileInfo> list = journal2.getCompactionCandidates(0.95d);
        assertFalse(list.isEmpty());
        assertEquals(nFile1, list.get(0).getFileId());
        }

    @Test
    public void testReadStoreEntryRoundTrip()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey   = new Binary(new byte[] {1, 2, 3, 4});
        Binary binValue = new Binary(new byte[] {10, 11, 12, 13, 14});

        long         lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        JournalEntry entry   = journal.readStoreEntry(lTicket);

        assertEquals(JournalEntry.TYPE_STORE, entry.getType());
        assertEquals(binKey, entry.getKey());
        assertEquals(binValue, entry.getValue());
        assertEquals(JournalEntry.OFFSET_STORE_VALUE_LEN, entry.getValLenOffset());
        }

    @Test
    public void testZeroLengthValueWrite()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey = new Binary(new byte[] {1, 2, 3});

        long         lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, Binary.NO_BINARY);
        JournalEntry entry   = journal.readStoreEntry(lTicket);

        assertEquals(Binary.NO_BINARY, journal.read(lTicket).toBinary());
        assertEquals(Binary.NO_BINARY, entry.getValue());
        }

    @Test
    public void testAppendAfterReopenRecoversWritePosition()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binKey1   = new Binary(new byte[] {1});
        Binary binValue1 = new Binary(new byte[] {11});
        Binary binKey2   = new Binary(new byte[] {2});
        Binary binValue2 = new Binary(new byte[] {22});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binValue1);
        long ofAppend = journal.getCurrentOffset();
        int  nFileId  = journal.getCurrentFileId();

        journal.close();

        PartitionJournal journal2 = new PartitionJournal(m_fileDir,
                new PartitionJournalConfig().setMaximumFileSize(1024 * 1024));
        journal2.open();
        m_listJournals.add(journal2);

        assertEquals(ofAppend, journal2.getCurrentOffset());
        assertEquals(nFileId, journal2.getCurrentFileId());

        long lTicket2 = journal2.append(JournalEntry.TYPE_STORE, binKey2, binValue2);

        assertEquals(ofAppend, PersistentTicket.extractEntryOffset(lTicket2));
        assertEquals(binValue1, journal2.read(lTicket1).toBinary());
        assertEquals(binValue2, journal2.read(lTicket2).toBinary());
        }

    @Test
    public void testConcurrentAppends()
            throws Exception
        {
        final int cThreads = 4;
        final int cWrites  = 25;

        PartitionJournal journal = newJournal(1024 * 1024);
        ExecutorService  executor = Executors.newFixedThreadPool(cThreads);
        CountDownLatch   latch    = new CountDownLatch(1);

        List<Future<List<Long>>> listFutures = new ArrayList<>();
        for (int i = 0; i < cThreads; i++)
            {
            final int nThread = i;
            listFutures.add(executor.submit(() ->
                {
                latch.await();
                List<Long> list = new ArrayList<>();
                for (int n = 0; n < cWrites; n++)
                    {
                    Binary     binKey   = new Binary(new byte[] {(byte) nThread, (byte) n});
                    Binary     binValue = new Binary(new byte[] {(byte) (n + 1)});
                    list.add(journal.append(JournalEntry.TYPE_STORE, binKey, binValue));
                    }
                return list;
                }));
            }

        latch.countDown();

        Set<Long> setTickets = new HashSet<>();
        for (Future<List<Long>> future : listFutures)
            {
            setTickets.addAll(future.get());
            }

        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));

        assertEquals(cThreads * cWrites, setTickets.size());
        }

    @Test
    public void testLargeValueNearFileLimit()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024);

        Binary     binKey   = new Binary(new byte[] {1, 2, 3, 4});
        Binary     binValue = new Binary(new byte[900]);

        long lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        assertEquals(binValue, journal.read(lTicket).toBinary());
        }

    @Test
    public void testLargeValueUsesStreamingAppendPath()
            throws Exception
        {
        PartitionJournal journal = newJournal(128 * 1024);

        Binary binKey   = new Binary(new byte[] {7, 8, 9, 10});
        Binary binValue = new Binary(new byte[48 * 1024]);

        long lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        assertEquals(binValue, journal.read(lTicket).toBinary());
        }

    @Test
    public void testDiscardRemovesFileFromDiskAndMap()
            throws Exception
        {
        PartitionJournal journal = newJournal(64);

        Binary     binKey   = new Binary(new byte[] {1});
        Binary     binValue = new Binary(new byte[24]);

        journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        int nFileToDiscard = journal.getCurrentFileId();

        long lTicket = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

        assertEquals(binValue, journal.read(lTicket).toBinary());

        File file = new File(m_fileDir, String.format("journal-%06d.coh", nFileToDiscard));
        assertTrue(file.exists());

        journal.markEvacuating(nFileToDiscard);
        journal.markGarbage(nFileToDiscard);
        journal.discard(nFileToDiscard);

        assertFalse(file.exists());

        try
            {
            journal.getFileInfo(nFileToDiscard);
            fail("expected missing file");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testReadFromDiscardedFileFailsClearly()
            throws Exception
        {
        PartitionJournal journal = newJournal(64);

        Binary binKey   = new Binary(new byte[] {1});
        Binary binValue = new Binary(new byte[24]);

        long lTicketDiscarded = journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        int  nFileToDiscard   = journal.getCurrentFileId();

        journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {2}), binValue);

        journal.markEvacuating(nFileToDiscard);
        journal.markGarbage(nFileToDiscard);
        journal.discard(nFileToDiscard);

        try
            {
            journal.read(lTicketDiscarded);
            fail("expected missing journal file error");
            }
        catch (IllegalArgumentException e)
            {
            assertTrue(e.getMessage().contains("missing journal file"));
            }
        }

    @Test
    public void testOversizedEntryRejected()
            throws Exception
        {
        PartitionJournal journal = newJournal(32);

        Binary     binKey   = new Binary(new byte[] {1});
        Binary     binValue = new Binary(new byte[32]);

        try
            {
            journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
            fail("expected oversized entry rejection");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testStateTransitionsToEvacuatingAndGarbage()
            throws Exception
        {
        PartitionJournal journal = newJournal(64);

        Binary     binKey   = new Binary(new byte[] {1});
        Binary     binValue = new Binary(new byte[24]);

        journal.append(JournalEntry.TYPE_STORE, binKey, binValue);
        int nFile = journal.getCurrentFileId();

        journal.append(JournalEntry.TYPE_STORE, binKey, binValue);

        journal.markEvacuating(nFile);
        assertEquals(PartitionJournal.FileState.EVACUATING, journal.getFileInfo(nFile).getState());

        journal.markGarbage(nFile);
        assertEquals(PartitionJournal.FileState.GARBAGE, journal.getFileInfo(nFile).getState());
        }

    @Test
    public void testInvalidStateTransitionsRejected()
            throws Exception
        {
        PartitionJournal journal = newJournal(128);

        int nCurrent = journal.getCurrentFileId();

        try
            {
            journal.markEvacuating(nCurrent);
            fail("expected markEvacuating to reject non-FULL file");
            }
        catch (IllegalStateException e)
            {
            // expected
            }

        try
            {
            journal.markGarbage(nCurrent);
            fail("expected markGarbage to reject non-EVACUATING file");
            }
        catch (IllegalStateException e)
            {
            // expected
            }
        }

    @Test
    public void testErrorCases()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024);
        journal.close();

        try
            {
            journal.append(JournalEntry.TYPE_ERASE_EXTENT, null, null);
            fail("expected closed-journal append failure");
            }
        catch (IllegalStateException e)
            {
            // expected
            }

        PartitionJournal journal2 = newJournal(1024);
        try
            {
            journal2.read(PersistentTicket.TICKET_NONE);
            fail("expected invalid ticket failure");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }

        try
            {
            journal2.discard(journal2.getCurrentFileId());
            fail("expected current-file discard failure");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testMultipleReadsAcrossDifferentFiles()
            throws Exception
        {
        PartitionJournal journal = newJournal(80);

        Binary binValue1 = new Binary(new byte[] {1, 2, 3});
        Binary binValue2 = new Binary(new byte[] {4, 5, 6});
        Binary binValue3 = new Binary(new byte[] {7, 8, 9});

        Binary binKey1 = new Binary(new byte[] {1});
        Binary binKey2 = new Binary(new byte[] {2});
        Binary binKey3 = new Binary(new byte[] {3});

        long t1 = journal.append(JournalEntry.TYPE_STORE, binKey1, binValue1);
        long t2 = journal.append(JournalEntry.TYPE_STORE, binKey2, binValue2);
        long t3 = journal.append(JournalEntry.TYPE_STORE, binKey3, binValue3);

        assertEquals(binValue1, journal.read(t1).toBinary());
        assertEquals(binValue2, journal.read(t2).toBinary());
        assertEquals(binValue3, journal.read(t3).toBinary());
        }

    private PartitionJournal newJournal(long cbMaxFile)
            throws Exception
        {
        return newJournal(new PartitionJournalConfig().setMaximumFileSize(cbMaxFile));
        }

    private PartitionJournal newJournal(PartitionJournalConfig config)
            throws Exception
        {
        PartitionJournal journal = new PartitionJournal(m_fileDir, config);
        journal.open();
        m_listJournals.add(journal);
        return journal;
        }

    private File m_fileDir;

    private List<PartitionJournal> m_listJournals;
    }
