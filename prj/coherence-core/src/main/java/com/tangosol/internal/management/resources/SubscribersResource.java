/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.internal.management.resources;

import com.tangosol.internal.http.HttpRequest;
import com.tangosol.internal.http.RequestRouter;
import com.tangosol.internal.http.Response;
import com.tangosol.internal.management.EntityMBeanResponse;
import java.net.URI;

import com.tangosol.net.management.MBeanAccessor.QueryBuilder;

import java.util.HashMap;

/**
 * Handles management API requests for subscribers in a service.
 */
public class SubscribersResource
        extends AbstractManagementResource
    {
    // ----- Routes methods -------------------------------------------------

    public void addRoutes(RequestRouter router, String sPathRoot)
        {
        router.addGet(sPathRoot, this::get);

        // child resources
        router.addRoutes(sPathRoot + "/{" + SUBSCRIBER_ID + "}", new SubscriberResource());
        }

    // ----- GET API --------------------------------------------------------

    /**
     * Returns the list of subscribers for a particular topic.
     *
     * @return the response object.
     */
    public Response get(HttpRequest request)
        {
        String       sTopicName   = request.getFirstPathParameter(TOPIC_NAME);
        QueryBuilder queryBuilder = createQueryBuilder(request)
                .withBaseQuery(TOPIC_SUBSCRIBERS_QUERY + sTopicName)
                .withService(getService(request));

        URI uriCurrent = getCurrentUri(request);
        EntityMBeanResponse response = getResponseBodyForMBeanCollection(request,
                                                                         queryBuilder,
                                                                         new SubscriberResource(),
                                                                         SUBSCRIBER_ID,
                                                                         null,
                                                                         getParentUri(request),
                                                                         uriCurrent,
                                                                         uriCurrent,
                                                                         null);

        if (response == null && getService(request) != null)
            {
            return Response.status(Response.Status.NOT_FOUND).build();
            }

        return response == null
               ? response(new HashMap<>())
               : response(response);
        }
    }
