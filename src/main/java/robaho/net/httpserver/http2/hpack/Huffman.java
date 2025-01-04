package robaho.net.httpserver.http2.hpack;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.Arrays;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class Huffman {
    private static class HuffmanSequence implements Comparable<HuffmanSequence> {
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
                hash = 31 * hash + (buffer[i] == '1' ? 1 : 0);
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
            if (hash != other.hash) {
                return false;
            }
            return compareTo(other) == 0;
        }
        @Override
        public String toString() {
            return new String(buffer, 0, length);
        }
        @Override
        public int compareTo(HuffmanSequence o) {
            if (length != o.length) {
                return length - o.length;
            }
            for (int i = 0; i < length; i++) {
                if (buffer[i] != o.buffer[i]) {
                    return buffer[i] - o.buffer[i];
                }
            }
            return 0;
        }
    }
    private static class HuffmanCode {
        private final HuffmanSequence sequence;
        private final int value;
        HuffmanCode(HuffmanSequence sequence, int value) {
            this.sequence = sequence;
            this.value = value;
        }
    }
    /**
     * a skip list of huffman codes
     */
    private static class HuffmanCodes {
        private final HuffmanCode[] codes;
        /** 
         * holds the offset into the array for the start of codes by the code length, or -1 if there
         * are no codes of that length
         */
        private final int[] offsets = new int[33];

        HuffmanCodes(HuffmanCode[] codes) {
            this.codes = codes;
            Arrays.sort(codes, (a, b) -> a.sequence.compareTo(b.sequence));
            Arrays.fill(offsets,-1);
            for (int i = 0; i < codes.length; i++) {
                HuffmanSequence sequence = codes[i].sequence;
                int length = sequence.length;
                if (length <= 32 && offsets[length] == -1) {
                    offsets[length] = i;
                }               
            }
        }
        /** @return the matched character value or null if no match */
        Integer get(HuffmanSequence sequence) {
            int index = offsets[sequence.length];
            if (index == -1) {
                return null;
            }
            while(index+8 < codes.length && codes[index+8].sequence.compareTo(sequence)<0) {
                index+=8;
            }
            while(index+4 < codes.length && codes[index+4].sequence.compareTo(sequence)<0) {
                index+=4;
            }
            int result=0;
            while(index < codes.length && (result = codes[index].sequence.compareTo(sequence))<0) {
                index++;
            }
            if(index < codes.length && result==0) {
                return codes[index].value;
            } else {
                return null;
            }
        }
    }

	private static HuffmanCodes huffmanCodes;
	private static HuffmanCodes getHuffmanCodes()
			throws FileNotFoundException, IOException, URISyntaxException {
		if (huffmanCodes == null) {
            HuffmanCode[] codes = new HuffmanCode[257];

			ClassLoader classloader = Thread.currentThread().getContextClassLoader();
			InputStream is = classloader.getResourceAsStream("huffman_codes_rfc7541.txt");

			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				String line;
				String code;
				int value = 0;
				while ((line = br.readLine()) != null) {
					code = line.substring(11, line.indexOf(' ', 11));
					code = code.replace("|", "");
					codes[value] = new HuffmanCode(new HuffmanSequence(code.toCharArray()), value);
					value++;
				}
			}
			huffmanCodes = new HuffmanCodes(codes);
		}

		return huffmanCodes;
	}

	public static String decode(byte[] value) throws HTTP2Exception {
		StringBuilder result = new StringBuilder();

		HuffmanCodes codes;
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

                var intValue = codes.get(code.sequence());
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
