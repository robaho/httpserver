package robaho.net.httpserver.http2.frame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public abstract class BaseFrame {
	
	private FrameHeader header;
		
	public BaseFrame(FrameHeader header) {
		this.header = header;
	}

	public FrameHeader getHeader()
	{
		return header;
	}
	
	public void setHeader(FrameHeader header)
	{
		this.header = header;
	}
	
	public abstract void writeTo(OutputStream os) throws IOException;
    
    public byte[] encode() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            writeTo(bos);
        } catch (IOException ignore) {
        }
        return bos.toByteArray();
    }
}
