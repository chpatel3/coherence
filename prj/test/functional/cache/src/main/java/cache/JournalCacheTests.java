/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package cache;

import com.oracle.bedrock.runtime.Application;
import com.oracle.bedrock.runtime.LocalPlatform;
import com.oracle.bedrock.runtime.options.Arguments;
import com.oracle.bedrock.testsupport.MavenProjectFileUtils;
import com.oracle.coherence.common.util.MemorySize;

import com.oracle.coherence.testing.TestHelper;
import com.oracle.coherence.testing.junit.ThreadDumpOnTimeoutRule;
import com.tangosol.io.Serializer;

import com.oracle.bedrock.testsupport.deferred.Eventually;
import com.tangosol.io.journal.Journal;
import com.tangosol.io.journal.JournalBinaryStore;
import com.tangosol.io.pof.ConfigurablePofContext;

import com.tangosol.net.CacheFactory;
import com.tangosol.net.NamedCache;
import com.tangosol.net.cache.CacheMap;
import com.tangosol.net.cache.CompactSerializationCache;
import com.tangosol.net.cache.ConfigurableCacheMap;
import com.tangosol.net.cache.LocalCache;

import com.tangosol.net.partition.ObservableSplittingBackingCache;
import com.tangosol.net.partition.PartitionAwareBackingMap;

import com.tangosol.util.Binary;
import com.tangosol.util.ExternalizableHelper;
import com.tangosol.util.MapEvent;

import com.oracle.coherence.testing.AbstractFunctionalTest;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.oracle.coherence.testing.TestMapListener;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import com.oracle.coherence.testing.util.CatchConcurrentExceptionsRule;

import static org.hamcrest.CoreMatchers.nullValue;

import static org.junit.Assert.*;

/**
 * Test harness for testing caches using the flashjournal and ramjournal schemes
 *
 * @author cf 2011-01-10
 */
public class JournalCacheTests
        extends AbstractFunctionalTest
    {
    /**
     * Default constructor.
     */
    public JournalCacheTests()
        {
        super(FILE_CFG_CACHE);
        }


    // ----- test lifecycle -------------------------------------------------

    /**
     * Initialize the test class.
     */
    @BeforeClass
    public static void _startup()
        {
        // this test requires local storage to be enabled
        System.setProperty("coherence.distributed.localstorage", "true");

        AbstractFunctionalTest._startup();
        }

    @Before
    public void beforeEach()
        {
        resetCacheConfig();
        }

    // ----- test methods ---------------------------------------------------

    /**
     * Tests an instantiation of a RamJournal based cache.
     */
    @Test
    public void testRamJournalCache()
        {
        NamedCache<String, String> testCache = CacheFactory.getCache("distram-test");
        for (int i = 0; i < 100000; i++)
            {
            String testKey = "test" + i % 1000;
            String testVal = "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890";
            testCache.put(testKey, testVal);

            String retrievedVal = testCache.get(testKey);
            assertEquals(retrievedVal, testVal);
            }
        }

    /**
     * Tests an instantiation of a RamJournal based cache.
     */
    @Test
    public void testFlashJournalCache()
        {
        NamedCache<String, String> testCache = CacheFactory.getCache("distflash-test");
        for (int i = 0; i < 100000; i++)
            {
            String testKey = "test" + i % 1000;
            String testVal = "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890"
                + "123456789012345678901234567890123456789012345678901234567890";
            testCache.put(testKey, testVal);

            String retrievedVal = testCache.get(testKey);
            assertEquals(retrievedVal, testVal);
            }
        }

    /**
     * Ensure that both RamJournal and FlashJournal backing maps use a
     * FlashJournal by default for a backup map.
     */
    @Test
    public void COH_7138_testRamJournalBackupStorage()
        {
        validateJournalBackup("dist-default-flash-backup");
        }

    /**
     * Ensure that both RamJournal and FlashJournal backing maps use a
     * FlashJournal by default for a backup map.
     */
    @Test
    public void COH_7138_testFlashJournalBackupStorage()
        {
        validateJournalBackup("dist-default-flash-backup");
        }

    @Test
    public void testLFUEvictionBinary()
            throws Exception
        {
        doTestEviction("lfu", "BINARY", "100M", "80M");
        }

    @Test
    public void testLFUEvictionFixed()
            throws Exception
        {
        doTestEviction("lfu", "FIXED",  "100000", "80000");
        }

    @Test
    public void testLRUEvictionBinary()
            throws Exception
        {
        doTestEviction("lru", "BINARY", "100M", "80M");
        }

    @Test
    public void testLRUEvictionFixed()
            throws Exception
        {
        doTestEviction("lru", "FIXED",  "100000", "80000");
        }

    @Test
    public void testHybridEvictionBinary()
            throws Exception
        {
        doTestEviction("hybrid", "BINARY", "100M", "80M");
        }

    @Test
    public void testHybridEvictionFixed()
            throws Exception
        {
        doTestEviction("hybrid", "FIXED",  "100000", "80000");
        }

    public void doTestEviction(String sPolicy, String sCalculator, String sHighUnits, String sLowUnits)
            throws Exception
        {
        final int cValSize    = 1024;
        int cEntryUnits = 1;

        // calculate the EntrySize
        if (sCalculator.equals("BINARY"))
            {
            Serializer serializer = new ConfigurablePofContext("pof-config.xml");
            Binary     binKey     = ExternalizableHelper.toBinary(0L, serializer);
            Binary     binValue   = ExternalizableHelper.toBinary(new byte[cValSize], serializer);

            binKey = ExternalizableHelper.decorateBinary(binKey, 257).toBinary();

            cEntryUnits = LocalCache.INSTANCE_BINARY.calculateUnits(binKey, binValue);
            }

        int cExpectedSizeHigh = (int) (new MemorySize(sHighUnits).getByteCount() / cEntryUnits);
        int cExpectedSizeLow  = (int) (new MemorySize(sLowUnits).getByteCount() / cEntryUnits);

        // compensate for the OSBC splitting calculation
        cExpectedSizeHigh = (cExpectedSizeHigh / 257) * 257;
        cExpectedSizeLow  = (cExpectedSizeLow / 257) * 257;

        final int cThreads       = 8;
        final int cKeysPerThread = (cExpectedSizeHigh * 2 + cThreads - 1) / cThreads;

        resetCacheConfig();

        System.setProperty("test.eviction.policy", sPolicy);
        System.setProperty("test.highunits", sHighUnits);
        System.setProperty("test.lowunits", sLowUnits);
        System.setProperty("test.unitcalculator", sCalculator);
        System.setProperty("test.unitfactor", "1");
        System.setProperty("test.expirydelay",  "0");

        final NamedCache<Long, byte[]> cache = getNamedCache("dist-with-expiry-eviction");

        try
            {
            Thread[] aThreads = new Thread[cThreads];

            for (int i = 0; i < cThreads; i++)
                {
                aThreads[i] = new Thread()
                    {
                    public void run()
                        {
                        Random random = new Random();
                        for (int i = 0; i < cKeysPerThread; i++)
                            {
                            long l = random.nextLong();
                            cache.put(l, new byte[1024]);
                            }
                        }
                    };
                }

            for (int i = 0; i < cThreads; i++)
                {
                aThreads[i].start();
                }

            for (int i = 0; i < cThreads; i++)
                {
                aThreads[i].join();
                }

            int cSize = cache.size();
            assertTrue("Cache Size of " + cSize + " is lower than low-units " + cExpectedSizeLow,
                       cache.size() >= cExpectedSizeLow);
            assertTrue("Cache Size of " + cSize + " is higher than high-units " + cExpectedSizeHigh,
                       cache.size() <= cExpectedSizeHigh);
            }
        finally
            {
            releaseNamedCache(cache);
            }
        }

    @Test
    public void testExpiry()
        {
        resetCacheConfig();

        System.setProperty("test.expirydelay",  "0");

        NamedCache<Integer, String> cache = getNamedCache("dist-with-expiry-eviction");
        cache.put(1, "foo");
        cache.put(2, "bar");
        cache.put(3, "baz", 1000L);
        cache.put(4, "biz", 1500L);
        cache.put(5, "boz", 2500L);

        sleep(3000L);

        assertTrue(cache.containsKey(1));
        assertTrue(cache.containsKey(2));
        assertFalse(cache.containsKey(3));
        assertFalse(cache.containsKey(4));
        assertFalse(cache.containsKey(5));

        cache.put(1, "foo");
        cache.put(2, "bar");
        cache.put(3, "baz", 1000L);
        cache.put(4, "biz", 1500L);
        cache.put(5, "boz", 2500L);

        sleep(3000L);

        assertEquals("foo", cache.get(1));
        assertEquals("bar", cache.get(2));
        assertNull(cache.get(3));
        assertNull(cache.get(4));
        assertNull(cache.get(5));

        cache.put(1, "foo");
        cache.put(2, "bar");
        cache.put(3, "baz", 1000L);
        cache.put(4, "biz", 1500L);
        cache.put(5, "boz", 2500L);

        sleep(3000L);

        assertEquals(2, cache.size());

        cache.put(1, "foo", 2000L);
        cache.put(1, "bar", CacheMap.EXPIRY_NEVER);

        sleep(3000L);

        assertEquals(2, cache.size());
        assertEquals("bar", cache.get(1));
        assertEquals("bar", cache.get(2));
        }

    @Test
    public void testDefaultExpiry()
        {
        resetCacheConfig();

        System.setProperty("test.expirydelay",  "1000ms");

        NamedCache<Integer, String> cache = getNamedCache("dist-with-expiry-eviction");
        cache.put(1, "foo", CacheMap.EXPIRY_NEVER);
        cache.put(2, "bar", 1000000L);
        cache.put(3, "baz", 2000L);
        cache.put(4, "biz");
        cache.put(5, "boz");

        sleep(3000L);

        assertTrue(cache.containsKey(1));
        assertTrue(cache.containsKey(2));
        assertFalse(cache.containsKey(3));
        assertFalse(cache.containsKey(4));
        assertFalse(cache.containsKey(5));

        cache.put(1, "foo", CacheMap.EXPIRY_NEVER);
        cache.put(2, "bar", 1000000L);
        cache.put(3, "baz", 2000L);
        cache.put(4, "biz");
        cache.put(5, "boz");

        sleep(3000L);

        assertEquals("foo", cache.get(1));
        assertEquals("bar", cache.get(2));
        assertNull(cache.get(3));
        assertNull(cache.get(4));
        assertNull(cache.get(5));

        cache.put(1, "foo", CacheMap.EXPIRY_NEVER);
        cache.put(2, "bar", 1000000L);
        cache.put(3, "baz", 2000L);
        cache.put(4, "biz");
        cache.put(5, "boz");

        sleep(3000L);

        assertEquals(2, cache.size());
        cache.put(1, "foo", 2000L);
        cache.put(1, "bar", CacheMap.EXPIRY_NEVER);

        sleep(3000L);

        assertEquals(2, cache.size());
        assertEquals("bar", cache.get(1));
        assertEquals("bar", cache.get(2));
        }

    /**
     * Test if getUnits returns the correct value.
     */
    @Test
    public void testGetUnits()
        {
        NamedCache<Integer, Integer> cache   = getNamedCache("distram-ccm");
        Map<?, ?>                    mapBack = TestHelper.getBackingMap(cache);

        assertTrue(mapBack instanceof ObservableSplittingBackingCache);

        for (int i = 0; i < 20000; i++)
            {
            cache.put(i,i);
            }
        ConfigurableCacheMap mapCCM = (ConfigurableCacheMap) mapBack;
        assertEquals(mapCCM.getUnits(), 20000);
        }

    /**
     * Test the behavior of {@link com.tangosol.net.NamedCache#truncate()}.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testTruncate()
        {
        System.setProperty("test.expirydelay",  "1000ms");

        NamedCache<String, String> cache = getNamedCache("dist-with-expiry-eviction");
        TestMapListener listener = new TestMapListener();
        cache.addMapListener(listener);

        assertTrue(cache.isActive());
        cache.put("key", "value");
        assertEquals("value", cache.get("key"));

        MapEvent<String, String> evt = listener.waitForEvent();
        assertNotNull("Missing event ", evt);

        cache.truncate();

        Eventually.assertDeferred(() -> cache.get("key"), nullValue());

        // cache should still be active
        assertTrue(cache.isActive());

        // the listener should still be registered with the cache
        cache.put("key1", "value1");

        evt = listener.waitForEvent();
        assertNotNull("Missing event", evt);

        cache.truncate();

        Eventually.assertDeferred(() -> cache.get("key1"), nullValue());

        assertTrue(cache.isActive());
        }

    /**
     * Ensure that the cache backup map uses a flash journal.
     *
     * @param sCacheName  the cache name
     */
    @SuppressWarnings("SameParameterValue")
    protected void validateJournalBackup(String sCacheName)
        {
        NamedCache<?, ?>         cache     = getNamedCache(sCacheName);
        Map<?, ?>                backupMap = CacheTestHelper.getBackupMap(cache);
        PartitionAwareBackingMap pabm     = (PartitionAwareBackingMap) backupMap;

        pabm.createPartition(0);

        JournalBinaryStore store   = (JournalBinaryStore) ((CompactSerializationCache) pabm.getPartitionMap(0)).getBinaryStore();
        Journal            journal = store.getJournal();
        long[]             alArray = new long[4000];

        Arrays.fill(alArray, 1L);
        long lTicket = journal.write(ExternalizableHelper.toBinary(alArray));

        // ensures that ticket is for a flash journal.
        long MASK_COMPACT_FLAG = 1L << 63;
        assertEquals(0L, (lTicket & MASK_COMPACT_FLAG));
        }

    /**
     * Dirty hack to force a cache-config reload
     */
    protected void resetCacheConfig()
        {
        CacheFactory.shutdown();
        setFactory(null);
        }

    /**
     * A JUnit rule that will cause the test to fail if it runs too long.
     * A thread dump will be generated on failure.
     */
    @ClassRule
    public static final ThreadDumpOnTimeoutRule timeout
            = ThreadDumpOnTimeoutRule.after(15, TimeUnit.MINUTES, true);

    @Rule
    public CatchConcurrentExceptionsRule m_catchRule = new CatchConcurrentExceptionsRule();

    /**
     * The file name of the default cache configuration file used by this test.
     */
    private static final String FILE_CFG_CACHE = "journal-cache-config.xml";
    }
