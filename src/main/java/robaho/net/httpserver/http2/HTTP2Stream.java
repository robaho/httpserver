package robaho.net.httpserver.http2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.NoSyncBufferedOutputStream;
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

    private final AtomicLong sendWindow = new AtomicLong(65535);

    private final HTTP2Connection connection;
    private final Logger logger;
    private final OutputStream outputStream;
    private final Pipe pipe;
    private final HTTP2Connection.StreamHandler handler;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new Headers();
    private volatile boolean headersSent = false;

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
        if(setting!=null)
            sendWindow.addAndGet((int)(setting.value-65535));
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

        if(connection.http2Streams.remove(streamId)==null) {
            return;
        }

        try {
            pipe.close();
            outputStream.close();

            connection.lock();
            try {
                // if stream was already closed, then ResetFrame was received, so do not send end of stream
                FrameHeader header = new FrameHeader(0, FrameType.DATA, EnumSet.of(FrameFlag.END_STREAM), streamId);
                header.writeTo(connection.outputStream);
                connection.outputStream.flush();
            } finally {
                connection.unlock();
            }
            if(thread!=null)
                thread.interrupt();
        } catch (IOException e) {
            if(!connection.isClosed()) {
                logger.log(Level.WARNING,"IOException closing http2 stream",e);
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
            logger.log(Level.TRACE,"received data frame, length "+dataFrame.body.length+" on stream "+streamId);
            if(halfClosed) {
                throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED);
            }
            if(!streamOpen) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
            }
            pipe.getOutputStream().write(dataFrame.body);
            logger.log(Level.TRACE,"wrote data frame to pipe, length "+dataFrame.body.length+" on stream "+streamId);
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
        if(headersSent) return;
        connection.lock();
        try {
            if (headersSent) {
                return;
            }
            HPackContext.writeHeaderFrame(responseHeaders, connection.outputStream, streamId);
            connection.outputStream.flush();
        } finally {      
            headersSent = true;
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
        private final int streamId;
        private final OutputStream outputStream = connection.outputStream;
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
            // test outside of lock so other streams can progress
            while(sendWindow.get()<=0 && !connection.isClosed()) {
                logger.log(Level.TRACE,() -> "sending stream window exhausted, pausing on stream "+streamId);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            connection.lock();
            try {
                if (!headersSent) {
                    writeResponseHeaders();
                }
                while(len>0) {
                    int _len = Math.min(Math.min(len,max_frame_size),(int)Math.min(connection.sendWindow.get(),sendWindow.get()));
                    if(_len<=0) {
                        logger.log(Level.TRACE,() -> "sending connection window exhausted, pausing on stream "+streamId);
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                        if(connection.isClosed()) {
                            throw new IOException("connection closed");
                        }
                        continue;
                    }
                    FrameHeader header = new FrameHeader(_len, FrameType.DATA, EnumSet.noneOf(FrameFlag.class), streamId);
                    logger.log(Level.TRACE,() -> "sending data frame length "+_len+" on stream "+streamId);
                    header.writeTo(outputStream);
                    outputStream.write(b, off, _len);
                    off+=_len;
                    len-=_len;
                    connection.sendWindow.addAndGet(-_len);
                    sendWindow.addAndGet(-_len);
                }
            } finally {
                connection.unlock();
            }
        }
        @Override
        public void flush() throws IOException {
            connection.lock();
            try {
                outputStream.flush();
            } finally {
                connection.unlock();
            }
        }
        @Override
        public void close() throws IOException {
            if(closed) return;
            connection.lock();
            try {
                if(connection.isClosed()) {
                    if(!headersSent) {
                        logger.log(Level.WARNING,"stream connection is closed and headers not sent on stream "+streamId);
                    }
                    return;
                }
                if (!headersSent) {
                    writeResponseHeaders();
                }
                outputStream.flush();
            } finally {
                closed=true;
                connection.unlock();
                HTTP2Stream.this.close();
            }
        }
    }

    private static class Pipe {
        private final CustomPipedInputStream inputStream;
        private final CustomPipedOutputStream outputStream;

        public Pipe() {
            this.inputStream = new CustomPipedInputStream();
            this.outputStream = new CustomPipedOutputStream(this.inputStream);
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

        @Override
        public int read() throws IOException {
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
                int result = buffer[readPos++] & 0xFF;
                if (readPos == buffer.length) {
                    readPos = 0;
                }
                notFull.signal();
                return result;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int bytesRead = 0;
            while (bytesRead < len) {
                int byteRead = read();
                if (byteRead == -1) {
                    return bytesRead == 0 ? -1 : bytesRead;
                }
                b[off + bytesRead++] = (byte) byteRead;
            }
            return bytesRead;
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
