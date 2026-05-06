/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence;

/**
 * Aggregated persistence recovery metrics with a small, stable reporting
 * surface.
 *
 * @author rl  2026.04.01
 * @since 26.04
 */
public final class PersistenceRecoveryMetrics
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Hidden utility constructor.
     */
    private PersistenceRecoveryMetrics()
        {
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Return {@code true} if aggregated recovery timing is enabled.
     *
     * @return {@code true} if aggregated recovery timing is enabled
     */
    public static boolean isEnabled()
        {
        return ENABLED;
        }

    /**
     * Begin a new aggregated recovery summary.
     */
    public static void beginRecovery()
        {
        if (!ENABLED)
            {
            return;
            }

        synchronized (LOCK)
            {
            if (m_cActiveRecoveries++ == 0)
                {
                m_summary = new Summary();
                }
            }
        }

    /**
     * Record a recovery summary contribution.
     *
     * @param cRecoveries         number of recovery operations represented
     * @param cKeys               number of recovered keys
     * @param cbCheckpointBytes   checkpoint bytes loaded
     * @param cNanosTotal         total recovery time
     * @param cNanosCheckpoint    checkpoint-load time
     * @param cNanosJournalScan   journal-scan time
     * @param cNanosJournalReplay journal-replay time
     * @param cNanosBackupRecovery backup-recovery time
     */
    public static void recordSummary(long cRecoveries, long cKeys, long cbCheckpointBytes, long cNanosTotal,
            long cNanosCheckpoint, long cNanosJournalScan, long cNanosJournalReplay, long cNanosBackupRecovery)
        {
        if (!ENABLED)
            {
            return;
            }

        synchronized (LOCK)
            {
            m_summary.record(cRecoveries, cKeys, cbCheckpointBytes, cNanosTotal, cNanosCheckpoint,
                    cNanosJournalScan, cNanosJournalReplay, cNanosBackupRecovery);
            }
        }

    /**
     * End the current aggregated recovery summary and return its formatted
     * contents.
     *
     * @return the formatted summary, or {@code null} if no metrics were
     *         recorded
     */
    public static String endRecovery()
        {
        if (!ENABLED)
            {
            return null;
            }

        synchronized (LOCK)
            {
            if (m_cActiveRecoveries > 0)
                {
                --m_cActiveRecoveries;
                }

            return m_summary.isEmpty() ? null : m_summary.format();
            }
        }


    // ----- inner class: Summary ------------------------------------------

    /**
     * Mutable aggregated recovery summary.
     */
    private static final class Summary
        {
        /**
         * Record an additional recovery summary contribution.
         *
         * @param cRecoveries          number of recovery operations represented
         * @param cKeys                number of recovered keys
         * @param cbCheckpointBytes    checkpoint bytes loaded
         * @param cNanosTotal          total recovery time
         * @param cNanosCheckpoint     checkpoint-load time
         * @param cNanosJournalScan    journal-scan time
         * @param cNanosJournalReplay  journal-replay time
         * @param cNanosBackupRecovery backup-recovery time
         */
        private void record(long cRecoveries, long cKeys, long cbCheckpointBytes, long cNanosTotal,
                long cNanosCheckpoint, long cNanosJournalScan, long cNanosJournalReplay,
                long cNanosBackupRecovery)
            {
            m_cRecoveries          += cRecoveries;
            m_cKeys                += cKeys;
            m_cbCheckpointBytes    += cbCheckpointBytes;
            m_cNanosTotal          += cNanosTotal;
            m_cNanosCheckpoint     += cNanosCheckpoint;
            m_cNanosJournalScan    += cNanosJournalScan;
            m_cNanosJournalReplay  += cNanosJournalReplay;
            m_cNanosBackupRecovery += cNanosBackupRecovery;
            }

        /**
         * Return {@code true} if no recovery metrics were recorded.
         *
         * @return {@code true} if no recovery metrics were recorded
         */
        private boolean isEmpty()
            {
            return m_cRecoveries == 0L
                    && m_cKeys == 0L
                    && m_cbCheckpointBytes == 0L
                    && m_cNanosTotal == 0L
                    && m_cNanosCheckpoint == 0L
                    && m_cNanosJournalScan == 0L
                    && m_cNanosJournalReplay == 0L
                    && m_cNanosBackupRecovery == 0L;
            }

        /**
         * Format the summary as a single line suitable for logging.
         *
         * @return the formatted summary
         */
        private String format()
            {
            return "Persistence recovery summary: recoveries=" + m_cRecoveries
                    + ", keys=" + m_cKeys
                    + ", checkpoint-bytes=" + m_cbCheckpointBytes
                    + ", total-ms=" + formatMillis(m_cNanosTotal)
                    + ", checkpoint-load-ms=" + formatMillis(m_cNanosCheckpoint)
                    + ", journal-scan-ms=" + formatMillis(m_cNanosJournalScan)
                    + ", journal-replay-ms=" + formatMillis(m_cNanosJournalReplay)
                    + ", backup-recovery-ms=" + formatMillis(m_cNanosBackupRecovery);
            }

        /**
         * Number of recovery operations represented by this summary.
         */
        private long m_cRecoveries;

        /**
         * Number of recovered keys.
         */
        private long m_cKeys;

        /**
         * Number of checkpoint bytes loaded.
         */
        private long m_cbCheckpointBytes;

        /**
         * Total recovery time in nanoseconds.
         */
        private long m_cNanosTotal;

        /**
         * Checkpoint-load time in nanoseconds.
         */
        private long m_cNanosCheckpoint;

        /**
         * Journal-scan time in nanoseconds.
         */
        private long m_cNanosJournalScan;

        /**
         * Journal-replay time in nanoseconds.
         */
        private long m_cNanosJournalReplay;

        /**
         * Backup-recovery time in nanoseconds.
         */
        private long m_cNanosBackupRecovery;
        }

    /**
     * Format nanoseconds as milliseconds with three decimal places.
     *
     * @param cNanos  the duration in nanoseconds
     *
     * @return formatted milliseconds
     */
    private static String formatMillis(long cNanos)
        {
        return String.format("%.3f", cNanos / 1_000_000.0d);
        }


    // ----- constants ------------------------------------------------------

    /**
     * Set to {@code true} to collect aggregated recovery summaries.
     */
    private static final boolean ENABLED =
            Boolean.getBoolean("coherence.persistence.recovery.timing");

    /**
     * Summary mutation lock.
     */
    private static final Object LOCK = new Object();


    // ----- data members ---------------------------------------------------

    /**
     * The active aggregated summary.
     */
    private static Summary m_summary = new Summary();

    /**
     * Count of active recovery windows.
     */
    private static int m_cActiveRecoveries;
    }
