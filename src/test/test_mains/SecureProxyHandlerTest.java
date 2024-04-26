
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jdk.test.lib.net.URIBuilder;
import robaho.net.httpserver.extras.ProxyHandler;

/**
 * @test
 * @summary test the ProxyHandler using a protected proxy endpoint
 */
public class SecureProxyHandlerTest {
    static final String REALM = "U\u00ffU@realm";  // non-ASCII char

    public static void main(String[] args) throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetSocketAddress addr = new InetSocketAddress (loopback, 0);   

        var server1 = HttpServer.create(addr,100);
        var proxy = HttpServer.create(addr,100);

        var auth = new ServerAuthenticator(REALM);

        proxy.createContext("/test",new ProxyHandler(new ProxyHandler.HostPort(server1.getAddress().getHostName(),server1.getAddress().getPort(),"http")));
        HttpHandler handler = (HttpExchange exchange) -> {
            exchange.sendResponseHeaders(200,0);
            if(exchange.getRequestMethod().equals("POST")) {
                exchange.getRequestBody().transferTo(exchange.getResponseBody());
                exchange.getResponseBody().close();
            } else {
                try (var os = exchange.getResponseBody()) {
                    os.write("hello".getBytes());
                }
            }
        };
        var ctx = server1.createContext("/test", handler);
        ctx.setAuthenticator(auth);

        proxy.start();
        server1.start();

        try {
            var client = HttpClient.newBuilder().authenticator(new ClientAuthenticator()).build();

            var direct_uri = URIBuilder.newBuilder().scheme("http").host(server1.getAddress().getHostName()).port(server1.getAddress().getPort()).path("/test").build();
            var response = client.send(HttpRequest.newBuilder(direct_uri).build(),HttpResponse.BodyHandlers.ofString());
            if(!response.body().equals("hello")) throw new IllegalStateException("incorrect body "+response.body());

            var uri = URIBuilder.newBuilder().scheme("http").host(proxy.getAddress().getHostName()).port(proxy.getAddress().getPort()).path("/test").build();
            var proxied_response = client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString());
            if(!proxied_response.body().equals("hello")) throw new IllegalStateException("incorrect body "+response.body());

            var post_response = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("senditback")).build(),HttpResponse.BodyHandlers.ofString());
            if(!post_response.body().equals("senditback")) throw new IllegalStateException("incorrect body "+response.body());


        } finally {
            server1.stop(0);
            proxy.stop(0);
        }
    }
    static class ServerAuthenticator extends BasicAuthenticator {
        ServerAuthenticator(String realm) {
            super(realm);
        }

        @Override
        public boolean checkCredentials(String username, String password) {
            return getRealm().equals(realm) && "password".equals(password);
        }
    }
    static class ClientAuthenticator extends java.net.Authenticator {
        @Override
        public PasswordAuthentication getPasswordAuthentication() {
            if (!getRequestingPrompt().equals(REALM)) {
                throw new RuntimeException("realm does not match");
            }
            return new PasswordAuthentication("username", "password".toCharArray());
        }
    }

}