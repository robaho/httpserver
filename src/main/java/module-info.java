module robaho.httpserver {
    exports robaho.net.httpserver;
    exports robaho.net.httpserver.extras;
    exports robaho.net.httpserver.websockets;
    exports robaho.net.httpserver.http2;

    requires transitive java.logging;
    requires transitive java.net.http;
    requires transitive jdk.httpserver;

    provides com.sun.net.httpserver.spi.HttpServerProvider with robaho.net.httpserver.DefaultHttpServerProvider;
}
