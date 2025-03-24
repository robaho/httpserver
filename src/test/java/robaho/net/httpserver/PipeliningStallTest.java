package robaho.net.httpserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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

import static java.nio.charset.StandardCharsets.*;

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

            runRawSocketHttpClient(loopback, server.getAddress().getPort(), -1);
        } finally {
            System.out.println("shutting server down");
            executor.shutdown();
            server.stop(0);
        }
        System.out.println("Server finished.");
    }

    static void runRawSocketHttpClient(InetAddress address, int port, int contentLength)
            throws Exception {
        Socket socket = null;
        PrintWriter writer = null;
        BufferedReader reader = null;
        final String CRLF = "\r\n";
        try {
            socket = new Socket(address, port);
            writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream()));
            System.out.println("Client connected by socket: " + socket);
            String body = "I will send all the data.";
            if (contentLength <= 0) {
                contentLength = body.getBytes(UTF_8).length;
            }

            writer.print("GET " + someContext + "/ HTTP/1.1" + CRLF);
            writer.print("User-Agent: Java/"
                    + System.getProperty("java.version")
                    + CRLF);
            writer.print("Host: " + address.getHostName() + CRLF);
            writer.print("Accept: */*" + CRLF);
            writer.print("Content-Length: " + contentLength + CRLF);
            writer.print("Connection: keep-alive" + CRLF);
            writer.print(CRLF); // Important, else the server will expect that
            // there's more into the request.
            writer.flush();
            System.out.println("Client wrote request to socket: " + socket);
            writer.print(body);
            writer.flush();

            reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream()));
            System.out.println("Client start reading from server:");
            String line = reader.readLine();
            for (; line != null; line = reader.readLine()) {
                if (line.isEmpty()) {
                    break;
                }
                System.out.println("\"" + line + "\"");
            }
            System.out.println("Client finished reading from server");
        } finally {
            // give time to the server to try & drain its input stream
            Thread.sleep(500);
            // closes the client outputstream while the server is draining
            // it
            if (writer != null) {
                writer.close();
            }
            // give time to the server to trigger its assertion
            // error before closing the connection
            Thread.sleep(500);
            if (reader != null)
                try {
                reader.close();
            } catch (IOException logOrIgnore) {
                logOrIgnore.printStackTrace();
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException logOrIgnore) {
                    logOrIgnore.printStackTrace();
                }
            }
        }
        System.out.println("Client finished.");
    }

}
