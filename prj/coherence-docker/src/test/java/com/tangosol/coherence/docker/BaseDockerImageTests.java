/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.coherence.docker;

import com.tangosol.internal.net.management.HttpHelper;
import com.tangosol.internal.net.metrics.MetricsHttpHelper;
import com.tangosol.net.CacheFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.lang.reflect.Method;


/**
 * A base class for testing Oracle Coherence Docker images.
 *
 * @author Jonathan Knight 2022.08.02
 */
public abstract class BaseDockerImageTests
    {
    // ----- test lifecycle -------------------------------------------------

    @BeforeEach
    void setUp(TestInfo testInfo)
        {
        m_sTestMethod = testInfo.getTestMethod().map(Method::getName).orElse("unknown");
        }

    @AfterEach
    void afterTest()
        {
        // clean up Coherence
        CacheFactory.shutdown();
        }

    // ----- helper methods -------------------------------------------------

    GenericContainer<?> start(GenericContainer<?> container)
        {
        container.start();
        return container;
        }

    protected File getOutputDirectory()
        {
        File fileOutDir = FileUtils.getTestOutputFolder(getClass());
        File fileTests  = new File(fileOutDir, "functional" + File.separator + getClass().getSimpleName());
        File dir        = new File(fileTests, m_sTestMethod);
        dir.mkdirs();
        return dir;
        }

    // ----- inner class NeverPull ------------------------------------------

    /**
     * A Testcontainers pull policy.
     */
    public static class NeverPull
            implements ImagePullPolicy
        {
        @Override
        public boolean shouldPull(DockerImageName imageName)
            {
            return false;
            }

        public static final NeverPull INSTANCE = new NeverPull();
        }

    // ----- constants ------------------------------------------------------

    /**
     * A {@link WaitStrategy} that waits for the "Started DefaultCacheServer" message in the container logs.
     */
    public static final WaitStrategy DCS_STARTED = new LogMessageWaitStrategy().withRegEx("Started\\sDefaultCacheServer(.*)");

    /**
     * COHERENCE_HOME inside coherence docker image.
     */
    public static final String COHERENCE_HOME = System.getProperty("docker.coherence.home", "/coherence");

    public static final int MANAGEMENT_PORT = Integer.getInteger("port.management", HttpHelper.DEFAULT_MANAGEMENT_OVER_REST_PORT);
    public static final int METRICS_PORT = Integer.getInteger("port.metrics", MetricsHttpHelper.DEFAULT_PROMETHEUS_METRICS_PORT);
    public static final int EXTEND_PORT = Integer.getInteger("port.extend",20000);

    // ----- data members ---------------------------------------------------

    /**
     * The name of the current test method.
     */
    protected  String m_sTestMethod = "unknown";
    }