package robaho.net.httpserver.websockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.testng.Assert.fail;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import robaho.net.httpserver.LoggingFilter;

public class WebSocketTest {

    static {
        // System.setProperty("jdk.httpclient.HttpClient.log", "all");
        // System.setProperty("jdk.internal.httpclient.websocket.debug", "true");
    }

    private static final int port = 9000;
    private static final String path = "/ws";

    HttpServer server;

    @BeforeMethod
    public void setUp() throws IOException {
        Logger logger = Logger.getLogger(WebSocketTest.class.getName());
        ConsoleHandler ch = new ConsoleHandler();
        logger.setLevel(Level.ALL);
        ch.setLevel(Level.ALL);
        logger.addHandler(ch);

        server = HttpServer.create(new InetSocketAddress(port), 0);
        HttpHandler h = new EchoWebSocketHandler();
        HttpContext c = server.createContext(path, h);
        c.getFilters().add(new LoggingFilter(logger));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterMethod
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void testEcho() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        var client = WebSocketClientUtil.createWSC(9000, path, s -> {
            if ("a_message".equals(s)) {
                latch.countDown();
            } else {
                fail("received wrong message");
            }
        }, null);

        client.sendText("a_message", true);

        if (!latch.await(5, TimeUnit.SECONDS)) {
            fail("did not receive message");
        }
    }

}
