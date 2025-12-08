package extend.proxy;

import com.tangosol.net.AbstractInvocable;
import com.tangosol.net.CacheFactory;
import com.tangosol.net.InvocationService;
import com.tangosol.net.Member;
import com.tangosol.net.NamedCache;
import com.tangosol.net.management.MBeanServerProxy;
import com.oracle.bedrock.junit.CoherenceClusterResource;
import com.oracle.bedrock.runtime.LocalPlatform;
import com.oracle.bedrock.runtime.coherence.CoherenceCluster;
import com.oracle.bedrock.runtime.coherence.CoherenceClusterMember;
import com.oracle.bedrock.runtime.coherence.JMXManagementMode;
import com.oracle.bedrock.runtime.coherence.options.CacheConfig;
import com.oracle.bedrock.runtime.coherence.options.ClusterName;
import com.oracle.bedrock.runtime.coherence.options.ClusterPort;
import com.oracle.bedrock.runtime.coherence.options.LocalHost;
import com.oracle.bedrock.runtime.coherence.options.LocalStorage;
import com.oracle.bedrock.runtime.coherence.options.Logging;
import com.oracle.bedrock.runtime.coherence.options.RoleName;
import com.oracle.bedrock.runtime.coherence.options.WellKnownAddress;
import com.oracle.bedrock.runtime.concurrent.RemoteCallable;
import com.oracle.bedrock.runtime.java.options.IPv4Preferred;
import com.oracle.bedrock.runtime.java.options.SystemProperty;
import com.oracle.bedrock.runtime.options.DisplayName;
import com.oracle.bedrock.testsupport.deferred.Eventually;
import com.oracle.bedrock.testsupport.junit.TestLogs;
import com.tangosol.io.ExternalizableLite;
import com.tangosol.io.pof.PofReader;
import com.tangosol.io.pof.PofWriter;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ExtendProxyTest
    {

    // ----- test lifecycle -------------------------------------------------

    @BeforeClass
    public static void setup()
        {
        CoherenceCluster cluster = clusterResource.getCluster();
        Eventually.assertDeferred(cluster::getClusterSize, is(1));
        Eventually.assertDeferred(cluster::isReady, is(true));
        Eventually.assertDeferred(cluster::isSafe, is(true));
        }

    @Before
    public void logStart()
        {
        String sMsg = ">>>>> Starting test: " + testName.getMethodName();
        for (CoherenceClusterMember member : clusterResource.getCluster())
            {
            member.submit(() -> System.err.println(sMsg)).join();
            }
        }

    @After
    public void logEnd()
        {
        String sMsg = ">>>>> Finished test: " + testName.getMethodName();
        for (CoherenceClusterMember member : clusterResource.getCluster())
            {
            member.submit(() -> System.err.println(sMsg)).join();
            }
        }

    // ----- test methods ---------------------------------------------------

    @Test
    public void testEnableProxy()
        {
        System.setProperty(WellKnownAddress.PROPERTY, LocalHost.loopback().getAddress());
        System.setProperty(CacheConfig.PROPERTY, FILE_CFG_CACHE_CLIENT);
        System.setProperty(ClusterName.PROPERTY, CLUSTER_NAME);
        System.setProperty(ClusterPort.PROPERTY, String.valueOf(CLUSTER_PORT));
        System.setProperty("coherence.proxy.port", String.valueOf(EXTEND_PORT));
        System.setProperty(JMXManagementMode.PROPERTY, "all");

        NamedCache        cache   = null;
        InvocationService service = null;
        Integer           iVal    = null;

        try
            {
            cache = CacheFactory.getCache("dist-extend");

            iVal = (Integer) cache.get("key");

            fail("Exception should have been thrown");
            }
        catch (Exception e)
            {
            assertNotNull(e); 
            }

        boolean enableAccess = clusterResource.getCluster().getAny().invoke(new InvokeCMMBeanOperation("enableAccess"));
        assertTrue(enableAccess);

        try
            {
            cache = CacheFactory.getCache("dist-extend");

            iVal = (Integer) cache.get("key");
            assertNull(iVal);

            iVal = iVal == null ? Integer.valueOf(1) : Integer.valueOf(iVal.intValue() + 1);

            cache.put("key", iVal);
            cache.addMapListener(new MyMapListener());
            service = (InvocationService) CacheFactory.getConfigurableCacheFactory().ensureService("ExtendTcpInvocationService");

            Map map = service.query(new GetClusterMember(), null);
            
            Integer memberId = (Integer) map.get(service.getCluster().getLocalMember());
            Eventually.assertThat(memberId, is(1));
            }
        catch(Exception e)
            {
            e.printStackTrace();
            fail("No Exception should have been thrown!");
            }
        }

    @Test
    public void testDisableProxy()
        {
        System.setProperty(WellKnownAddress.PROPERTY, LocalHost.loopback().getAddress());
        System.setProperty(CacheConfig.PROPERTY, FILE_CFG_CACHE_CLIENT);
        System.setProperty(ClusterName.PROPERTY, CLUSTER_NAME);
        System.setProperty(ClusterPort.PROPERTY, String.valueOf(CLUSTER_PORT));
        System.setProperty("coherence.proxy.port", String.valueOf(EXTEND_PORT));
        System.setProperty(JMXManagementMode.PROPERTY, "all");

        NamedCache        cache   = null;
        InvocationService service = null;
        Integer           iVal    = null;

        try
            {
            cache = CacheFactory.getCache("dist-extend");

            iVal = (Integer) cache.get("key");

            Eventually.assertThat(iVal, is(1));
            }
        catch (Exception e)
            {
            fail("No Exception should have been thrown");
            }

        boolean disableAccess = clusterResource.getCluster().getAny().invoke(new InvokeCMMBeanOperation("disableAccess"));
        assertTrue(disableAccess);

        try
            {
            cache = CacheFactory.getCache("dist-extend");

            Map map = service.query(new GetClusterMember(), null);
            System.out.println("*** \n\n\n AFTER DISABLING PROXY MEM_ID : " + map.get(service.getCluster().getLocalMember()) + "\n\n\n ****");

            Object val = cache.get("key");
            System.out.println("*** \n\n\n AFTER DISABLING PROXY CACHE_VAL : " + val + "\n\n\n ****");

            fail("Exception should have been thrown");
            }
        catch(Exception e)
            {
            e.printStackTrace();
            assertNotNull(e);
            }
        }

    // ----- inner classes --------------------------------------------------

    public static class GetClusterMember
            extends AbstractInvocable
            implements ExternalizableLite, com.tangosol.io.pof.PortableObject
        {

        public GetClusterMember() {}

        public void run()
            {
            Member member = CacheFactory.getCluster().getLocalMember();
            System.out.println("This has been run by ExtendTcpInvocationService on: " + member.getId());
            setResult(member.getId());
            }

        // ----- ExternalizableLite methods -------------------------------
        public void writeExternal(DataOutput out)
                throws IOException
            {}

        public void readExternal(DataInput in)
                throws IOException
            {}

        // ----- PortableObject methods -------------------------------------

        public void readExternal(PofReader in)
                throws IOException
            {}

        public void writeExternal(PofWriter out)
                throws IOException
            {}
        }

    /**
     * A {@link RemoteCallable} to invoke operation on ConnectionManager MBean
     */
    public static class InvokeCMMBeanOperation
            implements RemoteCallable<Boolean>
        {

        public InvokeCMMBeanOperation(String opName)
            {
            s_opName = opName;
            }

        @Override
        public Boolean call() throws Exception
            {
            MBeanServerProxy mbeanServerProxy = CacheFactory.getCluster().getManagement().getMBeanServerProxy();

            try
                {
                mbeanServerProxy.invoke(CM_ON, s_opName, null, null);
                return true;
                }
            catch (Exception e)
                {
                e.printStackTrace();
                return false;
                }
            }

        // ----- data member ------------------------------------------------------

        private String s_opName;
        }


    // ----- constants and data members -------------------------------------

    /**
     * Cache configuration file with xml-override attribute specified.
     */
    public final static String FILE_CFG_CACHE = "coherence-cache-config.xml";

    /**
     * Cache configuration file with xml-override attribute specified without default.
     */
    public final static String FILE_CFG_CACHE_CLIENT = "client-cache-config.xml";

    /**
     * The number of storage enabled members in the {@link CoherenceClusterResource}.
     */
    protected static final int STORAGE_ENABLED_MEMBER_COUNT = 1;

    /**
     * The next available port to use for cluster port.
     */
    private static final Integer CLUSTER_PORT = LocalPlatform.get().getAvailablePorts().next();

    /**
     * The next available port to use for extend proxy connection between storage-enabled member
     * and extend client
     */
    private static final Integer EXTEND_PORT = LocalPlatform.get().getAvailablePorts().next();

    /**
     * Cluster Name
     */
    private static final String CLUSTER_NAME = "extend-proxy";

    private static final String CM_ON = "Coherence:type=ConnectionManager,name=Proxy,nodeId=1";

    @Rule
    public TestName testName = new TestName();

    @ClassRule
    public static final TestLogs testLogs = new TestLogs(ExtendProxyTest.class);

    @ClassRule
    public static final CoherenceClusterResource clusterResource = new CoherenceClusterResource()
            .with(WellKnownAddress.of(LocalHost.loopback().getAddress()),
                  ClusterName.of(CLUSTER_NAME),
                  ClusterPort.of(CLUSTER_PORT),
                  SystemProperty.of("coherence.extend.port", EXTEND_PORT),
                  SystemProperty.of("proxy.enabled", "false"),
                  IPv4Preferred.yes(),
                  JMXManagementMode.ALL,
                  Logging.atMax(),
                  LocalHost.only())
            .include(STORAGE_ENABLED_MEMBER_COUNT, CoherenceClusterMember.class,
                     LocalStorage.enabled(),
                     testLogs.builder(),
                     RoleName.of("storage"),
                     DisplayName.of("storage"));
    }
