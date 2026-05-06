/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package persistence;

import com.oracle.bedrock.runtime.coherence.CoherenceClusterMember;
import com.oracle.bedrock.testsupport.deferred.Eventually;

import com.oracle.coherence.testing.AbstractFunctionalTest;
import com.oracle.coherence.testing.AbstractRollingRestartTest;

import com.tangosol.io.FileHelper;

import com.tangosol.net.CacheFactory;
import com.tangosol.net.Cluster;
import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.DistributedCacheService;
import com.tangosol.net.NamedCache;

import java.io.File;
import java.io.IOException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;

import static com.oracle.bedrock.deferred.DeferredHelper.invoking;

import static org.hamcrest.CoreMatchers.is;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Functional tests for journal migration scenarios.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class JournalMigrationTests
        extends AbstractFunctionalTest
    {
    @BeforeClass
    public static void _startup()
        {
        System.setProperty("coherence.management", "all");
        System.setProperty("coherence.management.remote", "true");
        System.setProperty("coherence.management.refresh.expiry", "1s");
        System.setProperty("coherence.management.http", "inherit");
        System.setProperty("coherence.management.metrics.port", "0");

        AbstractFunctionalTest._startup();
        }

    @Test
    public void testBdbSnapshotMigratesIntoJournalPersistence()
            throws Exception
        {
        File       fileActive   = FileHelper.createTempDir();
        File       fileSnapshot = FileHelper.createTempDir();
        File       fileTrash    = FileHelper.createTempDir();
        Cluster    cluster      = CacheFactory.getCluster();
        Properties propsBdb     = createBaseProperties(fileActive, fileSnapshot, fileTrash, "simple-bdb-environment");

        CoherenceClusterMember member = startCacheServer("JournalMigrationBdb-1",
                "persistence", "simple-persistence-bdb-cache-config.xml", propsBdb);

        ConfigurableCacheFactory factory = ensureFactory();
        NamedCache               cacheOne = factory.ensureCache("simple-persistent-1", null);
        NamedCache               cacheTwo = factory.ensureCache("simple-persistent-2", null);
        DistributedCacheService  service  = (DistributedCacheService) cacheOne.getCacheService();
        String                   sService = service.getInfo().getServiceName();

        try
            {
            waitForServiceReady(member, service, cacheOne, 1);

            cacheOne.put("shared-key", "cache-one");
            cacheTwo.put("shared-key", "cache-two");
            cacheOne.put("post-migration-key", "before-migration");

            String sSnapshot = "snapshot-bdb-to-journal";
            new PersistenceTestHelper().createSnapshot(sService, sSnapshot);

            stopCacheServer("JournalMigrationBdb-1");
            Eventually.assertThat(invoking(cluster).getMemberSet().size(), is(1));

            Properties propsJournal = createBaseProperties(fileActive, fileSnapshot, fileTrash,
                    "simple-journal-environment");
            propsJournal.setProperty("test.persistence.migration.dir",
                    snapshotDirectory(fileSnapshot, cluster.getClusterName(), sService, sSnapshot).getAbsolutePath());

            member = startCacheServer("JournalMigrationJournal-1",
                    "persistence", "simple-persistence-bdb-cache-config.xml", propsJournal);
            waitForServiceReady(member, service, cacheOne, 1);

            assertEquals("cache-one", cacheOne.get("shared-key"));
            assertEquals("cache-two", cacheTwo.get("shared-key"));
            assertEquals("before-migration", cacheOne.get("post-migration-key"));

            cacheOne.put("post-migration-key", "after-migration");

            stopCacheServer("JournalMigrationJournal-1");
            Eventually.assertThat(invoking(cluster).getMemberSet().size(), is(1));

            member = startCacheServer("JournalMigrationJournal-2",
                    "persistence", "simple-persistence-bdb-cache-config.xml", propsJournal);
            waitForServiceReady(member, service, cacheOne, 1);

            assertEquals("cache-one", cacheOne.get("shared-key"));
            assertEquals("cache-two", cacheTwo.get("shared-key"));
            assertEquals("after-migration", cacheOne.get("post-migration-key"));
            assertTrue(new File(serviceDirectory(fileActive, cluster.getClusterName(), sService),
                    "migration.properties").isFile());
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

    @Test
    public void testRollingRestartFromBdbToJournalRebuildsPersistentStores()
            throws IOException
        {
        File       dirActive   = FileHelper.createTempDir();
        File       dirSnapshot = FileHelper.createTempDir();
        File       dirTrash    = FileHelper.createTempDir();
        Properties propsBdb    = createBaseProperties(dirActive, dirSnapshot, dirTrash, "simple-bdb-environment");
        Properties propsJournal = createBaseProperties(dirActive, dirSnapshot, dirTrash, "simple-journal-environment");

        propsBdb.setProperty("test.backupcount", "1");
        propsJournal.setProperty("test.backupcount", "1");

        try
            {
            startRollingMember("RollingBdb-1", "machine-1", dirActive, dirTrash, propsBdb);
            startRollingMember("RollingBdb-2", "machine-2", dirActive, dirTrash, propsBdb);
            startRollingMember("RollingBdb-3", "machine-3", dirActive, dirTrash, propsBdb);

            ConfigurableCacheFactory factory = ensureFactory();
            NamedCache               cache   = factory.ensureCache("rolling-wi13-switch", null);
            DistributedCacheService  service = (DistributedCacheService) cache.getCacheService();

            waitForClusterReady(service, cache, 3);

            Map<Integer, String> mapExpected = new LinkedHashMap<>();
            for (int i = 0; i < 1000; i++)
                {
                mapExpected.put(Integer.valueOf(i), "value-" + i);
                }
            cache.putAll(mapExpected);

            stopCacheServer("RollingBdb-1");
            startRollingMember("RollingJournal-1", "machine-1", dirActive, dirTrash, propsJournal);
            waitForClusterReady(service, cache, 3);

            stopCacheServer("RollingBdb-2");
            startRollingMember("RollingJournal-2", "machine-2", dirActive, dirTrash, propsJournal);
            waitForClusterReady(service, cache, 3);

            stopCacheServer("RollingBdb-3");
            startRollingMember("RollingJournal-3", "machine-3", dirActive, dirTrash, propsJournal);
            waitForClusterReady(service, cache, 3);

            assertCacheContents(cache, mapExpected);

            stopCacheServer("RollingJournal-1");
            stopCacheServer("RollingJournal-2");
            stopCacheServer("RollingJournal-3");

            startRollingMember("RollingJournal-Restart-1", "machine-1", dirActive, dirTrash, propsJournal);
            startRollingMember("RollingJournal-Restart-2", "machine-2", dirActive, dirTrash, propsJournal);
            startRollingMember("RollingJournal-Restart-3", "machine-3", dirActive, dirTrash, propsJournal);
            waitForClusterReady(service, cache, 3);
            assertCacheContents(cache, mapExpected);
            }
        finally
            {
            stopAllApplications();
            CacheFactory.shutdown();
            FileHelper.deleteDirSilent(dirActive);
            FileHelper.deleteDirSilent(dirSnapshot);
            FileHelper.deleteDirSilent(dirTrash);
            }
        }

    private Properties createBaseProperties(File fileActive, File fileSnapshot, File fileTrash, String sEnvironment)
        {
        Properties props = new Properties();
        props.setProperty("test.persistence.active.dir", fileActive.getAbsolutePath());
        props.setProperty("test.persistence.snapshot.dir", fileSnapshot.getAbsolutePath());
        props.setProperty("test.persistence.trash.dir", fileTrash.getAbsolutePath());
        props.setProperty("test.persistent-environment", sEnvironment);
        props.setProperty("coherence.distribution.2server", "false");
        props.setProperty("coherence.override", "common-tangosol-coherence-override.xml");
        return props;
        }

    private ConfigurableCacheFactory ensureFactory()
        {
        ConfigurableCacheFactory factory = CacheFactory.getCacheFactoryBuilder()
                .getConfigurableCacheFactory("client-cache-config.xml", null);
        setFactory(factory);
        return factory;
        }

    private void waitForServiceReady(CoherenceClusterMember member, DistributedCacheService service,
            NamedCache cache, int cStorageMembers)
        {
        Eventually.assertThat(invoking(member).isServiceRunning(service.getInfo().getServiceName()), is(true));
        waitForClusterReady(service, cache, cStorageMembers);
        }

    private void waitForClusterReady(DistributedCacheService service, NamedCache cache, int cStorageMembers)
        {
        Eventually.assertDeferred(() -> service.getOwnershipEnabledMembers().size(), is(cStorageMembers));
        Eventually.assertDeferred(() -> service.getPartitionOwner(0) != null, is(true));
        AbstractRollingRestartTest.waitForNoOrphans(cache.getCacheService());
        AbstractRollingRestartTest.waitForBalanced(cache.getCacheService());
        }

    private CoherenceClusterMember startRollingMember(String sName, String sMachine, File dirActiveRoot,
            File dirTrashRoot, Properties propsTemplate)
            throws IOException
        {
        File       dirActive = FileHelper.ensureDir(new File(dirActiveRoot, sMachine));
        File       dirTrash  = FileHelper.ensureDir(new File(dirTrashRoot, sMachine));
        Properties props     = new Properties();

        props.putAll(propsTemplate);
        props.setProperty("coherence.machine", sMachine);
        props.setProperty("test.persistence.active.dir", dirActive.getAbsolutePath());
        props.setProperty("test.persistence.trash.dir", dirTrash.getAbsolutePath());

        return startCacheServer(sName, "persistence", "rolling-persistence-bdb-cache-config.xml", props);
        }

    private void assertCacheContents(NamedCache cache, Map<Integer, String> mapExpected)
        {
        Eventually.assertDeferred(cache::size, is(mapExpected.size()));

        for (Map.Entry<Integer, String> entry : mapExpected.entrySet())
            {
            assertEquals(entry.getValue(), cache.get(entry.getKey()));
            }
        }

    private File snapshotDirectory(File dirSnapshotRoot, String sClusterName, String sServiceName, String sSnapshotName)
        {
        return new File(serviceDirectory(dirSnapshotRoot, sClusterName, sServiceName), FileHelper.toFilename(sSnapshotName));
        }

    private File serviceDirectory(File dirRoot, String sClusterName, String sServiceName)
        {
        return new File(new File(dirRoot, FileHelper.toFilename(sClusterName)),
                FileHelper.toFilename(sServiceName));
        }
    }
