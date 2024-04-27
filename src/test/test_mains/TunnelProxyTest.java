
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import jdk.test.lib.net.SimpleSSLContext;
import jdk.test.lib.net.URIBuilder;
import robaho.net.httpserver.extras.ProxyHandler;

/**
 * @test
 * @summary test ability to create a tunneling proxy server using the CONNECT request method
 */
public class TunnelProxyTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.httpclient.HttpClient.log","all");

        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetSocketAddress addr = new InetSocketAddress (loopback, 0);

        SSLContext sslContext = new SimpleSSLContext().get();

        var server1 = HttpsServer.create(addr,100);
        server1.setHttpsConfigurator(new HttpsConfigurator(sslContext));

        var proxy = HttpServer.create(addr,100);

        var ctx = proxy.createContext("/", new ProxyHandler());

        server1.createContext("/test", (HttpExchange exchange) -> {
            System.out.println("request headers: "+exchange.getRequestHeaders());
            exchange.sendResponseHeaders(200,0);
            if(exchange.getRequestMethod().equals("POST")) {
                exchange.getRequestBody().transferTo(exchange.getResponseBody());
                exchange.getResponseBody().close();
            } else {
                try (var os = exchange.getResponseBody()) {
                    os.write("hello".getBytes());
                }
            }
        });

        proxy.start();
        server1.start();

        try {
            var client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxy.getAddress().getHostName(),proxy.getAddress().getPort())))
                .sslContext(sslContext )
                .build();

            try(client) {
                var uri = URIBuilder.newBuilder().scheme("https").host(server1.getAddress().getHostName()).port(server1.getAddress().getPort()).path("/test").build();
                var response = client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString());
                if(!response.body().equals("hello")) throw new IllegalStateException("incorrect body "+response.body());

                // response = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("senditback")).build(),HttpResponse.BodyHandlers.ofString());
                // if(!response.body().equals("senditback")) throw new IllegalStateException("incorrect body "+response.body());
            }

        } finally {
            server1.stop(0);
            proxy.stop(0);
        }
    }
}