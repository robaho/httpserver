package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

class NotImplementedFrame extends BaseFrame {
    private byte[] body;
    public NotImplementedFrame(FrameHeader header,byte[] body) {
        super(header);
        this.body = body;
    }

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    static BaseFrame parse(byte[] body, FrameHeader frameHeader) {
        return new NotImplementedFrame(frameHeader,body);
    }
}