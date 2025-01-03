package robaho.net.httpserver.http2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.NoSyncBufferedInputStream;
import robaho.net.httpserver.NoSyncBufferedOutputStream;
import robaho.net.httpserver.OptimizedHeaders;
import robaho.net.httpserver.http2.hpack.HPackContext;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.http2.frame.ResetStreamFrame;
import robaho.net.httpserver.http2.frame.SettingIdentifier;
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;

public class HTTP2Stream {

    private final int streamId;

    // needs to be accessible for connection to adjust based on SettingsFrame
    final AtomicLong sendWindow = new AtomicLong(65535);

    private final HTTP2Connection connection;
    private final Logger logger;
    private final OutputStream outputStream;
    private final Pipe pipe;
    private final HTTP2Connection.StreamHandler handler;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new OptimizedHeaders(16);
    private final AtomicBoolean headersSent = new AtomicBoolean(false);

    private volatile Thread thread;
    private volatile boolean streamOpen = true;
    private volatile boolean halfClosed = false;

    private long dataInSize = 0;

    public HTTP2Stream(int streamId, HTTP2Connection connection, Headers requestHeaders, HTTP2Connection.StreamHandler handler) throws IOException {
        this.streamId = streamId;
        this.connection = connection;
        this.logger = connection.logger;
        this.requestHeaders = requestHeaders;
        this.handler = handler;
        this.pipe = new Pipe();
        this.outputStream = new NoSyncBufferedOutputStream(new Http2OutputStream(streamId));
        var setting = connection.getRemoteSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            sendWindow.addAndGet((int)(setting.value-65535));
        }
        logger.log(Level.TRACE,() -> "new stream, window size "+sendWindow.get()+" on stream "+streamId);
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
        return connection.toString()+", stream "+streamId;
    }

    public boolean isOpen() {
        return streamOpen;
    }
    public boolean isHalfClosed() {
        return halfClosed;
    }

    public void close() {
        streamOpen = false;
        halfClosed = true;

        if(connection.http2Streams.put(streamId,null)==null) {
            return;
        }

        try {
            pipe.close();
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
            pipe.getOutputStream().write(dataFrame.body);
            dataInSize += dataFrame.body.length;
            if (dataFrame.getHeader().getFlags().contains(FrameFlag.END_STREAM)) {
                if(requestHeaders.containsKey("Content-length")) {
                    if(dataInSize!=Long.parseLong(requestHeaders.getFirst("Content-length"))) {
                        connection.sendResetStream(HTTP2ErrorCode.PROTOCOL_ERROR, streamId);
                        close();
                        break;
                    }
                }
                pipe.closeOutput();
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

    private void performRequest(boolean halfClosed) throws IOException {
        connection.httpConnection.requestCount.incrementAndGet();
        connection.requestsInProgress.incrementAndGet();

        InputStream in = halfClosed ? InputStream.nullInputStream() : pipe.getInputStream();
        
        if(halfClosed) {
            this.halfClosed = true;
            pipe.closeOutput();
        }

        handler.getExecutor().execute(() -> {
                thread = Thread.currentThread();
                try {
                    handler.handleStream(this,in,outputStream);
                } catch (IOException ex) {
                    close();
                }
        });
    }
    public void writeResponseHeaders() throws IOException {
        if(!headersSent.compareAndSet(false,true))
            return;
        connection.lock();
        try {
            HPackContext.writeHeaderFrame(responseHeaders,connection.outputStream,streamId);
        } finally {
            connection.unlock();
        }
    }
    public InetSocketAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    class Http2OutputStream extends OutputStream {
        private static final EnumSet<FrameFlag> END_STREAM = EnumSet.of(FrameFlag.END_STREAM);

        private final int streamId;
        private final int max_frame_size;
        private boolean closed;
        private long pauses = 0;

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
            // test outside of lock so other streams can progress
            while(sendWindow.get()<=0 && !connection.isClosed()) {
                pauses++;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            writeResponseHeaders();
            while(len>0) {
                int _len = Math.min(Math.min(len,max_frame_size),(int)Math.min(connection.sendWindow.get(),sendWindow.get()));
                if(_len<=0) {
                    pauses++;
                    connection.lock();
                    try {
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
            if(pauses>0)
                logger.log(Level.INFO,() -> "sending stream window exhausted "+pauses+" on stream "+streamId);
            try {
                if(connection.isClosed()) {
                    if(!headersSent.get()) {
                        logger.log(Level.WARNING,"stream connection is closed and headers not sent on stream "+streamId);
                    }
                    return;
                }
                connection.requestsInProgress.decrementAndGet();
                writeResponseHeaders();
                connection.lock();
                try {
                    FrameHeader.writeTo(connection.outputStream, 0, FrameType.DATA, END_STREAM, streamId);
                    if(connection.requestsInProgress.get()<=0) {
                        connection.outputStream.flush();
                    }
                } finally {
                    connection.unlock();
                }
                // connection.enqueue(header);
            } finally {
                closed=true;
                HTTP2Stream.this.close();
            }
        }
    }

    // custom Pipe implementation since JDK version still uses synchronized methods which are not optimal for virtual threads
    private static class Pipe {
        private final InputStream inputStream;
        private final CustomPipedOutputStream outputStream;

        public Pipe() {
            var pipeIn = new CustomPipedInputStream();
            this.inputStream = new NoSyncBufferedInputStream(pipeIn);
            this.outputStream = new CustomPipedOutputStream(pipeIn);
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        public OutputStream getOutputStream() {
            return outputStream;
        }

        public void close() throws IOException {
            inputStream.close();
            outputStream.close();
        }

        public void closeOutput() throws IOException {
            outputStream.close();
        }
    }

    private static class CustomPipedInputStream extends InputStream {
        private final byte[] buffer = new byte[1024];
        private int readPos = 0;
        private int writePos = 0;
        private boolean closed = false;
        private final Lock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final Condition notFull = lock.newCondition();

        private final byte[] single = new byte[1];

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            lock.lock();
            try {
                while (readPos == writePos && !closed) {
                    try {
                        notEmpty.await();
                    } catch (InterruptedException e) {
                        throw new IOException("Interrupted while waiting for data", e);
                    }
                }
                if (closed && readPos == writePos) {
                    return -1;
                }

                int available;
                if (readPos <= writePos) {
                    available = writePos - readPos;
                } else {
                    available = buffer.length - readPos;
                }

                int bytesToRead = Math.min(len, available);
                System.arraycopy(buffer, readPos, b, off, bytesToRead);
                readPos += bytesToRead;
                if (readPos == buffer.length) {
                    readPos = 0;
                }
                notFull.signal();
                return bytesToRead;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            lock.lock();
            try {
                closed = true;
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private static class CustomPipedOutputStream extends OutputStream {
        private final CustomPipedInputStream inputStream;
        private boolean closed = false;

        public CustomPipedOutputStream(CustomPipedInputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            inputStream.lock.lock();
            try {
                while (len > 0) {
                    while ((inputStream.writePos == inputStream.readPos - 1 || 
                           (inputStream.writePos == inputStream.buffer.length - 1 && inputStream.readPos == 0)) 
                           && !closed) {
                        try {
                            inputStream.notFull.await();
                        } catch (InterruptedException e) {
                            throw new IOException("Interrupted while waiting for buffer space", e);
                        }
                    }
                    if (closed) {
                        throw new IOException("Stream closed");
                    }
                    int space = inputStream.readPos <= inputStream.writePos ? 
                               inputStream.buffer.length - inputStream.writePos : 
                               inputStream.readPos - inputStream.writePos - 1;
                    int bytesToWrite = Math.min(len, space);
                    System.arraycopy(b, off, inputStream.buffer, inputStream.writePos, bytesToWrite);
                    inputStream.writePos += bytesToWrite;
                    if (inputStream.writePos == inputStream.buffer.length) {
                        inputStream.writePos = 0;
                    }
                    off += bytesToWrite;
                    len -= bytesToWrite;
                    inputStream.notEmpty.signal();
                }
            } finally {
                inputStream.lock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            inputStream.lock.lock();
            try {
                closed = true;
                inputStream.close();
            } finally {
                inputStream.lock.unlock();
            }
        }
    }
}
