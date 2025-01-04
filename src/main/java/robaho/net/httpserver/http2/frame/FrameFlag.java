package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.HTTP2Exception;

/**
 * An enumeration to define all the Flags that can be attached to a frame
 */
public enum FrameFlag {

    END_STREAM((byte) 0x1),
    ACK((byte) 0x1),
    END_HEADERS((byte) 0x4),
    PADDED((byte) 0x8),
    PRIORITY((byte) 0x20);

    private final byte value;
    private static final byte MASK = (byte) (END_STREAM.value | END_HEADERS.value | PADDED.value | PRIORITY.value);
    private static final FrameFlag[] _values = FrameFlag.values();

    FrameFlag(byte value) {
        this.value = value;
    }

    public static final FlagSet NONE = new FlagSet(0,false);

    public byte getValue() {
        return value;
    }

    public static FlagSet getEnumSet(byte value, FrameType type) throws HTTP2Exception {
        if (value == 0) {
            return NONE;
        }
        return new FlagSet(value & MASK, type == FrameType.SETTINGS || type == FrameType.PING);
    }

    public static class FlagSet {

        private final int value;
        private final boolean isAck;

        FlagSet(int value, boolean isAck) {
            this.value = value;
            this.isAck = isAck;
        }

        public byte value() {
            return (byte) value;
        }
        public boolean contains(FrameFlag flag) {
            return (value & flag.value) == flag.value;
        }
        public static FlagSet of(FrameFlag... flags) {
            int value = 0;
            boolean isAck = false;
            for (FrameFlag flag : flags) {
                value |= flag.value;
                if (flag == ACK) {
                    isAck = true;
                }
            }
            return new FlagSet(value, isAck);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            var tmp = this.value;

            if ((tmp & 1) == 1) {
                sb.append(isAck ? "ACK" : "END_STREAM");
                // reset the first bit
                tmp = (byte) (tmp ^ 1);
            }
            for (FrameFlag flag : FrameFlag._values) {
                if ((tmp & flag.value) == flag.value) {
                    if(!sb.isEmpty()) {
                        sb.append(",");
                    }
                    sb.append(flag);
                }
            }
            sb.append("]");
            return sb.toString();
        }
    }
}
