/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.ByteArrayReadBuffer;

import com.tangosol.util.Binary;

import java.nio.ByteBuffer;

import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link JournalEntry}.
 *
 * @author rl  2026.03.05
 * @since 26.04
 */
public class JournalEntryTest
    {
    @Test
    public void testStoreRoundTrip()
        {
        Binary     binKey   = new Binary(new byte[] {1, 2, 3});
        Binary     binValue = new Binary(new byte[] {10, 11, 12, 13});
        ByteBuffer buffer   = JournalEntry.createStoreEntry(binKey, binValue);

        JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(buffer.array()), 0);

        assertEquals(JournalEntry.TYPE_STORE, entry.getType());
        assertEquals(binKey, entry.getKey());
        assertEquals(binValue, entry.getValue());
        assertEquals(buffer.remaining(), entry.getEntryLen());
        assertEquals(JournalEntry.OFFSET_STORE_VALUE_LEN, entry.getValLenOffset());
        assertTrue(entry.isValid());
        }

    @Test
    public void testEraseRoundTrip()
        {
        Binary     binKey = new Binary(new byte[] {5, 6});
        ByteBuffer buffer = JournalEntry.createEraseEntry(binKey);

        JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(buffer.array()), 0);

        assertEquals(JournalEntry.TYPE_ERASE, entry.getType());
        assertEquals(binKey, entry.getKey());
        assertNull(entry.getValue());
        assertEquals(-1, entry.getValLenOffset());
        assertTrue(entry.isValid());
        }

    @Test
    public void testEraseExtentRoundTrip()
        {
        ByteBuffer buffer = JournalEntry.createEraseExtentEntry();

        JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(buffer.array()), 0);

        assertEquals(JournalEntry.TYPE_ERASE_EXTENT, entry.getType());
        assertNull(entry.getKey());
        assertNull(entry.getValue());
        assertEquals(-1, entry.getValLenOffset());
        assertTrue(entry.isValid());
        }

    @Test
    public void testCrcValidationFailure()
        {
        ByteBuffer buffer = JournalEntry.createStoreEntry(
                new Binary(new byte[] {1}),
                new Binary(new byte[] {2, 3, 4}));

        byte[] ab = buffer.array();
        int ofValue = JournalEntry.computeValueOffset(1);
        ab[ofValue] ^= 0x01;

        JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(ab), 0);
        assertFalse(entry.isValid());
        }

    @Test
    public void testPaddingAlignment()
        {
        for (int cbKey = 0; cbKey < 7; cbKey++)
            {
            for (int cbValue = 0; cbValue < 7; cbValue++)
                {
                ByteBuffer buffer = JournalEntry.createStoreEntry(
                        new Binary(new byte[cbKey]),
                        new Binary(new byte[cbValue]));

                assertEquals(0, buffer.remaining() % 16);

                JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(buffer.array()), 0);
                assertEquals(buffer.remaining(), entry.getEntryLen());
                }
            }
        }

    @Test
    public void testEmptyKeyAndValue()
        {
        ByteBuffer buffer = JournalEntry.createStoreEntry(Binary.NO_BINARY, Binary.NO_BINARY);
        JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(buffer.array()), 0);

        assertEquals(Binary.NO_BINARY, entry.getKey());
        assertEquals(Binary.NO_BINARY, entry.getValue());
        assertTrue(entry.isValid());
        }

    @Test
    public void testTruncatedLargeValueHeader()
        {
        byte[] ab = new byte[64];
        ByteBuffer buf = ByteBuffer.wrap(ab);
        buf.putInt(64);                          // entry_len
        buf.put(JournalEntry.TYPE_STORE);         // type
        buf.putInt(0);                            // key_len
        buf.putInt(Integer.MAX_VALUE);            // val_len (huge)

        try
            {
            JournalEntry.parseEntry(new ByteArrayReadBuffer(ab), 0);
            fail("expected IllegalArgumentException for truncated value payload");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testParseEntryHeaderSkipsValue()
        {
        Binary     binKey   = new Binary(new byte[] {1, 2, 3, 4});
        Binary     binValue = new Binary(new byte[1024]);
        ByteBuffer buffer   = JournalEntry.createStoreEntry(binKey, binValue);

        JournalEntry entry = JournalEntry.parseEntryHeader(new ByteArrayReadBuffer(buffer.array()), 0);

        assertEquals(JournalEntry.TYPE_STORE, entry.getType());
        assertEquals(binKey, entry.getKey());
        assertNull(entry.getValue());
        assertEquals(JournalEntry.OFFSET_STORE_VALUE_LEN, entry.getValLenOffset());
        assertEquals(buffer.remaining(), entry.getEntryLen());
        assertTrue(entry.isValid());
        }

    @Test
    public void testStoreHeaderOffsets()
        {
        assertEquals(5, JournalEntry.OFFSET_STORE_KEY_LEN);
        assertEquals(9, JournalEntry.OFFSET_STORE_VALUE_LEN);
        assertEquals(13, JournalEntry.STORE_HEADER_SIZE);
        assertEquals(16, JournalEntry.computeValueOffset(3));
        }

    @Test
    public void testParseEntryHeaderEraseAndEraseExtent()
        {
        ByteArrayReadBuffer bufErase = new ByteArrayReadBuffer(
                JournalEntry.createEraseEntry(new Binary(new byte[] {1, 2})).array());

        JournalEntry entryEraseFull   = JournalEntry.parseEntry(bufErase, 0);
        JournalEntry entryEraseHeader = JournalEntry.parseEntryHeader(bufErase, 0);

        assertEquals(entryEraseFull.getType(), entryEraseHeader.getType());
        assertEquals(entryEraseFull.getKey(), entryEraseHeader.getKey());
        assertEquals(entryEraseFull.getValue(), entryEraseHeader.getValue());
        assertEquals(entryEraseFull.getValLenOffset(), entryEraseHeader.getValLenOffset());
        assertEquals(entryEraseFull.getEntryLen(), entryEraseHeader.getEntryLen());
        assertEquals(entryEraseFull.isValid(), entryEraseHeader.isValid());

        ByteArrayReadBuffer bufEraseExtent = new ByteArrayReadBuffer(
                JournalEntry.createEraseExtentEntry().array());

        JournalEntry entryEraseExtentFull   = JournalEntry.parseEntry(bufEraseExtent, 0);
        JournalEntry entryEraseExtentHeader = JournalEntry.parseEntryHeader(bufEraseExtent, 0);

        assertEquals(entryEraseExtentFull.getType(), entryEraseExtentHeader.getType());
        assertEquals(entryEraseExtentFull.getKey(), entryEraseExtentHeader.getKey());
        assertEquals(entryEraseExtentFull.getValue(), entryEraseExtentHeader.getValue());
        assertEquals(entryEraseExtentFull.getValLenOffset(), entryEraseExtentHeader.getValLenOffset());
        assertEquals(entryEraseExtentFull.getEntryLen(), entryEraseExtentHeader.getEntryLen());
        assertEquals(entryEraseExtentFull.isValid(), entryEraseExtentHeader.isValid());
        }

    @Test
    public void testParseEntryAtNonZeroOffset()
        {
        ByteBuffer bufStore = JournalEntry.createStoreEntry(
                new Binary(new byte[] {1, 2}),
                new Binary(new byte[] {3, 4, 5}));

        ByteBuffer bufErase = JournalEntry.createEraseEntry(new Binary(new byte[] {9, 8, 7}));

        byte[] abStore = bufStore.array();
        byte[] abErase = bufErase.array();
        byte[] abAll   = Arrays.copyOf(abStore, abStore.length + abErase.length);

        System.arraycopy(abErase, 0, abAll, abStore.length, abErase.length);

        JournalEntry entry = JournalEntry.parseEntry(new ByteArrayReadBuffer(abAll), abStore.length);

        assertEquals(JournalEntry.TYPE_ERASE, entry.getType());
        assertEquals(new Binary(new byte[] {9, 8, 7}), entry.getKey());
        assertNull(entry.getValue());
        assertTrue(entry.isValid());
        }

    @Test
    public void testSequentialParseAcrossMultipleEntries()
        {
        ByteBuffer[] aBuffer = new ByteBuffer[]
            {
            JournalEntry.createStoreEntry(new Binary(new byte[] {1}), new Binary(new byte[] {10, 11})),
            JournalEntry.createEraseEntry(new Binary(new byte[] {2, 3})),
            JournalEntry.createEraseExtentEntry(),
            JournalEntry.createStoreEntry(Binary.NO_BINARY, new Binary(new byte[] {12}))
            };

        int cbTotal = 0;
        for (ByteBuffer buffer : aBuffer)
            {
            cbTotal += buffer.array().length;
            }

        byte[] abAll = new byte[cbTotal];
        int    of    = 0;
        for (ByteBuffer buffer : aBuffer)
            {
            byte[] ab = buffer.array();
            System.arraycopy(ab, 0, abAll, of, ab.length);
            of += ab.length;
            }

        ByteArrayReadBuffer readBuffer = new ByteArrayReadBuffer(abAll);
        int                 ofEntry    = 0;

        for (int i = 0; i < aBuffer.length; i++)
            {
            JournalEntry entry = JournalEntry.parseEntry(readBuffer, ofEntry);

            assertTrue(entry.isValid());
            ofEntry += (int) entry.getEntryLen();
            }

        assertEquals(abAll.length, ofEntry);
        }

    @Test
    public void testUnknownEntryTypeRejected()
        {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(16L);
        buffer.put((byte) 0x7F);

        try
            {
            JournalEntry.parseEntry(new ByteArrayReadBuffer(buffer.array()), 0);
            fail("expected IllegalArgumentException for unknown journal entry type");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }
    }
