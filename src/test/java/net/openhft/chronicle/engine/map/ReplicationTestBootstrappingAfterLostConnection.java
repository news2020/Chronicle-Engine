package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.engine.ChronicleMapKeyValueStoreTest;
import net.openhft.chronicle.engine.VanillaAssetTreeEgMain;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.fs.ChronicleMapGroupFS;
import net.openhft.chronicle.engine.fs.Clusters;
import net.openhft.chronicle.engine.fs.FilePerKeyGroupFS;
import net.openhft.chronicle.engine.fs.HostDetails;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.VanillaSessionDetails;
import net.openhft.chronicle.network.api.session.SessionDetails;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.junit.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertNotNull;

/**
 * Created by Rob Austin
 */

public class ReplicationTestBootstrappingAfterLostConnection {
    public static final WireType WIRE_TYPE = WireType.TEXT;
    public static final String NAME = "/ChMaps/test";
    public static ServerEndpoint serverEndpoint1;
    public static ServerEndpoint serverEndpoint2;

    private static AssetTree tree3;
    private static AssetTree tree1;
    private static AssetTree tree2;
    private static AtomicReference<Throwable> t = new AtomicReference();

    @BeforeClass
    public static void before() throws IOException {
        YamlLogging.setAll(false);
        ClassAliasPool.CLASS_ALIASES.addAlias(ChronicleMapGroupFS.class);
        ClassAliasPool.CLASS_ALIASES.addAlias(FilePerKeyGroupFS.class);
        //Delete any files from the last run
        Files.deleteIfExists(Paths.get(OS.TARGET, NAME));
        TCPRegistry.createServerSocketChannelFor("host.port1", "host.port2", "host.port3");
        WireType writeType = WireType.TEXT;

        tree1 = create(1, writeType, "clusterTwo");
        serverEndpoint1 = new ServerEndpoint("host.port1", tree1, writeType);

        tree2 = create(2, writeType, "clusterTwo");
        serverEndpoint2 = new ServerEndpoint("host.port2", tree2, writeType);
    }

    @AfterClass
    public static void after() throws IOException {
        if (serverEndpoint1 != null)
            serverEndpoint1.close();
        if (serverEndpoint2 != null)
            serverEndpoint2.close();

        if (tree1 != null)
            tree1.close();
        if (tree2 != null)
            tree2.close();

        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();
    }

    @NotNull
    private static AssetTree create(final int hostId, Function<Bytes, Wire> writeType, final String clusterName) {
        AssetTree tree = new VanillaAssetTree((byte) hostId)
                .forTesting(x -> t.compareAndSet(null, x))
                .withConfig(resourcesDir() + "/cmkvst", OS.TARGET + "/" + hostId);

        tree.root().addWrappingRule(MapView.class, "map directly to KeyValueStore",
                VanillaMapView::new,
                KeyValueStore.class);
        tree.root().addLeafRule(EngineReplication.class, "Engine replication holder",
                CMap2EngineReplicator::new);
        tree.root().addLeafRule(KeyValueStore.class, "KVS is Chronicle Map", (context, asset) ->
                new ChronicleMapKeyValueStore(context.wireType(writeType).cluster(clusterName),
                        asset));

        VanillaAssetTreeEgMain.registerTextViewofTree("host " + hostId, tree);

        return tree;
    }

    @NotNull
    public static String resourcesDir() {
        String path = ChronicleMapKeyValueStoreTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (path == null)
            return ".";
        return new File(path).getParentFile().getParentFile() + "/src/test/resources";
    }

    @Test
    public void testCreateAndThenFindView() {

        final String userId = "myUser";
        final String securityToken = "myToken";
        final String domain = "myDomain";

        tree1.root().addView(SessionDetails.class, VanillaSessionDetails.of(userId,
                securityToken, domain));

        final SessionDetails view = tree1.root().findView(SessionDetails.class);
        Assert.assertNotNull(view);
        Assert.assertEquals(userId, view.userId());
        Assert.assertEquals(securityToken, view.securityToken());
        Assert.assertEquals(domain, view.domain());

    }

    @After
    public void afterMethod() {
        final Throwable th = t.getAndSet(null);
        if (th != null) Jvm.rethrow(th);
    }

    @Test
    public void testBootstrapWhenTheClientConnectionIsKilled() throws InterruptedException {

        final ConcurrentMap<String, String> map1 = tree2.acquireMap(NAME, String.class, String
                .class);
        assertNotNull(map1);

        map1.put("hello1", "world1");
        final ConcurrentMap<String, String> map2 = tree1.acquireMap(NAME, String.class, String
                .class);
        assertNotNull(map2);
        map2.put("hello2", "world2");
        checkEqual(map1, map2, 2);

        simulateSomeonePullingOutTheNetworkCableAndPluginItBackIn();

        map2.put("hello5", "world5");
        map1.put("hello4", "world4");
        map2.put("hello3", "world3");
        map1.put("hello6", "world6");

        checkEqual(map1, map2, 6);

    }

    private void simulateSomeonePullingOutTheNetworkCableAndPluginItBackIn() {
        final Collection<HostDetails> cluster = cluster();
        final Iterator<HostDetails> iterator = cluster.iterator();
        iterator.next();
        final TcpChannelHub tcpChannelHub = iterator.next().tcpChannelHub();
        tcpChannelHub.forceDisconnect();
    }

    private Collection<HostDetails> cluster() {
        final Clusters clusters = tree1.root().getView(Clusters.class);
        return clusters.get("clusterTwo").hostDetails();
    }

    private void checkEqual(ConcurrentMap<String, String> map1, ConcurrentMap<String, String> map2, final int expectedSize) {
        for (int j = 0; j < 5; j++) {
            for (int i = 1; i <= 50; i++) {
                if (map1.size() == expectedSize && map2.size() == expectedSize)
                    break;
                Jvm.pause(10);
            }
        }

        // we wrap the maps in a tree-map to ensure that thier order is the same as
        // this make it easier to compare when looking at them
        Assert.assertEquals(new TreeMap<>(map1), new TreeMap<String, String>(map2));

    }

    @Test
    public void testBootstrapWhenTheServerIsKilled() throws InterruptedException, IOException {

        ConcurrentMap<String, String> map1 = tree1.acquireMap(NAME
                , String.class,
                String
                        .class);
        assertNotNull(map1);
        map1.put("hello1", "world1");

        final ConcurrentMap<String, String> map2 = tree2.acquireMap(NAME, String.class, String
                .class);
        assertNotNull(map2);
        map2.put("hello2", "world2");

        checkEqual(map1, map2, 2);

        serverEndpoint1.close();
        if (tree1 != null)
            tree1.close();

        map2.put("hello3", "world3");

        tree1 = create(1, WireType.TEXT, "clusterTwo");
        serverEndpoint1 = new ServerEndpoint("host.port1", tree1, WireType.TEXT);

        map1 = tree1.acquireMap(NAME
                , String.class, String.class);

        // given that the old map1 has been shut down this will cause and exception to be thrown
        // and map2 will attempt a reconnect to map1
        map2.put("hello4", "world4");
        map1.put("hello5", "world5");

        checkEqual(map1, map2, 6);

    }

    @Test
    public void testBootstrapWhenTheClientIsKilled() throws InterruptedException, IOException {

        ConcurrentMap<String, String> map1 = tree1.acquireMap(NAME
                , String.class,
                String
                        .class);
        assertNotNull(map1);
        map1.put("hello1", "world1");

        ConcurrentMap<String, String> map2 = tree2.acquireMap(NAME, String.class, String
                .class);
        assertNotNull(map2);
        map2.put("hello2", "world2");

        checkEqual(map1, map2, 2);

        serverEndpoint2.close();
        if (tree2 != null)
            tree2.close();

        map1.put("hello3", "world3");

        tree2 = create(2, WireType.TEXT, "clusterTwo");
        serverEndpoint2 = new ServerEndpoint("host.port2", tree2, WireType.TEXT);

        map2 = tree2.acquireMap(NAME, String.class, String.class);

        // given that the old map1 has been shut down this will cause and exception to be thrown
        // and map2 will attempt a reconnect to map1
        map2.put("hello4", "world4");
        map1.put("hello5", "world5");

        checkEqual(map1, map2, 6);
    }

    @Test
    public void testCheckDataIsLoadedFromPersistedFile() throws InterruptedException,
            IOException {

        final Path basePath = Files.createTempDirectory("temp");

        //create the map with a single entry
        ConcurrentMap<String, String> map1 = tree1.acquireMap(NAME + "unique" + "?basePath=" + basePath
                , String.class,
                String
                        .class);
        assertNotNull(map1);

        map1.put("hello1", "world1");
        Thread.sleep(100);
        //close the map
        serverEndpoint1.close();
        tree1.close();

        //recreate the map and load off the persisted file
        tree1 = create(1, WireType.TEXT, "clusterTwo");
        serverEndpoint1 = new ServerEndpoint("host.port3", tree1, WireType.TEXT);
        ConcurrentMap<String, String> map1a = tree1.acquireMap(NAME + "unique" + "?basePath=" +
                basePath
                , String.class, String.class);
        assertNotNull(map1a);
        Thread.sleep(100);
        Assert.assertEquals(1, map1a.size());

    }

    @Test
    public void testBootstrapWhenTheServerIsKilledUsingPersistedFile() throws InterruptedException,
            IOException {

        final Path basePath = Files.createTempDirectory("");

        ConcurrentMap<String, String> map1 = tree1.acquireMap(NAME + "?basePath=" + basePath
                , String.class, String.class);
        assertNotNull(map1);

        map1.put("hello1", "world1");

        final ConcurrentMap<String, String> map2 = tree2.acquireMap(NAME, String.class, String
                .class);
        assertNotNull(map2);

        map2.put("hello2", "world2");
        checkEqual(map1, map2, 2);

        serverEndpoint1.close();
        if (tree1 != null)
            tree1.close();

        map2.put("hello3", "world3");

        tree1 = create(1, WireType.TEXT, "clusterTwo");
        serverEndpoint1 = new ServerEndpoint("host.port1", tree1, WireType.TEXT);

        map1 = tree1.acquireMap(NAME + "?basePath=" + basePath
                , String.class, String.class);

        // given that the old map1 has been shut down this will cause and exception to be thrown
        // and map2 will attempt a reconnect to map1
        map2.put("hello4", "world4");
        map1.put("hello5", "world5");

        checkEqual(map1, map2, 6);

    }
}

