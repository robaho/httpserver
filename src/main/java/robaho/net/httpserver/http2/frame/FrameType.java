package robaho.net.httpserver.http2.frame;

/**
 * [rfc7540: Section 11.2] A enumeration of all the Frame types introduced in
 * HTTP2 This specification defines a number of frame types, each identified by
 * a unique 8-bit type code. Each frame type serves a distinct purpose in the
 * establishment and management either of the connection as a whole or of
 * individual streams.
 */
public enum FrameType {

	DATA((byte) 0x0), HEADERS((byte) 0x1), PRIORITY((byte) 0x2), RST_STREAM((byte) 0x3), SETTINGS(
			(byte) 0x4), PUSH_PROMISE((byte) 0x5), PING((byte) 0x6), GOAWAY(
					(byte) 0x7), WINDOW_UPDATE((byte) 0x8), CONTINUATION((byte) 0x9), NOT_IMPLEMENTED((byte) 0xA);

	byte value;

	FrameType(byte value) {
		this.value = value;
	}

	public byte getValue() {
		return value;
	}

	public static FrameType getEnum(int value) {
		for (FrameType e : FrameType.values()) {
			if (e.getValue() == value)
				return e;
		}
		return FrameType.NOT_IMPLEMENTED;
	}
}
