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

import com.tangosol.coherence.component.util.SafeService;

import com.tangosol.io.FileHelper;
import com.tangosol.io.journal2.JournalEntry;

import com.tangosol.internal.util.PartitionedCacheComponent;

import com.tangosol.net.CacheFactory;
import com.tangosol.net.Cluster;
import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.DistributedCacheService;
import com.tangosol.net.NamedCache;
import com.tangosol.net.partition.KeyPartitioningStrategy;

import com.tangosol.util.listener.SimpleMapListener;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;

import static com.oracle.bedrock.deferred.DeferredHelper.invoking;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Functional tests for journal-backed backing map integration.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class JournalBackingMapTests
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
    public void testSingleMemberRestartRecovery()
            throws IOException
        {
        File       fileActive   = FileHelper.createTempDir();
        File       fileSnapshot = FileHelper.createTempDir();
        File       fileTrash    = FileHelper.createTempDir();
        Properties props        = createPersistenceProperties(fileActive, fileSnapshot, fileTrash);
        Cluster    cluster      = CacheFactory.getCluster();

        CoherenceClusterMember   member  = startMember("JournalBacking-1", cluster, props);
        ConfigurableCacheFactory factory = ensureFactory();
        NamedCache               cache   = factory.ensureCache("journal-backing-map", null);
        NamedCache               cacheTwo = factory.ensureCache("journal-backing-map-two", null);
        DistributedCacheService  service = (DistributedCacheService) cache.getCacheService();

        try
            {
            waitForServiceReady(member, service, cache);

            cache.put("key-1", "value-1");
            cache.put("key-2", "value-2");
            cache.put("key-3", "value-3");
            cache.put("shared-key", "value-one");
            cacheTwo.put("shared-key", "value-two");

            assertEquals("value-1", cache.get("key-1"));
            assertEquals("value-2", cache.get("key-2"));
            assertEquals("value-3", cache.get("key-3"));
            assertEquals("value-one", cache.get("shared-key"));
            assertEquals("value-two", cacheTwo.get("shared-key"));

            stopCacheServer("JournalBacking-1");
            Eventually.assertThat(invoking(cluster).getMemberSet().size(), is(1));

            member = startMember("JournalBacking-2", cluster, props);
            waitForServiceReady(member, service, cache);

            assertEquals("value-1", cache.get("key-1"));
            assertEquals("value-2", cache.get("key-2"));
            assertEquals("value-3", cache.get("key-3"));
            assertEquals("value-one", cache.get("shared-key"));
            assertEquals("value-two", cacheTwo.get("shared-key"));
            assertNull(cache.get("key-missing"));
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
    public void testMultiCacheSameKeyRestartRecovery()
            throws IOException
        {
        File       fileActive   = FileHelper.createTempDir();
        File       fileSnapshot = FileHelper.createTempDir();
        File       fileTrash    = FileHelper.createTempDir();
        Properties props        = createPersistenceProperties(fileActive, fileSnapshot, fileTrash);
        Cluster    cluster      = CacheFactory.getCluster();

        CoherenceClusterMember   member  = startMember("JournalBacking-1", cluster, props);
        ConfigurableCacheFactory factory = ensureFactory();
        NamedCache               cache   = factory.ensureCache("journal-backing-map", null);
        NamedCache               cacheTwo = factory.ensureCache("journal-backing-map-two", null);
        DistributedCacheService  service = (DistributedCacheService) cache.getCacheService();

        try
            {
            waitForServiceReady(member, service, cache);

            String                  sSharedKey = "path-b-shared-key";
            KeyPartitioningStrategy strategy   = service.getKeyPartitioningStrategy();
            int                     nPartOne   = strategy.getKeyPartition(sSharedKey);
            int                     nPartTwo   = strategy.getKeyPartition(sSharedKey);

            assertEquals(nPartOne, nPartTwo);
            assertEquals(nPartOne, service.getKeyPartitioningStrategy().getKeyPartition(sSharedKey));

            cache.put(sSharedKey, "cache-one");
            cacheTwo.put(sSharedKey, "cache-two");

            assertEquals("cache-one", cache.get(sSharedKey));
            assertEquals("cache-two", cacheTwo.get(sSharedKey));

            stopCacheServer("JournalBacking-1");
            Eventually.assertThat(invoking(cluster).getMemberSet().size(), is(1));

            member = startMember("JournalBacking-2", cluster, props);
            waitForServiceReady(member, service, cache);

            assertEquals("cache-one", cache.get(sSharedKey));
            assertEquals("cache-two", cacheTwo.get(sSharedKey));
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
    public void testSinglePutProducesSingleJournalStoreEntryImmediately()
            throws Exception
        {
        File       fileActive   = FileHelper.createTempDir();
        File       fileSnapshot = FileHelper.createTempDir();
        File       fileTrash    = FileHelper.createTempDir();
        Properties props        = createPersistenceProperties(fileActive, fileSnapshot, fileTrash);
        Cluster    cluster      = CacheFactory.getCluster();

        CoherenceClusterMember   member  = startMember("JournalBacking-1", cluster, props);
        ConfigurableCacheFactory factory = ensureFactory();
        NamedCache               cache   = factory.ensureCache("journal-backing-map", null);
        DistributedCacheService  service = (DistributedCacheService) cache.getCacheService();

        try
            {
            waitForServiceReady(member, service, cache);

            String sKey = "single-write-key";
            int    nPart = service.getKeyPartitioningStrategy().getKeyPartition(sKey);
            long   lExtentId = resolveServiceComponent(service).getCacheId(cache.getCacheName());

            cache.put(sKey, "value-one");

            assertEquals("value-one", cache.get(sKey));

            assertEquals(1, countRawStoreEntriesForExtent(fileActive, nPart, lExtentId));
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
    public void testJournalBackingMapRaisesInsertUpdateDeleteEvents()
            throws IOException
        {
        File       fileActive   = FileHelper.createTempDir();
        File       fileSnapshot = FileHelper.createTempDir();
        File       fileTrash    = FileHelper.createTempDir();
        Properties props        = createPersistenceProperties(fileActive, fileSnapshot, fileTrash);
        Cluster    cluster      = CacheFactory.getCluster();

        CoherenceClusterMember   member  = startMember("JournalBacking-1", cluster, props);
        ConfigurableCacheFactory factory = ensureFactory();
        NamedCache               cache   = factory.ensureCache("journal-backing-map", null);
        DistributedCacheService  service = (DistributedCacheService) cache.getCacheService();
        List<String>             listEvents = Collections.synchronizedList(new ArrayList<>());
        String                   sKey = "listener-key";
        SimpleMapListener        listener = new SimpleMapListener<>()
                .addInsertHandler(evt -> listEvents.add("insert:" + evt.getOldValue() + ":" + evt.getNewValue()))
                .addUpdateHandler(evt -> listEvents.add("update:" + evt.getOldValue() + ":" + evt.getNewValue()))
                .addDeleteHandler(evt -> listEvents.add("delete:" + evt.getOldValue() + ":" + evt.getNewValue()));

        try
            {
            waitForServiceReady(member, service, cache);

            cache.addMapListener(listener, sKey, false);

            cache.put(sKey, "value-one");
            cache.put(sKey, "value-two");
            cache.remove(sKey);

            Eventually.assertDeferred(() -> listEvents.size(), is(3));

            assertEquals("insert:null:value-one", listEvents.get(0));
            assertEquals("update:value-one:value-two", listEvents.get(1));
            assertEquals("delete:value-two:null", listEvents.get(2));
            }
        finally
            {
            cache.removeMapListener(listener, sKey);
            stopAllApplications();
            CacheFactory.shutdown();
            FileHelper.deleteDirSilent(fileActive);
            FileHelper.deleteDirSilent(fileSnapshot);
            FileHelper.deleteDirSilent(fileTrash);
            }
        }

    private Properties createPersistenceProperties(File fileActive, File fileSnapshot, File fileTrash)
        {
        Properties props = new Properties();
        props.setProperty("test.persistence.active.dir", fileActive.getAbsolutePath());
        props.setProperty("test.persistence.snapshot.dir", fileSnapshot.getAbsolutePath());
        props.setProperty("test.persistence.trash.dir", fileTrash.getAbsolutePath());
        props.setProperty("test.persistent-environment", "simple-journal-environment");
        props.setProperty("coherence.distribution.2server", "false");
        props.setProperty("coherence.override", "common-tangosol-coherence-override.xml");
        return props;
        }

    private CoherenceClusterMember startMember(String sName, Cluster cluster, Properties props)
        {
        CoherenceClusterMember member = startCacheServer(sName,
                "persistence", "journal-backing-map-cache-config.xml", props);
        Eventually.assertThat(invoking(cluster).getMemberSet().size(), is(2));
        return member;
        }

    private ConfigurableCacheFactory ensureFactory()
        {
        ConfigurableCacheFactory factory = CacheFactory.getCacheFactoryBuilder()
                .getConfigurableCacheFactory("client-cache-config.xml", null);
        setFactory(factory);
        return factory;
        }

    private void waitForServiceReady(CoherenceClusterMember member, DistributedCacheService service, NamedCache cache)
        {
        Eventually.assertThat(invoking(member).isServiceRunning(service.getInfo().getServiceName()), is(true));
        Eventually.assertDeferred(() -> service.getOwnershipEnabledMembers().size(), is(1));
        Eventually.assertDeferred(() -> service.getPartitionOwner(0) != null, is(true));
        AbstractRollingRestartTest.waitForNoOrphans(cache.getCacheService());
        AbstractRollingRestartTest.waitForBalanced(cache.getCacheService());
        }

    private PartitionedCacheComponent resolveServiceComponent(DistributedCacheService service)
        {
        if (service instanceof PartitionedCacheComponent)
            {
            return (PartitionedCacheComponent) service;
            }

        if (service instanceof SafeService)
            {
            return (PartitionedCacheComponent) ((SafeService) service).getRunningService();
            }

        throw new IllegalStateException("Unable to resolve partitioned cache component from service " + service);
        }
    private int countRawStoreEntriesForExtent(File fileActive, int nPartition, long lExtentId)
            throws Exception
        {
        File   dirCluster = singleSubdirectory(fileActive);
        File   dirService = new File(dirCluster, "JournalDistributedCache");
        File   dirStore   = findPartitionStoreDirectory(dirService, nPartition);
        File   dirExtents = new File(dirStore, "extents");
        File   dirExtent  = new File(dirExtents, String.format("%016d", lExtentId));

        assertTrue("Expected extent directory " + dirExtent + " to exist", dirExtent.isDirectory());

        Class<?> clsJournalUtils = Class.forName("com.tangosol.io.journal2.JournalUtils");
        Class<?> clsVisitor      = Class.forName("com.tangosol.io.journal2.JournalUtils$JournalEntryVisitor");
        Method   mDiscover       = clsJournalUtils.getDeclaredMethod("discoverFileIds", File.class);
        Method   mScan           = clsJournalUtils.getDeclaredMethod("scanFile", File.class, int.class, long.class, clsVisitor);

        mDiscover.setAccessible(true);
        mScan.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Integer> listFileIds = (List<Integer>) mDiscover.invoke(null, dirExtent);
        int[]         cStores     = new int[1];
        Object        visitor     = Proxy.newProxyInstance(clsVisitor.getClassLoader(), new Class<?>[] {clsVisitor},
                (proxy, method, args) ->
                    {
                    if ("visit".equals(method.getName()))
                        {
                        JournalEntry entry = (JournalEntry) args[2];
                        if (entry.isValid() && entry.getType() == JournalEntry.TYPE_STORE)
                            {
                            cStores[0]++;
                            }
                        return true;
                        }

                    return null;
                    });

        for (Integer nFileId : listFileIds)
            {
            File fileJournal = new File(dirExtent, String.format("journal-%06d.coh", nFileId.intValue()));
            mScan.invoke(null, fileJournal, nFileId.intValue(), 0L, visitor);
            }

        return cStores[0];
        }

    private File singleSubdirectory(File dirParent)
        {
        File[] aDir = dirParent.listFiles(File::isDirectory);

        assertTrue("Expected at least one child directory in " + dirParent, aDir != null && aDir.length > 0);

        return aDir[0];
        }

    private File findPartitionStoreDirectory(File dirService, int nPartition)
        {
        File[] aDir = dirService.listFiles(file -> file.isDirectory()
                && file.getName().startsWith(nPartition + "-"));

        assertTrue("Expected a store directory for partition " + nPartition + " in " + dirService,
                aDir != null && aDir.length > 0);

        return aDir[0];
        }
    }
