package robaho.net.httpserver.http2;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.HttpConnection;
import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.hpack.HPackContext;
import robaho.net.httpserver.http2.hpack.HTTP2HeaderField;
import robaho.net.httpserver.http2.hpack.HeaderFields;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.ContinuationFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.FrameFlag;
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
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;

public class HTTP2Connection {

	static final String PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
	static final String PARTIAL_PREFACE = "\r\nSM\r\n\r\n";

	final private InputStream inputStream;
	final OutputStream outputStream;

	private int lastSeenStreamId = 0;

	final ConcurrentMap<Integer, HTTP2Stream> http2Streams = new ConcurrentHashMap<>();
    private final Set<Integer> previousStreams = new HashSet(); 

	private final HashMap<SettingIdentifier, SettingParameter> remoteSettings = new HashMap<>();
	private final HashMap<SettingIdentifier, SettingParameter> localSettings = new HashMap<>();

    private final StreamHandler handler;

    private final Lock lock = new ReentrantLock();

    final HttpConnection httpConnection;

    final Logger logger;
    final HPackContext hpack = new HPackContext();

    final AtomicLong sendWindow = new AtomicLong(65535);
    final AtomicInteger receiveWindow = new AtomicInteger(65535);

    private int maxConcurrentStreams = -1;
    private int highNumberStreams = 0;

	/**
	 * Constructor to instantiate HTTP2Connection object
	 * 
	 * @param input
	 *            HTTP2Client passes the ExBufferedInputStream
	 * @param output
	 */
	public HTTP2Connection(HttpConnection httpConnection,InputStream input, OutputStream output, StreamHandler handler) {
        this.httpConnection = httpConnection;
		this.inputStream = input;
		this.outputStream = output;
        this.handler = handler;
        this.logger = System.getLogger("robaho.net.httpserver.http2");

        localSettings.put(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE,new SettingParameter(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE,ServerConfig.http2MaxFrameSize()));
        localSettings.put(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE,new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE,ServerConfig.http2InitialWindowSize()));
        if(ServerConfig.http2MaxConcurrentStreams()!=-1) {
            localSettings.put(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS,new SettingParameter(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS,ServerConfig.http2MaxConcurrentStreams()));
        }
        logger.log(Level.DEBUG,"opened http2 connection "+httpConnection+", max concurrent streams "+ServerConfig.http2MaxConcurrentStreams());
	}

    @Override
    public String toString() {
        return "{" + httpConnection +", streams=" + http2Streams.size()+", high "+highNumberStreams+"}";
    }

    public void close() {
        for (HTTP2Stream stream : http2Streams.values()) {
            stream.close();
        }
    }

	public HashMap<SettingIdentifier, SettingParameter> getRemoteSettings() {
		return remoteSettings;
	}

	public HashMap<SettingIdentifier, SettingParameter> getLocalSettings() {
		return localSettings;
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

    void lock() {
        lock.lock();
    }
    void unlock() {
        lock.unlock();
    }

    public boolean isClosed() {
        return httpConnection.isClosed();
    }

	public void handle() throws Exception {
        try {
            processFrames();
        } catch (HTTP2Exception e) {
            logger.log(Level.DEBUG,"exception on http2 connection",e);
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
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"invalid stream id " + streamId+ " on type " + frame.getHeader().getType());
            }

			// rfc7540 (section 6.5): SETTINGS frames always apply to a
			// connection, never a single stream.

            switch (frame.getHeader().getType()) {
                case SETTINGS:
                    if (frame.getHeader().getFlags().contains(FrameFlag.ACK)) {
                        if(ServerConfig.http2MaxConcurrentStreams()!=-1) {
                            // cannot set this until it's been acked
                            maxConcurrentStreams = http2Streams.size()+ServerConfig.http2MaxConcurrentStreams();
                        }
                        continue;
                    } else {
                        updateRemoteSettings((SettingsFrame) frame);
                        sendSettingsAck();
                    }
                    continue;
                case GOAWAY:
                    GoawayFrame goaway = (GoawayFrame) frame;
                    if(goaway.errorCode==HTTP2ErrorCode.NO_ERROR) {
                        continue;
                    }
                    throw new IOException("received GOAWAY from remote "+goaway.errorCode);
                case PING:
                    if (!frame.getHeader().getFlags().contains(FrameFlag.ACK)) {
                        sendPingAck((PingFrame) frame);
                    }
                    continue;
                case WINDOW_UPDATE:
                    if (frame.getHeader().getStreamIdentifier()== 0) {
                        int windowSizeIncrement = ((WindowUpdateFrame)frame).getWindowSizeIncrement();
                        sendWindow.addAndGet(windowSizeIncrement);
                        logger.log(Level.DEBUG,"received connection window update "+windowSizeIncrement+", new size "+sendWindow.get());
                        if(sendWindow.get() > 2147483647) {
                            throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR,"maximum window size exceeded");
                        }
                        continue;
                    }
                    break;
                case NOT_IMPLEMENTED:
                    if(inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"NOT_IMPLEMENTED frame received while headers being received");
                    }
                    if (frame.getHeader().getStreamIdentifier() == 0) {
                        continue;
                    }
                    break;
                case DATA:
                    DataFrame dataFrame = (DataFrame) frame;
                    if(receiveWindow.addAndGet(-dataFrame.body.length)<=0) {
                        sendWindowUpdate();
                    }
                    if(inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"DATA frame received while headers being received");
                    }
                    break;
                case HEADERS:
                    if(inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"HEADERS frame received on open stream");
                    }
                    if(streamId < lastSeenStreamId) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"HEADERS frame received out of order");
                    }
                    var stream = http2Streams.get(streamId);
                    if(stream!=null) {
                        if(!stream.isOpen() || stream.isHalfClosed()) {
                            throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED,"HEADERS frame received on already closed stream");
                        } else {
                            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"HEADERS frame received on already established stream");
                        }
                    }
                    HeadersFrame headersFrame = (HeadersFrame) frame;
                    headerBlockFragments.add(headersFrame.getHeaderBlock());
                    if(!headersFrame.getHeader().getFlags().contains(FrameFlag.END_HEADERS)) {
                        inHeaders = true;
                        openStreamId = streamId;
                        continue;
                    }
                    break;
                case CONTINUATION:
                    if(inHeaders && frame.getHeader().getStreamIdentifier() != openStreamId) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"HEADERS frame received on open stream");
                    }
                    if(!inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"CONTINUATION frame received on closed stream");
                    }   
                    ContinuationFrame continuationFrame = (ContinuationFrame) frame;
                    headerBlockFragments.add(continuationFrame.getHeaderBlock());
                    if(!continuationFrame.getHeader().getFlags().contains(FrameFlag.END_HEADERS)) {
                        continue;
                    }
                    break;
                case PRIORITY:
                    if(inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"PRIORITY frame received during headers receive");
                    }
                    if(streamId == 0) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"PRIORITY frame received on stream 0");
                    }
                    // stream priority is ignore for now
                    continue;
                case RST_STREAM:
                    ResetStreamFrame resetFrame = (ResetStreamFrame) frame;
                    if(resetFrame.errorCode==HTTP2ErrorCode.NO_ERROR) {
                        continue;
                    }
                    if(!http2Streams.containsKey(streamId)) {
                        if(previousStreams.contains(streamId)) continue;
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"RST_STREAM frame received on non-existent stream");
                    }
                    break;
            }

            // we can only get here if we have a complete set of headers

            HTTP2Stream targetStream = null;

            // check if streamID is already present in the hashmap, i.e., a
            // already established stream

            if (http2Streams.containsKey(streamId)) {
                targetStream = http2Streams.get(streamId);
            } else if (lastSeenStreamId < streamId) {
                int currentSize = http2Streams.size();
                if(maxConcurrentStreams!=-1 && currentSize>=maxConcurrentStreams) {
                    throw new HTTP2Exception(HTTP2ErrorCode.REFUSED_STREAM);
                }
                highNumberStreams = Math.max(highNumberStreams,currentSize);
                byte[] headerBlock = Utils.combineByteArrays(headerBlockFragments);
                HeaderFields fields = new HeaderFields();
                fields.addAll(hpack.decodeFieldSegments(headerBlock));
                // streamID is not present and has to be greater than all
                // the stream IDs present
                fields.validate();
                Headers requestHeaders = new Headers();
                for(HTTP2HeaderField field : fields) {
                    if(field.value==null) {
                        logger.log(Level.TRACE,() -> "ignoring null header for "+field.getName());
                    } else {
                        requestHeaders.add(field.name,field.value);
                    }
                }
                headerBlockFragments.clear();
                inHeaders = false;
                targetStream = new HTTP2Stream(streamId, this, requestHeaders, handler);
                http2Streams.put(streamId, targetStream);
                previousStreams.add(streamId);
                lastSeenStreamId = streamId;
            } else {
                if(previousStreams.contains(streamId)) {
                    throw new HTTP2Exception(HTTP2ErrorCode.STREAM_CLOSED,"stream "+streamId+" is closed");
                }
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"Stream ID not in order");
            }

            if (targetStream != null) {
                targetStream.processFrame(frame);
            }
		}
	}

	public void updateRemoteSettings(SettingsFrame remoteSettingFrame) throws HTTP2Exception {
        for (SettingParameter parameter : remoteSettingFrame.getSettingParameters()) {
            if(parameter.identifier == SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE) {
                if(parameter.value > 2147483647) {
                    throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR,"Invalid value for SETTINGS_INITIAL_WINDOW_SIZE "+parameter.value);
                }
                logger.log(Level.DEBUG,() -> "received initial window size of "+parameter.value);
            }
            if(parameter.identifier == SettingIdentifier.SETTINGS_MAX_FRAME_SIZE) {
                logger.log(Level.DEBUG,() -> "received max frame size "+parameter.value);
            }
            getRemoteSettings().put(parameter.identifier, parameter);
        }
	}

	public void sendSettingsAck() throws IOException {
        lock();
        try {
            SettingsFrame frame = new SettingsFrame();
            frame.writeTo(outputStream);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,() -> "Sent Settings Ack");
        }
	}
	public void sendMySettings() throws IOException {
        lock();
        try {
            FrameHeader header = new FrameHeader(0, FrameType.SETTINGS, EnumSet.noneOf(FrameFlag.class), 0);
            SettingsFrame frame = new SettingsFrame(header);
            for(var setting : localSettings.values()) {
                frame.getSettingParameters().add(setting);
            }
            frame.writeTo(outputStream);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,() -> "Sent My Settings");
        }
	}
	public void sendWindowUpdate() throws IOException {
        lock();
        try {
            receiveWindow.addAndGet(65535);
            FrameHeader header = new FrameHeader(4, FrameType.WINDOW_UPDATE, EnumSet.noneOf(FrameFlag.class), 0);
            WindowUpdateFrame frame = new WindowUpdateFrame(header);
            frame.writeTo(outputStream);
            Utils.writeBinary(outputStream, 65535, 4);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,() -> "Sent My Settings");
        }
	}

    InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) httpConnection.getRemoteAddress();
    }
    InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) httpConnection.getLocalAddress();
    }

    public void sendGoAway(HTTP2ErrorCode errorCode) throws IOException {
        FrameHeader header = new FrameHeader(8, FrameType.GOAWAY, EnumSet.noneOf(FrameFlag.class), 0);
        lock();
        try {
            header.writeTo(outputStream);
            Utils.writeBinary(outputStream, lastSeenStreamId, 4);
            Utils.writeBinary(outputStream, errorCode.value, 4);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,() -> "Sent GoAway "+errorCode);
        }
    }
    public void sendResetStream(HTTP2ErrorCode errorCode,int streamId) throws IOException {
        FrameHeader header = new FrameHeader(4, FrameType.RST_STREAM, EnumSet.noneOf(FrameFlag.class), streamId);
        lock();
        try {
            header.writeTo(outputStream);
            Utils.writeBinary(outputStream, errorCode.value, 4);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,() -> "Sent Reset Stream "+streamId);
        }
    }

    public void sendPing() throws IOException {
        FrameHeader header = new FrameHeader(8, FrameType.PING, EnumSet.noneOf(FrameFlag.class), 0);
        lock();
        try {
            header.writeTo(outputStream);
            Utils.writeBinary(outputStream, 0, 4);
            Utils.writeBinary(outputStream, 0, 4);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,"Sent Ping");
        }
    }
    private void sendPingAck(PingFrame frame) throws IOException {
        lock();
        try {
            FrameHeader header = new FrameHeader(frame.body.length, FrameType.PING, EnumSet.of(FrameFlag.ACK), 0);
            header.writeTo(outputStream);
            outputStream.write(frame.body);
            outputStream.flush();
        } finally {
            unlock();
            logger.log(Level.TRACE,"Sent Ping Ack");
        }
    }

    public static interface StreamHandler {
        void handleStream(HTTP2Stream stream,InputStream in,OutputStream out) throws IOException;
        Executor getExecutor();
    }

    public static void readFully(InputStream inputStream, byte[] buffer) throws IOException {
        if(buffer.length==0) return;
        
        int bytesRead = 0;
        int offset = 0;
        int length = buffer.length;
        while (bytesRead != -1 && offset < length) {
            bytesRead = inputStream.read(buffer, offset, length - offset);
            if (bytesRead != -1) {
                offset += bytesRead;
            }
        }
        if (offset==0) {
            throw new EOFException("end of stream detected");
        }
        if (offset < length) {
            throw new IOException("failed to read the full buffer");
        }
    }
}
