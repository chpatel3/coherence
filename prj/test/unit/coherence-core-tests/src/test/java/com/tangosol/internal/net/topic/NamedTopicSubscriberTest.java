/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.internal.net.topic;

import com.tangosol.internal.net.topic.impl.paged.model.SubscriberGroupId;
import com.tangosol.internal.net.topic.impl.paged.model.SubscriberId;

import com.tangosol.net.Cluster;
import com.tangosol.net.ClusterDependencies;
import com.tangosol.net.TopicService;

import com.tangosol.net.topic.NamedTopic;
import com.tangosol.net.topic.Position;
import com.tangosol.net.topic.Subscriber;
import com.tangosol.net.topic.TopicDependencies;
import com.tangosol.net.topic.TopicException;

import com.tangosol.util.ServiceListener;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NamedTopicSubscriber}.
 *
 * @author Aleks Seovic  2026.05.22
 */
public class NamedTopicSubscriberTest
    {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldCleanupConnectorWhenInitializeFails()
        {
        NamedTopic<String>           topic               = mock(NamedTopic.class);
        TopicService                 service             = mock(TopicService.class);
        Cluster                      cluster             = mock(Cluster.class);
        ClusterDependencies          clusterDependencies = mock(ClusterDependencies.class);
        TopicDependencies            topicDependencies   = mock(TopicDependencies.class);
        SubscriberConnector<String>  connector           = mock(SubscriberConnector.class);
        SubscriberId                 subscriberId        = new SubscriberId(1, 1, null);
        SubscriberGroupId            groupId             = SubscriberGroupId.withName("test-group");
        NamedTopicSubscriber.TopicChannel channel        = new TestTopicChannel(0);

        when(topic.getName()).thenReturn("test-topic");
        when(topic.getService()).thenReturn(service);
        when(topic.getTopicService()).thenReturn(service);
        when(topic.getChannelCount()).thenReturn(1);
        when(service.getCluster()).thenReturn(cluster);
        when(cluster.getDependencies()).thenReturn(clusterDependencies);
        when(clusterDependencies.getPublisherCloggedCount()).thenReturn(100);
        when(connector.getSubscriberId()).thenReturn(subscriberId);
        when(connector.getSubscriberGroupId()).thenReturn(groupId);
        when(connector.getTopicDependencies()).thenReturn(topicDependencies);
        when(connector.getTypeName()).thenReturn("test");
        when(connector.getSubscriptionId()).thenReturn(1L);
        when(topicDependencies.getReconnectRetryMillis()).thenReturn(1L);
        when(topicDependencies.getReconnectTimeoutMillis()).thenReturn(1000L);
        when(connector.createChannel(any(), eq(0))).thenReturn(channel);
        doThrow(new TopicException("boom")).when(connector).initialize(any(), anyBoolean(), anyBoolean(), anyBoolean());

        assertThrows(TopicException.class,
                () -> new TestNamedTopicSubscriber<>(topic, connector, new Subscriber.Option[0]));

        verify(connector).closeSubscription(any(), eq(false));
        verify(connector).removeListener(any(SubscriberConnector.SubscriberListener.class));
        verify(connector).close();
        verify(service).removeServiceListener(any(ServiceListener.class));
        }

    private static class TestNamedTopicSubscriber<V>
            extends NamedTopicSubscriber<V>
        {
        private <T> TestNamedTopicSubscriber(NamedTopic<?> topic, SubscriberConnector<V> connector,
                Subscriber.Option<? super T, V>[] options)
            {
            super(topic, connector, options);
            }

        @Override
        protected void registerMBean()
            {
            }

        @Override
        protected void unregisterMBean()
            {
            }
        }

    private static class TestTopicChannel
            extends NamedTopicSubscriber.TopicChannel
        {
        private TestTopicChannel(int nChannel)
            {
            f_nChannel = nChannel;
            }

        @Override
        public int getId()
            {
            return f_nChannel;
            }

        @Override
        protected void resetHead()
            {
            }

        private final int f_nChannel;
        }
    }
