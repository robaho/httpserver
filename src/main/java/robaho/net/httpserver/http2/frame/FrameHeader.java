package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;

import robaho.net.httpserver.http2.Utils;

/**
 * Create a frame header object
 */
public class FrameHeader {
	
	private final int length;
	private final FrameType type;
	private final EnumSet<FrameFlag> flags;
	private final int streamIdentifier;

	/**
	 *            24-bit unsigned integer value that specifies length of the
	 *            frame
	 */
	public int getLength() {
		return length;
	}

	/**
	 *            defined as an enum FrameType, it identifies the type of the
	 *            frame
	 */
	public FrameType getType() {
		return type;
	}

	/**
	 *            defined as an EnumSet&lt;FrameFlag&gt;, it identifies flags associated with a
	 *            particular frame
	 */
	public EnumSet<FrameFlag> getFlags() {
		return flags;
	}

	/**
	 *            31-bit unsigned integer uniquely identifies a frame
	 */
	public int getStreamIdentifier() {
		return streamIdentifier;
	}

    public FrameHeader(int length, FrameType type, EnumSet<FrameFlag> flags, int streamIdentifier) {
		this.length = length;
		this.type = type;
		this.flags = flags;
		this.streamIdentifier = streamIdentifier;
	}

    @Override
    public String toString() {
        return "FrameHeader{" +
                "length=" + length +
                ", type=" + type +
                ", flags=" + flags +
                ", streamIdentifier=" + streamIdentifier +
                '}';
    }

	/**
	 * Parse the 9 bytes frame header to determine length, type, flags and the stream identifier
	 * @param tmpBuffer 9 bytes frame header
	 * @return
	 * @throws Exception 
	 */
    // TODO validate the frame size, frame number, and number of frames in session based on the SETTINGS frame
	public static FrameHeader Parse(byte[] tmpBuffer) throws Exception {
		FrameHeader frameHeader = null;

		FrameType type = null;
		EnumSet<FrameFlag> flag = null;
		int streamIdentifier = 0;
		int length = 0;
		int readIndex = 0;

		length = Utils.convertToInt(tmpBuffer, readIndex, 3);
		readIndex += 3;

		type = FrameType.getEnum(tmpBuffer[readIndex]); 
		readIndex++;

        flag = FrameFlag.getEnumSet(tmpBuffer[readIndex], type);
        readIndex++;
	
		streamIdentifier = Utils.convertToInt(tmpBuffer, readIndex);
		readIndex += 4;
        
        streamIdentifier = streamIdentifier & 0x7FFFFFFF;

		frameHeader = new FrameHeader(length, type, flag, streamIdentifier);

		return frameHeader;
	}
	
	public void writeTo(OutputStream os) throws IOException {
        Utils.writeBinary(os,this.length,3);
        os.write(this.getType().value & 0xFF);
        os.write(FrameFlag.getValue(this.getFlags()) & 0xFF);
        Utils.writeBinary(os,this.streamIdentifier);
    }
}
