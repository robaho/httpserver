package robaho.net.httpserver.http2.frame;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import robaho.net.httpserver.http2.HTTP2Exception;

/**
 * An enumeration to define all the Flags that can be attached to a frame
 */
public enum FrameFlag {

	END_STREAM((byte)0x1), 
	ACK((byte)0x1), 
	END_HEADERS((byte)0x4), 
	PADDED((byte)0x8), 
	PRIORITY((byte)0x20);

	private final byte value;

	FrameFlag(byte value) {
		this.value = value;
	}

    private static final FrameFlag[] _values = FrameFlag.values();

    public static final Set<FrameFlag> NONE = Collections.unmodifiableSet(EnumSet.noneOf(FrameFlag.class));

	public byte getValue() {
		return value;
	}

	public static Set<FrameFlag> getEnumSet(byte value, FrameType type) throws HTTP2Exception {
        if(value==0) {
            return NONE;
        }

		// Empty EnumSet
		EnumSet<FrameFlag> result = EnumSet.noneOf(FrameFlag.class);

		// Check if the first bit is set
		if((value & 1)  == 1)
		{
			// for SETTING and PING frames the first bit indicates whether the frame is ACK
			if(type == FrameType.SETTINGS || type == FrameType.PING)
			{
				result.add(FrameFlag.ACK);
			}
			else
			{
				result.add(FrameFlag.END_STREAM);
			}
			
			// reset the first bit
			value = (byte)(value ^ 1);
		}

		// For each flag in FrameFlag
		for (FrameFlag flag : _values) {
			// Check whether the flag bit is set
			if ((value & flag.value) != 0) {
				result.add(flag);
				
				// reset the flag bit
				value = (byte)(value ^ flag.value);
			}
		}
		
		if(value != 0) {
            // Unknown bit flag is set, according to the spec we should ignore it
			// throw new HTTP2Exception(HTTP2ErrorCode.CONNECT_ERROR, "Unknown bit flag is set: " + value);
        }

		return result;
	}
	
	public static byte getValue(Set<FrameFlag> flags) {

		byte result = 0;

		for (FrameFlag flag : flags) {
			result = (byte) (result | flag.getValue());
		}

		return result;
	}
}
