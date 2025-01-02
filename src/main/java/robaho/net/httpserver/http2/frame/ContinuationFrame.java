package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;

public class ContinuationFrame extends BaseFrame {
    private final byte[] body;

	public ContinuationFrame(FrameHeader header,byte[] body) {
		super(header);
        this.body = body;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception {
        return new ContinuationFrame(frameHeader,body);
    }
    public byte[] getHeaderBlock() {
        return body;
    }
    
    public byte[] encode() {
        return Utils.combineByteArrays(List.of(getHeader().encode(),body));
    }

}
