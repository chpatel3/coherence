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

import java.nio.file.Files;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link CheckpointFile}.
 *
 * @author rl  2026.03.05
 * @since 26.04
 */
public class CheckpointFileTest
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
    public void testWriteReadRoundTrip()
            throws Exception
        {
        BinaryRadixTree tree       = new BinaryRadixTree();

        Binary binKey1 = new Binary(new byte[] {1, 2, 3});
        Binary binKey2 = new Binary(new byte[] {4});
        long   lTicket1 = PersistentTicket.encode(1, 16L, binKey1.length());
        long   lTicket2 = PersistentTicket.encode(2, 64L, binKey2.length());
        tree.put(binKey1, lTicket1);
        tree.put(binKey2, lTicket2);

        File fileCheckpoint = new File(m_fileDir, "checkpoint.coh");
        CheckpointFile.write(fileCheckpoint, 9, 512L, tree);

        CheckpointFile.CheckpointData data = CheckpointFile.read(fileCheckpoint);

        assertEquals(CheckpointFile.VERSION, data.getVersion());
        assertEquals(9, data.getFileNo());
        assertEquals(512L, data.getOffset());
        assertEquals(2, data.getEntries().size());

        assertEquals(Long.valueOf(lTicket1), data.getEntries().get(binKey1));
        assertEquals(Long.valueOf(lTicket2), data.getEntries().get(binKey2));
        }

    @Test
    public void testReadCorruptedCrc()
            throws Exception
        {
        BinaryRadixTree tree       = new BinaryRadixTree();

        Binary binKey   = new Binary(new byte[] {9});
        long   lTicket  = PersistentTicket.encode(3, 80L, binKey.length());

        tree.put(binKey, lTicket);

        File fileCheckpoint = new File(m_fileDir, "checkpoint.coh");
        CheckpointFile.write(fileCheckpoint, 1, 32L, tree);

        byte[] ab = Files.readAllBytes(fileCheckpoint.toPath());
        ab[ab.length - 1] ^= 0x01;
        Files.write(fileCheckpoint.toPath(), ab);

        try
            {
            CheckpointFile.read(fileCheckpoint);
            fail("expected IllegalArgumentException for bad CRC");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testEmptyCheckpoint()
            throws Exception
        {
        BinaryRadixTree tree       = new BinaryRadixTree();

        File fileCheckpoint = new File(m_fileDir, "checkpoint.coh");
        CheckpointFile.write(fileCheckpoint, 7, 128L, tree);

        CheckpointFile.CheckpointData data = CheckpointFile.read(fileCheckpoint);
        assertEquals(CheckpointFile.VERSION, data.getVersion());
        assertEquals(7, data.getFileNo());
        assertEquals(128L, data.getOffset());
        assertTrue(data.getEntries().isEmpty());
        }

    @Test
    public void testAtomicWritePreservesOldCheckpointUntilReplace()
            throws Exception
        {
        BinaryRadixTree tree1       = new BinaryRadixTree();
        long            lTicket1    = PersistentTicket.encode(1, 16L, 1);

        tree1.put(new Binary(new byte[] {1}), lTicket1);

        File fileCheckpoint = new File(m_fileDir, "checkpoint.coh");
        CheckpointFile.write(fileCheckpoint, 1, 16L, tree1);
        long cbBefore = fileCheckpoint.length();

        File fileTmp = new File(m_fileDir, "checkpoint.coh.tmp");
        Files.write(fileTmp.toPath(), new byte[] {1, 2, 3, 4, 5});

        CheckpointFile.CheckpointData dataBefore = CheckpointFile.read(fileCheckpoint);
        assertEquals(1, dataBefore.getEntries().size());

        BinaryRadixTree tree2       = new BinaryRadixTree();
        long            lTicket2    = PersistentTicket.encode(2, 32L, 1);

        tree2.put(new Binary(new byte[] {2}), lTicket2);

        CheckpointFile.write(fileCheckpoint, 2, 32L, tree2);

        assertTrue(fileCheckpoint.exists());
        assertFalse(fileTmp.exists());
        assertNotNull(CheckpointFile.read(fileCheckpoint));
        assertTrue(fileCheckpoint.length() > 0);
        assertTrue(cbBefore > 0);
        }

    @Test
    public void testVisitorBasedRead()
            throws Exception
        {
        BinaryRadixTree tree       = new BinaryRadixTree();

        Binary binKey1 = new Binary(new byte[] {1, 2, 3});
        Binary binKey2 = new Binary(new byte[] {4, 5});
        Binary binKey3 = new Binary(new byte[] {6});
        long   lTicket1 = PersistentTicket.encode(1, 16L, binKey1.length());
        long   lTicket2 = PersistentTicket.encode(2, 64L, binKey2.length());
        long   lTicket3 = PersistentTicket.encode(3, 128L, binKey3.length());
        tree.put(binKey1, lTicket1);
        tree.put(binKey2, lTicket2);
        tree.put(binKey3, lTicket3);

        File fileCheckpoint = new File(m_fileDir, "checkpoint.coh");
        CheckpointFile.write(fileCheckpoint, 5, 256L, tree);

        // Use visitor-based read
        Map<Binary, Long> mapVisited = new LinkedHashMap<>();
        CheckpointFile.CheckpointHeader header = CheckpointFile.read(fileCheckpoint,
                mapVisited::put);

        assertEquals(5, header.getFileNo());
        assertEquals(256L, header.getOffset());
        assertEquals(3, mapVisited.size());

        assertEquals(Long.valueOf(lTicket1), mapVisited.get(binKey1));
        assertEquals(Long.valueOf(lTicket2), mapVisited.get(binKey2));
        assertEquals(Long.valueOf(lTicket3), mapVisited.get(binKey3));
        }

    private File m_fileDir;
    }
