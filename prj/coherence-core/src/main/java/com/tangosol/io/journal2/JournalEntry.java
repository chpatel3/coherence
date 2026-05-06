/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.ReadBuffer;

import com.tangosol.util.Base;
import com.tangosol.util.Binary;

import java.nio.ByteBuffer;

/**
 * Utility for constructing and parsing unified journal entries.
 *
 * @author rl  2026.03.04
 * @since 26.04
 */
public class JournalEntry
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Construct a parsed {@link JournalEntry}.
     *
     * @param bType          the entry type
     * @param binKey         the optional key
     * @param binValue       the optional value
     * @param lEntryLen      the full entry length, including padding
     * @param nValLenOffset  the offset of the value-length field or -1
     * @param fValid         {@code true} if CRC validation passed
     */
    JournalEntry(byte bType, Binary binKey, Binary binValue, long lEntryLen, int nValLenOffset, int cbValue,
            boolean fValid)
        {
        m_bType         = bType;
        m_binKey        = binKey;
        m_binValue      = binValue;
        m_lEntryLen     = lEntryLen;
        m_nValLenOffset = nValLenOffset;
        m_cbValue       = cbValue;
        m_fValid        = fValid;
        }


    // ----- construction helpers ------------------------------------------

    /**
     * Create a STORE entry.
     * <p>
     * This method is retained for unit-test and parsing scenarios.
     *
     * @param bufKey     the key
     * @param bufValue   the value
     *
     * @return a {@link ByteBuffer} ready for writing to a journal file
     */
    public static ByteBuffer createStoreEntry(ReadBuffer bufKey, ReadBuffer bufValue)
        {
        if (bufKey == null)
            {
            throw new IllegalArgumentException("key cannot be null");
            }
        if (bufValue == null)
            {
            throw new IllegalArgumentException("value cannot be null");
            }

        int cbKey     = bufKey.length();
        int cbValue   = bufValue.length();
        int cbPayload = TYPE_SIZE + KEY_LEN_SIZE + VAL_LEN_SIZE + cbKey + cbValue;

        ByteBuffer buffer = instantiateEntryBuffer(cbPayload + CRC_SIZE);
        buffer.putInt(buffer.capacity());
        buffer.put(TYPE_STORE);
        buffer.putInt(cbKey);
        buffer.putInt(cbValue);
        bufKey.writeTo(buffer);
        bufValue.writeTo(buffer);

        int nCrc = Base.toCrc(buffer.array(), ENTRY_LEN_SIZE, cbPayload, 0);
        buffer.putInt(nCrc);
        return finalizeEntryBuffer(buffer);
        }

    /**
     * Compute the offset of the {@code val_len} field for a STORE entry.
     *
     * @return offset of {@code val_len} relative to the entry start
     */
    public static int computeValLenOffset()
        {
        return OFFSET_STORE_VALUE_LEN;
        }

    /**
     * Compute the offset of the STORE value bytes.
     *
     * @param cbKey  key length in bytes
     *
     * @return offset of STORE value bytes relative to the entry start
     */
    public static int computeValueOffset(int cbKey)
        {
        if (cbKey < 0)
            {
            throw new IllegalArgumentException("key length cannot be negative: " + cbKey);
            }

        return STORE_HEADER_SIZE + cbKey;
        }

    /**
     * Create an ERASE entry.
     * <p>
     * This method is retained for unit-test and parsing scenarios.
     *
     * @param bufKey     the key
     *
     * @return a {@link ByteBuffer} ready for writing to a journal file
     */
    public static ByteBuffer createEraseEntry(ReadBuffer bufKey)
        {
        if (bufKey == null)
            {
            throw new IllegalArgumentException("key cannot be null");
            }

        int cbKey     = bufKey.length();
        int cbPayload = TYPE_SIZE + KEY_LEN_SIZE + cbKey;

        ByteBuffer buffer = instantiateEntryBuffer(cbPayload + CRC_SIZE);
        buffer.putInt(buffer.capacity());
        buffer.put(TYPE_ERASE);
        buffer.putInt(cbKey);
        bufKey.writeTo(buffer);

        int nCrc = Base.toCrc(buffer.array(), ENTRY_LEN_SIZE, cbPayload, 0);
        buffer.putInt(nCrc);
        return finalizeEntryBuffer(buffer);
        }

    /**
     * Create an ERASE_EXTENT entry.
     * <p>
     * This method is retained for unit-test and parsing scenarios.
     *
     * @return a {@link ByteBuffer} ready for writing to a journal file
     */
    public static ByteBuffer createEraseExtentEntry()
        {
        int cbPayload = TYPE_SIZE;

        ByteBuffer buffer = instantiateEntryBuffer(cbPayload + CRC_SIZE);
        buffer.putInt(buffer.capacity());
        buffer.put(TYPE_ERASE_EXTENT);

        int nCrc = Base.toCrc(buffer.array(), ENTRY_LEN_SIZE, cbPayload, 0);
        buffer.putInt(nCrc);
        return finalizeEntryBuffer(buffer);
        }


    // ----- parsing --------------------------------------------------------

    /**
     * Parse a journal entry at the specified buffer offset.
     *
     * @param buffer  the source buffer
     * @param of      the offset of the entry in the buffer
     *
     * @return the parsed {@link JournalEntry}
     */
    public static JournalEntry parseEntry(ReadBuffer buffer, int of)
        {
        return parseEntryInternal(buffer, of, true);
        }

    /**
     * Parse a journal entry header at the specified buffer offset.
     * <p>
     * For STORE entries, this method parses all metadata while intentionally
     * skipping value extraction ({@link #getValue()} will return {@code null}).
     *
     * @param buffer  the source buffer
     * @param of      the offset of the entry in the buffer
     *
     * @return the parsed {@link JournalEntry} header
     */
    public static JournalEntry parseEntryHeader(ReadBuffer buffer, int of)
        {
        return parseEntryInternal(buffer, of, false);
        }

    /**
     * Parse a journal entry, optionally including value extraction.
     *
     * @param buffer         the source buffer
     * @param of             the offset of the entry in the buffer
     * @param fIncludeValue  {@code true} to extract STORE value bytes
     *
     * @return the parsed {@link JournalEntry}
     */
    private static JournalEntry parseEntryInternal(ReadBuffer buffer, int of, boolean fIncludeValue)
        {
        if (buffer == null)
            {
            throw new IllegalArgumentException("buffer cannot be null");
            }

        int cbBuffer = buffer.length();
        if (of < 0 || of + MIN_ENTRY_SIZE > cbBuffer)
            {
            throw new IllegalArgumentException("entry offset out of range: " + of);
            }

        long lEntryLen = JournalUtils.readInt(buffer, of) & 0xFFFFFFFFL;
        if (lEntryLen < MIN_ENTRY_SIZE || lEntryLen > Integer.MAX_VALUE)
            {
            throw new IllegalArgumentException("invalid entry length: " + lEntryLen);
            }

        int cbEntry = (int) lEntryLen;
        if (of + cbEntry > cbBuffer)
            {
            throw new IllegalArgumentException("truncated entry: offset=" + of + ", entry-length=" + lEntryLen);
            }

        byte bType        = buffer.byteAt(of + OFFSET_TYPE);
        int    ofCursor       = of + HEADER_SIZE;
        int    ofPayloadEnd;
        int    nValLenOffset  = -1;
        Binary binKey         = null;
        Binary binValue       = null;
        int    cbValue        = -1;

        switch (bType)
            {
            case TYPE_STORE:
                {
                int cbKey = JournalUtils.readInt(buffer, ofCursor);
                ofCursor += KEY_LEN_SIZE;
                cbValue = JournalUtils.readInt(buffer, ofCursor);
                ofCursor += VAL_LEN_SIZE;
                ensureLength("key", cbKey, ofCursor, of + cbEntry);
                ensureLength("value", cbValue, ofCursor + cbKey, of + cbEntry);

                nValLenOffset = OFFSET_STORE_VALUE_LEN;
                binKey = buffer.toBinary(ofCursor, cbKey);
                ofCursor += cbKey;

                if (fIncludeValue)
                    {
                    binValue = buffer.toBinary(ofCursor, cbValue);
                    }
                ofPayloadEnd  = ofCursor + cbValue;
                break;
                }

            case TYPE_ERASE:
                {
                int cbKey = JournalUtils.readInt(buffer, ofCursor);
                ofCursor += KEY_LEN_SIZE;
                ensureLength("key", cbKey, ofCursor, of + cbEntry);

                binKey       = buffer.toBinary(ofCursor, cbKey);
                ofPayloadEnd = ofCursor + cbKey;
                break;
                }

            case TYPE_ERASE_EXTENT:
                ofPayloadEnd = ofCursor;
                break;

            default:
                throw new IllegalArgumentException("unknown journal entry type: " + bType);
            }

        if (ofPayloadEnd + CRC_SIZE > of + cbEntry)
            {
            throw new IllegalArgumentException("truncated crc field");
            }

        int ofCrcStart   = of + ENTRY_LEN_SIZE;
        int nExpectedCrc = JournalUtils.readInt(buffer, ofPayloadEnd);
        int nActual      = Base.toCrc(buffer, ofCrcStart, ofPayloadEnd, 0);
        boolean fValid   = nActual == nExpectedCrc;

        return new JournalEntry(bType, binKey, binValue, lEntryLen, nValLenOffset, cbValue, fValid);
        }


    // ----- accessors ------------------------------------------------------

    /**
     * Return the entry type.
     *
     * @return the entry type
     */
    public byte getType()
        {
        return m_bType;
        }

    /**
     * Return the key or {@code null} for {@link #TYPE_ERASE_EXTENT}.
     *
     * @return the key or {@code null}
     */
    public Binary getKey()
        {
        return m_binKey;
        }

    /**
     * Return the value or {@code null} for non-STORE entries.
     *
     * @return the value or {@code null}
     */
    public Binary getValue()
        {
        return m_binValue;
        }

    /**
     * Return full entry length including padding.
     *
     * @return full entry length
     */
    public long getEntryLen()
        {
        return m_lEntryLen;
        }

    /**
     * Return offset of value-length field within this entry.
     * <p>
     * The returned value is relative to the start of this entry, not an
     * absolute file offset.
     *
     * @return value-length field offset, or {@code -1}
     */
    public int getValLenOffset()
        {
        return m_nValLenOffset;
        }

    /**
     * Return STORE value length, or {@code -1} for non-STORE entries.
     *
     * @return STORE value length, or {@code -1}
     */
    public int getValueLength()
        {
        return m_cbValue;
        }

    /**
     * Return {@code true} iff CRC validation succeeded.
     *
     * @return {@code true} iff CRC validation succeeded
     */
    public boolean isValid()
        {
        return m_fValid;
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Create a new entry buffer large enough for the specified payload.
     *
     * @param cbBody  entry body size (payload + crc)
     *
     * @return the allocated entry buffer
     */
    private static ByteBuffer instantiateEntryBuffer(int cbBody)
        {
        long lEntryRaw = ENTRY_LEN_SIZE + cbBody;
        long lEntry    = align(lEntryRaw, ENTRY_ALIGNMENT);
        if (lEntry > Integer.MAX_VALUE)
            {
            throw new IllegalArgumentException("entry exceeds maximum supported size: " + lEntry);
            }

        return ByteBuffer.allocate((int) lEntry);
        }

    /**
     * Finalize a newly written entry buffer for reading.
     *
     * @param buffer  the entry buffer
     *
     * @return the finalized buffer
     */
    private static ByteBuffer finalizeEntryBuffer(ByteBuffer buffer)
        {
        buffer.position(buffer.capacity());
        return (ByteBuffer) buffer.flip();
        }

    /**
     * Ensure the parsed field length is valid and in bounds.
     *
     * @param sName       field name
     * @param cb          field length
     * @param ofValue     field data offset
     * @param ofEntryEnd  end offset of the containing entry
     */
    private static void ensureLength(String sName, int cb, int ofValue, int ofEntryEnd)
        {
        if (cb < 0)
            {
            throw new IllegalArgumentException("negative " + sName + " length: " + cb);
            }

        if ((long) ofValue + cb > ofEntryEnd)
            {
            throw new IllegalArgumentException("truncated " + sName + " field; length=" + cb);
            }
        }

    /**
     * Align the specified value to the specified alignment.
     *
     * @param n          value to align
     * @param nAlignment alignment, power of two
     *
     * @return aligned value
     */
    private static long align(long n, int nAlignment)
        {
        long nMask = nAlignment - 1L;
        return (n + nMask) & ~nMask;
        }


    // ----- constants ------------------------------------------------------

    /**
     * Store entry type.
     */
    public static final byte TYPE_STORE = 0x01;

    /**
     * Erase entry type.
     */
    public static final byte TYPE_ERASE = 0x02;

    /**
     * Erase extent entry type.
     */
    public static final byte TYPE_ERASE_EXTENT = 0x03;

    /**
     * Entry length field size.
     */
    static final int ENTRY_LEN_SIZE = Integer.BYTES;

    /**
     * CRC field size.
     */
    static final int CRC_SIZE = 4;

    /**
     * Type field size.
     */
    private static final int TYPE_SIZE = 1;

    /**
     * Key length field size.
     */
    static final int KEY_LEN_SIZE = 4;

    /**
     * Value length field size.
     */
    static final int VAL_LEN_SIZE = 4;

    /**
     * Offset of length field in entry.
     */
    public static final int OFFSET_ENTRY_LEN = 0;

    /**
     * Offset of type field in entry.
     */
    public static final int OFFSET_TYPE = ENTRY_LEN_SIZE;

    /**
     * Offset of STORE key-length field in entry.
     */
    public static final int OFFSET_STORE_KEY_LEN = OFFSET_TYPE + TYPE_SIZE;

    /**
     * Offset of STORE value-length field in entry.
     */
    public static final int OFFSET_STORE_VALUE_LEN = OFFSET_STORE_KEY_LEN + KEY_LEN_SIZE;

    /**
     * Offset of STORE key bytes in entry.
     */
    public static final int OFFSET_STORE_KEY = OFFSET_STORE_VALUE_LEN + VAL_LEN_SIZE;

    /**
     * Header size including type.
     */
    public static final int HEADER_SIZE = OFFSET_TYPE + TYPE_SIZE;

    /**
     * Fixed STORE entry header size.
     */
    public static final int STORE_HEADER_SIZE = OFFSET_STORE_KEY;

    /**
     * Minimum entry size (ERASE_EXTENT without padding).
     */
    static final int MIN_ENTRY_SIZE = ENTRY_LEN_SIZE + TYPE_SIZE + CRC_SIZE;

    /**
     * Entry alignment in bytes.
     */
    static final int ENTRY_ALIGNMENT = 16;


    // ----- data members ---------------------------------------------------

    /**
     * Entry type.
     */
    private final byte m_bType;

    /**
     * Entry key.
     */
    private final Binary m_binKey;

    /**
     * Entry value.
     */
    private final Binary m_binValue;

    /**
     * Full entry length.
     */
    private final long m_lEntryLen;

    /**
     * Value-length field offset within entry.
     */
    private final int m_nValLenOffset;

    /**
     * STORE value length, or {@code -1}.
     */
    private final int m_cbValue;

    /**
     * Entry CRC validity.
     */
    private final boolean m_fValid;
    }
