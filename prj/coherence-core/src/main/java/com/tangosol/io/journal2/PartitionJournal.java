/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.ReadBuffer;

import com.tangosol.util.Binary;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.DataOutput;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.zip.CRC32;

/**
 * Per-partition journal file manager.
 *
 * @author rl  2026.03.05
 * @since 26.04
 */
public class PartitionJournal
    {
    // ----- inner class: AppendRequest ------------------------------------

    /**
     * Immutable append request used by batched journal appends.
     */
    public static class AppendRequest
        {
        /**
         * Create an append request.
         *
         * @param bType     entry type
         * @param bufKey    entry key, or {@code null}
         * @param bufValue  entry value, or {@code null}
         */
        public AppendRequest(byte bType, ReadBuffer bufKey, ReadBuffer bufValue)
            {
            f_bType    = bType;
            f_bufKey   = bufKey;
            f_bufValue = bufValue;
            }

        /**
         * Return entry type.
         *
         * @return entry type
         */
        public byte getType()
            {
            return f_bType;
            }

        /**
         * Return entry key.
         *
         * @return entry key, or {@code null}
         */
        public ReadBuffer getKey()
            {
            return f_bufKey;
            }

        /**
         * Return entry value.
         *
         * @return entry value, or {@code null}
         */
        public ReadBuffer getValue()
            {
            return f_bufValue;
            }

        /**
         * Entry type.
         */
        private final byte f_bType;

        /**
         * Entry key.
         */
        private final ReadBuffer f_bufKey;

        /**
         * Entry value.
         */
        private final ReadBuffer f_bufValue;
        }

    // ----- constructors ---------------------------------------------------

    /**
     * Construct a partition journal.
     *
     * @param dirPartition  partition directory
     * @param config        journal configuration
     */
    public PartitionJournal(File dirPartition, PartitionJournalConfig config)
        {
        if (dirPartition == null)
            {
            throw new IllegalArgumentException("partition directory cannot be null");
            }
        if (config == null)
            {
            throw new IllegalArgumentException("config cannot be null");
            }

        m_dirPartition = dirPartition;
        f_config       = config.validate();
        m_mapStoreEntryMetadata.defaultReturnValue(NO_PACKED_METADATA);
        }


    // ----- lifecycle ------------------------------------------------------

    /**
     * Open the journal and discover existing files.
     * <p>
     * On open, discovered files are initialized with {@code liveBytes=size}
     * until recovery computes accurate live-byte counts.
     *
     * @throws IOException on I/O failure
     */
    public synchronized void open()
            throws IOException
        {
        if (m_fOpen)
            {
            return;
            }

        if (!m_dirPartition.exists() && !m_dirPartition.mkdirs())
            {
            throw new IOException("unable to create partition directory: " + m_dirPartition);
            }

        m_mapFiles.clear();
        m_mapStoreEntryMetadata.clear();

        List<Integer> listIds = JournalUtils.discoverFileIds(m_dirPartition);
        for (int nFileId : listIds)
            {
            File      file   = JournalUtils.toJournalFile(m_dirPartition, nFileId);
            long      cbFile = file.length();
            // File state is transient and not encoded in the filename. If a
            // process crashes mid-transition (for example after marking a
            // file EVACUATING or GARBAGE but before discarding it), restart
            // rediscovers the file and treats it as FULL so recovery can
            // still replay it safely.
            FileState state  = FileState.FULL;

            m_mapFiles.put(nFileId, new JournalFileInfoInternal(nFileId, file, state, cbFile, cbFile));
            }

        if (listIds.isEmpty())
            {
            m_nCurrentFileId = 1;
            createAppendingFile(m_nCurrentFileId);
            }
        else
            {
            int nLast = listIds.get(listIds.size() - 1);
            JournalFileInfoInternal infoLast = m_mapFiles.get(nLast);
            if (infoLast.getSize() < getMaximumFileSize())
                {
                infoLast.setState(FileState.APPENDING);
                m_nCurrentFileId = nLast;
                }
            else
                {
                m_nCurrentFileId = nextFileId(nLast);
                createAppendingFile(m_nCurrentFileId);
                }
            }

        m_fOpen = true;
        }

    /**
     * Close the journal.
     */
    public synchronized void close()
        {
        closeFiles();

        m_mapFiles.clear();
        m_mapStoreEntryMetadata.clear();
        m_nCurrentFileId = 0;
        m_fOpen = false;
        }

    /**
     * Close this journal for a directory move while retaining in-memory file
     * state for a subsequent reopen.
     */
    public synchronized void closeForMove()
        {
        ensureOpen();

        // Intentionally retain the per-ticket metadata cache across a move.
        // Tickets still identify the same logical entries, and the underlying
        // file contents are unchanged; only the parent directory path changes.
        // Reopen-after-move remaps file handles to the new location, so the
        // cached packed metadata remains valid.
        closeFiles();
        m_fOpen = false;
        }

    /**
     * Reopen this journal after its extent directory has been moved.
     *
     * @param dirPartition  the new extent directory
     *
     * @throws IOException on I/O failure
     */
    public synchronized void reopenAfterMove(File dirPartition)
            throws IOException
        {
        if (dirPartition == null)
            {
            throw new IllegalArgumentException("partition directory cannot be null");
            }
        if (m_fOpen)
            {
            throw new IllegalStateException("partition journal is already open");
            }

        if (!dirPartition.exists() && !dirPartition.mkdirs())
            {
            throw new IOException("unable to create partition directory: " + dirPartition);
            }

        m_dirPartition = dirPartition;

        if (m_mapFiles.isEmpty())
            {
            m_nCurrentFileId = 1;
            createAppendingFile(m_nCurrentFileId);
            }
        else
            {
            for (JournalFileInfoInternal info : m_mapFiles.values())
                {
                info.setFile(JournalUtils.toJournalFile(m_dirPartition, info.getFileId()));
                }
            }

        m_fOpen = true;
        }


    // ----- journal operations --------------------------------------------

    /**
     * Append an entry to the current appending file.
     *
     * @param bType      entry type
     * @param bufKey     entry key, or {@code null} for ERASE_EXTENT
     * @param bufValue   entry value, or {@code null} for ERASE/ERASE_EXTENT
     *
     * @return encoded persistent ticket
     *
     * @throws IOException on I/O failure
     */
    public synchronized long append(byte bType, ReadBuffer bufKey, ReadBuffer bufValue)
            throws IOException
        {
        ensureOpen();
        AppendPlan              plan = planAppend(bType, bufKey, bufValue);
        JournalFileInfoInternal info = ensureAppendingFile(plan.getEntrySize());
        long                    ofEntry = info.getSize();
        RandomAccessFile        raf = info.ensureAppendFile();

        raf.seek(ofEntry);
        return appendPrepared(info, raf, plan, ofEntry);
        }

    /**
     * Append a batch of entries while holding the journal monitor only once.
     *
     * @param listRequest  append requests to write, in order
     *
     * @return appended STORE tickets, or {@code TICKET_NONE} for non-STORE
     *         entries
     *
     * @throws IOException on I/O failure
     */
    public synchronized long[] appendBatch(List<AppendRequest> listRequest)
            throws IOException
        {
        ensureOpen();

        if (listRequest.isEmpty())
            {
            return NO_TICKETS;
            }

        long[] alTicket = new long[listRequest.size()];
        int    iRequest = 0;

        while (iRequest < listRequest.size())
            {
            AppendRequest          request = listRequest.get(iRequest);
            AppendPlan             plan    = planAppend(request.getType(), request.getKey(), request.getValue());
            JournalFileInfoInternal info   = ensureAppendingFile(plan.getEntrySize());

            if (!shouldBufferAppend(plan.getEntrySize()))
                {
                RandomAccessFile raf = info.ensureAppendFile();
                long             ofEntry = info.getSize();

                raf.seek(ofEntry);
                alTicket[iRequest++] = appendPrepared(info, raf, plan, ofEntry);
                continue;
                }

            int                  iChunkStart  = iRequest;
            long                 ofChunkStart = info.getSize();
            int                  cbChunk      = 0;
            int                  cbChunkLimit = getBufferedBatchChunkSize();
            byte[]               abChunk      = ensureBufferedEntryCapacity(cbChunkLimit);
            List<AppendPlan>     listPlan     = new ArrayList<>();
            RandomAccessFile     raf          = info.ensureAppendFile();

            while (iRequest < listRequest.size())
                {
                request = listRequest.get(iRequest);
                plan    = planAppend(request.getType(), request.getKey(), request.getValue());

                if (!shouldBufferAppend(plan.getEntrySize()))
                    {
                    break;
                    }

                if (ofChunkStart + cbChunk + plan.getEntrySize() > getMaximumFileSize()
                        || (cbChunk > 0 && cbChunk + plan.getEntrySize() > cbChunkLimit))
                    {
                    break;
                    }

                cbChunk = appendBufferedTo(abChunk, cbChunk, plan);
                listPlan.add(plan);
                iRequest++;
                }

            raf.seek(ofChunkStart);
            raf.write(abChunk, 0, cbChunk);

            long ofEntry = ofChunkStart;
            for (int i = 0; i < listPlan.size(); i++)
                {
                AppendPlan planChunk = listPlan.get(i);
                alTicket[iChunkStart + i] = completeAppend(info, planChunk, ofEntry);
                ofEntry += planChunk.getEntrySize();
                }
            }

        return alTicket;
        }

    /**
     * Return {@code true} if the entry should be assembled into a single
     * in-memory buffer before writing.
     *
     * @param cbEntry  total aligned entry size
     *
     * @return {@code true} for the buffered append path
     */
    private boolean shouldBufferAppend(long cbEntry)
        {
        return cbEntry <= f_config.getBufferedAppendThreshold();
        }

    /**
     * Return the maximum buffered batch chunk size.
     *
     * @return maximum buffered batch chunk size
     */
    private int getBufferedBatchChunkSize()
        {
        return Math.max(f_config.getBufferedAppendThreshold(), f_config.getBlockSize());
        }

    /**
     * Validate and pre-compute append metadata for an entry.
     *
     * @param bType     entry type
     * @param bufKey    entry key, or {@code null}
     * @param bufValue  entry value, or {@code null}
     *
     * @return append plan
     */
    private AppendPlan planAppend(byte bType, ReadBuffer bufKey, ReadBuffer bufValue)
        {
        int cbKey   = bufKey == null ? 0 : bufKey.length();
        int cbValue = bufValue == null ? 0 : bufValue.length();
        int cbPayload;

        switch (bType)
            {
            case JournalEntry.TYPE_STORE:
                if (bufKey == null)
                    {
                    throw new IllegalArgumentException("key cannot be null for STORE entries");
                    }
                if (bufValue == null)
                    {
                    throw new IllegalArgumentException("value cannot be null for STORE entries");
                    }
                cbPayload = 1 + 4 + 4 + cbKey + cbValue;
                break;

            case JournalEntry.TYPE_ERASE:
                if (bufKey == null)
                    {
                    throw new IllegalArgumentException("key cannot be null for ERASE entries");
                    }
                cbPayload = 1 + 4 + cbKey;
                break;

            case JournalEntry.TYPE_ERASE_EXTENT:
                cbPayload = 1;
                break;

            default:
                throw new IllegalArgumentException("unknown entry type: " + bType);
            }

        long lEntryRaw = JournalEntry.ENTRY_LEN_SIZE + cbPayload + JournalEntry.CRC_SIZE;
        long lEntry    = align16(lEntryRaw);

        if (lEntry > Integer.MAX_VALUE)
            {
            throw new IllegalArgumentException("entry exceeds maximum supported size: " + lEntry);
            }
        if (lEntry <= 0)
            {
            throw new IllegalArgumentException("entry size must be greater than zero");
            }
        if (lEntry > getMaximumFileSize())
            {
            throw new IllegalArgumentException(
                    "entry size (" + lEntry + ") exceeds maximum file size (" + getMaximumFileSize() + ")");
            }

        return new AppendPlan(bType, bufKey, cbKey, bufValue, cbValue, cbPayload, lEntryRaw, (int) lEntry);
        }

    /**
     * Append a pre-validated entry at the current file position.
     *
     * @param info     appending file metadata
     * @param raf      append file handle positioned at {@code ofEntry}
     * @param plan     append plan
     * @param ofEntry  entry start offset
     *
     * @return appended STORE ticket, or {@code TICKET_NONE}
     *
     * @throws IOException on I/O failure
     */
    private long appendPrepared(JournalFileInfoInternal info, RandomAccessFile raf, AppendPlan plan, long ofEntry)
            throws IOException
        {
        if (shouldBufferAppend(plan.getEntrySize()))
            {
            appendBuffered(raf, plan);
            }
        else
            {
            appendStreaming(raf, plan.getType(), plan.getKey(), plan.getKeyLength(), plan.getValue(),
                    plan.getValueLength(), plan.getRawEntrySize());
            }

        return completeAppend(info, plan, ofEntry);
        }

    /**
     * Update in-memory journal state after an entry has been appended
     * successfully.
     *
     * @param info     appending file metadata
     * @param plan     append plan
     * @param ofEntry  entry start offset
     *
     * @return appended STORE ticket, or {@code TICKET_NONE}
     *
     * @throws IOException on I/O failure
     */
    private long completeAppend(JournalFileInfoInternal info, AppendPlan plan, long ofEntry)
            throws IOException
        {
        int  cbEntry   = plan.getEntrySize();
        long cbNewSize = ofEntry + cbEntry;
        info.setSize(cbNewSize);
        info.addLiveBytes(cbEntry);
        info.markDirty();

        long lTicket = PersistentTicket.TICKET_NONE;
        if (plan.getType() == JournalEntry.TYPE_STORE)
            {
            lTicket = PersistentTicket.encode(info.getFileId(), ofEntry, plan.getKeyLength());
            m_mapStoreEntryMetadata.put(lTicket,
                    JournalUtils.packStoreEntryMetadata(cbEntry, plan.getValueLength()));
            }

        if (cbNewSize >= getMaximumFileSize())
            {
            info.setState(FileState.FULL);
            info.closeAppendFile();
            }

        return lTicket;
        }

    /**
     * Append an entry using a reusable in-memory buffer and a single file write.
     *
     * @param raf         append file
     * @param bType       entry type
     * @param bufKey      key buffer
     * @param cbKey       key length
     * @param bufValue    value buffer
     * @param cbValue     value length
     * @param cbPayload   payload length
     * @param cbEntryRaw  unaligned entry length
     * @param cbEntry     aligned entry length
     *
     * @throws IOException on I/O failure
     */
    private void appendBuffered(RandomAccessFile raf, byte bType, ReadBuffer bufKey, int cbKey,
            ReadBuffer bufValue, int cbValue, int cbPayload, long lEntryRaw, int cbEntry)
            throws IOException
        {
        byte[] abEntry = ensureBufferedEntryCapacity(cbEntry);
        appendBufferedTo(abEntry, 0, bType, bufKey, cbKey, bufValue, cbValue, cbPayload, lEntryRaw, cbEntry);
        raf.write(abEntry, 0, cbEntry);
        }

    /**
     * Append an entry using a reusable in-memory buffer and a single file
     * write.
     *
     * @param raf   append file
     * @param plan  append plan
     *
     * @throws IOException on I/O failure
     */
    private void appendBuffered(RandomAccessFile raf, AppendPlan plan)
            throws IOException
        {
        appendBuffered(raf, plan.getType(), plan.getKey(), plan.getKeyLength(), plan.getValue(),
                plan.getValueLength(), plan.getPayloadSize(), plan.getRawEntrySize(), plan.getEntrySize());
        }

    /**
     * Encode a buffered journal entry into the supplied byte array.
     *
     * @param abEntry    target byte array
     * @param ofEntry    start offset within {@code abEntry}
     * @param bType      entry type
     * @param bufKey     key buffer
     * @param cbKey      key length
     * @param bufValue   value buffer
     * @param cbValue    value length
     * @param cbPayload  payload length
     * @param lEntryRaw  unaligned entry length
     * @param cbEntry    aligned entry length
     *
     * @return offset immediately after the encoded entry
     */
    private int appendBufferedTo(byte[] abEntry, int ofEntry, byte bType, ReadBuffer bufKey, int cbKey,
            ReadBuffer bufValue, int cbValue, int cbPayload, long lEntryRaw, int cbEntry)
        {
        int of = ofEntry;

        writeIntBE(abEntry, of, cbEntry);
        of += Integer.BYTES;

        switch (bType)
            {
            case JournalEntry.TYPE_STORE:
                abEntry[of++] = bType;
                writeIntBE(abEntry, of, cbKey);
                of += Integer.BYTES;
                writeIntBE(abEntry, of, cbValue);
                of += Integer.BYTES;
                if (cbKey > 0)
                    {
                    bufKey.copyBytes(0, cbKey, abEntry, of);
                    of += cbKey;
                    }
                if (cbValue > 0)
                    {
                    bufValue.copyBytes(0, cbValue, abEntry, of);
                    of += cbValue;
                    }
                break;

            case JournalEntry.TYPE_ERASE:
                abEntry[of++] = bType;
                writeIntBE(abEntry, of, cbKey);
                of += Integer.BYTES;
                if (cbKey > 0)
                    {
                    bufKey.copyBytes(0, cbKey, abEntry, of);
                    of += cbKey;
                    }
                break;

            case JournalEntry.TYPE_ERASE_EXTENT:
                abEntry[of++] = bType;
                break;

            default:
                throw new IllegalArgumentException("unknown entry type: " + bType);
            }

        CRC32 crc32 = m_crc32;
        crc32.reset();
        crc32.update(abEntry, ofEntry + Integer.BYTES, cbPayload);

        int nCrc = (int) -crc32.getValue() - 1;
        writeIntBE(abEntry, of, nCrc);
        of += Integer.BYTES;

        int cbPadding = cbEntry - (int) lEntryRaw;
        if (cbPadding > 0)
            {
            Arrays.fill(abEntry, of, of + cbPadding, (byte) 0);
            of += cbPadding;
            }

        assert of == ofEntry + cbEntry;
        return of;
        }

    /**
     * Encode a buffered journal entry from an append plan into the supplied
     * byte array.
     *
     * @param abEntry  target byte array
     * @param ofEntry  start offset within {@code abEntry}
     * @param plan     append plan
     *
     * @return offset immediately after the encoded entry
     */
    private int appendBufferedTo(byte[] abEntry, int ofEntry, AppendPlan plan)
        {
        return appendBufferedTo(abEntry, ofEntry, plan.getType(), plan.getKey(), plan.getKeyLength(),
                plan.getValue(), plan.getValueLength(), plan.getPayloadSize(), plan.getRawEntrySize(),
                plan.getEntrySize());
        }

    /**
     * Append an entry using the streaming write path.
     *
     * @param raf         append file
     * @param bType       entry type
     * @param bufKey      key buffer
     * @param cbKey       key length
     * @param bufValue    value buffer
     * @param cbValue     value length
     * @param cbEntryRaw  unaligned entry length
     *
     * @throws IOException on I/O failure
     */
    private void appendStreaming(RandomAccessFile raf, byte bType, ReadBuffer bufKey, int cbKey,
            ReadBuffer bufValue, int cbValue, long lEntryRaw)
            throws IOException
        {
        byte[] abScratch = m_abAppendScratch;
        writeIntBE(abScratch, 0, (int) align16(lEntryRaw));
        raf.write(abScratch, 0, Integer.BYTES);

        CRC32         crc32  = m_crc32;
        CrcDataOutput outCrc = m_outCrc.reset(raf, crc32);
        crc32.reset();

        switch (bType)
            {
            case JournalEntry.TYPE_STORE:
                {
                abScratch[0] = bType;
                writeIntBE(abScratch, 1, cbKey);
                writeIntBE(abScratch, 5, cbValue);
                outCrc.write(abScratch, 0, 9);

                if (cbKey > 0)
                    {
                    bufKey.writeTo(outCrc);
                    }

                if (cbValue > 0)
                    {
                    bufValue.writeTo(outCrc);
                    }
                break;
                }

            case JournalEntry.TYPE_ERASE:
                {
                abScratch[0] = bType;
                writeIntBE(abScratch, 1, cbKey);
                outCrc.write(abScratch, 0, 5);

                if (cbKey > 0)
                    {
                    bufKey.writeTo(outCrc);
                    }
                break;
                }

            case JournalEntry.TYPE_ERASE_EXTENT:
                {
                abScratch[0] = bType;
                outCrc.write(abScratch, 0, 1);
                break;
                }

            default:
                throw new IllegalArgumentException("unknown entry type: " + bType);
            }

        int nCrc = (int) -crc32.getValue() - 1;
        writeIntBE(abScratch, 0, nCrc);
        raf.write(abScratch, 0, Integer.BYTES);

        int cbPadding = (int) (align16(lEntryRaw) - lEntryRaw);
        if (cbPadding > 0)
            {
            raf.write(EMPTY_PADDING, 0, cbPadding);
            }
        }

    /**
     * Ensure the reusable buffered-append array can hold the specified entry.
     *
     * @param cbEntry  required entry size
     *
     * @return the reusable entry buffer
     */
    private byte[] ensureBufferedEntryCapacity(int cbEntry)
        {
        byte[] abEntry = m_abBufferedEntry;
        if (abEntry == null || abEntry.length < cbEntry)
            {
            abEntry = new byte[cbEntry];
            m_abBufferedEntry = abEntry;
            }
        return abEntry;
        }

    /**
     * Read value bytes for the specified ticket.
     *
     * @param lTicket  ticket
     *
     * @return value buffer
     *
     * @throws IOException on I/O failure
     */
    public synchronized ReadBuffer read(long lTicket)
            throws IOException
        {
        ensureOpen();

        if (lTicket == PersistentTicket.TICKET_NONE || !PersistentTicket.isValid(lTicket))
            {
            throw new IllegalArgumentException("invalid ticket: " + lTicket);
            }

        int  nFileId    = PersistentTicket.extractFileId(lTicket);
        long ofEntry    = PersistentTicket.extractEntryOffset(lTicket);
        int  cbKey      = PersistentTicket.extractKeyLength(lTicket);
        long ofValue    = ofEntry + JournalEntry.computeValueOffset(cbKey);

        JournalFileInfoInternal info = m_mapFiles.get(nFileId);
        if (info == null)
            {
            throw new IllegalArgumentException("missing journal file for ticket: " + nFileId);
            }

        try
            {
            synchronized (info)
                {
                RandomAccessFile raf = info.ensureReadFile();
                int cbValue = JournalUtils.extractValueSize(
                        ensurePackedStoreEntryMetadata(info, lTicket, ofEntry, cbKey));
                raf.seek(ofValue);
                if (cbValue < 0)
                    {
                    throw new IOException("invalid value length at ticket " + lTicket + ": " + cbValue);
                    }

                byte[] abValue = new byte[cbValue];
                raf.readFully(abValue);

                return new Binary(abValue);
                }
            }
        catch (EOFException e)
            {
            throw new IOException("truncated value for ticket: " + lTicket, e);
            }
        }

    /**
     * Read the full STORE entry referenced by the supplied ticket.
     *
     * @param lTicket  the STORE ticket
     *
     * @return the parsed STORE entry
     *
     * @throws IOException on I/O failure
     */
    public synchronized JournalEntry readStoreEntry(long lTicket)
            throws IOException
        {
        ensureOpen();

        if (lTicket == PersistentTicket.TICKET_NONE || !PersistentTicket.isValid(lTicket))
            {
            throw new IllegalArgumentException("invalid ticket: " + lTicket);
            }

        int  nFileId = PersistentTicket.extractFileId(lTicket);
        long ofEntry = PersistentTicket.extractEntryOffset(lTicket);
        int  cbKey   = PersistentTicket.extractKeyLength(lTicket);

        JournalFileInfoInternal info = m_mapFiles.get(nFileId);
        if (info == null)
            {
            throw new IllegalArgumentException("missing journal file for ticket: " + nFileId);
            }

        try
            {
            synchronized (info)
                {
                RandomAccessFile raf      = info.ensureReadFile();
                long             lPacked  = ensurePackedStoreEntryMetadata(info, lTicket, ofEntry, cbKey);
                int              cbEntry  = JournalUtils.extractEntrySize(lPacked);
                int              cbValue  = JournalUtils.extractValueSize(lPacked);
                byte[]           abKey    = new byte[cbKey];
                byte[]           abValue  = new byte[cbValue];

                raf.seek(ofEntry + JournalEntry.STORE_HEADER_SIZE);
                raf.readFully(abKey);
                raf.readFully(abValue);

                return new JournalEntry(JournalEntry.TYPE_STORE,
                        new Binary(abKey),
                        new Binary(abValue),
                        cbEntry,
                        JournalEntry.OFFSET_STORE_VALUE_LEN,
                        cbValue,
                        true);
                }
            }
        catch (EOFException e)
            {
            throw new IOException("truncated store entry for ticket: " + lTicket, e);
            }
        }

    /**
     * Synchronize dirty journal files to stable storage.
     *
     * @throws IOException on I/O failure
     */
    public synchronized void sync()
            throws IOException
        {
        ensureOpen();

        for (JournalFileInfoInternal info : m_mapFiles.values())
            {
            info.sync();
            }
        }

    /**
     * Release a ticket and decrement its file's live-bytes count.
     *
     * @param lTicket  ticket
     */
    public synchronized void release(long lTicket)
        {
        releaseInternal(lTicket);
        }

    /**
     * Release a batch of tickets while holding the journal monitor only once.
     *
     * @param alTicket  tickets to release
     * @param cTickets  number of valid tickets
     */
    public synchronized void releaseBatch(long[] alTicket, int cTickets)
        {
        for (int i = 0; i < cTickets; i++)
            {
            releaseInternal(alTicket[i]);
            }
        }

    /**
     * Force APPENDING file rotation.
     *
     * @throws IOException on I/O failure
     */
    public synchronized void forceRotate()
            throws IOException
        {
        ensureOpen();

        JournalFileInfoInternal info = m_mapFiles.get(m_nCurrentFileId);
        if (info != null && info.getState() == FileState.APPENDING)
            {
            info.setState(FileState.FULL);
            info.closeAppendFile();
            }

        int nNext = nextFileId(m_nCurrentFileId);
        createAppendingFile(nNext);
        m_nCurrentFileId = nNext;
        }


    // ----- compaction support --------------------------------------------

    /**
     * Return compaction candidates.
     *
     * @param dflMinLoadFactor  minimum load-factor threshold
     *
     * @return candidate files
     */
    public synchronized List<JournalFileInfo> getCompactionCandidates(double dflMinLoadFactor)
        {
        List<JournalFileInfo> list = new ArrayList<>();

        for (JournalFileInfoInternal info : m_mapFiles.values())
            {
            if (info.getState() == FileState.FULL && info.getLoadFactor() < dflMinLoadFactor)
                {
                list.add(info.toExternal());
                }
            }

        list.sort((a, b) -> Integer.compare(a.getFileId(), b.getFileId()));
        return list;
        }

    /**
     * Return metadata for all known journal files in file-id order.
     *
     * @return file metadata for all known journal files
     */
    public synchronized List<JournalFileInfo> getFiles()
        {
        List<JournalFileInfo> list = new ArrayList<>(m_mapFiles.size());

        for (JournalFileInfoInternal info : m_mapFiles.values())
            {
            list.add(info.toExternal());
            }

        list.sort((a, b) -> Integer.compare(a.getFileId(), b.getFileId()));
        return list;
        }

    /**
     * Mark a file as EVACUATING.
     *
     * @param nFileId  file id
     */
    public synchronized void markEvacuating(int nFileId)
        {
        JournalFileInfoInternal info = requireFile(nFileId);
        if (info.getState() != FileState.FULL)
            {
            throw new IllegalStateException(
                    "can only evacuate FULL files, current state: " + info.getState());
            }
        info.setState(FileState.EVACUATING);
        }

    /**
     * Mark an EVACUATING file back to FULL.
     *
     * @param nFileId  file id
     */
    public synchronized void markFull(int nFileId)
        {
        JournalFileInfoInternal info = requireFile(nFileId);
        if (info.getState() != FileState.EVACUATING)
            {
            throw new IllegalStateException(
                    "can only reset EVACUATING files to FULL, current state: " + info.getState());
            }
        info.setState(FileState.FULL);
        }

    /**
     * Mark a file as GARBAGE.
     *
     * @param nFileId  file id
     */
    public synchronized void markGarbage(int nFileId)
        {
        JournalFileInfoInternal info = requireFile(nFileId);
        if (info.getState() != FileState.EVACUATING)
            {
            throw new IllegalStateException(
                    "can only mark EVACUATING files as GARBAGE, current state: " + info.getState());
            }
        info.setState(FileState.GARBAGE);
        }

    /**
     * Discard (delete) a file.
     *
     * @param nFileId  file id
     */
    public synchronized void discard(int nFileId)
        {
        if (nFileId == m_nCurrentFileId)
            {
            throw new IllegalArgumentException("cannot discard current appending file: " + nFileId);
            }

        JournalFileInfoInternal info = requireFile(nFileId);
        if (info.getState() != FileState.GARBAGE)
            {
            throw new IllegalStateException(
                    "can only discard GARBAGE files, current state: " + info.getState());
            }

        info.closeQuietly();
        info.getFile().delete();
        info.setState(FileState.DISCARDED);
        m_mapFiles.remove(nFileId);
        removeMetadataForFileId(nFileId);
        }

    /**
     * Set live-bytes for a file; intended for recovery-time initialization.
     *
     * @param nFileId  file id
     * @param cbLive   live bytes
     */
    public synchronized void setFileLiveBytes(int nFileId, long cbLive)
        {
        JournalFileInfoInternal info = requireFile(nFileId);
        info.setLiveBytes(cbLive);
        }

    // ----- accessors ------------------------------------------------------

    /**
     * Return current appending file id.
     *
     * @return current file id
     */
    public synchronized int getCurrentFileId()
        {
        return m_nCurrentFileId;
        }

    /**
     * Return metadata for a specific file.
     *
     * @param nFileId  file id
     *
     * @return file metadata
     */
    public synchronized JournalFileInfo getFileInfo(int nFileId)
        {
        return requireFile(nFileId).toExternal();
        }

    /**
     * Return current append offset.
     *
     * @return current append offset
     */
    public synchronized long getCurrentOffset()
        {
        JournalFileInfoInternal info = m_mapFiles.get(m_nCurrentFileId);
        return info == null ? 0L : info.getSize();
        }

    /**
     * Return the partition directory.
     *
     * @return partition directory
     */
    public File getDirectory()
        {
        return m_dirPartition;
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Ensure journal is open.
     */
    private void ensureOpen()
        {
        if (!m_fOpen)
            {
            throw new IllegalStateException("partition journal is not open");
            }
        }

    /**
     * Ensure current appending file can fit the specified entry size.
     *
     * @param cbEntry  entry size
     *
     * @return appending file
     *
     * @throws IOException on I/O failure
     */
    private JournalFileInfoInternal ensureAppendingFile(int cbEntry)
            throws IOException
        {
        JournalFileInfoInternal info = m_mapFiles.get(m_nCurrentFileId);
        if (info == null)
            {
            createAppendingFile(m_nCurrentFileId);
            info = m_mapFiles.get(m_nCurrentFileId);
            }

        if (info.getState() != FileState.APPENDING)
            {
            forceRotate();
            info = m_mapFiles.get(m_nCurrentFileId);
            }

        if (info.getSize() + cbEntry > getMaximumFileSize())
            {
            forceRotate();
            info = m_mapFiles.get(m_nCurrentFileId);
            }

        return info;
        }

    /**
     * Create an APPENDING file with the specified id.
     *
     * @param nFileId  file id
     *
     * @throws IOException on I/O failure
     */
    private void createAppendingFile(int nFileId)
            throws IOException
        {
        File file = JournalUtils.toJournalFile(m_dirPartition, nFileId);
        if (!file.exists())
            {
            if (!file.createNewFile())
                {
                throw new IOException("unable to create journal file: " + file);
                }
            }

        m_mapFiles.put(nFileId,
                new JournalFileInfoInternal(nFileId, file, FileState.APPENDING, file.length(), file.length()));
        }

    /**
     * Release a single ticket without reacquiring the journal monitor.
     *
     * @param lTicket  ticket to release
     */
    private void releaseInternal(long lTicket)
        {
        if (!PersistentTicket.isValid(lTicket))
            {
            return;
            }

        int                     nFileId = PersistentTicket.extractFileId(lTicket);
        JournalFileInfoInternal info    = m_mapFiles.get(nFileId);
        if (info == null)
            {
            return;
            }

        try
            {
            synchronized (info)
                {
                long lPacked = m_mapStoreEntryMetadata.remove(lTicket);
                if (lPacked == NO_PACKED_METADATA)
                    {
                    long ofEntry = PersistentTicket.extractEntryOffset(lTicket);
                    int  cbKey   = PersistentTicket.extractKeyLength(lTicket);
                    lPacked = ensurePackedStoreEntryMetadata(info, lTicket, ofEntry, cbKey);
                    m_mapStoreEntryMetadata.remove(lTicket);
                    }

                info.addLiveBytes(-JournalUtils.extractEntrySize(lPacked));
                }
            }
        catch (IOException e)
            {
            throw new IllegalStateException("failed to release ticket " + PersistentTicket.format(lTicket), e);
            }
        }

    /**
     * Return the supplied offset rounded up to 16-byte alignment.
     *
     * @param lOffset  offset to align
     *
     * @return aligned offset
     */
    private long align16(long lOffset)
        {
        return (lOffset + (ENTRY_ALIGNMENT - 1)) & ~(ENTRY_ALIGNMENT - 1);
        }

    /**
     * Write a long value to a byte array in big-endian form.
     *
     * @param ab  target byte array
     * @param of  offset into {@code ab}
     * @param l   value to write
     */
    private static void writeLongBE(byte[] ab, int of, long l)
        {
        ab[of]     = (byte) (l >>> 56);
        ab[of + 1] = (byte) (l >>> 48);
        ab[of + 2] = (byte) (l >>> 40);
        ab[of + 3] = (byte) (l >>> 32);
        ab[of + 4] = (byte) (l >>> 24);
        ab[of + 5] = (byte) (l >>> 16);
        ab[of + 6] = (byte) (l >>> 8);
        ab[of + 7] = (byte) l;
        }

    /**
     * Write an int value to a byte array in big-endian form.
     *
     * @param ab  target byte array
     * @param of  offset into {@code ab}
     * @param n   value to write
     */
    private static void writeIntBE(byte[] ab, int of, int n)
        {
        ab[of]     = (byte) (n >>> 24);
        ab[of + 1] = (byte) (n >>> 16);
        ab[of + 2] = (byte) (n >>> 8);
        ab[of + 3] = (byte) n;
        }

    /**
     * Immutable append plan.
     */
    private static class AppendPlan
        {
        /**
         * Create an append plan.
         *
         * @param bType       entry type
         * @param bufKey      key buffer
         * @param cbKey       key length
         * @param bufValue    value buffer
         * @param cbValue     value length
         * @param cbPayload   payload size
         * @param lEntryRaw   raw entry size
         * @param cbEntry     aligned entry size
         */
        private AppendPlan(byte bType, ReadBuffer bufKey, int cbKey, ReadBuffer bufValue, int cbValue,
                int cbPayload, long lEntryRaw, int cbEntry)
            {
            f_bType     = bType;
            f_bufKey    = bufKey;
            f_cbKey     = cbKey;
            f_bufValue  = bufValue;
            f_cbValue   = cbValue;
            f_cbPayload = cbPayload;
            f_lEntryRaw = lEntryRaw;
            f_cbEntry   = cbEntry;
            }

        private byte getType()
            {
            return f_bType;
            }

        private ReadBuffer getKey()
            {
            return f_bufKey;
            }

        private int getKeyLength()
            {
            return f_cbKey;
            }

        private ReadBuffer getValue()
            {
            return f_bufValue;
            }

        private int getValueLength()
            {
            return f_cbValue;
            }

        private int getPayloadSize()
            {
            return f_cbPayload;
            }

        private long getRawEntrySize()
            {
            return f_lEntryRaw;
            }

        private int getEntrySize()
            {
            return f_cbEntry;
            }

        private final byte f_bType;
        private final ReadBuffer f_bufKey;
        private final int f_cbKey;
        private final ReadBuffer f_bufValue;
        private final int f_cbValue;
        private final int f_cbPayload;
        private final long f_lEntryRaw;
        private final int f_cbEntry;
        }

    /**
     * Return maximum file size.
     *
     * @return maximum file size
     */
    private long getMaximumFileSize()
        {
        return f_config.getMaximumFileSize();
        }

    /**
     * Return next file id.
     *
     * @param nCurrent  current file id
     *
     * @return next file id
     */
    private int nextFileId(int nCurrent)
        {
        for (int i = 1; i < MAX_FILE_SLOTS; i++)
            {
            int nCandidate = (nCurrent + i) % MAX_FILE_SLOTS;
            if (nCandidate == 0)
                {
                continue;
                }

            if (!m_mapFiles.containsKey(nCandidate))
                {
                return nCandidate;
                }
            }

        throw new IllegalStateException("all journal file slots are in use");
        }

    /**
     * Return internal file info for id.
     *
     * @param nFileId  file id
     *
     * @return file info
     */
    private JournalFileInfoInternal requireFile(int nFileId)
        {
        JournalFileInfoInternal info = m_mapFiles.get(nFileId);
        if (info == null)
            {
            throw new IllegalArgumentException("missing file id: " + nFileId);
            }
        return info;
        }

    /**
     * Return packed STORE metadata for the supplied ticket, populating the
     * local cache on first access.
     *
     * @param info     owning journal file
     * @param lTicket  STORE ticket
     * @param ofEntry  entry offset
     * @param cbKey    key length encoded in the ticket
     *
     * @return packed STORE metadata
     *
     * @throws IOException on I/O failure
     */
    private long ensurePackedStoreEntryMetadata(JournalFileInfoInternal info, long lTicket, long ofEntry, int cbKey)
            throws IOException
        {
        try
            {
            return m_mapStoreEntryMetadata.computeIfAbsent(lTicket, key ->
                {
                try
                    {
                    return JournalUtils.readPackedStoreMetadata(info.ensureReadFile(), key, ofEntry, cbKey);
                    }
                catch (IOException e)
                    {
                    throw new UncheckedIOException(e);
                    }
                });
            }
        catch (UncheckedIOException e)
            {
            throw e.getCause();
            }
        }

    /**
     * Close all open file handles without clearing retained metadata.
     */
    private void closeFiles()
        {
        for (JournalFileInfoInternal info : m_mapFiles.values())
            {
            info.closeQuietly();
            }
        }

    /**
     * Remove any cached STORE metadata for the supplied file id.
     *
     * @param nFileId  file id to purge
     */
    private void removeMetadataForFileId(int nFileId)
        {
        for (LongIterator iter = m_mapStoreEntryMetadata.keySet().iterator(); iter.hasNext(); )
            {
            if (PersistentTicket.extractFileId(iter.nextLong()) == nFileId)
                {
                iter.remove();
                }
            }
        }


    // ----- inner class: JournalFileInfo ----------------------------------

    /**
     * External view of journal file metadata.
     */
    public static class JournalFileInfo
        {
        /**
         * Construct metadata.
         *
         * @param nFileId    file id
         * @param state      state
         * @param cbSize     file size
         * @param cbLive     live bytes
         */
        public JournalFileInfo(int nFileId, FileState state, long cbSize, long cbLive)
            {
            m_nFileId = nFileId;
            m_state   = state;
            m_cbSize  = cbSize;
            m_cbLive  = cbLive;
            }

        /**
         * Return file id.
         *
         * @return file id
         */
        public int getFileId()
            {
            return m_nFileId;
            }

        /**
         * Return state.
         *
         * @return state
         */
        public FileState getState()
            {
            return m_state;
            }

        /**
         * Return file size.
         *
         * @return file size
         */
        public long getSize()
            {
            return m_cbSize;
            }

        /**
         * Return live bytes.
         *
         * @return live bytes
         */
        public long getLiveBytes()
            {
            return m_cbLive;
            }

        /**
         * Return load factor.
         *
         * @return load factor
         */
        public double getLoadFactor()
            {
            return m_cbSize == 0L ? 1.0d : Math.max(0.0d, Math.min(1.0d, ((double) m_cbLive) / m_cbSize));
            }

        /**
         * File id.
         */
        private final int m_nFileId;

        /**
         * State.
         */
        private final FileState m_state;

        /**
         * File size.
         */
        private final long m_cbSize;

        /**
         * Live bytes.
         */
        private final long m_cbLive;
        }

    /**
     * Internal mutable file metadata.
     */
    private static class JournalFileInfoInternal
        {
        /**
         * Construct metadata.
         *
         * @param nFileId  file id
         * @param file     file
         * @param state    state
         * @param cbSize   file size
         * @param cbLive   live bytes
         */
        private JournalFileInfoInternal(int nFileId, File file, FileState state, long cbSize, long cbLive)
            {
            m_nFileId = nFileId;
            m_file    = file;
            m_state   = state;
            m_cbSize  = cbSize;
            m_cbLive  = cbLive;
            }

        /**
         * Return immutable external metadata.
         *
         * @return external metadata
         */
        private JournalFileInfo toExternal()
            {
            return new JournalFileInfo(m_nFileId, m_state, m_cbSize, m_cbLive);
            }

        private int getFileId()
            {
            return m_nFileId;
            }

        private File getFile()
            {
            return m_file;
            }

        private void setFile(File file)
            {
            m_file = file;
            }

        private FileState getState()
            {
            return m_state;
            }

        private void setState(FileState state)
            {
            m_state = state;
            }

        private long getSize()
            {
            return m_cbSize;
            }

        private void setSize(long cbSize)
            {
            m_cbSize = cbSize;
            }

        private long getLiveBytes()
            {
            return m_cbLive;
            }

        private void addLiveBytes(long cbDelta)
            {
            m_cbLive = Math.max(0L, m_cbLive + cbDelta);
            }

        private void setLiveBytes(long cbLive)
            {
            m_cbLive = Math.max(0L, cbLive);
            }

        private RandomAccessFile ensureAppendFile()
                throws IOException
            {
            if (m_rafAppend == null)
                {
                m_rafAppend = new RandomAccessFile(m_file, "rw");
                }

            return m_rafAppend;
            }

        private RandomAccessFile ensureReadFile()
                throws IOException
            {
            if (m_rafRead == null)
                {
                m_rafRead = new RandomAccessFile(m_file, "r");
                }

            return m_rafRead;
            }

        private void markDirty()
            {
            m_fDirty = true;
            }

        private void sync()
                throws IOException
            {
            if (!m_fDirty)
                {
                return;
                }

            RandomAccessFile raf = m_rafAppend;
            if (raf == null)
                {
                try (RandomAccessFile rafSync = new RandomAccessFile(m_file, "rw"))
                    {
                    rafSync.getFD().sync();
                    }
                }
            else
                {
                raf.getFD().sync();
                }

            m_fDirty = false;
            }

        private void closeAppendFile()
                throws IOException
            {
            if (m_rafAppend != null)
                {
                m_rafAppend.close();
                m_rafAppend = null;
                }
            }

        private void closeQuietly()
            {
            if (m_rafAppend != null)
                {
                try
                    {
                    m_rafAppend.close();
                    }
                catch (IOException ignored)
                    {
                    }
                m_rafAppend = null;
                }

            if (m_rafRead != null)
                {
                try
                    {
                    m_rafRead.close();
                    }
                catch (IOException ignored)
                    {
                    }
                m_rafRead = null;
                }
            }

        private double getLoadFactor()
            {
            return m_cbSize == 0L ? 1.0d : Math.max(0.0d, Math.min(1.0d, ((double) m_cbLive) / m_cbSize));
            }

        private final int m_nFileId;
        private File m_file;
        private FileState m_state;
        private long m_cbSize;
        private long m_cbLive;
        private RandomAccessFile m_rafAppend;
        private RandomAccessFile m_rafRead;
        private boolean m_fDirty;
        }

    /**
     * {@link DataOutput} that forwards writes to a {@link RandomAccessFile}
     * while updating a running CRC.
     */
    private static class CrcDataOutput
            implements DataOutput
        {
        /**
         * Reset this output for a new append operation.
         *
         * @param out    the delegate output
         * @param crc32  the CRC accumulator
         *
         * @return this output
         */
        CrcDataOutput reset(RandomAccessFile out, CRC32 crc32)
            {
            m_out   = out;
            m_crc32 = crc32;
            return this;
            }

        @Override
        public void write(int b)
                throws IOException
            {
            m_out.write(b);
            m_crc32.update(b);
            }

        @Override
        public void write(byte[] ab)
                throws IOException
            {
            write(ab, 0, ab.length);
            }

        @Override
        public void write(byte[] ab, int of, int cb)
                throws IOException
            {
            m_out.write(ab, of, cb);
            m_crc32.update(ab, of, cb);
            }

        @Override
        public void writeBoolean(boolean f)
                throws IOException
            {
            write(f ? 1 : 0);
            }

        @Override
        public void writeByte(int b)
                throws IOException
            {
            write(b);
            }

        @Override
        public void writeShort(int n)
                throws IOException
            {
            byte[] ab = m_abScratch;
            ab[0] = (byte) (n >>> 8);
            ab[1] = (byte) n;
            write(ab, 0, Short.BYTES);
            }

        @Override
        public void writeChar(int ch)
                throws IOException
            {
            writeShort(ch);
            }

        @Override
        public void writeInt(int n)
                throws IOException
            {
            byte[] ab = m_abScratch;
            writeIntBE(ab, 0, n);
            write(ab, 0, Integer.BYTES);
            }

        @Override
        public void writeLong(long l)
                throws IOException
            {
            byte[] ab = m_abScratch;
            writeLongBE(ab, 0, l);
            write(ab, 0, Long.BYTES);
            }

        @Override
        public void writeFloat(float fl)
                throws IOException
            {
            writeInt(Float.floatToIntBits(fl));
            }

        @Override
        public void writeDouble(double dfl)
                throws IOException
            {
            writeLong(Double.doubleToLongBits(dfl));
            }

        @Override
        public void writeBytes(String s)
                throws IOException
            {
            for (int i = 0, c = s.length(); i < c; i++)
                {
                writeByte((byte) s.charAt(i));
                }
            }

        @Override
        public void writeChars(String s)
                throws IOException
            {
            for (int i = 0, c = s.length(); i < c; i++)
                {
                writeChar(s.charAt(i));
                }
            }

        @Override
        public void writeUTF(String s)
                throws IOException
            {
            throw new UnsupportedOperationException("writeUTF is not supported");
            }

        /**
         * Delegate output.
         */
        private RandomAccessFile m_out;

        /**
         * CRC accumulator.
         */
        private CRC32 m_crc32;

        /**
         * Scratch buffer reused for fixed-width primitive writes.
         */
        private final byte[] m_abScratch = new byte[Long.BYTES];
        }


    // ----- enum: FileState ------------------------------------------------

    /**
     * Journal file state.
     */
    public enum FileState
        {
        APPENDING,
        FULL,
        EVACUATING,
        GARBAGE,
        DISCARDED
        }


    // ----- constants ------------------------------------------------------

    /**
     * Max file slots.
     */
    private static final int MAX_FILE_SLOTS = PersistentTicket.MAX_FILE_ID + 1;

    /**
     * Entry alignment (bytes).
     */
    private static final int ENTRY_ALIGNMENT = 16;

    /**
     * Zero padding reused for aligned appends.
     */
    private static final byte[] EMPTY_PADDING = new byte[ENTRY_ALIGNMENT];

    /**
     * Shared empty ticket array.
     */
    private static final long[] NO_TICKETS = new long[0];

    // ----- data members ---------------------------------------------------

    /**
     * Partition directory.
     */
    private File m_dirPartition;

    /**
     * Configuration.
     */
    private final PartitionJournalConfig f_config;

    /**
     * Open flag.
     */
    private volatile boolean m_fOpen;

    /**
     * File id -> metadata.
     */
    private final Int2ObjectOpenHashMap<JournalFileInfoInternal> m_mapFiles = new Int2ObjectOpenHashMap<>();

    /**
     * Cached packed STORE entry metadata keyed by ticket.
     */
    private final Long2LongOpenHashMap m_mapStoreEntryMetadata = new Long2LongOpenHashMap();

    /**
     * Reusable scratch buffer for append headers, lengths, and CRC.
     */
    private final byte[] m_abAppendScratch = new byte[13];

    /**
     * Reusable buffer for the small-entry single-write append path.
     */
    private byte[] m_abBufferedEntry;

    /**
     * Reused CRC accumulator for append operations.
     */
    private final CRC32 m_crc32 = new CRC32();

    /**
     * Reused CRC-tracking output for streaming entry payload writes.
     */
    private final CrcDataOutput m_outCrc = new CrcDataOutput();

    /**
     * Current appending file id.
     */
    private int m_nCurrentFileId;

    /**
     * Sentinel returned by the metadata cache when no metadata is present.
     */
    private static final long NO_PACKED_METADATA = 0L;
    }
