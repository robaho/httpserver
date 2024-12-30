package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;
import static robaho.net.httpserver.http2.Utils.convertToInt;

public class ResetStreamFrame extends BaseFrame {
    final public HTTP2ErrorCode errorCode;

	public ResetStreamFrame(FrameHeader header,HTTP2ErrorCode errorCode) {
		super(header);
        this.errorCode = errorCode;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
        Utils.writeBinary(os,errorCode.getValue(),4);
        os.flush();
    }

    static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception, Exception {
        if(body.length != 4) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }
        return new ResetStreamFrame(frameHeader,HTTP2ErrorCode.getEnum(convertToInt(body, 0)));
    }
}
