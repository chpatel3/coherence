/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.tangosol.io.journal2.PartitionJournalConfig;

import com.tangosol.persistence.AbstractPersistenceManager;
import com.tangosol.persistence.AbstractPersistencePerformanceTest;

import java.io.IOException;

/**
 * Performance test for a {@link JournalPersistenceManager}.
 *
 * @author rl  2026.04.01
 * @since 26.04
 */
public class JournalPerformanceTest
        extends AbstractPersistencePerformanceTest
    {
    // ----- constants -----------------------------------------------------

    /**
     * System property controlling the journal buffered append threshold.
     */
    public static final String PROP_BUFFERED_APPEND_THRESHOLD = "journal.buffered.append.threshold";

    // ----- AbstractPersistencePerformanceTest methods ---------------------

    @Override
    protected AbstractPersistenceManager createPersistenceManager()
            throws IOException
        {
        int nThreshold = Integer.getInteger(PROP_BUFFERED_APPEND_THRESHOLD,
                PartitionJournalConfig.DEFAULT_BUFFERED_APPEND_THRESHOLD);

        System.out.println("Journal buffered-append threshold: " + nThreshold + " bytes");

        JournalPersistenceManager manager = new JournalPersistenceManager(m_file, null, null);
        manager.setJournalConfig(new PartitionJournalConfig()
                .setMaximumFileSize(1024 * 1024)
                .setBufferedAppendThreshold(nThreshold)
                .setWriteSynchronous(false));
        manager.setDaemonPool(m_pool);
        return manager;
        }
    }
