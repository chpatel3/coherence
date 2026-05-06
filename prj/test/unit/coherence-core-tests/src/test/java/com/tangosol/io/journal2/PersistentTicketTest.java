/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link PersistentTicket}.
 *
 * @author rl  2026.03.04
 * @since 26.04
 */
public class PersistentTicketTest
    {
    @Test
    public void testRoundTrip()
        {
        assertRoundTrip(0, 0L, 0);
        assertRoundTrip(1, 16L, 0);
        assertRoundTrip(1, 32L, 17);
        assertRoundTrip(PersistentTicket.MAX_FILE_ID, 1024L, 4096);
        assertRoundTrip(PersistentTicket.MAX_FILE_ID, ((1L << 28) - 2L) << 4, PersistentTicket.MAX_KEY_LENGTH);
        }

    @Test
    public void testInvalidFileId()
        {
        try
            {
            PersistentTicket.encode(PersistentTicket.MAX_FILE_ID + 1, 0L, 0);
            fail("expected IllegalArgumentException for file id > max");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }

        try
            {
            PersistentTicket.encode(-1, 0L, 0);
            fail("expected IllegalArgumentException for negative file id");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testInvalidOffset()
        {
        try
            {
            PersistentTicket.encode(0, -1L, 0);
            fail("expected IllegalArgumentException for negative offset");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }

        try
            {
            PersistentTicket.encode(0, 1L, 0);
            fail("expected IllegalArgumentException for misaligned offset");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }

        try
            {
            PersistentTicket.encode(0, 1L << 32, 0);
            fail("expected IllegalArgumentException for offset out of supported range");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testInvalidKeyLength()
        {
        try
            {
            PersistentTicket.encode(0, 0L, -1);
            fail("expected IllegalArgumentException for negative key length");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }

        try
            {
            PersistentTicket.encode(0, 0L, PersistentTicket.MAX_KEY_LENGTH + 1);
            fail("expected IllegalArgumentException for oversized key length");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }

    @Test
    public void testNoneSentinel()
        {
        assertEquals(0L, PersistentTicket.TICKET_NONE);
        assertEquals(0, PersistentTicket.extractFileId(0L));
        assertEquals(0L, PersistentTicket.extractEntryOffset(0L));
        assertEquals(0, PersistentTicket.extractKeyLength(0L));
        assertTrue(PersistentTicket.isValid(0L));
        }

    @Test
    public void testReservedBitsAndValidation()
        {
        long lTicket = PersistentTicket.encode(123, 16L, 7);
        assertTrue(PersistentTicket.isValid(lTicket));

        // zero encoded offset units is reserved for TICKET_NONE
        assertFalse(PersistentTicket.isValid((long) 123 << 54));
        }

    @Test
    public void testFormat()
        {
        assertEquals("[ticket=none]", PersistentTicket.format(PersistentTicket.TICKET_NONE));
        assertEquals("[fileId=3, offset=1024, keyLen=42]",
                PersistentTicket.format(PersistentTicket.encode(3, 1024L, 42)));
        }

    private static void assertRoundTrip(int nFileId, long lOffset, int cbKey)
        {
        long lTicket = PersistentTicket.encode(nFileId, lOffset, cbKey);

        assertEquals(nFileId, PersistentTicket.extractFileId(lTicket));
        assertEquals(lOffset, PersistentTicket.extractEntryOffset(lTicket));
        assertEquals(cbKey, PersistentTicket.extractKeyLength(lTicket));
        assertTrue(PersistentTicket.isValid(lTicket));
        }
    }
