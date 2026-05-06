/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.coherence.config.builder;

import com.oracle.coherence.persistence.PersistenceEnvironment;

import com.tangosol.coherence.config.ResolvableParameterList;

import com.tangosol.config.expression.Parameter;

import com.tangosol.io.FileHelper;
import com.tangosol.io.ReadBuffer;

import com.tangosol.persistence.SafePersistenceWrappers;
import com.tangosol.persistence.bdb.BerkeleyDBEnvironment;
import com.tangosol.persistence.journal.JournalPersistenceEnvironment;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link PersistenceEnvironmentParamBuilder}.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public class PersistenceEnvironmentParamBuilderTest
    {
    @After
    public void cleanup()
            throws IOException
        {
        if (m_fileActive != null && m_fileActive.exists())
            {
            FileHelper.deleteDir(m_fileActive);
            }
        if (m_fileSnapshot != null && m_fileSnapshot.exists())
            {
            FileHelper.deleteDir(m_fileSnapshot);
            }
        if (m_fileTrash != null && m_fileTrash.exists())
            {
            FileHelper.deleteDir(m_fileTrash);
            }
        }

    @Test
    public void testDefaultTypeCreatesBDB()
            throws IOException
        {
        PersistenceEnvironmentParamBuilder builder = createBuilder();
        builder.setPersistenceMode("active");

        PersistenceEnvironment<ReadBuffer> env = builder.realize(createResolver(), null, null);
        try
            {
            PersistenceEnvironment<ReadBuffer> inner = SafePersistenceWrappers.unwrap(env);
            assertTrue("Expected BerkeleyDBEnvironment but got " + inner.getClass().getName(),
                    inner instanceof BerkeleyDBEnvironment);
            }
        finally
            {
            env.release();
            }
        }

    @Test
    public void testJournalTypeCreatesJournalEnvironment()
            throws IOException
        {
        PersistenceEnvironmentParamBuilder builder = createBuilder();

        builder.setPersistenceType("journal");

        PersistenceEnvironment<ReadBuffer> env = builder.realize(createResolver(), null, null);
        try
            {
            PersistenceEnvironment<ReadBuffer> inner = SafePersistenceWrappers.unwrap(env);
            assertTrue("Expected JournalPersistenceEnvironment but got " + inner.getClass().getName(),
                    inner instanceof JournalPersistenceEnvironment);
            }
        finally
            {
            env.release();
            }
        }

    @Test
    public void testExplicitBdbType()
            throws IOException
        {
        PersistenceEnvironmentParamBuilder builder = createBuilder();

        builder.setPersistenceType("bdb");

        PersistenceEnvironment<ReadBuffer> env = builder.realize(createResolver(), null, null);
        try
            {
            PersistenceEnvironment<ReadBuffer> inner = SafePersistenceWrappers.unwrap(env);
            assertTrue("Expected BerkeleyDBEnvironment but got " + inner.getClass().getName(),
                    inner instanceof BerkeleyDBEnvironment);
            }
        finally
            {
            env.release();
            }
        }

    @Test
    public void testJournalMigrationSourceDirectoryConfigured()
            throws IOException
        {
        File fileMigration = FileHelper.createTempDir();
        try
            {
            PersistenceEnvironmentParamBuilder builder = createBuilder();

            builder.setPersistenceType("journal");
            builder.setMigrationSourceDirectory(fileMigration.getAbsolutePath());

            PersistenceEnvironment<ReadBuffer> env = builder.realize(createResolver(), null, null);
            try
                {
                PersistenceEnvironment<ReadBuffer> inner = SafePersistenceWrappers.unwrap(env);
                assertTrue("Expected JournalPersistenceEnvironment but got " + inner.getClass().getName(),
                        inner instanceof JournalPersistenceEnvironment);
                assertTrue("Expected migration source directory to be configured",
                        fileMigration.equals(((JournalPersistenceEnvironment) inner).getMigrationSourceDirectory()));
                }
            finally
                {
                env.release();
                }
            }
        finally
            {
            FileHelper.deleteDir(fileMigration);
            }
        }

    @Test
    public void testParseJournalMemorySizeWithSuffix()
        {
        assertEquals(128L * 1024L * 1024L,
                PersistenceEnvironmentParamBuilder.parseJournalMemorySize("test.prop", "128M"));
        }

    @Test
    public void testParseJournalMemorySizeNumericOnly()
        {
        assertEquals(67_108_864L,
                PersistenceEnvironmentParamBuilder.parseJournalMemorySize("test.prop", "67108864"));
        }

    @Test
    public void testParseJournalMemorySizeInvalid()
        {
        try
            {
            PersistenceEnvironmentParamBuilder.parseJournalMemorySize("test.prop", "notanumber");
            fail("expected IllegalArgumentException");
            }
        catch (IllegalArgumentException e)
            {
            assertTrue(e.getMessage().contains("test.prop"));
            assertTrue(e.getMessage().contains("notanumber"));
            }
        }

    // ----- helpers --------------------------------------------------------

    private PersistenceEnvironmentParamBuilder createBuilder()
            throws IOException
        {
        m_fileActive   = FileHelper.createTempDir();
        m_fileSnapshot = FileHelper.createTempDir();
        m_fileTrash    = FileHelper.createTempDir();

        PersistenceEnvironmentParamBuilder builder =
                new PersistenceEnvironmentParamBuilder();

        builder.setActiveDirectory(m_fileActive.getAbsolutePath());
        builder.setPersistenceSnapshotDirectory(m_fileSnapshot.getAbsolutePath());
        builder.setPersistenceTrashDirectory(m_fileTrash.getAbsolutePath());

        return builder;
        }

    private ResolvableParameterList createResolver()
        {
        ResolvableParameterList resolver = new ResolvableParameterList();
        resolver.add(new Parameter("cluster-name", String.class, "test-cluster"));
        resolver.add(new Parameter("service-name", String.class, "test-service"));
        return resolver;
        }

    // ----- data members ---------------------------------------------------

    private File m_fileActive;
    private File m_fileSnapshot;
    private File m_fileTrash;
    }
