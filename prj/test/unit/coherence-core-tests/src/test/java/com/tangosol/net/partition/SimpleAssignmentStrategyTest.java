/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.net.partition;

import com.tangosol.net.Member;
import com.tangosol.net.PartitionedService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SimpleAssignmentStrategy}.
 *
 * @author Aleks Seovic  2026.04.22
 * @since 26.04
 */
public class SimpleAssignmentStrategyTest
    {
    @Test
    public void shouldPrioritizeLeastPortableOrphanRecoveries()
        {
        Member memberOne = mockMember(1, 1);
        Member memberTwo = mockMember(2, 2);

        PartitionedService service = mock(PartitionedService.class);
        when(service.getPartitionCount()).thenReturn(2);
        when(service.getBackupCount()).thenReturn(1);

        DistributionManager manager = mock(DistributionManager.class);
        when(manager.getService()).thenReturn(service);
        when(manager.getOwnershipMembers()).thenReturn(new LinkedHashSet<>(Arrays.asList(memberOne, memberTwo)));
        when(manager.getOwnershipLeavingMembers()).thenReturn(Collections.emptySet());
        when(manager.getMember(1)).thenReturn(memberOne);
        when(manager.getMember(2)).thenReturn(memberTwo);
        when(manager.getOwnedPartitions(any(Member.class), anyInt())).thenAnswer(invocation -> new PartitionSet(2));
        when(manager.getPartitionOwnership(anyInt())).thenAnswer(invocation -> new Ownership(1));

        List<PartitionAssignment> listAssignments = new ArrayList<>();
        doAnswer(invocation ->
            {
            PartitionSet parts  = invocation.getArgument(0);
            Ownership    owners = invocation.getArgument(1);

            for (int iPart = parts.next(0); iPart >= 0; iPart = parts.next(iPart + 1))
                {
                listAssignments.add(new PartitionAssignment(iPart, owners.getPrimaryOwner()));
                }
            return null;
            }).when(manager).suggest(any(PartitionSet.class), any(Ownership.class));

        TestSimpleAssignmentStrategy strategy = new TestSimpleAssignmentStrategy();
        strategy.setManager(manager);

        Map<Member, PartitionSet> mapConstraints = new HashMap<>();

        PartitionSet partsOne = new PartitionSet(2);
        partsOne.add(0);
        partsOne.add(1);
        mapConstraints.put(memberOne, partsOne);

        PartitionSet partsTwo = new PartitionSet(2);
        partsTwo.add(0);
        mapConstraints.put(memberTwo, partsTwo);

        strategy.analyzeOrphans(mapConstraints);

        assertThat(findPrimaryOwner(listAssignments, 0), is(2));
        assertThat(findPrimaryOwner(listAssignments, 1), is(1));
        }

    private static int findPrimaryOwner(List<PartitionAssignment> listAssignments, int iPartition)
        {
        for (PartitionAssignment assignment : listAssignments)
            {
            if (assignment.getPartition() == iPartition)
                {
                return assignment.getPrimaryOwner();
                }
            }

        throw new AssertionError("Missing suggestion for partition " + iPartition);
        }

    private static Member mockMember(int nId, int nMachine)
        {
        Member member = mock(Member.class);

        when(member.getId()).thenReturn(nId);
        when(member.getMachineId()).thenReturn(nMachine);
        when(member.getMachineName()).thenReturn("machine-" + nMachine);
        when(member.getRackName()).thenReturn("rack-1");
        when(member.getSiteName()).thenReturn("site-1");

        return member;
        }

    /**
     * Minimal strategy subclass that allows the test to inject a manager
     * without full coordinator initialization.
     */
    protected static class TestSimpleAssignmentStrategy
            extends SimpleAssignmentStrategy
        {
        protected void setManager(DistributionManager manager)
            {
            m_manager = manager;
            }
        }

    /**
     * Records a suggested primary-owner change for an orphaned partition.
     */
    protected static class PartitionAssignment
        {
        protected PartitionAssignment(int iPartition, int nPrimaryOwner)
            {
            m_iPartition    = iPartition;
            m_nPrimaryOwner = nPrimaryOwner;
            }

        protected int getPartition()
            {
            return m_iPartition;
            }

        protected int getPrimaryOwner()
            {
            return m_nPrimaryOwner;
            }

        protected final int m_iPartition;
        protected final int m_nPrimaryOwner;
        }
    }
