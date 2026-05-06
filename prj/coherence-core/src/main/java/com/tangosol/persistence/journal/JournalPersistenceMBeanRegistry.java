/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.oracle.coherence.common.base.Logger;

import com.tangosol.net.management.AnnotatedStandardMBean;
import com.tangosol.net.management.Registry;

import com.tangosol.persistence.PersistenceManagerMBean;

import com.tangosol.util.Base;

import javax.management.NotCompliantMBeanException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Dormant registry helper for the journal persistence management surface.
 * <p>
 * Production code does not construct this helper yet. Commit 1 of the
 * {@code journal-persistence-mbean-and-jfr-implementation-plan.md} plan keeps
 * the helper isolated so its name construction, attach/detach state machine,
 * and active/backup aggregation contract can be tested before any
 * {@link JournalPersistenceManager} lifecycle wiring is introduced.
 *
 * @author Aleks Seovic  2026.04.27
 * @since 26.04
 */
public class JournalPersistenceMBeanRegistry
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Create a new {@link JournalPersistenceMBeanRegistry}.
     *
     * @param registrar  the registrar used to register and unregister MBeans
     */
    public JournalPersistenceMBeanRegistry(MBeanRegistrar registrar)
        {
        f_registrar = Objects.requireNonNull(registrar, "registrar");
        }

    // ----- public API -----------------------------------------------------

    /**
     * Obtain the journal persistence MBean name for a given service.
     *
     * @param sService  the service name
     *
     * @return the journal persistence MBean name
     */
    public static String getMBeanName(String sService)
        {
        return Registry.PERSISTENCE_SNAPSHOT_TYPE
             + "," + Registry.KEY_SUBTYPE_TYPE + "Journal"
             + ",service=" + sService
             + "," + Registry.KEY_RESPONSIBILITY + PersistenceManagerMBean.PERSISTENCE_COORDINATOR;
        }

    /**
     * Return the process-wide journal persistence MBean registry.
     * <p>
     * The first registrar wins. Later calls with the same registrar return the
     * same registry. Later calls with a different registrar keep the original
     * registry and log a warning so attach/detach state cannot be split across
     * multiple registration backends.
     *
     * @param registrar  the registrar used by the shared registry
     *
     * @return the shared registry
     */
    public static JournalPersistenceMBeanRegistry shared(MBeanRegistrar registrar)
        {
        Objects.requireNonNull(registrar, "registrar");

        while (true)
            {
            JournalPersistenceMBeanRegistry registry = s_registryShared.get();
            if (registry != null)
                {
                if (registry.f_registrar != registrar)
                    {
                    Logger.warn("Ignoring journal persistence MBean registrar change; "
                            + "the first registrar remains active");
                    }
                return registry;
                }

            registry = new JournalPersistenceMBeanRegistry(registrar);
            if (s_registryShared.compareAndSet(null, registry))
                {
                return registry;
                }
            }
        }

    /**
     * Return an MBean registrar backed by a Coherence management registry.
     *
     * @param registry  the Coherence management registry
     *
     * @return the registrar, or {@code null} when no registry is available
     */
    public static MBeanRegistrar registrarFor(Registry registry)
        {
        if (registry == null)
            {
            return null;
            }

        synchronized (s_mapRegistrar)
            {
            RegistryMBeanRegistrar registrar = s_mapRegistrar.get(registry);
            if (registrar == null)
                {
                registrar = new RegistryMBeanRegistrar(registry);
                s_mapRegistrar.put(registry, registrar);
                }
            return registrar;
            }
        }

    /**
     * Attach a manager data source to the service-level journal persistence
     * MBean.
     * <p>
     * Attaching the same service/role pair more than once is intentionally a
     * no-op. The first successful attach for a service registers the MBean;
     * later attaches for a different role update the same registered MBean.
     *
     * @param sService  the service name
     * @param role      the journal manager role
     * @param source    the journal manager data source
     */
    public void attach(String sService, ManagerRole role, DataSource source)
        {
        Objects.requireNonNull(sService, "service");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(source, "source");

        while (true)
            {
            Snapshot                snapshotOld = f_snapshot.get();
            ServiceState            stateOld    = snapshotOld.get(sService);
            JournalPersistenceMBeanImpl mbean   = stateOld == null ? new JournalPersistenceMBeanImpl() : stateOld.getMBean();

            if (stateOld != null && stateOld.getSources().containsKey(role))
                {
                return;
                }

            Map<ManagerRole, AttachedSource> mapSources = stateOld == null
                    ? new EnumMap<>(ManagerRole.class)
                    : new EnumMap<>(stateOld.getSources());
            mapSources.put(role, new AttachedSource(role, source));

            ServiceState stateNew    = new ServiceState(mbean, immutableSources(mapSources));
            Snapshot     snapshotNew = snapshotOld.with(sService, stateNew);

            if (f_snapshot.compareAndSet(snapshotOld, snapshotNew))
                {
                mbean.setSources(stateNew.getSources());
                if (stateOld == null)
                    {
                    f_registrar.register(getMBeanName(sService), mbean);
                    }
                return;
                }
            }
        }

    /**
     * Detach a manager data source from the service-level journal persistence
     * MBean.
     * <p>
     * Detaching an unknown service/role pair is a no-op. The final detach for
     * a service unregisters the MBean.
     *
     * @param sService  the service name
     * @param role      the journal manager role
     */
    public void detach(String sService, ManagerRole role)
        {
        Objects.requireNonNull(sService, "service");
        Objects.requireNonNull(role, "role");

        while (true)
            {
            Snapshot     snapshotOld = f_snapshot.get();
            ServiceState stateOld    = snapshotOld.get(sService);

            if (stateOld == null || !stateOld.getSources().containsKey(role))
                {
                return;
                }

            Map<ManagerRole, AttachedSource> mapSources = new EnumMap<>(stateOld.getSources());
            mapSources.remove(role);

            boolean      fUnregister = mapSources.isEmpty();
            ServiceState stateNew    = fUnregister ? null : new ServiceState(stateOld.getMBean(), immutableSources(mapSources));
            Snapshot     snapshotNew = fUnregister
                    ? snapshotOld.without(sService)
                    : snapshotOld.with(sService, stateNew);

            if (f_snapshot.compareAndSet(snapshotOld, snapshotNew))
                {
                stateOld.getMBean().setSources(fUnregister ? Collections.emptyMap() : stateNew.getSources());
                if (fUnregister)
                    {
                    f_registrar.unregister(getMBeanName(sService));
                    }
                return;
                }
            }
        }

    // ----- helper methods -------------------------------------------------

    /**
     * Return an immutable role/source map.
     *
     * @param mapSources  the source map
     *
     * @return an immutable role/source map
     */
    private static Map<ManagerRole, AttachedSource> immutableSources(Map<ManagerRole, AttachedSource> mapSources)
        {
        return Collections.unmodifiableMap(new EnumMap<>(mapSources));
        }

    /**
     * Reset the shared registry for tests.
     */
    static void resetSharedForTesting()
        {
        s_registryShared.set(null);
        }

    /**
     * Reset the registry-to-registrar cache for tests.
     */
    static void resetRegistrarCacheForTesting()
        {
        synchronized (s_mapRegistrar)
            {
            s_mapRegistrar.clear();
            }
        }

    // ----- inner interface: MBeanRegistrar -------------------------------

    /**
     * Small registration abstraction used by the dormant helper.
     * <p>
     * Commit 4 will adapt this abstraction to the cluster management
     * registry. Keeping it local in this commit avoids coupling tests or the
     * dormant helper to a running cluster.
     */
    public interface MBeanRegistrar
        {
        /**
         * Register an MBean.
         *
         * @param sName  the MBean name
         * @param mbean  the MBean instance
         */
        public void register(String sName, Object mbean);

        /**
         * Unregister an MBean.
         *
         * @param sName  the MBean name
         */
        public void unregister(String sName);
        }

    // ----- inner class: RegistryMBeanRegistrar ---------------------------

    /**
     * {@link MBeanRegistrar} backed by the Coherence management registry.
     */
    private static class RegistryMBeanRegistrar
            implements MBeanRegistrar
        {
        // ----- constructors ---------------------------------------------

        /**
         * Create a registrar backed by the supplied registry.
         *
         * @param registry  the Coherence management registry
         */
        private RegistryMBeanRegistrar(Registry registry)
            {
            f_registry = registry;
            }

        // ----- MBeanRegistrar methods -----------------------------------

        @Override
        public void register(String sName, Object mbean)
            {
            try
                {
                f_registry.register(f_registry.ensureGlobalName(sName),
                        new AnnotatedStandardMBean((JournalPersistenceMBean) mbean, JournalPersistenceMBean.class));
                }
            catch (NotCompliantMBeanException e)
                {
                throw Base.ensureRuntimeException(e);
                }
            }

        @Override
        public void unregister(String sName)
            {
            f_registry.unregister(f_registry.ensureGlobalName(sName));
            }

        // ----- data members ---------------------------------------------

        /**
         * Coherence management registry.
         */
        private final Registry f_registry;
        }

    // ----- inner interface: DataSource -----------------------------------

    /**
     * Data supplied by one journal persistence manager.
     */
    public interface DataSource
        {
        /**
         * Return a snapshot of the current open extent count.
         *
         * @return a snapshot of the current open extent count
         */
        public int getOpenExtentCountSnapshot();

        /**
         * Return the current compaction progress summary.
         *
         * @return the current compaction progress summary
         */
        public String getCompactionProgressSummary();

        /**
         * Return the current last recovery summary.
         * <p>
         * Implementations should return the most recent local recovery
         * summary, or {@link RecoverySummary#EMPTY} when no recovery has been
         * recorded. A {@code null} return value is treated as empty.
         *
         * @return the current last recovery summary
         */
        public RecoverySummary getLastRecoverySummary();
        }

    // ----- enum: ManagerRole ---------------------------------------------

    /**
     * Journal persistence manager role.
     */
    public enum ManagerRole
        {
        ACTIVE,
        BACKUP;

        /**
         * Return the role label used in operator-facing summaries.
         *
         * @return the role label
         */
        public String getLabel()
            {
            return name().toLowerCase();
            }
        }

    // ----- inner class: JournalPersistenceMBeanImpl ----------------------

    /**
     * Aggregate journal persistence MBean implementation.
     */
    private static class JournalPersistenceMBeanImpl
            implements JournalPersistenceMBean
        {
        // ----- JournalPersistenceMBean methods ---------------------------

        @Override
        public String getLastRecoverySummary()
            {
            AttachedSource sourceMostRecent = null;
            RecoverySummary summaryMostRecent = RecoverySummary.EMPTY;

            for (AttachedSource source : m_mapSources.values())
                {
                RecoverySummary summary = normalize(source.getDataSource().getLastRecoverySummary());
                if (!summary.getText().isEmpty()
                        && summary.getTimestampMillis() > 0L
                        && summary.getTimestampMillis() > summaryMostRecent.getTimestampMillis())
                    {
                    sourceMostRecent  = source;
                    summaryMostRecent = summary;
                    }
                }

            return sourceMostRecent == null
                    ? ""
                    : "[" + sourceMostRecent.getRole().getLabel() + "] "
                            + summaryMostRecent.getText();
            }

        @Override
        public String getCompactionProgress()
            {
            Map<ManagerRole, AttachedSource> mapSources = m_mapSources;

            if (mapSources.isEmpty())
                {
                return "";
                }

            if (mapSources.size() == 1)
                {
                return safeString(mapSources.values().iterator().next().getDataSource().getCompactionProgressSummary());
                }

            StringBuilder builder = new StringBuilder();
            for (ManagerRole role : ManagerRole.values())
                {
                AttachedSource source = mapSources.get(role);
                if (source != null)
                    {
                    if (builder.length() > 0)
                        {
                        builder.append(", ");
                        }
                    builder.append(role.getLabel())
                           .append('{')
                           .append(safeString(source.getDataSource().getCompactionProgressSummary()))
                           .append('}');
                    }
                }
            return builder.toString();
            }

        @Override
        public int getOpenExtentCount()
            {
            int cOpen = 0;
            for (AttachedSource source : m_mapSources.values())
                {
                cOpen += Math.max(0, source.getDataSource().getOpenExtentCountSnapshot());
                }
            return cOpen;
            }

        // ----- accessors -------------------------------------------------

        /**
         * Set the immutable source snapshot used by this MBean.
         *
         * @param mapSources  the source snapshot
         */
        private void setSources(Map<ManagerRole, AttachedSource> mapSources)
            {
            m_mapSources = mapSources;
            }

        // ----- helper methods -------------------------------------------

        /**
         * Return a non-null string.
         *
         * @param sValue  the value
         *
         * @return a non-null string
         */
        private static String safeString(String sValue)
            {
            return sValue == null ? "" : sValue;
            }

        /**
         * Return a non-null recovery summary.
         *
         * @param summary  the recovery summary
         *
         * @return a non-null recovery summary
         */
        private static RecoverySummary normalize(RecoverySummary summary)
            {
            return summary == null ? RecoverySummary.EMPTY : summary;
            }

        // ----- data members ---------------------------------------------

        /**
         * Immutable attached-source snapshot.
         */
        private volatile Map<ManagerRole, AttachedSource> m_mapSources = Collections.emptyMap();
        }

    // ----- inner class: Snapshot -----------------------------------------

    /**
     * Immutable service-state snapshot.
     */
    private static class Snapshot
        {
        // ----- constructors ---------------------------------------------

        /**
         * Create a new {@link Snapshot}.
         *
         * @param mapServices  service states by service name
         */
        private Snapshot(Map<String, ServiceState> mapServices)
            {
            f_mapServices = mapServices;
            }

        // ----- accessors -------------------------------------------------

        /**
         * Return the state for a service.
         *
         * @param sService  the service name
         *
         * @return the service state, or {@code null}
         */
        private ServiceState get(String sService)
            {
            return f_mapServices.get(sService);
            }

        /**
         * Return a snapshot with the given service state.
         *
         * @param sService  the service name
         * @param state     the service state
         *
         * @return the new snapshot
         */
        private Snapshot with(String sService, ServiceState state)
            {
            Map<String, ServiceState> mapServices = new LinkedHashMap<>(f_mapServices);
            mapServices.put(sService, state);
            return new Snapshot(Collections.unmodifiableMap(mapServices));
            }

        /**
         * Return a snapshot without the given service.
         *
         * @param sService  the service name
         *
         * @return the new snapshot
         */
        private Snapshot without(String sService)
            {
            Map<String, ServiceState> mapServices = new LinkedHashMap<>(f_mapServices);
            mapServices.remove(sService);
            return new Snapshot(Collections.unmodifiableMap(mapServices));
            }

        // ----- constants -------------------------------------------------

        /**
         * Empty snapshot.
         */
        private static final Snapshot EMPTY = new Snapshot(Collections.emptyMap());

        // ----- data members ---------------------------------------------

        /**
         * Immutable service-state map.
         */
        private final Map<String, ServiceState> f_mapServices;
        }

    // ----- inner class: ServiceState -------------------------------------

    /**
     * Immutable service state.
     */
    private static class ServiceState
        {
        // ----- constructors ---------------------------------------------

        /**
         * Create a new {@link ServiceState}.
         *
         * @param mbean       the service-level MBean
         * @param mapSources  the attached source snapshot
         */
        private ServiceState(JournalPersistenceMBeanImpl mbean, Map<ManagerRole, AttachedSource> mapSources)
            {
            f_mbean      = mbean;
            f_mapSources = mapSources;
            }

        // ----- accessors -------------------------------------------------

        /**
         * Return the service-level MBean.
         *
         * @return the service-level MBean
         */
        private JournalPersistenceMBeanImpl getMBean()
            {
            return f_mbean;
            }

        /**
         * Return the attached source snapshot.
         *
         * @return the attached source snapshot
         */
        private Map<ManagerRole, AttachedSource> getSources()
            {
            return f_mapSources;
            }

        // ----- data members ---------------------------------------------

        /**
         * Service-level MBean.
         */
        private final JournalPersistenceMBeanImpl f_mbean;

        /**
         * Immutable attached source snapshot.
         */
        private final Map<ManagerRole, AttachedSource> f_mapSources;
        }

    // ----- inner class: AttachedSource -----------------------------------

    /**
     * Attached manager data source.
     */
    private static class AttachedSource
        {
        // ----- constructors ---------------------------------------------

        /**
         * Create a new {@link AttachedSource}.
         *
         * @param role    the manager role
         * @param source  the source
         */
        private AttachedSource(ManagerRole role, DataSource source)
            {
            f_role   = role;
            f_source = source;
            }

        // ----- accessors -------------------------------------------------

        /**
         * Return the manager role.
         *
         * @return the manager role
         */
        private ManagerRole getRole()
            {
            return f_role;
            }

        /**
         * Return the source.
         *
         * @return the source
         */
        private DataSource getDataSource()
            {
            return f_source;
            }

        // ----- data members ---------------------------------------------

        /**
         * Manager role.
         */
        private final ManagerRole f_role;

        /**
         * Source.
         */
        private final DataSource f_source;

        }

    // ----- data members ---------------------------------------------------

    /**
     * MBean registrar.
     */
    private final MBeanRegistrar f_registrar;

    /**
     * Atomic service-state snapshot.
     */
    private final AtomicReference<Snapshot> f_snapshot = new AtomicReference<>(Snapshot.EMPTY);

    /**
     * Shared process-wide registry.
     */
    private static final AtomicReference<JournalPersistenceMBeanRegistry> s_registryShared = new AtomicReference<>();

    /**
     * Registry-backed registrar cache keyed by Registry object identity.
     */
    private static final Map<Registry, RegistryMBeanRegistrar> s_mapRegistrar = new IdentityHashMap<>();

    }
