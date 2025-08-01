/*
 * Copyright (c) 2024, 2025, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package opentelemetry.grpc;

import com.tangosol.net.Coherence;
import com.tangosol.net.CoherenceConfiguration;
import com.tangosol.net.Session;
import com.tangosol.net.SessionConfiguration;

import io.opentelemetry.proto.trace.v1.Span;

import java.util.List;

import java.util.concurrent.TimeUnit;

import opentelemetry.core.TestingUtils;

import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests to validate traces generated by the grpc client and proxy.
 *
 * @author rl 9.27.2023
 * @since 24.03
 */
public class GrpcTracingIT
    {
    // ----- test methods ---------------------------------------------------

    @Test
    @Ignore
    public void shouldBeDisabledByDefault()
        {
        }

    @Test
    @Ignore
    public void testGetTracing()
        {
        }

    @Test
    @Ignore
    public void testRemoveTracing()
        {
        }

    @Test
    @Ignore
    public void testPutTracing()
        {
        }

    // ----- helper methods -------------------------------------------------

    protected Session createSession()
        {
        CoherenceConfiguration.Builder cfgBuilder = CoherenceConfiguration.builder();
        SessionConfiguration cfg = SessionConfiguration.builder()
                .named("test-session")
                .withMode(Coherence.Mode.Grpc)
                .withParameter("coherence.profile", "thin")
                .withParameter("coherence.wka",     "127.0.0.1")
                .build();

        cfgBuilder.withSession(cfg);

        Coherence coherence;
        try
            {
            coherence = Coherence.clientBuilder(cfgBuilder.build(), Coherence.Mode.Grpc).build()
                    .start().get(5, TimeUnit.MINUTES);
            }
        catch (Exception e)
            {
            throw new RuntimeException(e);
            }

        return coherence.getSession("test-session");
        }
    }
