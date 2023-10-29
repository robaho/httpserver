package robaho.net.httpserver.websockets;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WebSocketClientUtil {

    public static WebSocket createWSC(int port, String path, final Consumer<String> onTextCallback,
            Runnable onCloseCallback)
            throws InterruptedException {

        HttpClient client = HttpClient.newHttpClient();

        CountDownLatch waitForOpen = new CountDownLatch(1);

        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + path), new WebSocket.Listener() {
                    StringBuilder text = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) {
                            onTextCallback.accept(text.toString());
                            text = new StringBuilder();
                        }
                        webSocket.request(1);

                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        if (onCloseCallback != null) {
                            onCloseCallback.run();
                        }
                        return null;
                    }

                    public void onError(WebSocket webSocket, Throwable error) {
                        if (onCloseCallback != null) {
                            onCloseCallback.run();
                        }
                    }

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        waitForOpen.countDown();
                        webSocket.request(1);
                    }
                });

        WebSocket ws = future.join();
        if (!waitForOpen.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("websocket did not open");
        }
        return ws;
    }

}