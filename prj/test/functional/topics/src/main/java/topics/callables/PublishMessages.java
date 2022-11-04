/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package topics.callables;

import com.oracle.bedrock.runtime.concurrent.RemoteCallable;
import com.oracle.coherence.common.base.Exceptions;
import com.oracle.coherence.common.base.Logger;
import com.tangosol.internal.net.topic.impl.paged.PagedTopicCaches;
import com.tangosol.internal.net.topic.impl.paged.model.Page;
import com.tangosol.net.CacheService;
import com.tangosol.net.Coherence;
import com.tangosol.net.Session;
import com.tangosol.net.topic.NamedTopic;
import com.tangosol.net.topic.Publisher;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Publishes messages to a topic.
 */
public class PublishMessages
        implements RemoteCallable<Void>
    {
    public PublishMessages(String sTopicName, int cMessage)
        {
        this(sTopicName, cMessage, -1);
        }

    public PublishMessages(String sTopicName, int cMessage, int nChannel)
        {
        m_sTopicName = sTopicName;
        m_cMessage   = cMessage;
        m_nChannel   = nChannel;
        }

    @Override
    public Void call() throws Exception
        {
        Logger.info("PublishMessages callable starting: Populating topic " + m_sTopicName + " with " + m_cMessage + " messages");
        Session                   session = Coherence.getInstance().getSession();
        NamedTopic<String>        topic   = session.getTopic(m_sTopicName);
        Publisher.OrderBy<Object> orderBy = m_nChannel >= 0
                ? Publisher.OrderBy.id(m_nChannel)
                : Publisher.OrderBy.roundRobin();


        try (Publisher<String> publisher = topic.createPublisher(orderBy))
            {
            CompletableFuture<?>[] aFuture = new CompletableFuture[m_cMessage];
            for (int i = 0; i < m_cMessage; i++)
                {
                String sMessage = "message-" + i;
                CompletableFuture<Publisher.Status> future = publisher.publish(sMessage);
                aFuture[i] = future.handle((status, err) ->
                        {
                        if (err != null)
                            {
                            throw Exceptions.ensureRuntimeException(err);
                            }
                        return null;
                        });
                }

            CompletableFuture.allOf(aFuture).get(5, TimeUnit.MINUTES);
            Logger.info("PublishMessages callable complete: Populated topic " + m_sTopicName + " with " + m_cMessage + " messages");
            return null;
            }
        }
    
    private final String m_sTopicName;

    private final int m_cMessage;
    
    private final int m_nChannel;
    }
