package robaho.net.httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.testng.annotations.Test;

import jdk.test.lib.RawClient;

/**
 * see issue #19
 *
 * the server attempts to optimize flushing the response stream if there is
 * another request in the pipeline, but the bug caused the server to assume the
 * data remaining to be read was part of the next request, causing the server to
 * hang. Reading even a single character from the request body would have
 * prevented the issue since the buffer would have been filled.
 *
 * The solution is to read the remaining request data, then check if there are
 * any characters waiting to be read.
 */
public class PipeliningStallTest {

    private static final int msgCode = 200;
    private static final String someContext = "/context";

    static class ServerThreadFactory implements ThreadFactory {

        static final AtomicLong tokens = new AtomicLong();

        @Override
        public Thread newThread(Runnable r) {
            var thread = new Thread(r, "Server-" + tokens.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    static {
        Logger.getLogger("").setLevel(Level.ALL);
        Logger.getLogger("").getHandlers()[0].setLevel(Level.ALL);
    }

    @Test
    public void testSendResponse() throws Exception {
        System.out.println("testSendResponse()");
        InetAddress loopback = InetAddress.getLoopbackAddress();
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(new ServerThreadFactory());
        server.setExecutor(executor);
        try {
            server.createContext(someContext, new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    var length = exchange.getRequestHeaders().getFirst("Content-Length");

                    var msg = "hi";
                    var status = 200;
                    if (Integer.valueOf(length) > 4) {
                        msg = "oversized";
                        status = 413;
                    }

                    var bytes = msg.getBytes();

                    // -1 means no content, 0 means unknown content length
                    var contentLength = bytes.length == 0 ? -1 : bytes.length;

                    try (OutputStream os = exchange.getResponseBody()) {
                        exchange.sendResponseHeaders(status, contentLength);
                        os.write(bytes);
                    }
                }
            });
            server.start();
            System.out.println("Server started at port "
                    + server.getAddress().getPort());

            RawClient.runRawSocketHttpClient(loopback, server.getAddress().getPort(),someContext,"I will send all of the data", -1);
        } finally {
            System.out.println("shutting server down");
            executor.shutdown();
            server.stop(0);
        }
        System.out.println("Server finished.");
    }
}
