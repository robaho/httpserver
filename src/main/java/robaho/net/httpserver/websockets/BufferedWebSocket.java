package robaho.net.httpserver.websockets;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;

import robaho.net.httpserver.Attributes;

/**
 * a BufferedWebSocket allows the producer to buffer in user space (by limited
 * kernel buffer size) and provides properties to determine if the consumer is
 * reading the messages via pending()
 */
public abstract class BufferedWebSocket extends WebSocket {

    private static ExecutorService executor;

    private final LinkedBlockingQueue<WebSocketFrame> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger();

    private synchronized static ExecutorService getExecutor() {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        return executor;
    }

    private final Future<?> future;

    public static synchronized void setExecutor(ExecutorService executor) {
        if (executor != null) {
            throw new IllegalStateException("executor is already set");
        }
        BufferedWebSocket.executor = executor;
    }

    /**
     * a BufferedWebSocket that uses the kernel default buffer sizes
     */
    public BufferedWebSocket(HttpExchange exchange) {
        this(exchange, 0);
    }

    /**
     * a BufferedWebSocket that allows control over the kernel buffer sizes
     *
     * @param kernelBufferSizeBytes the kernel buffer size in bytes or 0 to use
     * the default
     *
     */
    public BufferedWebSocket(HttpExchange exchange, int kernelBufferSizeBytes) {
        super(exchange);
        if (kernelBufferSizeBytes != 0) {
            exchange.setAttribute(Attributes.SOCKET_WRITE_BUFFER, kernelBufferSizeBytes);
        }
        future = getExecutor().submit(() -> sendFrames());
    }

    @Override
    void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
        future.cancel(true);
        super.doClose(code, reason, initiatedByRemote);
    }

    private void sendFrames() {
        try {
            while (true) {
                var frame = queue.take();
                super.sendFrame(frame);
                pending.decrementAndGet();
            }
        } catch (IOException ex) {
            onException(ex);
            doClose(CloseCode.InvalidFramePayloadData, ex.toString(), false);
        } catch (InterruptedException ex) {
        }
    }

    @Override
    public void sendFrame(WebSocketFrame frame) throws IOException {
        pending.incrementAndGet();
        queue.add(frame);
    }

    public int pending() {
        return pending.get();
    }
}
