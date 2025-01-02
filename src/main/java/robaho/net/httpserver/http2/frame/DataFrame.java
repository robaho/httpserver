package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class DataFrame extends BaseFrame {

    public final byte[] body;
	public DataFrame(FrameHeader header,byte[] body) {
		super(header);
        this.body = body;
	}
    @Override
    public void writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(body);
    }   
    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception {
        int index = 0;
        int padding = 0;
        if(frameHeader.getFlags().contains(FrameFlag.PADDED)) {
            padding = (body[index] & 0xFF)+1;
            if(padding > body.length) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"padding exceeds frame size");
            }
        }
        if(padding>0) {
            return new DataFrame(frameHeader, Arrays.copyOfRange(body,index,body.length-padding));
        } else {
            return new DataFrame(frameHeader, body);
        }
    }
    public byte[] encode() {
        throw new UnsupportedOperationException();
    }
}
