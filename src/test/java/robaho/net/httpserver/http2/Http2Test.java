package robaho.net.httpserver.http2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.Executors;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import robaho.net.httpserver.Http2ExchangeImpl;
import robaho.net.httpserver.LoggingFilter;

@Test(enabled=false) // this is disabled since JDK HttpClient cannot perform "prior knowledge" Http2 connections over non-SSL

public class Http2Test {

    static {
        // System.setProperty("jdk.httpclient.HttpClient.log", "all");
        // System.setProperty("jdk.internal.httpclient.websocket.debug", "true");
    }

    private static final int port = 9000;
    private static final String path = "/echo";

    HttpServer server;

    private volatile boolean foundHttp2 = false;

    @BeforeMethod
    public void setUp() throws IOException {
        Logger logger = Logger.getLogger(Http2Test.class.getName());
        ConsoleHandler ch = new ConsoleHandler();
        logger.setLevel(Level.ALL);
        ch.setLevel(Level.ALL);
        logger.addHandler(ch);

        server = HttpServer.create(new InetSocketAddress(port), 0);
        HttpHandler h = new EchoHandler();
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
    public void testHttp2Request() throws InterruptedException, IOException, URISyntaxException {
        var client = HttpClient.newBuilder().version(Version.HTTP_2).build();
        var request = HttpRequest.newBuilder(new URI("http://localhost:9000"+path)).POST(HttpRequest.BodyPublishers.ofString("This is a test")).build();
        var response = client.send(request,BodyHandlers.ofString());
        Assert.assertEquals(response.body(),"This is a test");
        Assert.assertTrue(foundHttp2);
    }

    private class EchoHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange he) throws IOException {
            if(he instanceof Http2ExchangeImpl) {
                foundHttp2 = true;
            }
            he.sendResponseHeaders(200,0);
            try (var exchange = he) {
                exchange.getRequestBody().transferTo(exchange.getResponseBody());
            }
        }
        
    }
}
