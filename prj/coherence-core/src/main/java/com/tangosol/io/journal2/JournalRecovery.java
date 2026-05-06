/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import com.oracle.coherence.common.base.Logger;

import com.tangosol.persistence.PersistenceRecoveryMetrics;
import com.tangosol.util.Binary;
import com.tangosol.util.BinaryLongMap;
import com.tangosol.util.BinaryRadixTree;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recovery helper for rebuilding a partition radix tree from checkpoint and journal files.
 *
 * @author rl  2026.03.06
 * @since 26.04
 */
public final class JournalRecovery
    {
    // ----- inner interface: RecoveredEntryVisitor ------------------------

    /**
     * Visitor of materialized live recovered entries.
     */
    public interface RecoveredEntryVisitor
        {
        /**
         * Visit a recovered key/value pair.
         *
         * @param binKey    the recovered key
         * @param binValue  the recovered value
         *
         * @return {@code true} to continue, {@code false} to stop
         */
        boolean visit(Binary binKey, Binary binValue);
        }

    // ----- constructors ---------------------------------------------------

    /**
     * Hidden utility class constructor.
     */
    private JournalRecovery()
        {
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Recover partition state from checkpoint and journal files.
     *
     * @param dirPartition  partition directory
     * @param journal       opened partition journal
     * @param tree          radix tree to populate
     * @param lExtentId     fixed extent identifier for this journal
     *
     * @return recovery result
     *
     * @throws IOException on I/O failure
     */
    public static RecoveryResult recover(File dirPartition, PartitionJournal journal, BinaryRadixTree tree,
            long lExtentId)
            throws IOException
        {
        long ldtStart = System.nanoTime();

        if (dirPartition == null)
            {
            throw new IllegalArgumentException("partition directory cannot be null");
            }
        if (journal == null)
            {
            throw new IllegalArgumentException("journal cannot be null");
            }
        if (tree == null)
            {
            throw new IllegalArgumentException("tree cannot be null");
            }

        tree.clear();

        Map<Long, Set<Binary>> mapExtentToKeys = new HashMap<>();
        Map<Binary, Long>      mapKeyToExtent  = new HashMap<>();

        int  cRecovered = 0;
        int  cSkipped   = 0;
        int  nStartFile = Integer.MIN_VALUE;
        long ofStart    = 0L;
        long cNanosCheckpointRead = 0L;
        long cbCheckpointBytes    = 0L;

        File    fileCheckpoint = new File(dirPartition, CHECKPOINT_FILE);
        boolean fHasCheckpoint = fileCheckpoint.exists() && !isCheckpointIgnored();
        if (fHasCheckpoint)
            {
            long ldtCheckpointRead = System.nanoTime();
            CheckpointFile.CheckpointHeader header = CheckpointFile.read(fileCheckpoint,
                    (binKey, lTicket) ->
                        {
                        tree.put(binKey, lTicket);
                        mapKeyToExtent.put(binKey, lExtentId);
                        ensureExtentSet(mapExtentToKeys, lExtentId).add(binKey);
                        });
            cNanosCheckpointRead = System.nanoTime() - ldtCheckpointRead;
            cbCheckpointBytes    = fileCheckpoint.length();

            nStartFile = header.getFileNo();
            ofStart    = header.getOffset();
            cRecovered = tree.size();
            }

        long          ldtDiscover = System.nanoTime();
        List<Integer> listFileIds = JournalUtils.discoverFileIds(dirPartition);
        long          cNanosDiscover = System.nanoTime() - ldtDiscover;

        long         ldtScanReplay = System.nanoTime();
        ReplayResult  replayResult = replayAndIndexJournal(dirPartition, listFileIds,
                nStartFile, ofStart, !fHasCheckpoint, tree, mapExtentToKeys, mapKeyToExtent,
                lExtentId);
        long         cNanosScanReplay = System.nanoTime() - ldtScanReplay;

        cRecovered += replayResult.getRecovered();
        cSkipped   += replayResult.getSkipped();

        long               ldtVisit    = System.nanoTime();
        Map<Integer, Long> mapFileLive = computeLiveBytes(dirPartition, tree, listFileIds.size());
        long               cNanosVisit = System.nanoTime() - ldtVisit;

        long ldtSetLive = System.nanoTime();
        for (Integer nFileId : listFileIds)
            {
            long cbLive = mapFileLive.getOrDefault(nFileId, 0L);
            try
                {
                journal.setFileLiveBytes(nFileId, cbLive);
                }
            catch (IllegalArgumentException e)
                {
                Logger.finest(() -> "Skipping live-byte update during journal recovery; file=" + nFileId
                        + ", liveBytes=" + cbLive + ", reason=" + e.getMessage(), e);
                }
            }
        long cNanosSetLive = System.nanoTime() - ldtSetLive;

        RecoveryTimings timings = new RecoveryTimings(
                fHasCheckpoint,
                listFileIds.size(),
                tree.size(),
                cbCheckpointBytes,
                cNanosCheckpointRead,
                cNanosDiscover + cNanosScanReplay + cNanosVisit + cNanosSetLive,
                replayResult.getApplyNanos(),
                replayResult.getScannedEntryCount(),
                replayResult.getAppliedEntryCount(),
                System.nanoTime() - ldtStart);

        return new RecoveryResult(cRecovered, cSkipped, mapExtentToKeys, timings, nStartFile, ofStart);
        }

    /**
     * Return {@code true} if recovery timing logs are enabled.
     *
     * @return {@code true} if recovery timing logs are enabled
     */
    public static boolean isTimingEnabled()
        {
        return PersistenceRecoveryMetrics.isEnabled();
        }

    /**
     * Materialize recovered live entries directly from checkpoint and journal
     * files without opening a live runtime extent state.
     *
     * @param dirPartition  the extent directory
     * @param lExtentId     the fixed extent identifier
     * @param visitor       visitor to apply
     *
     * @throws IOException on I/O failure
     */
    public static void iterateRecoveredEntries(File dirPartition, long lExtentId, RecoveredEntryVisitor visitor)
            throws IOException
        {
        long ldtStart = System.nanoTime();

        if (dirPartition == null)
            {
            throw new IllegalArgumentException("partition directory cannot be null");
            }
        if (visitor == null)
            {
            throw new IllegalArgumentException("visitor cannot be null");
            }

        Map<Binary, Object> mapEntries = new HashMap<>();
        int                 nStartFile = Integer.MIN_VALUE;
        long                ofStart    = 0L;
        long                cNanosCheckpointRead = 0L;
        long                cNanosDiscover       = 0L;
        long                cNanosReplay         = 0L;
        long                cbCheckpointBytes    = 0L;
        MaterializedEmitTimings timingsEmit;

        File    fileCheckpoint = new File(dirPartition, CHECKPOINT_FILE);
        boolean fHasCheckpoint = fileCheckpoint.exists() && !isCheckpointIgnored();
        if (fHasCheckpoint)
            {
            try
                {
                long ldtCheckpointRead = System.nanoTime();
                CheckpointFile.CheckpointHeader header = CheckpointFile.read(fileCheckpoint,
                        (binKey, lTicket) -> mapEntries.put(binKey, Long.valueOf(lTicket)));
                cNanosCheckpointRead = System.nanoTime() - ldtCheckpointRead;
                cbCheckpointBytes    = fileCheckpoint.length();
                nStartFile = header.getFileNo();
                ofStart    = header.getOffset();
                }
            catch (IOException | RuntimeException e)
                {
                Logger.warn(() -> "Ignoring corrupted journal checkpoint during recovery materialization: "
                        + fileCheckpoint.getAbsolutePath(), e);
                mapEntries.clear();
                nStartFile = Integer.MIN_VALUE;
                ofStart    = 0L;
                }
            }

        long          ldtDiscover = System.nanoTime();
        List<Integer> listFileIds = JournalUtils.discoverFileIds(dirPartition);
        cNanosDiscover = System.nanoTime() - ldtDiscover;

        long ldtReplay = System.nanoTime();
        replayMaterializedJournal(dirPartition, listFileIds, nStartFile, ofStart, mapEntries, lExtentId);
        cNanosReplay = System.nanoTime() - ldtReplay;

        timingsEmit = emitMaterializedEntries(dirPartition, mapEntries, visitor);

        if (PersistenceRecoveryMetrics.isEnabled())
            {
            PersistenceRecoveryMetrics.recordSummary(
                    1L,
                    mapEntries.size(),
                    cbCheckpointBytes,
                    System.nanoTime() - ldtStart,
                    cNanosCheckpointRead,
                    cNanosDiscover,
                    cNanosReplay,
                    timingsEmit.getReadValueNanos() + timingsEmit.getVisitNanos());
            }
        }

    /**
     * Replay journal files and index STORE entry lengths in a single pass.
     *
     * @param dirPartition     partition directory
     * @param listFileIds      discovered journal file ids
     * @param nStartFile       checkpoint start file id, or {@link Integer#MIN_VALUE}
     * @param ofStart          checkpoint start offset
     * @param fScanHistorical  {@code true} to scan historical entries before the checkpoint
     * @param tree             radix tree
     * @param mapExtentToKeys  extent-to-keys map
     * @param mapKeyToExtent   key-to-extent map
     *
     * @return replay result
     *
     * @throws IOException on I/O failure
     */
    private static ReplayResult replayAndIndexJournal(File dirPartition,
                                                      List<Integer> listFileIds,
                                                      int nStartFile,
                                                      long ofStart,
                                                      boolean fScanHistorical,
                                                      BinaryRadixTree tree,
                                                      Map<Long, Set<Binary>> mapExtentToKeys,
                                                      Map<Binary, Long> mapKeyToExtent,
                                                      long lExtentId)
            throws IOException
        {
        ReplayCounters counters = new ReplayCounters();

        for (Integer nFileId : listFileIds)
            {
            if (counters.m_fStopReplay)
                {
                break;
                }

            File fileJournal = JournalUtils.toJournalFile(dirPartition, nFileId);
            boolean fReplayFile  = nStartFile == Integer.MIN_VALUE || nFileId >= nStartFile;
            long    ofReplayFrom = nStartFile != Integer.MIN_VALUE && nFileId == nStartFile
                    ? Math.max(0L, ofStart)
                    : 0L;
            if (!fScanHistorical && !fReplayFile)
                {
                continue;
                }
            long    cbFile       = fileJournal.length();
            long    ofScanFrom   = fScanHistorical || !fReplayFile ? 0L : ofReplayFrom;

            JournalUtils.scanFile(fileJournal, nFileId, ofScanFrom, (nFid, ofEntry, entry) ->
                {
                if (counters.m_fStopReplay)
                    {
                    return false;
                    }

                boolean fReplayEntry = fReplayFile && ofEntry >= ofReplayFrom;

                if (!entry.isValid())
                    {
                    if (!fReplayEntry)
                        {
                        return true;
                        }

                    counters.m_cSkipped++;
                    Logger.warn("Skipping journal entry with invalid CRC during recovery; file="
                            + nFid + ", offset=" + ofEntry + ", length=" + entry.getEntryLen());

                    if (ofEntry + entry.getEntryLen() >= cbFile)
                        {
                        counters.m_fStopReplay = true;
                        return false;
                        }

                    return true;
                    }

                if (!fReplayEntry)
                    {
                    return true;
                    }

                counters.m_cScanned++;

                long ldtApply = System.nanoTime();

                switch (entry.getType())
                    {
                    case JournalEntry.TYPE_STORE:
                        {
                        Binary binKey     = entry.getKey();
                        long   lOldTicket = tree.get(binKey);
                        long   lTicket    = PersistentTicket.encode(nFid, ofEntry, binKey.length());

                        tree.put(binKey, lTicket);

                        Long lOldExtent = mapKeyToExtent.put(binKey, lExtentId);
                        if (lOldExtent != null && lOldExtent.longValue() != lExtentId)
                            {
                            removeExtentMembership(mapExtentToKeys, lOldExtent.longValue(), binKey);
                            }

                        ensureExtentSet(mapExtentToKeys, lExtentId).add(binKey);
                        counters.m_cRecovered++;
                        counters.m_cApplied++;
                        incrementAppliedExtentCount(lExtentId, counters);
                        break;
                        }

                    case JournalEntry.TYPE_ERASE:
                        {
                        Binary binKey     = entry.getKey();

                        tree.remove(binKey);

                        Long lOldExtent = mapKeyToExtent.remove(binKey);
                        if (lOldExtent != null)
                            {
                            removeExtentMembership(mapExtentToKeys, lOldExtent.longValue(), binKey);
                            }
                        else
                            {
                            removeExtentMembership(mapExtentToKeys, lExtentId, binKey);
                            }

                        counters.m_cRecovered++;
                        counters.m_cApplied++;
                        incrementAppliedExtentCount(lExtentId, counters);
                        break;
                        }

                    case JournalEntry.TYPE_ERASE_EXTENT:
                        {
                        Set<Binary> setKeys   = mapExtentToKeys.remove(lExtentId);

                        if (setKeys != null)
                            {
                            for (Binary binKey : setKeys)
                                {
                                long lOldTicket = tree.get(binKey);
                                tree.remove(binKey);
                                mapKeyToExtent.remove(binKey);
                                }
                            }

                        counters.m_cRecovered++;
                        counters.m_cApplied++;
                        incrementAppliedExtentCount(lExtentId, counters);
                        break;
                        }

                    default:
                        counters.m_cSkipped++;
                        Logger.warn("Skipping journal entry with unsupported type during recovery; file="
                                + nFid + ", offset=" + ofEntry + ", type=" + entry.getType());
                        break;
                    }

                counters.m_cNanosApply += System.nanoTime() - ldtApply;
                return !counters.m_fStopReplay;
                });
            }

        return new ReplayResult(counters.m_cRecovered, counters.m_cSkipped, counters.m_cScanned, counters.m_cApplied,
                counters.m_cAppliedUser, counters.m_cAppliedMeta, counters.m_cAppliedResvd,
                counters.m_cNanosApply);
        }

    /**
     * Replay journal entries directly into the recovery materialization map.
     *
     * @param dirPartition  the extent directory
     * @param listFileIds   discovered journal file ids
     * @param nStartFile    checkpoint start file id, or {@link Integer#MIN_VALUE}
     * @param ofStart       checkpoint start offset
     * @param mapEntries    recovery materialization map
     * @param lExtentId     fixed extent identifier
     *
     * @throws IOException on I/O failure
     */
    private static void replayMaterializedJournal(File dirPartition, List<Integer> listFileIds, int nStartFile,
            long ofStart, Map<Binary, Object> mapEntries, long lExtentId)
            throws IOException
        {
        MaterializationState state = new MaterializationState();

        for (Integer nFileId : listFileIds)
            {
            if (state.m_fStopReplay)
                {
                break;
                }

            if (nStartFile != Integer.MIN_VALUE && nFileId < nStartFile)
                {
                continue;
                }

            File fileJournal = JournalUtils.toJournalFile(dirPartition, nFileId);
            long ofReplayFrom = nStartFile != Integer.MIN_VALUE && nFileId == nStartFile
                    ? Math.max(0L, ofStart)
                    : 0L;
            long cbFile = fileJournal.length();

            JournalUtils.scanFile(fileJournal, nFileId, ofReplayFrom, true, (nFid, ofEntry, entry) ->
                {
                if (state.m_fStopReplay)
                    {
                    return false;
                    }

                if (!entry.isValid())
                    {
                    Logger.warn("Skipping journal entry with invalid CRC during recovery materialization; file="
                            + nFid + ", offset=" + ofEntry + ", length=" + entry.getEntryLen());

                    if (ofEntry + Math.max(entry.getEntryLen(), 0L) >= cbFile)
                        {
                        state.m_fStopReplay = true;
                        return false;
                        }

                    return true;
                    }

                switch (entry.getType())
                    {
                    case JournalEntry.TYPE_STORE:
                        {
                        Binary binValue = entry.getValue();
                        if (binValue == null)
                            {
                            Logger.fine(() -> "Journal recovery materialization missing eager value; "
                                    + "falling back to ticket read. file=" + nFid
                                    + ", offset=" + ofEntry + ", extent=" + lExtentId);
                            long lTicket = PersistentTicket.encode(nFid, ofEntry, entry.getKey().length());
                            mapEntries.put(entry.getKey(), Long.valueOf(lTicket));
                            }
                        else
                            {
                            mapEntries.put(entry.getKey(), binValue);
                            }
                        break;
                        }

                    case JournalEntry.TYPE_ERASE:
                        mapEntries.remove(entry.getKey());
                        break;

                    case JournalEntry.TYPE_ERASE_EXTENT:
                        mapEntries.clear();
                        break;

                    default:
                        Logger.warn("Skipping journal entry with unsupported type during recovery materialization; file="
                                + nFid + ", offset=" + ofEntry + ", type=" + entry.getType()
                                + ", extent=" + lExtentId);
                        break;
                    }

                return true;
                });
            }
        }

    /**
     * Emit final recovered live entries from the materialization map.
     *
     * @param dirPartition  the extent directory
     * @param mapEntries    recovery materialization map
     * @param visitor       visitor to apply
     *
     * @throws IOException on I/O failure
     */
    private static MaterializedEmitTimings emitMaterializedEntries(File dirPartition, Map<Binary, Object> mapEntries,
            RecoveredEntryVisitor visitor)
            throws IOException
        {
        Map<Integer, List<Map.Entry<Binary, Object>>> mapTicketEntries = new HashMap<>();
        Set<Integer>                                  setUnresolvedFiles = new HashSet<>();
        long                                          cNanosVisit       = 0L;
        long                                          cNanosReadValue   = 0L;

        for (Map.Entry<Binary, Object> entry : mapEntries.entrySet())
            {
            Object oValue = entry.getValue();
            if (oValue instanceof Binary)
                {
                long ldtVisit = System.nanoTime();
                boolean fContinue = visitor.visit(entry.getKey(), (Binary) oValue);
                cNanosVisit += System.nanoTime() - ldtVisit;
                if (!fContinue)
                    {
                    return new MaterializedEmitTimings(cNanosReadValue, cNanosVisit, 0, 0);
                    }
                }
            else if (oValue instanceof Long)
                {
                long lTicket = ((Long) oValue).longValue();
                int  nFileId = PersistentTicket.extractFileId(lTicket);

                mapTicketEntries.computeIfAbsent(nFileId, key -> new ArrayList<>()).add(entry);
                setUnresolvedFiles.add(nFileId);
                }
            else
                {
                throw new IllegalStateException("unsupported recovery materialization value: " + oValue);
                }
            }

        int cUnresolvedFiles   = setUnresolvedFiles.size();
        int cUnresolvedTickets = 0;

        List<Integer> listFileIds = new ArrayList<>(mapTicketEntries.keySet());
        Collections.sort(listFileIds);

        for (Integer nFileId : listFileIds)
            {
            List<Map.Entry<Binary, Object>> listEntries = mapTicketEntries.get(nFileId);
            cUnresolvedTickets += listEntries.size();

            listEntries.sort((entry1, entry2) ->
                {
                long lTicket1 = ((Long) entry1.getValue()).longValue();
                long lTicket2 = ((Long) entry2.getValue()).longValue();

                return Long.compare(PersistentTicket.extractEntryOffset(lTicket1),
                        PersistentTicket.extractEntryOffset(lTicket2));
                });

            try (JournalUtils.BufferedValueReader reader = JournalUtils.openBufferedValueReader(dirPartition, nFileId))
                {
                for (Map.Entry<Binary, Object> entry : listEntries)
                    {
                    long ldtReadValue = System.nanoTime();
                    Binary binValue = reader.readValue(((Long) entry.getValue()).longValue());
                    cNanosReadValue += System.nanoTime() - ldtReadValue;

                    long ldtVisit = System.nanoTime();
                    boolean fContinue = visitor.visit(entry.getKey(), binValue);
                    cNanosVisit += System.nanoTime() - ldtVisit;
                    if (!fContinue)
                        {
                        return new MaterializedEmitTimings(cNanosReadValue, cNanosVisit,
                                cUnresolvedFiles, cUnresolvedTickets);
                        }
                    }
                }
            }

        return new MaterializedEmitTimings(cNanosReadValue, cNanosVisit, cUnresolvedFiles, cUnresolvedTickets);
        }

    /**
     * Compute live bytes per journal file from the recovered tree.
     *
     * @param dirPartition       the extent directory
     * @param tree               recovered radix tree
     * @param cExpectedFileCount expected number of journal files
     *
     * @return live bytes keyed by file id
     *
     * @throws IOException on I/O failure
     */
    private static Map<Integer, Long> computeLiveBytes(File dirPartition, BinaryRadixTree tree, int cExpectedFileCount)
            throws IOException
        {
        Map<Integer, Long>                     mapFileLive = new HashMap<>(Math.max(1, cExpectedFileCount));
        Map<Integer, java.io.RandomAccessFile> mapReaders  = new HashMap<>(Math.max(1, cExpectedFileCount));

        try
            {
            tree.visitAll(new BinaryLongMap.EntryVisitor()
                {
                @Override
                public void visit(BinaryLongMap.Entry entry)
                    {
                    long lTicket = entry.getValue();
                    int  nFileId = PersistentTicket.extractFileId(lTicket);
                    long ofEntry = PersistentTicket.extractEntryOffset(lTicket);
                    int  cbKey   = PersistentTicket.extractKeyLength(lTicket);

                    try
                        {
                        java.io.RandomAccessFile raf = mapReaders.get(nFileId);
                        if (raf == null)
                            {
                            raf = new java.io.RandomAccessFile(JournalUtils.toJournalFile(dirPartition, nFileId), "r");
                            mapReaders.put(nFileId, raf);
                            }

                        int cbEntry = JournalUtils.extractEntrySize(
                                JournalUtils.readPackedStoreMetadata(raf, lTicket, ofEntry, cbKey));
                        mapFileLive.put(nFileId, mapFileLive.getOrDefault(nFileId, 0L) + cbEntry);
                        }
                    catch (IOException e)
                        {
                        throw new IllegalStateException("failed to compute live bytes for ticket "
                                + PersistentTicket.format(lTicket), e);
                        }
                    }
                });

            return mapFileLive;
            }
        catch (IllegalStateException e)
            {
            if (e.getCause() instanceof IOException)
                {
                throw (IOException) e.getCause();
                }
            throw e;
            }
        finally
            {
            closeReaders(mapReaders);
            }
        }

    /**
     * Close cached journal readers.
     *
     * @param mapReaders  readers to close
     *
     * @throws IOException on I/O failure
     */
    private static void closeReaders(Map<Integer, java.io.RandomAccessFile> mapReaders)
            throws IOException
        {
        IOException eFirst = null;

        for (java.io.RandomAccessFile raf : mapReaders.values())
            {
            try
                {
                raf.close();
                }
            catch (IOException e)
                {
                if (eFirst == null)
                    {
                    eFirst = e;
                    }
                else
                    {
                    eFirst.addSuppressed(e);
                    }
                }
            }

        if (eFirst != null)
            {
            throw eFirst;
            }
        }

    /**
     * Increment applied-entry counters based on extent type.
     *
     * @param lExtentId        recovered extent id
     * @param counters   replay counters
     */
    private static void incrementAppliedExtentCount(long lExtentId, ReplayCounters counters)
        {
        if (lExtentId > 0L)
            {
            counters.m_cAppliedUser++;
            }
        else if (lExtentId == 0L)
            {
            counters.m_cAppliedMeta++;
            }
        else
            {
            counters.m_cAppliedResvd++;
            }
        }

    /**
     * Ensure extent-key set exists in supplied map.
     *
     * @param mapExtentToKeys  extent-key map
     * @param lExtentId        extent id
     *
     * @return extent key set
     */
    private static Set<Binary> ensureExtentSet(Map<Long, Set<Binary>> mapExtentToKeys, long lExtentId)
        {
        return mapExtentToKeys.computeIfAbsent(lExtentId, k -> new HashSet<>());
        }

    /**
     * Remove key membership from an extent set and prune empty extent sets.
     *
     * @param mapExtentToKeys  extent-key map
     * @param lExtentId        extent id
     * @param binKey           key to remove
     */
    private static void removeExtentMembership(Map<Long, Set<Binary>> mapExtentToKeys, long lExtentId, Binary binKey)
        {
        Set<Binary> setKeys = mapExtentToKeys.get(lExtentId);
        if (setKeys != null)
            {
            setKeys.remove(binKey);
            if (setKeys.isEmpty())
                {
                mapExtentToKeys.remove(lExtentId);
                }
            }
        }

    // ----- inner class: ReplayCounters -----------------------------------

    /**
     * Mutable replay counters used while scanning journal files.
     */
    private static class ReplayCounters
        {
        private int     m_cRecovered;
        private int     m_cSkipped;
        private int     m_cScanned;
        private int     m_cApplied;
        private int     m_cAppliedUser;
        private int     m_cAppliedMeta;
        private int     m_cAppliedResvd;
        private boolean m_fStopReplay;
        private long    m_cNanosApply;
        }

    /**
     * Mutable state used while replaying into a recovery materialization map.
     */
    private static class MaterializationState
        {
        private boolean m_fStopReplay;
        }

    /**
     * Timings captured while emitting recovered entries.
     */
    private static class MaterializedEmitTimings
        {
        /**
         * Create emission timings.
         *
         * @param cNanosReadValue  time spent resolving values from journal tickets
         * @param cNanosVisit      time spent invoking the recovery visitor
         * @param cUnresolvedFiles number of unresolved files referenced by ticket-backed entries
         * @param cUnresolvedTickets number of unresolved ticket-backed entries
         */
        private MaterializedEmitTimings(long cNanosReadValue, long cNanosVisit,
                int cUnresolvedFiles, int cUnresolvedTickets)
            {
            m_cNanosReadValue    = cNanosReadValue;
            m_cNanosVisit        = cNanosVisit;
            m_cUnresolvedFiles   = cUnresolvedFiles;
            m_cUnresolvedTickets = cUnresolvedTickets;
            }

        /**
         * Return ticket-backed value read time.
         *
         * @return ticket-backed value read time
         */
        private long getReadValueNanos()
            {
            return m_cNanosReadValue;
            }

        /**
         * Return visitor invocation time.
         *
         * @return visitor invocation time
         */
        private long getVisitNanos()
            {
            return m_cNanosVisit;
            }

        /**
         * Return the number of unresolved files referenced by ticket-backed entries.
         *
         * @return unresolved file count
         */
        private int getUnresolvedFileCount()
            {
            return m_cUnresolvedFiles;
            }

        /**
         * Return the number of unresolved ticket-backed entries.
         *
         * @return unresolved ticket count
         */
        private int getUnresolvedTicketCount()
            {
            return m_cUnresolvedTickets;
            }

        /**
         * Time spent resolving values from journal tickets.
         */
        private final long m_cNanosReadValue;

        /**
         * Time spent invoking the recovery visitor.
         */
        private final long m_cNanosVisit;

        /**
         * Number of unresolved files referenced by ticket-backed entries.
         */
        private final int m_cUnresolvedFiles;

        /**
         * Number of unresolved ticket-backed entries.
         */
        private final int m_cUnresolvedTickets;
        }


    // ----- inner class: ReplayResult -------------------------------------

    /**
     * Replay outcome counters.
     */
    private static class ReplayResult
        {
        /**
         * Construct replay result.
         *
         * @param cRecovered       recovered entry count
         * @param cSkipped         skipped entry count
         * @param cScanned         scanned replay-entry count
         * @param cApplied         applied replay-entry count
         * @param cAppliedUser     applied user-extent entry count
         * @param cAppliedMeta     applied META extent entry count
         * @param cAppliedResvd    applied reserved/meta extent entry count
         * @param cNanosApply      time spent applying post-checkpoint entries
         */
        private ReplayResult(int cRecovered, int cSkipped, int cScanned, int cApplied, int cAppliedUser, int cAppliedMeta,
                             int cAppliedResvd, long cNanosApply)
            {
            m_cRecovered      = cRecovered;
            m_cSkipped        = cSkipped;
            m_cScanned        = cScanned;
            m_cApplied        = cApplied;
            m_cAppliedUser    = cAppliedUser;
            m_cAppliedMeta    = cAppliedMeta;
            m_cAppliedResvd   = cAppliedResvd;
            m_cNanosApply     = cNanosApply;
            }

        /**
         * Return recovered entry count.
         *
         * @return recovered entry count
         */
        private int getRecovered()
            {
            return m_cRecovered;
            }

        /**
         * Return skipped entry count.
         *
         * @return skipped entry count
         */
        private int getSkipped()
            {
            return m_cSkipped;
            }

        /**
         * Return scanned replay-entry count.
         *
         * @return scanned replay-entry count
         */
        private int getScannedEntryCount()
            {
            return m_cScanned;
            }

        /**
         * Return applied post-checkpoint entry count.
         *
         * @return applied post-checkpoint entry count
         */
        private int getAppliedEntryCount()
            {
            return m_cApplied;
            }

        /**
         * Return count of applied user-extent entries.
         *
         * @return applied user-extent entry count
         */
        private int getAppliedUserEntryCount()
            {
            return m_cAppliedUser;
            }

        /**
         * Return count of applied META extent entries.
         *
         * @return applied META extent entry count
         */
        private int getAppliedMetaEntryCount()
            {
            return m_cAppliedMeta;
            }

        /**
         * Return count of applied reserved/meta extent entries.
         *
         * @return applied reserved/meta extent entry count
         */
        private int getAppliedReservedEntryCount()
            {
            return m_cAppliedResvd;
            }

        /**
         * Return time spent applying post-checkpoint entries.
         *
         * @return apply time in nanoseconds
         */
        private long getApplyNanos()
            {
            return m_cNanosApply;
            }

        /**
         * Recovered entry count.
         */
        private final int m_cRecovered;

        /**
         * Skipped entry count.
         */
        private final int m_cSkipped;

        /**
         * Scanned replay-entry count.
         */
        private final int m_cScanned;

        /**
         * Applied post-checkpoint entry count.
         */
        private final int m_cApplied;

        /**
         * Applied user-extent entry count.
         */
        private final int m_cAppliedUser;

        /**
         * Applied META extent entry count.
         */
        private final int m_cAppliedMeta;

        /**
         * Applied reserved/meta extent entry count.
         */
        private final int m_cAppliedResvd;

        /**
         * Time spent applying post-checkpoint entries.
         */
        private final long m_cNanosApply;

        }


    // ----- inner class: RecoveryResult -----------------------------------

    /**
     * Recovery outcome and recovered side structures.
     */
    public static class RecoveryResult
        {
        /**
         * Construct recovery result.
         *
         * @param cEntriesRecovered  number of recovered entries
         * @param cEntriesSkipped    number of skipped entries
         * @param mapExtentToKeys    recovered extent-to-keys mapping
         * @param timings            recovery timings
         * @param nCheckpointFileNo  checkpoint journal file number, or {@link Integer#MIN_VALUE}
         * @param ofCheckpoint       checkpoint journal offset
         */
        public RecoveryResult(int cEntriesRecovered, int cEntriesSkipped, Map<Long, Set<Binary>> mapExtentToKeys,
                              RecoveryTimings timings, int nCheckpointFileNo, long ofCheckpoint)
            {
            m_cEntriesRecovered = cEntriesRecovered;
            m_cEntriesSkipped   = cEntriesSkipped;

            Map<Long, Set<Binary>> mapCopy = new HashMap<>(Math.max(1, mapExtentToKeys.size()));
            for (Map.Entry<Long, Set<Binary>> entry : mapExtentToKeys.entrySet())
                {
                mapCopy.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
                }
            m_mapExtentToKeys = Collections.unmodifiableMap(mapCopy);
            m_timings         = timings;
            m_nCheckpointFileNo = nCheckpointFileNo;
            m_ofCheckpoint      = ofCheckpoint;
            }

        /**
         * Return recovered entry count.
         *
         * @return recovered entry count
         */
        public int getEntriesRecovered()
            {
            return m_cEntriesRecovered;
            }

        /**
         * Return skipped entry count.
         *
         * @return skipped entry count
         */
        public int getEntriesSkipped()
            {
            return m_cEntriesSkipped;
            }

        /**
         * Return recovered extent-to-keys mapping.
         *
         * @return recovered extent-to-keys mapping
         */
        public Map<Long, Set<Binary>> getExtentToKeys()
            {
            return m_mapExtentToKeys;
            }

        /**
         * Return phase timings collected during recovery.
         *
         * @return recovery timings
         */
        public RecoveryTimings getTimings()
            {
            return m_timings;
            }

        /**
         * Return checkpoint journal file number, or {@link Integer#MIN_VALUE}
         * if recovery did not use a checkpoint.
         *
         * @return checkpoint journal file number
         */
        public int getCheckpointFileNo()
            {
            return m_nCheckpointFileNo;
            }

        /**
         * Return checkpoint journal offset.
         *
         * @return checkpoint journal offset
         */
        public long getCheckpointOffset()
            {
            return m_ofCheckpoint;
            }

        /**
         * Recovered entry count.
         */
        private final int m_cEntriesRecovered;

        /**
         * Skipped entry count.
         */
        private final int m_cEntriesSkipped;

        /**
         * Recovered extent-to-keys map.
         */
        private final Map<Long, Set<Binary>> m_mapExtentToKeys;

        /**
         * Phase timings for the recovery.
         */
        private final RecoveryTimings m_timings;

        /**
         * Checkpoint journal file number, or {@link Integer#MIN_VALUE} if no
         * checkpoint was used.
         */
        private final int m_nCheckpointFileNo;

        /**
         * Checkpoint journal offset.
         */
        private final long m_ofCheckpoint;
        }

    // ----- inner class: RecoveryTimings ----------------------------------

    /**
     * Recovery phase timings collected during journal recovery.
     * <p>
     * Collection is always on so callers can safely use this as a cheap local
     * summary object even when recovery-metric reporting is disabled.
     * Reporting the captured values to {@link PersistenceRecoveryMetrics}
     * remains gated by {@link PersistenceRecoveryMetrics#isEnabled()}.
     */
    public static class RecoveryTimings
        {
        /**
         * Construct recovery timings.
         *
         * @param fCheckpoint         {@code true} if checkpoint file existed
         * @param cJournalFiles       number of discovered journal files
         * @param cKeys               number of recovered keys
         * @param cbCheckpointBytes   checkpoint bytes loaded
         * @param cNanosCheckpoint    time spent loading checkpoint contents
         * @param cNanosJournalScan   time spent scanning the journal state
         * @param cNanosJournalReplay time spent applying post-checkpoint entries
         * @param cEntriesScanned     number of replay entries scanned
         * @param cEntriesApplied     number of applied post-checkpoint entries
         * @param cNanosTotal         total recovery time
         */
        public RecoveryTimings(boolean fCheckpoint, int cJournalFiles, int cKeys, long cbCheckpointBytes,
                               long cNanosCheckpoint, long cNanosJournalScan, long cNanosJournalReplay,
                               int cEntriesScanned, int cEntriesApplied, long cNanosTotal)
            {
            m_fCheckpoint         = fCheckpoint;
            m_cJournalFiles       = cJournalFiles;
            m_cKeys               = cKeys;
            m_cbCheckpointBytes   = cbCheckpointBytes;
            m_cNanosCheckpoint    = cNanosCheckpoint;
            m_cNanosJournalScan   = cNanosJournalScan;
            m_cNanosJournalReplay = cNanosJournalReplay;
            m_cEntriesScanned     = cEntriesScanned;
            m_cEntriesApplied     = cEntriesApplied;
            m_cNanosTotal         = cNanosTotal;
            }

        /**
         * Return {@code true} if a checkpoint file was used.
         *
         * @return {@code true} if a checkpoint file was used
         */
        public boolean hasCheckpoint()
            {
            return m_fCheckpoint;
            }

        /**
         * Return discovered journal file count.
         *
         * @return journal file count
         */
        public int getJournalFileCount()
            {
            return m_cJournalFiles;
            }

        /**
         * Return recovered key count.
         *
         * @return recovered key count
         */
        public int getKeyCount()
            {
            return m_cKeys;
            }

        /**
         * Return checkpoint bytes loaded.
         *
         * @return checkpoint bytes loaded
         */
        public long getCheckpointBytes()
            {
            return m_cbCheckpointBytes;
            }

        /**
         * Return checkpoint-load time.
         *
         * @return checkpoint-load time
         */
        public long getCheckpointLoadNanos()
            {
            return m_cNanosCheckpoint;
            }

        /**
         * Return journal-scan time.
         *
         * @return journal-scan time
         */
        public long getJournalScanNanos()
            {
            return m_cNanosJournalScan;
            }

        /**
         * Return journal-replay time.
         *
         * @return journal-replay time
         */
        public long getJournalReplayNanos()
            {
            return m_cNanosJournalReplay;
            }

        /**
         * Return count of applied post-checkpoint entries.
         *
         * @return applied post-checkpoint entry count
         */
        public int getAppliedEntryCount()
            {
            return m_cEntriesApplied;
            }

        /**
         * Return count of replay entries scanned.
         *
         * @return scanned replay-entry count
         */
        public int getScannedEntryCount()
            {
            return m_cEntriesScanned;
            }

        /**
         * Return total recovery time.
         *
         * @return total recovery time
         */
        public long getTotalNanos()
            {
            return m_cNanosTotal;
            }

        /**
         * Whether checkpoint file existed.
         */
        private final boolean m_fCheckpoint;

        /**
         * Discovered journal file count.
         */
        private final int m_cJournalFiles;

        /**
         * Recovered key count.
         */
        private final int m_cKeys;

        /**
         * Checkpoint bytes loaded.
         */
        private final long m_cbCheckpointBytes;

        /**
         * Checkpoint-load time.
         */
        private final long m_cNanosCheckpoint;

        /**
         * Journal-scan time.
         */
        private final long m_cNanosJournalScan;

        /**
         * Journal-replay time.
         */
        private final long m_cNanosJournalReplay;

        /**
         * Scanned replay-entry count.
         */
        private final int m_cEntriesScanned;

        /**
         * Applied post-checkpoint entry count.
         */
        private final int m_cEntriesApplied;

        /**
         * Total recovery time.
         */
        private final long m_cNanosTotal;
        }


    // ----- constants ------------------------------------------------------

    /**
     * Checkpoint file name.
     */
    private static final String CHECKPOINT_FILE = "checkpoint.coh";

    /**
     * Return {@code true} if recovery should ignore checkpoint files and
     * rebuild state entirely by replaying journal entries.
     *
     * @return {@code true} if checkpoint files should be ignored
     */
    private static boolean isCheckpointIgnored()
        {
        return Boolean.getBoolean("coherence.persistence.journal.recovery.ignore.checkpoint");
        }

    }
