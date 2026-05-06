/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package persistence;

import com.tangosol.io.FileHelper;

import com.tangosol.net.CacheFactory;
import com.tangosol.net.Cluster;
import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.DistributedCacheService;
import com.tangosol.net.NamedCache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Helper application used by the Unified Persistence migration demo.
 *
 * @author Aleks Seovic  2026.04.07
 * @since 26.04
 */
public class UnifiedPersistenceMigrationDemoHelper
    {
    // ----- main entry ----------------------------------------------------

    public static void main(String[] asArg)
            throws Exception
        {
        if (asArg.length == 0)
            {
            usage();
            return;
            }

        String sCommand = asArg[0];

        switch (sCommand)
            {
            case "load-data":
                runWithCacheFactory(UnifiedPersistenceMigrationDemoHelper::loadData);
                break;

            case "verify-data":
                runWithCacheFactory(UnifiedPersistenceMigrationDemoHelper::verifyData);
                break;

            case "wait-ready":
                runWithCacheFactory(UnifiedPersistenceMigrationDemoHelper::waitReady);
                break;

            case "create-snapshot":
                runWithCacheFactory(UnifiedPersistenceMigrationDemoHelper::createSnapshot);
                break;

            case "show-paths":
                runWithCacheFactory(UnifiedPersistenceMigrationDemoHelper::showPaths);
                break;

            case "dataset-summary":
                printDatasetSummary(loadDataset());
                break;

            default:
                throw new IllegalArgumentException("Unknown command: " + sCommand);
            }
        }

    // ----- command handlers ---------------------------------------------

    private static void loadData(ConfigurableCacheFactory factory)
            throws Exception
        {
        DemoContext               context = DemoContext.create(factory);
        LinkedHashMap<String, String> mapData = loadDataset();
        NamedCache<String, String> cache   = context.getCache();

        System.out.println("Loading curated movie records into cache \"" + DISPLAY_CACHE_NAME + "\"...");
        cache.clear();
        cache.putAll(mapData);

        int cLoaded = cache.size();
        System.out.println("Loaded " + cLoaded + " entries.");
        printDatasetSummary(mapData);
        System.out.println("Service: " + context.getServiceName());
        }

    private static void verifyData(ConfigurableCacheFactory factory)
            throws Exception
        {
        DemoContext               context = DemoContext.create(factory);
        LinkedHashMap<String, String> mapExpected = loadDataset();
        NamedCache<String, String> cache       = context.getCache();

        System.out.println("Verifying recovered data in cache \"" + DISPLAY_CACHE_NAME + "\"...");

        int cActual = cache.size();
        if (cActual != mapExpected.size())
            {
            throw new IllegalStateException("Expected " + mapExpected.size() + " entries but found " + cActual);
            }

        for (Map.Entry<String, String> entry : mapExpected.entrySet())
            {
            String sActual = cache.get(entry.getKey());
            if (!Objects.equals(entry.getValue(), sActual))
                {
                throw new IllegalStateException("Value mismatch for key \"" + entry.getKey() + "\"");
                }
            }

        System.out.println("Verified " + cActual + " entries.");
        printRepresentativeLookups(cache, mapExpected);
        }

    private static void waitReady(ConfigurableCacheFactory factory)
            throws Exception
        {
        DemoContext             context = DemoContext.create(factory);
        DistributedCacheService service = context.getService();
        Instant                 deadline = Instant.now().plus(Duration.ofSeconds(getLongProperty("demo.wait.seconds", 120L)));
        Instant                 nextProgress = Instant.EPOCH;

        System.out.println("Waiting for service \"" + context.getServiceName() + "\" to become ready...");

        while (Instant.now().isBefore(deadline))
            {
            try
                {
                if (service.isRunning()
                        && service.getPartitionOwner(0) != null
                        && service.getOwnershipEnabledMembers().size() >= 1)
                    {
                    Cluster cluster = CacheFactory.getCluster();
                    System.out.println("Service is ready on cluster \"" + cluster.getClusterName() + "\".");
                    return;
                    }
                }
            catch (Exception ignored)
                {
                // keep polling until startup completes
                }

            if (Instant.now().isAfter(nextProgress))
                {
                System.out.println("  still waiting for partition ownership...");
                nextProgress = Instant.now().plusSeconds(5);
                }

            Thread.sleep(1000L);
            }

        throw new IllegalStateException("Timed out waiting for service readiness");
        }

    private static void createSnapshot(ConfigurableCacheFactory factory)
            throws Exception
        {
        DemoContext context   = DemoContext.create(factory);
        String      sSnapshot = getRequiredProperty("demo.snapshot.name");

        System.out.println("Creating BDB snapshot \"" + sSnapshot + "\" for service \"" + context.getServiceName() + "\"...");
        System.out.println("Migration uses a BDB snapshot as the journal import source.");
        PersistenceTestHelper.ensurePersistenceMBean(context.getServiceName());
        new PersistenceTestHelper().createSnapshot(context.getServiceName(), sSnapshot);
        System.out.println("Snapshot created.");
        System.out.println("Snapshot directory: " + context.getSnapshotDirectory(sSnapshot).getAbsolutePath());
        }

    private static void showPaths(ConfigurableCacheFactory factory)
            throws Exception
        {
        DemoContext context   = DemoContext.create(factory);
        String      sSnapshot = System.getProperty("demo.snapshot.name", SNAPSHOT_NAME);

        System.out.println("cluster=" + context.getClusterName());
        System.out.println("service=" + context.getServiceName());
        System.out.println("activeServiceDir=" + context.getActiveServiceDirectory().getAbsolutePath());
        System.out.println("snapshotServiceDir=" + context.getSnapshotServiceDirectory().getAbsolutePath());
        System.out.println("snapshotDir=" + context.getSnapshotDirectory(sSnapshot).getAbsolutePath());
        System.out.println("trashServiceDir=" + context.getTrashServiceDirectory().getAbsolutePath());
        }

    // ----- helpers -------------------------------------------------------

    private static void runWithCacheFactory(DemoAction action)
            throws Exception
        {
        ConfigurableCacheFactory factory = null;

        try
            {
            factory = CacheFactory.getCacheFactoryBuilder()
                    .getConfigurableCacheFactory(CLIENT_CACHE_CONFIG, null);
            action.run(factory);
            }
        finally
            {
            if (factory != null)
                {
                factory.dispose();
                }
            CacheFactory.shutdown();
            }
        }

    private static LinkedHashMap<String, String> loadDataset()
            throws IOException
        {
        LinkedHashMap<String, String> mapData = new LinkedHashMap<>();

        try (InputStream in = UnifiedPersistenceMigrationDemoHelper.class.getResourceAsStream(DATASET_RESOURCE))
            {
            if (in == null)
                {
                throw new IOException("Missing dataset resource: " + DATASET_RESOURCE);
                }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
                {
                String sLine;
                while ((sLine = reader.readLine()) != null)
                    {
                    String sTrimmed = sLine.trim();
                    if (sTrimmed.isEmpty() || sTrimmed.startsWith("#"))
                        {
                        continue;
                        }

                    int ofSplit = sLine.indexOf('\t');
                    if (ofSplit < 0)
                        {
                        throw new IOException("Malformed dataset line: " + sLine);
                        }

                    String sKey   = sLine.substring(0, ofSplit);
                    String sValue = sLine.substring(ofSplit + 1);
                    mapData.put(sKey, sValue);
                    }
                }
            }

        return mapData;
        }

    private static void printDatasetSummary(Map<String, String> mapData)
        {
        System.out.println("Dataset summary:");
        System.out.println("  total entries: " + mapData.size());

        int cPrinted = 0;
        for (Map.Entry<String, String> entry : mapData.entrySet())
            {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
            if (++cPrinted == 4)
                {
                break;
                }
            }
        }

    private static void printRepresentativeLookups(NamedCache<String, String> cache, Map<String, String> mapExpected)
        {
        System.out.println("Representative records:");

        List<String> listKeys = new ArrayList<>(mapExpected.keySet());
        int[] anIndex = {0, Math.min(4, listKeys.size() - 1), Math.min(8, listKeys.size() - 1)};

        for (int nIndex : anIndex)
            {
            String sKey = listKeys.get(nIndex);
            System.out.println("  " + sKey + " -> " + cache.get(sKey));
            }
        }

    private static String getRequiredProperty(String sName)
        {
        String sValue = System.getProperty(sName);
        if (sValue == null || sValue.isBlank())
            {
            throw new IllegalStateException("Missing required system property: " + sName);
            }
        return sValue;
        }

    private static long getLongProperty(String sName, long lDefault)
        {
        String sValue = System.getProperty(sName);
        return sValue == null || sValue.isBlank() ? lDefault : Long.parseLong(sValue);
        }

    private static void usage()
        {
        System.out.println("Usage: UnifiedPersistenceMigrationDemoHelper <command>");
        System.out.println("Commands: dataset-summary, wait-ready, load-data, create-snapshot, show-paths, verify-data");
        }

    // ----- inner class: DemoContext -------------------------------------

    private static final class DemoContext
        {
        static DemoContext create(ConfigurableCacheFactory factory)
            {
            NamedCache<String, String> cache = factory.ensureCache(CACHE_NAME, null);
            DistributedCacheService    service = (DistributedCacheService) cache.getCacheService();
            Cluster                    cluster = CacheFactory.getCluster();

            return new DemoContext(cluster.getClusterName(),
                    service.getInfo().getServiceName(),
                    cache,
                    service,
                    new java.io.File(getRequiredProperty(PROP_ACTIVE_DIR)),
                    new java.io.File(getRequiredProperty(PROP_SNAPSHOT_DIR)),
                    new java.io.File(getRequiredProperty(PROP_TRASH_DIR)));
            }

        DemoContext(String sClusterName, String sServiceName, NamedCache<String, String> cache,
                DistributedCacheService service, java.io.File dirActiveRoot, java.io.File dirSnapshotRoot,
                java.io.File dirTrashRoot)
            {
            m_sClusterName   = sClusterName;
            m_sServiceName   = sServiceName;
            m_cache          = cache;
            m_service        = service;
            m_dirActiveRoot  = dirActiveRoot;
            m_dirSnapshotRoot = dirSnapshotRoot;
            m_dirTrashRoot   = dirTrashRoot;
            }

        String getClusterName()
            {
            return m_sClusterName;
            }

        String getServiceName()
            {
            return m_sServiceName;
            }

        NamedCache<String, String> getCache()
            {
            return m_cache;
            }

        DistributedCacheService getService()
            {
            return m_service;
            }

        java.io.File getActiveServiceDirectory()
            {
            return new java.io.File(new java.io.File(m_dirActiveRoot, FileHelper.toFilename(m_sClusterName)),
                    FileHelper.toFilename(m_sServiceName));
            }

        java.io.File getSnapshotServiceDirectory()
            {
            return new java.io.File(new java.io.File(m_dirSnapshotRoot, FileHelper.toFilename(m_sClusterName)),
                    FileHelper.toFilename(m_sServiceName));
            }

        java.io.File getSnapshotDirectory(String sSnapshotName)
            {
            return new java.io.File(getSnapshotServiceDirectory(), FileHelper.toFilename(sSnapshotName));
            }

        java.io.File getTrashServiceDirectory()
            {
            return new java.io.File(new java.io.File(m_dirTrashRoot, FileHelper.toFilename(m_sClusterName)),
                    FileHelper.toFilename(m_sServiceName));
            }

        private final String                  m_sClusterName;
        private final String                  m_sServiceName;
        private final NamedCache<String, String> m_cache;
        private final DistributedCacheService m_service;
        private final java.io.File            m_dirActiveRoot;
        private final java.io.File            m_dirSnapshotRoot;
        private final java.io.File            m_dirTrashRoot;
        }

    // ----- inner interface: DemoAction ----------------------------------

    @FunctionalInterface
    private interface DemoAction
        {
        void run(ConfigurableCacheFactory factory)
                throws Exception;
        }

    // ----- constants -----------------------------------------------------

    private static final String CLIENT_CACHE_CONFIG = "client-cache-config.xml";
    private static final String CACHE_NAME          = "simple-persistent-1";
    private static final String DISPLAY_CACHE_NAME  = "Movies";
    private static final String SNAPSHOT_NAME       = "up-demo-bdb-snapshot";
    private static final String DATASET_RESOURCE    = "/demo/up-migration-movies.tsv";

    private static final String PROP_ACTIVE_DIR   = "test.persistence.active.dir";
    private static final String PROP_SNAPSHOT_DIR = "test.persistence.snapshot.dir";
    private static final String PROP_TRASH_DIR    = "test.persistence.trash.dir";
    }
