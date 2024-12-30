package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;

public class WindowUpdateFrame extends BaseFrame {
	
	private int windowSizeIncrement;

	public WindowUpdateFrame(FrameHeader header) {
		super(header);
	}
	
	public int getWindowSizeIncrement()
	{
		return windowSizeIncrement;
	}

	public static WindowUpdateFrame parse(byte[] frameBody, FrameHeader header) throws HTTP2Exception {

        if(frameBody.length!=4) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }

        WindowUpdateFrame frame = new WindowUpdateFrame(header);
        try {
            frame.windowSizeIncrement = Utils.convertToInt(frameBody, 0);
        } catch (Exception e) {
            throw new HTTP2Exception(HTTP2ErrorCode.INTERNAL_ERROR);
        }
        if(frame.windowSizeIncrement==0) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"window size increment == 0");
        }
        return frame;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }
}
