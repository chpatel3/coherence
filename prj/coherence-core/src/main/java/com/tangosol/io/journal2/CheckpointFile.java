/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.util.Binary;
import com.tangosol.util.BinaryRadixTree;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Utility for writing and reading radix-tree checkpoints.
 *
 * @author rl  2026.03.04
 * @since 26.04
 */
public final class CheckpointFile
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Hidden utility class constructor.
     */
    private CheckpointFile()
        {
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Write a checkpoint file containing live entry metadata.
     *
     * @param fileCheckpoint    the target checkpoint file
     * @param nFileNo           checkpoint journal file number
     * @param lOffset           checkpoint journal offset
     * @param tree              radix tree to serialize
     *
     * @throws IOException on I/O failure
     */
    public static void write(File fileCheckpoint,
                             int nFileNo,
                             long lOffset,
                             BinaryRadixTree tree)
            throws IOException
        {
        write(fileCheckpoint, nFileNo, lOffset, new TreeCheckpointEntries(tree));
        }

    /**
     * Write a checkpoint file containing live entry metadata.
     *
     * @param fileCheckpoint  the target checkpoint file
     * @param nFileNo         checkpoint journal file number
     * @param lOffset         checkpoint journal offset
     * @param aKeys           checkpoint keys
     * @param alTickets       checkpoint tickets
     * @param cEntries        number of valid entries
     *
     * @throws IOException on I/O failure
     */
    public static void write(File fileCheckpoint,
                             int nFileNo,
                             long lOffset,
                             Binary[] aKeys,
                             long[] alTickets,
                             int cEntries)
            throws IOException
        {
        if (aKeys == null || alTickets == null)
            {
            throw new IllegalArgumentException("checkpoint entry arrays cannot be null");
            }
        if (cEntries < 0 || cEntries > aKeys.length || cEntries > alTickets.length)
            {
            throw new IllegalArgumentException("invalid checkpoint entry count: " + cEntries);
            }

        write(fileCheckpoint, nFileNo, lOffset, new ArrayCheckpointEntries(aKeys, alTickets, cEntries));
        }

    /**
     * Write a checkpoint file containing live entry metadata.
     *
     * @param fileCheckpoint  the target checkpoint file
     * @param nFileNo         checkpoint journal file number
     * @param lOffset         checkpoint journal offset
     * @param entries         checkpoint entries to serialize
     *
     * @throws IOException on I/O failure
     */
    private static void write(File fileCheckpoint,
                              int nFileNo,
                              long lOffset,
                              CheckpointEntries entries)
            throws IOException
        {
        if (fileCheckpoint == null)
            {
            throw new IllegalArgumentException("checkpoint file cannot be null");
            }
        if (entries == null)
            {
            throw new IllegalArgumentException("entries cannot be null");
            }

        File fileParent = fileCheckpoint.getParentFile();
        if (fileParent != null)
            {
            fileParent.mkdirs();
            }

        File fileTmp = fileParent == null
                ? new File(fileCheckpoint.getPath() + ".tmp")
                : new File(fileParent, fileCheckpoint.getName() + ".tmp");

        try
            {
            try (FileOutputStream           outFile    = new FileOutputStream(fileTmp);
                 BufferedOutputStream outBuffered = new BufferedOutputStream(outFile);
                 CrcOutputStream            outCrc     = new CrcOutputStream(outBuffered);
                 DataOutputStream           out        = new DataOutputStream(outCrc))
                {
                out.writeInt(MAGIC);
                out.writeByte(VERSION);
                out.writeInt(nFileNo);
                out.writeLong(lOffset);

                entries.writeTo(out);

                out.writeInt(TERMINATOR);

                // Write CRC (not included in CRC computation)
                out.writeInt(outCrc.getCrc());
                out.flush();
                outFile.getFD().sync();
                }

            moveAtomically(fileTmp.toPath(), fileCheckpoint.toPath());
            }
        catch (IOException | RuntimeException e)
            {
            Files.deleteIfExists(fileTmp.toPath());
            throw e;
            }
        }

    /**
     * Read a checkpoint file.
     *
     * @param fileCheckpoint  the checkpoint file
     *
     * @return parsed checkpoint data
     *
     * @throws IOException on I/O failure
     */
    public static CheckpointData read(File fileCheckpoint)
            throws IOException
        {
        if (fileCheckpoint == null)
            {
            throw new IllegalArgumentException("checkpoint file cannot be null");
            }

        Map<Binary, Long> mapEntries = new LinkedHashMap<>();
        CheckpointHeader header = read(fileCheckpoint, mapEntries::put);

        return new CheckpointData(header.getVersion(), header.getFileNo(), header.getOffset(), mapEntries);
        }

    /**
     * Read a checkpoint file using a visitor pattern to avoid loading all entries into memory.
     *
     * @param fileCheckpoint  the checkpoint file
     * @param visitor         the entry visitor
     *
     * @return checkpoint header information
     *
     * @throws IOException on I/O failure
     */
    public static CheckpointHeader read(File fileCheckpoint, EntryVisitor visitor)
            throws IOException
        {
        if (visitor == null)
            {
            throw new IllegalArgumentException("visitor cannot be null");
            }

        long cbFile = fileCheckpoint.length();
        if (cbFile < HEADER_SIZE + TRAILER_SIZE)
            {
            throw new IllegalArgumentException("checkpoint is too small: " + cbFile);
            }

        try (FileInputStream     inFile = new FileInputStream(fileCheckpoint);
             BufferedInputStream inBuffered = new BufferedInputStream(inFile);
             CrcInputStream      inCrc   = new CrcInputStream(inBuffered, cbFile - CRC_SIZE);
             DataInputStream     in      = new DataInputStream(inCrc))
            {
            int nMagic = in.readInt();
            if (nMagic != MAGIC)
                {
                throw new IllegalArgumentException("invalid checkpoint magic: " + nMagic);
                }

            int nVersion = in.readUnsignedByte();
            if (nVersion != VERSION)
                {
                throw new IllegalArgumentException("unsupported checkpoint version: " + nVersion);
                }

            int  nFileNo = in.readInt();
            long lOffset = in.readLong();

            long cbEntryZone = cbFile - HEADER_SIZE - TRAILER_SIZE;
            long cbConsumed  = 0;

            while (cbConsumed < cbEntryZone)
                {
                int  cbKey     = in.readInt();
                if (cbKey < 0)
                    {
                    throw new IllegalArgumentException("negative key length: " + cbKey);
                    }

                byte[] abKey = new byte[cbKey];
                in.readFully(abKey);
                long lTicket = in.readLong();

                visitor.visit(new Binary(abKey), lTicket);
                cbConsumed += Integer.BYTES + cbKey + Long.BYTES;
                }

            int nTerminator = in.readInt();
            if (nTerminator != TERMINATOR)
                {
                throw new IllegalArgumentException("invalid checkpoint terminator: " + nTerminator);
                }

            int nExpectedCrc = in.readInt();
            int nActualCrc   = inCrc.getCrc();
            if (nActualCrc != nExpectedCrc)
                {
                throw new IllegalArgumentException("checkpoint CRC mismatch");
                }

            return new CheckpointHeader((byte) nVersion, nFileNo, lOffset);
            }
        }

    /**
     * Move the source file to destination using atomic move when possible.
     *
     * @param pathFrom  source path
     * @param pathTo    destination path
     *
     * @throws IOException on I/O failure
     */
    private static void moveAtomically(Path pathFrom, Path pathTo)
            throws IOException
        {
        try
            {
            Files.move(pathFrom, pathTo,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        catch (IOException e)
            {
            Files.move(pathFrom, pathTo, StandardCopyOption.REPLACE_EXISTING);
            }
        }

    // ----- inner interface: EntryVisitor ---------------------------------

    /**
     * Source of checkpoint entries to serialize.
     */
    private interface CheckpointEntries
        {
        /**
         * Write checkpoint entries to the supplied stream.
         *
         * @param out  the target stream
         *
         * @throws IOException on I/O failure
         */
        void writeTo(DataOutputStream out)
                throws IOException;
        }

    /**
     * Visitor for processing checkpoint entries during streaming read.
     */
    public interface EntryVisitor
        {
        /**
         * Visit a checkpoint entry.
         *
         * @param binKey   the entry key
         * @param lTicket  the ticket
         */
        void visit(Binary binKey, long lTicket);
        }

    // ----- inner class: TreeCheckpointEntries ----------------------------

    /**
     * {@link CheckpointEntries} backed by a {@link BinaryRadixTree}.
     */
    private static class TreeCheckpointEntries
            implements CheckpointEntries
        {
        /**
         * Create a tree-backed checkpoint entry source.
         *
         * @param tree  the tree to serialize
         */
        private TreeCheckpointEntries(BinaryRadixTree tree)
            {
            if (tree == null)
                {
                throw new IllegalArgumentException("tree cannot be null");
                }

            f_tree = tree;
            }

        @Override
        public void writeTo(DataOutputStream out)
                throws IOException
            {
            try
                {
                f_tree.visitAll(entry ->
                    {
                    try
                        {
                        writeEntry(out, entry.getKey(), entry.getValue());
                        }
                    catch (IOException e)
                        {
                        throw new IllegalStateException(e);
                        }
                    });
                }
            catch (IllegalStateException e)
                {
                Throwable cause = e.getCause();
                if (cause instanceof IOException)
                    {
                    throw (IOException) cause;
                    }
                throw e;
                }
            }

        /**
         * Backing tree.
         */
        private final BinaryRadixTree f_tree;
        }

    // ----- inner class: ArrayCheckpointEntries ---------------------------

    /**
     * {@link CheckpointEntries} backed by key/ticket arrays.
     */
    private static class ArrayCheckpointEntries
            implements CheckpointEntries
        {
        /**
         * Create an array-backed checkpoint entry source.
         *
         * @param aKeys      the keys
         * @param alTickets  the tickets
         * @param cEntries   the number of valid entries
         */
        private ArrayCheckpointEntries(Binary[] aKeys, long[] alTickets, int cEntries)
            {
            f_aKeys     = aKeys;
            f_alTickets = alTickets;
            f_cEntries  = cEntries;
            }

        @Override
        public void writeTo(DataOutputStream out)
                throws IOException
            {
            for (int i = 0; i < f_cEntries; i++)
                {
                writeEntry(out, f_aKeys[i], f_alTickets[i]);
                }
            }

        /**
         * Keys.
         */
        private final Binary[] f_aKeys;

        /**
         * Tickets.
         */
        private final long[] f_alTickets;

        /**
         * Number of valid entries.
         */
        private final int f_cEntries;
        }

    // ----- helpers -------------------------------------------------------

    /**
     * Write a single checkpoint entry.
     *
     * @param out      the target stream
     * @param binKey   the key
     * @param lTicket  the ticket
     *
     * @throws IOException on I/O failure
     */
    private static void writeEntry(DataOutputStream out, Binary binKey, long lTicket)
            throws IOException
        {
        out.writeInt(binKey.length());
        binKey.writeTo((java.io.DataOutput) out);
        out.writeLong(lTicket);
        }

    // ----- inner class: CheckpointHeader ---------------------------------

    /**
     * Checkpoint header information (without entries).
     */
    public static class CheckpointHeader
        {
        /**
         * Construct a checkpoint header.
         *
         * @param nFileNo  checkpoint file number
         * @param lOffset  checkpoint offset
         */
        public CheckpointHeader(byte nVersion, int nFileNo, long lOffset)
            {
            m_nVersion = nVersion;
            m_nFileNo = nFileNo;
            m_lOffset = lOffset;
            }

        /**
         * Return the checkpoint format version.
         *
         * @return checkpoint format version
         */
        public byte getVersion()
            {
            return m_nVersion;
            }

        /**
         * Return the checkpoint file number.
         *
         * @return checkpoint file number
         */
        public int getFileNo()
            {
            return m_nFileNo;
            }

        /**
         * Return the checkpoint offset.
         *
         * @return checkpoint offset
         */
        public long getOffset()
            {
            return m_lOffset;
            }

        /**
         * Checkpoint format version.
         */
        private final byte m_nVersion;

        /**
         * Checkpoint file number.
         */
        private final int m_nFileNo;

        /**
         * Checkpoint offset.
         */
        private final long m_lOffset;
        }


    // ----- inner class: CrcOutputStream ----------------------------------

    /**
     * Output stream that accumulates CRC while writing.
     */
    private static class CrcOutputStream
            extends FilterOutputStream
        {
        /**
         * Construct a CRC output stream.
         *
         * @param out  the underlying output stream
         */
        public CrcOutputStream(OutputStream out)
            {
            super(out);
            }

        @Override
        public void write(int b)
                throws IOException
            {
            out.write(b);
            m_crc.update(b);
            }

        @Override
        public void write(byte[] b, int off, int len)
                throws IOException
            {
            out.write(b, off, len);
            m_crc.update(b, off, len);
            }

        /**
         * Return the accumulated CRC.
         *
         * @return the CRC value
         */
        public int getCrc()
            {
            return (int) -m_crc.getValue() - 1;
            }

        /**
         * Accumulated CRC value.
         */
        private final CRC32 m_crc = new CRC32();
        }


    // ----- inner class: CrcInputStream -----------------------------------

    /**
     * Input stream that accumulates CRC while reading.
     */
    private static class CrcInputStream
            extends FilterInputStream
        {
        /**
         * Construct a CRC input stream.
         *
         * @param in         the underlying input stream
         * @param cbCrcLimit the byte limit for CRC accumulation
         */
        public CrcInputStream(InputStream in, long cbCrcLimit)
            {
            super(in);
            m_cbCrcLimit = cbCrcLimit;
            }

        @Override
        public int read()
                throws IOException
            {
            int b = in.read();
            if (b != -1 && m_cbRead < m_cbCrcLimit)
                {
                m_crc.update(b);
                m_cbRead++;
                }
            return b;
            }

        @Override
        public int read(byte[] b, int off, int len)
                throws IOException
            {
            int nRead = in.read(b, off, len);
            if (nRead > 0)
                {
                long cbRemaining = m_cbCrcLimit - m_cbRead;
                if (cbRemaining > 0)
                    {
                    int cbToCrc = (int) Math.min(cbRemaining, nRead);
                    m_crc.update(b, off, cbToCrc);
                    }
                m_cbRead += nRead;
                }
            return nRead;
            }

        /**
         * Return the accumulated CRC.
         *
         * @return the CRC value
         */
        public int getCrc()
            {
            return (int) -m_crc.getValue() - 1;
            }

        /**
         * Byte limit for CRC accumulation.
         */
        private final long m_cbCrcLimit;

        /**
         * Bytes read so far.
         */
        private long m_cbRead;

        /**
         * Accumulated CRC value.
         */
        private final CRC32 m_crc = new CRC32();
        }


    // ----- inner class: CheckpointData -----------------------------------

    /**
     * Parsed checkpoint data.
     */
    public static class CheckpointData
        {
        /**
         * Construct a checkpoint data record.
         *
         * @param nFileNo    checkpoint file number
         * @param lOffset    checkpoint offset
         * @param mapEntries checkpoint entries
         */
        public CheckpointData(byte nVersion, int nFileNo, long lOffset, Map<Binary, Long> mapEntries)
            {
            m_nVersion   = nVersion;
            m_nFileNo    = nFileNo;
            m_lOffset    = lOffset;
            m_mapEntries = Collections.unmodifiableMap(new LinkedHashMap<>(mapEntries));
            }

        /**
         * Return the checkpoint format version.
         *
         * @return checkpoint format version
         */
        public byte getVersion()
            {
            return m_nVersion;
            }

        /**
         * Return the checkpoint file number.
         *
         * @return checkpoint file number
         */
        public int getFileNo()
            {
            return m_nFileNo;
            }

        /**
         * Return the checkpoint offset.
         *
         * @return checkpoint offset
         */
        public long getOffset()
            {
            return m_lOffset;
            }

        /**
         * Return checkpoint entries keyed by binary key.
         *
         * @return checkpoint entries
         */
        public Map<Binary, Long> getEntries()
            {
            return m_mapEntries;
            }

        /**
         * Checkpoint format version.
         */
        private final byte m_nVersion;

        /**
         * Checkpoint file number.
         */
        private final int m_nFileNo;

        /**
         * Checkpoint offset.
         */
        private final long m_lOffset;

        /**
         * Checkpoint entries.
         */
        private final Map<Binary, Long> m_mapEntries;
        }


    // ----- constants ------------------------------------------------------

    /**
     * Checkpoint file magic ('COHC').
     */
    public static final int MAGIC = 0x434F4843;

    /**
     * Checkpoint format version.
     */
    public static final byte VERSION = 1;

    /**
     * Entry terminator.
     */
    private static final int TERMINATOR = -1;

    /**
     * Header size.
     */
    private static final int HEADER_SIZE = Integer.BYTES + Byte.BYTES + Integer.BYTES + Long.BYTES;

    /**
     * Trailer size (terminator + CRC).
     */
    private static final int TRAILER_SIZE = Integer.BYTES + Integer.BYTES;

    /**
     * CRC field size.
     */
    private static final int CRC_SIZE = Integer.BYTES;
    }
