package robaho.net.httpserver.http2.hpack;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.HashMap;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class Huffman {
	static HashMap<String, Integer> huffmanCodes = null;

	public static HashMap<String, Integer> getHuffmanCodes()
			throws FileNotFoundException, IOException, URISyntaxException {
		if (huffmanCodes == null) {
			huffmanCodes = new HashMap<>();

			ClassLoader classloader = Thread.currentThread().getContextClassLoader();
			InputStream is = classloader.getResourceAsStream("huffman_codes_rfc7541.txt");

			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				String line;
				String code;
				int value = 0;
				while ((line = br.readLine()) != null) {
					code = line.substring(11, line.indexOf(' ', 11));
					code = code.replace("|", "");
					huffmanCodes.put(code, value);
					value++;
				}
			}
		}

		return huffmanCodes;
	}

	public static String decode(byte[] value) throws HTTP2Exception {
		StringBuilder result = new StringBuilder();

		HashMap<String, Integer> codes;
            try {
                codes = getHuffmanCodes();
            } catch (IOException ex) {
                throw new HTTP2Exception("Error reading huffman codes", ex);
            } catch (URISyntaxException ex) {
                throw new HTTP2Exception("Error reading huffman codes", ex);
            }

		StringBuilder code = new StringBuilder();

		for (int i = 0; i < value.length; i++) {
			int unsignedByte = value[i] & 0xff;
			for (int j = 0; j < 8; j++) {
				if ((unsignedByte & 0x00000080) != 0) {
					code.append("1");
				} else {
					code.append("0");
				}

				unsignedByte = unsignedByte << 1;

                Integer intValue = codes.get(code.toString());
                if(intValue!=null) {
                    if(intValue==256) {
            			throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR,"decoded contains EOS "+code);
                    }
                    result.append((char)(intValue & 0xFF));
                    code.setLength(0);
                }
			}
		}

		// Check for EOS (End of Stream) condition
		if (code.length() > 7 || code.indexOf("0")>=0) {
			throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR,"decoded has incorrect padding "+code);
        }

		return result.toString();
	}

}
