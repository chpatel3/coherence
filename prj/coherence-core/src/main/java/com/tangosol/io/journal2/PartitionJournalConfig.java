/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

/**
 * Configuration for a {@link PartitionJournal}.
 *
 * @author rl  2026.03.05
 * @since 26.04
 */
public class PartitionJournalConfig
    {
    // ----- helpers --------------------------------------------------------

    /**
     * Validate the effective configuration.
     *
     * @return this config
     */
    public PartitionJournalConfig validate()
        {
        if ((m_cbBlock & (ENTRY_ALIGNMENT - 1)) != 0)
            {
            throw new IllegalArgumentException("block size must be a multiple of " + ENTRY_ALIGNMENT
                    + " bytes: " + m_cbBlock);
            }

        if (m_cAdaptiveCheckpointMinWrites > m_cAdaptiveCheckpointMaxWrites)
            {
            throw new IllegalArgumentException("adaptive checkpoint min writes must be <= max writes");
            }

        if (m_cAdaptiveCheckpointInitialWrites > 0L)
            {
            if (m_cAdaptiveCheckpointMinWrites > m_cAdaptiveCheckpointInitialWrites)
                {
                throw new IllegalArgumentException("adaptive checkpoint min writes must be <= initial writes");
                }

            if (m_cAdaptiveCheckpointInitialWrites > m_cAdaptiveCheckpointMaxWrites)
                {
                throw new IllegalArgumentException("adaptive checkpoint initial writes must be <= max writes");
                }
            }

        return this;
        }

    /**
     * Return the maximum journal file size.
     *
     * @return maximum file size in bytes
     */
    public long getMaximumFileSize()
        {
        return m_cbMaxFile;
        }

    /**
     * Set the maximum journal file size.
     *
     * @param cbMaxFile  maximum file size in bytes
     *
     * @return this config
     */
    public PartitionJournalConfig setMaximumFileSize(long cbMaxFile)
        {
        if (cbMaxFile <= 0)
            {
            throw new IllegalArgumentException("maximum file size must be positive");
            }

        m_cbMaxFile = cbMaxFile;
        return this;
        }

    /**
     * Return the write block size.
     *
     * @return write block size in bytes
     */
    public int getBlockSize()
        {
        return m_cbBlock;
        }

    /**
     * Set the write block size.
     *
     * @param cbBlock  write block size in bytes
     *
     * @return this config
     */
    public PartitionJournalConfig setBlockSize(int cbBlock)
        {
        if (cbBlock <= 0)
            {
            throw new IllegalArgumentException("block size must be positive");
            }

        if ((cbBlock & (ENTRY_ALIGNMENT - 1)) != 0)
            {
            throw new IllegalArgumentException("block size must be a multiple of " + ENTRY_ALIGNMENT
                    + " bytes: " + cbBlock);
            }

        m_cbBlock = cbBlock;
        return this;
        }

    /**
     * Return the checkpoint bytes threshold. When the number of bytes written
     * to the journal since the last checkpoint exceeds this threshold, a new
     * checkpoint is written automatically.
     * <p>
     * A value of {@code 0} disables periodic checkpointing.
     *
     * @return checkpoint bytes threshold
     */
    public long getCheckpointBytesThreshold()
        {
        return m_cbCheckpointThreshold;
        }

    /**
     * Set the checkpoint bytes threshold.
     *
     * @param cbThreshold  checkpoint bytes threshold ({@code 0} to disable)
     *
     * @return this config
     */
    public PartitionJournalConfig setCheckpointBytesThreshold(long cbThreshold)
        {
        if (cbThreshold < 0)
            {
            throw new IllegalArgumentException("checkpoint threshold must be non-negative");
            }

        m_cbCheckpointThreshold = cbThreshold;
        return this;
        }

    /**
     * Return the minimum load factor for compaction candidates.
     *
     * @return the minimum load factor for compaction candidates
     */
    public double getCompactionMinLoadFactor()
        {
        return m_dflCompactionMinLoadFactor;
        }

    /**
     * Set the minimum load factor for compaction candidates.
     *
     * @param dflMinLoadFactor  minimum compaction load factor
     *
     * @return this config
     */
    public PartitionJournalConfig setCompactionMinLoadFactor(double dflMinLoadFactor)
        {
        if (dflMinLoadFactor < 0.0d || dflMinLoadFactor > 1.0d || Double.isNaN(dflMinLoadFactor))
            {
            throw new IllegalArgumentException("compaction min load factor must be between 0.0 and 1.0: "
                    + dflMinLoadFactor);
            }

        m_dflCompactionMinLoadFactor = dflMinLoadFactor;
        return this;
        }

    /**
     * Return the initial write-count trigger for adaptive checkpointing.
     * <p>
     * A value of {@code 0} disables the adaptive write-count trigger while
     * leaving byte-threshold checkpointing unchanged.
     *
     * @return the initial adaptive checkpoint write trigger
     */
    public long getAdaptiveCheckpointInitialWrites()
        {
        return m_cAdaptiveCheckpointInitialWrites;
        }

    /**
     * Set the initial write-count trigger for adaptive checkpointing.
     *
     * @param cWrites  initial adaptive checkpoint write trigger
     *
     * @return this config
     */
    public PartitionJournalConfig setAdaptiveCheckpointInitialWrites(long cWrites)
        {
        if (cWrites < 0L)
            {
            throw new IllegalArgumentException("adaptive checkpoint initial writes must be non-negative");
            }

        m_cAdaptiveCheckpointInitialWrites = cWrites;
        return this;
        }

    /**
     * Return the minimum adaptive checkpoint write trigger.
     *
     * @return the minimum adaptive checkpoint write trigger
     */
    public long getAdaptiveCheckpointMinWrites()
        {
        return m_cAdaptiveCheckpointMinWrites;
        }

    /**
     * Set the minimum adaptive checkpoint write trigger.
     *
     * @param cWrites  minimum adaptive checkpoint write trigger
     *
     * @return this config
     */
    public PartitionJournalConfig setAdaptiveCheckpointMinWrites(long cWrites)
        {
        if (cWrites <= 0L)
            {
            throw new IllegalArgumentException("adaptive checkpoint min writes must be positive");
            }

        m_cAdaptiveCheckpointMinWrites = cWrites;
        return this;
        }

    /**
     * Return the maximum adaptive checkpoint write trigger.
     *
     * @return the maximum adaptive checkpoint write trigger
     */
    public long getAdaptiveCheckpointMaxWrites()
        {
        return m_cAdaptiveCheckpointMaxWrites;
        }

    /**
     * Set the maximum adaptive checkpoint write trigger.
     *
     * @param cWrites  maximum adaptive checkpoint write trigger
     *
     * @return this config
     */
    public PartitionJournalConfig setAdaptiveCheckpointMaxWrites(long cWrites)
        {
        if (cWrites <= 0L)
            {
            throw new IllegalArgumentException("adaptive checkpoint max writes must be positive");
            }

        m_cAdaptiveCheckpointMaxWrites = cWrites;
        return this;
        }

    /**
     * Return the checkpoint-duration budget, in milliseconds, used by the
     * adaptive backoff heuristic.
     *
     * @return checkpoint duration budget in milliseconds
     */
    public double getAdaptiveCheckpointTargetMillis()
        {
        return m_dflAdaptiveCheckpointTargetMillis;
        }

    /**
     * Set the checkpoint-duration budget, in milliseconds, used by the
     * adaptive backoff heuristic.
     *
     * @param dflMillis  checkpoint duration budget in milliseconds
     *
     * @return this config
     */
    public PartitionJournalConfig setAdaptiveCheckpointTargetMillis(double dflMillis)
        {
        if (dflMillis <= 0.0d)
            {
            throw new IllegalArgumentException("adaptive checkpoint target millis must be positive");
            }

        m_dflAdaptiveCheckpointTargetMillis = dflMillis;
        return this;
        }

    /**
     * Return the number of adaptive checkpoint samples to ignore for warmup.
     *
     * @return adaptive checkpoint warmup sample count
     */
    public int getAdaptiveCheckpointWarmupCount()
        {
        return m_cAdaptiveCheckpointWarmupCount;
        }

    /**
     * Set the number of adaptive checkpoint samples to ignore for warmup.
     *
     * @param cWarmup  adaptive checkpoint warmup sample count
     *
     * @return this config
     */
    public PartitionJournalConfig setAdaptiveCheckpointWarmupCount(int cWarmup)
        {
        if (cWarmup < 0)
            {
            throw new IllegalArgumentException("adaptive checkpoint warmup count must be non-negative");
            }

        m_cAdaptiveCheckpointWarmupCount = cWarmup;
        return this;
        }

    /**
     * Return {@code true} if writes should be synchronized on commit.
     *
     * @return {@code true} if writes should be synchronized on commit
     */
    public boolean isWriteSynchronous()
        {
        return m_fWriteSynchronous;
        }

    /**
     * Set whether writes should be synchronized on commit.
     *
     * @param fWriteSynchronous  {@code true} to synchronize writes on commit
     *
     * @return this config
     */
    public PartitionJournalConfig setWriteSynchronous(boolean fWriteSynchronous)
        {
        m_fWriteSynchronous = fWriteSynchronous;
        return this;
        }

    /**
     * Return the maximum aligned entry size that should use the buffered
     * single-write append path.
     *
     * @return buffered append threshold in bytes
     */
    public int getBufferedAppendThreshold()
        {
        return m_cbBufferedAppendThreshold;
        }

    /**
     * Set the maximum aligned entry size that should use the buffered
     * single-write append path.
     *
     * @param cbThreshold  buffered append threshold in bytes
     *
     * @return this config
     */
    public PartitionJournalConfig setBufferedAppendThreshold(int cbThreshold)
        {
        if (cbThreshold < 0)
            {
            throw new IllegalArgumentException("buffered append threshold must be non-negative");
            }

        m_cbBufferedAppendThreshold = cbThreshold;
        return this;
        }

    @Override
    public String toString()
        {
        return "PartitionJournalConfig{"
                + "maximumFileSize=" + m_cbMaxFile
                + ", blockSize=" + m_cbBlock
                + ", checkpointBytesThreshold=" + m_cbCheckpointThreshold
                + ", compactionMinLoadFactor=" + m_dflCompactionMinLoadFactor
                + ", adaptiveCheckpointInitialWrites=" + m_cAdaptiveCheckpointInitialWrites
                + ", adaptiveCheckpointMinWrites=" + m_cAdaptiveCheckpointMinWrites
                + ", adaptiveCheckpointMaxWrites=" + m_cAdaptiveCheckpointMaxWrites
                + ", adaptiveCheckpointTargetMillis=" + m_dflAdaptiveCheckpointTargetMillis
                + ", adaptiveCheckpointWarmupCount=" + m_cAdaptiveCheckpointWarmupCount
                + ", writeSynchronous=" + m_fWriteSynchronous
                + ", bufferedAppendThreshold=" + m_cbBufferedAppendThreshold
                + '}';
        }


    // ----- data members ---------------------------------------------------

    /**
     * Maximum journal file size.
     */
    private long m_cbMaxFile = DEFAULT_MAX_FILE_SIZE;

    /**
     * Block size.
     */
    private int m_cbBlock = DEFAULT_BLOCK_SIZE;

    /**
     * Checkpoint bytes threshold.
     */
    private long m_cbCheckpointThreshold = DEFAULT_CHECKPOINT_THRESHOLD;

    /**
     * Minimum load factor for compaction candidates.
     */
    private double m_dflCompactionMinLoadFactor = DEFAULT_COMPACTION_MIN_LOAD_FACTOR;

    /**
     * Initial write-count trigger for adaptive checkpointing.
     */
    private long m_cAdaptiveCheckpointInitialWrites = DEFAULT_ADAPTIVE_CHECKPOINT_INITIAL_WRITES;

    /**
     * Minimum adaptive checkpoint write trigger.
     */
    private long m_cAdaptiveCheckpointMinWrites = DEFAULT_ADAPTIVE_CHECKPOINT_MIN_WRITES;

    /**
     * Maximum adaptive checkpoint write trigger.
     */
    private long m_cAdaptiveCheckpointMaxWrites = DEFAULT_ADAPTIVE_CHECKPOINT_MAX_WRITES;

    /**
     * Checkpoint-duration budget, in milliseconds, for adaptive backoff.
     */
    private double m_dflAdaptiveCheckpointTargetMillis = DEFAULT_ADAPTIVE_CHECKPOINT_TARGET_MILLIS;

    /**
     * Number of adaptive checkpoint samples to ignore for warmup.
     */
    private int m_cAdaptiveCheckpointWarmupCount = DEFAULT_ADAPTIVE_CHECKPOINT_WARMUP_COUNT;

    /**
     * Whether writes should be synchronized on commit.
     */
    private boolean m_fWriteSynchronous = DEFAULT_WRITE_SYNCHRONOUS;

    /**
     * Maximum aligned entry size that uses the buffered append path.
     */
    private int m_cbBufferedAppendThreshold = DEFAULT_BUFFERED_APPEND_THRESHOLD;


    // ----- constants ------------------------------------------------------

    /**
     * Default max file size.
     */
    public static final long DEFAULT_MAX_FILE_SIZE = 128L * 1024L * 1024L;

    /**
     * Default write block size.
     */
    public static final int DEFAULT_BLOCK_SIZE = 256 * 1024;

    /**
     * Required journal entry alignment in bytes.
     */
    public static final int ENTRY_ALIGNMENT = 16;

    /**
     * Default checkpoint threshold (64 MB).
     */
    public static final long DEFAULT_CHECKPOINT_THRESHOLD = 64L * 1024L * 1024L;

    /**
     * Default minimum load factor for compaction candidates.
     */
    public static final double DEFAULT_COMPACTION_MIN_LOAD_FACTOR = 0.5d;

    /**
     * Default initial adaptive checkpoint write trigger.
     */
    public static final long DEFAULT_ADAPTIVE_CHECKPOINT_INITIAL_WRITES = 1000L;

    /**
     * Default minimum adaptive checkpoint write trigger.
     */
    public static final long DEFAULT_ADAPTIVE_CHECKPOINT_MIN_WRITES = 1000L;

    /**
     * Default maximum adaptive checkpoint write trigger.
     */
    public static final long DEFAULT_ADAPTIVE_CHECKPOINT_MAX_WRITES = 1_000_000L;

    /**
     * Default checkpoint-duration budget, in milliseconds, for adaptive
     * backoff.
     */
    public static final double DEFAULT_ADAPTIVE_CHECKPOINT_TARGET_MILLIS = 2.0d;

    /**
     * Default number of adaptive checkpoint samples to ignore for warmup.
     */
    public static final int DEFAULT_ADAPTIVE_CHECKPOINT_WARMUP_COUNT = 4;

    /**
     * Default write synchronization policy.
     */
    public static final boolean DEFAULT_WRITE_SYNCHRONOUS = false;

    /**
     * Default buffered append threshold.
     */
    public static final int DEFAULT_BUFFERED_APPEND_THRESHOLD = 16 * 1024;
    }
