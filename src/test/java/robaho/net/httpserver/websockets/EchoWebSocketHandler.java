package robaho.net.httpserver.websockets;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

public class EchoWebSocketHandler extends WebSocketHandler {

    @Override
    protected WebSocket openWebSocket(HttpExchange exchange) {
        return new EchoWebSocket(exchange);
    }

    private static class EchoWebSocket extends WebSocket {

        public EchoWebSocket(HttpExchange exchange) {
            super(exchange);
        }

        @Override
        protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
        }

        private StringBuilder sb = new StringBuilder();

        @Override
        protected void onMessage(WebSocketFrame message) throws WebSocketException {
            sb.append(message.getTextPayload());
            if (message.isFin()) {
                String msg = sb.toString();
                sb = new StringBuilder();
                try {
                    super.send(msg);
                } catch (IOException e) {
                    throw new WebSocketException(e);
                }
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
        }
    }
}
