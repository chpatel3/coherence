/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.ReadBuffer;

import com.tangosol.util.Binary;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.zip.CRC32;

/**
 * Package-local utility methods for journal binary parsing.
 *
 * @author rl  2026.03.05
 */
final class JournalUtils
    {
    // ----- inner interface: JournalEntryVisitor --------------------------

    /**
     * Visitor of parsed journal entries.
     */
    interface JournalEntryVisitor
        {
        /**
         * Visit a parsed journal entry.
         *
         * @param nFileId  journal file id
         * @param ofEntry  byte offset of the entry within the file
         * @param entry    parsed entry
         *
         * @return {@code true} to continue scanning, {@code false} to stop
         */
        boolean visit(int nFileId, long ofEntry, JournalEntry entry);
        }

    /**
     * Hidden utility class constructor.
     */
    private JournalUtils()
        {
        }

    /**
     * Journal file name format.
     */
    static final String FILE_FORMAT = "journal-%06d.coh";

    /**
     * Journal file name pattern.
     */
    static final Pattern FILE_PATTERN = Pattern.compile("journal-(\\d{6})\\.coh");

    /**
     * Buffer size used for sequential journal replay scans.
     */
    static final int SCAN_BUFFER_SIZE = 256 * 1024;

    /**
     * Resolve journal file for id.
     *
     * @param dirPartition  partition directory
     * @param nFileId       file id
     *
     * @return journal file path
     */
    static File toJournalFile(File dirPartition, int nFileId)
        {
        return new File(dirPartition, String.format(FILE_FORMAT, nFileId));
        }

    /**
     * Discover existing journal file ids in a partition directory.
     *
     * @param dir  partition directory
     *
     * @return sorted journal file ids
     */
    static List<Integer> discoverFileIds(File dir)
        {
        File[] aFile = dir.listFiles();
        if (aFile == null || aFile.length == 0)
            {
            return Collections.emptyList();
            }

        List<Integer> listIds = new ArrayList<>();
        for (File file : aFile)
            {
            Matcher matcher = FILE_PATTERN.matcher(file.getName());
            if (matcher.matches())
                {
                listIds.add(Integer.parseInt(matcher.group(1)));
                }
            }

        Collections.sort(listIds);
        return listIds;
        }

    /**
     * Sequentially scan entries in a journal file.
     *
     * @param file      journal file
     * @param nFileId   file id
     * @param ofStart   starting offset
     * @param visitor   entry visitor
     *
     * @throws IOException on I/O failure
     */
    static void scanFile(File file, int nFileId, long ofStart, JournalEntryVisitor visitor)
            throws IOException
        {
        scanFile(file, nFileId, ofStart, false, visitor);
        }

    /**
     * Sequentially scan entries in a journal file.
     *
     * @param file           journal file
     * @param nFileId        file id
     * @param ofStart        starting offset
     * @param fIncludeValue  {@code true} to include STORE value bytes
     * @param visitor        entry visitor
     *
     * @throws IOException on I/O failure
     */
    static void scanFile(File file, int nFileId, long ofStart, boolean fIncludeValue, JournalEntryVisitor visitor)
            throws IOException
        {
        if (file == null)
            {
            throw new IllegalArgumentException("file cannot be null");
            }
        if (visitor == null)
            {
            throw new IllegalArgumentException("visitor cannot be null");
            }

        long  of         = Math.max(0L, ofStart);
        CRC32 crc        = new CRC32();
        byte[] abBuffer  = new byte[SCAN_BUFFER_SIZE];
        int    ofBuffer  = 0;
        int    cbBuffer  = 0;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r"))
            {
            long cbFile = raf.length();

            raf.seek(of);

            while (of < cbFile)
                {
                if (cbBuffer - ofBuffer < JournalEntry.ENTRY_LEN_SIZE)
                    {
                    cbBuffer = refillScanBuffer(raf, abBuffer, ofBuffer, cbBuffer);
                    ofBuffer = 0;
                    }

                if (cbBuffer < JournalEntry.ENTRY_LEN_SIZE)
                    {
                    visitor.visit(nFileId, of,
                            new JournalEntry((byte) 0, null, null, cbFile - of, -1, -1, false));
                    break;
                    }
                long lEntryLen = readIntBE(abBuffer, ofBuffer) & 0xFFFFFFFFL;

                if (lEntryLen <= 0L || lEntryLen > Integer.MAX_VALUE)
                    {
                    visitor.visit(nFileId, of,
                            new JournalEntry((byte) 0, null, null, 0L, -1, -1, false));
                    break;
                    }

                int  cbEntry    = (int) lEntryLen;
                long ofEntryEnd = of + cbEntry;
                if (ofEntryEnd > cbFile)
                    {
                    visitor.visit(nFileId, of,
                            new JournalEntry((byte) 0, null, null, cbFile - of, -1, -1, false));
                    break;
                    }

                JournalEntry entry;
                if (cbEntry <= cbBuffer - ofBuffer)
                    {
                    entry = parseBufferedEntry(abBuffer, ofBuffer, cbEntry, fIncludeValue, crc);
                    ofBuffer += cbEntry;
                    }
                else if (cbEntry <= abBuffer.length)
                    {
                    cbBuffer = refillScanBuffer(raf, abBuffer, ofBuffer, cbBuffer);
                    ofBuffer = 0;
                    cbBuffer = ensureBufferedEntry(raf, abBuffer, cbBuffer, cbEntry);
                    if (cbBuffer < cbEntry)
                        {
                        visitor.visit(nFileId, of,
                                new JournalEntry((byte) 0, null, null, cbFile - of, -1, -1, false));
                        break;
                        }

                    entry = parseBufferedEntry(abBuffer, 0, cbEntry, fIncludeValue, crc);
                    ofBuffer = cbEntry;
                    }
                else
                    {
                    byte[] abLarge = new byte[cbEntry];
                    int    cbTail  = cbBuffer - ofBuffer;

                    System.arraycopy(abBuffer, ofBuffer, abLarge, 0, cbTail);
                    raf.readFully(abLarge, cbTail, cbEntry - cbTail);
                    entry = parseBufferedEntry(abLarge, 0, cbEntry, fIncludeValue, crc);
                    ofBuffer = 0;
                    cbBuffer = 0;
                    }

                if (!visitor.visit(nFileId, of, entry))
                    {
                    break;
                    }

                of += cbEntry;
                }
            }
        }

    /**
     * Refill the sequential scan buffer, preserving any unread bytes.
     *
     * @param raf       open journal file
     * @param abBuffer  scan buffer
     * @param ofBuffer  first unread byte offset
     * @param cbBuffer  number of buffered bytes
     *
     * @return total buffered bytes after refill
     *
     * @throws IOException on I/O failure
     */
    private static int refillScanBuffer(RandomAccessFile raf, byte[] abBuffer, int ofBuffer, int cbBuffer)
            throws IOException
        {
        int cbUnread = cbBuffer - ofBuffer;
        if (cbUnread > 0 && ofBuffer > 0)
            {
            System.arraycopy(abBuffer, ofBuffer, abBuffer, 0, cbUnread);
            }

        int cbRead = raf.read(abBuffer, cbUnread, abBuffer.length - cbUnread);
        return cbRead < 0 ? cbUnread : cbUnread + cbRead;
        }

    /**
     * Ensure the scan buffer contains the full current entry.
     *
     * @param raf        open journal file
     * @param abBuffer   scan buffer
     * @param cbBuffer   currently buffered bytes
     * @param cbEntry    required entry length
     *
     * @return buffered bytes after refills
     *
     * @throws IOException on I/O failure
     */
    private static int ensureBufferedEntry(RandomAccessFile raf, byte[] abBuffer, int cbBuffer, int cbEntry)
            throws IOException
        {
        while (cbBuffer < cbEntry)
            {
            int cbRead = raf.read(abBuffer, cbBuffer, abBuffer.length - cbBuffer);
            if (cbRead < 0)
                {
                break;
                }
            cbBuffer += cbRead;
            }
        return cbBuffer;
        }

    /**
     * Parse a journal entry fully buffered in memory.
     *
     * @param abEntry        entry bytes
     * @param ofEntry        entry offset within the byte array
     * @param cbEntry        entry length
     * @param fIncludeValue  {@code true} to include STORE value bytes
     * @param crc            reusable CRC instance
     *
     * @return parsed journal entry
     */
    private static JournalEntry parseBufferedEntry(byte[] abEntry, int ofEntry, int cbEntry, boolean fIncludeValue,
            CRC32 crc)
        {
        byte    bType         = abEntry[ofEntry + JournalEntry.OFFSET_TYPE];
        Binary  binKey        = null;
        Binary  binValue      = null;
        int     nValLenOffset = -1;
        int     cbValue       = -1;
        boolean fValid        = true;
        int     ofPayloadEnd;

        switch (bType)
            {
            case JournalEntry.TYPE_STORE:
                {
                if (cbEntry < JournalEntry.STORE_HEADER_SIZE + JournalEntry.CRC_SIZE)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, -1, -1, false);
                    }

                int cbKey = readIntBE(abEntry, ofEntry + JournalEntry.OFFSET_STORE_KEY_LEN);
                cbValue = readIntBE(abEntry, ofEntry + JournalEntry.OFFSET_STORE_VALUE_LEN);
                if (cbKey < 0 || cbValue < 0)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, -1, cbValue, false);
                    }

                int ofKey   = ofEntry + JournalEntry.STORE_HEADER_SIZE;
                int ofValue = ofKey + cbKey;
                if ((long) ofValue + cbValue + JournalEntry.CRC_SIZE > ofEntry + cbEntry)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, JournalEntry.computeValLenOffset(),
                            cbValue, false);
                    }

                // Binary(byte[], int, int) copies the data; safe to reuse the buffer
                binKey = new Binary(abEntry, ofKey, cbKey);
                if (fIncludeValue)
                    {
                    // Binary(byte[], int, int) copies the data; safe to reuse the buffer
                    binValue = new Binary(abEntry, ofValue, cbValue);
                    }

                nValLenOffset = JournalEntry.computeValLenOffset();
                ofPayloadEnd  = ofValue + cbValue;
                break;
                }

            case JournalEntry.TYPE_ERASE:
                {
                if (cbEntry < JournalEntry.HEADER_SIZE + JournalEntry.KEY_LEN_SIZE + JournalEntry.CRC_SIZE)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, -1, -1, false);
                    }

                int cbKey = readIntBE(abEntry, ofEntry + JournalEntry.HEADER_SIZE);
                if (cbKey < 0)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, -1, -1, false);
                    }

                int ofKey = ofEntry + JournalEntry.HEADER_SIZE + JournalEntry.KEY_LEN_SIZE;
                if ((long) ofKey + cbKey + JournalEntry.CRC_SIZE > ofEntry + cbEntry)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, -1, -1, false);
                    }

                // Binary(byte[], int, int) copies the data; safe to reuse the buffer
                binKey = new Binary(abEntry, ofKey, cbKey);
                ofPayloadEnd = ofKey + cbKey;
                break;
                }

            case JournalEntry.TYPE_ERASE_EXTENT:
                ofPayloadEnd = ofEntry + JournalEntry.HEADER_SIZE;
                if (ofPayloadEnd + JournalEntry.CRC_SIZE > ofEntry + cbEntry)
                    {
                    return new JournalEntry(bType, null, null, cbEntry, -1, -1, false);
                    }
                break;

            default:
                return new JournalEntry(bType, null, null, cbEntry, -1, -1, false);
            }

        crc.reset();
        crc.update(abEntry, ofEntry + JournalEntry.ENTRY_LEN_SIZE, ofPayloadEnd - ofEntry - JournalEntry.ENTRY_LEN_SIZE);
        int nStoredCrc = readIntBE(abEntry, ofPayloadEnd);
        fValid = nStoredCrc == (int) -crc.getValue() - 1;

        return new JournalEntry(bType, binKey, binValue, cbEntry, nValLenOffset, cbValue, fValid);
        }

    /**
     * Read the value referenced by the supplied STORE ticket directly from the
     * journal file.
     *
     * @param dirPartition  the extent directory
     * @param lTicket       STORE ticket
     *
     * @return the STORE value
     *
     * @throws IOException on I/O failure
     */
    static Binary readValue(File dirPartition, long lTicket)
            throws IOException
        {
        return readValue(dirPartition, lTicket, null);
        }

    /**
     * Read the value referenced by the supplied STORE ticket directly from the
     * journal file using an optional reader cache.
     *
     * @param dirPartition  the extent directory
     * @param lTicket       STORE ticket
     * @param mapReaders    optional open-file cache
     *
     * @return the STORE value
     *
     * @throws IOException on I/O failure
     */
    static Binary readValue(File dirPartition, long lTicket, java.util.Map<Integer, RandomAccessFile> mapReaders)
            throws IOException
        {
        if (dirPartition == null)
            {
            throw new IllegalArgumentException("partition directory cannot be null");
            }
        if (lTicket == PersistentTicket.TICKET_NONE || !PersistentTicket.isValid(lTicket))
            {
            throw new IllegalArgumentException("invalid ticket: " + lTicket);
            }

        int               nFileId = PersistentTicket.extractFileId(lTicket);
        RandomAccessFile  raf     = null;
        boolean           fClose  = false;

        try
            {
            if (mapReaders == null)
                {
                raf    = new RandomAccessFile(toJournalFile(dirPartition, nFileId), "r");
                fClose = true;
                }
            else
                {
                raf = mapReaders.get(nFileId);
                if (raf == null)
                    {
                    raf = new RandomAccessFile(toJournalFile(dirPartition, nFileId), "r");
                    mapReaders.put(nFileId, raf);
                    }
                }

            long               ofEntry  = PersistentTicket.extractEntryOffset(lTicket);
            int                cbKey    = PersistentTicket.extractKeyLength(lTicket);
            long packedMetadata = readPackedStoreMetadata(raf, lTicket, ofEntry, cbKey);
            long ofValue        = ofEntry + JournalEntry.computeValueOffset(cbKey);
            int  cbValue        = extractValueSize(packedMetadata);

            byte[] abValue = new byte[cbValue];
            raf.seek(ofValue);
            raf.readFully(abValue);

            return new Binary(abValue);
            }
        finally
            {
            if (fClose && raf != null)
                {
                raf.close();
                }
            }
        }

    /**
     * Create a buffered late-value reader for the specified journal file.
     *
     * @param dirPartition  the extent directory
     * @param nFileId       journal file id
     *
     * @return a buffered late-value reader
     *
     * @throws IOException on I/O failure
     */
    static BufferedValueReader openBufferedValueReader(File dirPartition, int nFileId)
            throws IOException
        {
        return new BufferedValueReader(dirPartition, nFileId, SCAN_BUFFER_SIZE);
        }

    /**
     * Read STORE entry metadata directly from the journal entry header.
     *
     * @param raf            open journal file
     * @param lTicket        STORE ticket
     * @param ofEntry        entry offset
     * @param cbExpectedKey  expected key length from the ticket
     *
     * @return the decoded STORE entry metadata
     *
     * @throws IOException on I/O failure
     */
    static long readPackedStoreMetadata(RandomAccessFile raf, long lTicket, long ofEntry, int cbExpectedKey)
            throws IOException
        {
        byte[] abHeader = new byte[JournalEntry.STORE_HEADER_SIZE];
        raf.seek(ofEntry);
        raf.readFully(abHeader);

        int cbEntry = readIntBE(abHeader, 0);
        if (cbEntry <= 0)
            {
            throw new IOException("invalid entry length at ticket " + PersistentTicket.format(lTicket)
                    + ": " + cbEntry);
            }

        if (abHeader[JournalEntry.OFFSET_TYPE] != JournalEntry.TYPE_STORE)
            {
            throw new IOException("ticket does not reference a STORE entry: " + PersistentTicket.format(lTicket));
            }

        int cbKey = readIntBE(abHeader, JournalEntry.OFFSET_STORE_KEY_LEN);
        if (cbKey != cbExpectedKey)
            {
            throw new IOException("ticket key length mismatch for ticket " + PersistentTicket.format(lTicket)
                    + "; expected=" + cbExpectedKey + ", actual=" + cbKey);
            }

        int cbValue = readIntBE(abHeader, JournalEntry.OFFSET_STORE_VALUE_LEN);
        if (cbValue < 0)
            {
            throw new IOException("invalid value length at ticket " + PersistentTicket.format(lTicket)
                    + ": " + cbValue);
            }

        return packStoreEntryMetadata(cbEntry, cbValue);
        }

    /**
     * Read an {@code int} at the specified offset.
     *
     * @param buffer  the source buffer
     * @param of      the offset
     *
     * @return the int value
     */
    static int readInt(ReadBuffer buffer, int of)
        {
        return ((buffer.byteAt(of)     & 0xFF) << 24)
             | ((buffer.byteAt(of + 1) & 0xFF) << 16)
             | ((buffer.byteAt(of + 2) & 0xFF) << 8)
             |  (buffer.byteAt(of + 3) & 0xFF);
        }

    /**
     * Read a {@code long} at the specified offset.
     *
     * @param buffer  the source buffer
     * @param of      the offset
     *
     * @return the long value
     */
    static long readLong(ReadBuffer buffer, int of)
        {
        return (((long) readInt(buffer, of)) << 32)
                | (((long) readInt(buffer, of + 4)) & 0xFFFFFFFFL);
        }

    /**
     * Read an {@code int} at the specified array offset in big-endian form.
     *
     * @param ab  source array
     * @param of  offset
     *
     * @return int value
     */
    static int readIntBE(byte[] ab, int of)
        {
        return ((ab[of]     & 0xFF) << 24)
             | ((ab[of + 1] & 0xFF) << 16)
             | ((ab[of + 2] & 0xFF) << 8)
             |  (ab[of + 3] & 0xFF);
        }

    /**
     * Read a {@code long} at the specified array offset in big-endian form.
     *
     * @param ab  source array
     * @param of  offset
     *
     * @return long value
     */
    static long readLongBE(byte[] ab, int of)
        {
        return (((long) readIntBE(ab, of)) << 32)
                | (((long) readIntBE(ab, of + 4)) & 0xFFFFFFFFL);
        }


    /**
     * Pack STORE entry metadata into a single long.
     *
     * @param cbEntry  aligned entry size
     * @param cbValue  value size
     *
     * @return packed metadata
     */
    static long packStoreEntryMetadata(int cbEntry, int cbValue)
        {
        return (((long) cbEntry) << 32) | (((long) cbValue) & 0xFFFFFFFFL);
        }

    /**
     * Extract the aligned STORE entry size from packed metadata.
     *
     * @param lPacked  packed metadata
     *
     * @return aligned entry size
     */
    static int extractEntrySize(long lPacked)
        {
        return (int) (lPacked >>> 32);
        }

    /**
     * Extract the STORE value size from packed metadata.
     *
     * @param lPacked  packed metadata
     *
     * @return STORE value size
     */
    static int extractValueSize(long lPacked)
        {
        return (int) lPacked;
        }

    // ----- inner class: BufferedValueReader ------------------------------

    /**
     * Recovery-only buffered reader for monotonically increasing STORE tickets
     * within a single journal file.
     */
    static class BufferedValueReader
            implements AutoCloseable
        {
        /**
         * Create a buffered value reader.
         *
         * @param dirPartition  the extent directory
         * @param nFileId       journal file id
         * @param cbBuffer      read buffer size
         *
         * @throws IOException on I/O failure
         */
        BufferedValueReader(File dirPartition, int nFileId, int cbBuffer)
                throws IOException
            {
            if (dirPartition == null)
                {
                throw new IllegalArgumentException("partition directory cannot be null");
                }
            if (cbBuffer <= 0)
                {
                throw new IllegalArgumentException("buffer size must be positive: " + cbBuffer);
                }

            f_nFileId  = nFileId;
            f_raf      = new RandomAccessFile(toJournalFile(dirPartition, nFileId), "r");
            f_abBuffer = new byte[cbBuffer];
            f_abHeader = new byte[JournalEntry.STORE_HEADER_SIZE];
            }

        /**
         * Resolve the STORE value referenced by the supplied ticket.
         *
         * @param lTicket  STORE ticket in this file
         *
         * @return the STORE value
         *
         * @throws IOException on I/O failure
         */
        Binary readValue(long lTicket)
                throws IOException
            {
            if (lTicket == PersistentTicket.TICKET_NONE || !PersistentTicket.isValid(lTicket))
                {
                throw new IllegalArgumentException("invalid ticket: " + lTicket);
                }

            int nFileId = PersistentTicket.extractFileId(lTicket);
            if (nFileId != f_nFileId)
                {
                throw new IllegalArgumentException("ticket " + PersistentTicket.format(lTicket)
                        + " does not belong to file " + f_nFileId);
                }

            long ofEntry = PersistentTicket.extractEntryOffset(lTicket);
            int  cbKey   = PersistentTicket.extractKeyLength(lTicket);
            int  cbValue = readValueLength(lTicket, ofEntry, cbKey);

            if (cbValue == 0)
                {
                return Binary.NO_BINARY;
                }

            long ofValue = ofEntry + JournalEntry.computeValueOffset(cbKey);
            if (cbValue <= f_abBuffer.length && ensureWindow(ofValue, cbValue))
                {
                // Binary(byte[], int, int) copies the data; safe to reuse the buffer
                return new Binary(f_abBuffer, toBufferOffset(ofValue), cbValue);
                }

            byte[] abValue = new byte[cbValue];
            f_raf.seek(ofValue);
            f_raf.readFully(abValue);
            return new Binary(abValue);
            }

        @Override
        public void close()
                throws IOException
            {
            f_raf.close();
            }

        /**
         * Read and validate STORE header metadata for the supplied ticket.
         *
         * @param lTicket  STORE ticket
         * @param ofEntry  entry offset
         * @param cbKey    expected key length
         *
         * @return STORE value length
         *
         * @throws IOException on I/O failure
         */
        private int readValueLength(long lTicket, long ofEntry, int cbKey)
                throws IOException
            {
            byte[] abHeader = f_abHeader;
            int    ofHeader = 0;

            if (ensureWindow(ofEntry, JournalEntry.STORE_HEADER_SIZE))
                {
                abHeader = f_abBuffer;
                ofHeader = toBufferOffset(ofEntry);
                }
            else
                {
                f_raf.seek(ofEntry);
                f_raf.readFully(f_abHeader);
                }

            int cbEntry = readIntBE(abHeader, ofHeader);
            if (cbEntry <= 0)
                {
                throw new IOException("invalid entry length at ticket " + PersistentTicket.format(lTicket)
                        + ": " + cbEntry);
                }

            if (abHeader[ofHeader + JournalEntry.OFFSET_TYPE] != JournalEntry.TYPE_STORE)
                {
                throw new IOException("ticket does not reference a STORE entry: "
                        + PersistentTicket.format(lTicket));
                }

            int cbActualKey = readIntBE(abHeader, ofHeader + JournalEntry.OFFSET_STORE_KEY_LEN);
            if (cbActualKey != cbKey)
                {
                throw new IOException("ticket key length mismatch for ticket " + PersistentTicket.format(lTicket)
                        + "; expected=" + cbKey + ", actual=" + cbActualKey);
                }

            int cbValue = readIntBE(abHeader, ofHeader + JournalEntry.OFFSET_STORE_VALUE_LEN);
            if (cbValue < 0)
                {
                throw new IOException("invalid value length at ticket " + PersistentTicket.format(lTicket)
                        + ": " + cbValue);
                }

            return cbValue;
            }

        /**
         * Ensure the current window contains the specified byte range.
         *
         * @param ofNeeded  range start offset
         * @param cbNeeded  range length
         *
         * @return {@code true} if the range is buffered
         *
         * @throws IOException on I/O failure
         */
        private boolean ensureWindow(long ofNeeded, int cbNeeded)
                throws IOException
            {
            if (cbNeeded > f_abBuffer.length)
                {
                return false;
                }

            if (m_ofBuffer >= 0L
                    && ofNeeded >= m_ofBuffer
                    && ofNeeded + cbNeeded <= m_ofBuffer + m_cbBuffer)
                {
                return true;
                }

            f_raf.seek(ofNeeded);
            m_cbBuffer = f_raf.read(f_abBuffer, 0, f_abBuffer.length);
            if (m_cbBuffer < 0)
                {
                m_cbBuffer = 0;
                }
            m_ofBuffer = ofNeeded;

            return ofNeeded + cbNeeded <= m_ofBuffer + m_cbBuffer;
            }

        /**
         * Convert file offset to in-buffer offset.
         *
         * @param of  file offset
         *
         * @return in-buffer offset
         */
        private int toBufferOffset(long of)
            {
            return (int) (of - m_ofBuffer);
            }

        /**
         * Journal file id.
         */
        private final int f_nFileId;

        /**
         * Journal file reader.
         */
        private final RandomAccessFile f_raf;

        /**
         * Reusable buffered read window.
         */
        private final byte[] f_abBuffer;

        /**
         * Reusable STORE header scratch buffer.
         */
        private final byte[] f_abHeader;

        /**
         * Current buffered window start offset.
         */
        private long m_ofBuffer = -1L;

        /**
         * Current buffered byte count.
         */
        private int m_cbBuffer;
        }
    }
