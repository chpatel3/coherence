/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.util;

import com.oracle.coherence.common.base.Predicate;

import java.util.Iterator;

/**
 * A BinaryLongMap is an interface representing a mapping from Binary values
 * to long values.
 *
 * @author cp 2012.08.03
 * @since Coherence 12.1.2
 */
public interface BinaryLongMap
    {
    /**
     * Find the specified key in the map and return the value associated with
     * it.
     *
     * @param binKey  a Binary key
     *
     * @return the value associated with the specified key, or <tt>0L</tt> if
     *         the specified key is not in the map
     */
    public long get(Binary binKey);

    /**
     * Blindly store the passed value for the specified key, adding the key if
     * it is not already in the map, or replacing the current value if the key
     * is in the map.
     * <p>
     * Note that associating the value zero with a key is analogous to
     * removing the key.
     *
     * @param binKey  the Binary key to add or update
     * @param lValue  the value to associate with the key
     */
    public void put(Binary binKey, long lValue);

    /**
     * Store the passed value for the specified key, only if the key does not
     * currently exist in the map.
     * <p>
     * Note that associating the value zero with a key using this method will
     * have no effect, since were that key already present, there would be no
     * change, and were it not present, the value zero is analogous to
     * removing the key, which again is no change (since it is not present).
     *
     * @param binKey   a Binary key
     * @param lValue   the new value to associate with the passed key
     *
     * @return true iff the key was not present in the map, and now it is
     *         present in the map associated with the passed value
     */
    public boolean putIfAbsent(Binary binKey, long lValue);

    /**
     * Store the passed "new" value for the specified key, only if the current
     * value associated with the specified key is the same as the specified
     * "old" value.
     * <p>
     * Note that replacing the value of zero is analogous to
     * {@link #putIfAbsent(com.tangosol.util.Binary, long) putIfAbsent}, and
     * associating the value zero with a key using this method is the same
     * as {@link #remove(com.tangosol.util.Binary, long) remove} passing the
     * old value to match.
     *
     * @param binKey     a Binary key
     * @param lValueOld  the assumed old value to replace
     * @param lValueNew  the new value to associate with the passed key
     *
     * @return true iff the key was associated with the passed "old" value,
     *         and now it is associated with the passed "new" value
     */
    public boolean replace(Binary binKey, long lValueOld, long lValueNew);

    /**
     * Blindly remove the specified Binary key from the map.
     *
     * @param binKey  a Binary key
     */
    public void remove(Binary binKey);

    /**
     * Remove the specified Binary key from the map iff it exists in the map
     * and is associated with the specified value.
     * <p>
     * Note that removing an association whose value is zero has no effect.
     *
     * @param binKey  a Binary key
     * @param lValue  the value that the key must have in order to be removed
     *
     * @return true iff the map contained the key, it was associated with the
     *         specified value, and has now been removed
     */
    public boolean remove(Binary binKey, long lValue);

    /**
     * Initialize the map to an empty state.
     */
    public void clear();

    /**
     * Determine the size of the map.
     *
     * @return the number of unique keys stored in the map
     */
    public int size();

    /**
     * Obtain an iterator of the keys stored in the map.
     *
     * @return an Iterator of Binary keys
     */
    public Iterator<Binary> keys();

    /**
     * Obtain an iterator of the keys stored in the map whose corresponding
     * {@link Entry Entry} matches the passed {@link Predicate Predicate&lt;Entry&gt;}.
     * <p>
     * The entry passed to the predicate should be treated as read-only, and any
     * attempt to modify the entry may have undefined behavior and/or throw an
     * Exception.  Modifications to entries should instead be performed using
     * an {@link EntryVisitor} via the {@link #visit} or {@link #visitAll} methods.
     *
     * @param predicate  a <tt>Predicate&lt;Entry&gt;</tt> to apply to each Entry
     *
     * @return an Iterator of Binary keys
     */
    public Iterator<Binary> keys(Predicate<Entry> predicate);

    /**
     * Apply the specified visitor to the entry associated with the specified
     * key, if the entry exists or may be added.  The visited entry may or may
     * not logically exist in the BinaryLongMap (e.g. it may be associated
     * with a value of 0L) but is guaranteed to be safe to be added or removed
     * (via {@link Entry#setValue}).
     *
     * @param binKey   the key to visit
     * @param visitor  the visitor to apply
     */
    public void visit(Binary binKey, EntryVisitor visitor);

    /**
     * Apply the specified visitor to all entries in the BinaryLongMap.
     *
     * @param visitor  the visitor to apply
     */
    public void visitAll(EntryVisitor visitor);

    /**
     * Internal opaque method: De-duplicate keys.
     *
     * @param o  some implementation-specific object
     */
    public void internKeys(Object o);



    // ----- inner interface: Entry -----------------------------------------

    /**
     * Represents an Entry stored in a BinaryLongMap.
     */
    public interface Entry
        {
        /**
         * Obtain the key.
         *
         * @return the key as a {@link Binary}
         */
        public Binary getKey();

        /**
         * Obtain the value associated with this entry.
         *
         * @return the associated value as a <tt>long</tt>
         */
        public long getValue();

        /**
         * Set the value associated with this entry.
         * <p>
         * Note: in some implementations, this operation may cause this Entry to
         *       be replaced with another Entry instance in the underlying
         *       representation.
         *
         * @param lValue  the value to associate with this entry
         *
         * @return an {@link Entry} with the new value, which may or may not
         *         be the same {@link Entry} as <tt>this</tt>
         */
        public Entry setValue(long lValue);
        }


    // ----- inner interface: EntryVisitor ----------------------------------

    /**
     * Represent a visitor to be applied to one or more entries in the
     * BinaryLongMap.
     */
    public interface EntryVisitor
        {
        /**
         * Visit the specified entry.
         *
         * @param entry  the entry
         */
        public void visit(Entry entry);
        }


    // ----- inner class: SimpleMapImpl -------------------------------------

    /**
     * A <tt>java.util.Map&lt;Binary, Long&gt;</tt> implementation. This is a
     * simple wrapper around a BinaryLongMap.
     */
    public static class SimpleMapImpl
            extends AbstractKeyBasedMap
        {
        // ----- constructors -----------------------------------------------

        /**
         * Construct a SimpleMapImpl using a BinaryRadixTree as the internal
         * storage.
         */
        public SimpleMapImpl()
            {
            this(new BinaryRadixTree());
            }

        /**
         * Construct a SimpleMapImpl around an existing BinaryLongMap.
         *
         * @param blm  the BinaryLongMap to use as the storage for this map
         */
        public SimpleMapImpl(BinaryLongMap blm)
            {
            assert blm != null;

            m_blm = blm;
            }

        // ----- Map methods ------------------------------------------------

        /**
         * {@inheritDoc}
         */
        @Override
        public void clear()
            {
            m_blm.clear();
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean containsKey(Object oKey)
            {
            return m_blm.get((Binary) oKey) != 0L;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Long get(Object oKey)
            {
            long l = m_blm.get((Binary) oKey);
            return l == 0L ? null : Long.valueOf(l);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Long put(Object oKey, Object oValue)
            {
            Long oOrig = get(oKey);
            m_blm.put((Binary) oKey, ((Long) oValue).longValue());
            return oOrig;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Long remove(Object oKey)
            {
            Long oOrig = get(oKey);
            m_blm.remove((Binary) oKey);
            return oOrig;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public int size()
            {
            return m_blm.size();
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Iterator iterateKeys()
            {
            return m_blm.keys();
            }

        // ----- data members -----------------------------------------------

        /**
         * The underlying BinaryLongMap that provides the Binary-to-long
         * mapping storage for this Map implementation.
         */
        private final BinaryLongMap m_blm;
        }
    }
