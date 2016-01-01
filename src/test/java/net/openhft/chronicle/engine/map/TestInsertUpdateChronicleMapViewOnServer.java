package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Rob Austin.
 */
@RunWith(value = Parameterized.class)
public class TestInsertUpdateChronicleMapViewOnServer {

    private static final String NAME = "test";
    private static AtomicReference<Throwable> t = new AtomicReference();
    private final WireType wireType;
    public String connection = "RemoteSubscriptionTest.host.port";
    @NotNull

    private AssetTree clientAssetTree ;
    private VanillaAssetTree serverAssetTree;
    private ServerEndpoint serverEndpoint;

    public TestInsertUpdateChronicleMapViewOnServer(WireType wireType) {

        this.wireType = wireType;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() throws IOException {
        final List<Object[]> list = new ArrayList<>();
        list.add(new Object[]{WireType.BINARY});
        list.add(new Object[]{WireType.TEXT});
        return list;
    }

    @After
    public void afterMethod() {
        final Throwable th = t.getAndSet(null);
        if (th != null) Jvm.rethrow(th);
    }

    @Before
    public void before() throws IOException {
        serverAssetTree = new VanillaAssetTree().forTesting(x -> t.compareAndSet(null, x));

        YamlLogging.setAll(false);

        connection = "TestInsertUpdateChronicleMapView.host.port";
        TCPRegistry.createServerSocketChannelFor(connection);
        serverEndpoint = new ServerEndpoint(connection, serverAssetTree, wireType);

        serverAssetTree.root().addWrappingRule(MapView.class, "map directly to " + "KeyValueStore",
                VanillaMapView::new, KeyValueStore.class);

        serverAssetTree.root().addLeafRule(KeyValueStore.class, "use Chronicle Map", (context, asset) ->
                new ChronicleMapKeyValueStore(context.basePath(null).entries(100)
                        .putReturnsNull(false), asset));

        clientAssetTree = serverAssetTree;

    }

    @After
    public void after() throws IOException {
        clientAssetTree.close();
        Jvm.pause(1000);
        if (serverEndpoint != null)
            serverEndpoint.close();
        serverAssetTree.close();

        TCPRegistry.reset();
    }

    @Test
    public void testInsertFollowedByUpdate() throws Exception {

        final MapView<String, String> serverMap = serverAssetTree.acquireMap
                ("name?putReturnsNull=false",
                        String.class, String
                                .class);

        final BlockingQueue<MapEvent> events = new ArrayBlockingQueue<>(128);
        clientAssetTree.registerSubscriber("name?putReturnsNull=false", MapEvent.class,
                events::add);

        {
            serverMap.put("hello", "world");
            final MapEvent event = events.poll(10, SECONDS);
            Assert.assertTrue(event instanceof InsertedEvent);
        }
        {
            serverMap.put("hello", "world2");
            final MapEvent event = events.poll(10, SECONDS);
            Assert.assertTrue(event instanceof UpdatedEvent);
        }
    }

    @Test
    public void testInsertFollowedByUpdateWhenPutReturnsNullTrue() throws Exception {

        final MapView<String, String> serverMap = serverAssetTree.acquireMap
                ("name?putReturnsNull=true",
                        String.class, String
                                .class);

        final BlockingQueue<MapEvent> events = new ArrayBlockingQueue<>(128);
        clientAssetTree.registerSubscriber("name?putReturnsNull=true", MapEvent.class,
                events::add);

        Thread.sleep(1000);

        {
            serverMap.put("hello", "world");
            final MapEvent event = events.poll(10, SECONDS);
            Assert.assertTrue(event instanceof InsertedEvent);
        }
        {
            serverMap.put("hello", "world2");
            final MapEvent event = events.poll(10, SECONDS);
            Assert.assertTrue(event instanceof UpdatedEvent);
        }
    }

}
