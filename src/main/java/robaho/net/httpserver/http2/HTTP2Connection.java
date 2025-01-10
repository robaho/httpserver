package robaho.net.httpserver.http2;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.HttpConnection;
import robaho.net.httpserver.OpenAddressIntMap;
import robaho.net.httpserver.OptimizedHeaders;
import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.hpack.HPackContext;
import robaho.net.httpserver.http2.hpack.HTTP2HeaderField;
import robaho.net.httpserver.http2.hpack.HeaderFields;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.ContinuationFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameSerializer;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.http2.frame.GoawayFrame;
import robaho.net.httpserver.http2.frame.HeadersFrame;
import robaho.net.httpserver.http2.frame.PingFrame;
import robaho.net.httpserver.http2.frame.ResetStreamFrame;
import robaho.net.httpserver.http2.frame.SettingIdentifier;
import robaho.net.httpserver.http2.frame.SettingParameter;
import robaho.net.httpserver.http2.frame.SettingsFrame;
import robaho.net.httpserver.http2.frame.SettingsMap;
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;

public class HTTP2Connection {

    static final String PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    static final String PARTIAL_PREFACE = "\r\nSM\r\n\r\n";

    final private InputStream inputStream;
    final OutputStream outputStream;

    private int lastSeenStreamId = 0;

    final OpenAddressIntMap<HTTP2Stream> http2Streams = new OpenAddressIntMap(16);

    private final SettingsMap remoteSettings = new SettingsMap();
    private final SettingsMap localSettings = new SettingsMap();

    private final StreamHandler handler;

    final HttpConnection httpConnection;

    final Logger logger;
    final HPackContext hpack = new HPackContext();

    final AtomicLong sendWindow = new AtomicLong(65535);
    final AtomicInteger receiveWindow = new AtomicInteger(65535);
    final AtomicInteger requestsInProgress = new AtomicInteger();

    final HTTP2Stats stats;

    private final int connectionWindowSize;

    private int maxConcurrentStreams = -1;
    private int highNumberStreams = 0;

    private final Lock lock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructor to instantiate HTTP2Connection object
     *
     * @param input HTTP2Client passes the ExBufferedInputStream
     * @param output
     */
    public HTTP2Connection(HttpConnection httpConnection, HTTP2Stats stats, InputStream input, OutputStream output, StreamHandler handler) {
        this.httpConnection = httpConnection;
        this.inputStream = input;
        this.outputStream = output;
        this.handler = handler;
        this.stats = stats;
        this.logger = System.getLogger("robaho.net.httpserver.http2");

        connectionWindowSize = ServerConfig.http2ConnectionWindowSize();

        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, ServerConfig.http2MaxFrameSize()));
        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, ServerConfig.http2InitialWindowSize()));

        if (ServerConfig.http2MaxConcurrentStreams() != -1) {
            localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, ServerConfig.http2MaxConcurrentStreams()));
        }
        logger.log(Level.DEBUG, "opened http2 connection " + httpConnection + ", max concurrent streams " + ServerConfig.http2MaxConcurrentStreams());
    }

    public void debug() {
        logger.log(Level.INFO,toString()+" receive window "+receiveWindow.get()+" send window "+sendWindow.get()+" in progress "+requestsInProgress.get());
        for(var stream : http2Streams.values()) {
            stream.debug();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    @Override
    public String toString() {
        return "{" + httpConnection + ", streams=" + http2Streams.size() + ", high " + highNumberStreams + "}";
    }

    public void close() {
        if(closed.compareAndSet(false,true)) {
            for (HTTP2Stream stream : http2Streams.values()) {
                stream.close();
            }
        }
    }

    public SettingsMap getRemoteSettings() {
        return remoteSettings;
    }

    public SettingsMap getLocalSettings() {
        return localSettings;
    }

    public void writeFrame(byte[] frame) throws IOException {
        writeFrame(List.of(frame));
    }

    /**
     * writes a frame that consists of multiple byte arrays. the method is
     * designed for low-volume frames where the overhead of creating a new byte
     * array is minimal. Otherwise, the data/frame should be written directly to
     * the output stream, e.g. see HTTP2Stream.Http2OutputStream
     */
    public void writeFrame(List<byte[]> partials) throws IOException {
        lock();
        try {
            logger.log(Level.TRACE, () -> "sending frame " + FrameHeader.debug(partials.get(0)));
            for (var frame : partials) {
                outputStream.write(frame);
            }
            outputStream.flush();
            stats.flushes.incrementAndGet();
        } finally {
            unlock();
        }
    }

    /**
     * Function to validate the PREFACE received on the input stream from the
     * remote system
     *
     * @return true if preface is valid
     * @throws IOException
     */
    public boolean hasProperPreface() throws IOException {
        String preface_match = (httpConnection.isSSL()) ? PREFACE : PARTIAL_PREFACE;
        byte[] preface = new byte[preface_match.length()];
        inputStream.read(preface);
        String prefaceStr = new String(preface, 0, preface.length);
        return prefaceStr.equals(preface_match);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void handle() throws Exception {
        try {
            processFrames();
        } catch (HTTP2Exception e) {
            logger.log(Level.DEBUG, "exception on http2 connection", e);
            sendGoAway(e.getErrorCode());
            throw e;
        }
    }

    private void processFrames() throws Exception {
        boolean inHeaders = false;
        int openStreamId = 0;

        List<byte[]> headerBlockFragments = new ArrayList();

        // main HTTP2
        while (!httpConnection.isClosed()) {
            BaseFrame frame = FrameSerializer.deserialize(inputStream);
            // System.out.println("Received frame: " + frame.getHeader());

            int streamId = frame.getHeader().getStreamIdentifier();
            if (streamId != 0 && streamId % 2 == 0) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "invalid stream id " + streamId + " on type " + frame.getHeader().getType());
            }

            // rfc7540 (section 6.5): SETTINGS frames always apply to a
            // connection, never a single stream.
            switch (frame.getHeader().getType()) {
                case SETTINGS:
                    if (frame.getHeader().getFlags().contains(FrameFlag.ACK)) {
                        if (ServerConfig.http2MaxConcurrentStreams() != -1) {
                            // cannot set this until it's been acked
                            maxConcurrentStreams = http2Streams.size() + ServerConfig.http2MaxConcurrentStreams();
                        }
                        continue;
                    } else {
                        updateRemoteSettings((SettingsFrame) frame);
                        sendSettingsAck();
                    }
                    continue;
                case GOAWAY:
                    GoawayFrame goaway = (GoawayFrame) frame;
                    if (goaway.errorCode == HTTP2ErrorCode.NO_ERROR) {
                        continue;
                    }
                    throw new IOException("received GOAWAY from remote " + goaway.errorCode);
                case PING:
                    if (!frame.getHeader().getFlags().contains(FrameFlag.ACK)) {
                        sendPingAck((PingFrame) frame);
                    }
                    continue;
                case WINDOW_UPDATE:
                    if (streamId == 0) {
                        int windowSizeIncrement = ((WindowUpdateFrame) frame).getWindowSizeIncrement();
                        sendWindow.addAndGet(windowSizeIncrement);
                        logger.log(Level.DEBUG, "received connection window update " + windowSizeIncrement + ", new size " + sendWindow.get());
                        if (sendWindow.get() > 2147483647) {
                            throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "maximum window size exceeded");
                        }
                        continue;
                    }
                    break;
                case NOT_IMPLEMENTED:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "NOT_IMPLEMENTED frame received while headers being received");
                    }
                    if (frame.getHeader().getStreamIdentifier() == 0) {
                        continue;
                    }
                    break;
                case DATA:
                    DataFrame dataFrame = (DataFrame) frame;
                    if (receiveWindow.addAndGet(-dataFrame.body.length) < connectionWindowSize/10) {
                        sendWindowUpdate();
                    }
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "DATA frame received while headers being received");
                    }
                    break;
                case HEADERS:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received on open stream");
                    }
                    if (streamId < lastSeenStreamId) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received out of order");
                    }
                    var stream = http2Streams.get(streamId);
                    if (stream != null) {
                        if (!stream.isOpen() || stream.isHalfClosed()) {
                            throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED, "HEADERS frame received on already closed stream");
                        } else {
                            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received on already established stream");
                        }
                    }
                    HeadersFrame headersFrame = (HeadersFrame) frame;
                    headerBlockFragments.add(headersFrame.getHeaderBlock());
                    if (!headersFrame.getHeader().getFlags().contains(FrameFlag.END_HEADERS)) {
                        inHeaders = true;
                        openStreamId = streamId;
                        continue;
                    }
                    break;
                case CONTINUATION:
                    if (inHeaders && frame.getHeader().getStreamIdentifier() != openStreamId) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received on open stream");
                    }
                    if (!inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "CONTINUATION frame received on closed stream");
                    }
                    ContinuationFrame continuationFrame = (ContinuationFrame) frame;
                    headerBlockFragments.add(continuationFrame.getHeaderBlock());
                    if (!continuationFrame.getHeader().getFlags().contains(FrameFlag.END_HEADERS)) {
                        continue;
                    }
                    break;
                case PRIORITY:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "PRIORITY frame received during headers receive");
                    }
                    if (streamId == 0) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "PRIORITY frame received on stream 0");
                    }
                    // stream priority is ignore for now
                    continue;
                case RST_STREAM:
                    ResetStreamFrame resetFrame = (ResetStreamFrame) frame;
                    if (resetFrame.errorCode == HTTP2ErrorCode.NO_ERROR) {
                        continue;
                    }
                    if (streamId == 0) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "RST_STREAM frame received on stream 0");
                    }
                    if (http2Streams.get(streamId) == null) {
                        if (streamId <= lastSeenStreamId) {
                            continue;
                        }
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "RST_STREAM frame received on non-existent stream");
                    }
                    break;
            }

            // we can only get here if we have a complete set of headers
            HTTP2Stream targetStream = http2Streams.get(streamId);

            if (targetStream != null) {
                // found existing stream
            } else if (lastSeenStreamId < streamId) {
                int currentSize = http2Streams.size();
                if (maxConcurrentStreams != -1 && currentSize >= maxConcurrentStreams) {
                    throw new HTTP2Exception(HTTP2ErrorCode.REFUSED_STREAM);
                }
                highNumberStreams = Math.max(highNumberStreams, currentSize);
                byte[] headerBlock = Utils.combineByteArrays(headerBlockFragments);
                HeaderFields fields = new HeaderFields();
                fields.addAll(hpack.decodeFieldSegments(headerBlock));
                // streamID is not present and has to be greater than all
                // the stream IDs present
                fields.validate();
                Headers requestHeaders = new OptimizedHeaders(fields.size()*2);
                for (HTTP2HeaderField field : fields) {
                    if (field.value == null) {
                        logger.log(Level.TRACE, () -> "ignoring null header for " + field.getName());
                    } else {
                        requestHeaders.add(field.normalizedName, field.value);
                    }
                }
                headerBlockFragments.clear();
                inHeaders = false;
                targetStream = new HTTP2Stream(streamId, this, requestHeaders, handler);
                http2Streams.put(streamId, targetStream);
                lastSeenStreamId = streamId;
            } else {
                if (streamId <= lastSeenStreamId) {
                    if(frame.getHeader().getType()==FrameType.WINDOW_UPDATE) {
                        // must accept window update even if stream is closed
                        logger.log(Level.TRACE,() -> "received WINDOW_UPDATE on closed stream "+streamId);
                        continue;
                    }
                    throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED, "frame "+frame.getHeader().getType()+ ", length "+ frame.getHeader().getLength()+", stream " + streamId + " is closed");
                }
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,  "frame "+frame.getHeader().getType()+", stream "+streamId+" not in order");
            }

            targetStream.processFrame(frame);
        }
    }

    public void updateRemoteSettings(SettingsFrame remoteSettingFrame) throws HTTP2Exception {
        logger.log(Level.TRACE, () -> "updating remote settings");

        for (SettingParameter parameter : remoteSettingFrame.getSettingParameters()) {
            long oldInitialWindowSize = remoteSettings.getOrDefault(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, SettingParameter.DEFAULT_INITIAL_WINDOWSIZE).value;
            if (parameter.identifier == SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE) {
                if (parameter.value > 2147483647) {
                    throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "Invalid value for SETTINGS_INITIAL_WINDOW_SIZE " + parameter.value);
                }
                logger.log(Level.DEBUG, () -> "received initial window size of " + parameter.value);
                for (var stream : http2Streams.values()) {
                    stream.sendWindow.addAndGet(parameter.value - oldInitialWindowSize);
                }
            }
            if (parameter.identifier == SettingIdentifier.SETTINGS_MAX_FRAME_SIZE) {
                logger.log(Level.DEBUG, () -> "received max frame size " + parameter.value);
            }
            getRemoteSettings().set(parameter);
        }
    }

    public void sendSettingsAck() throws IOException {
        try {
            byte[] frame = FrameHeader.encode(0, FrameType.SETTINGS, FlagSet.of(FrameFlag.ACK), 0);
            HTTP2Connection.this.writeFrame(frame);
        } finally {
            logger.log(Level.TRACE, () -> "sent Settings Ack");
        }
    }

    public void sendMySettings() throws IOException {
        try {
            FrameHeader header = new FrameHeader(0, FrameType.SETTINGS, FrameFlag.NONE, 0);
            SettingsFrame frame = new SettingsFrame(header);
            localSettings.forEach(setting -> frame.getSettingParameters().add(setting));
            HTTP2Connection.this.writeFrame(frame.encode());
        } finally {
            logger.log(Level.TRACE, () -> "sent My Settings");
        }
    }

    public void sendWindowUpdate() throws IOException {
        int current = receiveWindow.get();
        try {
            int increment = connectionWindowSize-current;
            receiveWindow.addAndGet(increment);
            WindowUpdateFrame frame = new WindowUpdateFrame(0, increment);
            HTTP2Connection.this.writeFrame(frame.encode());
        } finally {
            logger.log(Level.DEBUG, () -> "sent connection window update, previous "+current+", now "+ receiveWindow.get());
        }
    }

    InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) httpConnection.getRemoteAddress();
    }

    InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) httpConnection.getLocalAddress();
    }

    public void sendGoAway(HTTP2ErrorCode errorCode) throws IOException {
        lock();
        try {
            GoawayFrame frame = new GoawayFrame(errorCode, lastSeenStreamId);
            frame.writeTo(outputStream);
            outputStream.flush();
        } finally {
            unlock();
        }
        logger.log(Level.TRACE, () -> "Sent GoAway " + errorCode + ", last stream " + lastSeenStreamId);
    }

    public void sendResetStream(HTTP2ErrorCode errorCode, int streamId) throws IOException {
        ResetStreamFrame frame = new ResetStreamFrame(errorCode, streamId);
        HTTP2Connection.this.writeFrame(frame.encode());
        logger.log(Level.TRACE, () -> "Sent ResetStream " + errorCode);
    }

    public void sendPing() throws IOException {
        PingFrame frame = new PingFrame();
        HTTP2Connection.this.writeFrame(frame.encode());
        stats.pingsSent.incrementAndGet();
        logger.log(Level.TRACE, () -> "Sent Ping ");
    }

    private void sendPingAck(PingFrame ping) throws IOException {
        PingFrame frame = new PingFrame(ping);
        HTTP2Connection.this.writeFrame(frame.encode());
        logger.log(Level.TRACE, "Sent Ping Ack");
    }

    public static interface StreamHandler {

        void handleStream(HTTP2Stream stream, InputStream in, OutputStream out) throws IOException;

        Executor getExecutor();
    }

    public static void readFully(InputStream inputStream, byte[] buffer) throws IOException {
        if (buffer.length == 0) {
            return;
        }

        int bytesRead = 0;
        int offset = 0;
        int length = buffer.length;
        while (bytesRead != -1 && offset < length) {
            bytesRead = inputStream.read(buffer, offset, length - offset);
            if (bytesRead != -1) {
                offset += bytesRead;
            }
        }
        if (offset == 0) {
            throw new EOFException("end of stream detected");
        }
        if (offset < length) {
            throw new IOException("failed to read the full buffer");
        }
    }
}
