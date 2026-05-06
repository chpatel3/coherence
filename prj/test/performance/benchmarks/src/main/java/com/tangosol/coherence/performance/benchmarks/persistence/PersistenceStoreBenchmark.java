/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.coherence.performance.benchmarks.persistence;

import com.oracle.coherence.persistence.PersistentStore;

import com.tangosol.io.ByteArrayReadBuffer;
import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;
import com.tangosol.io.journal2.PartitionJournalConfig;

import com.tangosol.persistence.AbstractPersistenceManager;
import com.tangosol.persistence.bdb.BerkeleyDBManager;
import com.tangosol.persistence.journal.JournalPersistenceManager;

import com.tangosol.util.Base;
import com.tangosol.util.Binary;
import com.tangosol.util.BitHelper;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;
import java.io.IOException;

import java.util.Random;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH benchmark for Coherence-centric persistence workloads.
 *
 * <p>The two workloads intentionally mirror the behaviors we care about:
 * <ul>
 *   <li>{@code store}: steady-state single-entry persisted mutations</li>
 *   <li>{@code load}: sequential entry reads from an already-populated store</li>
 *   <li>{@code randomLoad}: shuffled entry reads from an already-populated store</li>
 * </ul>
 *
 * <p>Payload sizes are:
 * <ul>
 *   <li>small: 256 bytes</li>
 *   <li>medium-small: 512 bytes</li>
 *   <li>medium: 2 KB</li>
 *   <li>large: 8 KB</li>
 * </ul>
 *
 * <p>Each size targets roughly the same resident data volume so the derived
 * MiB/s numbers remain easier to compare across sizes.
 *
 * @author Aleks Seovic  2026.04.03
 * @since 26.04
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Dcoherence.log.level=2"})
public class PersistenceStoreBenchmark
    {
    // ----- benchmark methods ---------------------------------------------

    @Benchmark
    public void store(StoreState state)
        {
        state.storeNext();
        }

    @Benchmark
    public Binary load(LoadState state)
        {
        return state.loadNext();
        }

    @Benchmark
    public Binary randomLoad(RandomLoadState state)
        {
        return state.loadNext();
        }

    // ----- inner class: store benchmark state ----------------------------

    @State(Scope.Benchmark)
    public static class StoreState
            extends AbstractBenchmarkState
        {
        @Setup(Level.Iteration)
        public void setupIteration()
            {
            openStore();
            m_nIndex = 0;
            }

        @TearDown(Level.Iteration)
        public void tearDownIteration()
            {
            deleteStore();
            }

        void storeNext()
            {
            PersistentStore<ReadBuffer> store = getStore();
            ReadBuffer                  key   = m_aKeys[m_nIndex];

            store.store(1L, key, m_binValue, null);
            m_nIndex = (m_nIndex + 1) % m_aKeys.length;
            }

        private int m_nIndex;
        }

    // ----- inner class: load benchmark state -----------------------------

    @State(Scope.Benchmark)
    public static class LoadState
            extends AbstractBenchmarkState
        {
        @Setup(Level.Iteration)
        public void setupIteration()
            {
            openStore();

            PersistentStore<ReadBuffer> store = getStore();
            for (ReadBuffer key : m_aKeys)
                {
                store.store(1L, key, m_binValue, null);
                }

            m_nIndex = 0;
            }

        @TearDown(Level.Iteration)
        public void tearDownIteration()
            {
            deleteStore();
            }

        Binary loadNext()
            {
            PersistentStore<ReadBuffer> store   = getStore();
            ReadBuffer                  key     = m_aKeys[m_nIndex];
            Binary                      binRead = (Binary) store.load(1L, key);

            m_nIndex = (m_nIndex + 1) % m_aKeys.length;
            return binRead;
            }

        private int m_nIndex;
        }

    // ----- inner class: random-load benchmark state ---------------------

    @State(Scope.Benchmark)
    public static class RandomLoadState
            extends AbstractBenchmarkState
        {
        @Setup(Level.Iteration)
        public void setupIteration()
            {
            openStore();

            PersistentStore<ReadBuffer> store = getStore();
            for (ReadBuffer key : m_aKeys)
                {
                store.store(1L, key, m_binValue, null);
                }

            m_aReadKeys = createRandomizedKeySequence(m_aKeys);
            m_nIndex    = 0;
            }

        @TearDown(Level.Iteration)
        public void tearDownIteration()
            {
            deleteStore();
            }

        Binary loadNext()
            {
            PersistentStore<ReadBuffer> store   = getStore();
            ReadBuffer                  key     = m_aReadKeys[m_nIndex];
            Binary                      binRead = (Binary) store.load(1L, key);

            m_nIndex = (m_nIndex + 1) % m_aReadKeys.length;
            return binRead;
            }

        private ReadBuffer[] m_aReadKeys;
        private int          m_nIndex;
        }

    // ----- inner class: shared benchmark state ---------------------------

    @State(Scope.Benchmark)
    public abstract static class AbstractBenchmarkState
        {
        @Param({"BDB", "JOURNAL"})
        public String implementation;

        @Param({"256", "512", "2048", "8192"})
        public int payloadBytes;

        @Setup(Level.Trial)
        public void setupTrial()
                throws IOException
            {
            m_dirData = FileHelper.createTempDir();
            m_manager = createManager(m_dirData);

            m_binValue = Base.getRandomBinary(payloadBytes, payloadBytes);
            m_aKeys    = createKeySequence(Math.max(1, TARGET_DATASET_BYTES / payloadBytes));
            }

        @TearDown(Level.Trial)
        public void tearDownTrial()
            {
            if (m_manager != null)
                {
                m_manager.release();
                }

            if (m_dirData != null)
                {
                try
                    {
                    FileHelper.deleteDir(m_dirData);
                    }
                catch (IOException e)
                    {
                    throw new RuntimeException("Failed to delete benchmark directory " + m_dirData, e);
                    }
                }
            }

        protected void openStore()
            {
            String sStoreId = implementation + "-store-" + m_cStoreIds.incrementAndGet();

            m_sStoreId = sStoreId;
            m_store    = m_manager.open(sStoreId, null);
            m_store.ensureExtent(1L);
            }

        protected void deleteStore()
            {
            if (m_sStoreId != null)
                {
                m_manager.delete(m_sStoreId, false);
                m_sStoreId = null;
                m_store    = null;
                }
            }

        protected PersistentStore<ReadBuffer> getStore()
            {
            return m_store;
            }

        private AbstractPersistenceManager<?> createManager(File dirData)
                throws IOException
            {
            if ("JOURNAL".equals(implementation))
                {
                JournalPersistenceManager manager = new JournalPersistenceManager(dirData, null, implementation);
                manager.setJournalConfig(new PartitionJournalConfig()
                        .setMaximumFileSize(1024 * 1024)
                        .setBufferedAppendThreshold(PartitionJournalConfig.DEFAULT_BUFFERED_APPEND_THRESHOLD)
                        .setWriteSynchronous(false));
                return manager;
                }

            return new BerkeleyDBManager(dirData, null, implementation);
            }

        private ReadBuffer[] createKeySequence(int cKeys)
            {
            ReadBuffer[] aKeys = new ReadBuffer[cKeys];
            for (int i = 0; i < cKeys; i++)
                {
                aKeys[i] = new ByteArrayReadBuffer(BitHelper.toBytes(i));
                }
            return aKeys;
            }

        protected ReadBuffer[] createRandomizedKeySequence(ReadBuffer[] aKeys)
            {
            ReadBuffer[] aRandomized = aKeys.clone();
            Random       random      = new Random(RANDOM_READ_SEED);

            for (int i = aRandomized.length - 1; i > 0; --i)
                {
                int nSwap   = random.nextInt(i + 1);
                ReadBuffer key = aRandomized[i];
                aRandomized[i] = aRandomized[nSwap];
                aRandomized[nSwap] = key;
                }

            return aRandomized;
            }

        protected File                       m_dirData;
        protected AbstractPersistenceManager<?> m_manager;
        protected PersistentStore<ReadBuffer>   m_store;
        protected String                        m_sStoreId;
        protected Binary                        m_binValue;
        protected ReadBuffer[]                  m_aKeys;

        private final AtomicLong m_cStoreIds = new AtomicLong();
        }

    // ----- constants ------------------------------------------------------

    /**
     * Target resident dataset size per benchmark size profile.
     */
    private static final int TARGET_DATASET_BYTES = 8 * 1024 * 1024;

    /**
     * Fixed seed used to generate shuffled read order for random loads.
     */
    private static final long RANDOM_READ_SEED = 12345L;
    }
