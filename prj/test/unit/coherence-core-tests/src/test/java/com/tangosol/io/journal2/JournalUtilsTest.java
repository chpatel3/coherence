/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.ByteArrayReadBuffer;
import com.tangosol.io.FileHelper;

import com.tangosol.util.Binary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JournalUtils}.
 *
 * @author rl  2026.04.03
 * @since 26.04
 */
public class JournalUtilsTest
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
        if (m_fileDir != null)
            {
            FileHelper.deleteDirSilent(m_fileDir);
            }
        }

    @Test
    public void testDiscoverFileIdsEmptyDirectory()
        {
        assertTrue(JournalUtils.discoverFileIds(m_fileDir).isEmpty());
        }

    @Test
    public void testDiscoverFileIdsIgnoresNonJournalFilesAndSortsGaps()
            throws Exception
        {
        assertTrue(new File(m_fileDir, "notes.txt").createNewFile());
        assertTrue(JournalUtils.toJournalFile(m_fileDir, 7).createNewFile());
        assertTrue(JournalUtils.toJournalFile(m_fileDir, 1).createNewFile());
        assertTrue(new File(m_fileDir, "journal-abc.coh").createNewFile());
        assertTrue(JournalUtils.toJournalFile(m_fileDir, 3).createNewFile());

        assertEquals(Arrays.asList(1, 3, 7), JournalUtils.discoverFileIds(m_fileDir));
        }

    @Test
    public void testScanFileZeroLengthFileVisitsNothing()
            throws Exception
        {
        File file = JournalUtils.toJournalFile(m_fileDir, 1);
        assertTrue(file.createNewFile());

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, (nFileId, ofEntry, entry) ->
            {
            listEntries.add(entry);
            return true;
            });

        assertTrue(listEntries.isEmpty());
        }

    @Test
    public void testScanFileInvalidEntryLengthProducesInvalidEntry()
            throws Exception
        {
        File file = JournalUtils.toJournalFile(m_fileDir, 1);
        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(new byte[Integer.BYTES]);
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, (nFileId, ofEntry, entry) ->
            {
            listEntries.add(entry);
            return true;
            });

        assertEquals(1, listEntries.size());
        assertFalse(listEntries.get(0).isValid());
        assertEquals(0L, listEntries.get(0).getEntryLen());
        }

    @Test
    public void testScanFileEntryLengthBeyondFileSizeProducesInvalidEntry()
            throws Exception
        {
        File       file   = JournalUtils.toJournalFile(m_fileDir, 1);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(64);
        buffer.putInt(0);

        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(buffer.array());
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, (nFileId, ofEntry, entry) ->
            {
            listEntries.add(entry);
            return true;
            });

        assertEquals(1, listEntries.size());
        assertFalse(listEntries.get(0).isValid());
        assertEquals(file.length(), listEntries.get(0).getEntryLen());
        }

    @Test
    public void testReadIntAndReadLongUseBigEndianOrder()
        {
        byte[] ab = new byte[]
            {
            0x01, 0x23, 0x45, 0x67,
            (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF
            };

        ByteArrayReadBuffer buffer = new ByteArrayReadBuffer(ab);

        assertEquals(0x01234567, JournalUtils.readInt(buffer, 0));
        assertEquals(0x0123456789ABCDEFL, JournalUtils.readLong(buffer, 0));
        }

    @Test
    public void testScanFileReadsValidStoreEntry()
            throws Exception
        {
        File       file   = JournalUtils.toJournalFile(m_fileDir, 1);
        ByteBuffer entry  = JournalEntry.createStoreEntry(new Binary(new byte[] {1, 2}),
                new Binary(new byte[] {3, 4, 5}));

        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(entry.array());
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, (nFileId, ofEntry, parsed) ->
            {
            listEntries.add(parsed);
            return true;
            });

        assertEquals(1, listEntries.size());
        assertTrue(listEntries.get(0).isValid());
        assertEquals(new Binary(new byte[] {1, 2}), listEntries.get(0).getKey());
        }

    @Test
    public void testScanFileReadsValidStoreEntryWithValue()
            throws Exception
        {
        File       file    = JournalUtils.toJournalFile(m_fileDir, 1);
        Binary     binKey  = new Binary(new byte[] {1, 2});
        Binary     binValue = new Binary(new byte[] {3, 4, 5});
        ByteBuffer entry   = JournalEntry.createStoreEntry(binKey, binValue);

        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(entry.array());
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, true, (nFileId, ofEntry, parsed) ->
            {
            listEntries.add(parsed);
            return true;
            });

        assertEquals(1, listEntries.size());
        assertTrue(listEntries.get(0).isValid());
        assertEquals(binKey, listEntries.get(0).getKey());
        assertEquals(binValue, listEntries.get(0).getValue());
        }

    @Test
    public void testScanFileReadsEntryAcrossScanBufferBoundary()
            throws Exception
        {
        File   file      = JournalUtils.toJournalFile(m_fileDir, 1);
        Binary binKey1   = new Binary(new byte[] {1});
        Binary binValue1 = new Binary(new byte[JournalUtils.SCAN_BUFFER_SIZE - 64]);
        Binary binKey2   = new Binary(new byte[] {2, 3});
        Binary binValue2 = new Binary(new byte[128]);

        ByteBuffer entry1 = JournalEntry.createStoreEntry(binKey1, binValue1);
        ByteBuffer entry2 = JournalEntry.createStoreEntry(binKey2, binValue2);

        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(entry1.array());
            out.write(entry2.array());
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, true, (nFileId, ofEntry, parsed) ->
            {
            listEntries.add(parsed);
            return true;
            });

        assertEquals(2, listEntries.size());
        assertTrue(listEntries.get(0).isValid());
        assertTrue(listEntries.get(1).isValid());
        assertEquals(binKey1, listEntries.get(0).getKey());
        assertEquals(binValue1, listEntries.get(0).getValue());
        assertEquals(binKey2, listEntries.get(1).getKey());
        assertEquals(binValue2, listEntries.get(1).getValue());
        }

    @Test
    public void testScanFileStreamingFallbackForOversizedEntry()
            throws Exception
        {
        File   file     = JournalUtils.toJournalFile(m_fileDir, 1);
        Binary binKey   = new Binary(new byte[] {1, 2, 3});
        Binary binValue = new Binary(new byte[JournalUtils.SCAN_BUFFER_SIZE + 1024]);

        ByteBuffer entry = JournalEntry.createStoreEntry(binKey, binValue);

        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(entry.array());
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, true, (nFileId, ofEntry, parsed) ->
            {
            listEntries.add(parsed);
            return true;
            });

        assertEquals(1, listEntries.size());
        assertTrue(listEntries.get(0).isValid());
        assertEquals(binKey, listEntries.get(0).getKey());
        assertEquals(binValue, listEntries.get(0).getValue());
        }

    @Test
    public void testScanFileTruncatedEntryAtEOF()
            throws Exception
        {
        File       file        = JournalUtils.toJournalFile(m_fileDir, 1);
        Binary     binKey      = new Binary(new byte[] {7});
        Binary     binValue    = new Binary(new byte[] {8, 9, 10});
        ByteBuffer entryValid  = JournalEntry.createStoreEntry(binKey, binValue);
        ByteBuffer entryBroken = JournalEntry.createStoreEntry(new Binary(new byte[] {11}),
                new Binary(new byte[] {12, 13, 14, 15}));
        int        cbTruncated = entryBroken.array().length - 5;

        try (FileOutputStream out = new FileOutputStream(file))
            {
            out.write(entryValid.array());
            out.write(entryBroken.array(), 0, cbTruncated);
            }

        List<JournalEntry> listEntries = new ArrayList<>();
        JournalUtils.scanFile(file, 1, 0L, true, (nFileId, ofEntry, parsed) ->
            {
            listEntries.add(parsed);
            return true;
            });

        assertEquals(2, listEntries.size());
        assertTrue(listEntries.get(0).isValid());
        assertEquals(binKey, listEntries.get(0).getKey());
        assertEquals(binValue, listEntries.get(0).getValue());
        assertFalse(listEntries.get(1).isValid());
        assertEquals((long) cbTruncated, listEntries.get(1).getEntryLen());
        }

    @Test
    public void testBufferedValueReaderReadsSequentialEntriesInSingleFile()
            throws Exception
        {
        PartitionJournal journal = newJournal(1024 * 1024);

        Binary binValue1 = new Binary(new byte[JournalUtils.SCAN_BUFFER_SIZE - 128]);
        Binary binValue2 = new Binary(new byte[] {9, 8, 7, 6});

        long lTicket1 = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {1}), binValue1);
        long lTicket2 = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {2}), binValue2);
        int  nFileId  = journal.getCurrentFileId();

        journal.close();

        try (JournalUtils.BufferedValueReader reader = JournalUtils.openBufferedValueReader(m_fileDir, nFileId))
            {
            assertEquals(binValue1, reader.readValue(lTicket1));
            assertEquals(binValue2, reader.readValue(lTicket2));
            }
        }

    @Test
    public void testBufferedValueReaderReadsAcrossMultipleFiles()
            throws Exception
        {
        PartitionJournal journal = newJournal(96);
        List<Long>       listTickets = new ArrayList<>();
        List<Binary>     listValues  = new ArrayList<>();

        for (int i = 0; i < 6; i++)
            {
            Binary binValue = new Binary(new byte[] {(byte) (11 + i), (byte) (21 + i), (byte) (31 + i)});
            listTickets.add(journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {(byte) i}), binValue));
            listValues.add(binValue);
            }

        journal.close();

        assertTrue(JournalUtils.discoverFileIds(m_fileDir).size() > 1);

        int nLastFileId = -1;
        JournalUtils.BufferedValueReader reader = null;
        try
            {
            for (int i = 0; i < listTickets.size(); i++)
                {
                long lTicket  = listTickets.get(i);
                int  nFileId  = PersistentTicket.extractFileId(lTicket);

                if (nFileId != nLastFileId)
                    {
                    if (reader != null)
                        {
                        reader.close();
                        }
                    reader = JournalUtils.openBufferedValueReader(m_fileDir, nFileId);
                    nLastFileId = nFileId;
                    }

                assertEquals(listValues.get(i), reader.readValue(lTicket));
                }
            }
        finally
            {
            if (reader != null)
                {
                reader.close();
                }
            }
        }

    @Test
    public void testBufferedValueReaderFallsBackForOversizedValue()
            throws Exception
        {
        PartitionJournal journal = newJournal(2L * JournalUtils.SCAN_BUFFER_SIZE);

        byte[] abValue = new byte[JournalUtils.SCAN_BUFFER_SIZE + 1024];
        for (int i = 0; i < abValue.length; i++)
            {
            abValue[i] = (byte) (i & 0xFF);
            }

        Binary binValue = new Binary(abValue);
        long   lTicket  = journal.append(JournalEntry.TYPE_STORE, new Binary(new byte[] {1, 2, 3}), binValue);
        int    nFileId  = journal.getCurrentFileId();

        journal.close();

        try (JournalUtils.BufferedValueReader reader = JournalUtils.openBufferedValueReader(m_fileDir, nFileId))
            {
            assertEquals(binValue, reader.readValue(lTicket));
            }
        }

    private PartitionJournal newJournal(long cbMaxFile)
            throws Exception
        {
        PartitionJournalConfig config = new PartitionJournalConfig().setMaximumFileSize(cbMaxFile);
        PartitionJournal       journal = new PartitionJournal(m_fileDir, config);
        journal.open();
        return journal;
        }

    /**
     * Temp directory.
     */
    private File m_fileDir;
    }
