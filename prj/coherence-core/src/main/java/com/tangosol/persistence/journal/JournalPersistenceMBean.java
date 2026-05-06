/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.tangosol.net.management.annotation.Description;

/**
 * Standard MBean interface that exposes journal persistence observability
 * attributes.
 * <p>
 * This interface is the Phase 1 management surface described by the unified
 * persistence journal MBean / JFR design and accepted review:
 * {@code design/features/unified-persistence/design/journal-persistence-mbean-and-jfr.md}
 * and
 * {@code design/features/unified-persistence/reviews/2026-04-27-02-journal-persistence-mbean-and-jfr-design.md}.
 *
 * @author Aleks Seovic  2026.04.27
 * @since 26.04
 */
public interface JournalPersistenceMBean
    {
    // ----- attributes -----------------------------------------------------

    /**
     * Return the most recent journal recovery summary.
     *
     * @return the most recent journal recovery summary
     */
    @Description("The most recent journal recovery summary for this service, tagged with the journal manager that produced it, or an empty string if no recovery has been recorded.")
    public String getLastRecoverySummary();

    /**
     * Return the current journal compaction progress summary.
     *
     * @return the current journal compaction progress summary
     */
    @Description("A formatted summary of current journal compaction state, including per-manager breakdown when active and backup journal managers are present.")
    public String getCompactionProgress();

    /**
     * Return the total number of open journal extents.
     *
     * @return the total number of open journal extents
     */
    @Description("The total number of currently open journal extents across all journal persistence managers for this service.")
    public int getOpenExtentCount();
    }
