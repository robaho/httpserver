package robaho.net.httpserver.websockets;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import robaho.net.httpserver.Code;
import robaho.net.httpserver.Utils;

public abstract class WebSocketHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();

        if (!isWebsocketRequested(headers)) {
            exchange.sendResponseHeaders(Code.HTTP_BAD_REQUEST, -1l);
            return;
        }

        if (!Util.HEADER_WEBSOCKET_VERSION_VALUE.equalsIgnoreCase(headers.getFirst(Util.HEADER_WEBSOCKET_VERSION))) {
            Util.sendResponseHeaders(exchange, Code.HTTP_BAD_REQUEST,
                    "Invalid Websocket-Version " + headers.getFirst(Util.HEADER_WEBSOCKET_VERSION));
            return;
        }

        if (!headers.containsKey(Util.HEADER_WEBSOCKET_KEY)) {
            Util.sendResponseHeaders(exchange, Code.HTTP_BAD_REQUEST, "Missing Websocket-Key");
            return;
        }

        WebSocket webSocket = openWebSocket(exchange);

        try {
            exchange.getResponseHeaders().add(Util.HEADER_WEBSOCKET_ACCEPT,
                    Util.makeAcceptKey(headers.getFirst(Util.HEADER_WEBSOCKET_KEY)));
        } catch (NoSuchAlgorithmException e) {
            Util.sendResponseHeaders(exchange, Code.HTTP_INTERNAL_ERROR,
                    "The SHA-1 Algorithm required for websockets is not available on the server.");
            return;
        }

        if (headers.containsKey(Util.HEADER_WEBSOCKET_PROTOCOL)) {
            exchange.getResponseHeaders().add(Util.HEADER_WEBSOCKET_PROTOCOL,
                    headers.getFirst(Util.HEADER_WEBSOCKET_PROTOCOL).split(",")[0]);
        }

        exchange.getResponseHeaders().add(Util.HEADER_UPGRADE, Util.HEADER_UPGRADE_VALUE);
        exchange.getResponseHeaders().add(Util.HEADER_CONNECTION, Util.HEADER_CONNECTION_VALUE);

        exchange.sendResponseHeaders(101, 0);

        // this won't return until websocket is closed
        webSocket.readWebsocket();
    }

    public static boolean isWebsocketRequested(Headers headers) {
        // check if Upgrade connection
        var values = headers.get(Util.HEADER_CONNECTION);
        if(values==null || values.stream().filter(s -> Utils.containsIgnoreCase(s, Util.HEADER_CONNECTION_VALUE)).findAny().isEmpty()) return false;
        // check for proper upgrade type
        String upgrade = headers.getFirst(Util.HEADER_UPGRADE);
        return Util.HEADER_UPGRADE_VALUE.equalsIgnoreCase(upgrade);
    }

    protected abstract WebSocket openWebSocket(HttpExchange exchange);
}
