/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.util.filter;

import com.tangosol.util.Filter;
import com.tangosol.util.MapIndex;
import com.tangosol.util.ValueExtractor;

import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.NavigableMap;

/**
* Filter which compares the result of a method invocation with a value for
* "Less" condition. In a case when either result of a method
* invocation or a value to compare are equal to null, the <tt>evaluate</tt>
* test yields <tt>false</tt>. This approach is equivalent to the way
* the NULL values are handled by SQL.
*
* @param <T> the type of the input argument to the filter
* @param <E> the type of the value to use for comparison
*
* @author cp/gg 2002.10.29
*/
@SuppressWarnings({"unchecked", "rawtypes"})
public class LessFilter<T, E extends Comparable<? super E>>
        extends    ComparisonFilter<T, E, E>
        implements IndexAwareFilter<Object, T>
    {
    // ----- constructors ---------------------------------------------------

    /**
    * Default constructor (necessary for the ExternalizableLite interface).
    */
    public LessFilter()
        {
        }

    /**
    * Construct a LessFilter for testing "Less" condition.
    *
    * @param extractor the ValueExtractor to use by this filter
    * @param value     the object to compare the result with
    */
    public LessFilter(ValueExtractor<? super T, ? extends E> extractor, E value)
        {
        super(extractor, value);
        }

    /**
    * Construct a LessFilter for testing "Less" condition.
    *
    * @param sMethod  the name of the method to invoke via reflection
    * @param value    the object to compare the result with
    */
    public LessFilter(String sMethod, E value)
        {
        super(sMethod, value);
        }

    // ----- Filter interface -----------------------------------------------

    protected String getOperator()
        {
        return "<";
        }

    // ----- ExtractorFilter methods ----------------------------------------

    /**
    * {@inheritDoc}
    */
    protected boolean evaluateExtracted(E extracted)
        {
        E value = getValue();

        return extracted != null && value != null &&
               extracted.compareTo(value) < 0;
        }


    // ----- IndexAwareFilter interface -------------------------------------

    /**
    * {@inheritDoc}
    */
    public int calculateEffectiveness(Map mapIndexes, Set setKeys)
        {
        MapIndex index = (MapIndex) mapIndexes.get(getValueExtractor());
        if (index == null)
            {
            // there is no relevant index
            return -1;
            }

        Map<E, Set<?>> mapContents = index.getIndexContents();
        if (mapContents.isEmpty())
            {
            return 0;
            }

        int cMatch = 0;
        if (mapContents instanceof NavigableMap)
            {
            NavigableMap<E, Set<?>> mapSorted     = (NavigableMap<E, Set<?>>) mapContents;
            Integer                 cAllOrNothing = allOrNothing(index, mapSorted, setKeys);
            if (cAllOrNothing != null)
                {
                return cAllOrNothing;
                }

            NavigableMap<E, Set<?>> subMap = mapSorted.headMap(getValue(), includeEquals());
            for (Set<?> set : subMap.values())
                {
                cMatch += ensureSafeSet(set).size();
                }
            }
        else
            {
            for (Map.Entry<E, Set<?>> entry : mapContents.entrySet())
                {
                if (evaluateExtracted(entry.getKey()))
                    {
                    cMatch += ensureSafeSet(entry.getValue()).size();
                    }
                }
            }

        return cMatch;
        }

    /**
    * {@inheritDoc}
    */
    public Filter applyIndex(Map mapIndexes, Set setKeys)
        {
        E value = getValue();
        if (value == null)
            {
            // nothing could be compared to null
            setKeys.clear();
            return null;
            }

        MapIndex index = (MapIndex) mapIndexes.get(getValueExtractor());
        if (index == null)
            {
            // there is no relevant index; evaluate individual entries
            return this;
            }
        else if (index.getIndexContents().isEmpty())
            {
            // there are no entries in the index, which means no entries match this filter
            setKeys.clear();
            return null;
            }

        if (index.isOrdered())
            {
            NavigableMap<E, Set<?>> mapContents   = (NavigableMap<E, Set<?>>) index.getIndexContents();
            Integer                 cAllOrNothing = allOrNothing(index, mapContents, setKeys);
            if (cAllOrNothing != null)
                {
                if (cAllOrNothing == 0)
                    {
                    setKeys.clear();
                    }
                return null;
                }

            Set          setNULL    = mapContents.get(null);
            NavigableMap mapHead    = mapContents.headMap(value, includeEquals());
            NavigableMap mapTail    = mapContents.tailMap(value, !includeEquals());
            boolean      fHeadHeavy = mapHead.size() > mapContents.size() / 2;

            setKeys.removeAll(ensureSafeSet(setNULL));

            if (fHeadHeavy && !index.isPartial())
                {
                for (Object o : mapTail.values())
                    {
                    Set set = (Set) o;
                    setKeys.removeAll(ensureSafeSet(set));
                    }
                }
            else
                {
                Set setLT = new HashSet();
                for (Object o : mapHead.values())
                    {
                    Set set = (Set) o;
                    setLT.addAll(ensureSafeSet(set));
                    }

                setKeys.retainAll(setLT);
                }
            }
        else
            {
            Map<E, Set<?>> mapContents = index.getIndexContents();

            if (index.isPartial())
                {
                Set setLT = new HashSet();
                for (Map.Entry<E, Set<?>> entry : mapContents.entrySet())
                    {
                    if (evaluateExtracted(entry.getKey()))
                        {
                        setLT.addAll(ensureSafeSet(entry.getValue()));
                        }
                    }
                setKeys.retainAll(setLT);
                }
            else
                {
                for (Map.Entry<E, Set<?>> entry : mapContents.entrySet())
                    {
                    if (!evaluateExtracted(entry.getKey()))
                        {
                        setKeys.removeAll(ensureSafeSet(entry.getValue()));
                        }
                    }
                }
            }

        return null;
        }

    // ---- helpers ---------------------------------------------------------

    /**
     * Return whether the entries that match comparison value
     * for this filter should be included in the results.
     *
     * @return {@code true} if equal values should be included in the results;
     *         {@code false otherwise}
     */
    protected boolean includeEquals()
        {
        return false;
        }

    /**
     * Determine if the filter will match all or none of the entries in the index.
     *
     * @param index        the index
     * @param mapContents  the index contents
     * @param setKeys      the set of keys to filter
     *
     * @return {@code 0} if no entries match; {@code setKeys.size()} if all entries match;
     *         and {@code null} if only some entries match or no conclusive determination
     *         can be made
     */
    protected Integer allOrNothing(MapIndex index, NavigableMap<E, Set<?>> mapContents, Set setKeys)
        {
        if (!index.isPartial())
            {
            Map.Entry<E, Set<?>> loEntry = mapContents.firstEntry();
            if (loEntry == null || !evaluateExtracted(loEntry.getKey()))
                {
                // no entries match, remove all keys
                return 0;
                }

            Map.Entry<E, Set<?>> hiEntry = mapContents.lastEntry();
            if (hiEntry == null)
                {
                // the map is empty, remove all keys
                return 0;
                }
            else if (evaluateExtracted(hiEntry.getKey()))
                {
                // all entries match, nothing to remove
                return setKeys.size();
                }
            }

        return null;
        }
    }
