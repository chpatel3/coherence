/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package netty.grpc.proxy.java21;


import com.oracle.coherence.ai.grpc.AsyncVectorStoreService;
import com.oracle.coherence.ai.grpc.VectorStoreService;

import grpc.proxy.java21.TestVectorStoreServiceProvider;

public class TestVectorStoreServiceProviderImpl
        implements TestVectorStoreServiceProvider
    {
    @Override
    public VectorStoreService getService(VectorStoreService.Dependencies dependencies)
        {
        return new AsyncVectorStoreService(dependencies);
        }
    }
