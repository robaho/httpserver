package jdk.test.lib;

import java.io.*;
import java.net.*;
import static java.nio.charset.StandardCharsets.UTF_8;

public class RawClient {

    /**
     * performs an HTTP request using a raw socket.
     *
     * @param address the server address
     * @param port the server port
     * @param context the server context (i.e. endpoint)
     * @param data the data to send in the request body
     * @param contentLength the content length, if > 0, this will be used as the
     * Content-length header value which can be used to simulate the client
     * advertising more data than it sends. in almost all cases this parameter
     * should be 0, upon which the actual length of the data is used.
     * @throws Exception
     */
    public static void runRawSocketHttpClient(InetAddress address, int port, String context, String data, int contentLength)
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
            String body = data == null ? "" : data;
            if (contentLength <= 0) {
                contentLength = body.getBytes(UTF_8).length;
            }

            writer.print("GET " + context + "/ HTTP/1.1" + CRLF);
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

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
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
