package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;

public class GoawayFrame extends BaseFrame {
    public final HTTP2ErrorCode errorCode;

	public GoawayFrame(FrameHeader header,HTTP2ErrorCode errorCode) {
		super(header);
        this.errorCode = errorCode;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception {
        if(frameHeader.getStreamIdentifier()!=0) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
        if(body.length<8) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }
        try {
            var errorCode = Utils.convertToInt(body, 4);
            return new GoawayFrame(frameHeader,HTTP2ErrorCode.getEnum(errorCode));
        } catch (Exception e) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
    }
}
