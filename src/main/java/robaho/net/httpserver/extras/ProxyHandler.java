package robaho.net.httpserver.extras;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import javax.net.SocketFactory;

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

    protected final Logger logger = Logger.getLogger("robaho.net.httpserver.ProxyHandler");

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
        if(exchange.getRequestMethod().equals("CONNECT")) {
            if(!authorizeConnect(exchange)) return;
            try (Socket s = SocketFactory.getDefault().createSocket()) {
                var uri = exchange.getRequestURI();
                var addr = new InetSocketAddress(uri.getScheme(),Integer.parseInt(uri.getSchemeSpecificPart()));
                try {
                    s.connect(addr);
                } catch(Exception e) {
                    logger.warning("failed to connect to "+addr);
                    exchange.sendResponseHeaders(500,-1);
                    return;
                }
                logger.fine("connected to "+s.getRemoteSocketAddress());
                exchange.sendResponseHeaders(200,0);

                try {
                    exchange.getHttpContext().getServer().getExecutor().execute(() -> {
                        try {
                            transfer(s.getInputStream(),exchange.getResponseBody());
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    });
                    transfer(exchange.getRequestBody(),s.getOutputStream());
                } finally {
                    logger.fine("proxy connection to "+s.getRemoteSocketAddress()+" ended");
                    return;
                }
            }
        }
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
                transfer(response.body(),os);
            }
        } catch (InterruptedException ex) {
            throw new IOException("unable to proxy request to "+exchange.getRequestURI(),ex);
        }
    }

    /**
     * override to check authorization headers. if returning false,
     * the implementation must call exchange.sendResponseHeaders() with the appropriate code.
     * 
     * @return true if the CONNECT should proceed, else false
     */
    protected boolean authorizeConnect(HttpExchange exchange) {
        return true;
    }

    private static int DEFAULT_BUFFER_SIZE = 16384;
    private static long transfer(InputStream in, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        long transferred = 0;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer, 0, DEFAULT_BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, read);
            out.flush();
            if (transferred < Long.MAX_VALUE) {
                try {
                    transferred = Math.addExact(transferred, read);
                } catch (ArithmeticException ignore) {
                    transferred = Long.MAX_VALUE;
                }
            }
        }
        return transferred;
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