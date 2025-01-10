package robaho.net.httpserver.http2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.NoSyncBufferedOutputStream;
import robaho.net.httpserver.OptimizedHeaders;
import robaho.net.httpserver.http2.hpack.HPackContext;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.http2.frame.ResetStreamFrame;
import robaho.net.httpserver.http2.frame.SettingIdentifier;
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;

public class HTTP2Stream {

    private final int streamId;

    // needs to be accessible for connection to adjust based on SettingsFrame
    final AtomicLong sendWindow = new AtomicLong(65535);
    private final AtomicLong receiveWindow = new AtomicLong(65535);
    private final int initialWindowSize;

    private final HTTP2Connection connection;
    private final Logger logger;
    private final OutputStream outputStream;
    private final DataIn dataIn;
    private final HTTP2Connection.StreamHandler handler;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new OptimizedHeaders(16);
    private final AtomicBoolean headersSent = new AtomicBoolean(false);

    private volatile Thread thread;
    private volatile boolean streamOpen = true;
    private volatile boolean halfClosed = false;
    private volatile boolean streamOutClosed = false;
    private volatile AtomicBoolean handlingRequest = new AtomicBoolean(false);

    private long dataInSize = 0;

    public HTTP2Stream(int streamId, HTTP2Connection connection, Headers requestHeaders, HTTP2Connection.StreamHandler handler) throws IOException {
        this.streamId = streamId;
        this.connection = connection;
        this.logger = connection.logger;
        this.requestHeaders = requestHeaders;
        this.handler = handler;
        this.dataIn = new DataIn();
        this.outputStream = new NoSyncBufferedOutputStream(new Http2OutputStream(streamId));
        var setting = connection.getRemoteSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            sendWindow.set((int)(setting.value));
        }
        setting = connection.getLocalSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            receiveWindow.set((int)(setting.value));
            initialWindowSize = (int)setting.value;
        } else {
            initialWindowSize = 65535;
        }
        if(logger.isLoggable(Level.TRACE)) {
            logger.log(Level.TRACE,() -> "new stream, send window size "+sendWindow.get()+", receive window size "+receiveWindow.get()+" on stream "+streamId);
        }
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public String toString() {
        return connection.httpConnection.toString()+" stream "+streamId;
    }

    public void debug() {
        logger.log(Level.INFO,connection.toString()+", stream "+streamId+" open "+streamOpen+" half closed "+halfClosed+", thread "+thread);
        logger.log(Level.INFO,connection.toString()+", stream "+streamId+" data in size "+dataInSize+" expected "+expectedSize());
        logger.log(Level.INFO,""+Arrays.toString(thread.getStackTrace()));
    }

    public boolean isOpen() {
        return streamOpen;
    }
    public boolean isHalfClosed() {
        return halfClosed;
    }

    private long expectedSize() {
        if(requestHeaders.containsKey("Content-length")) {
            return Long.parseLong(requestHeaders.getFirst("Content-length"));
        }
        return -1;
    }

    public void close() {
        streamOpen = false;
        halfClosed = true;

        if(connection.http2Streams.put(streamId,null)==null) {
            return;
        }
        logger.log(Level.TRACE,() -> "closing stream "+streamId);

        try {
            dataIn.close();
            outputStream.close();
            if(thread!=null)
                thread.interrupt();
        } catch (IOException e) {
            if(!connection.isClosed()) {
                connection.close();
                logger.log(connection.httpConnection.requestCount.get()>0 ? Level.WARNING : Level.DEBUG, "IOException closing http2 stream",e);
            }
        } finally {
        }
    }

    public void processFrame(BaseFrame frame) throws HTTP2Exception, IOException {

        switch (frame.getHeader().getType()) {
        case HEADERS:
        case CONTINUATION:
            if(halfClosed) {
                throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED);
            }

            performRequest(frame.getHeader().getFlags().contains(FrameFlag.END_STREAM));
            break;
        case DATA:
            DataFrame dataFrame = (DataFrame) frame;
            logger.log(Level.TRACE,()->"received data frame, length "+dataFrame.body.length+" on stream "+streamId);
            if(halfClosed) {
                throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED);
            }
            if(!streamOpen) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
            }
            dataIn.enqueue(dataFrame.body);
            dataInSize += dataFrame.body.length;
            if (dataFrame.getHeader().getFlags().contains(FrameFlag.END_STREAM)) {
                long expected = expectedSize();
                if(expected!=-1 && dataInSize!=expected) {
                    connection.sendResetStream(HTTP2ErrorCode.PROTOCOL_ERROR, streamId);
                    close();
                    break;
                }
                dataIn.close();
                halfClosed = true;
            }
            break;
        case PRIORITY:
            if(streamOpen) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
            }
            break;
        case RST_STREAM:
            ResetStreamFrame resetFrame = (ResetStreamFrame) frame;
            logger.log(Level.DEBUG,"received reset stream "+resetFrame.errorCode+", on stream "+streamId);
            close();
            break;
        case WINDOW_UPDATE:
            int windowSizeIncrement = ((WindowUpdateFrame)frame).getWindowSizeIncrement();
            if(sendWindow.addAndGet(windowSizeIncrement)> 2147483647) {
                connection.sendResetStream(HTTP2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                close();
            }
            logger.log(Level.DEBUG,"received window update "+windowSizeIncrement+", new size "+sendWindow.get()+", on stream "+streamId);
            break;
        default:
            break;
        }
    }

    private void performRequest(boolean halfClosed) throws IOException, HTTP2Exception {
        if(!handlingRequest.compareAndSet(false, true)) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"already received headers for stream "+streamId);
        }
        connection.httpConnection.requestCount.incrementAndGet();
        connection.requestsInProgress.incrementAndGet();
        connection.stats.activeStreams.incrementAndGet();

        InputStream in = halfClosed ? InputStream.nullInputStream() : dataIn;
        
        if(halfClosed) {
            this.halfClosed = true;
            dataIn.close();
        }

        handler.getExecutor().execute(() -> {
                thread = Thread.currentThread();
                try {
                    handler.handleStream(this,in,outputStream);
                } catch (IOException ex) {
                    logger.log(Level.DEBUG,"io exception on stream "+streamId,ex);
                    close();
                }
        });
    }
    /**
     * @param closeStream if true the output stream is closed, and any attempts
     * to write data to the stream will fail. This is an optimization that
     * allows the CLOSE_STREAM bit to be set in the Headers frame, reducing the
     * packet count.
     */
    public void writeResponseHeaders(boolean closeStream) throws IOException {
        if (headersSent.compareAndSet(false, true)) {
            connection.lock();
            try {
                HPackContext.writeHeaderFrame(responseHeaders, connection.outputStream, streamId, closeStream);
                if (closeStream) {
                    streamOutClosed = true;
                }
            } finally {
                connection.unlock();
            }
        }
    }

    public InetSocketAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    class Http2OutputStream extends OutputStream {
        private static final FlagSet END_STREAM = FlagSet.of(FrameFlag.END_STREAM);

        private final int streamId;
        private final int max_frame_size;
        private boolean closed;

        public Http2OutputStream(int streamId) {
            this.streamId = streamId;
            var setting = connection.getRemoteSettings().get(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE);
            max_frame_size = setting!=null ? (int)setting.value : 16384;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            connection.stats.bytesSent.addAndGet(len);
            // test outside of lock so other streams can progress
            while(sendWindow.get()<=0 && !connection.isClosed()) {
                connection.stats.pauses.incrementAndGet();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            writeResponseHeaders(false);
            if(streamOutClosed) {
                throw new IOException("output stream was closed during headers send");
            }
            while(len>0) {
                int _len = Math.min(Math.min(len,max_frame_size),(int)Math.min(connection.sendWindow.get(),sendWindow.get()));
                if(_len<=0) {
                    connection.stats.pauses.incrementAndGet();
                    connection.lock();
                    try {
                        connection.stats.flushes.incrementAndGet();
                        connection.outputStream.flush();
                    } finally {
                        connection.unlock();
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    if(connection.isClosed()) {
                        throw new IOException("connection closed");
                    }
                    int remaining = len;
                    // logger.log(Level.TRACE,() -> "paused sending data frame, remaining "+remaining+", length "+_len+" on stream "+streamId);
                    continue;
                }
                if(connection.sendWindow.addAndGet(-_len)<0) {
                    // if we can't get the space from the connection window, need to retry
                    connection.sendWindow.addAndGet(_len);
                    continue;
                }
                connection.lock();
                try {
                    FrameHeader.writeTo(connection.outputStream, _len, FrameType.DATA, FrameFlag.NONE, streamId);
                    connection.outputStream.write(b,off,_len);
                    connection.stats.framesSent.incrementAndGet();
                } finally {
                    connection.unlock();
                }
                // byte[] header = FrameHeader.encode(_len, FrameType.DATA, FrameFlag.NONE, streamId);
                // byte[] data = Arrays.copyOfRange(b,off, len);
                // connection.enqueue(List.of(header,data));
                off+=_len;
                len-=_len;
                sendWindow.addAndGet(-_len);
                logger.log(Level.TRACE,() -> "sent data frame, length "+_len+", new send window "+sendWindow.get()+" on stream "+streamId);
            }
        }
        @Override
        public void flush() throws IOException {
        }
        @Override
        public void close() throws IOException {
            if(closed) return;
            try {
                if(connection.isClosed()) {
                    if(headersSent.compareAndSet(false,true)) {
                        logger.log(Level.WARNING,"stream connection is closed and headers not sent on stream "+streamId);
                    }
                    return;
                }
                writeResponseHeaders(false);
                connection.lock();
                boolean lastRequest = connection.requestsInProgress.decrementAndGet() == 0;
                try {
                    if(!streamOutClosed) {
                        FrameHeader.writeTo(connection.outputStream, 0, FrameType.DATA, END_STREAM, streamId);
                        connection.stats.framesSent.incrementAndGet();
                    }
                    if(lastRequest) {
                        connection.outputStream.flush();
                        connection.stats.flushes.incrementAndGet();
                    }
                } finally {
                    connection.unlock();
                }
                // same as http1, read all incoming frames when closing the output stream.
                // TODO review this, as the http2 stream is bidirectional and the spec may allow the server to continue to process inbound frames
                // after closing the outbound stream - similar to a http2 client
                dataIn.readAllBytes();
            } finally {
                connection.stats.activeStreams.decrementAndGet();
                closed=true;
                HTTP2Stream.this.close();
            }
        }
    }

    // the data InputStream passed to handlers
    private class DataIn extends InputStream {
        private final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
        private volatile Thread reader;
        /** offset into the top of the queue array */
        private int offset = 0;
        private volatile boolean closed;

        public DataIn() {
        }

        void enqueue(byte[] data) {
            if(closed) return;
            queue.add(data);
            LockSupport.unpark(reader);
        }

        @Override
        public void close() throws IOException {
            closed=true;
            LockSupport.unpark(reader);
        }

        private final byte[] single = new byte[1];

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = 0;
            try {
                reader = Thread.currentThread();
                for(;len>0;) {
                    byte[] data;
                    while((data=queue.peek())==null) {
                        if(read>0) {
                            return read;
                        }
                        if(closed) return -1;
                        LockSupport.park();
                        if(Thread.interrupted()) {
                            throw new IOException("interrupted");
                        }
                    } 
                    int available = data.length-offset;
                    int bytesToRead = Math.min(len, available);
                    System.arraycopy(data, offset, b, off, bytesToRead);
                    offset+=bytesToRead;
                    off+=bytesToRead;
                    len-=bytesToRead;
                    available-=bytesToRead;
                    read+=bytesToRead;
                    if(available==0) { // remove top buffer from queue
                        queue.poll();
                        offset=0;
                    }
                }
                return read;
            } finally {
                if(receiveWindow.addAndGet(-read)<initialWindowSize/2) {
                    receiveWindow.addAndGet(initialWindowSize/2);
                    connection.lock();
                    try {
                        WindowUpdateFrame frame = new WindowUpdateFrame(streamId, initialWindowSize/2);
                        frame.writeTo(connection.outputStream);
                        logger.log(Level.TRACE, () -> "sent stream window update, receive window "+receiveWindow.get()+" on stream "+streamId);
                    } finally {
                        connection.unlock();
                    }
                }
            }
        }
    }
}
