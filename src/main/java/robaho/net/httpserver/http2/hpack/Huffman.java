package robaho.net.httpserver.http2.hpack;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;

import robaho.net.httpserver.OpenAddressMap;
import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class Huffman {
    private static class HuffmanSequence {
        private final char[] buffer;
        private final int length;
        private final int hash;
        HuffmanSequence(char[] buffer, int length) {
            this.buffer = buffer;
            this.length = length;
            this.hash = calculateHash();
        }
        HuffmanSequence(char[] buffer) {
            this(buffer, buffer.length);
        }
        private int calculateHash() {
            int hash = 0;
            for (int i = 0; i < length; i++) {
                hash = 31 * hash + buffer[i];
            }
            return hash;
        }
        @Override
        public int hashCode() {
            return hash;
        }
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HuffmanSequence other)) {
                return false;
            }
            if (length != other.length || hash != other.hash) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (buffer[i] != other.buffer[i]) {
                    return false;
                }
            }
            return true;
        }
    }
	static OpenAddressMap<HuffmanSequence,Integer> huffmanCodes = null;

	public static OpenAddressMap<HuffmanSequence,Integer> getHuffmanCodes()
			throws FileNotFoundException, IOException, URISyntaxException {
		if (huffmanCodes == null) {
			huffmanCodes = new OpenAddressMap<>(512);

			ClassLoader classloader = Thread.currentThread().getContextClassLoader();
			InputStream is = classloader.getResourceAsStream("huffman_codes_rfc7541.txt");

			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				String line;
				String code;
				int value = 0;
				while ((line = br.readLine()) != null) {
					code = line.substring(11, line.indexOf(' ', 11));
					code = code.replace("|", "");
					huffmanCodes.put(new HuffmanSequence(code.toCharArray()), value);
					value++;
				}
			}
		}

		return huffmanCodes;
	}

	public static String decode(byte[] value) throws HTTP2Exception {
		StringBuilder result = new StringBuilder();

		OpenAddressMap<HuffmanSequence,Integer> codes;
            try {
                codes = getHuffmanCodes();
            } catch (IOException ex) {
                throw new HTTP2Exception("Error reading huffman codes", ex);
            } catch (URISyntaxException ex) {
                throw new HTTP2Exception("Error reading huffman codes", ex);
            }

        CodeBuffer code = new CodeBuffer();

		for (int i = 0; i < value.length; i++) {
			int unsignedByte = value[i] & 0xff;
			for (int j = 0; j < 8; j++) {
				if ((unsignedByte & 0x00000080) != 0) {
					code.append('1');
				} else {
					code.append('0');
				}

				unsignedByte = unsignedByte << 1;

                Integer intValue = codes.get(code.sequence());
                if(intValue!=null) {
                    if(intValue==256) {
            			throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR,"decoded contains EOS "+code);
                    }
                    result.append((char)(intValue & 0xFF));
                    code.reset();
                }
			}
		}

		// Check for EOS (End of Stream) condition
		if (code.length() > 7 || code.indexOf('0')>=0) {
			throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR,"decoded has incorrect padding "+code);
        }

		return result.toString();
	}
    private static class CodeBuffer {
        private char[] buffer = new char[32];
        private int length;

        void append(char ch) {
            if (length >= buffer.length) {
                char[] newBuffer = new char[buffer.length * 2];
                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                buffer = newBuffer;
            }
            buffer[length++] = ch;
        }

        void reset() {
            length = 0;
        }

        int length() {
            return length;
        }

        int indexOf(char c) {
            for (int i = 0; i < length; i++) {
                if (buffer[i] == c) {
                    return i;
                }
            }
            return -1;
        }

        HuffmanSequence sequence() {
            return new HuffmanSequence(buffer, length);
        }

        @Override
        public String toString() {
            return new String(buffer, 0, length);
        }
    }





}
