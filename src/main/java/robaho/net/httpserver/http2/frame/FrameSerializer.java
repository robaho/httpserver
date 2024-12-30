package robaho.net.httpserver.http2.frame;

import java.io.InputStream;

import robaho.net.httpserver.http2.HTTP2Connection;
import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class FrameSerializer {

	public static BaseFrame deserialize(InputStream inputStream) throws Exception {

		BaseFrame baseFrame = null;
		byte[] tmpBuffer = new byte[9];

        HTTP2Connection.readFully(inputStream, tmpBuffer);
		FrameHeader frameHeader = FrameHeader.Parse(tmpBuffer);

        byte[] body = new byte[frameHeader.getLength()];
        HTTP2Connection.readFully(inputStream, body);

        if(frameHeader.getLength() > 16384) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }

		switch (frameHeader.getType()) {
		case HEADERS:
			baseFrame = HeadersFrame.parse(body, frameHeader);
			break;
		case CONTINUATION:
			baseFrame = ContinuationFrame.parse(body,frameHeader);
			break;
		case DATA:
			baseFrame = DataFrame.parse(body,frameHeader);
			break;
		case GOAWAY:
			baseFrame = GoawayFrame.parse(body,frameHeader);
			break;
		case PING:
			baseFrame = PingFrame.parse(body,frameHeader);
			break;
		case PRIORITY:
			baseFrame = PriorityFrame.parse(body,frameHeader);
			break;
		case PUSH_PROMISE:
			baseFrame = PushPromiseFrame.parse(body,frameHeader);
			break;
		case RST_STREAM:
			baseFrame = ResetStreamFrame.parse(body,frameHeader);
			break;
		case SETTINGS:
			baseFrame = SettingsFrame.parse(body, frameHeader);
			break;
		case WINDOW_UPDATE:
			baseFrame = WindowUpdateFrame.parse(body, frameHeader);
			break;
		default:
            baseFrame = NotImplementedFrame.parse(body,frameHeader);
			break;
		}

		return baseFrame;
	}
}
