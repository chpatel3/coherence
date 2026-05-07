/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.util;

import com.oracle.coherence.common.base.Predicate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicReference;

import com.oracle.coherence.testing.util.BaseMapTest;
import com.tangosol.util.BinaryLongMap.Entry;
import com.tangosol.util.BinaryLongMap.EntryVisitor;

import org.hamcrest.Matchers;

import org.junit.Test;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;

import static org.junit.Assert.*;

import static com.oracle.coherence.testing.util.BinaryUtils.*;

/**
* Unit tests for BinaryRadixTree.
*
* @author cp 2010.06.08
* @since Coherence 3.7
*/
public class BinaryRadixTreeTest
    {
    public static void main(String[] asArg)
        {
        new BinaryRadixTreeTest().testPut();
        new BinaryRadixTreeTest().testPut2();
        new BinaryRadixTreeTest().testPut2b();
        new BinaryRadixTreeTest().testPut3();
        new BinaryRadixTreeTest().testPut4();
        new BinaryRadixTreeTest().testPutIfAbsent();
        new BinaryRadixTreeTest().testReplace();
        new BinaryRadixTreeTest().testRemove();
        new BinaryRadixTreeTest().testConditionalRemove();
        new BinaryRadixTreeTest().testRegression1();

        new BinaryRadixTreeTest().testMap();

        // out of memory tests
//        new BinaryRadixTreeTest().testRadixTreeOOM(8);
//        new BinaryRadixTreeTest().testHashMapOOM(8);
        }

    @Test
    public void testPut()
        {
        BinaryRadixTree tree   = new BinaryRadixTree();
        Binary          binKey = str2bin("test");
        assertTrue(tree.size() == 0);
        tree.put(binKey, 1L);
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(binKey) == 1L);
        }

    @Test
    public void testPut2()
        {
        testPuts("test", "testing");
        }

    @Test
    public void testPut2b()
        {
        testPuts("testing", "test");
        }

    @Test
    public void testPut3()
        {
        testPuts("test", "testing", "testingmore", "test2", "test2again");
        }

    @Test
    public void testPut4()
        {
        // forces replacing a parent with a parent+value, then splits
        testPuts("tester", "testing", "tests", "testingmore", "test", "tea");
        }

    protected void testPuts(String... asKey)
        {
        BinaryRadixTree tree    = new BinaryRadixTree();
        assertTrue(tree.size() == 0);

        int      cKeys   = asKey.length;
        Binary[] abinKey = new Binary[cKeys];
        for (int i = 0; i < cKeys; ++i)
            {
            abinKey[i] = str2bin(asKey[i]);
            }

        for (int i = 1; i <= cKeys; ++i)
            {
            tree.put(abinKey[i-1], i);
            assertTrue(tree.size() == i);
            assertTrue(tree.get(abinKey[i-1]) == i);
            }

        for (int i = 1; i <= cKeys; ++i)
            {
            assertTrue(tree.get(abinKey[i-1]) == i);
            }

        /*
        StringBuilder sb = new StringBuilder("Started with: ");
        for (int i = 0; i < cKeys; ++i)
            {
            if (i > 0)
                {
                sb.append(", ");
                }
            sb.append(asKey[i]);
            }
        out(sb.toString());

        sb = new StringBuilder("Ended with: ");
        boolean fNotFirst = false;
        for (Iterator<Binary> iter = tree.keys(); iter.hasNext(); )
            {
            if (fNotFirst)
                {
                sb.append(", ");
                }
            sb.append(bin2str(iter.next()));
            fNotFirst = true;
            }
        out(sb.toString());
        */
        }

    @Test
    public void testPutIfAbsent()
        {
        BinaryRadixTree tree    = new BinaryRadixTree();
        assertTrue(tree.size() == 0);
        assertTrue(tree.get(str2bin("test")) == 0L);
        assertTrue(tree.putIfAbsent(str2bin("test"), 3));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 3L);
        assertFalse(tree.putIfAbsent(str2bin("test"), 3));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 3L);
        assertFalse(tree.putIfAbsent(str2bin("test"), 4));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 3L);
        tree.remove(str2bin("test"));
        assertTrue(tree.size() == 0);
        assertTrue(tree.get(str2bin("test")) == 0L);
        assertTrue(tree.putIfAbsent(str2bin("test"), 4));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 4L);
        }

    @Test
    public void testReplace()
        {
        BinaryRadixTree tree = new BinaryRadixTree();
        assertTrue(tree.size() == 0);
        assertTrue(tree.get(str2bin("test")) == 0L);
        assertFalse(tree.replace(str2bin("test"), 1, 3));
        assertTrue(tree.size() == 0);
        assertTrue(tree.get(str2bin("test")) == 0L);
        assertTrue(tree.replace(str2bin("test"), 0, 3));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 3L);
        assertFalse(tree.replace(str2bin("test"), 2, 4));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 3L);
        assertTrue(tree.replace(str2bin("test"), 3, 4));
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(str2bin("test")) == 4L);
        assertTrue(tree.replace(str2bin("test"), 4, 0));
        assertTrue(tree.size() == 0);
        assertTrue(tree.get(str2bin("test")) == 0L);
        }

    @Test
    public void testRemove()
        {
        BinaryRadixTree tree = new BinaryRadixTree();
        assertTrue(tree.size() == 0);

        Binary[] abinKey = new Binary[]
            {
            str2bin("test"),
            str2bin("testing"),
            str2bin("tester"),
            str2bin("testreallylongname"),
            str2bin("testeroony"),
            };
        int cKeys = abinKey.length;

        for (int i = 0; i < cKeys; ++i)
            {
            tree.put(abinKey[i], 1);
            }
        assertTrue(tree.size() == cKeys);

        for (int i = 0; i < cKeys; ++i)
            {
            tree.remove(abinKey[i]);
            assertTrue(tree.size() == cKeys - i - 1);
            }

        for (int i = 0; i < cKeys; ++i)
            {
            tree.put(abinKey[i], 1);
            }
        assertTrue(tree.size() == cKeys);

        for (int i = 1; i <= cKeys; ++i)
            {
            tree.remove(abinKey[cKeys - i]);
            assertTrue(tree.size() == cKeys - i);
            }
        }

    @Test
    public void testConditionalRemove()
        {
        BinaryRadixTree tree = new BinaryRadixTree();
        assertTrue(tree.size() == 0);

        tree.put(str2bin("test"), 1);
        assertTrue(tree.size() == 1);

        assertFalse(tree.remove(str2bin("tes"), 1));
        assertFalse(tree.remove(str2bin("test"), 0));
        assertFalse(tree.remove(str2bin("test"), 2));
        assertFalse(tree.remove(str2bin("testy"), 1));
        assertTrue(tree.size() == 1);

        assertTrue(tree.remove(str2bin("test"), 1));
        assertTrue(tree.size() == 0);
        }

    // TODO size, keys, findAll

    @Test
    public void testRegression1()
        {
        BinaryRadixTree tree = new BinaryRadixTree();
        assertTrue(tree.size() == 0);

        Binary binKey1 = new Binary(Base.parseHex("816C1BDB74C92211F6C51BB605D958C5DA0D2DE6"));
        tree.put(binKey1, 1);
        assertTrue(tree.size() == 1);
        assertTrue(tree.get(binKey1) == 1L);

        Binary binKey2 = new Binary(Base.parseHex("E09070BFE1144D4CE580BC906DA409ECE7CD48DE"));
        tree.put(binKey2, 2);
        assertTrue(tree.size() == 2);
        assertTrue(tree.get(binKey2) == 2L);
        }

    @Test
    public void testSplit()
        {
        BinaryRadixTree brt = new BinaryRadixTree();

        final AtomicReference<Iterator<Binary>> ref = new AtomicReference<Iterator<Binary>>();
        Iterable<Binary> iterable = new Iterable<Binary>()
            {
            public Iterator<Binary> iterator()
                {
                return ref.get() == null ? NullImplementation.getIterator()
                                         : ref.get();
                }
            };

        Binary binKey0 = new Binary(Base.parseHex("0AEF"));
        Binary binKey1 = new Binary(Base.parseHex("0AEFA1"));
        Binary binKey2 = new Binary(Base.parseHex("0AEFB1"));
        Binary binKey3 = new Binary(Base.parseHex("0AE1C1"));

        //brt.put(binKey0, 8L);
        brt.put(binKey1, 8L);
        brt.put(binKey2, 8L);

        ref.set(brt.keys());
        assertThat(iterable, everyItem(Matchers.<Binary>anyOf(is(binKey1), is(binKey2))));

        brt.remove(binKey1);
        brt.remove(binKey2);
        brt.put(binKey3, 8L);

        ref.set(brt.keys());
        assertThat(iterable, everyItem(Matchers.<Binary>anyOf(is(binKey3))));
        }

    @Test
    public void testPutWithDecoration()
        {
        BinaryRadixTree tree    = new BinaryRadixTree();
        String          sBase   = "foobar";
        final int       MAX     = 8;
        final List<Binary>    listBin = new ArrayList<>(MAX);

        for (int i = 1; i <= MAX; ++i)
            {
            Binary binKey = str2bin(sBase + "-" + i);
            listBin.add(i - 1, binKey = (Binary) ExternalizableHelper.decorateBinary(
                    binKey, binKey.calculateNaturalPartition(0)));

            tree.put(binKey, i);
            assertTrue(tree.size() == i);
            }

        tree.visitAll(new EntryVisitor()
            {
            public void visit(Entry entry)
                {
                assertTrue(listBin.contains(entry.getKey()));
                }
            });

        for (int i = 0; i < MAX; ++i)
            {
            final Binary binKey = listBin.get(i);
            tree.visit(binKey, new EntryVisitor()
                {
                @Override
                public void visit(Entry entry)
                    {
                    assertEquals(binKey, entry.getKey());
                    }
                });
            }

        for (int i = MAX; i > 0; --i)
            {
            Binary binKey = listBin.get(i - 1);
            assertEquals(i, tree.get(binKey));

            tree.remove(binKey);
            assertEquals(i - 1, tree.size());
            }

        }

    public void testBRTSize()
        {
        BinaryRadixTree tree  = new BinaryRadixTree();
        String          sBase = "11111111111111111111111111111111";
        final int       MAX   = 1024;

        for (int i = 1; i <= MAX; ++i)
            {
            Binary binKey = str2bin(sBase + "-" + i);
            binKey = (Binary) ExternalizableHelper.decorateBinary(binKey,
                    binKey.calculateNaturalPartition(0));

            tree.put(binKey, i);
            }
        assertTrue(tree.size() == MAX);

        System.out.println(tree.sizeof());
        }

    public void testHashMapOOM(int cbKey)
        {
        HashMap map = new HashMap(0x80000, 1.0f);
        long lStart = System.currentTimeMillis();
        for (int i = 0; i < 10000000; ++i)
            {
            Binary bin = Base.getRandomBinary(cbKey, cbKey, (byte) 0x15);
            map.put(bin, null); // new Long(i));
            if ((i % 10000) == 0)
                {
                out("finished putting " + i + " (elapsed=" + (System.currentTimeMillis() - lStart) + "ms)");
                }
            }
        }

    public void testRadixTreeOOM(int cbKey)
        {
        BinaryRadixTree tree = new BinaryRadixTree();
        long lStart = System.currentTimeMillis();
        for (int i = 0; i < 10000000; ++i)
            {
            Binary bin = Base.getRandomBinary(cbKey, cbKey, (byte) 0x15);
            tree.put(bin, i);
            if ((i % 10000) == 0)
                {
                out("finished putting " + i + " (elapsed=" + (System.currentTimeMillis() - lStart) + "ms)");
                }
            }
        }

    /**
    * Taken and adapted from MapTest.
    */
    @Test
    public void testMap()
        {
        Map mapControl = new HashMap();
        Map mapTest    = new BinaryLongMap.SimpleMapImpl(new BinaryRadixTree());

        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        // test single operations
        Object oControl = mapControl.put(str2bin("hello"), 12345L);
        Object oTest    = mapTest   .put(str2bin("hello"), 12345L);
        BaseMapTest.assertIdenticalResult(oControl, oTest);
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        oControl = mapControl.put(str2bin("hello"), 12345L);
        oTest    = mapTest   .put(str2bin("hello"), 12345L);
        BaseMapTest.assertIdenticalResult(oControl, oTest);
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        oControl = mapControl.put(str2bin("hello"), 54321L);
        oTest    = mapTest   .put(str2bin("hello"), 54321L);
        BaseMapTest.assertIdenticalResult(oControl, oTest);
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        oControl = mapControl.put(str2bin("hello2"), 54321L);
        oTest    = mapTest   .put(str2bin("hello2"), 54321L);
        BaseMapTest.assertIdenticalResult(oControl, oTest);
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        oControl = mapControl.put(str2bin("hello2"), 543210L);
        oTest    = mapTest   .put(str2bin("hello2"), 543210L);
        BaseMapTest.assertIdenticalResult(oControl, oTest);
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        for (int iRepeat = 0; iRepeat < 2; ++iRepeat)
            {
            for (int i = 1; i < 200; ++i)
                {
                Long   LValue = (long) i;
                Binary binKey = ExternalizableHelper.toBinary(LValue);
                oControl = mapControl.put(binKey, LValue);
                oTest    = mapTest   .put(binKey, LValue);
                BaseMapTest.assertIdenticalResult(oControl, oTest);
                BaseMapTest.assertIdenticalMaps(mapControl, mapTest);
                }
            }

        // make a copy of what we have already
        Map mapData = new HashMap();
        mapData.putAll(mapControl);

        for (int iRepeat = 0; iRepeat < 2; ++iRepeat)
            {
            for (int i = 1; i < 100; ++i)
                {
                Long   LValue = (long) i;
                Binary binKey = ExternalizableHelper.toBinary(LValue);
                oControl = mapControl.remove(binKey);
                oTest    = mapTest   .remove(binKey);
                BaseMapTest.assertIdenticalResult(oControl, oTest);
                BaseMapTest.assertIdenticalMaps(mapControl, mapTest);
                }
            }

        // test bulk operations
        mapControl.clear();
        mapTest   .clear();
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);

        for (int iRepeat = 0; iRepeat < 2; ++iRepeat)
            {
            mapControl.putAll(mapData);
            mapTest   .putAll(mapData);
            BaseMapTest.assertIdenticalMaps(mapControl, mapTest);
            }

        mapControl.clear();
        mapTest   .clear();
        BaseMapTest.assertIdenticalMaps(mapControl, mapTest);
        }

    @Test
    public void testFindAll()
        {
        class MaskValueFilter
                implements Predicate<BinaryLongMap.Entry>
            {
            MaskValueFilter(long lMask, long lValue)
                {
                m_lMask  = lMask;
                m_lValue = lValue;
                }

            public boolean evaluate(BinaryLongMap.Entry entry)
                {
                return ((entry.getValue() ^ m_lValue) & m_lMask) == 0L;
                }

            long m_lMask;
            long m_lValue;
            }

        BinaryRadixTree tree  = new BinaryRadixTree();
        long            lVal1 = 0xAAAAAAAAAAAAAAAAL;
        long            lVal2 = 0xAAAAAAAAL;
        long            lVal3 = 0x2L;
        int             cKeys = 20;

        Map mapControl = new HashMap();
        for (int i = 0; i < cKeys; i++)
            {
            Binary bin = getUniqueBinary(mapControl.keySet());

            mapControl.put(bin, lVal1);
            tree.put(bin, lVal1);
            }
        for (int i = 0; i < cKeys; i++)
            {
            Binary bin = getUniqueBinary(mapControl.keySet());

            mapControl.put(bin, lVal2);
            tree.put(bin, lVal2);
            }
        for (int i = 0; i < cKeys; i++)
            {
            Binary bin = getUniqueBinary(mapControl.keySet());

            mapControl.put(bin, lVal3);
            tree.put(bin, lVal3);
            }

        // check lVal1
        Set setMatch = new HashSet();
        for (Iterator iter = tree.keys(new MaskValueFilter(0xFFFFFFFFFFFFFFFFL, lVal1));
             iter.hasNext(); )
            {
            Binary binKey = (Binary) iter.next();

            long lValControl = (Long) mapControl.get(binKey);
            assertTrue(lValControl == lVal1);
            setMatch.add(binKey);
            }
        assertEquals(cKeys, setMatch.size());

        // check for lVal1 || lVal2
        setMatch.clear();
        for (Iterator iter = tree.keys(new MaskValueFilter(0xFFFFFFFFL, lVal2));
             iter.hasNext(); )
            {
            Binary binKey = (Binary) iter.next();

            long lValControl = (Long) mapControl.get(binKey);
            assertTrue(lValControl == lVal1 || lValControl == lVal2);
            setMatch.add(binKey);
            }

        assertEquals(2 * cKeys, setMatch.size());

        // check for lVal1 || lVal2
        setMatch.clear();
        for (Iterator iter = tree.keys(new MaskValueFilter(0x8L, 0x8L));
             iter.hasNext(); )
            {
            Binary binKey = (Binary) iter.next();

            long lValControl = (Long) mapControl.get(binKey);
            assertTrue(lValControl == lVal1 || lValControl == lVal2);
            setMatch.add(binKey);
            }

        assertEquals(2 * cKeys, setMatch.size());

        // check for lVal1 || lVal2 || lVal3
        setMatch.clear();
        for (Iterator iter = tree.keys(new MaskValueFilter(0x3L, 0x2L));
             iter.hasNext(); )
            {
            Binary binKey = (Binary) iter.next();

            long lValControl = (Long) mapControl.get(binKey);
            assertTrue(lValControl == lVal1 || lValControl == lVal2 || lValControl == lVal3);
            setMatch.add(binKey);
            }

        assertEquals(3 * cKeys, setMatch.size());
        }


    // ----- helpers ------------------------------------------------------

    protected Binary getUniqueBinary(Set setBin)
        {
        return getUniqueBinary(setBin, 64, 64);
        }

    protected Binary getUniqueBinary(Set setBin, int cbMin, int cbMax)
        {
        Binary bin;
        do
            {
            bin = getRandomBinary(64, 64, (byte) 0x15);
            }
        while (setBin.contains(bin));

        return bin;
        }
    }
