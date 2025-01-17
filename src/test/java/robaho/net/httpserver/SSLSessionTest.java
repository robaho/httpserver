package robaho.net.httpserver;

import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import static org.testng.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import jdk.test.lib.net.SimpleSSLContext;
import jdk.test.lib.net.URIBuilder;

public class SSLSessionTest {
    volatile boolean hasSslSession = false;
    @Test
    public void TestConfigureCalled() throws IOException, URISyntaxException {
        int port = 8443;

        var httpsServer = HttpsServer.create(new InetSocketAddress(port), 8192);
        var ctx = new SimpleSSLContext().get();
        httpsServer.setHttpsConfigurator(new MyHttpsConfigurator (ctx));
        httpsServer.createContext("/test", (HttpExchange he) -> {
            var sslSession = (HttpsExchange)he;
            hasSslSession = sslSession!=null;
            he.sendResponseHeaders(200,0);
            try (var os = he.getResponseBody()) {
                os.write("Hello".getBytes());
            }
        });
        httpsServer.start();
        try {
            URL url = URIBuilder.newBuilder()
                .scheme("https")
                .loopback()
                .port(httpsServer.getAddress().getPort())
                .path("/test")
                .toURL();
            HttpsURLConnection urlc = (HttpsURLConnection)url.openConnection(Proxy.NO_PROXY);
            urlc.setSSLSocketFactory (ctx.getSocketFactory());
            urlc.setHostnameVerifier (new DummyVerifier());
            urlc.getInputStream().readAllBytes();
            assertTrue(urlc.getResponseCode()==200);
        } finally {
            httpsServer.stop(0);
        }
        assertTrue(hasSslSession);
    }

    class MyHttpsConfigurator extends HttpsConfigurator {
        public MyHttpsConfigurator(SSLContext context) {
            super(context);
        }

        @Override
        public void configure(HttpsParameters params) {
            super.configure(params);
        }
    }
    public class DummyVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String s, SSLSession s1) {
            return true;
        }
    }
}
