package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.EnumSet;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;

/**
 * [rfc7540 Section 6.2] The HEADERS frame (type=0x1) is used to open a stream
 * (Section 5.1), and additionally carries a header block fragment. HEADERS
 * frames can be sent on a stream in the "idle", "reserved (local)", "open", or
 * "half-closed (remote)" state.
 */
public class HeadersFrame extends BaseFrame {

	private int padLength;
	private boolean isExclusive;
	private long dependentStreamId;
	private int weight;
	private byte[] headerBlock;
	private byte[] padding;

	public HeadersFrame() {
		this(new FrameHeader(0, FrameType.HEADERS, FrameFlag.NONE, 0));
	}

	public HeadersFrame(FrameHeader header) {
		super(header);
	}

	/**
	 * An 8-bit field containing the length of the frame padding in units of
	 * octets. This field is only present if the PADDED flag is set.
	 */
	public int getPadLength() {
		return padLength;
	}

	public void setPadLength(int padLength) {
		this.padLength = padLength;
	}

	/**
	 * A single-bit flag indicating that the stream dependency is exclusive (see
	 * Section 5.3). This field is only present if the PRIORITY flag is set.
	 */
	public boolean getIsExclusive() {
		return isExclusive;
	}

	public void setIsExclusive(boolean isExclusive) {
		this.isExclusive = isExclusive;
	}

	/**
	 * A 31-bit stream identifier for the stream that this stream depends on
	 * (see Section 5.3). This field is only present if the PRIORITY flag is
	 * set.
	 */
	public long getDependentStream() {
		return dependentStreamId;
	}

	public void setDependentStream(long streamIdentifier) {
		this.dependentStreamId = streamIdentifier;
	}

	/**
	 * An unsigned 8-bit integer representing a priority weight for the stream
	 * (see Section 5.3). Add one to the value to obtain a weight between 1 and
	 * 256. This field is only present if the PRIORITY flag is set.
	 */
	public int getWeight() {
		return weight;
	}

	public void setWeight(int weight) {
		this.weight = weight;
	}

	/**
	 * A header block fragment (Section 4.3).
	 */
	public byte[] getHeaderBlock() {
		return headerBlock;
	}

	public void setHeaderBlock(byte[] headerBlock) {
		this.headerBlock = headerBlock;
	}

	/**
	 * Padding octets.
	 */
	public byte[] getPadding() {
		return padding;
	}

	public void setPadding(byte[] padding) {
		this.padding = padding;
	}

	/**
	 * 
	 * @param frameBody
	 *            payload of the HeadersFrame after consuming the FrameHeader
	 * @param header
	 *            FrameHeader
	 * @return HeadersFrame object
	 * @throws HTTP2Exception
	 * @throws Exception
	 */
	public static HeadersFrame parse(byte[] frameBody, FrameHeader header) throws HTTP2Exception, Exception {

        if(frameBody == null) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }

        if(header.getLength() != frameBody.length) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }

        int paramIndex = 0;

        HeadersFrame headersFrame = new HeadersFrame(header);

        //check for PADDED flag and if set then store padding length
        if (header.getFlags().contains(FrameFlag.PADDED)) {
            int padLength = Utils.convertToInt(frameBody, paramIndex, 1);
            if(padLength >= header.getLength()) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
            }
            headersFrame.setPadLength(padLength);
            paramIndex += 1;
        }
        
        //check for PRIORITY flag and if set then store the Exclusive bit (isExclusive) and store streamID
        if (header.getFlags().contains(FrameFlag.PRIORITY)) {

            var streamId = Utils.convertToInt(frameBody, paramIndex, 4);
            paramIndex += 4;

            if ((streamId & 0x80000000L) == 0x80000000L) {
                headersFrame.setIsExclusive(true);
            }
            headersFrame.setDependentStream(streamId & 0x7FFFFFFFL);
            if (headersFrame.dependentStreamId == header.getStreamIdentifier()) {
                // cannot depend on itself
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
            }

            headersFrame.setWeight( (frameBody[paramIndex] & 0xFF) +1 );
            paramIndex += 1;
        }

        headersFrame.setHeaderBlock(Arrays.copyOfRange(frameBody, paramIndex, (header.getLength() - headersFrame.getPadLength())));

		return headersFrame;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        byte[] buffer = getHeaderBlock();
        FrameHeader.writeTo(os, buffer.length, FrameType.HEADERS, FlagSet.of(FrameFlag.END_HEADERS), getHeader().getStreamIdentifier());
        os.write(buffer);
        os.flush();
    }
    public byte[] encode() {
        throw new UnsupportedOperationException("use HPackContext encodeFrameHeaders()");
    }
}
