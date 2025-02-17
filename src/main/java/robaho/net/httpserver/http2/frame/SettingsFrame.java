package robaho.net.httpserver.http2.frame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;

public class SettingsFrame extends BaseFrame {

	ArrayList<SettingParameter> params = new ArrayList<>();

	/**
	 * SettingsFrame Constructor which calls the parameterized constructor
	 */
	public SettingsFrame() {
		this(new FrameHeader(0, FrameType.SETTINGS, FlagSet.of(FrameFlag.ACK) , 0));
	}

	public SettingsFrame(FrameHeader header) {
		super(header);
	}

	public ArrayList<SettingParameter> getSettingParameters() {
		return params;
	}

	public static SettingsFrame parse(byte[] frameBody, FrameHeader header) throws HTTP2Exception {
        if (header.getStreamIdentifier() != 0) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }

        if(header.getFlags().contains(FrameFlag.ACK)) {
            if(header.getLength() != 0) {
                throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
            }
        }

        if(frameBody.length % 6 != 0) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }

        SettingsFrame result = new SettingsFrame(header);
        int paramIndex = 0;

        while (paramIndex < frameBody.length) {
            var param = SettingParameter.parse(Arrays.copyOfRange(frameBody, paramIndex, paramIndex + 6));
            if(param!=null) {
                result.params.add(param);
            }   
            paramIndex += 6;
        }
        return result;
	}

	@Override
	public void writeTo(OutputStream os) throws IOException {

		int settingBodySize = params.size() * SettingParameter.PARAMETER_SIZE;

        FrameHeader header = new FrameHeader(settingBodySize,FrameType.SETTINGS,getHeader().getFlags(),getHeader().getStreamIdentifier());
        header.writeTo(os);

		for (int i = 0; i < params.size(); i++) {
			params.get(i).writeTo(os);
		}
    }

    public byte[] encode() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            writeTo(bos);
        } catch (IOException ignore) {
        }
        return bos.toByteArray();
    }

}
