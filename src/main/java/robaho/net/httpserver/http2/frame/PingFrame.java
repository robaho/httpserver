package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class PingFrame extends BaseFrame {
    public final byte[] body;

	public PingFrame(FrameHeader header, byte[] body) {
		super(header);
        this.body = body;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception {
        if (frameHeader.getStreamIdentifier() != 0) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
        if(body.length!=8) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }
        return new PingFrame(frameHeader, body);
    }
}
