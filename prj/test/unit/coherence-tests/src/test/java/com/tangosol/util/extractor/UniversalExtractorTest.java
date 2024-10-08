/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.util.extractor;

import com.tangosol.io.WriteBuffer;

import com.tangosol.io.pof.PofBufferReader;
import com.tangosol.io.pof.PofBufferWriter;
import com.tangosol.io.pof.PofReader;
import com.tangosol.io.pof.PofSerializer;
import com.tangosol.io.pof.PofWriter;
import com.tangosol.io.pof.PortableObjectSerializer;
import com.tangosol.io.pof.SimplePofContext;

import com.tangosol.util.Base;
import com.tangosol.util.Binary;
import com.tangosol.util.BinaryWriteBuffer;
import com.tangosol.util.ExternalizableHelper;
import com.tangosol.util.ValueExtractor;
import com.tangosol.util.WrapperException;

import data.extractor.InvokeTestClass;

import java.io.IOException;

import java.lang.reflect.InvocationTargetException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.hamcrest.CoreMatchers.is;

/**
 * Unit test of the {@link UniversalExtractor}.
 *
 * @author jf 2017.11.28
 */
public class UniversalExtractorTest
        extends Base
    {

    @Test(expected = IllegalArgumentException.class)
    public void testOptionalParametersNoMethodSuffix()
        {
        UniversalExtractor missingMethodSuffix = new UniversalExtractor("foo", new Object[]{5});
        }

    @Test
    public void testCanonicalName()
        {
        UniversalExtractor methodNoName = new UniversalExtractor("()");
        assertEquals("()", methodNoName.getCanonicalName());
        assertEquals("", methodNoName.getMethodName());

        UniversalExtractor propertyExtractor = new UniversalExtractor("getFoo()");
        assertEquals("foo", propertyExtractor.getCanonicalName());
        assertEquals("getFoo", propertyExtractor.getMethodName());
        assertTrue(propertyExtractor.isPropertyExtractor());
        assertFalse(propertyExtractor.isMethodExtractor());

        UniversalExtractor methodExtractor = new UniversalExtractor("foo()");
        assertEquals("foo()", methodExtractor.getCanonicalName());
        assertTrue(methodExtractor.isMethodExtractor());
        assertFalse(methodExtractor.isPropertyExtractor());
        assertEquals("foo", methodExtractor.getMethodName());
        assertTrue(methodExtractor.isMethodExtractor());
        assertFalse(methodExtractor.isPropertyExtractor());
        }

    @Test
    public void testExtractorEquality()
        {
        UniversalExtractor  extractorUniversal1   = new UniversalExtractor("getFoo()");
        UniversalExtractor  extractorUniversal2   = new UniversalExtractor("foo");
        UniversalExtractor  extractorUniversal3   = new UniversalExtractor("getFoo");
        ReflectionExtractor extractorReflection1  = new ReflectionExtractor("getFoo");

        assertTrue("u1=" + extractorUniversal1 + " u2=" + extractorUniversal2, extractorUniversal1.equals(extractorUniversal2));
        assertTrue("u1=" + extractorUniversal1 + " u3=" + extractorUniversal3, extractorUniversal1.equals(extractorUniversal3));
        assertTrue("u2=" + extractorUniversal1 + " u3=" + extractorUniversal3, extractorUniversal1.equals(extractorUniversal3));
        assertTrue("u1=" + extractorUniversal1 + " r1=" + extractorReflection1, extractorUniversal1.equals(extractorReflection1));
        assertTrue("u1=" + extractorUniversal1 + " r1=" + extractorReflection1, extractorReflection1.equals(extractorUniversal1));
        assertTrue("u2=" + extractorUniversal2 + " r1=" + extractorReflection1, extractorUniversal2.equals(extractorReflection1));
        assertTrue("u2=" + extractorUniversal2 + " r1=" + extractorReflection1, extractorReflection1.equals(extractorUniversal2));
        assertTrue("u3=" + extractorUniversal3 + " r1=" + extractorReflection1, extractorReflection1.equals(extractorUniversal3));
        }

    @Test
    public void testMethodExtractorEqualityHashCode()
        {
        UniversalExtractor  ueMethod   = new UniversalExtractor("aMethod()");
        UniversalExtractor  ueProperty = new UniversalExtractor("aMethod");    // this is a property, validating not a reflective method extractor
        ReflectionExtractor re1        = new ReflectionExtractor("aMethod");

        assertTrue(ueMethod.isMethodExtractor());
        assertTrue(ueProperty.isPropertyExtractor());
        assertEquals(ueMethod, re1);
        assertNotEquals(ueMethod, ueProperty);
        assertNotEquals(ueProperty, re1);
        assertThat(ueMethod.hashCode(), is(re1.hashCode()));
        assertNotEquals(re1, ueProperty);
        assertNotEquals(ueProperty.hashCode(), re1.hashCode());
        }

    @Test
    public void testOptionalParameters()
        {
        UniversalExtractor methodExtractor = new UniversalExtractor("getFoo()", new Object[]{5});
        assertEquals(null, methodExtractor.getCanonicalName());
        assertEquals("getFoo", methodExtractor.getMethodName());
        assertFalse(methodExtractor.isPropertyExtractor());
        assertTrue(methodExtractor.isMethodExtractor());

        UniversalExtractor methodExtractor2 = new UniversalExtractor("foo()",  new Object[]{5});
        assertEquals(null, methodExtractor2.getCanonicalName());
        assertTrue(methodExtractor2.isMethodExtractor());
        assertFalse(methodExtractor2.isPropertyExtractor());
        assertEquals("foo", methodExtractor2.getMethodName());
        assertTrue(methodExtractor2.isMethodExtractor());
        assertFalse(methodExtractor2.isPropertyExtractor());
        }

    /**
     * Validate property vs method identification based on name parameter pas
     */
    @Test
    public void testEqualsHashCodeCanonicalName()
        {
        UniversalExtractor  extractor1 = new UniversalExtractor("foo");
        UniversalExtractor  extractor2 = new UniversalExtractor("foo()");
        UniversalExtractor  extractor3 = new UniversalExtractor("getFoo()");
        UniversalExtractor  extractor6 = new UniversalExtractor("getFoo");
        UniversalExtractor  extractor5 = new UniversalExtractor("isFoo()");
        ReflectionExtractor extractor7 = new ReflectionExtractor("getFoo");
        ReflectionExtractor extractor8 = new ReflectionExtractor("foo");

        assertFalse("comparing a method extractor with a property extractor, expected false", extractor1.equals(extractor2));
        assertFalse("comparing a method extractor with a property extractor, expected false", extractor2.equals(extractor1));
        assertNotEquals(extractor1.hashCode(), extractor2.hashCode());

        assertTrue("compare RE foo with UE foo(), expect true", extractor2.equals(extractor8));
        assertEquals(extractor2.getCanonicalName(), extractor8.getCanonicalName());

        assertTrue(extractor1.equals(extractor3));
        assertTrue(extractor3.equals(extractor1));
        assertTrue(extractor7.equals(extractor6));
        assertTrue(extractor7.equals(extractor1));
        assertEquals(extractor1.hashCode(), extractor3.hashCode());

        assertTrue(extractor1.equals(extractor5));
        assertTrue(extractor5.equals(extractor1));
        assertTrue(extractor3.equals(extractor6));
        assertTrue(extractor1.equals(extractor6));
        assertEquals(extractor1.hashCode(), extractor5.hashCode());
        assertEquals(extractor1.hashCode(), extractor7.hashCode());
        assertEquals(extractor6.hashCode(), extractor3.hashCode());
        assertEquals(extractor6.hashCode(), extractor7.hashCode());

        // single arg method invocation
        UniversalExtractor extractor4 = new UniversalExtractor("foo()", new Integer[]{ 42 });
        assertFalse(extractor1.equals(extractor4));
        assertFalse(extractor2.equals(extractor4));

        assertEquals("foo",   extractor1.getCanonicalName());
        assertEquals("foo()", extractor2.getCanonicalName());
        assertEquals("foo()", extractor8.getCanonicalName());
        assertEquals("foo",   extractor3.getCanonicalName());
        assertEquals("foo",   extractor6.getCanonicalName());
        assertEquals(null,    extractor4.getCanonicalName());
        }

    /**
     * Test of how the Reflection Extractor independent of Coherence.
     */
    @Test
    public void test()
        {
        Object              oTestClass     = new InvokeTestClass();
        UniversalExtractor extractorTest1 = new UniversalExtractor("retVal()");
        String              sRet           = (String)extractorTest1.extract(oTestClass);
        assertTrue("UniversalExtractor : Error invoking on No Parameters",
                sRet.equals("Return Value"));
        assertEquals("retVal()", extractorTest1.getCanonicalName());

        Integer             iTest2Value    = 100;
        Object[]            aoTest2Parm    = {iTest2Value};
        UniversalExtractor extractorTest2 = new UniversalExtractor("retVal()", aoTest2Parm);
        Integer             iRet           = (Integer) extractorTest2.extract(oTestClass);
        assertTrue("UniversalExtractor : Error invoking on int Parameter",
                iRet.equals(iTest2Value));
        assertNull(extractorTest2.getCanonicalName());
        assertFalse(extractorTest2.equals(extractorTest1));
        assertFalse(extractorTest1.equals(extractorTest2));

        UniversalExtractor extractorTest2A = new UniversalExtractor("retVal()", new Integer[]{100});
        assertTrue(extractorTest2.equals(extractorTest2A));
        assertTrue(extractorTest2A.equals(extractorTest2));
        assertEquals(extractorTest2.hashCode(), extractorTest2A.hashCode());

        Object[]            aoTest3Parm    = {Boolean.TRUE};
        UniversalExtractor extractorTest3 = new UniversalExtractor("retVal()", aoTest3Parm);
        Boolean             bRet           = (Boolean) extractorTest3.extract(oTestClass);
        assertTrue("UniversalExtractor : Error invoking on boolean Parameter",
                bRet.equals(Boolean.TRUE));

        Object[]            aoTest4Value   = new Object[10];
        Object[]            aoTest4Parm    = {aoTest4Value};
        UniversalExtractor ExtractorTest4 = new UniversalExtractor("sumIntTest()", aoTest4Parm);
        int                 nExp = 0;

        for (int j = 0, c = aoTest4Value.length; j < c; j++)
            {
            nExp += j + 1;
            aoTest4Value[j] = j + 1;
            }
        iRet = (Integer) ExtractorTest4.extract(oTestClass);
        assertTrue("UniversalExtractor : Error invoking Array Parameters",
                iRet.intValue() == nExp);

        UniversalExtractor test4ExtractorDup;
        Binary binTest4Extractor =
                ExternalizableHelper.toBinary(ExtractorTest4);
        test4ExtractorDup = (UniversalExtractor) ExternalizableHelper
                .fromBinary(binTest4Extractor);
        iRet = (Integer) test4ExtractorDup.extract(oTestClass);
        assertTrue("UniversalExtractor : Error invoking on ExternalizableLite Duplication",
                iRet.intValue() == nExp);

        WriteBuffer buf = new BinaryWriteBuffer(0);
        WriteBuffer.BufferOutput out = buf.getBufferOutput();

        SimplePofContext ctx            = new SimplePofContext();
        PofWriter writer         = new PofBufferWriter(out, ctx);
        PofSerializer pofSerializer1 = new PortableObjectSerializer(1);
        ctx.registerUserType(1, ExtractorTest4.getClass(), pofSerializer1);
        try
            {
            writer.writeObject(0, ExtractorTest4);

            PofReader reader = new PofBufferReader(
                    buf.getReadBuffer().getBufferInput(), ctx);
            UniversalExtractor test4ExtractorPOFDup = (UniversalExtractor) reader.readObject(0);
            iRet = (Integer) test4ExtractorPOFDup.extract(oTestClass);
            assertTrue("UniversalExtractor : Error invoking on PortableObject Duplication",
                    iRet.intValue() == nExp);
            }
        catch (IOException e)
            {
            fail(e.toString());
            }

        Object[]           aoTest5Parm    = {100, 200, 300};
        UniversalExtractor extractorTest5 = new UniversalExtractor("sumIntTest()", aoTest5Parm);
        iRet  = (Integer) extractorTest5.extract(oTestClass);
        assertTrue("UniversalExtractor : Error Invoking sum of 3 Integer Parameters.",
                iRet.intValue() == 600);
        }

    /**
     * Test support for UniversalExtractor with a Map.
     */
    @Test
    public void testExtractFromMap()
        {
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        map.put("key2", "value2");

        UniversalExtractor extractor1 = new UniversalExtractor("key");
        assertEquals("key", extractor1.getCanonicalName());
        assertNotNull(extractor1.toString());
        assertTrue(extractor1.extract(map).equals("value"));
        assertNotNull(extractor1.toString());

        // validate cached Map target source code path works also.
        assertTrue(extractor1.extract(map).equals("value"));

        UniversalExtractor extractor2 = new UniversalExtractor("getKey2()");
        assertEquals("key2", extractor2.getCanonicalName());

        assertTrue(extractor2.extract(map).equals("value2"));
        }


    /**
     * Test support for UniversalExtractor with a Map.
     */
    @Test
    public void testExtractFromMapWithGetterProperty()
        {
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        map.put("key2", "value2");

        UniversalExtractor extractor1 = new UniversalExtractor("getKey()");
        assertEquals("key", extractor1.getCanonicalName());
        assertNotNull(extractor1.toString());
        assertTrue(extractor1.extract(map).equals("value"));
        assertNotNull(extractor1.toString());

        // validate cached map target source code path works also.
        assertTrue(extractor1.extract(map).equals("value"));

        UniversalExtractor extractor2 = new UniversalExtractor("getKey2()");
        assertEquals("key2", extractor2.getCanonicalName());

        assertTrue(extractor2.extract(map).equals("value2"));
        }

    /**
     * Ensure that do not support Map extraction when there is a extractor method parameter(s) provided.
     */
    @Test(expected=RuntimeException.class)
    public void testInvalidExtractFromJsonObject()
        {
        Map map = new HashMap();
        map.put("key", "value");

        Object[] params = {"parameter"};
        UniversalExtractor extractor = new UniversalExtractor("key()", params);
        extractor.extract(map);
        }

    @SuppressWarnings("unchecked")
    @Test(expected = IllegalArgumentException.class)
    public void testDefaultReflectionBlacklist()
        throws Throwable
        {
        UniversalExtractor extractor = new UniversalExtractor("name", new Object[] {});
        try
            {
            extractor.extract(String.class);
            }
        catch (WrapperException e)
            {
            throw e.getOriginalException();
            }
        }

    public static class TestJavaBean
        {
        // ----- TestJavaBean methods ---------------------------------------

        public void setFoo(String s)
            {
            m_sFoo = s;
            }

        public String getFoo()
            {
            return m_sFoo;
            }

        public void setFlag(boolean f)
            {
            m_fFlag = f;
            }

        public boolean isFlag()
            {
            return m_fFlag;
            }

        public String boo()
            {
            return "boo";
            }

        // ----- data members -----------------------------------------------
        private String  m_sFoo;

        private boolean m_fFlag;
        }

    @Test
    public void testJavaRecord()
        {
        record TestRecord(String foo, int bar) {}

        TestRecord rec = new TestRecord("foo", 42);

        UniversalExtractor<Object, Object> ex = new UniversalExtractor<>("foo");
        assertEquals("foo", ex.extract(rec));
        assertEquals("foo", ex.getCanonicalName());

        ex = new UniversalExtractor<>("bar");
        assertEquals(42, ex.extract(rec));
        assertEquals("bar", ex.getCanonicalName());
        }

    @Test
    public void testJavaBean()
        {
        TestJavaBean bean = new TestJavaBean();
        bean.setFoo("value");
        bean.setFlag(true);

        // test scenarios where java bean attribute is extracted.
        UniversalExtractor extractor = new UniversalExtractor("foo");
        assertEquals(extractor.extract(bean), "value");

        // Ensure that these are identified as java bean property and work same as above.
        UniversalExtractor                    ue1 = new UniversalExtractor("getFoo()");
        UniversalExtractor                    ue2 = new UniversalExtractor("getFoo");
        ValueExtractor<TestJavaBean, String>  ve  = TestJavaBean::getFoo;

        assertEquals(ue1.extract(bean), "value");
        assertEquals(ue2.extract(bean), "value");
        assertEquals(ve.extract(bean), "value");
        assertEquals(ue1, ue2);
        assertEquals(extractor, ue1);
        assertEquals(extractor, ue2);
        assertEquals(ve.getCanonicalName(), ue1.getCanonicalName());

        // ensure cached method approach works also
        assertEquals(extractor.extract(bean), "value");

        UniversalExtractor extractor1 = new UniversalExtractor("flag");
        assertTrue((boolean) extractor1.extract(bean));

        // test scenarios where full javabean accessor method is provided.
        UniversalExtractor extractor2 = new UniversalExtractor("foo");
        assertEquals(extractor2.extract(bean), "value");

        // ensure cached method approach works also
        assertEquals(extractor2.extract(bean), "value");

        UniversalExtractor extractor3 = new UniversalExtractor("flag");
        assertTrue((boolean) extractor3.extract(bean));
        assertEquals("flag", extractor3.getCanonicalName());

        // non javabean property no arg method
        UniversalExtractor extractor4 = new UniversalExtractor("boo()");
        assertEquals("boo", extractor4.extract(bean));
        assertEquals("boo()", extractor4.getCanonicalName());

        // direct access to non JavaBean property (Java records convention)
        UniversalExtractor extractor5 = new UniversalExtractor("boo");
        assertEquals("boo", extractor5.extract(bean));
        assertEquals("boo", extractor5.getCanonicalName());
        }

    class ReflectionExtractorWithStatistics<T, E> extends UniversalExtractor<T,E>
        {

        public ReflectionExtractorWithStatistics(String name)
            {
            super(name);
            }

        protected E extractComplex(T oTarget)
                throws InvocationTargetException, IllegalAccessException
            {
            nExtractComplexCalls++;
            return super.extractComplex(oTarget);
            }

        public int nExtractComplexCalls;
        }

    @Test
    public void validateReflectionMethodExtractorCaching()
        {
        ArrayList<TestJavaBean> beans = new ArrayList<>(10);

        for (int i = 0; i < 10; i++)
            {
            TestJavaBean bean = new TestJavaBean();
            bean.setFoo(Integer.toString(i));
            bean.setFlag(false);
            beans.add(bean);
            }

        // test scenarios where java bean attribute is extracted.
        ReflectionExtractorWithStatistics extractor = new ReflectionExtractorWithStatistics("foo");
        for (int i = 0; i < beans.size(); i++)
            {
            assertEquals(extractor.extract(beans.get(i)), Integer.toString(i));
            }

        assertTrue("expected 1, observed " + extractor.nExtractComplexCalls, extractor.nExtractComplexCalls == 1);
        }

    @Test
    public void validateReflectionExtractorCaching()
        {
        ArrayList<TestJavaBean> beans = new ArrayList<>(10);

        for (int i = 0; i < 10; i++)
            {
            TestJavaBean bean = new TestJavaBean();
            bean.setFoo(Integer.toString(i));
            bean.setFlag(false);
            beans.add(bean);
            }

        // test scenarios where java bean attribute is extracted.
        ReflectionExtractorWithStatistics extractor = new ReflectionExtractorWithStatistics("foo");
        for (int i = 0; i < 10; i++)
            {
            assertEquals(extractor.extract(beans.get(i)), Integer.toString(i));
            }

        assertTrue("expected 1, observed " + extractor.nExtractComplexCalls, extractor.nExtractComplexCalls == 1);
        }

    @Test
    public void validateReflectionExtractorMapCaching()
        {
        ArrayList<Map> maps = new ArrayList<>(10);

        for (int i = 0; i < 10; i++)
            {
            Map map = new HashMap();
            map.put("key", "value");
            maps.add(map);
            }

        // test scenarios where Map access across different Map types.
        ReflectionExtractorWithStatistics extractor = new ReflectionExtractorWithStatistics("key");
        for (int i = 0; i < maps.size(); i++)
            {
            assertEquals(extractor.extract(maps.get(i)), "value");
            }

        assertTrue("expected 1, observed " + extractor.nExtractComplexCalls, extractor.nExtractComplexCalls == 1);
        }

    @Test
    public void validateReflectionExtractorMapCachingGetter()
        {
        ArrayList<Map> maps = new ArrayList<>(10);

        for (int i = 0; i < 10; i++)
            {
            Map map = new HashMap();
            map.put("key", "value");
            maps.add(map);
            }

        // test scenarios where Map access across different types.
        ReflectionExtractorWithStatistics extractor = new ReflectionExtractorWithStatistics("key");
        for (int i = 0; i < maps.size(); i++)
            {
            assertEquals(extractor.extract(maps.get(i)), "value");
            }

        assertTrue("expected 1, observed " + extractor.nExtractComplexCalls, extractor.nExtractComplexCalls == 1);
        }
    }
