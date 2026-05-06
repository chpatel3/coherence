/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package persistence;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import java.time.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;

import static com.oracle.bedrock.deferred.DeferredHelper.invoking;

import static org.hamcrest.CoreMatchers.is;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Performance-oriented benchmark harness for primary journal-backing-map restart recovery.
 *
 * @author Aleks Seovic  2026.04.23
 * @since 26.04
 */
public class JournalBackingMapRecoveryBenchmarkTests
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
    public void benchmarkJournalBackingMapRestartRecovery()
            throws Exception
        {
        assumeTrue("Performance benchmark; set test.journalbacking.benchmark.enabled=true to run",
                Boolean.getBoolean("test.journalbacking.benchmark.enabled"));

        ScenarioResult result = runRestartScenario();

        System.out.println();
        System.out.println("=== Journal Backing Map Recovery Benchmark ===");
        System.out.printf("Dataset: %d live entries, %d updates/key, %d total writes, %d bytes/value, %d partitions%n",
                ENTRY_COUNT, UPDATES_PER_KEY, (long) ENTRY_COUNT * UPDATES_PER_KEY, VALUE_SIZE_BYTES, PARTITION_COUNT);
        System.out.println(result.describe());
        }

    private ScenarioResult runRestartScenario()
            throws Exception
        {
        File             fileActive   = FileHelper.createTempDir();
        File             fileSnapshot = FileHelper.createTempDir();
        File             fileTrash    = FileHelper.createTempDir();
        String           sClusterName = createClusterName("benchmark-restart");
        PropertySnapshot snapshot     = applyClientProperties(sClusterName);

        try
            {
            Cluster cluster = CacheFactory.getCluster();
            String  sServer = "JournalBackingBenchmark-1";
            String  sRestart = "JournalBackingBenchmark-Restart-1";

            ConfigurableCacheFactory factory = ensureFactory();
            NamedCache<Integer, byte[]> cache = ensureCache(factory);
            DistributedCacheService     service = (DistributedCacheService) cache.getCacheService();

            Properties props = createMemberProperties(sClusterName, fileActive, fileSnapshot, fileTrash,
                    "simple-journal-environment", "active");

            startCacheServer(sServer, PROJECT_NAME, BACKING_MAP_CACHE_CONFIG, props);
            waitForClusterReady(service, cache, 1);

            long cStoreMillis = measureMillis(() -> populate(cache));
            validate(cache);

            long cbActiveBytes = directorySize(fileActive);
            assertTrue("Expected active journal data before restart", cbActiveBytes > 0L);

            stopCacheServer(sServer);
            Eventually.assertThat(invoking(cluster).getMemberSet().size(), is(1));

            CacheFactory.shutdown();

            long ldtRestartStart = System.nanoTime();

            startCacheServer(sRestart, PROJECT_NAME, BACKING_MAP_CACHE_CONFIG, props, false);

            ConfigurableCacheFactory factoryRestart = ensureFactory();
            NamedCache<Integer, byte[]> cacheRestart = ensureCache(factoryRestart);
            DistributedCacheService     serviceRestart = (DistributedCacheService) cacheRestart.getCacheService();

            waitForClusterReady(serviceRestart, cacheRestart, 1);
            validate(cacheRestart);

            long cRestartMillis = Duration.ofNanos(System.nanoTime() - ldtRestartStart).toMillis();

            return new ScenarioResult(cStoreMillis, cRestartMillis, cbActiveBytes);
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

    private NamedCache<Integer, byte[]> ensureCache(ConfigurableCacheFactory factory)
        {
        @SuppressWarnings("unchecked")
        NamedCache<Integer, byte[]> cache = factory.ensureCache(CACHE_NAME, null);
        return cache;
        }

    private void populate(NamedCache<Integer, byte[]> cache)
        {
        Map<Integer, byte[]> mapBatch = new LinkedHashMap<>(BATCH_SIZE);

        for (int nUpdate = 0; nUpdate < UPDATES_PER_KEY; nUpdate++)
            {
            for (int i = 0; i < ENTRY_COUNT; i++)
                {
                mapBatch.put(Integer.valueOf(i), expectedValue(i, nUpdate));

                if (mapBatch.size() == BATCH_SIZE)
                    {
                    cache.putAll(mapBatch);
                    mapBatch.clear();
                    }
                }

            if (!mapBatch.isEmpty())
                {
                cache.putAll(mapBatch);
                mapBatch.clear();
                }
            }
        }

    private void validate(NamedCache<Integer, byte[]> cache)
        {
        Eventually.assertDeferred(cache::size, is(ENTRY_COUNT));

        List<Integer> listKeys = new ArrayList<>(BATCH_SIZE);

        for (int i = 0; i < ENTRY_COUNT; i++)
            {
            listKeys.add(Integer.valueOf(i));

            if (listKeys.size() == BATCH_SIZE)
                {
                validateBatch(cache, listKeys);
                listKeys.clear();
                }
            }

        if (!listKeys.isEmpty())
            {
            validateBatch(cache, listKeys);
            }
        }

    private void validateBatch(NamedCache<Integer, byte[]> cache, List<Integer> listKeys)
        {
        @SuppressWarnings("unchecked")
        Map<Integer, byte[]> mapValues = cache.getAll(listKeys);

        for (Integer IKey : listKeys)
            {
            assertArrayEquals("Value mismatch for key " + IKey,
                    expectedValue(IKey.intValue(), UPDATES_PER_KEY - 1), mapValues.get(IKey));
            }
        }

    private byte[] expectedValue(int nKey, int nUpdate)
        {
        byte[] abValue  = new byte[VALUE_SIZE_BYTES];
        byte[] abHeader = ("backing-benchmark:" + nKey + ":" + nUpdate).getBytes(StandardCharsets.UTF_8);

        System.arraycopy(abHeader, 0, abValue, 0, Math.min(abHeader.length, abValue.length));
        for (int i = abHeader.length; i < abValue.length; i++)
            {
            abValue[i] = (byte) (nKey + i);
            }

        return abValue;
        }

    private Properties createMemberProperties(String sClusterName, File fileActive, File fileSnapshot,
            File fileTrash, String sEnvironment, String sPersistenceMode)
        {
        Properties props = new Properties();
        props.setProperty("coherence.cluster", sClusterName);
        props.setProperty("coherence.localhost", "127.0.0.1");
        props.setProperty("coherence.ttl", "0");
        props.setProperty("coherence.management", "all");
        props.setProperty("coherence.management.remote", "true");
        props.setProperty("coherence.management.refresh.expiry", "1s");
        props.setProperty("coherence.management.http", "inherit");
        props.setProperty("coherence.management.metrics.port", "0");
        props.setProperty("coherence.log.level", "1");
        props.setProperty("coherence.override", "common-tangosol-coherence-override.xml");
        props.setProperty("coherence.distribution.2server", "false");
        props.setProperty("coherence.distributed.partitions", Integer.toString(PARTITION_COUNT));
        props.setProperty("test.backupcount", "1");
        props.setProperty("test.threads", Integer.toString(THREAD_COUNT));
        props.setProperty("test.persistence.mode", sPersistenceMode);
        props.setProperty("test.persistence.active.dir", fileActive.getAbsolutePath());
        props.setProperty("test.persistence.snapshot.dir", fileSnapshot.getAbsolutePath());
        props.setProperty("test.persistence.trash.dir", fileTrash.getAbsolutePath());
        props.setProperty("test.persistent-environment", sEnvironment);
        applyResolvedLauncherProperties(props);
        return props;
        }

    private PropertySnapshot applyClientProperties(String sClusterName)
        {
        PropertySnapshot snapshot = new PropertySnapshot();

        snapshot.setProperty("coherence.cluster", sClusterName);
        snapshot.setProperty("coherence.localhost", "127.0.0.1");
        snapshot.setProperty("coherence.ttl", "0");
        snapshot.setProperty("coherence.management", "all");
        snapshot.setProperty("coherence.management.remote", "true");
        snapshot.setProperty("coherence.management.refresh.expiry", "1s");
        snapshot.setProperty("coherence.management.http", "inherit");
        snapshot.setProperty("coherence.management.metrics.port", "0");
        snapshot.setProperty("coherence.distributed.localstorage", "false");
        snapshot.setProperty("coherence.override", "common-tangosol-coherence-override.xml");
        applyResolvedLauncherProperties(snapshot, new File(System.getProperty("user.dir")).getAbsoluteFile());

        CacheFactory.shutdown();

        return snapshot;
        }

    private void applyResolvedLauncherProperties(Properties props)
        {
        applyResolvedLauncherProperties(props, new File(System.getProperty("user.dir")).getAbsoluteFile());
        }

    private void applyResolvedLauncherProperties(PropertySnapshot snapshot, File fileProjectDir)
        {
        Properties props = new Properties();
        applyResolvedLauncherProperties(props, fileProjectDir);

        for (String sName : props.stringPropertyNames())
            {
            snapshot.setProperty(sName, props.getProperty(sName));
            }
        }

    private void applyResolvedLauncherProperties(Properties props, File fileProjectDir)
        {
        File fileRootDir = fileProjectDir.getParentFile().getParentFile().getParentFile();

        props.setProperty("test.project.dir", fileProjectDir.getAbsolutePath());
        props.setProperty("test.root.dir", fileRootDir.getAbsolutePath());
        props.setProperty("test.tmp.dir", System.getProperty("java.io.tmpdir"));
        }

    private ConfigurableCacheFactory ensureFactory()
        {
        ConfigurableCacheFactory factory = CacheFactory.getCacheFactoryBuilder()
                .getConfigurableCacheFactory("client-cache-config.xml", null);
        setFactory(factory);
        return factory;
        }

    private void waitForClusterReady(DistributedCacheService service, NamedCache<?, ?> cache, int cStorageMembers)
        {
        Eventually.assertDeferred(() -> service.getOwnershipEnabledMembers().size(), is(cStorageMembers));
        Eventually.assertDeferred(() -> service.getPartitionOwner(0) != null, is(true));
        AbstractRollingRestartTest.waitForNoOrphans(cache.getCacheService());
        AbstractRollingRestartTest.waitForBalanced(cache.getCacheService());
        }

    private long directorySize(File dir)
            throws IOException
        {
        if (!dir.isDirectory())
            {
            return 0L;
            }

        final long[] cbTotal = new long[1];

        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>()
            {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException
                {
                try
                    {
                    cbTotal[0] += Files.size(file);
                    }
                catch (NoSuchFileException ignored)
                    {
                    }

                return java.nio.file.FileVisitResult.CONTINUE;
                }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException
                {
                if (exc instanceof NoSuchFileException)
                    {
                    return java.nio.file.FileVisitResult.CONTINUE;
                    }

                throw exc;
                }
            });

        return cbTotal[0];
        }

    private long measureMillis(CheckedRunnable task)
            throws Exception
        {
        long ldtStart = System.nanoTime();
        task.run();
        return Duration.ofNanos(System.nanoTime() - ldtStart).toMillis();
        }

    private String createClusterName(String sLabel)
        {
        String sSuffix = Long.toUnsignedString(ProcessHandle.current().pid(), 36)
                + "-"
                + Long.toUnsignedString(System.nanoTime(), 36);
        int    cLabel  = MAX_CLUSTER_NAME_LENGTH - CLUSTER_NAME_PREFIX.length() - sSuffix.length() - 2;

        if (sLabel.length() > cLabel)
            {
            sLabel = sLabel.substring(0, cLabel);
            }

        return CLUSTER_NAME_PREFIX + "-" + sLabel + "-" + sSuffix;
        }

    @FunctionalInterface
    private interface CheckedRunnable
        {
        void run()
                throws Exception;
        }

    private static class ScenarioResult
        {
        private ScenarioResult(long cStoreMillis, long cRestartMillis, long cbActiveBytes)
            {
            m_cStoreMillis   = cStoreMillis;
            m_cRestartMillis = cRestartMillis;
            m_cbActiveBytes  = cbActiveBytes;
            }

        private String describe()
            {
            return String.format("restart: store=%d ms, restart=%d ms, active-bytes=%d",
                    m_cStoreMillis, m_cRestartMillis, m_cbActiveBytes);
            }

        private final long m_cStoreMillis;
        private final long m_cRestartMillis;
        private final long m_cbActiveBytes;
        }

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

    private static final String CACHE_NAME = "journal-store-compatibility";

    private static final String BACKING_MAP_CACHE_CONFIG = "journal-store-compat-backing-map-cache-config.xml";

    private static final String CLUSTER_NAME_PREFIX = "jbm";

    private static final int MAX_CLUSTER_NAME_LENGTH = 66;

    private static final String PROJECT_NAME = "persistence";

    private static final int PARTITION_COUNT = Integer.getInteger("test.journalbacking.partition.count", 257);

    private static final int THREAD_COUNT = Integer.getInteger("test.journalbacking.threads", 5);

    private static final int ENTRY_COUNT = Integer.getInteger("test.journalbacking.entry.count", 10000);

    private static final int UPDATES_PER_KEY = Integer.getInteger("test.journalbacking.updates.per.key", 1);

    private static final int VALUE_SIZE_BYTES = Integer.getInteger("test.journalbacking.value.size", 512);

    private static final int BATCH_SIZE = Integer.getInteger("test.journalbacking.batch.size", 1000);
    }
