/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.tangosol.io.ReadBuffer;

import com.tangosol.util.Binary;
import com.tangosol.util.BinaryRadixTree;

import java.io.File;
import java.io.IOException;

import java.util.List;

/**
 * Per-partition journal compaction logic.
 * <p>
 * Compacts journal files with low load factors by evacuating live entries
 * to the current appending file, handling tombstone lifecycle, and deleting
 * the evacuated files.
 *
 * @author rl  2026.03.07
 * @since 26.04
 */
public class JournalCompactor
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Construct a compactor.
     *
     * @param journal  the partition journal
     * @param tree     the binary radix tree (key → ticket index)
     */
    public JournalCompactor(PartitionJournal journal, BinaryRadixTree tree)
        {
        if (journal == null)
            {
            throw new IllegalArgumentException("journal cannot be null");
            }
        if (tree == null)
            {
            throw new IllegalArgumentException("tree cannot be null");
            }

        f_journal = journal;
        f_tree    = tree;
        }


    // ----- compaction operations -----------------------------------------

    /**
     * Run one compaction cycle.
     *
     * @param nCheckpointFileNo  the file number of the last checkpoint
     *                           (tombstones in files strictly before this
     *                           number can be dropped)
     * @param dflMinLoadFactor   minimum load-factor threshold; FULL files
     *                           with a load factor below this are candidates
     *
     * @return the number of files compacted (evacuated + discarded)
     *
     * @throws IOException on I/O failure
     */
    public int compact(int nCheckpointFileNo, double dflMinLoadFactor)
            throws IOException
        {
        return compact(nCheckpointFileNo, dflMinLoadFactor, Integer.MAX_VALUE);
        }

    /**
     * Run one compaction cycle, limiting the number of files compacted in a
     * single pass.
     *
     * @param nCheckpointFileNo  the file number of the last checkpoint
     *                           (tombstones in files strictly before this
     *                           number can be dropped)
     * @param dflMinLoadFactor   minimum load-factor threshold; FULL files
     *                           with a load factor below this are candidates
     * @param cMaxFiles          maximum number of files to compact in this pass
     *
     * @return the number of files compacted (evacuated + discarded)
     *
     * @throws IOException on I/O failure
     */
    public int compact(int nCheckpointFileNo, double dflMinLoadFactor, int cMaxFiles)
            throws IOException
        {
        int cCompacted = 0;

        List<PartitionJournal.JournalFileInfo> listCandidates =
                f_journal.getCompactionCandidates(dflMinLoadFactor);

        for (PartitionJournal.JournalFileInfo info : listCandidates)
            {
            if (cCompacted >= cMaxFiles)
                {
                break;
                }

            evacuateFile(info.getFileId(), nCheckpointFileNo);
            cCompacted++;
            }

        return cCompacted;
        }

    /**
     * Evacuate a single journal file.
     *
     * @param nFileId            the file id to evacuate
     * @param nCheckpointFileNo  the last checkpoint file number
     *
     * @throws IOException on I/O failure
     */
    public void evacuateFile(int nFileId, int nCheckpointFileNo)
            throws IOException
        {
        if (nFileId == f_journal.getCurrentFileId())
            {
            f_journal.forceRotate();
            }

        f_journal.markEvacuating(nFileId);

        File file = JournalUtils.toJournalFile(f_journal.getDirectory(), nFileId);

        try
            {
            JournalUtils.scanFile(file, nFileId, 0L, (nScannedFileId, ofEntry, entry) ->
                {
                if (!entry.isValid())
                    {
                    return true;
                    }

                try
                    {
                    switch (entry.getType())
                        {
                        case JournalEntry.TYPE_STORE:
                            handleStore(nScannedFileId, ofEntry, entry);
                            break;

                        case JournalEntry.TYPE_ERASE:
                            handleErase(nScannedFileId, nCheckpointFileNo, entry);
                            break;

                        case JournalEntry.TYPE_ERASE_EXTENT:
                            handleEraseExtent(nScannedFileId, nCheckpointFileNo, entry);
                            break;

                        default:
                            break;
                        }
                    }
                catch (IOException e)
                    {
                    throw new WrappedIOException(e);
                    }

                return true;
                });
            }
        catch (WrappedIOException e)
            {
            f_journal.markFull(nFileId);
            throw e.getCause();
            }
        catch (IOException e)
            {
            f_journal.markFull(nFileId);
            throw e;
            }

        f_journal.markGarbage(nFileId);
        f_journal.discard(nFileId);
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Handle a STORE entry encountered during file evacuation.
     *
     * @param nFileId  file id
     * @param ofEntry  entry offset
     * @param entry    parsed entry
     *
     * @throws IOException on I/O failure
     */
    private void handleStore(int nFileId, long ofEntry, JournalEntry entry)
            throws IOException
        {
        Binary binKey = entry.getKey();
        if (binKey == null)
            {
            return;
            }

        long lExpectedTicket = PersistentTicket.encode(nFileId, ofEntry, binKey.length());
        long lCurrentTicket  = f_tree.get(binKey);

        if (lCurrentTicket != lExpectedTicket)
            {
            return;
            }

        ReadBuffer bufValue   = f_journal.read(lExpectedTicket);
        long       lNewTicket = f_journal.append(JournalEntry.TYPE_STORE, binKey, bufValue);

        if (f_tree.replace(binKey, lExpectedTicket, lNewTicket))
            {
            f_journal.release(lExpectedTicket);
            }
        else
            {
            f_journal.release(lNewTicket);
            }
        }

    /**
     * Handle an ERASE tombstone encountered during file evacuation.
     *
     * @param nFileId            file id
     * @param nCheckpointFileNo  last checkpoint file id
     * @param entry              parsed entry
     *
     * @throws IOException on I/O failure
     */
    private void handleErase(int nFileId, int nCheckpointFileNo, JournalEntry entry)
            throws IOException
        {
        if (nFileId < nCheckpointFileNo)
            {
            return;
            }

        f_journal.append(JournalEntry.TYPE_ERASE, entry.getKey(), null);
        }

    /**
     * Handle an ERASE_EXTENT tombstone encountered during file evacuation.
     *
     * @param nFileId            file id
     * @param nCheckpointFileNo  last checkpoint file id
     * @param entry              parsed entry
     *
     * @throws IOException on I/O failure
     */
    private void handleEraseExtent(int nFileId, int nCheckpointFileNo, JournalEntry entry)
            throws IOException
        {
        if (nFileId < nCheckpointFileNo)
            {
            return;
            }

        f_journal.append(JournalEntry.TYPE_ERASE_EXTENT, null, null);
        }


    // ----- inner class: WrappedIOException -------------------------------

    /**
     * Runtime wrapper for checked I/O exceptions raised from scanner visitor callbacks.
     */
    private static class WrappedIOException
            extends RuntimeException
        {
        /**
         * Construct wrapper.
         *
         * @param cause  wrapped cause
         */
        private WrappedIOException(IOException cause)
            {
            super(cause);
            }

        /**
         * Return wrapped cause.
         *
         * @return wrapped cause
         */
        @Override
        public IOException getCause()
            {
            return (IOException) super.getCause();
            }
        }


    // ----- data members ---------------------------------------------------

    /**
     * Partition journal.
     */
    private final PartitionJournal f_journal;

    /**
     * Key-to-ticket radix tree.
     */
    private final BinaryRadixTree f_tree;

    }
