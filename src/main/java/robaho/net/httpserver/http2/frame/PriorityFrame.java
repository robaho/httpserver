package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;

public class PriorityFrame extends BaseFrame {
    public int streamDependency;
    public int weight;
    public boolean exclusive;

	public PriorityFrame(FrameHeader header) {
		super(header);
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Exception {
        var frame = new PriorityFrame(frameHeader);
        if(body.length != 5) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }
        var tmp = Utils.convertToInt(body, 0, 4);
        frame.exclusive = (tmp & 0x80000000) != 0;
        frame.streamDependency = tmp & 0x7FFFFFFF;
        if(frame.streamDependency == frameHeader.getStreamIdentifier()) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
        frame.weight = (body[4] & 0xFF) + 1;
        return frame;
    }

    public byte[] encode() {
        byte[] buffer = new byte[5];
        Utils.convertToBinary(buffer, 0, streamDependency);
        buffer[4] = (byte)((weight-1) & 0xFF);
        return Utils.combineByteArrays(List.of(getHeader().encode(),buffer));
    }
}
