/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

/**
 * Utility for encoding and decoding persistent journal tickets.
 *
 * @author rl  2026.03.04
 * @since 26.04
 */
public final class PersistentTicket
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Hidden utility class constructor.
     */
    private PersistentTicket()
        {
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Encode a persistent ticket.
     *
     * @param nFileId    the file identifier in the range [0, 1023]
     * @param lEntryOf   the 16-byte aligned entry offset
     * @param cbKey      the key length
     *
     * @return the encoded ticket
     */
    public static long encode(int nFileId, long lEntryOf, int cbKey)
        {
        if (nFileId < 0 || nFileId > MAX_FILE_ID)
            {
            throw new IllegalArgumentException("invalid file id: " + nFileId);
            }

        if (lEntryOf < 0L)
            {
            throw new IllegalArgumentException("entry offset must be non-negative: " + lEntryOf);
            }

        if ((lEntryOf & ENTRY_OFFSET_ALIGNMENT_MASK) != 0L)
            {
            throw new IllegalArgumentException("entry offset must be 16-byte aligned: " + lEntryOf);
            }

        long nEntryOffsetUnitsPlusOne = (lEntryOf >>> SHIFT_ENTRY_OFFSET_ALIGNMENT) + 1L;
        if ((nEntryOffsetUnitsPlusOne & ~ENTRY_OFFSET_UNITS_MASK) != 0L)
            {
            throw new IllegalArgumentException("entry offset exceeds supported range: " + lEntryOf);
            }

        if (cbKey < 0 || cbKey > MAX_KEY_LENGTH)
            {
            throw new IllegalArgumentException("invalid key length: " + cbKey);
            }

        return ((long) nFileId << SHIFT_FILE_ID)
                | (nEntryOffsetUnitsPlusOne << SHIFT_ENTRY_OFFSET_UNITS)
                | cbKey;
        }

    /**
     * Extract the file identifier from the ticket.
     *
     * @param lTicket  the encoded ticket
     *
     * @return the file identifier
     */
    public static int extractFileId(long lTicket)
        {
        return (int) ((lTicket & MASK_FILE_ID) >>> SHIFT_FILE_ID);
        }

    /**
     * Extract the entry byte offset from the ticket.
     *
     * @param lTicket  the encoded ticket
     *
     * @return the entry byte offset
     */
    public static long extractEntryOffset(long lTicket)
        {
        long nEntryOffsetUnitsPlusOne = (lTicket & MASK_ENTRY_OFFSET_UNITS) >>> SHIFT_ENTRY_OFFSET_UNITS;
        return nEntryOffsetUnitsPlusOne == 0L
                ? 0L
                : (nEntryOffsetUnitsPlusOne - 1L) << SHIFT_ENTRY_OFFSET_ALIGNMENT;
        }

    /**
     * Extract the key length from the ticket.
     *
     * @param lTicket  the encoded ticket
     *
     * @return the key length
     */
    public static int extractKeyLength(long lTicket)
        {
        return (int) (lTicket & MASK_KEY_LENGTH);
        }

    /**
     * Format a ticket for diagnostics.
     *
     * @param lTicket  the encoded ticket
     *
     * @return formatted ticket description
     */
    public static String format(long lTicket)
        {
        if (lTicket == TICKET_NONE)
            {
            return "[ticket=none]";
            }

        return "[fileId=" + extractFileId(lTicket)
                + ", offset=" + extractEntryOffset(lTicket)
                + ", keyLen=" + extractKeyLength(lTicket) + "]";
        }

    /**
     * Determine if a ticket is valid for persistent mode.
     *
     * @param lTicket  the encoded ticket
     *
     * @return true iff the ticket uses persistent encoding
     */
    public static boolean isValid(long lTicket)
        {
        return lTicket == TICKET_NONE
                || ((lTicket & MASK_ENTRY_OFFSET_UNITS) >>> SHIFT_ENTRY_OFFSET_UNITS) != 0L;
        }


    // ----- constants ------------------------------------------------------

    /**
     * Sentinel for no ticket.
     */
    public static final long TICKET_NONE = 0L;

    /**
     * File-id field width.
     */
    private static final int FILE_ID_BITS = 10;

    /**
     * Encoded aligned entry-offset field width.
     */
    private static final int ENTRY_OFFSET_UNITS_BITS = 28;

    /**
     * Key-length field width.
     */
    private static final int KEY_LENGTH_BITS = 26;

    /**
     * Maximum allowed file id.
     */
    public static final int MAX_FILE_ID = (1 << FILE_ID_BITS) - 1;

    /**
     * Maximum allowed key length.
     */
    public static final int MAX_KEY_LENGTH = (1 << KEY_LENGTH_BITS) - 1;

    /**
     * Shift for the file id field.
     */
    private static final int SHIFT_FILE_ID = ENTRY_OFFSET_UNITS_BITS + KEY_LENGTH_BITS;

    /**
     * Shift for the encoded aligned entry-offset units field.
     */
    private static final int SHIFT_ENTRY_OFFSET_UNITS = KEY_LENGTH_BITS;

    /**
     * Shift for the 16-byte entry alignment.
     */
    private static final int SHIFT_ENTRY_OFFSET_ALIGNMENT = 4;

    /**
     * Mask for file id bits.
     */
    private static final long MASK_FILE_ID = ((1L << FILE_ID_BITS) - 1L) << SHIFT_FILE_ID;

    /**
     * Mask for entry-offset units bits.
     */
    private static final long MASK_ENTRY_OFFSET_UNITS =
            ((1L << ENTRY_OFFSET_UNITS_BITS) - 1L) << SHIFT_ENTRY_OFFSET_UNITS;

    /**
     * Mask for key-length bits.
     */
    private static final long MASK_KEY_LENGTH = (1L << KEY_LENGTH_BITS) - 1L;

    /**
     * Maximum encoded aligned entry-offset units value.
     */
    private static final long ENTRY_OFFSET_UNITS_MASK = (1L << ENTRY_OFFSET_UNITS_BITS) - 1L;

    /**
     * Entry offset alignment mask.
     */
    private static final long ENTRY_OFFSET_ALIGNMENT_MASK = (1L << SHIFT_ENTRY_OFFSET_ALIGNMENT) - 1L;
    }
