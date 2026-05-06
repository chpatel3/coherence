/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package persistence;

import com.oracle.bedrock.testsupport.deferred.Eventually;

import com.oracle.coherence.testing.AbstractRollingRestartTest;

import com.tangosol.coherence.component.util.SafeService;
import com.tangosol.coherence.component.util.daemon.queueProcessor.service.grid.partitionedService.PartitionedCache;
import com.tangosol.io.FileHelper;

import com.tangosol.net.CacheFactory;
import com.tangosol.net.CacheService;
import com.tangosol.net.Coherence;
import com.tangosol.net.CoherenceConfiguration;
import com.tangosol.net.DistributedCacheService;
import com.tangosol.net.NamedCache;
import com.tangosol.net.SessionConfiguration;
import com.tangosol.persistence.PersistenceRecoveryMetrics;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import java.time.Duration;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import org.junit.Test;

import static org.junit.Assume.assumeTrue;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Benchmark-only functional comparison of Berkeley DB and journal persistence
 * for restart recovery time and active-store disk footprint.
 * <p>
 * This class is intentionally skipped in standard functional validation and
 * should be enabled only for manual benchmark runs via
 * {@code -Pbenchmark-tests} or {@code -Dtest.persistence.benchmark=true}.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class JournalVsBdbComparisonTests
    {
    // ----- tests ----------------------------------------------------------

    @Test
    public void compareRecoveryAndFootprint()
            throws Exception
        {
        assumeTrue("Benchmark-only comparison; enable with -Pbenchmark-tests or "
                        + "-Dtest.persistence.benchmark=true",
                Boolean.getBoolean("test.persistence.benchmark"));

        ComparisonResult resultBdb = isScenarioEnabled("bdb")
                ? runScenario("bdb",
                "simple-persistence-bdb-cache-config.xml",
                "simple-bdb-environment",
                RecoveryMode.ACTIVE_RESTART)
                : null;
        ComparisonResult resultJournal = isScenarioEnabled("journal") || isScenarioEnabled("journal-checkpoint")
                ? runScenario("journal",
                "simple-persistence-journal-cache-config.xml",
                "simple-journal-environment",
                RecoveryMode.ACTIVE_RESTART)
                : null;

        assertTrue("At least one scenario must be enabled",
                resultBdb != null || resultJournal != null);
        if (resultBdb != null)
            {
            assertTrue("BDB active store should not be empty", resultBdb.m_cbActiveBytes > 0L);
            }
        if (resultJournal != null)
            {
            assertTrue("Journal active store should not be empty", resultJournal.m_cbActiveBytes > 0L);
            }

        System.out.println();
        System.out.println("=== Journal vs Berkeley DB Comparison ===");
        System.out.println("Dataset: "
                + ENTRY_COUNT_PER_CACHE + " live entries per cache, "
                + UPDATES_PER_KEY + " updates/key, "
                + TOTAL_WRITES_PER_CACHE + " total writes per cache, "
                + getCacheNames().length + " caches, "
                + VALUE_SIZE_BYTES + " bytes/value, "
                + "1 member, "
                + PARTITION_COUNT + " partitions");
        if (resultBdb != null)
            {
            System.out.println(resultBdb.describe());
            }
        if (resultJournal != null)
            {
            System.out.println(resultJournal.describe());
            }
        if (resultBdb != null && resultJournal != null)
            {
            System.out.printf("Store delta (journal - bdb): %d ms%n",
                    resultJournal.m_cStoreMillis - resultBdb.m_cStoreMillis);
            System.out.printf("Recovery delta (journal - bdb): %d ms%n",
                    resultJournal.m_cRecoveryMillis - resultBdb.m_cRecoveryMillis);
            System.out.printf("Footprint delta (journal - bdb): %d bytes%n",
                    resultJournal.m_cbActiveBytes - resultBdb.m_cbActiveBytes);
            }
        }

    // ----- helpers --------------------------------------------------------

    private ComparisonResult runScenario(String sLabel, String sCacheConfig, String sEnvironment,
            RecoveryMode mode)
            throws Exception
        {
        String               sRunId        = createRunId();
        MemberDirectories    dirsPrimary   = MemberDirectories.create();
        MemberDirectories    dirsSecondary = null;
        MemberDirectories    dirsRestart   = dirsPrimary;
        String               sClusterName  = createClusterName(sLabel, sRunId);
        String               sInstanceName = sClusterName;
        String               sSessionName  = sInstanceName + "-session";
        Properties           props       = createBaseProperties(sClusterName, sEnvironment, mode);
        PropertySnapshot     snapshot    = applyProperties(props);
        String[]             asCacheNames = getCacheNames();
        Coherence            coherence   = null;

        try
            {
            applyMemberDirectories(snapshot, dirsPrimary);
            String       sStorePhaseSummary;
            try
                {
                coherence = startMember(sCacheConfig, sInstanceName, sSessionName);

                NamedCache              cacheOne = coherence.getSession().getCache(asCacheNames[0]);
                NamedCache              cacheTwo = asCacheNames.length > 1 ? coherence.getSession().getCache(asCacheNames[1]) : null;
                DistributedCacheService service  = (DistributedCacheService) cacheOne.getCacheService();

                waitForClusterReady(service, cacheOne);
                JfrRecording jfrStore = maybeStartJfr(sLabel, "store");
                long         ldtStore = System.nanoTime();
                try
                    {
                    populate(cacheOne, asCacheNames[0]);
                    if (cacheTwo != null)
                        {
                        populate(cacheTwo, asCacheNames[1]);
                        }
                    }
                finally
                    {
                    jfrStore.stop();
                    }
                long cStoreMillis = Duration.ofNanos(System.nanoTime() - ldtStore).toMillis();
                validate(cacheOne, asCacheNames[0]);
                if (cacheTwo != null)
                    {
                    validate(cacheTwo, asCacheNames[1]);
                    }

                sStorePhaseSummary = null;

                if (mode == RecoveryMode.FULL_REPLAY)
                    {
                    dirsSecondary = MemberDirectories.create();
                    copyRestartImage(dirsPrimary, dirsSecondary);
                    dirsRestart = dirsSecondary;
                    logCheckpointFiles("replay-after-copy", dirsRestart);
                    assertCheckpointLayout(dirsRestart, sEnvironment, mode);
                    }

                stopMember(coherence, true);
                coherence = null;

                assertCheckpointLayout(dirsRestart, sEnvironment, mode);
                logJournalFiles("post-stop", dirsRestart);

                if (mode == RecoveryMode.FULL_REPLAY)
                    {
                    logCheckpointFiles("replay-primary-after-stop", dirsPrimary);
                    logCheckpointFiles("replay-after-stop-before-restart", dirsRestart);
                    }

                long cbActive = directorySize(dirsRestart.m_dirActive);

                applyMemberDirectories(snapshot, dirsRestart);
                if (mode == RecoveryMode.FULL_REPLAY)
                    {
                    System.out.printf("Restart active dir property: %s%n",
                            System.getProperty("test.persistence.active.dir"));
                    }
                PersistenceRecoveryMetrics.beginRecovery();
                JfrRecording jfrRecovery = maybeStartJfr(sLabel, "recovery");
                long         ldtRecovery = System.nanoTime();
                try
                    {
                    coherence = startMember(sCacheConfig, sInstanceName, sSessionName);
                    cacheOne  = coherence.getSession().getCache(asCacheNames[0]);
                    cacheTwo  = asCacheNames.length > 1 ? coherence.getSession().getCache(asCacheNames[1]) : null;
                    service   = (DistributedCacheService) cacheOne.getCacheService();
                    PartitionedCache.PersistenceControl ctrl = getPersistenceControl(service);

                    Eventually.assertDeferred(
                            () -> ctrl.getLastRecoverySource() != null || ctrl.getActiveRecoveryRequests().get() > 0,
                            is(true));
                    Eventually.assertDeferred(() -> ctrl.getActiveRecoveryRequests().get(), is(0));

                    long cRecoveryMillis = Duration.ofNanos(System.nanoTime() - ldtRecovery).toMillis();

                    jfrRecovery.stop();

                    waitForClusterReady(service, cacheOne);

                    validate(cacheOne, asCacheNames[0]);
                    if (cacheTwo != null)
                        {
                        validate(cacheTwo, asCacheNames[1]);
                        }

                    if (mode == RecoveryMode.FULL_REPLAY)
                        {
                        logCheckpointFiles("replay-after-restart", dirsRestart);
                        }
                    RecoveryMetrics metrics = getLocalRecoveryMetrics(service, cRecoveryMillis);
                    String          sRecoverySummary = PersistenceRecoveryMetrics.endRecovery();

                    return new ComparisonResult(sLabel, cStoreMillis, metrics, cbActive, sStorePhaseSummary, sRecoverySummary);
                    }
                catch (Throwable t)
                    {
                    jfrRecovery.stop();
                    PersistenceRecoveryMetrics.endRecovery();
                    throw t;
                    }
                }
                catch (Throwable t)
                    {
                    throw t;
                    }
                }
        finally
            {
            stopMember(coherence, true);
            snapshot.restore();
            dirsPrimary.delete();
            if (dirsSecondary != null)
                {
                dirsSecondary.delete();
                }
            }
        }

    private Properties createBaseProperties(String sClusterName, String sEnvironment, RecoveryMode mode)
        {
        Properties props = new Properties();
        props.setProperty("coherence.cluster", sClusterName);
        props.setProperty("test.persistent-environment", sEnvironment);
        props.setProperty("coherence.localhost", "127.0.0.1");
        props.setProperty("coherence.ttl", "0");
        props.setProperty("coherence.distributed.localstorage", "true");
        props.setProperty("coherence.management", "all");
        props.setProperty("coherence.management.remote", "true");
        props.setProperty("coherence.management.refresh.expiry", "1s");
        props.setProperty("coherence.management.http", "inherit");
        props.setProperty("coherence.management.metrics.port", "0");
        props.setProperty("coherence.log.level", "1");
        props.setProperty("test.log.level", "1");
        props.setProperty("coherence.override", "common-tangosol-coherence-override.xml");
        props.setProperty("test.backupcount", "1");
        props.setProperty("coherence.distributed.partitions", String.valueOf(PARTITION_COUNT));
        if (mode.getJournalCheckpointThreshold() != null)
            {
            props.setProperty("coherence.persistence.journal.checkpoint.threshold",
                    mode.getJournalCheckpointThreshold().toString());
            }
        return props;
        }

    private String createClusterName(String sLabel, String sRunId)
        {
        return getClass().getSimpleName() + "-" + sLabel + "-" + sRunId;
        }

    private String createRunId()
        {
        return Long.toUnsignedString(ProcessHandle.current().pid(), 36)
                + "-"
                + Long.toUnsignedString(System.nanoTime(), 36);
        }

    private PropertySnapshot applyProperties(Properties props)
        {
        PropertySnapshot snapshot = new PropertySnapshot();

        for (String sName : props.stringPropertyNames())
            {
            snapshot.capture(sName);
            System.setProperty(sName, props.getProperty(sName));
            }

        return snapshot;
        }

    private void applyMemberDirectories(PropertySnapshot snapshot, MemberDirectories dirs)
        {
        snapshot.capture("test.persistence.active.dir");
        snapshot.capture("test.persistence.backup.dir");
        snapshot.capture("test.persistence.snapshot.dir");
        snapshot.capture("test.persistence.trash.dir");
        System.setProperty("test.persistence.active.dir", dirs.m_dirActive.getAbsolutePath());
        System.setProperty("test.persistence.backup.dir", dirs.m_dirBackup.getAbsolutePath());
        System.setProperty("test.persistence.snapshot.dir", dirs.m_dirSnapshot.getAbsolutePath());
        System.setProperty("test.persistence.trash.dir", dirs.m_dirTrash.getAbsolutePath());
        }

    private Coherence startMember(String sCacheConfig, String sInstanceName, String sSessionName)
            throws Exception
        {
        CoherenceConfiguration configuration = CoherenceConfiguration.builder()
                .named(sInstanceName)
                .discoverSessions(false)
                .withSession(SessionConfiguration.create(sSessionName, sCacheConfig))
                .withDefaultSession(sSessionName)
                .build();

        return Coherence.clusterMember(configuration).startAndWait();
        }

    private void stopMember(Coherence coherence, boolean fGlobalCleanup)
        {
        if (coherence != null)
            {
            coherence.close();
            }

        if (fGlobalCleanup)
            {
            Coherence.closeAll();
            CacheFactory.getCacheFactoryBuilder().releaseAll(null);
            CacheFactory.shutdown();
            }
        }

    private void waitForClusterReady(DistributedCacheService service, NamedCache cache)
        {
        Eventually.assertDeferred(() -> service.getOwnershipEnabledMembers().size(), is(1));
        Eventually.assertDeferred(() -> service.getPartitionOwner(0) != null, is(true));
        AbstractRollingRestartTest.waitForNoOrphans(cache.getCacheService());
        AbstractRollingRestartTest.waitForBalanced(cache.getCacheService());
        }

    private void populate(NamedCache cache, String sCacheName)
        {
        Map<Integer, byte[]> mapBatch = new HashMap<>(BATCH_SIZE);

        for (int nUpdate = 0; nUpdate < UPDATES_PER_KEY; nUpdate++)
            {
            for (int i = 0; i < ENTRY_COUNT_PER_CACHE; i++)
                {
                mapBatch.put(i, expectedValue(sCacheName, i, nUpdate));

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

    private void validate(NamedCache cache, String sCacheName)
        {
        Eventually.assertDeferred(cache::size, is(ENTRY_COUNT_PER_CACHE));

        List<Integer> listKeys = new ArrayList<>(BATCH_SIZE);

        for (int i = 0; i < ENTRY_COUNT_PER_CACHE; i++)
            {
            listKeys.add(i);

            if (listKeys.size() == BATCH_SIZE)
                {
                validateBatch(cache, sCacheName, listKeys);
                listKeys.clear();
                }
            }

        if (!listKeys.isEmpty())
            {
            validateBatch(cache, sCacheName, listKeys);
            }
        }

    private void validateBatch(NamedCache cache, String sCacheName, List<Integer> listKeys)
        {
        @SuppressWarnings("unchecked")
        Map<Integer, byte[]> mapValues = cache.getAll(listKeys);

        for (Integer IKey : listKeys)
            {
            assertArrayEquals(expectedValue(sCacheName, IKey, UPDATES_PER_KEY - 1), mapValues.get(IKey));
            }
        }

    private RecoveryMetrics getLocalRecoveryMetrics(CacheService service, long cRecoveryMillis)
        {
        PartitionedCache.PersistenceControl ctrl = getPersistenceControl(service);

        return new RecoveryMetrics(
                cRecoveryMillis,
                ctrl.getLastRecoveryRecoveredPartitions(),
                ctrl.getLastRecoveryFailedPartitions(),
                ctrl.getLastRecoverySource());
        }

    private PartitionedCache.PersistenceControl getPersistenceControl(CacheService service)
        {
        if (service instanceof SafeService)
            {
            service = (CacheService) ((SafeService) service).getService();
            }

        return (PartitionedCache.PersistenceControl) ((PartitionedCache) service).getPersistenceControl();
        }

    private boolean isScenarioEnabled(String sLabel)
        {
        return ENABLED_SCENARIOS.isEmpty() || ENABLED_SCENARIOS.contains(sLabel);
        }

    private JfrRecording maybeStartJfr(String sLabel, String sStage)
            throws Exception
        {
        if (!JFR_ENABLED || !JFR_SCENARIOS.isEmpty() && !JFR_SCENARIOS.contains(sLabel))
            {
            return JfrRecording.disabled();
            }

        Path dir = Path.of("target", "jfr");
        Files.createDirectories(dir);

        Path path = dir.resolve(String.format("journal-vs-bdb-%s-%s-%d-%d-p%d-u%d.jfr",
                sLabel, sStage, ENTRY_COUNT_PER_CACHE, VALUE_SIZE_BYTES, PARTITION_COUNT, UPDATES_PER_KEY));

        Files.deleteIfExists(path);

        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName("JournalVsBdbComparisonTests-" + sLabel + "-" + sStage);
        recording.setDumpOnExit(true);
        recording.setDestination(path);
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
        recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
        recording.start();

        System.out.printf("JFR started for %s/%s -> %s%n", sLabel, sStage, path.toAbsolutePath());
        return new JfrRecording(recording, path);
        }

    private void assertCheckpointLayout(MemberDirectories dirs, String sEnvironment, RecoveryMode mode)
            throws IOException
        {
        if (!sEnvironment.contains("journal"))
            {
            return;
            }

        boolean fCheckpointPresent;

        if (mode == RecoveryMode.ACTIVE_RESTART)
            {
            fCheckpointPresent = containsFileNamed(dirs, "checkpoint.coh");
            System.out.printf("Journal layout (%s): checkpoint file %s%n",
                    mode.name().toLowerCase(), fCheckpointPresent ? "observed" : "not observed");
            }
        else if (mode == RecoveryMode.GRACEFUL_CHECKPOINT)
            {
            try
                {
                fCheckpointPresent = waitForFileNamed(dirs, "checkpoint.coh", 5_000L);
                }
            catch (InterruptedException e)
                {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for checkpoint file", e);
                }
            System.out.printf("Journal layout (%s): checkpoint file %s%n",
                    mode.name().toLowerCase(), fCheckpointPresent ? "observed" : "not observed");
            }
        else if (mode == RecoveryMode.FULL_REPLAY)
            {
            fCheckpointPresent = containsFileNamed(dirs, "checkpoint.coh");
            assertTrue("Did not expect checkpoint file for full replay mode", !fCheckpointPresent);
            }
        }

    private void copyRestartImage(MemberDirectories dirsSource, MemberDirectories dirsTarget)
            throws IOException
        {
        FileHelper.deleteDir(dirsTarget.m_dirActive);
        FileHelper.copyDir(dirsSource.m_dirActive, dirsTarget.m_dirActive);
        }

    private boolean containsFileNamed(MemberDirectories dirs, String sFileName)
            throws IOException
        {
        return containsFileNamed(dirs.m_dirActive.toPath(), sFileName);
        }

    private boolean containsFileNamed(Path dir, String sFileName)
            throws IOException
        {
        boolean[] afFound = {false};

        Files.walkFileTree(dir, new SimpleFileVisitor<>()
            {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                if (attrs.isRegularFile() && sFileName.equals(file.getFileName().toString()))
                    {
                    afFound[0] = true;
                    return FileVisitResult.TERMINATE;
                    }

                return FileVisitResult.CONTINUE;
                }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e)
                    throws IOException
                {
                if (e instanceof NoSuchFileException)
                    {
                    return FileVisitResult.CONTINUE;
                    }

                throw e;
                }
            });

        return afFound[0];
        }

    private void logCheckpointFiles(String sLabel, MemberDirectories dirs)
            throws IOException
        {
        List<String> listFiles = listFilesNamed(dirs.m_dirActive.toPath(), "checkpoint.coh");

        System.out.printf("Checkpoint files (%s): %s%n",
                sLabel,
                listFiles.isEmpty() ? "<none>" : String.join(", ", listFiles));
        }

    private void deleteFilesNamed(MemberDirectories dirs, String sFileName)
            throws IOException
        {
        Files.walkFileTree(dirs.m_dirActive.toPath(), new SimpleFileVisitor<>()
            {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException
                {
                if (attrs.isRegularFile() && sFileName.equals(file.getFileName().toString()))
                    {
                    Files.deleteIfExists(file);
                    }

                return FileVisitResult.CONTINUE;
                }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e)
                    throws IOException
                {
                if (e instanceof NoSuchFileException)
                    {
                    return FileVisitResult.CONTINUE;
                    }

                throw e;
                }
            });
        }

    private void logJournalFiles(String sLabel, MemberDirectories dirs)
            throws IOException
        {
        List<Path> listFiles = listJournalFiles(dirs.m_dirActive.toPath());
        long       cbTotal   = 0L;

        for (Path path : listFiles)
            {
            cbTotal += Files.size(path);
            }

        System.out.printf("Journal files (%s): count=%d, bytes=%d%n",
                sLabel, listFiles.size(), cbTotal);

        if (!listFiles.isEmpty())
            {
            System.out.printf("Journal files (%s) detail: %s%n",
                    sLabel,
                    listFiles.stream()
                            .map(path ->
                                {
                                try
                                    {
                                    return dirs.m_dirActive.toPath().relativize(path) + "(" + Files.size(path) + ")";
                                    }
                                catch (IOException e)
                                    {
                                    throw new RuntimeException(e);
                                    }
                                })
                            .collect(Collectors.joining(", ")));
            }
        }

    private List<String> listFilesNamed(Path dir, String sFileName)
            throws IOException
        {
        List<String> listFiles = new ArrayList<>();

        Files.walkFileTree(dir, new SimpleFileVisitor<>()
            {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                if (attrs.isRegularFile() && sFileName.equals(file.getFileName().toString()))
                    {
                    listFiles.add(dir.relativize(file).toString());
                    }

                return FileVisitResult.CONTINUE;
                }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e)
                    throws IOException
                {
                if (e instanceof NoSuchFileException)
                    {
                    return FileVisitResult.CONTINUE;
                    }

                throw e;
                }
            });

        Collections.sort(listFiles);
        return listFiles;
        }

    private List<Path> listJournalFiles(Path dir)
            throws IOException
        {
        List<Path> listFiles = new ArrayList<>();

        Files.walkFileTree(dir, new SimpleFileVisitor<>()
            {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                String sName = file.getFileName().toString();
                if (attrs.isRegularFile() && sName.startsWith("journal-") && sName.endsWith(".coh"))
                    {
                    listFiles.add(file);
                    }

                return FileVisitResult.CONTINUE;
                }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e)
                    throws IOException
                {
                if (e instanceof NoSuchFileException)
                    {
                    return FileVisitResult.CONTINUE;
                    }

                throw e;
                }
            });

        Collections.sort(listFiles);
        return listFiles;
        }

    private boolean waitForFileNamed(MemberDirectories dirs, String sFileName, long cWaitMillis)
            throws IOException, InterruptedException
        {
        long ldtDeadline = System.currentTimeMillis() + cWaitMillis;

        while (System.currentTimeMillis() < ldtDeadline)
            {
            if (containsFileNamed(dirs, sFileName))
                {
                return true;
                }

            Thread.sleep(100L);
            }

        return containsFileNamed(dirs, sFileName);
        }

    private String[] getCacheNames()
        {
        int cCaches = Math.max(1, CACHE_COUNT);
        String[] asNames = new String[cCaches];

        for (int i = 0; i < cCaches; i++)
            {
            asNames[i] = "simple-persistent-" + (i + 1);
            }
        return asNames;
        }

    private byte[] expectedValue(String sCacheName, int nKey, int nUpdate)
        {
        byte[] abValue  = new byte[VALUE_SIZE_BYTES];
        byte[] abHeader = (sCacheName + ":" + nKey + ":" + nUpdate).getBytes(StandardCharsets.UTF_8);

        System.arraycopy(abHeader, 0, abValue, 0, Math.min(abHeader.length, abValue.length));
        for (int i = abHeader.length; i < abValue.length; i++)
            {
            abValue[i] = (byte) (nKey + i);
            }

        return abValue;
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
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException
                {
                try
                    {
                    cbTotal[0] += Files.size(file);
                    }
                catch (NoSuchFileException ignored)
                    {
                    // BDB lock files can disappear during shutdown sizing.
                    }

                return FileVisitResult.CONTINUE;
                }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException
                {
                if (exc instanceof NoSuchFileException)
                    {
                    return FileVisitResult.CONTINUE;
                    }

                throw exc;
                }
            });

        return cbTotal[0];
        }

    // ----- inner class: ComparisonResult ----------------------------------

    private static class ComparisonResult
        {
        private ComparisonResult(String sLabel, long cStoreMillis, RecoveryMetrics metrics,
                long cbActiveBytes, String sStorePhaseSummary, String sRecoverySummary)
            {
            m_cStoreMillis      = cStoreMillis;
            m_sLabel            = sLabel;
            m_cRecoveryMillis   = metrics.m_cRecoveryMillis;
            m_cRecoveredParts   = metrics.m_cRecoveredPartitions;
            m_cFailedParts      = metrics.m_cFailedPartitions;
            m_sRecoverySource   = metrics.m_sSource;
            m_cbActiveBytes     = cbActiveBytes;
            m_sStorePhaseSummary = sStorePhaseSummary;
            m_sRecoverySummary  = sRecoverySummary;
            }

        private String describe()
            {
            String sStoreDetails    = formatSummaryDetails(m_sStorePhaseSummary);
            String sRecoveryDetails = formatSummaryDetails(m_sRecoverySummary);
            int    cSkippedParts    = m_cRecoveredParts + m_cFailedParts == PARTITION_COUNT ? m_cFailedParts : 0;
            String sPartitionSummary = cSkippedParts > 0
                    ? String.format("recovered=%d, skipped=%d", m_cRecoveredParts, cSkippedParts)
                    : String.format("recovered=%d, failed=%d", m_cRecoveredParts, m_cFailedParts);

            return String.format("%s: store=%d ms, recovery=%d ms, %s, source=%s, active-store=%d bytes%n  store-summary: %s%s%n  recovery-summary: %s%s",
                    m_sLabel, m_cStoreMillis, m_cRecoveryMillis, sPartitionSummary,
                    m_sRecoverySource, m_cbActiveBytes,
                    m_sStorePhaseSummary == null ? "<none>" : m_sStorePhaseSummary,
                    sStoreDetails,
                    m_sRecoverySummary == null ? "<none>" : m_sRecoverySummary,
                    sRecoveryDetails);
            }

        private static String formatSummaryDetails(String sSummary)
            {
            String sAdaptiveSummary = formatAdaptiveSummary(sSummary);
            String sPhaseBreakdown = formatPhaseBreakdown(sSummary);
            String sOperationBreakdown = formatOperationBreakdown(sSummary);
            String sPhaseWallBreakdown = formatPhaseWallBreakdown(sSummary);

            return sAdaptiveSummary + sPhaseBreakdown + sOperationBreakdown + sPhaseWallBreakdown;
            }

        private static String formatAdaptiveSummary(String sSummary)
            {
            if (sSummary == null)
                {
                return "";
                }

            int ofAdaptive = sSummary.indexOf("adaptive=");
            if (ofAdaptive < 0)
                {
                return "";
                }

            int ofValue = ofAdaptive + "adaptive=".length();
            int ofEnd   = sSummary.indexOf(", store-open-detail=", ofValue);
            String sAdaptive = ofEnd < 0
                    ? sSummary.substring(ofValue).trim()
                    : sSummary.substring(ofValue, ofEnd).trim();

            if (sAdaptive.isEmpty() || "none".equals(sAdaptive))
                {
                return "";
                }

            return System.lineSeparator() + "  adaptive-checkpoint: " + sAdaptive;
            }

        private static String formatPhaseBreakdown(String sStoreSummary)
            {
            if (sStoreSummary == null)
                {
                return "";
                }

            int ofPhases = sStoreSummary.indexOf("phases=");
            if (ofPhases < 0)
                {
                return "";
                }

            String sPhases = extractBracketedValue(sStoreSummary, ofPhases + "phases=".length());
            if (sPhases == null || sPhases.isEmpty())
                {
                return "";
                }

            List<String> listPhases = splitPhaseBlocks(sPhases);
            if (listPhases.isEmpty())
                {
                return "";
                }

            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator()).append("  phase-breakdown:");

            for (String sPhase : listPhases)
                {
                int ofBracket = sPhase.indexOf('[');
                if (ofBracket < 0 || !sPhase.endsWith("]"))
                    {
                    continue;
                    }

                String sName = sPhase.substring(0, ofBracket);
                String sBody = sPhase.substring(ofBracket + 1, sPhase.length() - 1);

                sb.append(System.lineSeparator())
                        .append("    ")
                        .append(sName)
                        .append(": ")
                        .append(formatPhaseMetrics(sBody));
                }

            return sb.toString();
            }

        private static String formatOperationBreakdown(String sStoreSummary)
            {
            if (sStoreSummary == null)
                {
                return "";
                }

            int ofOperations = sStoreSummary.indexOf("operations=");
            if (ofOperations < 0)
                {
                return "";
                }

            String sOperations = sStoreSummary.substring(ofOperations + "operations=".length()).trim();
            if (sOperations.isEmpty() || "none".equals(sOperations))
                {
                return "";
                }

            List<String> listPhases = splitPhaseBlocks(sOperations);
            if (listPhases.isEmpty())
                {
                return "";
                }

            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator()).append("  operation-breakdown:");

            for (String sPhase : listPhases)
                {
                int ofBracket = sPhase.indexOf('[');
                if (ofBracket < 0 || !sPhase.endsWith("]"))
                    {
                    continue;
                    }

                String sName = sPhase.substring(0, ofBracket);
                String sBody = sPhase.substring(ofBracket + 1, sPhase.length() - 1);

                sb.append(System.lineSeparator())
                        .append("    ")
                        .append(sName)
                        .append(": ")
                        .append(sBody);
                }

            return sb.toString();
            }

        private static String formatPhaseWallBreakdown(String sStoreSummary)
            {
            if (sStoreSummary == null)
                {
                return "";
                }

            int ofPhaseWalls = sStoreSummary.indexOf("phase-walls=");
            if (ofPhaseWalls < 0)
                {
                return "";
                }

            String sPhaseWalls = sStoreSummary.substring(ofPhaseWalls + "phase-walls=".length()).trim();
            if (sPhaseWalls.isEmpty() || "none".equals(sPhaseWalls))
                {
                return "";
                }

            List<String> listPhases = splitPhaseBlocks(sPhaseWalls);
            if (listPhases.isEmpty())
                {
                return "";
                }

            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator()).append("  phase-wall-breakdown:");

            for (String sPhase : listPhases)
                {
                int ofBracket = sPhase.indexOf('[');
                if (ofBracket < 0 || !sPhase.endsWith("]"))
                    {
                    continue;
                    }

                String sName = sPhase.substring(0, ofBracket);
                String sBody = sPhase.substring(ofBracket + 1, sPhase.length() - 1);

                sb.append(System.lineSeparator())
                        .append("    ")
                        .append(sName)
                        .append(": ")
                        .append(sBody);
                }

            return sb.toString();
            }

        private static List<String> splitPhaseBlocks(String sPhases)
            {
            List<String> list = new ArrayList<>();
            int          cDepth = 0;
            int          ofStart = 0;

            for (int i = 0; i < sPhases.length(); i++)
                {
                char ch = sPhases.charAt(i);
                if (ch == '[')
                    {
                    cDepth++;
                    }
                else if (ch == ']')
                    {
                    cDepth--;
                    }
                else if (ch == ';' && cDepth == 0)
                    {
                    list.add(sPhases.substring(ofStart, i));
                    ofStart = i + 1;
                    }
                }

            if (ofStart < sPhases.length())
                {
                list.add(sPhases.substring(ofStart));
                }

            return list;
            }

        private static String formatPhaseMetrics(String sBody)
            {
            List<String> list = new ArrayList<>();

            appendMetric(list, sBody, "store-total-ms");
            appendMetric(list, sBody, "extent-recover-ms");
            appendMetric(list, sBody, "extent-checkpointed");
            appendMetric(list, sBody, "extent-scanned");
            appendMetric(list, sBody, "extent-applied");
            appendMetric(list, sBody, "env-open-ms");
            appendMetric(list, sBody, "list-databases-ms");
            appendMetric(list, sBody, "open-databases-ms");

            return list.isEmpty() ? sBody : String.join(", ", list);
            }

        private static String extractBracketedValue(String sText, int ofStart)
            {
            int cDepth = 0;

            for (int i = ofStart; i < sText.length(); i++)
                {
                char ch = sText.charAt(i);

                if (ch == '[')
                    {
                    cDepth++;
                    }
                else if (ch == ']')
                    {
                    if (cDepth == 0)
                        {
                        return sText.substring(ofStart, i).trim();
                        }
                    cDepth--;
                    }
                else if (ch == ',' && cDepth == 0)
                    {
                    return sText.substring(ofStart, i).trim();
                    }
                }

            return sText.substring(ofStart).trim();
            }

        private static void appendMetric(List<String> list, String sBody, String sMetric)
            {
            String sValue = extractMetricValue(sBody, sMetric);
            if (sValue != null)
                {
                list.add(sMetric + "=" + sValue);
                }
            }

        private static String extractMetricValue(String sBody, String sMetric)
            {
            String sPrefix = sMetric + "=";
            int    ofStart = sBody.indexOf(sPrefix);
            if (ofStart < 0)
                {
                return null;
                }

            ofStart += sPrefix.length();
            int ofEnd = sBody.indexOf(',', ofStart);
            if (ofEnd < 0)
                {
                ofEnd = sBody.length();
                }

            return sBody.substring(ofStart, ofEnd).trim();
            }

        private final long   m_cStoreMillis;
        private final String m_sLabel;
        private final long   m_cRecoveryMillis;
        private final int    m_cRecoveredParts;
        private final int    m_cFailedParts;
        private final String m_sRecoverySource;
        private final long   m_cbActiveBytes;
        private final String m_sStorePhaseSummary;
        private final String m_sRecoverySummary;
        }

    // ----- inner enum: RecoveryMode -------------------------------------

    private enum RecoveryMode
        {
        ACTIVE_RESTART(true, null),
        GRACEFUL_CHECKPOINT(true, null),
        FULL_REPLAY(false, Long.valueOf(0L));

        RecoveryMode(boolean fGracefulShutdown, Long cbCheckpointThreshold)
            {
            m_fGracefulShutdown   = fGracefulShutdown;
            m_cbCheckpointThreshold = cbCheckpointThreshold;
            }

        boolean isGracefulShutdown()
            {
            return m_fGracefulShutdown;
            }

        Long getJournalCheckpointThreshold()
            {
            return m_cbCheckpointThreshold;
            }

        private final boolean m_fGracefulShutdown;
        private final Long    m_cbCheckpointThreshold;
        }

    // ----- inner class: RecoveryMetrics ---------------------------------

    public static class RecoveryMetrics
            implements Serializable
        {
        public RecoveryMetrics(long cRecoveryMillis, int cRecoveredPartitions, int cFailedPartitions, String sSource)
            {
            m_cRecoveryMillis      = cRecoveryMillis;
            m_cRecoveredPartitions = cRecoveredPartitions;
            m_cFailedPartitions    = cFailedPartitions;
            m_sSource              = sSource;
            }

        private final long   m_cRecoveryMillis;
        private final int    m_cRecoveredPartitions;
        private final int    m_cFailedPartitions;
        private final String m_sSource;
        }

    // ----- inner class: MemberDirectories --------------------------------

    private static class MemberDirectories
        {
        private static MemberDirectories create()
                throws IOException
            {
            File dirRoot = FileHelper.createTempDir();

            return new MemberDirectories(dirRoot,
                    FileHelper.ensureDir(new File(dirRoot, "active")),
                    FileHelper.ensureDir(new File(dirRoot, "backup")),
                    FileHelper.ensureDir(new File(dirRoot, "snapshot")),
                    FileHelper.ensureDir(new File(dirRoot, "trash")));
            }

        private MemberDirectories(File dirRoot, File dirActive, File dirBackup, File dirSnapshot, File dirTrash)
            {
            m_dirRoot     = dirRoot;
            m_dirActive   = dirActive;
            m_dirBackup   = dirBackup;
            m_dirSnapshot = dirSnapshot;
            m_dirTrash    = dirTrash;
            }

        private void delete()
            {
            FileHelper.deleteDirSilent(m_dirRoot);
            }

        private final File m_dirRoot;
        private final File m_dirActive;
        private final File m_dirBackup;
        private final File m_dirSnapshot;
        private final File m_dirTrash;
        }

    // ----- inner class: PropertySnapshot ---------------------------------

    private static class PropertySnapshot
        {
        private void capture(String sName)
            {
            if (!m_mapValues.containsKey(sName))
                {
                m_mapValues.put(sName, System.getProperty(sName));
                }
            }

        private void restore()
            {
            for (Map.Entry<String, String> entry : m_mapValues.entrySet())
                {
                if (entry.getValue() == null)
                    {
                    System.clearProperty(entry.getKey());
                    }
                else
                    {
                    System.setProperty(entry.getKey(), entry.getValue());
                    }
                }
            }

        private final Map<String, String> m_mapValues = new HashMap<>();
        }

    // ----- inner class: JfrRecording --------------------------------------

    private static class JfrRecording
        {
        private static JfrRecording disabled()
            {
            return new JfrRecording(null, null);
            }

        private JfrRecording(Recording recording, Path path)
            {
            m_recording = recording;
            m_path      = path;
            }

        private void stop()
                throws IOException
            {
            if (m_recording != null && !m_fStopped)
                {
                m_fStopped = true;
                try
                    {
                    try
                        {
                        m_recording.stop();
                        }
                    catch (IllegalStateException ignored)
                        {
                        // The recording may have been stopped already during shutdown.
                        }

                    if (Files.exists(m_path))
                        {
                        System.out.printf("JFR written to %s%n", m_path.toAbsolutePath());
                        }
                    else
                        {
                        m_recording.dump(m_path);
                        System.out.printf("JFR written to %s%n", m_path.toAbsolutePath());
                        }
                    }
                finally
                    {
                    m_recording.close();
                    }
                }
            }

        private final Recording m_recording;
        private final Path      m_path;
        private boolean         m_fStopped;
        }

    // ----- constants ------------------------------------------------------

    private static final boolean INCLUDE_REPLAY      = Boolean.getBoolean("test.journalvsbdb.include.replay");
    private static final boolean JFR_ENABLED         = Boolean.getBoolean("test.journalvsbdb.jfr");
    private static final int     CACHE_COUNT         = Integer.getInteger("test.journalvsbdb.cache.count", 2);
    private static final int     PARTITION_COUNT     = Integer.getInteger("test.journalvsbdb.partition.count", 257);

    /**
     * Target per-batch payload size for default putAll/getAll operations.
     */
    private static final int DEFAULT_BATCH_BYTES = 10 * 1024 * 1024;

    private static final int     VALUE_SIZE_BYTES    = Integer.getInteger("test.journalvsbdb.value.size", 4 * 1024);
    private static final int     BATCH_SIZE          = Integer.getInteger("test.journalvsbdb.batch.size",
            Math.max(1, DEFAULT_BATCH_BYTES / Math.max(1, VALUE_SIZE_BYTES)));
    private static final int     ENTRY_COUNT_PER_CACHE = Integer.getInteger("test.journalvsbdb.entry.count", 2_000);
    private static final int     UPDATES_PER_KEY     = Integer.getInteger("test.journalvsbdb.updates.per.key", 1);
    private static final long    TOTAL_WRITES_PER_CACHE = (long) ENTRY_COUNT_PER_CACHE * UPDATES_PER_KEY;
    private static final Set<String> ENABLED_SCENARIOS = parseLabels(System.getProperty("test.journalvsbdb.scenarios", ""));
    private static final Set<String> JFR_SCENARIOS     = parseLabels(System.getProperty("test.journalvsbdb.jfr.scenarios", ""));

    private static Set<String> parseLabels(String sValue)
        {
        return Arrays.stream(sValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        }
    }
