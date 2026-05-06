/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.tangosol.persistence.journal.JournalPersistenceMBeanRegistry.DataSource;
import com.tangosol.persistence.journal.JournalPersistenceMBeanRegistry.ManagerRole;
import com.tangosol.persistence.journal.JournalPersistenceMBeanRegistry.MBeanRegistrar;

import com.tangosol.net.management.Registry;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link JournalPersistenceMBeanRegistry}.
 *
 * @author Aleks Seovic  2026.04.27
 * @since 26.04
 */
public class JournalPersistenceMBeanRegistryTest
    {
    // ----- test lifecycle -------------------------------------------------

    @Before
    public void resetSharedRegistry()
        {
        JournalPersistenceMBeanRegistry.resetSharedForTesting();
        JournalPersistenceMBeanRegistry.resetRegistrarCacheForTesting();
        }

    // ----- test methods ---------------------------------------------------

    @Test
    public void testNameBuilderProducesCanonicalCoordinate()
        {
        assertEquals("type=Persistence,subType=Journal,service=Foo,responsibility=PersistenceCoordinator",
                JournalPersistenceMBeanRegistry.getMBeanName("Foo"));
        }

    @Test
    public void testSharedRegistryIsIdempotent()
        {
        TestRegistrar                  registrarOne = new TestRegistrar();
        TestRegistrar                  registrarTwo = new TestRegistrar();
        JournalPersistenceMBeanRegistry registryOne  = JournalPersistenceMBeanRegistry.shared(registrarOne);

        assertSame(registryOne, JournalPersistenceMBeanRegistry.shared(registrarOne));
        assertSame(registryOne, JournalPersistenceMBeanRegistry.shared(registrarTwo));
        }

    @Test
    public void testRegistrarForReturnsSameInstanceForSameRegistry()
        {
        Registry       registryOne = newRegistryProxy();
        Registry       registryTwo = newRegistryProxy();
        MBeanRegistrar registrarOne = JournalPersistenceMBeanRegistry.registrarFor(registryOne);

        assertSame(registrarOne, JournalPersistenceMBeanRegistry.registrarFor(registryOne));
        assertTrue(registrarOne != JournalPersistenceMBeanRegistry.registrarFor(registryTwo));
        }

    @Test
    public void testSingleManagerAttachRegistersMBeanAndExposesAttributes()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);
        TestDataSource                  source    = new TestDataSource("recovered partitions=257", 100L, "idle", 3);

        registry.attach(SERVICE, ManagerRole.ACTIVE, source);

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals(1, registrar.getRegisterCount());
        assertEquals(0, registrar.getUnregisterCount());
        assertNotNull(mbean);
        assertEquals("[active] recovered partitions=257", mbean.getLastRecoverySummary());
        assertEquals("idle", mbean.getCompactionProgress());
        assertEquals(3, mbean.getOpenExtentCount());
        }

    @Test
    public void testDualManagerAttachAggregatesWithoutReregistering()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("active recovery", 100L, "active=10%", 2));
        registry.attach(SERVICE, ManagerRole.BACKUP, new TestDataSource("backup recovery", 200L, "backup=40%", 5));

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals(1, registrar.getRegisterCount());
        assertEquals(0, registrar.getUnregisterCount());
        assertEquals("[backup] backup recovery", mbean.getLastRecoverySummary());
        assertEquals("active{active=10%}, backup{backup=40%}", mbean.getCompactionProgress());
        assertEquals(7, mbean.getOpenExtentCount());
        }

    @Test
    public void testCompactionProgressMultiManagerFormat()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("active recovery", 100L, "active=25%", 2));

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals("active=25%", mbean.getCompactionProgress());

        registry.attach(SERVICE, ManagerRole.BACKUP, new TestDataSource("backup recovery", 200L, "backup=75%", 5));

        assertEquals("active{active=25%}, backup{backup=75%}", mbean.getCompactionProgress());
        }

    @Test
    public void testReleaseOutOfOrderDoesNotUnregisterEarly()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("active recovery", 100L, "active=10%", 2));
        registry.attach(SERVICE, ManagerRole.BACKUP, new TestDataSource("backup recovery", 200L, "backup=40%", 5));

        registry.detach(SERVICE, ManagerRole.ACTIVE);

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals(1, registrar.getRegisterCount());
        assertEquals(0, registrar.getUnregisterCount());
        assertNotNull(mbean);
        assertEquals("[backup] backup recovery", mbean.getLastRecoverySummary());
        assertEquals("backup=40%", mbean.getCompactionProgress());
        assertEquals(5, mbean.getOpenExtentCount());

        registry.detach(SERVICE, ManagerRole.BACKUP);

        assertEquals(1, registrar.getUnregisterCount());
        assertNull(registrar.getMBean(SERVICE));
        }

    @Test
    public void testReleaseInOrderWorksSymmetrically()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("active recovery", 100L, "active=10%", 2));
        registry.attach(SERVICE, ManagerRole.BACKUP, new TestDataSource("backup recovery", 200L, "backup=40%", 5));

        registry.detach(SERVICE, ManagerRole.BACKUP);

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals(1, registrar.getRegisterCount());
        assertEquals(0, registrar.getUnregisterCount());
        assertNotNull(mbean);
        assertEquals("[active] active recovery", mbean.getLastRecoverySummary());
        assertEquals("active=10%", mbean.getCompactionProgress());
        assertEquals(2, mbean.getOpenExtentCount());

        registry.detach(SERVICE, ManagerRole.ACTIVE);

        assertEquals(1, registrar.getUnregisterCount());
        assertNull(registrar.getMBean(SERVICE));
        }

    @Test
    public void testIdempotentAttachForSameServiceAndRole()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("initial recovery", 100L, "initial", 2));
        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("replacement recovery", 200L, "replacement", 7));

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals(1, registrar.getRegisterCount());
        assertNotNull(mbean);
        assertEquals("[active] initial recovery", mbean.getLastRecoverySummary());
        assertEquals("initial", mbean.getCompactionProgress());
        assertEquals(2, mbean.getOpenExtentCount());
        }

    @Test
    public void testAttributeReadsTolerateConcurrentAttachAndDetach()
            throws Exception
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("active recovery", 100L, "active", 2));
        registry.attach(SERVICE, ManagerRole.BACKUP, new TestDataSource("backup recovery", 200L, "backup", 5));

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertNotNull(mbean);

        CountDownLatch             latchStart = new CountDownLatch(1);
        AtomicReference<Throwable> error      = new AtomicReference<>();
        ExecutorService            executor   = Executors.newFixedThreadPool(2);

        executor.submit(() ->
            {
            await(latchStart);
            try
                {
                for (int i = 0; i < 1000 && error.get() == null; i++)
                    {
                    String sSummary    = mbean.getLastRecoverySummary();
                    String sCompaction = mbean.getCompactionProgress();
                    int    cOpen       = mbean.getOpenExtentCount();

                    assertNotNull(sSummary);
                    assertNotNull(sCompaction);
                    assertTrue(cOpen >= 0);
                    }
                }
            catch (Throwable e)
                {
                error.compareAndSet(null, e);
                }
            });

        executor.submit(() ->
            {
            await(latchStart);
            try
                {
                for (int i = 0; i < 1000 && error.get() == null; i++)
                    {
                    registry.detach(SERVICE, ManagerRole.BACKUP);
                    registry.attach(SERVICE, ManagerRole.BACKUP,
                            new TestDataSource("backup recovery " + i, 300L + i, "backup=" + i, i % 10));
                    }
                }
            catch (Throwable e)
                {
                error.compareAndSet(null, e);
                }
            });

        latchStart.countDown();
        executor.shutdown();

        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        if (error.get() != null)
            {
            fail(error.get().toString());
            }
        }

    @Test
    public void testTimestampOrderingBeatsAttachOrder()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);
        TestDataSource                  backup    = new TestDataSource("backup recovery", 200L, "backup=40%", 5);
        TestDataSource                  active    = new TestDataSource("active recovery", 100L, "active=10%", 2);

        registry.attach(SERVICE, ManagerRole.BACKUP, backup);
        registry.attach(SERVICE, ManagerRole.ACTIVE, active);

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals("[backup] backup recovery", mbean.getLastRecoverySummary());

        active.setRecovery("active recovery", 300L);

        assertEquals("[active] active recovery", mbean.getLastRecoverySummary());
        }

    @Test
    public void testEmptyRecoverySummaryIsSkipped()
        {
        TestRegistrar                  registrar = new TestRegistrar();
        JournalPersistenceMBeanRegistry registry  = new JournalPersistenceMBeanRegistry(registrar);

        registry.attach(SERVICE, ManagerRole.BACKUP, new TestDataSource(RecoverySummary.EMPTY, "backup=40%", 5));
        registry.attach(SERVICE, ManagerRole.ACTIVE, new TestDataSource("active recovery", 100L, "active=10%", 2));

        JournalPersistenceMBean mbean = registrar.getMBean(SERVICE);

        assertEquals("[active] active recovery", mbean.getLastRecoverySummary());
        }

    // ----- helper methods -------------------------------------------------

    private static void await(CountDownLatch latch)
        {
        try
            {
            latch.await();
            }
        catch (InterruptedException e)
            {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
            }
        }

    private static Registry newRegistryProxy()
        {
        return (Registry) Proxy.newProxyInstance(Registry.class.getClassLoader(), new Class<?>[] {Registry.class},
                (proxy, method, args) -> null);
        }

    // ----- inner class: TestRegistrar ------------------------------------

    private static class TestRegistrar
            implements JournalPersistenceMBeanRegistry.MBeanRegistrar
        {
        @Override
        public void register(String sName, Object mbean)
            {
            f_cRegister.incrementAndGet();
            f_mapMBeans.put(sName, (JournalPersistenceMBean) mbean);
            }

        @Override
        public void unregister(String sName)
            {
            f_cUnregister.incrementAndGet();
            f_mapMBeans.remove(sName);
            }

        public JournalPersistenceMBean getMBean(String sService)
            {
            return f_mapMBeans.get(JournalPersistenceMBeanRegistry.getMBeanName(sService));
            }

        public int getRegisterCount()
            {
            return f_cRegister.get();
            }

        public int getUnregisterCount()
            {
            return f_cUnregister.get();
            }

        private final Map<String, JournalPersistenceMBean> f_mapMBeans = new ConcurrentHashMap<>();

        private final AtomicInteger f_cRegister = new AtomicInteger();

        private final AtomicInteger f_cUnregister = new AtomicInteger();
        }

    // ----- inner class: TestDataSource -----------------------------------

    private static class TestDataSource
            implements DataSource
        {
        private TestDataSource(String sRecovery, long ldtRecovery, String sCompaction, int cOpen)
            {
            this(RecoverySummary.of(sRecovery, ldtRecovery, "test"), sCompaction, cOpen);
            }

        private TestDataSource(RecoverySummary summary, String sCompaction, int cOpen)
            {
            f_summary.set(summary);
            f_sCompaction = sCompaction;
            f_cOpen       = cOpen;
            }

        private void setRecovery(String sRecovery, long ldtRecovery)
            {
            f_summary.set(RecoverySummary.of(sRecovery, ldtRecovery, "test"));
            }

        @Override
        public int getOpenExtentCountSnapshot()
            {
            return f_cOpen;
            }

        @Override
        public String getCompactionProgressSummary()
            {
            return f_sCompaction;
            }

        @Override
        public RecoverySummary getLastRecoverySummary()
            {
            return f_summary.get();
            }

        private final AtomicReference<RecoverySummary> f_summary = new AtomicReference<>(RecoverySummary.EMPTY);

        private final String f_sCompaction;

        private final int f_cOpen;
        }

    // ----- constants ------------------------------------------------------

    private static final String SERVICE = "Foo";
    }
