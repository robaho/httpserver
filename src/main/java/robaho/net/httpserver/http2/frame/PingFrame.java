package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.List;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;

public class PingFrame extends BaseFrame {
    public final byte[] body;

	public PingFrame(FrameHeader header, byte[] body) {
		super(header);
        this.body = body;
	}
    public PingFrame() {
        super(new FrameHeader(8,FrameType.PING,FrameFlag.NONE,0));
        body = new byte[8];
    }
    public PingFrame(PingFrame toBeAcked) {
        super(new FrameHeader(toBeAcked.body.length,FrameType.PING,FlagSet.of(FrameFlag.ACK),0));
        body = toBeAcked.body;
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
    public byte[] encode() {
        return Utils.combineByteArrays(List.of(getHeader().encode(),body));
    }
}
