/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * JFR event emitted for each journal recovery phase.
 *
 * @author Aleks Seovic  2026.04.27
 * @since 26.04
 */
@Name("com.tangosol.persistence.journal.JournalRecovery")
@Label("Journal Recovery")
@Category({"Coherence", "Persistence", "Journal"})
@StackTrace(false)
public class JournalRecoveryEvent
        extends Event
    {
    /**
     * Recovery phase.
     */
    @Label("Phase")
    @Description("Recovery phase: checkpoint-load, journal-scan, journal-replay, total.")
    public String phase;

    /**
     * Absolute path of the recovered extent directory.
     */
    @Description("Absolute path of the recovered extent directory.")
    public String extentPath;

    /**
     * {@code true} if a checkpoint file was loaded for this extent.
     */
    @Description("True if a checkpoint file was loaded for this extent.")
    public boolean checkpoint;

    /**
     * Number of journal files scanned for this extent.
     */
    @Description("Number of journal files scanned for this extent.")
    public int fileCount;

    /**
     * Number of keys recovered for this extent.
     */
    @Description("Number of keys recovered for this extent.")
    public long keyCount;

    /**
     * Bytes loaded from the checkpoint file, or {@code -1} when not
     * applicable to this phase.
     */
    @DataAmount(DataAmount.BYTES)
    @Description("Bytes loaded from the checkpoint file, or -1 when not applicable to this phase.")
    public long checkpointBytes;

    /**
     * Phase duration in nanoseconds.
     */
    @Timespan(Timespan.NANOSECONDS)
    @Description("Phase duration in nanoseconds.")
    public long durationNanos;
    }
