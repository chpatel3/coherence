/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.tangosol.internal.util.DaemonPool;
import com.tangosol.internal.util.Daemons;
import com.tangosol.internal.util.DefaultDaemonPoolDependencies;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;
import com.tangosol.io.journal2.PartitionJournalConfig;

import com.tangosol.persistence.AbstractPersistenceManager.AbstractPersistentStore;

import com.tangosol.util.Binary;

import java.io.File;
import java.io.IOException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.nio.file.Path;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JournalRecoveryEvent}.
 *
 * @author Aleks Seovic  2026.04.27
 * @since 26.04
 */
public class JournalRecoveryEventTest
    {
    @Before
    public void setup()
            throws IOException
        {
        m_file = FileHelper.createTempDir();

        DefaultDaemonPoolDependencies deps = new DefaultDaemonPoolDependencies();
        deps.setThreadCount(1);

        m_pool = Daemons.newDaemonPool(deps);
        m_pool.start();
        }

    @After
    public void cleanup()
            throws IOException
        {
        if (m_manager != null)
            {
            m_manager.release();
            }
        if (m_pool != null)
            {
            m_pool.stop();
            }
        if (m_file != null)
            {
            FileHelper.deleteDir(m_file);
            }
        }

    @Test
    public void testJournalRecoveryEventsAreRecorded()
            throws Exception
        {
        Binary binKey    = new Binary(new byte[] {1});
        Binary binValue  = new Binary(new byte[] {11});
        String sExtent   = createCheckpointedStore(binKey, binValue);
        Path   pathEvent = new File(m_file, "journal-recovery.jfr").toPath();

        try (Recording recording = new Recording())
            {
            recording.enable(EVENT_NAME);
            recording.start();

            AbstractPersistentStore store = (AbstractPersistentStore) m_manager.open(STORE_ID, null);

            assertEquals(binValue, store.load(1L, binKey));

            recording.stop();
            recording.dump(pathEvent);
            }

        List<RecordedEvent> listEvents = RecordingFile.readAllEvents(pathEvent)
                .stream()
                .filter(event -> EVENT_NAME.equals(event.getEventType().getName()))
                .filter(event -> sExtent.equals(event.getString("extentPath")))
                .collect(Collectors.toList());

        assertEquals("expected one journal recovery event per phase", 4, listEvents.size());

        Map<String, RecordedEvent> mapPhase = new HashMap<>();
        for (RecordedEvent event : listEvents)
            {
            String sPhase = event.getString("phase");
            assertFalse("duplicate phase " + sPhase, mapPhase.containsKey(sPhase));
            mapPhase.put(sPhase, event);

            assertFalse(event.getString("extentPath").isEmpty());
            assertTrue(event.getLong("durationNanos") >= 0L);
            assertTrue(event.getLong("keyCount") >= 0L);
            assertTrue(event.getInt("fileCount") >= 0);
            }

        assertPhase(mapPhase, "checkpoint-load");
        assertPhase(mapPhase, "journal-scan");
        assertPhase(mapPhase, "journal-replay");
        assertPhase(mapPhase, "total");

        RecordedEvent eventCheckpoint = mapPhase.get("checkpoint-load");

        assertTrue(eventCheckpoint.getBoolean("checkpoint"));
        assertTrue(eventCheckpoint.getLong("checkpointBytes") >= 0L);
        assertEquals(-1L, mapPhase.get("journal-scan").getLong("checkpointBytes"));
        assertEquals(-1L, mapPhase.get("journal-replay").getLong("checkpointBytes"));
        assertEquals(-1L, mapPhase.get("total").getLong("checkpointBytes"));
        }

    @Test
    public void testRecoverySucceedsWhenJfrIsDisabled()
            throws Exception
        {
        Binary binKey   = new Binary(new byte[] {2});
        Binary binValue = new Binary(new byte[] {22});

        createCheckpointedStore(binKey, binValue);

        AbstractPersistentStore store = (AbstractPersistentStore) m_manager.open(STORE_ID, null);

        assertEquals(binValue, store.load(1L, binKey));

        RecoverySummary summary = m_manager.getLastRecoverySummary();

        assertNotNull(summary);
        assertFalse(summary.getText().isEmpty());
        }

    // ----- helper methods -------------------------------------------------

    /**
     * Create a store with a forced checkpoint and close it so the next open
     * performs checkpoint-backed recovery.
     *
     * @param binKey    key to store
     * @param binValue  value to store
     *
     * @return the extent directory path
     *
     * @throws Exception on test failure
     */
    private String createCheckpointedStore(Binary binKey, ReadBuffer binValue)
            throws Exception
        {
        m_manager = new JournalPersistenceManager(m_file, null, "test-journal-recovery-event");
        m_manager.setJournalConfig(new PartitionJournalConfig().setMaximumFileSize(1024 * 1024));
        m_manager.setDaemonPool(m_pool);

        AbstractPersistentStore store = (AbstractPersistentStore) m_manager.open(STORE_ID, null);
        store.ensureExtent(1L);
        store.store(1L, binKey, binValue, null);

        Object state   = getExtentState((JournalPersistenceManager.JournalPersistentStore) store, 1L);
        String sExtent = ((File) getFieldValue(state, "m_dirExtent")).getAbsolutePath();

        invokeWriteCheckpoint(state);
        m_manager.close(STORE_ID);

        return sExtent;
        }

    /**
     * Assert that a phase exists in the supplied map.
     *
     * @param mapPhase  events keyed by phase
     * @param sPhase    the phase
     */
    private void assertPhase(Map<String, RecordedEvent> mapPhase, String sPhase)
        {
        assertTrue("missing phase " + sPhase, mapPhase.containsKey(sPhase));
        }

    /**
     * Return the extent state for the supplied extent.
     *
     * @param store      the journal store
     * @param lExtentId  the extent identifier
     *
     * @return the extent state
     *
     * @throws Exception on reflection failure
     */
    private Object getExtentState(JournalPersistenceManager.JournalPersistentStore store, long lExtentId)
            throws Exception
        {
        Field field = store.getClass().getDeclaredField("m_mapExtentState");
        field.setAccessible(true);

        Map<?, ?> mapState = (Map<?, ?>) field.get(store);

        return mapState.get(lExtentId);
        }

    /**
     * Return a field value via reflection.
     *
     * @param oTarget  target object
     * @param sField   field name
     *
     * @return field value
     *
     * @throws Exception on reflection failure
     */
    private Object getFieldValue(Object oTarget, String sField)
            throws Exception
        {
        Field field = oTarget.getClass().getDeclaredField(sField);
        field.setAccessible(true);
        return field.get(oTarget);
        }

    /**
     * Invoke the extent checkpoint writer.
     *
     * @param state  extent state
     *
     * @throws Exception on reflection failure
     */
    private void invokeWriteCheckpoint(Object state)
            throws Exception
        {
        Method method = state.getClass().getDeclaredMethod("writeCheckpoint");
        method.setAccessible(true);
        method.invoke(state);
        }

    // ----- constants ------------------------------------------------------

    /**
     * JFR event name.
     */
    private static final String EVENT_NAME = "com.tangosol.persistence.journal.JournalRecovery";

    /**
     * Test store id.
     */
    private static final String STORE_ID = "partition-0";

    // ----- data members ---------------------------------------------------

    /**
     * Temporary data directory.
     */
    private File m_file;

    /**
     * Test daemon pool.
     */
    private DaemonPool m_pool;

    /**
     * Test manager.
     */
    private JournalPersistenceManager m_manager;
    }
