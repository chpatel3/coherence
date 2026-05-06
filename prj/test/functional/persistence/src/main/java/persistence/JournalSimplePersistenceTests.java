/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package persistence;

import com.oracle.coherence.persistence.PersistenceManager;

import com.oracle.bedrock.runtime.coherence.CoherenceClusterMember;
import com.oracle.bedrock.testsupport.deferred.Eventually;

import com.oracle.coherence.testing.junit.ThreadDumpOnTimeoutRule;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;

import com.tangosol.net.CacheFactory;
import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.DistributedCacheService;
import com.tangosol.net.NamedCache;
import com.tangosol.net.PartitionedService;
import com.tangosol.net.management.MBeanServerProxy;
import com.tangosol.net.management.Registry;

import com.tangosol.persistence.journal.JournalPersistenceMBeanRegistry;
import com.tangosol.persistence.journal.JournalPersistenceManager;

import org.junit.ClassRule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.management.MBeanException;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static com.oracle.bedrock.deferred.DeferredHelper.invoking;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Functional tests for simple cache persistence and recovery using the
 * JournalPersistenceManager.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class JournalSimplePersistenceTests
        extends AbstractSimplePersistenceTests
    {

    // ----- AbstractSimplePersistenceTests methods -------------------------

    @Override
    protected PersistenceManager<ReadBuffer> createPersistenceManager(File file)
            throws IOException
        {
        return new JournalPersistenceManager(file, null, null);
        }

    @Override
    public String getPersistenceManagerName()
        {
        return "Journal";
        }

    @Override
    public String getCacheConfigPath()
        {
        return "simple-persistence-journal-cache-config.xml";
        }

    // ----- functional smoke tests ----------------------------------------

    /**
     * Smoke test for active persistence restart recovery.
     */
    @Test
    public void testActiveRecoverySmoke()
            throws IOException
        {
        testSingleServer();
        }

    /**
     * Smoke test for active persistence snapshot recovery.
     */
    @Test
    public void testActiveSnapshotSmoke()
            throws IOException, MBeanException
        {
        testSnapshotSmoke("testActiveSnapshotSmoke" + getPersistenceManagerName(), "simple-persistent", true);
        }

    /**
     * Smoke test for active-backup persistence restart recovery.
     */
    @Test
    public void testActiveBackupSmoke()
            throws IOException, MBeanException
        {
        testActiveBackupSmoke("testActiveBackupSmoke" + getPersistenceManagerName(), "simple-persistent");
        }

    @Test
    public void testJournalPersistenceMBeanRegistered()
            throws IOException
        {
        runJournalPersistenceMBeanScenario("testJournalPersistenceMBeanRegistered", getCacheConfigPath(), true);
        runJournalPersistenceMBeanScenario("testJournalPersistenceMBeanBdbAbsent",
                "simple-persistence-bdb-cache-config.xml", false);
        }

    @Test
    public void testSparseSnapshotRecoveryEmitsJournalRecoveryJfr()
            throws Exception
        {
        File             fileActive   = FileHelper.createTempDir();
        File             fileSnapshot = FileHelper.createTempDir();
        File             fileTrash    = FileHelper.createTempDir();
        Path             pathEvent    = new File(fileSnapshot, "sparse-snapshot-recovery.jfr").toPath();
        PropertySnapshot snapshot     = new PropertySnapshot();

        snapshot.setProperty("test.persistence.mode", "on-demand");
        snapshot.setProperty("test.persistence.active.dir", fileActive.getAbsolutePath());
        snapshot.setProperty("test.persistence.snapshot.dir", fileSnapshot.getAbsolutePath());
        snapshot.setProperty("test.persistence.trash.dir", fileTrash.getAbsolutePath());
        snapshot.setProperty("coherence.distributed.localstorage", "true");
        snapshot.setProperty("coherence.management", "all");
        snapshot.setProperty("coherence.management.remote", "true");
        snapshot.setProperty("coherence.distribution.2server", "false");
        snapshot.setProperty("test.threads", "1");
        snapshot.setProperty("coherence.override", "common-tangosol-coherence-override.xml");
        CacheFactory.shutdown();

        try
            {
            ConfigurableCacheFactory factory = CacheFactory.getCacheFactoryBuilder()
                    .getConfigurableCacheFactory(getCacheConfigPath(), null);
            setFactory(factory);

            NamedCache<String, String> cache   = getNamedCache("simple-persistent");
            DistributedCacheService    service = (DistributedCacheService) cache.getCacheService();
            String                     sName   = service.getInfo().getServiceName();
            PersistenceTestHelper      helper  = new PersistenceTestHelper();

            Eventually.assertThat(invoking(service).getOwnershipEnabledMembers().size(), is(1));
            waitForBalanced(service);

            cache.put("snapshot-jfr-key", "before");
            helper.createSnapshot(sName, "snapshot-jfr");
            cache.put("snapshot-jfr-key", "after");

            try (Recording recording = new Recording())
                {
                recording.enable(EVENT_NAME);
                recording.start();

                helper.recoverSnapshot(sName, "snapshot-jfr");
                waitForBalanced(service);

                recording.stop();
                recording.dump(pathEvent);
                }

            assertEquals("before", cache.get("snapshot-jfr-key"));

            List<RecordedEvent> listEvents = RecordingFile.readAllEvents(pathEvent)
                    .stream()
                    .filter(event -> EVENT_NAME.equals(event.getEventType().getName()))
                    .filter(event -> isUnderDirectory(event.getString("extentPath"), fileSnapshot))
                    .collect(Collectors.toList());

            assertFalse("expected JournalRecovery events under the snapshot directory", listEvents.isEmpty());

            Set<String> setPhase = new HashSet<>();
            for (RecordedEvent event : listEvents)
                {
                String sPhase = event.getString("phase");

                assertTrue("unexpected phase " + sPhase, RECOVERY_PHASES.contains(sPhase));
                assertFalse(event.getString("extentPath").isEmpty());
                assertTrue(event.getLong("durationNanos") >= 0L);
                assertTrue(event.getLong("keyCount") >= 0L);
                assertTrue(event.getInt("fileCount") >= 0);
                setPhase.add(sPhase);
                }

            assertTrue(setPhase.containsAll(RECOVERY_PHASES));
            }
        finally
            {
            stopAllApplications();
            CacheFactory.shutdown();
            snapshot.restore();
            FileHelper.deleteDirSilent(fileActive);
            FileHelper.deleteDirSilent(fileSnapshot);
            FileHelper.deleteDirSilent(fileTrash);
            }
        }

    // ----- helpers --------------------------------------------------------

    private void runJournalPersistenceMBeanScenario(String sServer, String sCacheConfig, boolean fExpectJournalMBean)
            throws IOException
        {
        File fileActive   = FileHelper.createTempDir();
        File fileSnapshot = FileHelper.createTempDir();
        File fileTrash    = FileHelper.createTempDir();

        Properties props = new Properties();
        props.setProperty("test.persistence.active.dir", fileActive.getAbsolutePath());
        props.setProperty("test.persistence.snapshot.dir", fileSnapshot.getAbsolutePath());
        props.setProperty("test.persistence.trash.dir", fileTrash.getAbsolutePath());
        props.setProperty("coherence.distribution.2server", "false");
        props.setProperty("coherence.management", "all");
        props.setProperty("coherence.management.remote", "true");
        props.setProperty("coherence.override", "common-tangosol-coherence-override.xml");

        CoherenceClusterMember member = startCacheServer(sServer + "-1", getProjectName(), sCacheConfig, props);

        try
            {
            ConfigurableCacheFactory factory = CacheFactory.getCacheFactoryBuilder()
                    .getConfigurableCacheFactory("client-cache-config.xml", null);
            setFactory(factory);

            NamedCache         cache   = getNamedCache("simple-persistent");
            PartitionedService service = (PartitionedService) cache.getCacheService();
            String             sName   = service.getInfo().getServiceName();

            Eventually.assertThat(invoking(member).isServiceRunning(sName), is(true));

            cache.put("journal-mbean-key", "journal-mbean-value");

            Registry         registry = CacheFactory.getCluster().getManagement();
            MBeanServerProxy proxy    = registry.getMBeanServerProxy();
            String           sMBean   = registry.ensureGlobalName(
                    JournalPersistenceMBeanRegistry.getMBeanName(sName));

            if (fExpectJournalMBean)
                {
                Eventually.assertThat(invoking(proxy).isMBeanRegistered(sMBean), is(true));

                Object oOpenExtentCount     = proxy.getAttribute(sMBean, "OpenExtentCount");
                Object oCompactionProgress  = proxy.getAttribute(sMBean, "CompactionProgress");
                Object oLastRecoverySummary = proxy.getAttribute(sMBean, "LastRecoverySummary");

                assertTrue(((Number) oOpenExtentCount).intValue() >= 0);
                assertNotNull(oCompactionProgress);
                assertFalse(((String) oCompactionProgress).isEmpty());
                assertNotNull(oLastRecoverySummary);
                }
            else
                {
                assertFalse("BDB persistence must not register a journal persistence MBean",
                        proxy.isMBeanRegistered(sMBean));
                }
            }
        finally
            {
            stopAllApplications();
            CacheFactory.shutdown();

            FileHelper.deleteDirSilent(fileActive);
            FileHelper.deleteDirSilent(fileSnapshot);
            FileHelper.deleteDirSilent(fileTrash);
            }
        }

    private boolean isUnderDirectory(String sPath, File dirRoot)
        {
        try
            {
            String sCanonicalPath = new File(sPath).getCanonicalPath();
            String sCanonicalRoot = dirRoot.getCanonicalPath();

            return sCanonicalPath.startsWith(sCanonicalRoot + File.separator);
            }
        catch (IOException e)
            {
            throw new AssertionError("Unable to resolve canonical path for " + sPath, e);
            }
        }

    // ----- inner class: PropertySnapshot ---------------------------------

    protected static class PropertySnapshot
        {
        protected void setProperty(String sName, String sValue)
            {
            if (!m_mapOriginal.containsKey(sName))
                {
                m_mapOriginal.put(sName, System.getProperty(sName));
                }

            if (sValue == null)
                {
                System.clearProperty(sName);
                }
            else
                {
                System.setProperty(sName, sValue);
                }
            }

        protected void restore()
            {
            for (Map.Entry<String, String> entry : m_mapOriginal.entrySet())
                {
                String sValue = entry.getValue();
                if (sValue == null)
                    {
                    System.clearProperty(entry.getKey());
                    }
                else
                    {
                    System.setProperty(entry.getKey(), sValue);
                    }
                }
            }

        private final Map<String, String> m_mapOriginal = new HashMap<>();
        }

    // ----- constants ------------------------------------------------------

    private static final String EVENT_NAME = "com.tangosol.persistence.journal.JournalRecovery";

    private static final Set<String> RECOVERY_PHASES = Set.of("checkpoint-load", "journal-scan", "journal-replay", "total");

    // ----- data members ---------------------------------------------------

    @ClassRule
    public static final ThreadDumpOnTimeoutRule timeout = ThreadDumpOnTimeoutRule.after(90, TimeUnit.MINUTES);
    }
