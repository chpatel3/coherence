/*
 * Copyright (c) 2000, 2021, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package com.tangosol.util;

import com.oracle.coherence.common.base.Predicate;

import com.oracle.coherence.common.base.Blocking;
import com.oracle.coherence.common.collections.AbstractStableIterator;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.Iterator;

import java.util.List;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * A data structure that represents a series of BinaryLongMap instances. The
 * first (or "primary") instance is the <i>control</i> instance, in that only
 * it defines the key set, i.e. a Binary key can only be added to or removed
 * from the primary instance. Additional instances of BinaryLongMap can be
 * obtained from the MultiBinaryLongMap, but these instances are constrained
 * by the key set of the primary BinaryLongMap instance; it is illegal for
 * one of these instances to add a key that does not exist in the primary
 * instance, and removing a key simply sets the associated value to zero
 * (which has the effect of the key appearing to have been removed).
 * <p>
 * Since additions or removals from the primary instance has an effect on all
 * other instances, it is possible to listen to changes in the primary
 * instance's key set by implementing the BinaryLongMapListener interface.
 * This listener interface provides notifications of additions (after they
 * happen), removals (before they happen), and clear operations (both before
 * and after they happen). By using this interface, it is possible for
 * consumers of the additional BinaryLongMap instances to keep in sync with
 * the changes that are occurring to the primary BinaryLongMap instance.
 *
 * @author cp, rhl  2012-08-07
 */
public class MultiBinaryLongMap
    {
    // ----- constructors -------------------------------------------------

    /**
     * Construct a MultiBinaryLongMap.
     */
    public MultiBinaryLongMap()
        {
        this(null);
        }

    /**
     * Construct a MultiBinaryLongMap.
     *
     * @param lock  the ReentrantReadWriteLock to use for thread safety; if
     *              one is not provided, then one will be created
     */
    public MultiBinaryLongMap(ReentrantReadWriteLock lock)
        {
        f_rwLockMaster   = lock == null ? new ReentrantReadWriteLock() : lock;
        f_tree           = new BinaryRadixTree();
        f_blmPrimary     = new PrimaryBinaryLongMap();
        f_holderLeftover = new LeftoverLongMapHolder();
        }


    // ----- BinaryLongMap instance management ----------------------------

    /**
     * Obtain a reference to the primary BinaryLongMap instance.
     * <p>
     * The returned BinaryLongMap is thread-safe.
     *
     * @return the primary BinaryLongMap instance, which "owns" the keys that
     *         are represented in all of the BinaryLongMap instances, and thus
     *         can add or remove keys
     */
    public PrimaryBinaryLongMap getPrimaryBinaryLongMap()
        {
        return f_blmPrimary;
        }

    /**
     * Create a new BinaryLongMap that can be used to manage values for the
     * set of keys present in the primary BinaryLongMap instance.
     * <p>
     * The returned BinaryLongMap is thread-safe.
     *
     * @return a new BinaryLongMap instance that manages a mapping between
     *         <tt>Binary</tt> keys and <tt>long</tt> values
     */
    public BinaryLongMap createBinaryLongMap()
        {
        Lock lockXMaster = f_rwLockMaster.writeLock();
        lockXMaster.lock();
        try
            {
            ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

            Lock lockSMaster = f_rwLockMaster.readLock();
            Lock lockSThis   = new ChainedLock(lockSMaster, rwLock.readLock());
            Lock lockXThis   = new ChainedLock(lockSMaster, rwLock.writeLock());

            BinaryLongMap blmDelegate = createBinaryLongMapInternal();

            return new SafeBinaryLongMap(blmDelegate, lockSThis, lockXThis);
            }
        finally
            {
            lockXMaster.unlock();
            }
        }

    /**
     * Helper method to create a new delegating BinaryLongMap.  This method does
     * not return the created BinaryLongMap as a SafeBinaryLongMap and requires
     * that the caller holds the master exclusive lock for the MultiBinaryLongMap.
     *
     * @return a new delegating BinaryLongMap
     */
    protected DelegatingBinaryLongMap createBinaryLongMapInternal()
        {
        int iIndexNew = f_storage.addIndex();
        if (iIndexNew == 1 && f_holderLeftover.isEmpty())
            {
            // this is the first actual (non-primary) delegate BLM; need to
            // "inflate" the Primary BLM
            f_blmPrimary.inflateRep();
            }

        DelegatingBinaryLongMap blmDelegate =
                new DelegatingBinaryLongMap(f_tree, f_storage, iIndexNew);

        // grow the list of delegate maps if necessary
        for (int i = f_listDelegates.size(); i <= iIndexNew; i++)
            {
            f_listDelegates.add(null);
            }
        f_listDelegates.set(iIndexNew, blmDelegate);

        return blmDelegate;
        }

    /**
     * Create a new BinaryLongMap that can be used to manage numeric values of
     * the specified bit-width for the set of keys present in the primary
     * BinaryLongMap instance.  Values inserted into the returned BinaryLongMap
     * must fit within the specified number of bits (logically having the range
     * of: <tt>-(1 &lt;&lt; (cBits - 1) through (1 &lt;&lt; (cBits - 1))) - 1</tt>.  Values
     * returned from the resulting map will be sign-extended to the <tt>long</tt>
     * data-type and it is the responsibility of calling code intending to use
     * the map to store unsigned values to adjust/mask the sign accordingly.
     * <p>
     * The returned BinaryLongMap is thread-safe.
     *
     * @param cBits  the bit-width of values
     *
     * @return a new BinaryLongMap instance that manages a mapping between
     *         <tt>Binary</tt> keys and numeric values of the specified bit width
     */
    public BinaryLongMap createBitMap(int cBits)
        {
        Lock lockXMaster = f_rwLockMaster.writeLock();
        lockXMaster.lock();
        try
            {
            // check the leftover holder
            boolean       fLeftoverEmpty = f_holderLeftover.isEmpty();
            BinaryLongMap blmMasked      = f_holderLeftover.reserveMap(cBits);
            if (blmMasked != null)
                {
                if (fLeftoverEmpty && f_storage.getIndexCount() == 1)
                    {
                    // we are handing out the "leftover" bits for the first time;
                    // check to see if this is the first non-primary BLM, and if
                    // so, inflate the representation of the primary BLM
                    f_blmPrimary.inflateRep();
                    }

                return blmMasked;
                }

            // check to see if there are any already allocated indices that have
            // space for another masked BLM
            for (Iterator iter = f_listDelegates.iterator(); iter.hasNext(); )
                {
                Object o = iter.next();
                if (o instanceof MaskedLongMapHolder)
                    {
                    MaskedLongMapHolder holder = (MaskedLongMapHolder) o;
                    BinaryLongMap       blm    = holder.reserveMap(cBits);

                    if (blm != null)
                        {
                        return blm;
                        }
                    }
                }

            // no free holders exist; create a new index to be split by a
            // MaskedLongMapHolder
            DelegatingBinaryLongMap dblm   = createBinaryLongMapInternal();
            MaskedLongMapHolder     holder = new MaskedLongMapHolder(dblm, f_rwLockMaster.readLock());

            // overwrite the slot with the holder (it previously held the DBLM)
            f_listDelegates.set(dblm.getIndex(), holder);

            // this is guaranteed to succeed
            // Note: "safe" wrapping is handled by the MaskedLongMapHolder, as it
            //       must share the resource locks between the component maps that
            //       share the same underlying BLM
            return holder.reserveMap(cBits);
            }
        finally
            {
            lockXMaster.unlock();
            }
        }

    /**
     * Create a new BinaryLongMap that can be used to manage <tt>int</tt> (not
     * <tt>long</tt>) values for the set of keys present in the primary
     * BinaryLongMap instance.  Values inserted into the returned BinaryLongMap
     * must fit within the 32-bits (logically having the range of the <i>int</i>
     * datatype.  More formally, the value logically inserted into the map is
     * given by: <tt>(long) ((int) (lValue &amp; 0xFFFFFFFFL))</tt>.  Values
     * returned from the resulting map will be sign-extended to the <tt>long</tt>
     * data-type and it is the responsibility of calling code intending to use
     * the map to store unsigned values to adjust/mask the sign accordingly.
     * <p>
     * The returned BinaryLongMap is thread-safe.
     *
     * @return a new BinaryLongMap instance that manages a mapping between
     *         <tt>Binary</tt> keys and <tt>int</tt> values
     */
    public BinaryLongMap createBinaryIntMap()
        {
        return createBitMap(32);
        }

    /**
     * Release one of the maps previously returned from either {@link
     * #createBinaryLongMap()}, {@link #createBinaryIntMap()} or
     * {@link #createBitMap}. It is illegal to pass an instance not created by
     * this MultiBinaryLongMap, or to pass the primary BinaryLongMap instance.
     *
     * @param blm  a BinaryLongMap instance previously created by this
     *             MultiBinaryLongMap
     */
    public void releaseMap(BinaryLongMap blm)
        {
        if (blm instanceof PrimaryBinaryLongMap)
            {
            throw new IllegalArgumentException(
                    "The PrimaryBinaryLongMap cannot be released.");
            }

        Lock lockXMaster = f_rwLockMaster.writeLock();
        lockXMaster.lock();
        try
            {
            // everything handed out to clients is wrapped in a SafeBLM
            SafeBinaryLongMap blmSafe = (SafeBinaryLongMap) blm;

            releaseMapInternal(blmSafe.getMap());  // getMap() prevents a double-release

            // render the client's map reference unusable
            blmSafe.release();
            }
        finally
            {
            lockXMaster.unlock();
            }
        }

    /**
     * Helper method for releasing a BinaryLongMap created by this
     * MultiBinaryLongMap.  The caller is responsible for unwrapping any {@link
     * SafeBinaryLongMap "safe" wrappers} around the BinaryLongMap that is being
     * released, as well as holding the master exclusive lock for this
     * MultiBinaryLongMap.
     *
     * @param blm  the BinaryLongMap to be released
     */
    protected void releaseMapInternal(BinaryLongMap blm)
        {
        if (blm instanceof DelegatingBinaryLongMap)
            {
            DelegatingBinaryLongMap mapDelegate = (DelegatingBinaryLongMap) blm;
            int                     iIndex      = mapDelegate.getIndex();

            if (mapDelegate.f_blm == f_tree)
                {
                f_storage.removeIndex(iIndex);

                // if the last storage index (delegate BLM) has been removed and
                // the "leftover" masked BLM is empty, deflate the primary
                // BLM representation to the "compressed" form
                if (f_storage.getIndexCount() == 1 && f_holderLeftover.isEmpty())
                    {
                    // this is the first actual (non-primary) delegate BLM; need
                    // to "deflate" the Primary BLM
                    f_blmPrimary.deflateRep();
                    f_storage.clear();
                    }

                // compact the indices of all delegate maps (or int-map
                // holders) with indices greater than the one being released.
                for (Iterator iter = f_listDelegates.listIterator(iIndex + 1); iter.hasNext(); )
                    {
                    Object o = iter.next();
                    DelegatingBinaryLongMap dblm = o instanceof DelegatingBinaryLongMap
                            ? (DelegatingBinaryLongMap) o
                            : (DelegatingBinaryLongMap) ((MaskedLongMapHolder) o).getDelegateMap();

                    dblm.setIndex(dblm.getIndex() - 1);
                    }

                f_listDelegates.remove(iIndex);
                }
            else
                {
                throw new IllegalStateException(
                        "The BinaryLongMap was not created by this MultiBinaryLongMap.");
                }
            }
        else if (blm instanceof MaskedBinaryLongMap)
            {
            MaskedBinaryLongMap maskedBLM = (MaskedBinaryLongMap) blm;
            MaskedLongMapHolder blmHolder = maskedBLM.getHolder();

            // #releaseMap will clear (or drop) the map contents
            if (blmHolder.releaseMap(maskedBLM))
                {
                // the holder became empty (all bits free) as a result of releasing
                if (blmHolder == f_holderLeftover)
                    {
                    if (f_storage.getIndexCount() == 1)
                        {
                        // there are no additional storage-indexes in-use and all of the
                        // "leftover" bit-maps are released; "deflate" the Primary BLM
                        f_blmPrimary.deflateRep();
                        f_storage.clear();
                        }
                    }
                else
                    {
                    // the holder is backed by a delegate map which can be released
                    releaseMapInternal(blmHolder.getDelegateMap());
                    }
                }
           }
        else
            {
            // shouldn't happen...
            throw new IllegalStateException(
                    "Unknown BinaryLongMap implementation: " + blm.getClass().getName());
            }
        }

    // ----- listener management ------------------------------------------

    /**
     * Add the specified listener to listen to changes that occur to the
     * MultiBinaryLongMap's primary BinaryLongMap.
     *
     * @param listener  the listener to add
     */
    public void addListener(BinaryLongMapListener listener)
        {
        f_blmPrimary.addListener(listener);
        }

    /**
     * Remove the specified listener from listening to changes that occur to
     * the MultiBinaryLongMap's primary BinaryLongMap.
     *
     * @param listener  the listener to remove
     */
    public void removeListener(BinaryLongMapListener listener)
        {
        f_blmPrimary.removeListener(listener);
        }

    // ----- static helpers -----------------------------------------------

    /**
     * Extract a slot index from a long that was stored in a
     * BinaryLongMap.
     *
     * @param l  the long value stored in the BinaryLongMap
     *
     * @return the slot index to use with a LongStorage
     */
    protected static int decodeSlot(long l)
        {
        return ((int) (l & 0xFFFFFFFFL)) - 1;
        }

    /**
     * Extract the "remainder" portion (the portion not encoding the {@link
     * #decodeSlot(long) slot}) from the specified long that was stored in a
     * BinaryLongMap.
     *
     * @param l  the long value stored in the BinaryLongMap
     *
     * @return the remainder portion of the specified long value
     */
    protected static int decodeRemainder(long l)
        {
        return (int) ((l >> 32) & 0xFFFFFFFFL);
        }

    /**
     * Encode a slot index as a long that can be stored in a
     * BinaryLongMap. Only the least significant 32 bits are used.
     *
     * @param l      the long value stored in the BinaryLongMap
     * @param iSlot  the slot index for a LongStorage
     *
     * @return the long to store in the BinaryLongMap
     */
    protected static long encodeSlot(long l, int iSlot)
        {
        assert iSlot >= -1;

        // encodes -1 as 0 (i.e. it would be removed from the
        // BinaryRadixTree if there were no other data encoded in the
        // long)
        return (l & 0xFFFFFFFF00000000L) | (((long) (iSlot + 1)) & 0xFFFFFFFFL);
        }

    /**
     * Encode a 32-bit "remainder" value as a long that can be stored in a
     * BinaryLongMap.
     *
     * @param l           the long value stored in the BinaryLongMap
     * @param iRemainder  the 32-bit remainder to encode
     *
     * @return the long to store in the BinaryLongMap
     */
    protected static long encodeRemainder(long l, int iRemainder)
        {
        return (l & 0xFFFFFFFFL) | (((long) iRemainder) << 32);
        }

    // ----- data members -------------------------------------------------

    /**
     * The underlying storage for the keys and handles.
     */
    protected final BinaryRadixTree f_tree;

    /**
     * The underlying storage for the values.
     */
    protected final LongStorage f_storage = new LongStorage(1);

    /**
     * The primary BinaryLongMap instance.
     */
    protected final PrimaryBinaryLongMap f_blmPrimary;

    /**
     * The MaskedLongMapHolder representing the "leftover" space in the primary
     * BinaryLongMap.
     */
    protected final LeftoverLongMapHolder f_holderLeftover;

    /**
     * The read/write lock for concurrency control for the BinaryRadixTree.
     */
    protected final ReentrantReadWriteLock f_rwLockMaster;

    /**
     * The list of delegates (either a DelegateBLM, or a MaskedLongMapHolder)
     * indexed by the free
     */
    protected final ArrayList f_listDelegates = new ArrayList(8);


    // ----- inner interface: BinaryLongMapListener -----------------------

    /**
     * A listener that allows the owner of one of the delegating maps to
     * respond to changes in the key set made by an owner of another
     * delegating map.
     * <p>
     * Events may be raised to the BinaryLongMapListener while holding resource
     * locks on the primary BinaryLongMap, so the listener is not permitted to
     * further modify the contents of the primary BinaryLongMap synchronously.
     */
    public interface BinaryLongMapListener
            extends EventListener
        {
        /**
         * A notification that the primary map has added a new Binary/long
         * key/value mapping; this implicitly creates a new key/value mapping
         * in each delegating map, with the value being <tt>0L</tt>.
         *
         * @param binKey  the key that was added
         */
        public void keyAdded(Binary binKey);

        /**
         * A notification that the primary map is removing a Binary/long
         * key/value mapping; this implies that the existing key/value
         * mapping for the same Binary key in each delegating map is also
         * being removed.
         *
         * @param binKey  the key that is being removed
         */
        public void keyRemoving(Binary binKey);

        /**
         * A notification that the primary map is about to be cleared; this
         * implies that all of the existing key/value mappings in each
         * delegating map are about to be removed, but that no per-mapping
         * events will be generated in response to those removals.
         */
        public void mapClearing();

        /**
         * A notification that the primary map has been cleared; this implies
         * that all of the existing key/value mappings in each delegating map
         * have also been removed.
         */
        public void mapCleared();
        }


    // ----- inner class: AbstractDelegateBinaryLongMap -------------------

    /**
     * Abstract base class for BinaryLongMap implementations that delegate the
     * key and value storage to the MultiBinaryLongMap.
     */
    public static abstract class AbstractDelegateBinaryLongMap
            implements BinaryLongMap
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct an AbstractDelegateBinaryLongMap based on the specified
         * key tree, long storage, and storage index.
         *
         * @param blm     the BinaryLongMap holding the keys
         * @param store   the long storage
         * @param iIndex  the storage index
         */
        public AbstractDelegateBinaryLongMap(BinaryLongMap blm, LongStorage store, int iIndex)
            {
            f_blm    = blm;
            f_store  = store;
            m_iIndex = iIndex;
            }

        // ----- BinaryLongMap methods ------------------------------------

        /**
         * {@inheritDoc}
         */
        public long get(Binary binKey)
            {
            int iSlot = getSlot(binKey);
            return iSlot >= 0 ? f_store.get(iSlot, m_iIndex) : 0L;
            }

        /**
         * {@inheritDoc}
         */
        public void internKeys(Object o)
            {
            // delegate to the tree that actually holds the binary keys
            f_blm.internKeys(o);
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys()
            {
            return keys(null);
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys(final Predicate<Entry> predicate)
            {
            int             cSize    = size();
            final ArrayList listKeys = new ArrayList(predicate == null ? cSize : cSize >>> 8);

            // use the visit() framework to collect the keys; the "adapter"
            // visitor is made safe by ensuring that the passed predicate
            // is "safe"
            f_blm.visitAll(new DelegateEntryVisitor(new SafeEntryVisitor()
                            {
                            public void visit(Entry entry)
                                {
                                if (f_predicateSafe == null ||
                                    f_predicateSafe.evaluate(entry))
                                    {
                                    listKeys.add(entry.getKey());
                                    }
                                }

                            final SafePredicate f_predicateSafe =
                                        ensureSafePredicate(predicate);
                            }, /*fExisting*/ true));

            final Iterator<Binary> iter = listKeys.iterator();
            return new AbstractStableIterator<Binary>()
                {
                protected void advance()
                    {
                    if (iter.hasNext())
                        {
                        setNext(iter.next());
                        }
                    }

                protected void remove(Binary binPrev)
                    {
                    AbstractDelegateBinaryLongMap.this.remove(binPrev);
                    }
                };
            }

        /**
         * {@inheritDoc}
         */
        public void visit(Binary binKey, EntryVisitor visitor)
            {
            f_blm.visit(binKey, new DelegateEntryVisitor(ensureSafeVisitor(this, visitor), false));
            }

        /**
         * {@inheritDoc}
         */
        public void visitAll(EntryVisitor visitor)
            {
            f_blm.visitAll(new DelegateEntryVisitor(ensureSafeVisitor(this, visitor), true));
            }

        // ----- inner class: DelegateEntryVisitor ------------------------

        /**
         * DelegateEntryVisitor is a wrapper for an EntryVisitor that exposes
         * the logical entries of this AbstractDelegateBinaryLongMap.
         */
        protected class DelegateEntryVisitor
                implements SafeEntryVisitor
            {
            /**
             * Construct a DelegateEntryVisitor around the specified visitor.
             *
             * @param visitor    the underlying EntryVisitor
             * @param fExisting  true iff the underlying visitor should only
             *                   visit existing entries
             */
            public DelegateEntryVisitor(EntryVisitor visitor, boolean fExisting)
                {
                AbstractDelegateBinaryLongMap blm = AbstractDelegateBinaryLongMap.this;

                f_visitor   = ensureSafeVisitor(blm, visitor);
                f_iIndex    = getIndex();
                f_store     = blm.f_store;
                f_fExisting = fExisting;
                }

            // ----- EntryVisitor methods ---------------------------------

            /**
             * {@inheritDoc}
             */
            public void visit(Entry entry)
                {
                // the entry is a Node in the BRT whose value can be decoded
                // to tell us what slot of the LongStorage holds this
                // DelegatingBinaryLongMap's value; only keys that actually exist
                // in the "real" key tree (iSlot >= 0) may be visited here
                int iSlot = decodeSlot(entry.getValue());
                if (iSlot >= 0)
                    {
                    entry = getTempEntry(entry);

                    // check that the entry logically exists in this delegate
                    // BLM (or if we are allowed to visit non-existent entries)
                    if (!f_fExisting || entry.getValue() != 0L)
                        {
                        f_visitor.visit(entry);
                        }
                    }
                }

            // ----- DelegateEntryVisitor methods -------------------------

            /**
             * Return the temporary WrapperEntry, configured to wrap the
             * specified entry and associate it with the specified value.
             *
             * @param entryReal  the underlying entry to wrap
             *
             * @return the temporary WrapperEntry
             */
            private Entry getTempEntry(Entry entryReal)
                {
                f_entryTemp.reset(entryReal);
                return f_entryTemp;
                }

            // ----- inner class: WrapperEntry ----------------------------

            /**
             * WrapperEntry is used to wrap an Entry from the master key-tree
             * (which associates the key with the encoded slot-id) and
             * associate it with the logical value associated with this
             * AbstractDelegatingBinaryLongMap for the purposes of exposing
             * to the predicate.  The purpose of the WrapperEntry is to limit
             * the amount
             */
            protected class WrapperEntry
                    implements BinaryLongMap.Entry
                {
                // ----- entry methods --------------------------------

                /**
                 * {@inheritDoc}
                 */
                public Binary getKey()
                    {
                    return m_entry.getKey();
                    }

                /**
                 * {@inheritDoc}
                 */
                public long getValue()
                    {
                    // go through the front-door in case this is called after setValue
                    return f_store.get(decodeSlot(m_entry.getValue()), f_iIndex);
                    }

                /**
                 * {@inheritDoc}
                 */
                public BinaryLongMap.Entry setValue(long lValue)
                    {
                    f_store.put(decodeSlot(m_entry.getValue()), f_iIndex, lValue);

                    return this;
                    }

                // ----- WrapperEntry methods -------------------------

                /**
                 * Reset the WrapperEntry to represent a different underlying Entry.
                 *
                 * @param entry  the new entry to wrap
                 */
                public void reset(Entry entry)
                    {
                    m_entry = entry;
                    }

                /**
                 * The underlying Entry.
                 */
                protected BinaryLongMap.Entry m_entry;
                }

            // ----- data members -----------------------------------------

            /**
             * The (singleton) WrapperEntry that is used in the visitor iteration.
             */
            protected final transient WrapperEntry f_entryTemp = new WrapperEntry();

            /**
             * The wrapped (safe) predicate to apply to the logical entries
             * of this DelegatingBinaryLongMap.
             */
            protected final EntryVisitor f_visitor;

            /**
             * The index of the DelegatingBinaryLongMap.
             */
            protected final int f_iIndex;

            /**
             * The LongStorage.
             */
            protected final LongStorage f_store;

            /**
             * Flag indicating whether the underlying visitor should visit
             * only entries that logically exist.
             */
            protected final boolean f_fExisting;
            }

        // ----- helpers --------------------------------------------------

        /**
         * Determine the index into the LongStorage that this
         * DelegatingBinaryLongMap is assigned.
         *
         * @return the index into the LongStorage
         */
        protected int getIndex()
            {
            return m_iIndex;
            }

        /**
         * Set the index into the LongStorage assigned to this DelegatingBinaryLongMap.
         *
         * @param iIndex  the index into the LongStorage
         */
        protected void setIndex(int iIndex)
            {
            m_iIndex = iIndex;
            }

        /**
         * Return the slot index associated with the specified key.
         *
         * @param binKey  the key to return the slot for
         *
         * @return the slot index associated with the specified key
         */
        protected int getSlot(Binary binKey)
            {
            return decodeSlot(f_blm.get(binKey));
            }

        /**
         * Associate the passed value with the specified key, optionally
         * performing the operation only if the value currently associated
         * with the key matches the passed "old" value.
         *
         * @param iSlot      the slot whose value to update
         * @param lValueOld  the value that is assumed to be currently
         *                   associated with the key
         * @param lValueNew  the value to associate with the key
         * @param fForce     true iff the value should be associated regardless
         *                   of the old value
         *
         * @return if <tt>fForce</tt> is true, then the return value is true
         *         iff an item was added as the result of this operation; if
         *         <tt>fForce</tt> is false, then the return value is true iff
         *         the operation affected a change to the BinaryLongMap
         */
        protected boolean updateValue(int iSlot, long lValueOld, long lValueNew, boolean fForce)
            {
            int  iIndex     = m_iIndex;
            long lValuePrev = f_store.get(iSlot, iIndex);
            if (fForce || lValueOld == lValuePrev)
                {
                f_store.put(iSlot, iIndex, lValueNew);
                return !fForce || lValuePrev == 0L;
                }
            else
                {
                // no change (the old value didn't match)
                return false;
                }
            }

        // ----- data members ---------------------------------------------

        /**
         * The BinaryLongMap to delegate storage of this BinaryLongMap's keys to.
         */
        protected final BinaryLongMap f_blm;

        /**
         * The LongStorage to delegate storage of this BinaryLongMap's values to.
         */
        protected final LongStorage f_store;

        /**
         * The index into the LongStorage.
         */
        protected int m_iIndex;
        }


    // ----- inner class: DelegatingBinaryLongMap -------------------------

    /**
     * An implementation of BinaryLongMap that uses another BinaryLongMap to
     * store its keys and one index of a LongStorage to store its values.
     * This allows a number of BinaryLongMap instances to be created that
     * share a single other BinaryLongMap (e.g. BinaryRadixTree) instance.
     * <p>
     * This implementation differs substantially from the BinaryLongMap
     * interface's contract in the following ways:
     * <ol>
     *   <li>There is a concept of a <i>primary</i> BinaryLongMap instance,
     *       which <i>owns</i> the keys in the underlying BinaryRadixTree;
     *   <li>Only the primary can add and remove keys;
     *   <li>Other instances besides the primary will generate exceptions if
     *       they attempt to add keys that aren't already in the primary, and
     *       will store zero values when they attempt to remove keys (which will
     *       have the effect of removing that key from the <tt>keys()</tt>
     *       iterator for the instance);
     *   <li>The key iterator for the non-primary instances will only iterate
     *       keys with non-zero values (which is to be expected);
     *   <li>Since the primary instance can remove keys, and since that removal
     *       will also remove the same keys from all other dependent instances,
     *       consumers of the dependent instances should use a BinaryLongMapListener
     *       to listen to changes to the primary instance.
     * </ol>
     * In each of these cases, it should be apparent that using dependent
     * instances requires some knowledge of the specific implementation
     * behavior of the DelegatingBinaryLongMap, and thus the substitutability
     * of DelegatingBinaryLongMap for BinaryRadixTree cannot be done blindly.
     */
    public static class DelegatingBinaryLongMap
            extends AbstractDelegateBinaryLongMap
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a DelegatingBinaryLongMap.
         *
         * @param blm     the BinaryLongMap that holds the keys for this map
         * @param store   the LongStorage that holds the values for this map
         * @param iIndex  the index into the LongStorage for this map; must be &gt; 0
         */
        public DelegatingBinaryLongMap(BinaryLongMap blm, LongStorage store, int iIndex)
            {
            super(blm, store, iIndex);

            // index 0 is reserved for the "primary" BLM
            assert iIndex > 0;
            }

        // ----- public interface -----------------------------------------

        /**
         * {@inheritDoc}
         */
        public void put(Binary binKey, long lValue)
            {
            if (lValue == 0L)
                {
                remove(binKey);
                return;
                }

            int iSlot = getSlot(binKey);
            if (iSlot < 0)
                {
                throw new UnsupportedOperationException(
                        "Keys may not be added to the MultiBinaryLongMap through a delegating map.");
                }

            updateValue(iSlot, 0L, lValue, true);
            }

        /**
         * {@inheritDoc}
         */
        public boolean putIfAbsent(Binary binKey, long lValue)
            {
            if (lValue == 0L)
                {
                return false;
                }

            int iSlot = getSlot(binKey);
            if (iSlot < 0)
                {
                throw new UnsupportedOperationException();
                }

            return updateValue(iSlot, 0L, lValue, false);
            }

        /**
         * {@inheritDoc}
         */
        public boolean replace(Binary binKey, long lValueOld, long lValueNew)
            {
            if (lValueOld == 0L)
                {
                return putIfAbsent(binKey, lValueNew);
                }
            else if (lValueNew == 0L)
                {
                return remove(binKey, lValueOld);
                }
            else
                {
                int iSlot = getSlot(binKey);
                return iSlot >= 0 && updateValue(iSlot, lValueOld, lValueNew, false);
                }
            }

        /**
         * {@inheritDoc}
         */
        public void remove(Binary binKey)
            {
            int iSlot = getSlot(binKey);
            if (iSlot >= 0)
                {
                updateValue(iSlot, 0L, 0L, true);
                }
            }

        /**
         * {@inheritDoc}
         */
        public boolean remove(Binary binKey, long lValue)
            {
            if (lValue == 0L)
                {
                return false;
                }

            int iSlot = getSlot(binKey);
            return iSlot >= 0 && updateValue(iSlot, lValue, 0L, false);
            }

        /**
         * {@inheritDoc}
         */
        public void clear()
            {
            // clear() just sets all of the values for this BinaryLongMap
            // to zero (since this BinaryLongMap does not own the keys and
            // thus cannot actually remove them)
            BinaryLongMap blm    = f_blm;
            LongStorage   store  = f_store;
            int           iIndex = m_iIndex;
            assert iIndex > 0;
            for (Iterator<Binary> iter = blm.keys(); iter.hasNext(); )
                {
                store.put(decodeSlot(blm.get(iter.next())), iIndex, 0L);
                }
            }

        /**
         * {@inheritDoc}
         */
        public int size()
            {
            int iIndex = m_iIndex;

            assert iIndex > 0;
            return f_store.countValues(iIndex);
            }
        }

    /**
     * A BinaryLongMap implementation that logically represents the "primary"
     * BinaryLongMap of the containing MultiBinaryLongMap.
     * <p>
     * The implementation dynamically switches between a "compressed" form
     * which uses the real key-tree to store the primary BLM's logical contents,
     * and a "delegating" multi-form which uses the LongStorage to associate
     * multiple long values (split over multiple delegating BinaryLongMap
     * instances) with each key.
     * <p>
     * Note: the reference to the "wrapped" underlying BLM can change dynamically
     *       as the containing MultiBinaryLongMap grows or shrinks in size.  The
     *       {@link ValidatingLock} provides a memory-barrier for this field,
     *       which is not otherwise declared "volatile".
     */
    public class PrimaryBinaryLongMap
            extends WrapperBinaryLongMap
            implements BinaryLongMapListener
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a PrimaryBinaryLongMap.
         */
        public PrimaryBinaryLongMap()
            {
            super(null);

            BinaryLongMap blmKeys = MultiBinaryLongMap.this.f_tree;

            f_rwLockMaster = MultiBinaryLongMap.this.f_rwLockMaster;

            f_pblmCompressed = new CompressedPrimaryBinaryLongMap(blmKeys, f_rwLockMaster);
            f_pblmMulti      = new DelegatingPrimaryBinaryLongMap(blmKeys, MultiBinaryLongMap.this.f_storage, f_rwLockMaster);

            // start with the compressed implementation
            setMap(f_pblmCompressed);
            }

        // ----- PrimaryBinaryLongMap methods -----------------------------

        /**
         * Inflate the underlying representation of the PrimaryBinaryLongMap
         * to use the LongStorage to support multiple delegating BinaryLongMap
         * instances.
         * <p>
         * Note: it is the caller's responsibility to hold the master exclusive
         *       lock during this operation
         */
        protected void inflateRep()
            {
            f_tree.visitAll(new SafeEntryVisitor()
                    {
                    public void visit(Entry entry)
                        {
                        int iSlot = f_storage.reserveSlot();

                        f_storage.put(iSlot, /*iIndex*/0, entry.getValue());

                        entry.setValue(encodeSlot(0L, iSlot));
                        }
                    });

            setMap(f_pblmMulti);
            }

        /**
         * Deflate the underlying representation of the PrimaryBinaryLongMap
         * to use the main key-tree to associate a single long value with each
         * key (logically represented by this PrimaryBinaryLongMap).
         * <p>
         * Note: it is the caller's responsibility to hold the master exclusive
         *       lock during this operation
         */
        protected void deflateRep()
            {
            f_tree.visitAll(new SafeEntryVisitor()
                    {
                    public void visit(Entry entry)
                        {
                        int  iSlot  = decodeSlot(entry.getValue());
                        long lValue = f_storage.get(iSlot, /*iIndex*/0);

                        entry.setValue(lValue);
                        }
                    });

            setMap(f_pblmCompressed);
            }

        // ----- listener management --------------------------------------

        /**
         * Add the specified listener to the collection of listeners that this
         * DelegatingPrimaryBinaryLongMap dispatches notifications to.
         *
         * @param listener  the listener to add to the collection of listeners
         *                  that this DelegatingPrimaryBinaryLongMap dispatches
         *                  notifications to
         */
        public void addListener(BinaryLongMapListener listener)
            {
            Lock lockXMaster = f_rwLockMaster.writeLock();
            lockXMaster.lock();
            try
                {
                if (listener == null)
                    {
                    throw new IllegalArgumentException("listener must not be null");
                    }

                f_listeners.add(listener);
                }
            finally
                {
                lockXMaster.unlock();
                }
            }

        /**
         * Remove the specified listener from the collection of listeners that
         * this DelegatingPrimaryBinaryLongMap dispatches notifications to.
         *
         * @param listener  the listener to remove from the collection of
         *                  listeners that this DelegatingPrimaryBinaryLongMap
         *                  dispatches notifications to
         */
        public void removeListener(BinaryLongMapListener listener)
            {
            Lock lockXMaster = f_rwLockMaster.writeLock();
            lockXMaster.lock();
            try
                {
                if (listener == null)
                    {
                    throw new IllegalArgumentException("listener must not be null");
                    }

                f_listeners.remove(listener);
                }
            finally
                {
                lockXMaster.unlock();
                }
            }

        // ----- BinaryLongMap methods ------------------------------------

        /**
         * {@inheritDoc}
         */
        public long get(Binary binKey)
            {
            do
                {
                try
                    {
                    return super.get(binKey);
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public void put(Binary binKey, long lValue)
            {
            do
                {
                try
                    {
                    super.put(binKey, lValue);
                    return;
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public boolean putIfAbsent(Binary binKey, long lValue)
            {
            do
                {
                try
                    {
                    return super.putIfAbsent(binKey, lValue);
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public boolean replace(Binary binKey, long lValueOld, long lValueNew)
            {
            do
                {
                try
                    {
                    return super.replace(binKey, lValueOld, lValueNew);
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public void remove(Binary binKey)
            {
            do
                {
                try
                    {
                    super.remove(binKey);
                    return;
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public boolean remove(Binary binKey, long lValue)
            {
            do
                {
                try
                    {
                    return super.remove(binKey, lValue);
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public void clear()
            {
            do
                {
                try
                    {
                    super.clear();
                    return;
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public int size()
            {
            do
                {
                try
                    {
                    return super.size();
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys()
            {
            do
                {
                try
                    {
                    return super.keys();
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys(Predicate<Entry> predicate)
            {
            do
                {
                try
                    {
                    return super.keys(predicate);
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public void visit(Binary binKey, EntryVisitor visitor)
            {
            do
                {
                try
                    {
                    super.visit(binKey, visitor);
                    return;
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public void visitAll(EntryVisitor visitor)
            {
            do
                {
                try
                    {
                    super.visitAll(visitor);
                    return;
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        /**
         * {@inheritDoc}
         */
        public void internKeys(Object o)
            {
            do
                {
                try
                    {
                    super.internKeys(o);
                    return;
                    }
                catch (RetryException e) {}
                }
            while (true);
            }

        // ----- BinaryLongMapListener interface --------------------------

        /**
         * {@inheritDoc}
         */
        public void keyAdded(Binary binKey)
            {
            for (EventListener listener : f_listeners.listeners())
                {
                ((BinaryLongMapListener) listener).keyAdded(binKey);
                }
            }

        /**
         * {@inheritDoc}
         */
        public void keyRemoving(Binary binKey)
            {
            for (EventListener listener : f_listeners.listeners())
                {
                ((BinaryLongMapListener) listener).keyRemoving(binKey);
                }
            }

        /**
         * {@inheritDoc}
         */
        public void mapClearing()
            {
            for (EventListener listener : f_listeners.listeners())
                {
                ((BinaryLongMapListener) listener).mapClearing();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void mapCleared()
            {
            for (EventListener listener : f_listeners.listeners())
                {
                ((BinaryLongMapListener) listener).mapCleared();
                }
            }

        // ----- inner class: ValidatingLock ------------------------------

        /**
         * ValidatingLock is a Lock implementation specialized for the
         * PrimaryBinaryLongMap that validates after every lock acquisition that
         * the underlying BinaryLongMap implementation did not change.
         * <p>
         * Note: ValidatingLock is assumed to be used by the PrimaryBinaryLongMap
         *       to wrap lock implementations that are dependent (chained) to the
         *       "master" RW-lock.  As the master RW-lock is used to protect any
         *       structural changes, it also implicitly serves as a JMM memory
         *       barrier that guarantees a "flush" of the map implementation
         *       reference.
         */
        protected class ValidatingLock
                implements Lock
            {
            /**
             * Construct a ValidatingLock backed by the specified Lock.
             *
             * @param lock  the underlying lock
             */
            public ValidatingLock(Lock lock)
                {
                f_lock = lock;
                }

            /**
             * Construct a ValidatingLock backed by the specified Lock.
             *
             * @param blm   the primary BinaryLongMap implementation to validate
             * @param lock  the underlying lock
             */
            public ValidatingLock(BinaryLongMap blm, Lock lock)
                {
                m_blmImpl = blm;
                f_lock    = lock;
                }

            // ----- Lock methods -----------------------------------------

            /**
             * {@inheritDoc}
             */
            public void unlock()
                {
                f_lock.unlock();
                }

            /**
             * {@inheritDoc}
             */
            public void lock()
                {
                f_lock.lock();

                validate();
                }

            /**
             * {@inheritDoc}
             */
            public void lockInterruptibly() throws InterruptedException
                {
                Blocking.lockInterruptibly(f_lock);

                validate();
                }

            /**
             * {@inheritDoc}
             */
            public boolean tryLock()
                {
                if (f_lock.tryLock())
                    {
                    validate();
                    return true;
                    }
                else
                    {
                    return false;
                    }
                }

            /**
             * {@inheritDoc}
             */
            public boolean tryLock(long cTime, TimeUnit unit) throws InterruptedException
                {
                if (Blocking.tryLock(f_lock, cTime, unit))
                    {
                    validate();
                    return true;
                    }
                else
                    {
                    return false;
                    }
                }

            /**
             * {@inheritDoc}
             */
            public Condition newCondition()
                {
                throw new UnsupportedOperationException();
                }

            // ----- internal methods -------------------------------------

            /**
             * Return the primary BinaryLongMap implementation validate by this
             * ValidatingLock.
             *
             * @return the primary BinaryLongMap implementation validated by
             *         this ValidatingLock
             */
            protected BinaryLongMap getImplMap()
                {
                return m_blmImpl;
                }

            /**
             * Set the primary BinaryLongMap implementation to be validated by
             * this ValidatingLock.
             *
             * @param blmImpl  the primary BinaryLongMap implementation
             */
            protected void setImplMap(BinaryLongMap blmImpl)
                {
                m_blmImpl = blmImpl;
                }

            /**
             * Validate that the BinaryLongMap associated with this ValidatingLock
             * is still the implementation map of the containing
             * PrimaryBinaryLongMap.
             * <p>
             * Callers are required to hold the lock.
             * Note: the lock provides a memory barrier ensuring visibility of
             *       updates to the map implementation of the outer
             *       PrimaryBinaryLongMap.
             */
            protected void validate()
                {
                if (PrimaryBinaryLongMap.this.getMap() != getImplMap())
                    {
                    f_lock.unlock();
                    throw new RetryException();
                    }
                }

            // ----- data members -----------------------------------------

            /**
             * The underlying lock.
             */
            protected final Lock f_lock;

            /**
             * The primary BinaryLongMap implementation to check for
             */
            protected BinaryLongMap m_blmImpl;
            }

        // ----- inner class: CompressedPrimaryBinaryLongMap --------------

        /**
         * The CompressedPrimaryBinaryLongMap is a trivial "Safe" wrapper around
         * the main key-tree, which associates keys to their single (primary)
         * long value.
         */
        protected class CompressedPrimaryBinaryLongMap
                extends SafeBinaryLongMap
            {
            // ----- constructors -----------------------------------------

            /**
             * Construct a CompressedPrimaryBinaryLongMap.
             *
             * @param blmKeyTree  the BinaryLongMap holding the keys for the
             *                    outer MultiBinaryLongMap
             * @param rwLock      the master ReadWriteReentrantLock
             */
            public CompressedPrimaryBinaryLongMap(BinaryLongMap blmKeyTree, ReentrantReadWriteLock rwLock)
                {
                super(blmKeyTree, new ValidatingLock(rwLock.readLock()), new ValidatingLock(rwLock.writeLock()));

                // idiocy required by Java source compiler prevents capturing
                // a reference to the ValidatingLocks in the super() expression
                ((ValidatingLock) f_lockShared).setImplMap(this);
                ((ValidatingLock) f_lockExclusive).setImplMap(this);
                }

            // ----- BinaryLongMap methods --------------------------------

            /**
             * {@inheritDoc}
             */
            public void put(Binary binKey, long lValue)
                {
                // override instead of calling the super impl in order to
                // handle BinaryLongMapListener events without an additional
                // (reentrant) locking imposed by the SafeBinaryLongMap
                f_lockExclusive.lock();
                try
                    {
                    BinaryLongMap blm = getMap();

                    boolean fNew = lValue != 0L && blm.get(binKey) == 0L;
                    blm.put(binKey, lValue);

                    if (fNew)
                        {
                        keyAdded(binKey);
                        }
                    }
                finally
                    {
                    f_lockExclusive.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public boolean putIfAbsent(Binary binKey, long lValue)
                {
                // override instead of calling the super impl in order to
                // handle BinaryLongMapListener events without an additional
                // (reentrant) locking imposed by the SafeBinaryLongMap
                f_lockExclusive.lock();
                try
                    {
                    if (getMap().putIfAbsent(binKey, lValue))
                        {
                        keyAdded(binKey);
                        return true;
                        }
                    else
                        {
                        return false;
                        }
                    }
                finally
                    {
                    f_lockExclusive.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void remove(Binary binKey)
                {
                // override instead of calling the super impl in order to
                // handle BinaryLongMapListener events without an additional
                // (reentrant) locking imposed by the SafeBinaryLongMap
                f_lockExclusive.lock();
                try
                    {
                    BinaryLongMap blm = getMap();
                    if (blm.get(binKey) != 0L)
                        {
                        keyRemoving(binKey);
                        }

                    blm.remove(binKey);
                    }
                finally
                    {
                    f_lockExclusive.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public boolean remove(Binary binKey, long lValue)
                {
                // override instead of calling the super impl in order to
                // handle BinaryLongMapListener events without an additional
                // (reentrant) locking imposed by the SafeBinaryLongMap
                f_lockExclusive.lock();
                try
                    {
                    BinaryLongMap blm = getMap();
                    if (lValue != 0L && blm.get(binKey) == lValue)
                        {
                        keyRemoving(binKey);

                        blm.remove(binKey);
                        return true;
                        }
                    else
                        {
                        return false;
                        }
                    }
                finally
                    {
                    f_lockExclusive.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void clear()
                {
                // override instead of calling the super impl in order to
                // handle BinaryLongMapListener events without an additional
                // (reentrant) locking imposed by the SafeBinaryLongMap
                f_lockExclusive.lock();
                try
                    {
                    mapClearing();

                    getMap().clear();

                    mapCleared();
                    }
                finally
                    {
                    f_lockExclusive.unlock();
                    }
                }
            }

        // ----- inner class: DelegatingPrimaryBinaryLongMap ----------------------------

        /**
         * An extension to the DelegatingBinaryLongMap for the "primary" or
         * "owning" BinaryLongMap that is responsible for adding and removing
         * keys.
         */
        protected class DelegatingPrimaryBinaryLongMap
                extends AbstractDelegateBinaryLongMap
            {
            // ----- constructors ---------------------------------------------

            /**
             * Construct a DelegatingPrimaryBinaryLongMap.
             *
             * @param blmKeyTree    the BinaryLongMap that holds the keys for this map
             * @param store         the LongStorage that holds the values for this map
             * @param rwLockMaster  the "master" ReentrantReadWriteLock protecting the
             *                      underlying MLBM BRT and LongStorage structures
             */
            public DelegatingPrimaryBinaryLongMap(BinaryLongMap blmKeyTree,
                                                  LongStorage store,
                                                  ReentrantReadWriteLock rwLockMaster)
                {
                super(blmKeyTree, store, 0);

                ReentrantReadWriteLock rwLockThis = new ReentrantReadWriteLock();

                f_lockXMaster = new ValidatingLock(this, rwLockMaster.writeLock());
                f_lockSThis   = new ValidatingLock(this, new ChainedLock(rwLockMaster.readLock(), rwLockThis.readLock()));
                f_lockXThis   = new ValidatingLock(this, new ChainedLock(rwLockMaster.readLock(), rwLockThis.writeLock()));
                }

            // ----- public interface -----------------------------------------

            /**
             * {@inheritDoc}
             */
            public long get(Binary binKey)
                {
                f_lockSThis.lock();
                try
                    {
                    return super.get(binKey);
                    }
                finally
                    {
                    f_lockSThis.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void put(Binary binKey, long lValue)
                {
                if (lValue == 0L)
                    {
                    // remove(Binary) will lock the master exclusive lock
                    remove(binKey);
                    }
                else
                    {
                    doPut(binKey, lValue, /*fOnlyIfAbsent*/ false);
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void remove(Binary binKey)
                {
                // Note: we directly lock the master here (rather than locking the
                //       shared-exclusive and then "promoting"), as we expect most
                //       removes to succeed.
                f_lockXMaster.lock();
                try
                    {
                    boolean fRemoved = removeValue(binKey, 0L);
                    if (fRemoved)
                        {
                        checkCompaction();
                        }
                    }
                finally
                    {
                    f_lockXMaster.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public boolean remove(Binary binKey, long lValue)
                {
                if (lValue == 0L)
                    {
                    return false;
                    }

                // Note: we directly lock the master here (rather than locking the
                //       shared-exclusive and then "promoting"), as we expect most
                //       removes to succeed.
                f_lockXMaster.lock();
                try
                    {
                    boolean fRemoved = removeValue(binKey, lValue);
                    if (fRemoved)
                        {
                        checkCompaction();
                        }

                    return fRemoved;
                    }
                finally
                    {
                    f_lockXMaster.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public boolean putIfAbsent(Binary binKey, long lValue)
                {
                return lValue != 0L && doPut(binKey, lValue, /*fOnlyIfAbsent*/ true);
                }

            /**
             * {@inheritDoc}
             */
            public boolean replace(Binary binKey, long lValueOld, long lValueNew)
                {
                if (lValueOld == 0L)
                    {
                    return putIfAbsent(binKey, lValueNew);
                    }
                else if (lValueNew == 0L)
                    {
                    return remove(binKey, lValueOld);
                    }
                else
                    {
                    f_lockXThis.lock();
                    try
                        {
                        int iSlot = getSlot(binKey);
                        return iSlot >= 0 && updateValue(iSlot, lValueOld, lValueNew, false);
                        }
                    finally
                        {
                        f_lockXThis.unlock();
                        }
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void clear()
                {
                f_lockXMaster.lock();
                try
                    {
                    // this is a "real" clear
                    mapClearing();

                    f_blm.clear();
                    f_store.clear();

                    mapCleared();
                    }
                finally
                    {
                    f_lockXMaster.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public int size()
                {
                f_lockSThis.lock();
                try
                    {
                    // for the primary map where all logical keys are reflected in the
                    // key tree, we can optimize by delegating
                    return f_blm.size();
                    }
                finally
                    {
                    f_lockSThis.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public Iterator<Binary> keys()
                {
                f_lockSThis.lock();
                try
                    {
                    // for the primary map where all logical keys are reflected in the
                    // key tree, we can optimize by delegating
                    return f_blm.keys();
                    }
                finally
                    {
                    f_lockSThis.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public Iterator<Binary> keys(Predicate<Entry> predicate)
                {
                f_lockSThis.lock();
                try
                    {
                    return super.keys(predicate);
                    }
                finally
                    {
                    f_lockSThis.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void visit(final Binary binKey, EntryVisitor visitor)
                {
                Lock lock = f_lockXThis;
                lock.lock();
                try
                    {
                    boolean fMissing = f_blm.get(binKey) == 0L;
                    if (fMissing)
                        {
                        lock.unlock();
                        lock = f_lockXMaster;
                        lock.lock();

                        fMissing = f_blm.get(binKey) == 0L;
                        }

                    if (fMissing)
                        {
                        visitor.visit(new Entry()
                            {
                            public Binary getKey()
                                {
                                return binKey;
                                }

                            public long getValue()
                                {
                                return DelegatingPrimaryBinaryLongMap.this.get(binKey);
                                }

                            public Entry setValue(long lValue)
                                {
                                DelegatingPrimaryBinaryLongMap.this.put(binKey, lValue);
                                return this;
                                }
                            });
                        }
                    else
                        {
                        // the key exists in the real key-tree; the "default" delegating
                        // #visit implementation will suffice
                        super.visit(binKey, visitor);
                        }
                    }
                finally
                    {
                    lock.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void visitAll(EntryVisitor visitor)
                {
                f_lockXThis.lock();
                try
                    {
                    super.visitAll(visitor);
                    }
                finally
                    {
                    f_lockXThis.unlock();
                    }
                }

            /**
             * {@inheritDoc}
             */
            public void internKeys(Object o)
                {
                f_lockSThis.lock();
                try
                    {
                    super.internKeys(o);
                    }
                finally
                    {
                    f_lockSThis.unlock();
                    }
                }

            // ----- internal -------------------------------------------------

            /**
             * Helper method for {@link #put} and {@link #putIfAbsent} operations.
             *
             * @param binKey         the key to add to the map
             * @param lValue         the value to be associated
             * @param fOnlyIfAbsent  true iff the key/value should be associated only
             *                       if the key was previously absent
             *
             * @return true iff the contents of the binary long map were changed
             */
            protected boolean doPut(Binary binKey, long lValue, boolean fOnlyIfAbsent)
                {
                Lock lock = f_lockXThis;
                lock.lock();
                try
                    {
                    int     iSlot    = getSlot(binKey);
                    boolean fMissing = iSlot < 0;
                    if (fMissing)
                        {
                        // drop the shared-exclusive to upgrade to the
                        // master-exclusive, and re-check the condition
                        lock.unlock();

                        lock = f_lockXMaster;
                        lock.lock();

                        iSlot = getSlot(binKey);
                        fMissing = iSlot < 0;
                        }

                    if (fMissing)
                        {
                        // we must hold the master lock here (see above)
                        addValue(binKey, lValue);
                        return true;
                        }
                    else if (fOnlyIfAbsent)
                        {
                        return false;
                        }
                    else
                        {
                        updateValue(iSlot, 0L, lValue, true);
                        return true;
                        }
                    }
                finally
                    {
                    lock.unlock();
                    }
                }

            /**
             * Add the specified key to the BinaryLongMap, and associate the specified
             * value with it in the primary BLM.
             * <p>
             * The caller is responsible for holding the exclusive lock protecting
             * the key tree and LongStorage.
             *
             * @param binKey     the key to add to the BinaryLongMap
             * @param lValueNew  the new value to associate with the key
             */
            protected void addValue(Binary binKey, long lValueNew)
                {
                LongStorage   store  = f_store;
                BinaryLongMap blm    = f_blm;
                long          lValue = blm.get(binKey);

                assert decodeRemainder(lValue) == 0L;
                assert decodeSlot(lValue) < 0;

                int iSlot = store.reserveSlot();
                blm.put(binKey, encodeSlot(lValue, iSlot));
                store.put(iSlot, 0, lValueNew);

                // issue the add event
                keyAdded(binKey);
                }

            /**
             * Remove the specified key from the BinaryLongMap, optionally
             * performing the operation only if the value currently associated
             * with the key matches the passed "old" value.
             * <p>
             * The caller is responsible for holding the exclusive lock protecting
             * the key tree and LongStorage.
             *
             * @param binKey  the key
             * @param lValue  the value that is assumed to be currently associated
             *                with the key, or 0 if the key should be removed
             *                unconditionally
             *
             * @return true iff the operation affected a change to the BinaryLongMap
             */
            protected boolean removeValue(Binary binKey, long lValue)
                {
                BinaryLongMap blm   = f_blm;
                int           iSlot = decodeSlot(blm.get(binKey));
                if (iSlot < 0)
                    {
                    return false;
                    }

                if (lValue == 0L || lValue == f_store.get(iSlot, 0))
                    {
                    // issue the remove event
                    keyRemoving(binKey);

                    blm.remove(binKey);
                    f_store.releaseSlot(iSlot);

                    return true;
                    }
                else
                    {
                    // value didn't match, so don't remove
                    return false;
                    }
                }

            /**
             * Check to see whether or not the LongStorage slots should be compacted.
             * <p>
             * The caller is responsible for holding the exclusive lock protecting
             * the key tree and LongStorage.
             */
            protected void checkCompaction()
                {
                if (f_store.isCompressionIndicated())
                    {
                    f_store.compressBegin();
                    try
                        {
                        f_blm.visitAll(new SafeEntryVisitor()
                            {
                            public void visit(Entry entry)
                                {
                                long lTicket  = entry.getValue();
                                int  iSlotCur = decodeSlot(lTicket);
                                int  iSlotNew = f_store.compressRelocate(iSlotCur);

                                if (iSlotCur != iSlotNew)
                                    {
                                    // setValue may have a cost, so only do it if the
                                    // slot was actually relocated
                                    entry.setValue(encodeSlot(lTicket, iSlotNew));
                                    }
                                }
                            });
                        }
                    finally
                        {
                        f_store.compressEnd();
                        }
                    }
                }

            // ----- data members ---------------------------------------------

            /**
             * The (exclusive) lock that protects the data-structures for the
             * MultiBinaryLongMap.
             */
            protected final Lock f_lockXMaster;

            /**
             * The shared lock that protects the logical contents of the primary
             * BinaryLongMap.
             */
            protected final Lock f_lockSThis;

            /**
             * The exclusive lock that protects the logical contents of the primary
             * BinaryLongMap.
             */
            protected final Lock f_lockXThis;
            }

        // ----- inner class: RetryException ------------------------------

        /**
         * Marker Exception thrown to indicate that the underlying BinaryLongMap
         * implementation has concurrently changed and the operation needs to
         * be resubmitted by the PrimaryBinaryLongMap.
         */
        protected class RetryException
                extends RuntimeException
            {}


        // ----- data members ---------------------------------------------

        /**
         * The "master" read-write lock for the MultiBinaryLongMap.
         */
        protected final ReentrantReadWriteLock f_rwLockMaster;

        /**
         * The "compressed" PBLM implementation.
         */
        protected final CompressedPrimaryBinaryLongMap f_pblmCompressed;

        /**
         * The "full" or "multi-aware" PBLM implementation.
         */
        protected final BinaryLongMap f_pblmMulti;

        /**
         * The listeners to notify of changes to the BinaryLongMap.
         */
        protected final Listeners f_listeners = new Listeners();
        }


    // ----- inner class: MaskedLongMapHolder ------------------------------

    /**
     * MaskedLongMapHolder is used to produce {@link MaskedBinaryLongMap}
     * instances backed by a shared BinaryLongMap. MaskedBinaryLongMap instances
     * created by this holder are thread-safe and are all protected by the same
     * shared and exclusive locks.
     */
    protected static class MaskedLongMapHolder
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a MaskedLongMapHolder backed by the specified blm.
         *
         * @param blm          the BinaryLongMap that the component masked
         *                     maps are backed by
         * @param lockSMaster  the shared master-lock for the MultiBinaryLongMap
         */
        public MaskedLongMapHolder(BinaryLongMap blm, Lock lockSMaster)
            {
            this(blm, lockSMaster, 0L);
            }

        /**
         * Construct a MaskedLongMapHolder backed by the specified blm with the
         * specified reserved bit-mask.
         *
         * @param blm            the BinaryLongMap that the component masked
         *                       maps are backed by
         * @param lockSMaster    the shared master-lock for the MultiBinaryLongMap
         * @param lMaskReserved  the bit-mask representing the reserved bits
         */
        public MaskedLongMapHolder(BinaryLongMap blm, Lock lockSMaster, long lMaskReserved)
            {
            ReentrantReadWriteLock rwLockThis = new ReentrantReadWriteLock();

            f_lockS = new ChainedLock(lockSMaster, rwLockThis.readLock());
            f_lockX = new ChainedLock(lockSMaster, rwLockThis.writeLock());
            f_blm   = blm;

            m_lMaskFree     = ~lMaskReserved;
            f_lMaskReserved = lMaskReserved;
            }

        // ----- accessors ------------------------------------------------

        /**
         * Return the BinaryLongMap that provides the underlying storage for
         * this MaskedLongMapHolder.
         *
         * @return the underlying BinaryLongMap
         */
        public BinaryLongMap getDelegateMap()
            {
            return f_blm;
            }

        // ----- MaskedLongMapHolder methods ------------------------------

        /**
         * Reserve and return a masked BLM from this MaskedLongMapHolder if
         * available, or null otherwise.  Reserved maps must be {@link #releaseMap
         * released} when no longer needed.
         *
         * @param cBits  the number of bits to use to store each value
         *
         * @return a masked BLM, or null
         */
        public BinaryLongMap reserveMap(int cBits)
            {
            assert cBits < 64;
            assert cBits >= 0;

            long lMaskFree = m_lMaskFree;
            if (BitHelper.countBits(lMaskFree) < cBits)
                {
                // there no room at all
                return null;
                }

            // see if there is room available anywhere
            long lMaskNew = (1L << cBits) - 1;
            int  cShift   = -1;
            for (int c = 0; c + cBits <= 64; c++, lMaskNew <<= 1)
                {
                if ((lMaskFree | lMaskNew) == lMaskFree)
                    {
                    cShift = c;
                    break;
                    }
                }

            // TODO - consider implementing a number of split-bit BLM impls
            //        to avoid having to pay the cost of compaction and instead
            //        construct the values "on-the-fly" using masks and shifts

            List<MaskedBinaryLongMap> listBLMs = m_listBLMs;
            if (cShift < 0)
                {
                // there is no contiguous room; compact all of the masked maps
                for (MaskedBinaryLongMap blm : listBLMs)
                    {
                    long nMaskLow    = (1L << blm.m_cShift) - 1;
                    int  ofFree      = BitHelper.indexOfMSB(~m_lMaskFree & nMaskLow) + 1;
                    if (ofFree < blm.m_cShift)
                        {
                        shiftMaskedMap(blm, blm.m_cShift - ofFree);
                        }
                    }

                cShift = BitHelper.indexOfMSB(~m_lMaskFree) + 1;
                }

            assert cShift >= 0;

            MaskedBinaryLongMap blmNew = instantiateMaskedBinaryLongMap(cShift, cBits);
            for (int iNew = 0, c = listBLMs.size(); true; iNew++)
                {
                if (iNew < c)
                    {
                    MaskedBinaryLongMap blmCurr = listBLMs.get(iNew);
                    if (cShift < blmCurr.m_cShift)
                        {
                        listBLMs.add(iNew, blmNew);
                        break;
                        }
                    }
                else
                    {
                    listBLMs.add(blmNew);
                    break;
                    }
                }

            // update the free bit-mask
            m_lMaskFree &= ~(((1L << cBits) - 1) << cShift);

            // create a new "safe" wrapper for each client; see #releaseMap
            return new SafeBinaryLongMap(blmNew, f_lockS, f_lockX);
            }

        /**
         * Release a masked BLM that was {@link #reserveMap reserved} from
         * this MaskedLongMapHolder.
         *
         * @param blm  the masked BLM to release
         *
         * @return true iff the MaskedLongMapHolder becomes empty as a result
         */
        public boolean releaseMap(MaskedBinaryLongMap blm)
            {
            if (blm.getHolder() == this)
                {
                blm.clear();
                m_lMaskFree |= blm.f_nMask;

                m_listBLMs.remove(blm);

                return isEmpty();
                }
            else
                {
                // the map wasn't part of this holder
                throw new IllegalArgumentException();
                }
            }

        /**
         * Return true iff all non-reserved bits are free (not in-use).
         *
         * @return true iff all non-reserved bits are free (not in use)
         */
        public boolean isEmpty()
            {
            return (m_lMaskFree ^ f_lMaskReserved) == 0xFFFFFFFFFFFFFFFFL;
            }

        // ----- helpers --------------------------------------------------

        /**
         * Factory method for instantiating MaskedBinaryLongMap instances
         * associated with this MaskedLongMapHolder.
         *
         * @param cShift  the shift position of the masked BLM
         * @param cBits   the bit-width of values represented by the BLM
         *
         * @return a MaskedBinaryLongMap with the specified shift and mask
         *         associated with this LongMapHolder (and backed by the underlying
         *         BLM)
         */
        protected MaskedBinaryLongMap instantiateMaskedBinaryLongMap(int cShift, int cBits)
            {
            return new MaskedBinaryLongMap(this, f_blm, cShift, cBits);
            }

        /**
         * Shift the representation of the specified MaskedBinaryLongMap by the
         * specified number of bits to the right.
         * <p>
         * Note: the caller must hold exclusive access to the underlying BLM
         *       (and by extension, exclusive access to all derived masked maps).
         *
         * @param blm          the MaskedBinaryLongMap to shift
         * @param cShiftRight  the number of bits to shift to the right
         */
        protected void shiftMaskedMap(MaskedBinaryLongMap blm, final int cShiftRight)
            {
                  int  cShiftNew = blm.m_cShift - cShiftRight;
            final long lMaskNew  = ((1L << blm.f_cBits) - 1) << cShiftNew;
            final long lMaskOld  = blm.f_nMask;

            f_blm.visitAll(new SafeEntryVisitor()
                    {
                    public void visit(BinaryLongMap.Entry entry)
                        {
                        long lValueWhole = entry.getValue();
                        long lValueNew   = (lValueWhole & ~(lMaskOld | lMaskNew)) |
                                           ((lValueWhole & lMaskOld) >>> cShiftRight);
                        entry.setValue(lValueNew);
                        }
                    });

            blm.m_cShift = cShiftNew;
            blm.f_nMask  = lMaskNew;

            m_lMaskFree |= lMaskOld;   // reclaim the previously assigned bits as free
            m_lMaskFree &= ~lMaskNew;  // clear the newly assigned bits as used
            }

        /**
         * Debugging function to check the representational invariant.
         */
        protected void checkRep()
            {
            f_blm.visitAll(new SafeEntryVisitor()
                {
                public void visit(BinaryLongMap.Entry entry)
                    {
                    long lValue = entry.getValue();
                    if ((lValue & ~m_lMaskFree) != lValue)
                        {
                        throw new AssertionError(
                                "Representation Invariant Broken: key "
                              + entry.getKey() + " has value 0x"
                              + Long.toHexString(lValue) + " with free-mask 0x"
                              + Long.toHexString(m_lMaskFree));
                        }
                    }
                });
            }


        // ----- data members ---------------------------------------------

        /**
         * The list of {@link #reserveMap reserved} masked BLMs, ordered by
         * increasing shift-position.
         */
        protected List<MaskedBinaryLongMap> m_listBLMs = new ArrayList<MaskedBinaryLongMap>();

        /**
         * The bit-mask describing which bits in the underlying BLM
         * representation are reserved (and may not be allocated by this holder).
         */
        protected final long f_lMaskReserved;

        /**
         * The bit-mask describing which bits in the underlying BLM
         * representation are free.
         */
        protected long m_lMaskFree;

        /**
         * The shared lock used to protected the logical contents of the maps
         * exposed by this MaskedLongMapHolder.
         */
        protected final Lock f_lockS;

        /**
         * The exclusive lock used to protected the logical contents of the maps
         * exposed by this MaskedLongMapHolder.
         */
        protected final Lock f_lockX;

        /**
         * The BinaryLongMap shared by the component masked BLMs.
         */
        protected final BinaryLongMap f_blm;
        }


    // ----- inner class: MaskedBinaryLongMap -----------------------------

    /**
     * MaskedBinaryLongMap is a BinaryLongMap which represents a mapping from a
     * Binary to a fixed-bit-width numeric value (though exposed as a <i>long</i>
     * datatype), based on an underlying BinaryLongMap.   Values returned from
     * the resulting map will be sign-extended to the <tt>long</tt> data-type and
     * it is the responsibility of calling code intending to use this map to store
     * unsigned values to adjust/mask the sign accordingly.
     * <p>
     * MaskedBinaryLongMap is not thread-safe, and it is the caller's responsibility
     * to ensure that any concurrent access to the MaskedBinaryLongMap is protected
     * in a manner consistent with the underlying BinaryLongMap.
     */
    public static class MaskedBinaryLongMap
            implements BinaryLongMap
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a MaskedBinaryLongMap based on the specified binary long map
         * using the specified shift and bit-widths.  More formally, the logical
         * value stored by this MaskedBinaryLongMap is represented in the
         * underlying physical BinaryLongMap as:
         * <code>
         *   lValueLogical = (lValuePhysical &gt;&gt; cShift) &amp; ((1 &lt;&lt; cBits) - 1)
         * </code>
         *
         * @param holder  the MaskedLongMapHolder associated with this MaskedBinaryLongMap
         * @param blm     the underlying BinaryLongMap
         * @param cShift  the number of positions to left-shift the values stored
         *                in this MaskedBinaryLongMap
         * @param cBits   the number of bits in the values stored in this
         *                MaskedBinaryLongMap
         */
        public MaskedBinaryLongMap(MaskedLongMapHolder holder, BinaryLongMap blm, int cShift, int cBits)
            {
            assert cBits < 64;
            assert cBits + cShift <= 64; // the upper 32bits could be used (int map)

            f_holder = holder;
            f_blm    = blm;
            m_cShift = cShift;
            f_cBits  = cBits;

            f_nMask = ((1L << cBits) - 1) << cShift;
            }

        // ----- accessors ------------------------------------------------

        /**
         * Return the MaskedLongMapHolder used to manage the lifecycle of this
         * MaskedBinaryLongMap.
         *
         * @return the MaskedLongMapHolder
         */
        public MaskedLongMapHolder getHolder()
            {
            return f_holder;
            }

        // ----- BinaryLongMap methods ------------------------------------

        /**
         * {@inheritDoc}
         */
        public long get(Binary binKey)
            {
            return decode(f_blm.get(binKey));
            }

        /**
         * {@inheritDoc}
         */
        public void put(Binary binKey, long lValue)
            {
            long lValueWhole = f_blm.get(binKey);
            f_blm.put(binKey, encode(lValueWhole, lValue));
            }

        /**
         * {@inheritDoc}
         */
        public boolean putIfAbsent(Binary binKey, long lValue)
            {
            return replace(binKey, 0L, lValue);
            }

        /**
         * {@inheritDoc}
         */
        public boolean replace(Binary binKey, long lValueOld, long lValueNew)
            {
            long lValueWhole = f_blm.get(binKey);
            if (decode(lValueWhole) == lValueOld)
                {
                f_blm.put(binKey, encode(lValueWhole, lValueNew));
                return true;
                }
            else
                {
                return false;
                }
            }

        /**
         * {@inheritDoc}
         */
        public void remove(Binary binKey)
            {
            long lValueWhole = f_blm.get(binKey);
            f_blm.replace(binKey, lValueWhole, encode(lValueWhole, 0L));
            }

        /**
         * {@inheritDoc}
         */
        public boolean remove(Binary binKey, long lValue)
            {
            return replace(binKey, lValue, 0L);
            }

        /**
         * {@inheritDoc}
         */
        public void clear()
            {
            f_blm.visitAll(new SafeEntryVisitor()
                {
                public void visit(Entry entry)
                    {
                    long lValue = entry.getValue();
                    if (decode(lValue) != 0L)
                        {
                        entry.setValue(encode(lValue, 0L));
                        }
                    }
                });
            }

        /**
         * {@inheritDoc}
         */
        public int size()
            {
            final int[] aiHolder = new int[1];
            f_blm.visitAll(new SafeEntryVisitor()
                {
                public void visit(Entry entry)
                    {
                    long lValue = entry.getValue();
                    if (decode(lValue) != 0L)
                        {
                        aiHolder[0]++;
                        }
                    }
                });

            return aiHolder[0];
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys()
            {
            return f_blm.keys(new SafePredicate()
                {
                public boolean evaluate(Entry entry)
                    {
                    return decode(entry.getValue()) != 0L;
                    }
                });
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys(final Predicate<Entry> predicate)
            {
            return f_blm.keys(new SafePredicate()
                {
                public boolean evaluate(Entry entry)
                    {
                    return decode(entry.getValue()) != 0L &&
                           f_predicateSafe.evaluate(new MaskedEntry(entry));
                    }

                final SafePredicate f_predicateSafe = ensureSafePredicate(predicate);
                });
            }

        /**
         * {@inheritDoc}
         */
        public void visit(Binary binKey, EntryVisitor visitor)
            {
            f_blm.visit(binKey, instantiateMaskedEntryVisitor(visitor, false));
            }

        /**
         * {@inheritDoc}
         */
        public void visitAll(EntryVisitor visitor)
            {
            f_blm.visitAll(instantiateMaskedEntryVisitor(visitor, true));
            }

        /**
         * {@inheritDoc}
         */
        public void internKeys(Object o)
            {
            f_blm.internKeys(o);
            }

        // ----- inner class: MaskedEntryVisitor --------------------------

        /**
         * Instantiate a MaskedEntryVisitor to use to {@link #visit} entries
         * in this MaskedBinaryLongMap.
         *
         * @param visitor    the underlying visitor to wrap
         * @param fExisting  true iff the underlying visitor is to visit only
         *                   entries that logically exist in the MaskedBinaryLongMap
         *
         * @return a MaskedEntryVisitor
         */
        protected MaskedEntryVisitor instantiateMaskedEntryVisitor(EntryVisitor visitor, boolean fExisting)
            {
            return new MaskedEntryVisitor(visitor, fExisting);
            }

        /**
         * MaskedEntryVisitor is a wrapper for an EntryVisitor that exposes
         * the logical entries of this MaskedEntryVisitor.
         */
        protected class MaskedEntryVisitor
                implements SafeEntryVisitor
            {
            // ----- constructors -----------------------------------------

            /**
             * Construct a DelegateEntryVisitor around the specified visitor.
             *
             * @param visitor  the underlying EntryVisitor
             */
            public MaskedEntryVisitor(EntryVisitor visitor, boolean fExisting)
                {
                f_visitorSafe = ensureSafeVisitor(MaskedBinaryLongMap.this, visitor);
                f_fExisting   = fExisting;
                }

            // ----- EntryVisitor methods ---------------------------------

            /**
             * {@inheritDoc}
             */
            public void visit(Entry entry)
                {
                entry = new MaskedEntry(entry);
                if (!f_fExisting || entry.getValue() != 0L)
                    {
                    f_visitorSafe.visit(entry);
                    }
                }

            // ----- data members -----------------------------------------

            /**
             * The wrapped (safe) predicate to apply to the logical entries
             * of this MaskedBinaryLongMap.
             */
            protected final SafeEntryVisitor f_visitorSafe;

            /**
             * Flag to indicate whether the underlying visitor is to visit only
             * entries that logically exist in the MaskedBinaryLongMap.
             */
            protected final boolean f_fExisting;
            }

        // ----- inner class: MaskedEntry ---------------------------------

        /**
         * MaskedEntry represents an Entry in the MaskedBinaryLongMap.
         */
        protected class MaskedEntry
                implements Entry
            {
            /**
             * Construct a MaskedEntry backed by the specified underlying entry.
             *
             * @param entry  the underlying entry
             */
            protected MaskedEntry(Entry entry)
                {
                f_entry = entry;
                }

            // ----- Entry methods ----------------------------------------

            /**
             * {@inheritDoc}
             */
            public Binary getKey()
                {
                return f_entry.getKey();
                }

            /**
             * {@inheritDoc}
             */
            public long getValue()
                {
                return decode(f_entry.getValue());
                }

            /**
             * {@inheritDoc}
             */
            public Entry setValue(long lValue)
                {
                f_entry.setValue(encode(f_entry.getValue(), lValue));
                return this;
                }

            // ----- data members -----------------------------------------

            /**
             * The underlying entry.
             */
            protected final Entry f_entry;
            }

        // ----- helpers --------------------------------------------------

        /**
         * Decode the n-bit value from the specified {@link #encode encoded}
         * long value.
         *
         * @param lEncoded  the 64-bit encoded value to decode
         *
         * @return the decoded value
         */
        protected long decode(long lEncoded)
            {
            // Note: intentionally not refactored into a static helper in order
            //       to encourage aggressive JIT inlining and code-folding
            return ((lEncoded & f_nMask) << (64 - f_cBits - m_cShift)) >> (64 - f_cBits);
            }

        /**
         * Encode the specified n-bit value into the possibly already encoded
         * value, preserving any unrelated bits.
         *
         * @param lEncoded  the 64-bit value to encode into
         * @param lValue    the value to encode
         *
         * @return the new encoded value
         */
        protected long encode(long lEncoded, long lValue)
            {
            // Note: intentionally not refactored into a static helper in order
            //       to encourage aggressive JIT inlining and code-folding
            return (lEncoded & ~f_nMask) | ((lValue << m_cShift) & f_nMask);
            }

        // ----- data members ---------------------------------------------

        /**
         * The MaskedLongMapHolder associated with this MaskedBinaryLongMap.
         */
        protected final MaskedLongMapHolder f_holder;

        /**
         * The underlying BinaryLongMap.
         */
        protected final BinaryLongMap f_blm;

        /**
         * The number of bits that the logical value represented by this
         * MaskedBinaryLongMap is shifted left in the underlying physical
         * representation.
         */
        protected int m_cShift;

        /**
         * The number of bits that the logical value represented by this
         * MaskedBinaryLongMap is limited to in the underlying physical
         * representation.
         */
        protected final int f_cBits;

        /**
         * The bit-mask representing the positions used to store values in the
         * underlying physical representation.
         */
        protected long f_nMask;
        }


    // ----- inner class: LeftoverLongMapHolder ---------------------------

    /**
     * LeftoverLongMapHolder is the MaskedLongMapHolder implementation that is
     * backed by the "real" key tree itself.  The MaskedBinaryLongMap instances
     * created by this holder are stored in the upper 32-bits of the underlying
     * key-tree (where the lower 32-bits encode the "slot" info). Additionally,
     * the returned instances contain additional safety checks to ensure that
     * users of the returned BinaryLongMap may not cause keys to be inadvertently
     * (and illegally) added to the MultiBinaryLongMap.
     */
    public class LeftoverLongMapHolder
            extends MaskedLongMapHolder
        {
        // ----- constructor ----------------------------------------------

        /**
         * Default constructor.
         */
        public LeftoverLongMapHolder()
            {
            super(f_tree, f_rwLockMaster.readLock(), 0xFFFFFFFFL);
            }

        // ----- MaskedLongMapHolder methods ------------------------------

        /**
         * {@inheritDoc}
         */
        protected MaskedBinaryLongMap instantiateMaskedBinaryLongMap(int cShift, int cBits)
            {
            return new LeftoverMaskedBinaryLongMap(this, f_blm, cShift, cBits);
            }

        // ----- inner class: LeftoverMaskedBinaryLongMap -----------------

        /**
         * LeftoverMaskedBinaryLongMap is a MaskedBinaryLongMap implementation
         * that is backed by the leftover (upper 32) bits in "real" key-tree
         * itself.
         */
        public class LeftoverMaskedBinaryLongMap
                extends MaskedBinaryLongMap
            {
            // ----- constructors -----------------------------------------

            /**
             * Construct a LeftoverMaskedBinaryLongMap.
             *
             * @param holder  the MaskedLongMapHolder
             * @param blm     the underlying BinaryLongMap
             * @param cShift  the number of positions to left-shift the values
             *                stored in this MaskedBinaryLongMap
             * @param cBits   the number of bits in the values stored in this
             *                MaskedBinaryLongMap
             */
            public LeftoverMaskedBinaryLongMap(MaskedLongMapHolder holder,
                                               BinaryLongMap blm, int cShift, int cBits)
                {
                super(holder, blm, cShift, cBits);
                }

            // ----- MaskedBinaryLongMap methods --------------------------

            /**
             * {@inheritDoc}
             */
            protected long encode(long lEncoded, long lValue)
                {
                if (lEncoded == 0L && lValue != 0L)
                    {
                    throw new UnsupportedOperationException(
                            "Keys may not be added to the MultiBinaryLongMap through a delegating map.");
                    }

                return super.encode(lEncoded, lValue);
                }

            /**
             * {@inheritDoc}
             */
            protected MaskedEntryVisitor instantiateMaskedEntryVisitor(
                            EntryVisitor visitor, boolean fExisting)
                {
                return new MaskedEntryVisitor(visitor, fExisting)
                    {
                    public void visit(Entry entry)
                        {
                        // for the LeftoverBinaryLongMap, the underlying Entry is
                        // backed by the "real" key tree; we must not allow any
                        // entries for keys that don't exist in the real key tree
                        // to pass through to the visitor as the entry is not
                        // allowed to be added here
                        if (entry.getValue() != 0L)
                            {
                            super.visit(entry);
                            }
                        }
                    };
                }
            }
        }


    // ----- inner class: WrapperBinaryLongMap ----------------------------

    /**
     * The WrapperBinaryLongMap is a BinaryLongMap implementation that wraps
     * an underlying BinaryLongMap.
     */
    public static class WrapperBinaryLongMap
            implements BinaryLongMap
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a WrapperBinaryLongMap based on the specified BinaryLongMap.
         *
         * @param blm  the BinaryLongMap to wrap
         */
        public WrapperBinaryLongMap(BinaryLongMap blm)
            {
            m_blm = blm;
            }

        // ----- accessors ------------------------------------------------

        /**
         * Return the underlying BinaryLongMap.
         *
         * @return the underlying BinaryLongMap
         */
        public BinaryLongMap getMap()
            {
            return m_blm;
            }

        /**
         * Return the underlying BinaryLongMap.
         *
         * @param blm  the underlying BinaryLongMap
         */
        protected void setMap(BinaryLongMap blm)
            {
            m_blm = blm;
            }

        // ----- BinaryLongMap methods ------------------------------------

        /**
         * {@inheritDoc}
         */
        public long get(Binary binKey)
            {
            return getMap().get(binKey);
            }

        /**
         * {@inheritDoc}
         */
        public void put(Binary binKey, long lValue)
            {
            getMap().put(binKey, lValue);
            }

        /**
         * {@inheritDoc}
         */
        public boolean putIfAbsent(Binary binKey, long lValue)
            {
            return getMap().putIfAbsent(binKey, lValue);
            }

        /**
         * {@inheritDoc}
         */
        public boolean replace(Binary binKey, long lValueOld, long lValueNew)
            {
            return getMap().replace(binKey, lValueOld, lValueNew);
            }

        /**
         * {@inheritDoc}
         */
        public void remove(Binary binKey)
            {
            getMap().remove(binKey);
            }

        /**
         * {@inheritDoc}
         */
        public boolean remove(Binary binKey, long lValue)
            {
            return getMap().remove(binKey, lValue);
            }

        /**
         * {@inheritDoc}
         */
        public void clear()
            {
            getMap().clear();
            }

        /**
         * {@inheritDoc}
         */
        public int size()
            {
            return getMap().size();
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys()
            {
            return new WrapperIterator(getMap().keys());
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys(Predicate<Entry> predicate)
            {
            return new WrapperIterator(getMap().keys(predicate));
            }

        /**
         * {@inheritDoc}
         */
        public void visit(Binary binKey, EntryVisitor visitor)
            {
            getMap().visit(binKey, visitor);
            }

        /**
         * {@inheritDoc}
         */
        public void visitAll(EntryVisitor visitor)
            {
            getMap().visitAll(visitor);
            }

        /**
         * {@inheritDoc}
         */
        public void internKeys(Object o)
            {
            getMap().internKeys(o);
            }

        // ----- inner class: WrapperIterator -----------------------------

        /**
         * A wrapper key Iterator that implements {@link Iterator#remove} by
         * coming back through the WrapperBinaryLongMap's {@link #remove(Binary)}
         * method.
         */
        protected class WrapperIterator
                implements Iterator<Binary>
            {
            /**
             * Construct a WrapperIterator.
             *
             * @param iter  the underlying Iterator
             */
            public WrapperIterator(Iterator<Binary> iter)
                {
                assert iter != null;
                f_iter = iter;
                }

            // ----- Iterator methods -------------------------------------

            /**
             * {@inheritDoc}
             */
            public boolean hasNext()
                {
                return f_iter.hasNext();
                }

            /**
             * {@inheritDoc}
             */
            public Binary next()
                {
                return m_binKey = f_iter.next();
                }

            /**
             * {@inheritDoc}
             */
            public void remove()
                {
                Binary binKey = m_binKey;
                if (binKey == null)
                    {
                    throw new IllegalStateException();
                    }

                m_binKey = null;
                WrapperBinaryLongMap.this.remove(binKey);
                }

            // ----- data members -----------------------------------------

            /**
             * The underlying Iterator of Binary keys.
             */
            private final Iterator<Binary> f_iter;

            /**
             * The most recently iterated Binary key.
             */
            private Binary m_binKey;
            }

        // ----- data members ---------------------------------------------

        /**
         * The underlying BinaryLongMap.
         */
        protected BinaryLongMap m_blm;
        }


    // ----- inner class: SafeBinaryLongMap -------------------------------

    /**
     * The SafeBinaryLongMap is a BinaryLongMap implementation that wraps an
     * underlying BinaryLongMap in order to add thread safety.
     */
    public static class SafeBinaryLongMap
            extends WrapperBinaryLongMap
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a SafeBinaryLongMap around the specified map, protected by
         * the specified locks.
         * <p>
         * It is assumed that the specified locks have the following reentrant
         * properties:
         * <ul>
         *   <li>a thread holding one or both of the locks may reacquire those
         *       locks (and must release them accordingly)
         *   <li>a thread holding the exclusive lock may acquire the shared lock
         *       (but not vice-versa).
         * </ul>
         *
         * @param blm            the underlying BinaryLongMap
         * @param lockShared     the lock to acquire for shared access
         * @param lockExclusive  the lock to acquire for exclusive access
         */
        public SafeBinaryLongMap(BinaryLongMap blm, Lock lockShared, Lock lockExclusive)
            {
            super(blm);

            f_lockShared    = lockShared;
            f_lockExclusive = lockExclusive;
            }

        // ----- accessors ------------------------------------------------

        /**
         * {@inheritDoc}
         */
        public BinaryLongMap getMap()
            {
            BinaryLongMap blm = super.getMap();
            if (blm == null)
                {
                throw new IllegalStateException(
                        "The SafeBinaryLongMap has been explicitly released.");
                }

            return blm;
            }

        // ----- SafeBinaryLongMap methods --------------------------------

        /**
         * Release the SafeBinaryLongMap.  Any future operations against this
         * map will result in an exception.
         */
        public void release()
            {
            setMap(null);
            }

        // ----- BinaryLongMap methods ------------------------------------

        /**
         * {@inheritDoc}
         */
        public long get(Binary binKey)
            {
            f_lockShared.lock();
            try
                {
                return super.get(binKey);
                }
            finally
                {
                f_lockShared.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void put(Binary binKey, long lValue)
            {
            f_lockExclusive.lock();
            try
                {
                super.put(binKey, lValue);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public boolean putIfAbsent(Binary binKey, long lValue)
            {
            f_lockExclusive.lock();
            try
                {
                return super.putIfAbsent(binKey, lValue);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public boolean replace(Binary binKey, long lValueOld, long lValueNew)
            {
            f_lockExclusive.lock();
            try
                {
                return super.replace(binKey, lValueOld, lValueNew);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void remove(Binary binKey)
            {
            f_lockExclusive.lock();
            try
                {
                super.remove(binKey);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public boolean remove(Binary binKey, long lValue)
            {
            f_lockExclusive.lock();
            try
                {
                return super.remove(binKey, lValue);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void clear()
            {
            f_lockExclusive.lock();
            try
                {
                super.clear();
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public int size()
            {
            f_lockShared.lock();
            try
                {
                return super.size();
                }
            finally
                {
                f_lockShared.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys()
            {
            f_lockShared.lock();
            try
                {
                return super.keys();
                }
            finally
                {
                f_lockShared.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public Iterator<Binary> keys(Predicate<Entry> predicate)
            {
            f_lockShared.lock();
            try
                {
                return super.keys(predicate);
                }
            finally
                {
                f_lockShared.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void visit(Binary binKey, EntryVisitor visitor)
            {
            f_lockExclusive.lock();
            try
                {
                super.visit(binKey, visitor);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void visitAll(EntryVisitor visitor)
            {
            f_lockExclusive.lock();
            try
                {
                super.visitAll(visitor);
                }
            finally
                {
                f_lockExclusive.unlock();
                }
            }

        /**
         * {@inheritDoc}
         */
        public void internKeys(Object o)
            {
            // it is assumed that interning keys is generally thread safe,
            // (e.g. BinaryRadixTree), but the shared lock prevents other threads
            // from adding/removing entries during the interning process

            f_lockShared.lock();
            try
                {
                super.internKeys(o);
                }
            finally
                {
                f_lockShared.unlock();
                }
            }

        // ----- data members ---------------------------------------------

        /**
         * The shared lock.
         */
        protected final Lock f_lockShared;

        /**
         * The exclusive lock.
         */
        protected final Lock f_lockExclusive;
        }


    // ----- inner class: SafeEntry ---------------------------------------

    /**
     * SafeEntry is an immutable {@link BinaryLongMap.Entry} implementation that
     * exposes the logical contents of an underlying Entry in a way that is
     * safe from mutations or being held as a reference.
     */
    protected static class SafeEntry
            implements BinaryLongMap.Entry
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a SafeEntry backed by the specified underlying entry.
         *
         * @param entry  the underlying entry.
         */
        public SafeEntry(BinaryLongMap.Entry entry)
            {
            f_entry  = entry;   // it's safe to use the entry reference to get
                                // the key; it is guaranteed to be immutable
            f_lValue = entry.getValue();
            }

        // ----- Entry methods --------------------------------------------

        /**
         * {@inheritDoc}
         */
        public Binary getKey()
            {
            return f_entry.getKey();
            }

        /**
         * {@inheritDoc}
         */
        public long getValue()
            {
            return f_lValue;
            }

        /**
         * {@inheritDoc}
         */
        public BinaryLongMap.Entry setValue(long lValue)
            {
            // "safe" entries are not allowed to do mutations through the backdoor
            throw new UnsupportedOperationException();
            }

        // ----- data members ---------------------------------------------

        /**
         * The underlying entry (used for binary key retrieval).
         */
        protected final BinaryLongMap.Entry f_entry;

        /**
         * The associated value
         */
        protected final long f_lValue;
        }


    // ----- inner interface: SafePredicate -------------------------------

    /**
     * Return a SafePredicate based on the specified predicate implementation.
     *
     * @param predicate  the predicate
     *
     * @return a SafePredicate
     */
    protected static SafePredicate ensureSafePredicate(
            final Predicate<BinaryLongMap.Entry> predicate)
        {
        return predicate == null || predicate instanceof SafePredicate
            ? (SafePredicate) predicate
            : new SafePredicate()
                    {
                    public boolean evaluate(final BinaryLongMap.Entry entry)
                        {
                        // Note: this is creating a new Entry instance for
                        // each evaluation, but that is necessary in case
                        // anyone is holding on to the Entry instances; also,
                        // the SafeEntry does not allow mutation (setValue)
                        return predicate.evaluate(new SafeEntry(entry));
                        }
                    };
        }

    /**
     * SafePredicate is a marker interface used internally by the implementation
     * of the MultiBinaryLongMap to indicate that a {@link Predicate}
     * implementation is "safe" and does not retain any references to passed
     * Entries.
     */
    public interface SafePredicate
            extends Predicate<BinaryLongMap.Entry>
        {
        }


    // ----- inner interface: SafeEntryVisitor ----------------------------

    /**
     * Return a SafeEntryVisitor based on the specified visitor implementation.
     *
     * @param visitor  the visitor
     *
     * @return a SafeEntryVisitor
     */
    protected static SafeEntryVisitor ensureSafeVisitor(
            final BinaryLongMap blm, final BinaryLongMap.EntryVisitor visitor)
        {
        return visitor == null || visitor instanceof SafeEntryVisitor
            ? (SafeEntryVisitor) visitor
            : new SafeEntryVisitor()
                    {
                    public void visit(final BinaryLongMap.Entry entry)
                        {
                        // Note: this is creating a new Entry instance for
                        // each evaluation, but that is necessary in case
                        // anyone is holding on to the Entry instances
                        visitor.visit(new SafeEntry(entry)
                            {
                            public BinaryLongMap.Entry setValue(long lValue)
                                {
                                // allow mutations, but they must go through the
                                // front-door
                                blm.put(entry.getKey(), lValue);
                                return this;
                                }
                            });
                        }
                    };
        }

    /**
     * SafeEntryVisitor is a marker interface used internally by the implementation
     * of the MultiBinaryLongMap to indicate that an {@link BinaryLongMap.EntryVisitor}
     * implementation is "safe" and does not retain any references to passed
     * Entries.
     */
    public interface SafeEntryVisitor
            extends BinaryLongMap.EntryVisitor
        {
        }


    // ----- inner class: LongStorage -------------------------------------

    /**
     * An internal data structure for managing elastic storage of
     * multi-dimensional data; basically, it is a two-dimensional array of
     * rows and columns that can be grown in either dimension, but is
     * optimized specifically for the addition and removal of rows.
     * <p>
     * The first dimension is a sequence of "slots" (i.e. rows) that each hold
     * one or more indexed values. The second dimension is a sequence of
     * "indexes" (i.e. columns). A combination of a slot and an index is used
     * to access or modify a specific <tt>long</tt> value.
     */
    protected static class LongStorage
        {
        // ----- constructors ---------------------------------------------

        /**
         * Construct a <tt>LongStorage</tt> that will initially hold
         * <tt>cIndexes</tt> columns of data.
         *
         * @param cIndexes  the number of values (i.e. columns) to hold for
         *                  each slot (i.e. row)
         */
        public LongStorage(int cIndexes)
            {
            if (cIndexes < 1)
                {
                throw new IllegalArgumentException("cIndexes (" + cIndexes + ") must be >= 1");
                }

            m_cIndexes = cIndexes;
            clear();
            }

        // ----- accessors ------------------------------------------------

        /**
         * Determine the number of indexes (columns) supported by the
         * LongStorage. For each index, one value is stored in each slot
         * (row).
         *
         * @return the number of values stored in each slot
         */
        public int getIndexCount()
            {
            return m_cIndexes;
            }

        /**
         * Determine the number of slots that have been reserved in the
         * LongStorage.
         *
         * @return the number of slots that have been reserved
         */
        public int getSlotCount()
            {
            return m_cTotal - m_cFree;
            }

        /**
         * Determine the current capacity (the actual number of slots for
         * which storage has been allocated) in the LongStorage.
         * <p>
         * Note: During the compression phase, the capacity is set to the
         * number of slots in use, which is the level to which the LongStorage
         * is compressed.
         *
         * @return the total number of slots that are currently allocated in
         *         the LongStorage
         */
        public int getCapacity()
            {
            return m_cTotal;
            }

        // ----- maintenance ----------------------------------------------

        /**
         * Add an additional index value to each slot.
         * <p>
         * It is the responsibility of the caller to ensure that no
         * multi-threaded access to the LongStorage occurs during this
         * process.
         *
         * @return the index identifier of the new index value
         */
        public synchronized int addIndex()
            {
            int      cIndexOld  = m_cIndexes;
            int      cIndexNew  = cIndexOld + 1;
            long[][] aalStorage = m_aalStorage;
            int      cSegments  = aalStorage.length;
            if (cSegments > 0)
                {
                for (int iSegment = 0; iSegment < cSegments; ++iSegment)
                    {
                    int    cSlots     = calcSegmentSize(iSegment);
                    long[] alSlotsNew = new long[cSlots * cIndexNew];
                    long[] alSlotsOld = aalStorage[iSegment];
                    assert alSlotsOld.length == cSlots * cIndexOld;

                    for (int iSlot = 0; iSlot < cSlots; ++iSlot)
                        {
                        System.arraycopy(alSlotsOld, iSlot * cIndexOld,
                                alSlotsNew, iSlot * cIndexNew, cIndexOld);
                        }

                    aalStorage[iSegment] = alSlotsNew;
                    }
                }

            m_cIndexes = cIndexNew;
            return cIndexOld;
            }

        /**
         * Remove an index value from each slot.
         * <p>
         * It is the responsibility of the caller to ensure that no
         * multi-threaded access to the LongStorage occurs during this process.
         *
         * @param iIndex  the index identifier of the index value to remove
         */
        public void removeIndex(int iIndex)
            {
            int cIndexOld  = m_cIndexes;
            if (iIndex < 0 || iIndex >= cIndexOld)
                {
                throw new IllegalArgumentException("illegal index (" + iIndex
                        + "); valid range is 0.." + (cIndexOld-1));
                }
            if (cIndexOld == 1)
                {
                throw new IllegalArgumentException("cannot remove last remaining index");
                }

            int      cIndexNew  = cIndexOld - 1;
            long[][] aalStorage = m_aalStorage;
            int      cSegments  = aalStorage.length;
            if (cSegments > 0)
                {
                if (iIndex == 0)
                    {
                    // copy the free list info from column 0 to column 1
                    int iFree = m_iFreeSlot;
                    while (iFree != -1)
                        {
                        int iNext = (int) get(iFree, 0);
                        put(iFree, 1, iNext);
                        iFree = iNext;
                        }
                    }

                // remove the "column" at iIndex
                int     cColumnsToTheLeft  = iIndex;
                boolean fColumnsToTheLeft  = cColumnsToTheLeft > 0;
                int     cColumnsToTheRight = cIndexNew - iIndex;
                boolean fColumnsToTheRight = cColumnsToTheRight > 0;
                for (int iSegment = 0; iSegment < cSegments; ++iSegment)
                    {
                    int    cSlots     = calcSegmentSize(iSegment);
                    long[] alSlotsNew = new long[cSlots * cIndexNew];
                    long[] alSlotsOld = aalStorage[iSegment];

                    assert alSlotsOld.length == cSlots * cIndexOld;

                    for (int iSlot = 0; iSlot < cSlots; ++iSlot)
                        {
                        if (fColumnsToTheLeft)
                            {
                            // copy the columns to the left of the column being removed
                            System.arraycopy(alSlotsOld, iSlot * cIndexOld,
                                    alSlotsNew, iSlot * cIndexNew, cColumnsToTheLeft);
                            }

                        if (fColumnsToTheRight)
                            {
                            // copy columns to the right of the column being removed
                            System.arraycopy(alSlotsOld, iSlot * cIndexOld + iIndex + 1,
                                    alSlotsNew, iSlot * cIndexNew + iIndex, cColumnsToTheRight);
                            }
                        }

                    aalStorage[iSegment] = alSlotsNew;
                    }
                }

            m_cIndexes = cIndexNew;
            }

        /**
         * Reserve a slot to use for storing information.
         * <p>
         * It is the responsibility of the caller to ensure that no
         * multi-threaded access to the LongStorage occurs during this
         * process.
         *
         * @return the slot index that was newly reserved
         */
        public int reserveSlot()
            {
            // get a slot number from the "free list"
            int iSlot = m_iFreeSlot;
            if (iSlot < 0)
                {
                assert m_cFree == 0;
                grow();
                assert m_cFree > 0;
                iSlot = m_iFreeSlot;
                assert iSlot >= 0;
                }

            // determine the segment that holds the slot and where the slot
            // is located within that segment
            int    iSegment = calcSegmentIndex(iSlot);
            long[] alSlots  = m_aalStorage[iSegment];
            int    ofSlot   = (iSlot - calcFirstSlotIndex(iSegment)) * m_cIndexes;

            // advance the pointer in the "free list"
            m_iFreeSlot = (int) alSlots[ofSlot];
            --m_cFree;
            assert m_cFree >= 0;

            // clear out the pointer (from the free list) that was stored in
            // the newly allocated slot
            alSlots[ofSlot] = 0L;

            return iSlot;
            }

        /**
         * Add a new segment of slot storage.
         */
        protected void grow()
            {
            int      cSlotsOld   = m_cTotal;
            long[][] aalStoreOld = m_aalStorage;
            int      cSegsOld    = aalStoreOld.length;
            int      cSegsNew    = cSegsOld + 1;
            long[][] aalStoreNew = new long[cSegsNew][];
            int      iSegAdd     = cSegsNew - 1;
            int      cSlotsAdd   = calcSegmentSize(iSegAdd);

            // create the new array of segments, including the added segment
            System.arraycopy(aalStoreOld, 0, aalStoreNew, 0, cSegsOld);
            int      cIndexes    = m_cIndexes;
            long[]   alSlotsAdd  = new long[cSlotsAdd * cIndexes];
            aalStoreNew[iSegAdd] = alSlotsAdd;

            // convert the new segment to a linked free list; each slot points
            // to the next, except the last slot is the list terminator (-1)
            for (int iNext = 1; iNext < cSlotsAdd; ++iNext)
                {
                alSlotsAdd[(iNext - 1) * cIndexes] = cSlotsOld + iNext;
                }
            alSlotsAdd[(cSlotsAdd - 1) * cIndexes] = -1;

            // update the LongStorage
            m_aalStorage = aalStoreNew;
            m_cTotal     = cSlotsOld + cSlotsAdd;
            m_iFreeSlot  = cSlotsOld;
            m_cFree      = cSlotsAdd;
            recalculateShrinkageThreshold();
            }

        /**
         * Release a previously-reserved slot.
         * <p>
         * It is the responsibility of the caller to ensure that no
         * multi-threaded access to the LongStorage occurs during this
         * process.
         *
         * @param iSlot the slot index to release
         */
        public void releaseSlot(int iSlot)
            {
            assert iSlot >= 0 && iSlot < m_cTotal;

            // determine the segment that holds the slot and where the slot
            // is located within that segment
            int    iSegment = calcSegmentIndex(iSlot);
            long[] alSlots  = m_aalStorage[iSegment];
            int    cIndexes = m_cIndexes;
            int    ofSlot   = (iSlot - calcFirstSlotIndex(iSegment)) * cIndexes;

            // use the first index of the slot to store the next slot in the
            // "free list", and clear the other index values in the slot
            alSlots[ofSlot] = m_iFreeSlot;
            for (int i = 1; i < cIndexes; ++i)
                {
                alSlots[ofSlot + i] = 0L;
                }

            // put the released slot at the head of the free list
            m_iFreeSlot = iSlot;

            // check if the LongStorage is now completely empty; if so, reset
            // to the initial state (i.e. release any memory being used)
            if (++m_cFree >= m_cTotal && m_cTotal > calcSegmentSize(0))
                {
                clear();
                }
            }

        /**
         * Reset the LongStorage to its initial empty state.
         * <p>
         * It is the responsibility of the caller to ensure that no
         * multi-threaded access to the LongStorage occurs during this
         * process.
         */
        public void clear()
            {
            m_aalStorage = EMPTY;
            m_cTotal     = 0;
            m_cFree      = 0;
            m_iFreeSlot  = -1;
            recalculateShrinkageThreshold();
            }

        // ----- slot access ----------------------------------------------

        /**
         * Load a value.
         *
         * @param iSlot   the slot index to load the value from
         * @param iIndex  the value index from which to load the value
         *
         * @return the value from the specified index of the specified slot
         */
        public long get(int iSlot, int iIndex)
            {
            // determine the segment that holds the slot and where the slot
            // is located within that segment
            assert iSlot >= 0 && iSlot < m_cTotal;
            int    iSegment = calcSegmentIndex(iSlot);
            long[] alSlots  = m_aalStorage[iSegment];
            int    cIndexes = m_cIndexes;
            assert iIndex < cIndexes;
            return alSlots[(iSlot - calcFirstSlotIndex(iSegment)) * cIndexes + iIndex];
            }

        /**
         * Store a value.
         *
         * @param iSlot   the slot index to store a value in
         * @param iIndex  the value index to store a value for
         * @param lValue  the value to store in the specified index of the
         *                specified slot
         */
        public void put(int iSlot, int iIndex, long lValue)
            {
            // determine the segment that holds the slot and where the slot
            // is located within that segment
            assert iSlot >= 0 && iSlot < m_cTotal;
            int    iSegment = calcSegmentIndex(iSlot);
            long[] alSlots  = m_aalStorage[iSegment];
            int    cIndexes = m_cIndexes;
            assert iIndex < cIndexes;
            alSlots[(iSlot - calcFirstSlotIndex(iSegment)) * cIndexes + iIndex] = lValue;
            }

        /**
         * For any index <tt>&gt; 0</tt>, count the number of values in the
         * LongStorage that are non-zero.
         *
         * @param iIndex  a valid index, other than the zero index
         *
         * @return the number of values in that index that are non-zero
         */
        public int countValues(int iIndex)
            {
            int cIndexes = m_cIndexes;
            if (iIndex < 1 || iIndex >= cIndexes)
                {
                throw new IllegalArgumentException("index (" + iIndex
                        + ") must be in the range 0 < index < " + cIndexes);
                }

            int      cNonZero   = 0;
            long[][] aalStorage = m_aalStorage;
            for (int iSegment = 0, cSegments = aalStorage.length; iSegment < cSegments; ++iSegment)
                {
                long[] alValues = aalStorage[iSegment];
                for (int ofValue = iIndex, cValues = alValues.length; ofValue < cValues; ofValue += cIndexes)
                    {
                    if (alValues[ofValue] != 0L)
                        {
                        ++cNonZero;
                        }
                    }
                }

            return cNonZero;
            }

        /**
         * Reset all of the values in a specified slot to zero.
         *
         * @param iSlot  the slot index to reset
         */
        public void reset(int iSlot)
            {
            // determine the segment that holds the slot and where the slot
            // is located within that segment
            assert iSlot >= 0 && iSlot < m_cTotal;
            int    iSegment = calcSegmentIndex(iSlot);
            long[] alSlots  = m_aalStorage[iSegment];
            int    cIndexes = m_cIndexes;
            int    ofSlot   = (iSlot - calcFirstSlotIndex(iSegment)) * cIndexes;
            for (int iIndex = 0; iIndex < cIndexes; ++iIndex)
                {
                alSlots[ofSlot + iIndex] = 0L;
                }
            }

        /**
         * Copy the contents of one slot to another.
         *
         * @param iSlotSrc   the slot to copy from
         * @param iSlotDest  the slot to copy to
         */
        public void copy(int iSlotSrc, int iSlotDest)
            {
            long[][] aalStorage = m_aalStorage;
            int      cIndexes   = m_cIndexes;

            int    iSegSrc  = calcSegmentIndex(iSlotSrc);
            int    ofSrc    = (iSlotSrc - calcFirstSlotIndex(iSegSrc)) * cIndexes;

            int    iSegDest = calcSegmentIndex(iSlotDest);
            int    ofDest   = (iSlotDest - calcFirstSlotIndex(iSegDest)) * cIndexes;

            System.arraycopy(aalStorage[iSegSrc], ofSrc, aalStorage[iSegDest], ofDest, cIndexes);
            }

        // ----- compression ----------------------------------------------

        /**
         * Determine at what point the LongStorage should be compressed.
         */
        protected void recalculateShrinkageThreshold()
            {
            // shrink when 1 3/8 times the size of the last segment is free
            long[][] aalStorage    = m_aalStorage;
            int      cSegments     = aalStorage.length;
            int      cLastSegSlots = cSegments == 0 ? calcSegmentSize(0) : aalStorage[cSegments-1].length;
            m_cFreeThreshold = cLastSegSlots + (cLastSegSlots >>> 2) + (cLastSegSlots >>> 3) + 1;
            }

        /**
         * Determine if the LongStorage should be compressed.
         *
         * @return true iff the LongStorage should be compressed
         *
         * @see #compressBegin()
         */
        public boolean isCompressionIndicated()
            {
            return m_cFree > m_cFreeThreshold;
            }

        /**
         * Begin the compression of the LongStorage into the minimum number of
         * contiguous slots.
         * <p>
         * Compression is a 4-step process:
         * <ol><li>The applicability of compression is determined using
         * {@link #isCompressionIndicated()};</li>
         * <li>The process is initiated by calling {@link #compressBegin()};</li>
         * <li>For each slot <i>i</i> that has been reserved, if
         * <tt>(<i>i</i> &gt;= {@link #getSlotCount()})</tt>, the reservation
         * must be relocated by calling
         * {@link #compressRelocate(int) compressRelocate(<i>i</i>)};</li>
         * <li>The process is concluded by calling
         * {@link #compressEnd()}.</li></ol>
         * It is the responsibility of the caller to ensure that no
         * multi-threaded access to the LongStorage occurs during this
         * process.
         */
        public void compressBegin()
            {
            // rewrite the free list to include only those slots that are in
            // the range 0..getSlotCount()
            long[][] aalStorage = m_aalStorage;
            int      cIndexes   = m_cIndexes;
            int      cSlots     = getSlotCount();
            int      iFree      = -1;
            int      iCur       = m_iFreeSlot;
            int      cFree      = 0;
            while (iCur >= 0)
                {
                int    iSegment = calcSegmentIndex(iCur);
                long[] alCur    = aalStorage[iSegment];
                int    ofCur    = (iCur - calcFirstSlotIndex(iSegment)) * cIndexes;

                int iNext = (int) alCur[ofCur];
                if (iCur < cSlots)
                    {
                    alCur[ofCur] = iFree;
                    iFree = iCur;
                    ++cFree;
                    }

                iCur = iNext;
                }
            m_iFreeSlot = iFree;
            m_cFree     = cFree;

            // shrink the capacity to the area that we are compressing into
            m_cTotal = cSlots;
            }

        /**
         * Relocate a reserved slot from a slot index that is at or beyond
         * {@link #getSlotCount()}.
         *
         * @param iSlot  a slot index for a reserved slot whose index is at or
         *               beyond {@link #getSlotCount()}
         *
         * @return the new slot index for the specified reserved slot
         *
         * @see #compressBegin()
         */
        public int compressRelocate(int iSlot)
            {
            int iSlotNew = iSlot;
            int cSlots   = getCapacity();
            if (iSlotNew >= cSlots)
                {
                iSlotNew = reserveSlot();
                assert iSlotNew >= 0 && iSlotNew < cSlots;
                copy(iSlot, iSlotNew);

                // note that the old slot is not cleaned out or added to the
                // free list; this work is the responsibility of compressEnd()
                }

            return iSlotNew;
            }

        /**
         * Complete the LongStorage compression process.
         *
         * @see #compressBegin()
         */
        public void compressEnd()
            {
            long[][] aalStorage = m_aalStorage;
            int cSlots     = getSlotCount();
            int iSlotFree  = cSlots;
            int iSegFree   = calcSegmentIndex(iSlotFree);
            int cOldSegs   = aalStorage.length;
            int cNewSegs   = iSegFree + 1;
            int cIndexes   = getIndexCount();
            int ofSlotFree = (iSlotFree - calcFirstSlotIndex(iSegFree)) * cIndexes;
            int cFree      = 0;
            if (ofSlotFree > 0)
                {
                m_iFreeSlot = iSlotFree;

                long[] alFree = aalStorage[iSegFree];
                int    ofEnd  = alFree.length;
                do
                    {
                    // store the "next" pointer of the free list in the first
                    // index of the slot
                    int ofSlotNext = ofSlotFree + cIndexes;
                    alFree[ofSlotFree] = ofSlotNext < ofEnd ? ++iSlotFree : -1;

                    // reset the remainder of the slot
                    for (int i = 1; i < cIndexes; ++i)
                        {
                        alFree[ofSlotFree + i] = 0L;
                        }

                    ofSlotFree = ofSlotNext;
                    ++cFree;
                    }
                while (ofSlotFree < ofEnd);
                }
            else
                {
                // the last segment containing reserved slots is entirely full
                // (i.e. there are no free slots); drop all following segments
                m_iFreeSlot = -1;
                --cNewSegs;
                }

            if (cNewSegs < cOldSegs)
                {
                long[][] aalNew = new long[cNewSegs][];
                System.arraycopy(aalStorage, 0, aalNew, 0, cNewSegs);
                m_aalStorage = aalNew;
                }

            // recalculate the capacity & free count
            m_cTotal = cSlots + cFree;
            m_cFree  = cFree;
            recalculateShrinkageThreshold();
            }

        // ----- internal -------------------------------------------------

        /**
         * Given an array segment index, determine the size of the array in slots.
         *
         * @param iSegment  the segment index
         *
         * @return the number of slots that the segment will hold
         */
        protected static int calcSegmentSize(int iSegment)
            {
            // the segment array is made up of "groups" of 4 segments of the same
            // size, so the size of a given segment is given by the size of the
            // "group" that it belongs to (iSegment >> 2).
            // The size of the first group (group 0) is 1 << 4, followed by
            // 1 << 5 and so on.

            assert iSegment >= 0;
            return 1 << ((iSegment >> 2) + 4);
            }

        /**
         * Give a slot index, determine what segment index the slot will be
         * found in.
         *
         * @param iSlot  the slot index
         *
         * @return the segment index
         */
        protected static int calcSegmentIndex(int iSlot)
            {
            assert iSlot >= 0;

            // in general, the storage is composed of four arrays of an equal
            // size (called a group) followed by four arrays of twice that size
            // and so on; the idea is that if the last slot of the fourth array
            // of a group could always be slot number 2^n-1 for some n,
            // and thus the first slot of the next segment (which is the first
            // segment of the exponentially larger size) is at slot number
            // 2^n, then we can determine the segment size by the binary
            // magnitude (the position of the most significant bit) of the
            // slot number, and the array number within the group of four is
            // specified by the next two bits. the only problem (with
            // the first segment size being 16, for example) is that the first
            // index of the first array is zero instead of being 64, and the
            // last index of the fourth array is 63 instead of being 127, so
            // all we have to do is to add a base offset of 4 * 16 (the size
            // of the first segment).
            iSlot += 64;
            int nMSB  = Integer.highestOneBit(iSlot);
            int ofMSB = Integer.numberOfTrailingZeros(nMSB);
            return ((ofMSB - 6) * 4) + ((iSlot & ~nMSB) >>> (ofMSB - 2));
            }

        /**
         * Given a segment index, determine the slot index of the first slot
         * that is stored in the segment.
         *
         * @param iSegment  the segment index
         *
         * @return the slot index of the first slot stored in the specified
         *         segment
         */
        protected static int calcFirstSlotIndex(int iSegment)
            {
            // The segments are arranged in "groups" of 4, each having identical
            // size.  The expression (iSegment >>> 2) gives the group of the
            // segment index, and (iSegment & 0x3) gives the index of the segment
            // within its group.  We then combine the segment # within the group
            // with 0x4 and left-shift it by the group index, which will yield
            // the correct "first slot index" (except that it is offset by 64)

            assert iSegment >= 0;
            return ((0x4 | (iSegment & 0x3)) << (4 + (iSegment >>> 2))) - 64;
            }

        /**
         * Print out the internal contents and structure of the LongStorage.
         */
        void dump()
            {
            long[][] aal  = m_aalStorage;
            int cSegments = aal == null ? 0 : aal.length;
            int cIndexes  = m_cIndexes;

            System.out.println("LongStorage{"
                    + "total-slots=" + m_cTotal
                    + ", free-slots=" + m_cFree
                    + ", first-free=" + m_iFreeSlot
                    + ", free-threshold=" + m_cFreeThreshold
                    + ", index-count=" + cIndexes
                    + ", array=" + (aal == null ? "null" : "[" + cSegments + "]"));

            int iSlot = 0;
            for (int i = 0; i < cSegments; ++i)
                {
                long[] al = aal[i];
                System.out.println("  Segment[" + i + "]="
                        + (al == null ? "null" : al.length + " elements:"));

                if (al != null)
                    {
                    int cElements = al.length;
                    if (cElements % cIndexes == 0)
                        {
                        int cRows = cElements / cIndexes;
                        int cch   = String.valueOf(iSlot + cRows).length();
                        for (int iRow = 0; iRow < cRows; ++iRow)
                            {
                            StringBuilder sb = new StringBuilder();
                            String sNum = "      " + iSlot++;
                            sb.append("  ")
                              .append(sNum.substring(sNum.length() - cch, sNum.length()))
                              .append(": ");
                            for (int iCol = 0; iCol < cIndexes; ++iCol)
                                {
                                sNum = "            " + al[iRow * cIndexes + iCol];
                                sb.append(" ")
                                  .append(sNum.substring(sNum.length()-12, sNum.length()));
                                }
                            System.out.println(sb.toString());
                            }
                        }
                    else
                        {
                        System.out.println("Invalid number of elements! (not divisible by " + cIndexes + ")");
                        break;
                        }
                    }
                }

            System.out.println("}");
            }

        // ----- data members ---------------------------------------------

        /**
         * Empty storage.
         */
        protected final static long[][] EMPTY = new long[0][];

        /**
         * The internal storage for the LongStorage, divided into segments,
         * with each segment being an array of long values. A segment is
         * further divided into slots, such that the first slot is found at
         * element zero in the array, and the second slot is found {@link
         * #m_cIndexes} elements after that. A slot is further divided into
         * indexed values, with the first element of the slot containing the
         * first indexed value, the second element of the slot containing the
         * second indexed value, and so on.
         * <p>
         * For example:
         * 16,16,16,16,32,32,32,32,64,64,64,64,128,128,128,128,256,...
         */
        protected long[][] m_aalStorage;

        /**
         * The total number of indexes. This is the number of long values that
         * can be stored in a single slot.
         */
        protected int m_cIndexes;

        /**
         * The total number of slots.
         */
        protected int m_cTotal;

        /**
         * The number of slots that are available ("free").
         */
        protected int m_cFree;

        /**
         * The index of the first free slot, or <tt>-1</tt>.
         */
        protected int m_iFreeSlot;

        /**
         * The number of free slots at which compression becomes indicated.
         */
        protected int m_cFreeThreshold;
        }

    // ----- inner class: ChainedLock -------------------------------------

    /**
     * A ChainedLock is a {@link Lock} implementation that represents a composition
     * of two component locks, parent and child.  All operations on this lock will
     * first be performed on the parent lock, and only upon successful completion
     * be performed on the child lock.
     * <p>
     * A ChainedLock could be used to manage multiple logical levels of concurrency
     * control across multiple components without exposing multiple resource locks
     * and thus imposing a locking order that reduces the possibility of introducing
     * deadlocks.
     * <p>
     * Note: a ChainedLock could be used to represent a chain of more than two
     * Locks by constructing a "chain of chains".
     */
    protected static class ChainedLock
            implements Lock
        {
        /**
         * Construct a new ChainedLock composed of the specified parent and child
         * locks.
         *
         * @param lockParent  the parent lock
         * @param lockChild   the child lock
         */
        public ChainedLock(Lock lockParent, Lock lockChild)
            {
            assert lockParent != lockChild;
            assert lockParent != null;
            assert lockChild  != null;

            f_lockParent = lockParent;
            f_lockChild  = lockChild;
            }

        // ----- Lock interface -------------------------------------------

        /**
         * {@inheritDoc}
         */
        public void lock()
            {
            f_lockParent.lock();
            try
                {
                f_lockChild.lock();
                }
            catch (Throwable t)
                {
                f_lockParent.unlock();

                throw Base.ensureRuntimeException(t);
                }
            }

        /**
         * {@inheritDoc}
         */
        public void lockInterruptibly()
                throws InterruptedException
            {
            Blocking.lockInterruptibly(f_lockParent);
            try
                {
                Blocking.lockInterruptibly(f_lockChild);
                }
            catch (Throwable t)
                {
                f_lockParent.unlock();
                if (t instanceof InterruptedException)
                    {
                    Thread.currentThread().interrupt();
                    }

                throw Base.ensureRuntimeException(t);
                }
            }

        /**
         * {@inheritDoc}
         */
        public boolean tryLock()
            {
            if (f_lockParent.tryLock())
                {
                try
                    {
                    if (f_lockChild.tryLock())
                        {
                        return true;
                        }
                    else
                        {
                        f_lockParent.unlock();
                        return false;
                        }
                    }
                catch (Throwable t)
                    {
                    f_lockParent.unlock();

                    throw Base.ensureRuntimeException(t);
                    }
                }
            else
                {
                return false;
                }
            }

        /**
         * {@inheritDoc}
         */
        public boolean tryLock(long cTime, TimeUnit unit)
                throws InterruptedException
            {
            long ldtStart = Base.getSafeTimeMillis();
            if (Blocking.tryLock(f_lockParent, cTime, unit))
                {
                long ldtEnd        = Base.getSafeTimeMillis();
                long cMillisRemain = unit.toMillis(cTime) - (ldtEnd - ldtStart);

                try
                    {
                    if (Blocking.tryLock(f_lockChild, cMillisRemain, TimeUnit.MILLISECONDS))
                        {
                        return true;
                        }
                    else
                        {
                        f_lockParent.unlock();
                        return false;
                        }
                    }
                catch (Throwable t)
                    {
                    f_lockParent.unlock();

                    if (t instanceof InterruptedException)
                        {
                        Thread.currentThread().interrupt();
                        }

                    throw Base.ensureRuntimeException(t);
                    }
                }
            else
                {
                return false;
                }
            }

        /**
         * {@inheritDoc}
         */
        public void unlock()
            {
            f_lockParent.unlock();
            f_lockChild .unlock();
            }

        /**
         * {@inheritDoc}
         */
        public Condition newCondition()
            {
            throw new UnsupportedOperationException();
            }

        // ----- data members ---------------------------------------------

        /**
         * The "parent" constituent lock.
         */
        protected final Lock f_lockParent;

        /**
         * The "child" constituent lock.
         */
        protected final Lock f_lockChild;
        }
    }
