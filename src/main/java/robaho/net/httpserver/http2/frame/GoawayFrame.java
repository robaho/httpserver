package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;

public class GoawayFrame extends BaseFrame {
    public final HTTP2ErrorCode errorCode;
    public final int lastSeenStream;

	public GoawayFrame(FrameHeader header,HTTP2ErrorCode errorCode) {
		this(header,errorCode,0);
	}

	public GoawayFrame(FrameHeader header,HTTP2ErrorCode errorCode,int lastSeenStream) {
		super(header);
        this.errorCode = errorCode;
        this.lastSeenStream = lastSeenStream;
	}

	public GoawayFrame(HTTP2ErrorCode errorCode,int lastSeenStream) {
		this(new FrameHeader(8,FrameType.GOAWAY,FrameFlag.NONE,0),errorCode,lastSeenStream);
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
        Utils.writeBinary(os, lastSeenStream);
        Utils.writeBinary(os, errorCode.getValue());
    }

    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception {
        if(frameHeader.getStreamIdentifier()!=0) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
        if(body.length<8) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }
        try {
            var errorCode = Utils.convertToInt(body, 4, 4);
            return new GoawayFrame(frameHeader,HTTP2ErrorCode.getEnum(errorCode));
        } catch (Exception e) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
    }
}
