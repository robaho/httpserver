
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jdk.test.lib.net.URIBuilder;
import robaho.net.httpserver.extras.ProxyHandler;

/**
 * @test
 * @summary test the ProxyHandler
 */
public class ProxyHandlerTest {
    public static void main(String[] args) throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetSocketAddress addr = new InetSocketAddress (loopback, 0);   

        var server1 = HttpServer.create(addr,100);
        var proxy = HttpServer.create(addr,100);

        proxy.createContext("/test",new ProxyHandler(new ProxyHandler.HostPort(server1.getAddress().getHostName(),server1.getAddress().getPort(),"http")));
        server1.createContext("/test", (HttpExchange exchange) -> {
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
            var client = HttpClient.newHttpClient();

            var uri = URIBuilder.newBuilder().scheme("http").host(server1.getAddress().getHostName()).port(server1.getAddress().getPort()).path("/test").build();
            var response = client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString());
            if(!response.body().equals("hello")) throw new IllegalStateException("incorrect body "+response.body());

            uri = URIBuilder.newBuilder().scheme("http").host(proxy.getAddress().getHostName()).port(proxy.getAddress().getPort()).path("/test").build();
            response = client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString());
            if(!response.body().equals("hello")) throw new IllegalStateException("incorrect body "+response.body());

            response = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("senditback")).build(),HttpResponse.BodyHandlers.ofString());
            if(!response.body().equals("senditback")) throw new IllegalStateException("incorrect body "+response.body());


        } finally {
            server1.stop(0);
            proxy.stop(0);
        }
    }
}