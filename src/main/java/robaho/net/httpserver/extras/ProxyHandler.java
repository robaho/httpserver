package robaho.net.httpserver.extras;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * proxy requests to another host
 */
public class ProxyHandler implements HttpHandler {
    public record HostPort(String server,int port,String scheme){}

    private final ConcurrentMap<URI,HostPort> proxies = new ConcurrentHashMap<>();
    private final HttpClient proxyClient;
    private final Optional<HostPort> defaultProxy;

    public ProxyHandler() {
        this(Optional.empty());
    }
    public ProxyHandler(HostPort defaultProxy) {
        this(Optional.of(defaultProxy));
    }
    private ProxyHandler(Optional<HostPort> defaultProxy) {
        this.defaultProxy = defaultProxy;
        proxyClient = HttpClient.newBuilder().proxy(new ProxySelector(){
            @Override
            public List<Proxy> select(URI uri) {
                var hp = proxies.get(uri);
                return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(hp.server,hp.port)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                ProxyHandler.this.connectFailed(uri, sa, ioe);
            }
        }).build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var proxy = proxyTo(exchange).orElseThrow(() -> new IOException("proxy not configured for "+exchange.getRequestURI()));
        var uri = exchange.getRequestURI();
        if(uri.getScheme()==null) {
            try {
                uri = new URI(proxy.scheme,uri.getUserInfo(),exchange.getLocalAddress().getHostName(),exchange.getLocalAddress().getPort(),uri.getPath(),uri.getQuery(),uri.getFragment());
            } catch (URISyntaxException ex) {
                throw new IOException("invalid proxy uri ",ex);
            }
        }
        proxies.put(uri,proxy);
        try {
            var response = proxyClient.send(HttpRequest.newBuilder(uri).headers(headers(exchange.getRequestHeaders())).method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofInputStream(() -> exchange.getRequestBody())).build(),HttpResponse.BodyHandlers.ofInputStream());
            exchange.getResponseHeaders().putAll(response.headers().map());
            exchange.sendResponseHeaders(response.statusCode(),0);
            try (var os = exchange.getResponseBody ()) {
                response.body().transferTo(os);
            }
        } catch (InterruptedException ex) {
            throw new IOException("unable to proxy request to "+exchange.getRequestURI(),ex);
        }
    }

    private static final Set<String> restrictedHeaders = Set.of("CONNECTION","HOST","UPGRADE","CONTENT-LENGTH");

    private static String[] headers(Headers headers) {
        List<String> copy = new ArrayList<>();
        headers.entrySet().stream().filter(e -> !restrictedHeaders.contains(e.getKey().toUpperCase())).forEach(e -> e.getValue().forEach(v -> { copy.add(e.getKey()); copy.add(v);}));
        return copy.toArray(String[]::new);
    }

    protected Optional<HostPort> proxyTo(HttpExchange exchange) {
        return defaultProxy;
    }
    
    protected void connectFailed(URI uri, SocketAddress sa, IOException ieo) {
    }
    
}