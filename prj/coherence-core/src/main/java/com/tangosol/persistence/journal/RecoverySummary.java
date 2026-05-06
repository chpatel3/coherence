/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import java.util.Objects;

/**
 * Immutable journal recovery summary recorded by a
 * {@link JournalPersistenceManager}.
 * <p>
 * The timestamp is wall-clock milliseconds captured from
 * {@link System#currentTimeMillis()} when the summary is recorded. It is used
 * by the service-level journal MBean to decide which attached manager
 * produced the most recent recovery summary; the timestamp is not exposed
 * directly as an operator-facing MBean attribute.
 *
 * @author Aleks Seovic  2026.04.27
 * @since 26.04
 */
public final class RecoverySummary
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Create a new {@link RecoverySummary}.
     *
     * @param sText            operator-facing summary text
     * @param ldtTimestamp     wall-clock timestamp in milliseconds
     * @param sSourceTag       optional source tag
     */
    private RecoverySummary(String sText, long ldtTimestamp, String sSourceTag)
        {
        f_sText          = sText == null ? "" : sText;
        f_ldtTimestamp   = ldtTimestamp;
        f_sSourceTag     = sSourceTag == null ? "" : sSourceTag;
        }

    // ----- factories ------------------------------------------------------

    /**
     * Create a new {@link RecoverySummary}.
     *
     * @param sText            operator-facing summary text
     * @param ldtTimestamp     wall-clock timestamp in milliseconds
     * @param sSourceTag       optional source tag
     *
     * @return the recovery summary
     */
    public static RecoverySummary of(String sText, long ldtTimestamp, String sSourceTag)
        {
        return new RecoverySummary(sText, ldtTimestamp, sSourceTag);
        }

    // ----- accessors ------------------------------------------------------

    /**
     * Return the operator-facing summary text.
     *
     * @return the operator-facing summary text
     */
    public String getText()
        {
        return f_sText;
        }

    /**
     * Return the wall-clock recording timestamp in milliseconds.
     *
     * @return the wall-clock recording timestamp in milliseconds
     */
    public long getTimestampMillis()
        {
        return f_ldtTimestamp;
        }

    /**
     * Return the optional source tag.
     *
     * @return the optional source tag
     */
    public String getSourceTag()
        {
        return f_sSourceTag;
        }

    // ----- Object methods -------------------------------------------------

    @Override
    public boolean equals(Object o)
        {
        if (o == this)
            {
            return true;
            }

        if (!(o instanceof RecoverySummary))
            {
            return false;
            }

        RecoverySummary that = (RecoverySummary) o;
        return f_ldtTimestamp == that.f_ldtTimestamp
                && Objects.equals(f_sText, that.f_sText)
                && Objects.equals(f_sSourceTag, that.f_sSourceTag);
        }

    @Override
    public int hashCode()
        {
        return Objects.hash(f_sText, f_ldtTimestamp, f_sSourceTag);
        }

    @Override
    public String toString()
        {
        return "RecoverySummary{text='" + f_sText + "', timestampMillis="
                + f_ldtTimestamp + ", sourceTag='" + f_sSourceTag + "'}";
        }

    // ----- constants ------------------------------------------------------

    /**
     * Empty recovery summary sentinel.
     */
    public static final RecoverySummary EMPTY = new RecoverySummary("", 0L, "");

    // ----- data members ---------------------------------------------------

    /**
     * Operator-facing summary text.
     */
    private final String f_sText;

    /**
     * Wall-clock recording timestamp in milliseconds.
     */
    private final long f_ldtTimestamp;

    /**
     * Optional source tag.
     */
    private final String f_sSourceTag;
    }
