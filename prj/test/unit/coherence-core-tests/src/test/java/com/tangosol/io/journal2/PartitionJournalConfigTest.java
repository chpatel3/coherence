/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.io.journal2;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link PartitionJournalConfig}.
 *
 * @author rl  2026.04.03
 * @since 26.04
 */
public class PartitionJournalConfigTest
    {
    @Test
    public void testDefaults()
        {
        PartitionJournalConfig config = new PartitionJournalConfig();

        assertEquals(PartitionJournalConfig.DEFAULT_MAX_FILE_SIZE, config.getMaximumFileSize());
        assertEquals(PartitionJournalConfig.DEFAULT_BLOCK_SIZE, config.getBlockSize());
        assertEquals(PartitionJournalConfig.DEFAULT_CHECKPOINT_THRESHOLD, config.getCheckpointBytesThreshold());
        assertEquals(PartitionJournalConfig.DEFAULT_COMPACTION_MIN_LOAD_FACTOR,
                config.getCompactionMinLoadFactor(), 0.0d);
        assertEquals(PartitionJournalConfig.DEFAULT_ADAPTIVE_CHECKPOINT_INITIAL_WRITES,
                config.getAdaptiveCheckpointInitialWrites());
        assertEquals(PartitionJournalConfig.DEFAULT_ADAPTIVE_CHECKPOINT_MIN_WRITES,
                config.getAdaptiveCheckpointMinWrites());
        assertEquals(PartitionJournalConfig.DEFAULT_ADAPTIVE_CHECKPOINT_MAX_WRITES,
                config.getAdaptiveCheckpointMaxWrites());
        assertEquals(PartitionJournalConfig.DEFAULT_ADAPTIVE_CHECKPOINT_TARGET_MILLIS,
                config.getAdaptiveCheckpointTargetMillis(), 0.0d);
        assertEquals(PartitionJournalConfig.DEFAULT_ADAPTIVE_CHECKPOINT_WARMUP_COUNT,
                config.getAdaptiveCheckpointWarmupCount());
        assertEquals(PartitionJournalConfig.DEFAULT_BUFFERED_APPEND_THRESHOLD, config.getBufferedAppendThreshold());
        assertFalse(config.isWriteSynchronous());
        }

    @Test
    public void testRejectsNonPositiveMaximumFileSize()
        {
        PartitionJournalConfig config = new PartitionJournalConfig();

        assertRejects(() -> config.setMaximumFileSize(0));
        assertRejects(() -> config.setMaximumFileSize(-1));
        }

    @Test
    public void testRejectsNonPositiveBlockSize()
        {
        PartitionJournalConfig config = new PartitionJournalConfig();

        assertRejects(() -> config.setBlockSize(0));
        assertRejects(() -> config.setBlockSize(-1));
        assertRejects(() -> config.setBlockSize(15));
        }

    @Test
    public void testRejectsNegativeCheckpointThreshold()
        {
        PartitionJournalConfig config = new PartitionJournalConfig();

        config.setCheckpointBytesThreshold(0);
        assertEquals(0L, config.getCheckpointBytesThreshold());
        assertRejects(() -> config.setCheckpointBytesThreshold(-1));
        }

    @Test
    public void testRejectsInvalidCompactionMinLoadFactor()
        {
        PartitionJournalConfig config = new PartitionJournalConfig();

        config.setCompactionMinLoadFactor(0.0d);
        assertEquals(0.0d, config.getCompactionMinLoadFactor(), 0.0d);

        config.setCompactionMinLoadFactor(1.0d);
        assertEquals(1.0d, config.getCompactionMinLoadFactor(), 0.0d);

        assertRejects(() -> config.setCompactionMinLoadFactor(-0.1d));
        assertRejects(() -> config.setCompactionMinLoadFactor(1.1d));
        assertRejects(() -> config.setCompactionMinLoadFactor(Double.NaN));
        }

    @Test
    public void testRejectsInvalidAdaptiveCheckpointSettings()
        {
        PartitionJournalConfig config = new PartitionJournalConfig();

        config.setAdaptiveCheckpointInitialWrites(0L);
        assertEquals(0L, config.getAdaptiveCheckpointInitialWrites());

        assertRejects(() -> config.setAdaptiveCheckpointInitialWrites(-1L));
        assertRejects(() -> config.setAdaptiveCheckpointMinWrites(0L));
        assertRejects(() -> config.setAdaptiveCheckpointMaxWrites(0L));
        assertRejects(() -> config.setAdaptiveCheckpointTargetMillis(0.0d));
        assertRejects(() -> config.setAdaptiveCheckpointWarmupCount(-1));
        }

    @Test
    public void testValidateAcceptsSmallMaximumFileSize()
        {
        PartitionJournalConfig config = new PartitionJournalConfig()
                .setMaximumFileSize(256)
                .setBlockSize(16);

        config.validate();
        }

    @Test
    public void testValidateRejectsInconsistentAdaptiveCheckpointBounds()
        {
        PartitionJournalConfig config = new PartitionJournalConfig()
                .setAdaptiveCheckpointInitialWrites(100L)
                .setAdaptiveCheckpointMinWrites(200L);

        assertRejects(config::validate);

        config = new PartitionJournalConfig()
                .setAdaptiveCheckpointInitialWrites(100L)
                .setAdaptiveCheckpointMaxWrites(50L);

        assertRejects(config::validate);

        config = new PartitionJournalConfig()
                .setAdaptiveCheckpointInitialWrites(0L)
                .setAdaptiveCheckpointMinWrites(200L)
                .setAdaptiveCheckpointMaxWrites(100L);

        assertRejects(config::validate);
        }

    @Test
    public void testToStringIncludesEffectiveSettings()
        {
        PartitionJournalConfig config = new PartitionJournalConfig()
                .setMaximumFileSize(2048)
                .setBlockSize(512)
                .setCheckpointBytesThreshold(1024)
                .setCompactionMinLoadFactor(0.9d)
                .setAdaptiveCheckpointInitialWrites(2000L)
                .setAdaptiveCheckpointMinWrites(1000L)
                .setAdaptiveCheckpointMaxWrites(8000L)
                .setAdaptiveCheckpointTargetMillis(3.5d)
                .setAdaptiveCheckpointWarmupCount(6)
                .setBufferedAppendThreshold(128)
                .setWriteSynchronous(true);

        String sConfig = config.toString();

        assertTrue(sConfig.contains("maximumFileSize=2048"));
        assertTrue(sConfig.contains("blockSize=512"));
        assertTrue(sConfig.contains("checkpointBytesThreshold=1024"));
        assertTrue(sConfig.contains("compactionMinLoadFactor=0.9"));
        assertTrue(sConfig.contains("adaptiveCheckpointInitialWrites=2000"));
        assertTrue(sConfig.contains("adaptiveCheckpointMinWrites=1000"));
        assertTrue(sConfig.contains("adaptiveCheckpointMaxWrites=8000"));
        assertTrue(sConfig.contains("adaptiveCheckpointTargetMillis=3.5"));
        assertTrue(sConfig.contains("adaptiveCheckpointWarmupCount=6"));
        assertTrue(sConfig.contains("bufferedAppendThreshold=128"));
        assertTrue(sConfig.contains("writeSynchronous=true"));
        }

    /**
     * Assert the supplied action throws {@link IllegalArgumentException}.
     *
     * @param action  action to execute
     */
    private void assertRejects(Runnable action)
        {
        try
            {
            action.run();
            fail("expected IllegalArgumentException");
            }
        catch (IllegalArgumentException e)
            {
            // expected
            }
        }
    }
