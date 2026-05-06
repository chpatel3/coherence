/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package persistence;

import org.junit.Test;

import java.io.IOException;

import java.lang.reflect.InvocationTargetException;

import java.util.concurrent.ExecutionException;

import javax.management.MBeanException;

/**
 * Endurance coverage for the full journal simple-persistence matrix.
 *
 * @author Aleks Seovic  2026.04.28
 * @since 26.04
 */
public class JournalSimplePersistenceEnduranceTests
        extends JournalSimplePersistenceTests
    {
    @Test
    public void testFullSingleServerAsyncSEONE()
            throws IOException
        {
        testSingleServerAsyncSEONE();
        }

    @Test
    public void testFullSingleServerAsync()
            throws IOException
        {
        testSingleServerAsync();
        }

    @Test
    public void testFullSingleServerWorkersSEONE()
            throws IOException
        {
        testSingleServerWorkersSEONE();
        }

    @Test
    public void testFullSingleServerWorkers()
            throws IOException
        {
        testSingleServerWorkers();
        }

    @Test
    public void testFullSingleServerAsyncWorkersSEONE()
            throws IOException
        {
        testSingleServerAsyncWorkersSEONE();
        }

    @Test
    public void testFullSingleServerAsyncWorkers()
            throws IOException
        {
        testSingleServerAsyncWorkers();
        }

    @Test
    public void testFullPassiveSnapshot()
            throws IOException, MBeanException
        {
        testPassiveSnapshot();
        }

    @Test
    public void testFullSnapshotRecoveryWithTTL()
            throws IOException, MBeanException
        {
        testSnapshotRecoveryWithTTL();
        }

    @Test
    public void testFullSnapshotRecoveryWithTTLPartitioned()
            throws IOException, MBeanException
        {
        testSnapshotRecoveryWithTTLPartitioned();
        }

    @Test
    public void testFullRecoveryWithTTL()
            throws IOException, MBeanException
        {
        testRecoveryWithTTL();
        }

    @Test
    public void testFullActiveSnapshot()
            throws IOException, MBeanException
        {
        testActiveSnapshot();
        }

    @Test
    public void testFullActiveArchiver()
            throws IOException, NoSuchMethodException, IllegalAccessException,
                   InvocationTargetException, MBeanException
        {
        testActiveArchiver();
        }

    @Test
    public void testFullBug25522362Regression()
            throws IOException, NoSuchMethodException, IllegalAccessException,
                   InvocationTargetException, MBeanException
        {
        testBug25522362Regression();
        }

    @Test
    public void testFullPassiveArchiver()
            throws IOException, NoSuchMethodException, IllegalAccessException,
                   InvocationTargetException, MBeanException
        {
        testPassiveArchiver();
        }

    @Test
    public void testFullToolsAPIWithProxy()
            throws IOException, NoSuchMethodException, IllegalAccessException,
                   InvocationTargetException, MBeanException, ExecutionException
        {
        testToolsAPIWithProxy();
        }

    @Test
    public void testFullMultipleRestartsBalanced()
            throws IOException, MBeanException
        {
        testMultipleRestartsBalanced();
        }

    @Test
    public void testFullMultipleRestartsBalancedBackup()
            throws IOException, MBeanException
        {
        testMultipleRestartsBalancedBackup();
        }

    @Test
    public void testFullTruncate()
            throws IOException
        {
        testTruncate();
        }

    @Test
    public void testFullCqcSnapshotRecovery()
            throws IOException, MBeanException
        {
        testCqcSnapshotRecovery();
        }

    @Test
    public void testFullPersistenceRecoveryLogsMessage()
            throws IOException, InterruptedException
        {
        testPersistenceRecoveryLogsMessage();
        }
    }
