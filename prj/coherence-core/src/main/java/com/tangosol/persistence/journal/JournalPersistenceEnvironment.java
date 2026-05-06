/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

import com.oracle.coherence.common.base.Logger;

import com.oracle.coherence.persistence.PersistenceException;
import com.oracle.coherence.persistence.PersistenceManager;
import com.oracle.coherence.persistence.PersistentStore;
import com.oracle.coherence.persistence.PersistentStoreInfo;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;

import com.tangosol.io.journal2.PartitionJournalConfig;

import com.tangosol.persistence.AbstractPersistenceEnvironment;
import com.tangosol.persistence.AbstractPersistenceManager;
import com.tangosol.persistence.CachePersistenceHelper;
import com.tangosol.persistence.bdb.BerkeleyDBManager;
import com.tangosol.persistence.journal.JournalPersistenceMBeanRegistry.ManagerRole;
import com.tangosol.persistence.journal.JournalPersistenceMBeanRegistry.MBeanRegistrar;

import com.tangosol.net.management.Registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.Properties;

/**
 * PersistenceEnvironment implementation that uses {@link JournalPersistenceManager}.
 *
 * @author rl  2026.03.07
 * @since 26.04
 */
public class JournalPersistenceEnvironment
        extends AbstractPersistenceEnvironment
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Construct a journal persistence environment.
     *
     * @param fileActive    active directory
     * @param fileSnapshot  snapshot directory
     * @param fileTrash     optional trash directory
     *
     * @throws IOException on I/O failure
     */
    public JournalPersistenceEnvironment(File fileActive, File fileSnapshot, File fileTrash)
            throws IOException
        {
        this(fileActive, fileSnapshot, fileTrash, null);
        }

    /**
     * Construct a journal persistence environment.
     *
     * @param fileActive    active directory
     * @param fileSnapshot  snapshot directory
     * @param fileTrash     optional trash directory
     * @param fileEvents    optional events directory
     *
     * @throws IOException on I/O failure
     */
    public JournalPersistenceEnvironment(File fileActive, File fileSnapshot, File fileTrash, File fileEvents)
            throws IOException
        {
        this(fileActive, null, fileEvents, fileSnapshot, fileTrash);
        }

    /**
     * Construct a journal persistence environment.
     *
     * @param fileActive    active directory
     * @param fileBackup    optional backup directory
     * @param fileEvents    optional events directory
     * @param fileSnapshot  snapshot directory
     * @param fileTrash     optional trash directory
     *
     * @throws IOException on I/O failure
     */
    public JournalPersistenceEnvironment(File fileActive, File fileBackup, File fileEvents, File fileSnapshot,
                                         File fileTrash)
            throws IOException
        {
        super(fileActive, fileBackup, fileEvents, fileSnapshot, fileTrash);
        }


    // ----- accessors ------------------------------------------------------

    /**
     * Set journal configuration applied to new managers.
     *
     * @param config  journal configuration
     *
     * @return this environment
     */
    public JournalPersistenceEnvironment setJournalConfig(PartitionJournalConfig config)
        {
        if (config == null)
            {
            throw new IllegalArgumentException("journal config cannot be null");
            }

        m_journalConfig = config;
        return this;
        }

    /**
     * Return the optional migration source directory.
     *
     * @return the optional migration source directory
     */
    public File getMigrationSourceDirectory()
        {
        return m_fileMigrationSource;
        }

    /**
     * Set the optional migration source directory.
     *
     * @param fileMigrationSource  the optional migration source directory
     *
     * @return this environment
     */
    public JournalPersistenceEnvironment setMigrationSourceDirectory(File fileMigrationSource)
        {
        m_fileMigrationSource = fileMigrationSource;
        return this;
        }

    /**
     * Set the optional service-level MBean context used by active and backup
     * managers opened by this environment.
     *
     * @param sService   service name; {@code null} or empty disables MBean wiring
     * @param registry   Coherence management registry
     *
     * @return this environment
     */
    public JournalPersistenceEnvironment setMBeanRegistryContext(String sService, Registry registry)
        {
        MBeanRegistrar registrar = JournalPersistenceMBeanRegistry.registrarFor(registry);
        if (sService == null || sService.isEmpty() || registrar == null)
            {
            m_sMBeanService  = null;
            m_registrarMBean = null;
            }
        else
            {
            m_sMBeanService  = sService;
            m_registrarMBean = registrar;
            }
        return this;
        }


    // ----- AbstractPersistenceEnvironment methods -------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractPersistenceManager openActiveInternal()
        {
        File fileActive = getPersistenceActiveDirectory();

        prepareActiveDirectory(fileActive);

        JournalPersistenceManager manager = instantiateManager(fileActive, getPersistenceTrashDirectory(), null);
        configureMBeanContext(manager, ManagerRole.ACTIVE);
        migrateFromSnapshotIfNeeded(manager);
        return manager;
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractPersistenceManager openBackupInternal()
        {
        JournalPersistenceManager manager = instantiateManager(getPersistenceBackupDirectory(), getPersistenceTrashDirectory(), null);
        configureMBeanContext(manager, ManagerRole.BACKUP);
        return manager;
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractPersistenceManager openEventsInternal()
        {
        return instantiateManager(getPersistenceEventsDirectory(), getPersistenceTrashDirectory(), null);
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractPersistenceManager openSnapshotInternal(File fileSnapshot, String sSnapshot)
        {
        return instantiateManager(fileSnapshot, null, sSnapshot);
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractPersistenceManager createSnapshotInternal(File fileSnapshot, String sSnapshot,
            PersistenceManager<ReadBuffer> manager)
        {
        if (manager != null && !(manager instanceof JournalPersistenceManager))
            {
            throw new IllegalArgumentException("incompatible persistence manager type: " + manager.getClass());
            }

        JournalPersistenceManager snapshot = (JournalPersistenceManager) openSnapshotInternal(fileSnapshot, sSnapshot);
        try
            {
            if (manager instanceof JournalPersistenceManager)
                {
                ((JournalPersistenceManager) manager).createSnapshot(fileSnapshot);
                }
            writeSparseSnapshotMetadata(fileSnapshot);
            }
        catch (IOException | PersistenceException e)
            {
            snapshot.release();
            throw e instanceof PersistenceException
                    ? (PersistenceException) e
                    : ensurePersistenceException(e, "Unable to write journal snapshot metadata for " + sSnapshot);
            }

        return snapshot;
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Instantiate a journal persistence manager.
     *
     * @param fileData   manager data directory
     * @param fileTrash  optional trash directory
     * @param sName      optional manager name
     *
     * @return journal persistence manager
     */
    private JournalPersistenceManager instantiateManager(File fileData, File fileTrash, String sName)
        {
        try
            {
            JournalPersistenceManager manager = new JournalPersistenceManager(fileData, fileTrash, sName);
            manager.setJournalConfig(m_journalConfig);
            return manager;
            }
        catch (IOException e)
            {
            throw ensurePersistenceException(e);
            }
        }

    /**
     * Configure service-level MBean context on active and backup managers.
     *
     * @param manager  manager to configure
     * @param role     manager role
     */
    private void configureMBeanContext(JournalPersistenceManager manager, ManagerRole role)
        {
        if (m_sMBeanService != null && m_registrarMBean != null)
            {
            manager.setMBeanContext(m_sMBeanService, role, m_registrarMBean);
            }
        }

    /**
     * Write root metadata used to identify journal snapshots as sparse-capable.
     *
     * @param fileSnapshot  the snapshot directory
     *
     * @throws IOException on I/O failure
     */
    private void writeSparseSnapshotMetadata(File fileSnapshot)
            throws IOException
        {
        Properties props = new Properties();
        props.setProperty(CachePersistenceHelper.META_IMPL_VERSION, "0");
        props.setProperty(CachePersistenceHelper.META_STORAGE_FORMAT, "JOURNAL");
        props.setProperty(CachePersistenceHelper.META_STORAGE_VERSION, "0");
        props.setProperty(CachePersistenceHelper.META_SPARSE_SNAPSHOT, Boolean.TRUE.toString());

        CachePersistenceHelper.writeMetadata(fileSnapshot, props);
        }

    /**
     * Prepare the active directory for journal persistence.
     *
     * @param fileActive  the active directory
     */
    private void prepareActiveDirectory(File fileActive)
        {
        MigrationStatus status = readMigrationStatus(fileActive);
        DirectoryFormat format = detectDirectoryFormat(fileActive);

        if (status == MigrationStatus.STARTED)
            {
            quarantineLegacyActiveDirectory(fileActive, "retry");
            return;
            }

        if (format == DirectoryFormat.BDB)
            {
            quarantineLegacyActiveDirectory(fileActive, "legacy-bdb");
            }
        else if (format == DirectoryFormat.MIXED)
            {
            throw ensurePersistenceException(new IOException("active directory contains mixed persistence formats: "
                    + fileActive));
            }
        }

    /**
     * Perform a one-time migration from a BDB snapshot into the journal active store.
     *
     * @param manager  the target journal manager
     */
    private void migrateFromSnapshotIfNeeded(JournalPersistenceManager manager)
        {
        File fileSource = m_fileMigrationSource;
        if (fileSource == null)
            {
            return;
            }

        File            fileActive = getPersistenceActiveDirectory();
        MigrationStatus status     = readMigrationStatus(fileActive);

        if (status == MigrationStatus.COMPLETED || detectDirectoryFormat(fileActive) == DirectoryFormat.JOURNAL)
            {
            return;
            }

        if (!fileSource.isDirectory())
            {
            throw ensurePersistenceException(new IOException("migration source directory does not exist: " + fileSource));
            }

        Logger.info("Migrating journal persistence from BDB snapshot \"" + fileSource + "\" into \""
                + fileActive + "\"");

        writeMigrationStatus(fileActive, MigrationStatus.STARTED);

        BerkeleyDBManager managerSource = null;
        try
            {
            managerSource = new BerkeleyDBManager(fileSource, null, fileSource.getName());
            importStores(managerSource, manager);
            writeMigrationStatus(fileActive, MigrationStatus.COMPLETED);
            }
        catch (Exception e)
            {
            throw ensurePersistenceException(e, "error migrating BDB snapshot \"" + fileSource
                    + "\" into journal persistence directory \"" + fileActive + '"');
            }
        finally
            {
            if (managerSource != null)
                {
                managerSource.release();
                }
            }
        }

    /**
     * Import all stores from the supplied source manager into the supplied target manager.
     *
     * @param managerSource  the source BDB manager
     * @param managerTarget  the target journal manager
     */
    private void importStores(BerkeleyDBManager managerSource, JournalPersistenceManager managerTarget)
        {
        PersistentStoreInfo[] aInfo = managerSource.listStoreInfo();
        for (PersistentStoreInfo info : aInfo)
            {
            String                        sId         = info.getId();
            PersistentStore<ReadBuffer>   storeSource = null;

            try
                {
                storeSource = managerSource.open(sId, null);
                managerTarget.open(sId, storeSource);
                }
            finally
                {
                managerTarget.close(sId);
                if (storeSource != null)
                    {
                    managerSource.close(sId);
                    }
                }
            }
        }

    /**
     * Quarantine legacy active data so journal persistence can start cleanly.
     *
     * @param fileActive  the active directory
     * @param sReason     the quarantine reason
     */
    private void quarantineLegacyActiveDirectory(File fileActive, String sReason)
        {
        if (!fileActive.isDirectory())
            {
            return;
            }

        File[] aChildren = fileActive.listFiles();
        if (aChildren == null || aChildren.length == 0)
            {
            return;
            }

        try
            {
            File fileTrash = getPersistenceTrashDirectory();
            if (fileTrash == null)
                {
                FileHelper.deleteDir(fileActive);
                FileHelper.ensureDir(fileActive);
                return;
                }

            FileHelper.ensureDir(fileTrash);
            File fileQuarantine = new File(fileTrash,
                    "legacy-" + sReason + '-' + System.currentTimeMillis());
            FileHelper.moveDir(fileActive, fileQuarantine);
            FileHelper.ensureDir(fileActive);
            Logger.info("Moved legacy persistence data from \"" + fileActive + "\" to \"" + fileQuarantine + "\"");
            }
        catch (IOException e)
            {
            throw ensurePersistenceException(e, "error preparing active directory \"" + fileActive
                    + "\" for journal persistence");
            }
        }

    /**
     * Detect the persistence format currently stored in the specified directory.
     *
     * @param dirData  the directory to inspect
     *
     * @return the detected directory format
     */
    private DirectoryFormat detectDirectoryFormat(File dirData)
        {
        if (dirData == null || !dirData.isDirectory())
            {
            return DirectoryFormat.NONE;
            }

        File[] aChild = dirData.listFiles();
        if (aChild == null || aChild.length == 0)
            {
            return DirectoryFormat.NONE;
            }

        boolean fBdb     = false;
        boolean fJournal = false;

        for (File fileChild : aChild)
            {
            if (MIGRATION_STATUS_FILENAME.equals(fileChild.getName())
                    || CachePersistenceHelper.DEFAULT_LOCK_DIR.equals(fileChild.getName()))
                {
                continue;
                }

            if (!fileChild.isDirectory())
                {
                continue;
                }

            try
                {
                Properties props = CachePersistenceHelper.readMetadata(fileChild);
                String     sType = props.getProperty(CachePersistenceHelper.META_STORAGE_FORMAT);

                if ("BDB".equalsIgnoreCase(sType))
                    {
                    fBdb = true;
                    }
                else if ("JOURNAL".equalsIgnoreCase(sType))
                    {
                    fJournal = true;
                    }
                }
            catch (IOException ignored)
                {
                }

            if (fBdb && fJournal)
                {
                return DirectoryFormat.MIXED;
                }
            }

        if (fBdb)
            {
            return DirectoryFormat.BDB;
            }

        if (fJournal)
            {
            return DirectoryFormat.JOURNAL;
            }

        return DirectoryFormat.NONE;
        }

    /**
     * Read migration status from the active directory.
     *
     * @param fileActive  the active directory
     *
     * @return the migration status
     */
    private MigrationStatus readMigrationStatus(File fileActive)
        {
        File fileStatus = new File(fileActive, MIGRATION_STATUS_FILENAME);
        if (!fileStatus.isFile())
            {
            return MigrationStatus.NONE;
            }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(fileStatus))
            {
            props.load(in);
            return MigrationStatus.valueOf(props.getProperty("status", MigrationStatus.NONE.name()));
            }
        catch (Exception e)
            {
            return MigrationStatus.NONE;
            }
        }

    /**
     * Write migration status into the active directory.
     *
     * @param fileActive  the active directory
     * @param status      the migration status
     */
    private void writeMigrationStatus(File fileActive, MigrationStatus status)
        {
        Properties props = new Properties();
        props.setProperty("status", status.name());

        File fileStatus = new File(fileActive, MIGRATION_STATUS_FILENAME);
        try (FileOutputStream out = new FileOutputStream(fileStatus))
            {
            props.store(out, "Journal persistence migration status");
            }
        catch (IOException e)
            {
            throw ensurePersistenceException(e, "error writing migration status for \"" + fileActive + '"');
            }
        }


    // ----- data members ---------------------------------------------------

    /**
     * Journal configuration used for newly created managers.
     */
    private PartitionJournalConfig m_journalConfig = new PartitionJournalConfig();

    /**
     * Optional migration source directory.
     */
    private File m_fileMigrationSource;

    /**
     * Service name used for active/backup MBean registration.
     */
    private String m_sMBeanService;

    /**
     * Registrar used for active/backup MBean registration.
     */
    private MBeanRegistrar m_registrarMBean;

    /**
     * Migration status filename.
     */
    private static final String MIGRATION_STATUS_FILENAME = "migration.properties";

    /**
     * Supported active directory formats.
     */
    private enum DirectoryFormat
        {
        NONE,
        BDB,
        JOURNAL,
        MIXED
        }

    /**
     * Migration status values.
     */
    private enum MigrationStatus
        {
        NONE,
        STARTED,
        COMPLETED
        }
    }
