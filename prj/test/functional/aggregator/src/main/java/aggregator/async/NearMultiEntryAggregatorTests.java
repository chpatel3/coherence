/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package aggregator.async;

import com.tangosol.util.InvocableMap;

import org.junit.AfterClass;
import org.junit.BeforeClass;


/**
 * A collection of functional tests for the various async
 * {@link InvocableMap.EntryAggregator} implementations that use the
 * "near-test2" cache and two cache servers.
 *
 * @author bb  2015.04.06
 *
 * @see InvocableMap
 */
public class NearMultiEntryAggregatorTests
        extends AbstractAsyncEntryAggregatorTests
    {
    // ----- constructors ---------------------------------------------------

    /**
    * Default constructor.
    */
    public NearMultiEntryAggregatorTests()
        {
        super("near-test2");
        }


    // ----- test lifecycle -------------------------------------------------

    /**
    * Initialize the test class.
    */
    @BeforeClass
    public static void startup()
        {
        startCacheServer("NearMultiEntryAggregatorTests-1", "aggregator");
        startCacheServer("NearMultiEntryAggregatorTests-2", "aggregator");
        }

    /**
    * Shutdown the test class.
    */
    @AfterClass
    public static void shutdown()
        {
        stopCacheServer("NearMultiEntryAggregatorTests-1", true);
        stopCacheServer("NearMultiEntryAggregatorTests-2", true);
        }


    // ----- AbstractEntryAggregatorTests methods ---------------------------

    /**
    * {@inheritDoc}
    */
    protected int getCacheServerCount()
        {
        return 2;
        }
    }
