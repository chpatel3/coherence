/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.net.cache;

import com.oracle.coherence.common.base.Disposable;

import com.tangosol.io.BinaryStore;
import com.tangosol.io.BinaryStoreManager;

import com.tangosol.util.AbstractKeyBasedMap;
import com.tangosol.util.Base;
import com.tangosol.util.Binary;
import com.tangosol.util.BinaryLongMap;
import com.tangosol.util.BitHelper;
import com.tangosol.util.ClassHelper;
import com.tangosol.util.ExternalizableHelper;
import com.tangosol.util.Filter;
import com.tangosol.util.MapListener;
import com.tangosol.util.MapListenerSupport;
import com.tangosol.util.MultiBinaryLongMap;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import java.util.concurrent.locks.ReentrantLock;


/**
 * CompactSerializationCache is an implementation of {@link ConfigurableCacheMap}
 * which is optimized for compact on-heap footprint.
 * <p>
 * This implementation is <i>partially thread-safe</i>. It assumes that multiple
 * threads will not be accessing the same keys at the same time, nor would
 * any other thread be accessing this cache while a clear() operation were
 * going on, for example. In other words, this implementation assumes that
 * access to this cache is either single-threaded or gated through an object
 * like {@link com.tangosol.util.WrapperConcurrentMap}.
 *
 * @author rhl  2013.01.23
 * @since Coherence 12.1.2
 */
public class CompactSerializationCache
        extends AbstractKeyBasedMap
        implements ConfigurableCacheMap, Disposable
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Construct a CompactSerializationCache using the specified BinaryStoreManager
     * and classloader.
     *
     * @param mgr     the BinaryStoreManager to use to create the BinaryStore
     * @param loader  the ClassLoader to use for deserialization
     */
    public CompactSerializationCache(BinaryStoreManager mgr, ClassLoader loader)
        {
        this(mgr, loader, false);
        }

    /**
     * Construct a CompactSerializationCache using the specified BinaryStoreManager,
     * optionally storing only Binary keys and values
     *
     * @param mgr      the BinaryStoreManager to use to create the BinaryStore
     * @param fBinary  true iff only Binary keys and values are to be stored
     */
    public CompactSerializationCache(BinaryStoreManager mgr, boolean fBinary)
        {
        this(mgr, null, fBinary);
        }

    /**
     * Construct a CompactSerializationCache using the specified BinaryStoreManager.
     *
     * @param mgr      the BinaryStoreManager to use to create the BinaryStore
     * @param loader   the ClassLoader to use for deserialization
     * @param fBinary  true iff only Binary keys and values are to be stored
     */
    protected CompactSerializationCache(BinaryStoreManager mgr, ClassLoader loader, boolean fBinary)
        {
        BinaryStore store = mgr.createBinaryStore();
        if (store instanceof BinaryStore.KeySetAware)
            {
            f_store   = (BinaryStore.KeySetAware) store;
            f_mblm    = f_store.getMultiBinaryLongMap();
            f_fBinary = fBinary;
            f_loader  = loader;

            m_dflPruneLevel = DEFAULT_PRUNE;
            }
        else
            {
            throw new UnsupportedOperationException(
                    "CompactSerializationCache requires a KeySetAware BinaryStore implementation");
            }
        }


    // ----- accessors ------------------------------------------------------

    /**
     * Returns the BinaryStore that this map uses for its storage.
     *
     * @return the BinaryStore
     */
    public BinaryStore.KeySetAware getBinaryStore()
        {
        return f_store;
        }

    /**
     * Return the configured ClassLoader, or null if none is configured.
     *
     * @return the classloader to use for deserialization, or null
     */
    public ClassLoader getClassLoader()
        {
        return f_loader;
        }

    /**
     * Determine if the keys and values in this map are known to be all Binary.
     *
     * @return true if all keys and values will be Binary to start with, and
     *         thus will not require conversion
     */
    public boolean isBinaryMap()
        {
        return f_fBinary;
        }

    /**
     * Return the BinaryLongMap associating the entries in this cache map with
     * their corresponding expiry.
     * <p>
     * Note: it is legal for an expiry to be 0 (meaning no-expiry), but the
     *       contract of the BinaryLongMap considers 0 to be missing, so operations
     *       such as size() and keys() on the returned BLM will not reflect
     *       the entire set of keys in this cache
     *
     * @return the BinaryLongMap holding the expiry information
     */
    public BinaryLongMap getExpiryMap()
        {
        return m_blmExpiry;
        }

    /**
     * Set the BinaryLongMap to use to associate the entries in this cache map
     * with their corresponding expiry.
     *
     * @param blmExpiry  the BinaryLongMap to use to hold the expiry information
     */
    protected void setExpiryMap(BinaryLongMap blmExpiry)
        {
        m_blmExpiry = blmExpiry;
        }

    /**
     * Return a BinaryLongMap whose values have no meaning, but whose keys can
     * be used internally to detect the presence of an entry in the cache map.
     * <p>
     * Note: the returned BinaryLongMap may not be mutated.
     *
     * @return a BinaryLongMap containing all keys in this CacheMap, mapped to
     *         an unspecified value
     */
    protected BinaryLongMap getKeyMap()
        {
        return f_mblm.getPrimaryBinaryLongMap();
        }

    /**
     * Return the BinaryLongMap associating the entries in this cache map with
     * their corresponding last "touch" time.  A "touch" is any access (read
     * or write) to an entry.
     *
     * @return the BinaryLongMap holding the touch-time information
     */
    public BinaryLongMap getTouchTimeMap()
        {
        return m_blmTouchTime;
        }

    /**
     * Return the BinaryLongMap to use to associate the entries in this cache
     * map with their corresponding last "touch" time.  A "touch" is any access
     * (read or write) to an entry.
     *
     * @param blmTouch  the BinaryLongMap holding the touch-time information
     */
    protected void setTouchTimeMap(BinaryLongMap blmTouch)
        {
        m_blmTouchTime = blmTouch;
        }

    /**
     * Return the BinaryLongMap associating the entries in this cache map with
     * their corresponding touch count.  A "touch" is any access (read or write)
     * to an entry.
     *
     * @return the BinaryLongMap holding the touch count information
     */
    public BinaryLongMap getTouchCountMap()
        {
        return m_blmTouchCount;
        }

    /**
     * Return the BinaryLongMap to use to associate the entries in this cache
     * map with their corresponding "touch" count.  A "touch" is any access
     * (read or write) to an entry.
     *
     * @param blmTouch  the BinaryLongMap holding the touch count information
     */
    protected void setTouchCountMap(BinaryLongMap blmTouch)
        {
        m_blmTouchCount = blmTouch;
        }

    /**
     * Return the BinaryLongMap associating the entries in this cache map with
     * their size in "units".
     *
     * @return the BinaryLongMap holding the units
     */
    public BinaryLongMap getUnitsMap()
        {
        return m_blmUnits;
        }

    /**
     * Set the BinaryLongMap to use to associate the entries in this cache map
     * with their size in "units".
     *
     * @param blmUnits  the BinaryLongMap to use for storing the units
     */
    protected void setUnitsMap(BinaryLongMap blmUnits)
        {
        m_blmUnits = blmUnits;
        }

    /**
     * Determine if the cache map has any listeners at all.
     *
     * @return true iff this cache map has at least one MapListener
     */
    protected boolean hasListeners()
        {
        // m_listenerSupport defaults to null, and it is reset to null when
        // the last listener is unregistered
        return m_listenerSupport != null;
        }

    /**
     * Returns the CacheStatistics for this cache.
     * <p>
     * Note: this method is invoked reflectively by the CacheModel
     *
     * @return a CacheStatistics object
     */
    public CacheStatistics getCacheStatistics()
        {
        return f_stats;
        }

    /**
     * Return whether this CompactSerializationCache instance is in blind mode.
     * Blind mode allows this Map implementation to forgo the often costly
     * parts of the Map API, in particular returning the previous value for both
     * remove and put operations.
     *
     * @return  whether this CompactSerializationCache is operating in a blind
     *          mode
     */
    public boolean isBlind()
        {
        return m_fBlind;
        }

    /**
     * Set whether this CompactSerializationCache instance should operate under
     * a blind mode.
     *
     * @param fBlind  whether this CompactSerializationCache should operate
     *                under a blind mode
     */
    public void setBlind(boolean fBlind)
        {
        m_fBlind = fBlind;
        }


    // ----- Map methods --------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public int size()
        {
        checkExpiry(true);

        return getKeyMap().size();
        }

    /**
     * {@inheritDoc}
     */
    public boolean containsKey(Object oKey)
        {
        Binary binKey = toBinary(oKey);

        checkExpiry(binKey);

        return getBinaryStore().containsKey(binKey);
        }

    /**
     * {@inheritDoc}
     */
    public boolean containsValue(Object oValue)
        {
        checkExpiry(true);

        Binary      binValue = toBinary(oValue);
        BinaryStore store    = getBinaryStore();
        for (Iterator<Binary> iter = store.keys(); iter.hasNext(); )
            {
            Binary binKey = iter.next();
            if (Base.equals(binValue, store.load(binKey)))
                {
                return true;
                }
            }

        return false;
        }

    /**
     * {@inheritDoc}
     */
    public Object get(Object oKey)
        {
        Binary binKey = toBinary(oKey);

        checkExpiry(binKey);

        long   ldtNow   = Base.getSafeTimeMillis();
        Binary binValue = getBinaryStore().load(binKey);
        if (binValue == null)
            {
            f_stats.registerMiss(ldtNow);
            }
        else
            {
            f_stats.registerHit(ldtNow);

            touch(binKey);
            }

        return fromBinary(binValue);
        }

    /**
     * {@inheritDoc}
     */
    public Object remove(Object oKey)
        {
        Binary binKey = toBinary(oKey);

        checkExpiry(binKey);

        Object oValueOld = removeInternal(oKey, binKey, isBlind());

        return oValueOld == OBJECT_EXISTS ? null : oValueOld;
        }

    /**
     * {@inheritDoc}
     */
    public void clear()
        {
        checkExpiry(true);

        if (hasListeners())
            {
            // now shoot ourselves in the head...
            for (Iterator iter = getKeyMap().keys(); iter.hasNext(); )
                {
                remove(iter.next());
                }

            assert f_atomicCurUnits.get() == 0L;
            }
        else
            {
            // no listeners; just nuke it all
            getBinaryStore().eraseAll();

            // clear the units
            f_atomicCurUnits.set(0L);
            }
        }

    /**
     * {@inheritDoc}
     */
    public Object put(Object oKey, Object oValue)
        {
        return put(oKey, oValue, EXPIRY_DEFAULT);
        }

    /**
     * {@inheritDoc}
     */
    public Object put(Object oKey, Object oValue, long cExpiry)
        {
        BinaryStore.KeySetAware store  = getBinaryStore();
        Binary                  binKey = toBinary(oKey);

        checkExpiry(binKey);

        Binary  binValueOld = isBlind() ? null : store.load(binKey);
        Binary  binValue    = toBinary(oValue);
        Object  oOrig       = fromBinary(binValueOld);
        int     cUnitsOld   = getEntryUnits(binKey);
        boolean fUpdate     = cUnitsOld > 0 || (!isBinaryMap() && store.containsKey(binKey));

        // raise the event prior to the store as a DeferredCacheEvent.getOldValue
        // call (possible until dispatchEvent returns control) should return
        // the correct result and not the result of the impending store operation
        if (hasListeners())
            {
            dispatchEvent(fUpdate ? CacheEvent.ENTRY_UPDATED : CacheEvent.ENTRY_INSERTED,
                          binKey, oKey, oOrig, oValue, false);
            }

        if (Base.equals(binValue, binValueOld))
            {
            // no change; nothing to update in the BinaryStore
            oOrig = oValue;
            }
        else
            {
            // store into the BinaryStore as either the value is new, changed,
            // or this is a blind map and in blind mode we prefer the re-write
            // of an equivalent update rather than the read & probable write
            store.store(binKey, binValue);
            }

        // update cache stats
        long ldtNow = Base.getSafeTimeMillis();
        f_stats.registerPut(ldtNow);

        // update touch count and time
        touch(binKey);

        // register the entry for expiry
        if (cExpiry == EXPIRY_DEFAULT)
            {
            cExpiry = getExpiryDelay();  // either 0 (never) or an offset
            }
        else if (cExpiry == EXPIRY_NEVER)
            {
            cExpiry = 0L;
            }

        if (fUpdate || cExpiry != 0L)
            {
            // if this is an insert and the expiry is "never", we can skip expiry
            // registration; in all other cases, we need to register the expiry
            registerExpiry(binKey, cExpiry);
            }

        // adjust the units
        int cUnitsNew = calculateUnits(binKey, binValue);

        f_atomicCurUnits.addAndGet(cUnitsNew - cUnitsOld);

        updateUnits(binKey, cUnitsNew);

        // check for eviction
        checkSize();

        return oOrig;
        }


    // ----- AbstractKeyBasedMap methods ------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    protected Set instantiateKeySet()
        {
        return new KeySet();
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Iterator iterateKeys()
        {
        return getBinaryStore().keys();
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean removeBlind(Object oKey)
        {
        Binary binKey = toBinary(oKey);

        checkExpiry(binKey);

        return removeInternal(oKey, binKey, true) != null;
        }


    // ----- ConfigurableCacheMap methods -----------------------------------

    /**
     * {@inheritDoc}
     */
    public int getUnits()
        {
        return toExternalUnits(f_atomicCurUnits.get(), getUnitFactor());
        }

    /**
     * {@inheritDoc}
     */
    public int getHighUnits()
        {
        return toExternalUnits(m_cMaxUnits, getUnitFactor());
        }

    /**
     * {@inheritDoc}
     */
    public void setHighUnits(int cMax)
        {
        assert cMax >= 0;

        long    cUnits  = toInternalUnits(cMax, getUnitFactor());
        boolean fShrink = cUnits < m_cMaxUnits;

        m_cMaxUnits   = cUnits;
        m_cPruneUnits = cUnits == Long.MAX_VALUE ? cUnits : (long) (m_dflPruneLevel * cUnits);

        configureEviction();

        checkSize();
        }

    /**
     * {@inheritDoc}
     */
    public int getLowUnits()
        {
        return toExternalUnits(m_cPruneUnits, getUnitFactor());
        }

    /**
     * {@inheritDoc}
     */
    public void setLowUnits(int cUnits)
        {
        m_cPruneUnits = toInternalUnits(cUnits, getUnitFactor());
        }

    /**
     * {@inheritDoc}
     */
    public int getUnitFactor()
        {
        return m_nUnitFactor;
        }

    /**
     * {@inheritDoc}
     */
    public void setUnitFactor(int nFactor)
        {
        m_nUnitFactor = nFactor;
        }

    /**
     * {@inheritDoc}
     */
    public void evict(Object oKey)
        {
        int cUnitsEvicted = evictInternal(toBinary(oKey));
        if (cUnitsEvicted > 0)
            {
            f_atomicCurUnits.addAndGet(-cUnitsEvicted);
            }
        }

    /**
     * {@inheritDoc}
     */
    public void evictAll(Collection colKeys)
        {
        for (Object oKey : colKeys)
            {
            evict(oKey);
            }
        }

    /**
     * {@inheritDoc}
     */
    public void evict()
        {
        // Note: the order is important here
        checkExpiry(true);
        checkSize(false);
        }

    /**
     * {@inheritDoc}
     */
    public EvictionApprover getEvictionApprover()
        {
        return m_apprvrEvict;
        }

    /**
     * {@inheritDoc}
     */
    public void setEvictionApprover(EvictionApprover approver)
        {
        m_apprvrEvict = approver;
        }

    /**
     * {@inheritDoc}
     */
    public int getExpiryDelay()
        {
        return m_cExpiryDelay;
        }

    /**
     * {@inheritDoc}
     */
    public void setExpiryDelay(int cMillis)
        {
        m_cExpiryDelay = cMillis;
        }

    /**
     * {@inheritDoc}
     */
    public long getNextExpiryTime()
        {
        BinaryLongMap blmExpiry = getExpiryMap();
        if (blmExpiry == null)
            {
            return 0;
            }

        MinExpiryVisitor visitor = new MinExpiryVisitor();
        blmExpiry.visitAll(visitor);
        return visitor.m_ldtExpiryMin;
        }

    /**
     * {@inheritDoc}
     */
    public ConfigurableCacheMap.Entry getCacheEntry(Object oKey)
        {
        Binary binKey = toBinary(oKey);

        checkExpiry(binKey);

        return getKeyMap().get(binKey) == 0L ? null : getCacheEntryInternal(binKey, oKey);
        }

    /**
     * {@inheritDoc}
     */
    public EvictionPolicy getEvictionPolicy()
        {
        InternalEvictionPolicy policyInternal = getInternalEvictionPolicy();
        return policyInternal == null ? null : policyInternal.getConfiguredPolicy();
        }

    /**
     * Return the InternalEvictionPolicy used by this CompactSerializationCache.
     * <p>
     * Note: this method returns the actual instance of EvictionPolicy being
     *       used by this cache, which may differ in implementation from the
     *       configured policy returned by {@link #getEvictionPolicy}.
     *
     * @return the internal eviction policy
     */
    protected InternalEvictionPolicy getInternalEvictionPolicy()
        {
        return m_policy;
        }

    /**
     * {@inheritDoc}
     */
    public void setEvictionPolicy(EvictionPolicy policy)
        {
        EvictionPolicy policyOld = getEvictionPolicy();
        if ((policyOld == null || policyOld == policy) && isEmpty())
            {
            if (policy == null || policy == LocalCache.INSTANCE_LRU)
                {
                // default policy is LRU instead of Hybrid (as in LocalCache)
                // as the Hybrid policy uses additional footprint for touch-count
                policy = new LRUEvictionPolicy();
                }
            else if (policy == LocalCache.INSTANCE_LFU)
                {
                policy = new LFUEvictionPolicy();
                }
            else if (policy == LocalCache.INSTANCE_HYBRID)
                {
                policy = new HybridEvictionPolicy();
                }
            else
                {
                policy = new WrapperEvictionPolicy(policy);
                }

            m_policy = (InternalEvictionPolicy) policy;

            configureEviction();
            }
        else
            {
            throw new UnsupportedOperationException(
                    "The EvictionPolicy cannot be reset.");
            }
        }

    /**
     * {@inheritDoc}
     */
    public UnitCalculator getUnitCalculator()
        {
        return m_calculator;
        }

    /**
     * {@inheritDoc}
     */
    public void setUnitCalculator(UnitCalculator calculator)
        {
        UnitCalculator calculatorOld = getUnitCalculator();
        if ((calculatorOld == null || calculatorOld == calculator) && isEmpty())
            {
            m_calculator = calculator;

            configureEviction();
            }
        else
            {
            throw new UnsupportedOperationException(
                    "The UnitCalculator cannot be reset.");
            }
        }


    // ----- ObservableMap methods ------------------------------------------

    /**
    * {@inheritDoc}
    */
    public void addMapListener(MapListener listener)
        {
        f_lockEvents.lock();
        try
            {
            addMapListener(listener, null, false);
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }

    /**
    * {@inheritDoc}
    */
    public void removeMapListener(MapListener listener)
        {
        f_lockEvents.lock();
        try
            {
            removeMapListener(listener, null);
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }

    /**
    * {@inheritDoc}
    */
    public void addMapListener(MapListener listener, Object oKey, boolean fLite)
        {
        azzert(listener != null);

        f_lockEvents.lock();
        try
            {
            MapListenerSupport support = m_listenerSupport;
            if (support == null)
                {
                support = m_listenerSupport = new MapListenerSupport();
                }

            support.addListener(listener, oKey, fLite);
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }

    /**
    * {@inheritDoc}
    */
    public void removeMapListener(MapListener listener, Object oKey)
        {
        azzert(listener != null);

        f_lockEvents.lock();
        try
            {
            MapListenerSupport support = m_listenerSupport;
            if (support != null)
                {
                support.removeListener(listener, oKey);
                if (support.isEmpty())
                    {
                    m_listenerSupport = null;
                    }
                }
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }

    /**
    * {@inheritDoc}
    */
    public void addMapListener(MapListener listener, Filter filter, boolean fLite)
        {
        azzert(listener != null);

        f_lockEvents.lock();
        try
            {
            MapListenerSupport support = m_listenerSupport;
            if (support == null)
                {
                support = m_listenerSupport = new MapListenerSupport();
                }

            support.addListener(listener, filter, fLite);
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }

    /**
    * {@inheritDoc}
    */
    public void removeMapListener(MapListener listener, Filter filter)
        {
        azzert(listener != null);

        f_lockEvents.lock();
        try
            {
            MapListenerSupport support = m_listenerSupport;
            if (support != null)
                {
                support.removeListener(listener, filter);
                if (support.isEmpty())
                    {
                    m_listenerSupport = null;
                    }
                }
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }


    // ----- Disposable interface -------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose()
        {
        if (f_store instanceof Disposable)
            {
            ((Disposable) f_store).dispose();
            }
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Create a DeferredCacheEvent object with the provided arguments. The
     * {@code oValueOld} may be null as the deferred event may call
     * {@link BinaryStore#load(Binary) load} upon request.
     *
     * @param nId         this event's id, one of {@link DeferredCacheEvent#ENTRY_INSERTED},
     *                    {@link DeferredCacheEvent#ENTRY_UPDATED} or {@link DeferredCacheEvent#ENTRY_DELETED}
     * @param binKey      the binary key into the map
     * @param oKey        the key into the map
     * @param oValueOld   optional old value (for update and delete events); if
     *                    not provided a call to getOldValue will load the old
     *                    value from the BinaryStore
     * @param oValueNew   the new value (for insert and update events)
     * @param fSynthetic  true iff the event is caused by the cache
     *                    internal processing such as eviction or loading
     *
     * @return a DeferredCacheEvent object
     */
    protected DeferredCacheEvent instantiateDeferredCacheEvent(int nId,
                                    final Binary binKey, Object oKey, Object oValueOld,
                                    Object oValueNew, boolean fSynthetic)
        {
        return new DeferredCacheEvent(
                        this, nId, oKey, oValueOld, oValueNew, fSynthetic)
            {
            @Override
            protected Object readOldValue()
                {
                return fromBinary(getBinaryStore().load(binKey));
                }
            };
        }

    /**
     * Dispatch the passed event.
     *
     * @param nId         this event's id, one of {@link DeferredCacheEvent#ENTRY_INSERTED},
     *                    {@link DeferredCacheEvent#ENTRY_UPDATED} or {@link DeferredCacheEvent#ENTRY_DELETED}
     * @param binKey      the binary key into the map
     * @param oKey        the key into the map
     * @param oValueOld   optional old value (for update and delete events); if
     *                    not provided a call to getOldValue will load the old
     *                    value from the BinaryStore
     * @param oValueNew   the new value (for insert and update events)
     * @param fSynthetic  true iff the event is caused by the cache
     *                    internal processing such as eviction or loading
     */
    protected void dispatchEvent(int nId, final Binary binKey, Object oKey, Object oValueOld,
                                 Object oValueNew, boolean fSynthetic)
        {
        DeferredCacheEvent event = instantiateDeferredCacheEvent(nId,
                                   binKey, oKey, oValueOld, oValueNew, fSynthetic);

        MapListenerSupport listenerSupport = m_listenerSupport;
        if (listenerSupport != null)
            {
            try
                {
                // the events can only be generated while the current thread
                // holds the monitor on this map
                f_lockEvents.lock();
                try
                    {
                    listenerSupport.fireEvent(event, false);
                    }
                finally
                    {
                    f_lockEvents.unlock();
                    }
                }
            finally
                {
                // the contract between the DeferredCacheEvent and consumers of
                // the event states consumers must call getOldValue prior to
                // returning from fireEvent; it is possible before we deactivate
                // the DCE another thread calls DCE.getOldValue, as PartitionedCache
                // will place the event on the fabric, which would result in
                // performing an unnecessary load, however will not exhibit
                // correctness side affects
                event.deactivate();
                }
            }
        }


    // ----- Object methods -------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public String toString()
        {
        return ClassHelper.getSimpleName(getClass())
            + "{BinaryStore=" + getBinaryStore() + '}';
        }


    // ----- helpers --------------------------------------------------------

    /**
     * Remove the given key from the {@link BinaryStore} only loading the value
     * if necessary.
     *
     * @param oKey    the key to be removed
     * @param binKey  the binary key to be removed
     * @param fBlind  whether the remove should be blind
     *
     * @return the previous value or an indicator ({@link #OBJECT_EXISTS}) to
     *         suggest the object did exist if {@code fBlind} is true
     */
    protected Object removeInternal(Object oKey, Binary binKey, boolean fBlind)
        {
        Object oValueRtn = null;

        BinaryStore.KeySetAware store  = getBinaryStore();
        long                    cUnits = getEntryUnits(binKey);
        boolean                 fExist = cUnits > 0 || (!isBinaryMap() && store.containsKey(binKey));
        if (fExist)
            {
            Object oValueOld;
            if (fBlind)
                {
                oValueOld = null;
                oValueRtn = OBJECT_EXISTS;
                }
            else
                {
                oValueOld = oValueRtn = fromBinary(store.load(binKey));
                }

            // raise the event prior to the erase as a DeferredCacheEvent.getOldValue
            // call (allowed until dispatchEvent returns control) should return
            // the correct result
            // Note: a blind remove may still result DCE.getOldValue calling
            //       BinaryStore.load to avoid corrupting any indices
            if (hasListeners())
                {
                dispatchEvent(CacheEvent.ENTRY_DELETED, binKey, oKey, oValueOld, null, false);
                }

            // remove from the BinaryStore
            store.erase(binKey);

            // adjust the units
            f_atomicCurUnits.addAndGet(-cUnits);
            }
        return oValueRtn;
        }

    /**
     * Ensure that the BinaryLongMaps required for eviction support are configured.
     */
    protected void configureEviction()
        {
        EvictionPolicy policy         = getInternalEvictionPolicy();
        UnitCalculator unitCalculator = getUnitCalculator();

        long    cHighUnits = m_cMaxUnits;
        boolean fUnits     = unitCalculator != null && unitCalculator != LocalCache.INSTANCE_FIXED;
        if (policy == null && !fUnits && (cHighUnits == 0 || cHighUnits == Long.MAX_VALUE))
            {
            // eviction is not configured (yet)
            return;
            }

        boolean fTouchTime  = false;
        boolean fTouchCount = false;
        if (policy instanceof LRUEvictionPolicy)
            {
            fTouchTime = true;
            }
        else if (policy instanceof LFUEvictionPolicy)
            {
            fTouchCount = true;
            }
        else if (policy instanceof HybridEvictionPolicy)
            {
            fTouchTime  = true;
            fTouchCount = true;
            }
        else
            {
            fTouchTime  = true;
            fTouchCount = true;
            }
        
        f_lockEvents.lock();
        try
            {
            if (fTouchTime && getTouchTimeMap() == null)
                {
                setTouchTimeMap(f_mblm.createBinaryIntMap());
                }
            if (fTouchCount && getTouchCountMap() == null)
                {
                setTouchCountMap(f_mblm.createBinaryIntMap());
                }
            if (fUnits && getUnitsMap() == null)
                {
                setUnitsMap(f_mblm.createBinaryIntMap());
                }
            }
        finally
            {
            f_lockEvents.unlock();
            }
        }

    /**
     * Ensure that the expiry-map is initialized.
     *
     * @return the expiry map
     */
    protected BinaryLongMap ensureExpiryMap()
        {
        BinaryLongMap blmExpiry = getExpiryMap();
        if (blmExpiry == null)
            {
            f_lockEvents.lock();
            try
                {
                if ((blmExpiry = getExpiryMap()) == null)
                    {
                    blmExpiry = f_mblm.createBinaryIntMap();
                    setExpiryMap(blmExpiry);
                    }
                }
            finally
                {
                f_lockEvents.unlock();
                }
            }

        return blmExpiry;
        }

    /**
     * Check the cache entries for expiration, removing expired entries as
     * necessary.
     *
     * @param fForce  pass true to force the expiry check, or false to only
     *                run it if it hasn't been run in a while
     */
    protected void checkExpiry(boolean fForce)
        {
        BinaryLongMap blmExpiry = getExpiryMap();
        if (blmExpiry != null &&
            f_atomicExpiringMutex.compareAndSet(false, true))
            {
            final long ldtNow  = Base.getSafeTimeMillis();
            long       ldtNext = m_ldtNextExpiryCheck;

            // avoid another thread thinking that it's time to check expiry
            m_ldtNextExpiryCheck = Long.MAX_VALUE;

            long cUnitsExpired = 0L;

            try
                {
                // double check that we need to do this
                if (!fForce && ldtNow < ldtNext)
                    {
                    return;
                    }

                // note that items with an expiry of "never" will have an
                // expiry time of 0, and thus will not be evaluated at all
                Iterator<Binary> iterExpired = blmExpiry.keys(
                        new MultiBinaryLongMap.SafePredicate()
                            {
                            public boolean evaluate(BinaryLongMap.Entry entry)
                                {
                                return ldtNow > decodeExpiry(entry.getValue());
                                }
                            });

                while (iterExpired.hasNext())
                    {
                    cUnitsExpired += evictInternal(iterExpired.next());
                    }

                ldtNext = ldtNow + EXPIRY_AUTO_CHECK_INTERVAL;
                }
            finally
                {
                assert f_atomicExpiringMutex.get();
                m_ldtNextExpiryCheck = ldtNext;

                if (cUnitsExpired != 0)
                    {
                    f_atomicCurUnits.addAndGet(-cUnitsExpired);
                    }

                f_atomicExpiringMutex.compareAndSet(true, false);
                }
            }
        }

    /**
     * Check the specified key to see if it is expired, removing it if necessary.
     * This method will also ensure that other cache entries which are ripe
     * for expiration will eventually be expired before too many accumulate.
     *
     * @param binKey  the key to check for expiration
     */
    protected void checkExpiry(Binary binKey)
        {
        BinaryLongMap blmExpiry = getExpiryMap();
        if (blmExpiry == null)
            {
            // expiry is not in use
            return;
            }

        long ldtNow = Base.getSafeTimeMillis();
        if (ldtNow > m_ldtNextExpiryCheck)
            {
            // periodically ensure that the expiry for all cache entries is
            // checked.  Though this is a bit costly of an operation, we should
            // ensure that it is done from time to time in order to minimize
            // the storage footprint

            checkExpiry(false);
            }

        long cExpiryEncoded = blmExpiry.get(binKey);
        if (cExpiryEncoded != 0L && ldtNow > decodeExpiry(cExpiryEncoded))
            {
            int cUnitsExpired = evictInternal(binKey);
            if (cUnitsExpired > 0)
                {
                f_atomicCurUnits.addAndGet(-cUnitsExpired);
                }
            }
        }

    /**
     * Check the current size of the cache to see that it is within the configured
     * {@link #setHighUnits high-units}, evicting entries according to the
     * configured {@link ConfigurableCacheMap.EvictionPolicy} if necessary.
     */
    protected void checkSize()
        {
        checkSize(true);
        }

    /**
     * Check the current size of the cache to see that it is within the configured
     * {@link #setHighUnits high-units}, evicting entries according to the
     * configured {@link ConfigurableCacheMap.EvictionPolicy} if necessary.
     *
     * @param fCheckExpiry  true iff the expiry should be checked if eviction is
     *                      called for
     */
    protected void checkSize(boolean fCheckExpiry)
        {
        long cHighUnits = m_cMaxUnits;
        if (f_atomicCurUnits.get() <= cHighUnits ||
                m_apprvrEvict == EvictionApprover.DISAPPROVER)
            {
            return;
            }

        if (fCheckExpiry)
            {
            // first, get all of the entries that are expired
            checkExpiry(true);
            if (f_atomicCurUnits.get() <= cHighUnits)
                {
                return;
                }
            }

        // only one thread needs to do the eviction
        if (f_atomicEvictingMutex.compareAndSet(false, true))
            {
            long ldtStart = Base.getSafeTimeMillis();
            try
                {
                getInternalEvictionPolicy().requestEviction(getLowUnits());
                }
            finally
                {
                assert f_atomicEvictingMutex.get();
                f_atomicEvictingMutex.compareAndSet(true, false);
                }

            f_stats.registerCachePrune(ldtStart);
            }
        }

    /**
     * Register a "touch" of the specified key.
     *
     * @param binKey  the key that was touched
     */
    protected void touch(Binary binKey)
        {
        InternalEvictionPolicy policy = getInternalEvictionPolicy();
        if (policy != null)
            {
            policy.entryTouched(binKey);
            }
        }

    /**
     * Attempt to evict the cache entry for the specified key from the cache as
     * a result of either expiration or size-triggered eviction, returning the
     * unit-size of the evicted entry, or 0 if the eviction was disallowed by
     * the {@link ConfigurableCacheMap.EvictionApprover}.
     *
     * @param binKey  the key to evict
     *
     * @return the number of units evicted
     */
    protected int evictInternal(Binary binKey)
        {
        EvictionApprover approver = m_apprvrEvict;
        if (approver == null || approver.isEvictable(getCacheEntryInternal(binKey)))
            {
            int cUnits = getEntryUnits(binKey);

            // raise events
            if (hasListeners())
                {
                dispatchEvent(CacheEvent.ENTRY_DELETED, binKey, fromBinary(binKey),
                              null, null, true);
                }

            // remove
            getBinaryStore().erase(binKey);

            return cUnits;
            }
        else
            {
            // the approver veto'd the eviction of this entry
            return 0;
            }
        }

    /**
     * Register the expiry for the specified key.
     *
     * @param binKey   the key
     * @param cExpiry  the number of milliseconds until the cache entry will
     *                 expire, also referred to as the entry's "time to live" or
     *                 0 to indicate that the cache entry should never expire
     */
    protected void registerExpiry(Binary binKey, final long cExpiry)
        {
        // Note: there could be a race here between this thread updating the
        //       expiry time, and a different thread that is evicting this entry;
        //       the visit() method will give us an atomic update and will
        //       only fire on an existing Entry (avoiding the possibility)
        //       of an "insertion" on a "delegate" BLM
        BinaryLongMap blmExpiry = getExpiryMap();
        if (blmExpiry != null || cExpiry > 0L)
            {
            blmExpiry = blmExpiry == null ? ensureExpiryMap() : blmExpiry;
            blmExpiry.visit(binKey, cExpiry == getExpiryDelay()
                    ? f_visitorDefaultExpiry
                    : (entry) -> entry.setValue(encodeExpiry(cExpiry)));
            }
        }

    /**
     * Return the unit-size of the entry associated with the specified key,
     * or 0 if the key does not exist.
     *
     * @param binKey  the key
     *
     * @return the unit-size of the entry associated with the specified key
     */
    protected int getEntryUnits(Binary binKey)
        {
        BinaryLongMap blmUnits = getUnitsMap();
        return blmUnits == null
                ? getKeyMap().get(binKey) == 0 ? 0 : 1   // FIXED calculator
                : (int) blmUnits.get(binKey);
        }

    /**
     * Calculate the unit cost of the specified cache entry, according to the
     * configured unit-calculator.
     *
     * @param binKey    the key
     * @param binValue  the value
     *
     * @return the unit cost of the cache entry
     */
    protected int calculateUnits(Binary binKey, Binary binValue)
        {
        return getUnitCalculator().calculateUnits(binKey, binValue);
        }

    /**
     * Update the units associated with the specified key.
     *
     * @param binKey  the key
     * @param cUnits  the units
     */
    protected void updateUnits(Binary binKey, final int cUnits)
        {
        BinaryLongMap blmUnits = getUnitsMap();
        if (blmUnits == null)
            {
            // either eviction is not configured, or we are using the FIXED
            // calculator; either way, we are not storing it
            }
        else
            {
            // Note: there could be a race here between this thread doing the
            //       "touch", and a different thread that is evicting this entry;
            //       the visit() method will give us an atomic update and will
            //       only fire on an existing Entry (avoiding the possibility)
            //       of an "insertion" on a "delegate" BLM
            blmUnits.visit(binKey, entry -> entry.setValue(cUnits));
            }
        }

    /**
     * Translate the passed Object object into an Binary object.
     *
     * @param o  the Object to serialize into a Binary object
     *
     * @return the Binary object
     */
    protected Binary toBinary(Object o)
        {
        return isBinaryMap() ? (Binary) o
                             : ExternalizableHelper.toBinary(o);
        }

    /**
     * Translate the passed Binary object into an Object object.
     *
     * @param bin  the Binary object to deserialize
     *
     * @return the deserialized object
     */
    protected Object fromBinary(Binary bin)
        {
        return bin == null || isBinaryMap()
                    ? bin
                    : ExternalizableHelper.fromBinary(bin, getClassLoader());
        }

    /**
     * Return a {@link ConfigurableCacheMap.Entry} for the specified key.
     *
     * @param binKey  the binary or "raw" form of the key
     *
     * @return an Entry representing the specified key
     */
    protected ConfigurableCacheMap.Entry getCacheEntryInternal(Binary binKey)
        {
        return getCacheEntryInternal(binKey, fromBinary(binKey));
        }

    /**
     * Return a {@link ConfigurableCacheMap.Entry} for the specified key.
     *
     * @param binKey  the binary or "raw" form of the key
     * @param oKey    the Object or "natural" form of the key
     *
     * @return an Entry representing the specified key
     */
    protected ConfigurableCacheMap.Entry getCacheEntryInternal(final Binary binKey, final Object oKey)
        {
        return new ConfigurableCacheMap.Entry()
            {
            public void touch()
                {
                CompactSerializationCache.this.touch(binKey);
                }

            public int getTouchCount()
                {
                BinaryLongMap blmTouch = CompactSerializationCache.this.getTouchCountMap();
                return blmTouch == null ? 0 : (int) blmTouch.get(binKey);
                }

            public long getLastTouchMillis()
                {
                BinaryLongMap blmTouch = CompactSerializationCache.this.getTouchTimeMap();
                return blmTouch == null ? 0L : decodeTime((int) blmTouch.get(binKey));
                }

            public long getExpiryMillis()
                {
                BinaryLongMap blmExpiry = CompactSerializationCache.this.getExpiryMap();
                return blmExpiry == null ? 0L : decodeExpiry(blmExpiry.get(binKey));
                }

            public void setExpiryMillis(long cMillis)
                {
                CompactSerializationCache.this.registerExpiry(binKey, cMillis);
                }

            public int getUnits()
                {
                return CompactSerializationCache.this.getEntryUnits(binKey);
                }

            public void setUnits(int cUnits)
                {
                BinaryLongMap blmUnits = CompactSerializationCache.this.getUnitsMap();
                if (blmUnits == null)
                    {
                    // we are configured with the FIXED calculator (or have no
                    // eviction configured) so we are not storing the units
                    }
                else
                    {
                    blmUnits.put(binKey, cUnits);
                    }
                }

            public Object getKey()
                {
                return oKey;
                }

            public Object getValue()
                {
                return CompactSerializationCache.this.get(oKey);
                }

            public Object setValue(Object oValue)
                {
                return CompactSerializationCache.this.put(oKey, oValue);
                }
            };
        }

    /**
    * Convert from an internal 64-bit unit value to an external 32-bit unit
    * value using the configured units factor.
    *
    * @param cUnits   an internal 64-bit units value
    * @param nFactor  the unit factor
    *
    * @return an external 32-bit units value
    */
    protected static int toExternalUnits(long cUnits, int nFactor)
        {
        if (cUnits == 0L || cUnits == Long.MAX_VALUE)
            {
            return 0;
            }

        if (nFactor > 1)
            {
            cUnits = (cUnits + nFactor - 1) / nFactor;
            }

        return cUnits > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cUnits;
        }

    /**
    * Convert from an external 32-bit unit value to an internal 64-bit unit
    * value using the configured units factor.
    *
    * @param cUnits   an external 32-bit units value
    * @param nFactor  the unit factor
    *
    * @return an internal 64-bit units value
    */
    protected static long toInternalUnits(int cUnits, int nFactor)
        {
        return cUnits <= 0 || cUnits == Integer.MAX_VALUE
               ? Long.MAX_VALUE
               : ((long) cUnits) * nFactor;
        }

    /**
     * Encode the specified expiry delay (relative to current time) into the
     * internal format.  The value can be {@link #decodeExpiry decoded} to yield
     * an absolute date-time that is <i>approximately</i> equivalent to the
     * the specified delay from the current time.
     *
     * @param cExpiryDelay  the relative delay from the current time; must be &gt;= 0
     *
     * @return the encoded expiry value
     */
    protected long encodeExpiry(long cExpiryDelay)
        {
        assert cExpiryDelay >= 0;

        return cExpiryDelay == 0 ? 0 : encodeTime(Base.getSafeTimeMillis() + cExpiryDelay);
        }

    /**
     * Decode the specified encoded expiry value into an absolute time.
     *
     * @param cExpiryEncoded  the encoded expiry value
     *
     * @return the absolute expiry time
     */
    protected long decodeExpiry(long cExpiryEncoded)
        {
        return decodeTime((int) cExpiryEncoded);
        }

    /**
     * Encode the specified time into an opaque 32-bit value.
     *
     * @param ldt  the time to encode
     *
     * @return the encoded time
     */
    protected int encodeTime(long ldt)
        {
        long cDelta = ldt - f_ldtEpoch;
        return Float.floatToIntBits((float) cDelta);
        }

    /**
     * Decode the specified encoded time into an absolute ldt.
     *
     * @param cEncoded  the 32-bit encoded time
     *
     * @return the absolute date-time
     */
    protected long decodeTime(int cEncoded)
        {
        long cDelta = (long) Float.intBitsToFloat(cEncoded);
        return cDelta + f_ldtEpoch;
        }

    // ----- inner class: KeySet --------------------------------------------

    /**
     * A KeySet implementation optimized for CompactSerializationCache. In addition
     * to the direct implementations provided by this KeySet class, CSC also
     * provides a {@link CompactSerializationCache#removeBlind(Object) removeBlind}
     * implementation invoked by {@link com.tangosol.util.AbstractKeyBasedMap.KeySet}.
     */
    protected class KeySet
            extends AbstractKeyBasedMap.KeySet
        {
        // ----- AbstractSet methods ----------------------------------------

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean removeAll(Collection coll)
            {
            assert coll != null;
            checkExpiry(false);

            boolean fRemoved = false;
            for (Object oKey : coll)
                {
                Binary binKey = toBinary(oKey);
                if (getBinaryStore().containsKey(binKey))
                    {
                    removeInternal(oKey, binKey, true);
                    fRemoved = true;
                    }
                }
            return fRemoved;
            }
        }


    // ----- inner class: MinExpiryVisitor -------------------------------

    /**
     * EntryVisitor implementation that calculates the next eviction time.
     */
    protected class MinExpiryVisitor
            implements MultiBinaryLongMap.SafeEntryVisitor
        {
        /**
         * Default constructor.
         */
        public MinExpiryVisitor()
            {
            m_ldtExpiryMin = Long.MAX_VALUE;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public void visit(BinaryLongMap.Entry entry)
            {
            long lEncoded = entry.getValue();
            if (lEncoded != 0L)
                {
                m_ldtExpiryMin = Math.min(m_ldtExpiryMin, decodeExpiry(lEncoded));
                }
            }

        // ----- data members -------------------------------------------

        /**
         * The minimum of the expiry values.
         */
        public long m_ldtExpiryMin;
        }

    // ----- inner class: InternalEvictionPolicy ----------------------------

    /**
     * InternalEvictionPolicy is an abstract base implementation of an {@link
     * ConfigurableCacheMap.EvictionPolicy} used by this CompactSerializationCache.
     */
    protected abstract class InternalEvictionPolicy
            implements EvictionPolicy
        {
        // ----- EvictionPolicy methods -------------------------------------

        /**
         * {@inheritDoc}
         */
        public void requestEviction(int cMaximum)
            {
            // TODO - do we need to make this incremental, or throttle the
            // TODO   low-units based on statistics?
            final EvictionVisitor visitor  = new EvictionVisitor(toInternalUnits(cMaximum, getUnitFactor()));
            BinaryLongMap         blmUnits = getUnitsMap();

            if (blmUnits == null)
                {
                // there is no units-map; we must be configured with the FIXED
                // calculator; dummy up a units-map that reports each entry with
                // a fixed size of 1 to the EvictionVisitor

                assert getUnitCalculator() == LocalCache.INSTANCE_FIXED;
                getKeyMap().visitAll(entry -> visitor.visit(new BinaryLongMap.Entry()
                    {
                    public Binary getKey()
                        {
                        return entry.getKey();
                        }

                    public long getValue()
                        {
                        return 1;  // each entry has 1 unit (FIXED)
                        }

                    public BinaryLongMap.Entry setValue(long lValue)
                        {
                        throw new UnsupportedOperationException();
                        }
                    }));
                }
            else
                {
                blmUnits.visitAll(visitor);
                }

            long cUnitsEvicted = 0L;
            for (EvictionCandidate candidate : visitor.f_setEvicting)
                {
                cUnitsEvicted += evictInternal(candidate.m_binKey);
                }

            f_atomicCurUnits.addAndGet(-cUnitsEvicted);
            m_ldtLastEvict = Base.getSafeTimeMillis();
            }

        /**
         * {@inheritDoc}
         */
        public void entryTouched(ConfigurableCacheMap.Entry entry)
            {
            entryTouched(toBinary(entry.getKey()));
            }

        /**
         * {@inheritDoc}
         */
        public String getName()
            {
            return getClass().getName();
            }

        // ----- InternalEvictionPolicy methods -----------------------------

        /**
         * Return the {@link ConfigurableCacheMap#setEvictionPolicy configured}
         * EvictionPolicy instance.
         *
         * @return the configured EvictionPolicy
         */
        protected abstract EvictionPolicy getConfiguredPolicy();

        /**
         * Calculate the "weight" of the entry associated with the specified
         * key.  The weight is a quantitative measure of how to prioritize the
         * eviction of this entry w.r.t. the other cache entries.  Entries with
         * a smaller weights are of higher priority and are preferred over
         * entries with larger weights for eviction.
         *
         * @param binKey  the key to calculate the weight for
         *
         * @return the weight of the entry
         */
        protected abstract long calculateWeight(Binary binKey);

        /**
         * This method is called by the cache to indicate that the entry for
         * the specified key has been touched.
         *
         * @param binKey  the key
         */
        public void entryTouched(Binary binKey)
            {
            touchInternal(binKey);
            }

        /**
         * Update the internally maintained "touch"-related statistics for the
         * specified key (e.g. touch time and touch count).
         *
         * @param binKey  the key being touched
         */
        protected void touchInternal(Binary binKey)
            {
            // Note: there could be a race here between this thread doing the
            //       "touch", and a different thread that is evicting this entry;
            //       the visit() method will give us an atomic update and will
            //       only fire on an existing Entry (avoiding the possibility)
            //       of an "insertion" on a "delegate" BLM

            // update the touch count
            BinaryLongMap blmTouchCount = getTouchCountMap();
            if (blmTouchCount != null)
                {
                blmTouchCount.visit(binKey, f_visitorTouchCount);
                }

            // update the touch-time
            BinaryLongMap blmTouchTime = getTouchTimeMap();
            if (blmTouchTime != null)
                {
                blmTouchTime.visit(binKey, f_visitorTouchTime);
                }
            }

        // ----- inner class: EvictionVisitor -------------------------------

        /**
         * SafeEntryVisitor implementation that calculates the set of eviction
         * candidates.
         * <p>
         * The EvictionVisitor is to be applied to Entries of a BinaryLongMap
         * that associates cache keys to their size in units (as determined by
         * the configured unit-calculator).
         */
        protected class EvictionVisitor
                implements MultiBinaryLongMap.SafeEntryVisitor
            {
            // ----- constructors -------------------------------------------

            /**
             * Construct an EvictionVisitor to identify the set of keys to evict
             * from the cache in order to prune the cache down to the specified
             * low-units threshold
             *
             * @param cLowUnits  the low-units threshold to prune the cache to
             */
            public EvictionVisitor(long cLowUnits)
                {
                f_setEvicting = new TreeSet<>();

                long cUnits = f_atomicCurUnits.get();
                f_cUnitsToEvict = cLowUnits >= cUnits ? 0 : cUnits - cLowUnits;
                }

            // ----- SafeEntryVisitor methods -----------------------------

            /**
             * {@inheritDoc}
             */
            public void visit(BinaryLongMap.Entry entry)
                {
                Binary binKey         = entry.getKey();
                long   lWeightThis    = calculateWeight(binKey);
                long   cUnitsThis     = entry.getValue();
                long   cUnitsEvicting = m_cUnitsEvicting;
                long   lKeepThreshold = m_lKeepThreshold;
                if (cUnitsEvicting < f_cUnitsToEvict || lWeightThis < lKeepThreshold)
                    {
                    // either we have not filled up the requested eviction size,
                    // or this entry is light enough that it should be considered
                    // for eviction (it is the lighter than the lightest entry
                    // to keep).

                    f_setEvicting.add(new EvictionCandidate(binKey, lWeightThis, cUnitsThis));

                    cUnitsEvicting += cUnitsThis;

                    while (cUnitsEvicting > f_cUnitsToEvict)
                        {
                        // make sure we don't evict too much; check the heaviest
                        // eviction candidate to see if we can keep it while still
                        // evicting a sufficient # of units.
                        EvictionCandidate candidateHeaviest = f_setEvicting.last();
                        long              cUnitsHeaviest    = candidateHeaviest.m_cUnits;
                        if (cUnitsEvicting - cUnitsHeaviest >= f_cUnitsToEvict)
                            {
                            f_setEvicting.remove(candidateHeaviest);
                            cUnitsEvicting -= cUnitsHeaviest;

                            lKeepThreshold = candidateHeaviest.m_lWeight;
                            }
                        else
                            {
                            break;
                            }
                        }

                    m_cUnitsEvicting = cUnitsEvicting;
                    m_lKeepThreshold = lKeepThreshold;
                    }
                }

            // ----- data members -------------------------------------------

            /**
             * The Map containing the keys which are candidates for eviction,
             * mapped to their size in units.
             */
            protected final SortedSet<EvictionCandidate> f_setEvicting;

            /**
             * The minimum number of units to evict.
             */
            protected final long f_cUnitsToEvict;

            /**
             * The number of units occupied by the entries in the eviction set.
             * Used during the iteration (evict-set calculation) only.
             */
            protected long m_cUnitsEvicting;

            /**
             * The weight of the lightest entry that we have decided to keep.
             * All of the entries to evict will be lighter than this threshold.
             */
            protected long m_lKeepThreshold = Long.MAX_VALUE;
            }

        // ----- inner class: EvictionCandidate -----------------------------------

        /**
         * EvictionCandidate represents a candidate for eviction during the
         * calculation of the eviction set, representing the key, its relative
         * "weight" as assigned by the configured {@link ConfigurableCacheMap.EvictionPolicy}
         * as well as its size in units.
         */
        protected class EvictionCandidate
                implements Comparable
            {
            // ----- constructors -------------------------------------------

            /**
             * Construct a EvictionCandidate for the specified key and weight.
             *
             * @param binKey   the key
             * @param lWeight  the weight
             * @param cUnits   the unit
             */
            public EvictionCandidate(Binary binKey, long lWeight, long cUnits)
                {
                m_binKey  = binKey;
                m_lWeight = lWeight;
                m_cUnits  = cUnits;
                }

            // ----- Comparable methods -------------------------------------

            /**
             * {@inheritDoc}
             */
            public int compareTo(Object o)
                {
                EvictionCandidate candThat    = (EvictionCandidate) o;
                long              lWeightThis = m_lWeight;
                long              lWeightThat = candThat.m_lWeight;

                // compare weight, then units, then arbitrary ordering
                if (lWeightThis == lWeightThat)
                    {
                    long cUnitsThis = m_cUnits;
                    long cUnitsThat = candThat.m_cUnits;

                    return cUnitsThis == cUnitsThat
                            ? m_binKey.compareTo(candThat.m_binKey)
                            : cUnitsThis < cUnitsThat
                                ? 1    // lower ordered items are higher priority
                                : -1;
                    }
                else
                    {
                    return lWeightThis < lWeightThat ? -1 : 1;
                    }
                }

            // ----- data members -------------------------------------------

            /**
             * The Binary key represented by this EvictionCandidate.
             */
            protected Binary m_binKey;

            /**
             * The calculated eviction weight (priority) of the key.
             */
            protected long   m_lWeight;

            /**
             * The size in units of the associated entry.
             */
            protected long   m_cUnits;
            }

        // ----- data members -----------------------------------------------

        /**
         * The date-time of the last eviction.
         */
        protected long m_ldtLastEvict;

        /**
         * The singleton stateless visitor to update the touch-count map.
         */
        protected final BinaryLongMap.EntryVisitor f_visitorTouchCount =
                (entry) -> entry.setValue(entry.getValue() + 1L);

        /**
         * The singleton stateless visitor to update the touch-time map.
         */
        protected final BinaryLongMap.EntryVisitor f_visitorTouchTime =
                // we know that prior to the "touch" operation, this
                // thread has already fetched the safe-time.  See #get/#put
                (entry) -> entry.setValue(encodeTime(Base.getLastSafeTimeMillis()));
        }


    // ----- inner class: LRUEvictionPolicy ---------------------------------

    /**
     * An EvictionPolicy implementing the Least Recently Used (LRU) algorithm.
     */
    protected class LRUEvictionPolicy
            extends InternalEvictionPolicy
        {
        /**
         * {@inheritDoc}
         */
        protected long calculateWeight(Binary binKey)
            {
            return decodeTime((int) getTouchTimeMap().get(binKey));
            }

        /**
         * {@inheritDoc}
         */
        protected EvictionPolicy getConfiguredPolicy()
            {
            // see #setEvictionPolicy
            return LocalCache.INSTANCE_LRU;
            }
        }

    // ----- inner class: TouchCountVisitor -------------------------------

    /**
     * TouchCountVisitor is applied to the touch-count map by EvictionPolicy
     * implementations that rely on the touch-count to retrieve and adjust
     * the touch-count.
     */
    protected static class TouchCountVisitor
            implements MultiBinaryLongMap.SafeEntryVisitor
        {
        /**
         * {@inheritDoc}
         */
        public void visit(BinaryLongMap.Entry entry)
            {
            // Reset the number of times that the cache entry has been touched.
            // The touch count does not get reset to zero, but rather to a
            // fraction of its former self; this prevents long lived items from
            // gaining an unassailable advantage in the eviction process.

            int cTouches = m_cTouchesLast = (int) entry.getValue();
            if (cTouches > 0)
                {
                entry.setValue(Math.max(1, cTouches >>> 4));
                }
            }

        // ----- data members -----------------------------------------

        /**
         * The touch-count of the last visited entry.
         */
        protected int m_cTouchesLast = 0;
        }

    // ----- inner class: LFUEvictionPolicy ---------------------------------

    /**
     * An EvictionPolicy implementing the Least Frequently Used (LFU) algorithm.
     */
    protected class LFUEvictionPolicy
            extends InternalEvictionPolicy
        {
        /**
         * {@inheritDoc}
         */
        protected long calculateWeight(Binary binKey)
            {
            getTouchCountMap().visit(binKey, f_visitor);

            return f_visitor.m_cTouchesLast;
            }

        /**
         * {@inheritDoc}
         */
        protected EvictionPolicy getConfiguredPolicy()
            {
            // see #setEvictionPolicy
            return LocalCache.INSTANCE_LFU;
            }

        // ----- data members -----------------------------------------------

        /**
         * The singleton TouchCountVisitor (eviction is single-threaded).
         */
        protected final TouchCountVisitor f_visitor = new TouchCountVisitor();
        }

    // ----- inner class: HybridEvictionPolicy ------------------------------

    /**
    * The EvictionPolicy object for the Hybrid eviction algorithm.
    */
    protected class HybridEvictionPolicy
            extends InternalEvictionPolicy
        {
        /**
         * {@inheritDoc}
         */
        public void requestEviction(int cMaximum)
            {
            final int[] aiInfo = new int[] { /*cEntries*/0, /*cTotal*/0};

            // note: using keys() instead of visitAll() to avoid exclusive lock
            getTouchCountMap().keys(new MultiBinaryLongMap.SafePredicate()
                    {
                    public boolean evaluate(BinaryLongMap.Entry entry)
                        {
                        aiInfo[0]++;                    // cEntries
                        aiInfo[1] += entry.getValue();  // cTotal

                        return false;
                        }
                    });

            m_cTouchesAvg = aiInfo[1] / Math.max(1, aiInfo[0]);

            super.requestEviction(cMaximum);
            }

        /**
         * {@inheritDoc}
         */
        protected long calculateWeight(Binary binKey)
            {
            // Logic is shamelessly copied from OldCache.java

            // calculate an LRU score - how recently was the entry used?
            long ldtPrune = m_ldtLastEvict;
            long ldtTouch = decodeTime((int) getTouchTimeMap().get(binKey));
            int  nScoreLRU = 0;
            if (ldtTouch > ldtPrune)
                {
                // measure recentness against the window of time since the
                // last prune
                long   ldtNow = getSafeTimeMillis();
                long   cMillisDormant = ldtNow - ldtTouch;
                long   cMillisWindow  = ldtNow - ldtPrune;
                double dflPct = (cMillisWindow - cMillisDormant) / (1.0 + cMillisWindow);
                nScoreLRU = 1 + BitHelper.indexOfMSB((int) ((dflPct * dflPct * 64)));
                }

            // calculate "frequency" - how often has the entry been used?
            getTouchCountMap().visit(binKey, f_visitor);
            int cUses     = f_visitor.m_cTouchesLast;
            int nScoreLFU = 0;
            if (cUses > 0)
                {
                nScoreLFU = 1;
                int cAvg = m_cTouchesAvg;
                if (cUses > cAvg)
                    {
                    ++nScoreLFU;
                    }

                int cAdj = (cUses << 1) - cAvg;
                if (cAdj > 0)
                    {
                    nScoreLFU += 1 + Math.min(4,
                        BitHelper.indexOfMSB((int) ((cAdj << 3) / (1.0 + cAvg))));
                    }
                }

            return Math.max(0L, 10L - nScoreLRU - nScoreLFU);
            }

        /**
         * {@inheritDoc}
         */
        protected EvictionPolicy getConfiguredPolicy()
            {
            // see #setEvictionPolicy
            return LocalCache.INSTANCE_HYBRID;
            }

        // ----- data members -----------------------------------------------

        /**
         * The average number of touches that each entry in the cache has.
         * Used during the iteration (evict-set calculation) only.
         */
        protected transient int m_cTouchesAvg;

        /**
         * The singleton TouchCountVisitor (eviction is single-threaded).
         */
        protected final TouchCountVisitor f_visitor = new TouchCountVisitor();
        }


    // ----- inner class: WrapperEvictionPolicy -----------------------------

    /**
     * WrapperEvictionPolicy is used to wrap external or "custom" EvictionPolicy
     * implementations to adapt the internal Binary key-based API to the
     * {@link ConfigurableCacheMap.Entry Entry-based} API.
     */
    protected class WrapperEvictionPolicy
            extends InternalEvictionPolicy
        {
        // ----- constructors -----------------------------------------------

        /**
         * Construct a WrapperEvictionPolicy.
         *
         * @param policy  the underlying EvictionPolicy
         */
        public WrapperEvictionPolicy(EvictionPolicy policy)
            {
            f_policy = policy;
            }

        // ----- InternalEvictionPolicy methods -----------------------------

        /**
         * {@inheritDoc}
         */
        public void entryTouched(Binary binKey)
            {
            touchInternal(binKey);

            f_policy.entryTouched(getCacheEntryInternal(binKey));
            }

        /**
         * {@inheritDoc}
         */
        public void entryTouched(ConfigurableCacheMap.Entry entry)
            {
            touchInternal(toBinary(entry.getKey()));

            f_policy.entryTouched(entry);
            }

        /**
         * {@inheritDoc}
         */
        protected long calculateWeight(Binary binKey)
            {
            // not used
            throw new UnsupportedOperationException();
            }

        /**
         * {@inheritDoc}
         */
        public void requestEviction(int cMaximum)
            {
            f_policy.requestEviction(cMaximum);
            }

        /**
         * {@inheritDoc}
         */
        protected EvictionPolicy getConfiguredPolicy()
            {
            return f_policy;
            }

        // ----- data members -----------------------------------------------

        /**
         * The underlying (custom) EvictionPolicy.
         */
        protected final EvictionPolicy f_policy;
        }


    // ----- constants and data members -------------------------------------

    /**
     * By default, when the cache prunes, it reduces its entries to this
     * percentage.
     */
    public static final double DEFAULT_PRUNE = 0.95D;

    /**
     * Ten second delay between automatically checking if anything has expired.
     */
    private static final long EXPIRY_AUTO_CHECK_INTERVAL = 10 * 1000;

    /**
     * Constant that serves as a "value exists" indicator.
     */
    private static final Object OBJECT_EXISTS = new Object();

    /**
     * The BinaryLongMap holding the expiry information for the entries in the
     * Cache Map.
     */
    protected BinaryLongMap m_blmExpiry;

    /**
     * The BinaryLongMap holding the touch-time information for the entries in
     * the Cache Map.
     */
    protected BinaryLongMap m_blmTouchTime;

    /**
     * The BinaryLongMap holding the touch-count information for the entries in
     * the Cache Map.
     */
    protected BinaryLongMap m_blmTouchCount;

    /**
     * The BinaryLongMap holding the units for each entry in the Cache Map.
     */
    protected BinaryLongMap m_blmUnits;

    /**
     * The BinaryStore.KeySetAware that this CompactSerializationCache uses to
     * store data.
     */
    protected final BinaryStore.KeySetAware f_store;

    /**
     * Maximum number of units to manage in the cache.
     */
    protected long m_cMaxUnits = Long.MAX_VALUE;

    /**
     * The percentage of the total number of units that will remain after the
     * cache manager prunes the cache (i.e. this is the "low water mark"
     * value); this value is in the range 0.0 to 1.0.
     */
    protected double m_dflPruneLevel;

    /**
     * The number of units to prune the cache down to.
     */
    protected long m_cPruneUnits;

    /**
     * Current number of units to in the cache.
     */
    protected final AtomicLong f_atomicCurUnits = new AtomicLong();

    /**
     * The unit calculator to use to limit size of the cache.
     */
    protected UnitCalculator m_calculator;

    /**
     * The unit factor.
     */
    protected int m_nUnitFactor = 1;

    /**
     * The default expiry int milliseconds, or 0 for no timeout.
     */
    protected int m_cExpiryDelay;

    /**
     * The time that the last cache-wide expiry check was performed.
     */
    protected long m_ldtNextExpiryCheck;

    /**
     * The EvictionPolicy.
     */
    protected InternalEvictionPolicy m_policy;

    /**
     * The EvictionApprover.
     */
    protected volatile EvictionApprover m_apprvrEvict;

    /**
     * The MapListenerSupport object.
     */
    protected MapListenerSupport m_listenerSupport;

    /**
     * True iff this Map should operate in blind mode. Blind mode allows a Map
     * implementation to forgo the often costly parts of the Map API, in particular
     * returning the previous value for both remove and put operations. Additionally,
     * for CCMs that dispatch events fetching the previous value can also be avoided.
     */
    protected boolean m_fBlind;

    /**
     * The MultiBinaryLongMap used for compact key (and entry-attribute) storage.
     */
    protected final MultiBinaryLongMap f_mblm;

    /**
     * The mutex acquired by the thread performing the cache-wide expiration.
     */
    protected final AtomicBoolean f_atomicExpiringMutex = new AtomicBoolean();

    /**
     * The mutex acquired by the thread performing the cache eviction.
     */
    protected final AtomicBoolean f_atomicEvictingMutex = new AtomicBoolean();

    /**
     * True iff the keys and values are known to be Binary (and not require
     * conversion).
     */
    protected final boolean f_fBinary;

    /**
     * The ClassLoader to use for deserialization of non-Binary keys/values.
     */
    protected final ClassLoader f_loader;

    /**
     * The time of the start of the "epoch", relative to which internal
     * time-offsets are stored.
     */
    protected final long f_ldtEpoch = Base.getSafeTimeMillis();

    /**
     * The singleton stateless EntryVisitor used to update the ExpiryMap with
     * the default expiry.  See {@link #registerExpiry}.
     */
    protected final BinaryLongMap.EntryVisitor f_visitorDefaultExpiry =
            (entry) -> entry.setValue(encodeExpiry(getExpiryDelay()));

    /**
     * The CacheStatistics for JMX.
     */
    protected final SimpleCacheStatistics f_stats = new SimpleCacheStatistics();

    /**
     * The lock to control event access.
     */
    private final ReentrantLock f_lockEvents = new ReentrantLock();
    }
