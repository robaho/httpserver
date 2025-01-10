package robaho.net.httpserver.http2.hpack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.OpenAddressMap;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.Utils;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameType;

public class HPackContext {
    private final List<HTTP2HeaderField> dynamicTable = new ArrayList(1024);

    public HPackContext() {
    }

    public HTTP2HeaderField getHeaderField(int index) {
        if (index > 0 && index <= 61) {
            return RFC7541Parser.getHeaderField(index);
        } else {
            return dynamicTable.get(index - 62);
        }
    }

    public void addHeaderField(HTTP2HeaderField field) {
        dynamicTable.add(0,field);
    }

    public List<HTTP2HeaderField> decodeFieldSegments(byte[] buffer) throws HTTP2Exception {
        List<HTTP2HeaderField> headers = new ArrayList<>(8);
        int index = 0;

        try {

            while (index < buffer.length) {
                HTTP2HeaderField headerField = new HTTP2HeaderField();
                if ((buffer[index] & 0x80) != 0) {
                    index = decodeIndexedHeaderField(buffer, index, headerField);
                } else if ((buffer[index] & 0x40) != 0) {
                    // Literal Header Field with Incremental Indexing
                    index = decodeFieldWithIncrementalIndexing(buffer, index, headerField);
                } else if ((buffer[index] & 0xF0) == 0) {
                    // Literal Header Field without Indexing
                    index = decodeLiteralFieldWithoutIndexing(buffer, index, headerField);
                } else if ((buffer[index] & 0xF0) == 0x10) {
                    // Literal Header Field never Indexed
                    index = decodeLiteralFieldNeverIndexed(buffer, index, headerField);
                } else if((buffer[index] & 0xE0) == 0x20) {
                    if(!headers.isEmpty()) {
                        throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "Dynamic table size update must occur at beginning of block");
                    }
                    index = decodeDynamicTableSizeUpdate(buffer,index);
                    continue;
                } else {
                    throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "Invalid header field representation " + buffer[index]);
                }
                headers.add(headerField);
            }
        } catch (HTTP2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, e);
        }

        return headers;
    }

    private int decodeIndexedHeaderField(byte[] buffer, int index, HTTP2HeaderField headerField) throws HTTP2Exception {
        var pair = decodeUnsignedInteger(buffer, index, 7);
        int headerIndex = pair.value;
        index = pair.index;

        HTTP2HeaderField field = getHeaderField(headerIndex);
        if (field == null) {
            throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "Invalid header index " + headerIndex + ", dynamic: "+dynamicTable);
        }

        headerField.setName(field.name,field.normalizedName);
        headerField.setValue(field.value);

        return index;
    }

    private int decodeFieldWithIncrementalIndexing(byte[] buffer, int index, HTTP2HeaderField headerField) throws HTTP2Exception {
        var pair = decodeUnsignedInteger(buffer, index, 6);
        int headerIndex = pair.value;
        index = pair.index;

        index = decodeFieldName(buffer, index, headerIndex, headerField);
        index = decodeFieldValue(buffer, index, headerField);

        dynamicTable.add(0,headerField);

        return index;
    }
    private int decodeLiteralFieldWithoutIndexing(byte[] buffer, int index, HTTP2HeaderField headerField) throws HTTP2Exception {
        var pair = decodeUnsignedInteger(buffer, index, 4);
        int headerIndex = pair.value;
        index = pair.index;

        index = decodeFieldName(buffer, index, headerIndex, headerField);
        index = decodeFieldValue(buffer, index, headerField);
        return index;
    }
    private int decodeDynamicTableSizeUpdate(byte[] buffer, int index) throws HTTP2Exception {
        var pair = decodeUnsignedInteger(buffer, index, 5);
        int size = pair.value;
        index = pair.index;

        if (size > 4096) { // Assuming 4096 is the maximum size for the dynamic table
            throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "Dynamic table size update too large: " + size);
        }

        while (dynamicTable.size() > size) {
            dynamicTable.removeLast();
        }

        return index;
    }

    private int decodeFieldName(byte[] buffer, int index, int headerIndex, HTTP2HeaderField headerField) throws HTTP2Exception {
        if (headerIndex == 0) {
            boolean huffmanCode = (buffer[index] & 0x80) != 0;
            int length = buffer[index] & 0x7F;
            index++;
            byte[] valueBytes = Arrays.copyOfRange(buffer, index, index + length);
            index += length;
            String value;
            if (huffmanCode) {
                value = Huffman.decode(valueBytes);
            } else {
                value = new String(valueBytes);
            }
            if(!value.equals(value.toLowerCase())) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "header field name is not lowercase " + value);
            }
            headerField.setName(value);
        } else {
            var field = getHeaderField(headerIndex);
            if (field == null) {
                throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "Invalid header index " + headerIndex);
            }
            headerField.setName(field.name,field.normalizedName);
        }
        return index;
    }

    private int decodeFieldValue(byte[] buffer, int index, HTTP2HeaderField headerField) throws HTTP2Exception {
        // Check if Huffman coding is used
        boolean huffmanCode = (buffer[index] & 0x80) != 0;
        var pair = decodeUnsignedInteger(buffer, index, 7);
        index = pair.index;
        int length = pair.value;

        byte[] valueBytes = Arrays.copyOfRange(buffer, index, index + length);
        String value;
        if (huffmanCode) {
            value = Huffman.decode(valueBytes);
        } else {
            value = new String(valueBytes);
        }
        headerField.setValue(value);

        return index + length;
    }

    private static record IndexValuePair(int index, int value) {
    }

    private static IndexValuePair decodeUnsignedInteger(byte[] buffer, int index, int prefixBits) {
        int value = buffer[index] & ((1 << prefixBits) - 1);
        index++;
        if (value < ((1 << prefixBits) - 1)) {
            return new IndexValuePair(index, value);
        }

        int m = 0;
        int b;
        do {
            b = buffer[index] & 0xFF;
            value += (b & 0x7F) << m;
            m += 7;
            index++;
        } while ((b & 0x80) != 0);

        return new IndexValuePair(index, value);
    }

    private int decodeLiteralFieldNeverIndexed(byte[] buffer, int index, HTTP2HeaderField headerField) throws HTTP2Exception {
        var pair = decodeUnsignedInteger(buffer, index, 4);
        int headerIndex = pair.value;
        index = pair.index;

        index = decodeFieldName(buffer, index, headerIndex, headerField);
        index = decodeFieldValue(buffer, index, headerField);
        return index;
    }

    /** this method is optimized for a server implementation and is not suitable for generic http2 hpack header encoding */
    public static void writeHeaderFrame(Headers headers, OutputStream outputStream, int streamId,boolean closeStream) throws IOException {
        ByteArrayOutputStream fields = new ByteArrayOutputStream(256); // average http headers length is 800 bytes

        // ':status' is required and the only allowed outbound pseudo headers
        fields.write(encodeHeader(":status", headers.getFirst(":status")));

        StringBuilder sb = new StringBuilder(32);

        headers.forEach((name, values) -> {
            for (String value : values) {
                if(name.startsWith(":")) {
                    continue;
                }
                // Headers keys are normalized to the first letter in uppercase and the rest in lowercase
                // http2 keys are all lowercase

                sb.setLength(0);
                sb.append(Character.toLowerCase(name.charAt(0)));
                sb.append(name.substring(1));

                byte[] header = encodeHeader(sb.toString(), value);
                try {
                    fields.write(header);
                } catch (IOException ex) {
                }
            }
        });

        FrameHeader.writeTo(outputStream, fields.size(),FrameType.HEADERS,closeStream ? END_OF_HEADERS_AND_STREAM : END_OF_HEADERS, streamId);
        fields.writeTo(outputStream);
    }

    public static void writeGenericHeaderFrame(Headers headers, OutputStream outputStream, int streamId) throws IOException {
        ByteArrayOutputStream pseudo = new ByteArrayOutputStream();
        ByteArrayOutputStream fields = new ByteArrayOutputStream();

        headers.forEach((name, values) -> {
            for (String value : values) {
                if(name.startsWith(":")) {
                    // Headers keys are normalized to the first letter in uppercase and the rest in lowercase
                    // http2 keys are all lowercase
                    byte[] header = encodeHeader(Character.toLowerCase(name.charAt(0))+name.substring(1), value);
                    try {
                        pseudo.write(header);
                    } catch (IOException ex) {
                    }
                } else {
                    // Headers keys are normalized to the first letter in uppercase and the rest in lowercase
                    // http2 keys are all lowercase
                    byte[] header = encodeHeader(Character.toLowerCase(name.charAt(0))+name.substring(1), value);
                    try {
                        fields.write(header);
                    } catch (IOException ex) {
                    }

                }
            }
        });

        FrameHeader.writeTo(outputStream,pseudo.size()+fields.size(),FrameType.HEADERS, END_OF_HEADERS, streamId);
        pseudo.writeTo(outputStream);
        fields.writeTo(outputStream);
    }

    private static final FlagSet END_OF_HEADERS = FlagSet.of(FrameFlag.END_HEADERS);
    private static final FlagSet END_OF_HEADERS_AND_STREAM = FlagSet.of(FrameFlag.END_HEADERS,FrameFlag.END_STREAM);

    public static List<byte[]> encodeHeadersFrame(Headers headers,int streamId) {
        byte[] buffer = encodeHeaders(headers);
        byte[] header = FrameHeader.encode(buffer.length, FrameType.HEADERS, END_OF_HEADERS, streamId);
        return List.of(header,buffer);
    }

    private static byte[] encodeHeaders(Headers headers) {
        List<byte[]> fields = new ArrayList(headers.size());
        List<byte[]> pseudo = new ArrayList<>(6);
        headers.forEach((name, values) -> {
            for (String value : values) {
                if(name.startsWith(":")) {
                    byte[] header = encodeHeader(name, value);
                    pseudo.add(header);
                } else {
                    // Headers keys are normalized to the first letter in uppercase and the rest in lowercase
                    byte[] header = encodeHeader(Character.toLowerCase(name.charAt(0))+name.substring(1), value);
                    fields.add(header);
                }
            }
        });
        return Utils.combineByteArrays(pseudo,fields);
    }

    private static byte[] encodeHeader(String name, String value) {
        if(":status".equals(name)) {
            byte[] result = RFC7541Parser.STATUSES.get(value);
            if(result!=null) return result;
        }
        var index = RFC7541Parser.getIndex(name);

        byte[] buffer = new byte[1];
        buffer[0]=0x00; // Literal Header Field without Indexing
        byte[] name_buffer = null;

        if(index!=null) {
            buffer = encodeIndexedField(index,4);
        } else {
            byte[] nameBytes = name.getBytes();
            name_buffer = encodeString(nameBytes);
        }

        // Encode header value
        byte[] valueBytes = value.getBytes();
        byte[] value_buffer = encodeString(valueBytes);

        return name_buffer == null ? Utils.combineByteArrays(buffer,value_buffer) : Utils.combineByteArrays(buffer,name_buffer,value_buffer);
    }

    private static byte[] encodeString(byte[] value) {
        // TODO: implement huffman encoding
        byte[] buffer = new byte[0];
        if (value.length < 128) {
            buffer = Arrays.copyOf(buffer, buffer.length + 1);
            buffer[buffer.length - 1] = (byte) value.length;
        } else {
            buffer = Arrays.copyOf(buffer, buffer.length + 1);
            buffer[buffer.length - 1] = (byte) (value.length | 0x80);
            buffer = Arrays.copyOf(buffer, buffer.length + 1);
            buffer[buffer.length - 1] = (byte) (value.length >> 7);
        }
        buffer = Arrays.copyOf(buffer, buffer.length + value.length);
        System.arraycopy(value, 0, buffer, buffer.length - value.length, value.length);
        return buffer;
    }

    private static byte[] encodeIndexedField(int index, int prefixBits) {
        byte[] buffer = new byte[1];
        int mask = (1 << prefixBits) - 1;
        if (index < mask) {
            buffer[0] = (byte) index;
            return buffer;
        }
        buffer[0] = (byte) mask;
        index -= mask;
        while (index >= 128) {
            buffer = Arrays.copyOf(buffer, buffer.length + 1);
            buffer[buffer.length - 1] = (byte) ((index & 0x7F) | 0x80);
            index >>>= 7;
        }
        buffer = Arrays.copyOf(buffer, buffer.length + 1);
        buffer[buffer.length - 1] = (byte) index;
        return buffer;
    }

}

class RFC7541Parser {

    private static final HTTP2HeaderField[] STATIC_HEADER_TABLE = new HTTP2HeaderField[62];
    static final Map<String,byte[]> STATUSES = Map.of(
        "200",indexedField(8),
        "204",indexedField(9),
        "206",indexedField(10),
        "304",indexedField(11),
        "400",indexedField(12),
        "404",indexedField(13),
        "500",indexedField(13));

    private static byte[] indexedField(int index) {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) (0x80 | index);
        return buffer;
    }

    static {
        STATIC_HEADER_TABLE[1] = new HTTP2HeaderField(":authority", null);
        STATIC_HEADER_TABLE[2] = new HTTP2HeaderField(":method", "GET");
        STATIC_HEADER_TABLE[3] = new HTTP2HeaderField(":method", "POST");
        STATIC_HEADER_TABLE[4] = new HTTP2HeaderField(":path", "/");
        STATIC_HEADER_TABLE[5] = new HTTP2HeaderField(":path", "/index.html");
        STATIC_HEADER_TABLE[6] = new HTTP2HeaderField(":scheme", "http");
        STATIC_HEADER_TABLE[7] = new HTTP2HeaderField(":scheme", "https");
        STATIC_HEADER_TABLE[8] = new HTTP2HeaderField(":status", "200");
        STATIC_HEADER_TABLE[9] = new HTTP2HeaderField(":status", "204");
        STATIC_HEADER_TABLE[10] = new HTTP2HeaderField(":status", "206");
        STATIC_HEADER_TABLE[11] = new HTTP2HeaderField(":status", "304");
        STATIC_HEADER_TABLE[12] = new HTTP2HeaderField(":status", "400");
        STATIC_HEADER_TABLE[13] = new HTTP2HeaderField(":status", "404");
        STATIC_HEADER_TABLE[14] = new HTTP2HeaderField(":status", "500");
        STATIC_HEADER_TABLE[15] = new HTTP2HeaderField("accept-charset", null);
        STATIC_HEADER_TABLE[16] = new HTTP2HeaderField("accept-encoding", "gzip, deflate");
        STATIC_HEADER_TABLE[17] = new HTTP2HeaderField("accept-language", null);
        STATIC_HEADER_TABLE[18] = new HTTP2HeaderField("accept-ranges", null);
        STATIC_HEADER_TABLE[19] = new HTTP2HeaderField("accept", null);
        STATIC_HEADER_TABLE[20] = new HTTP2HeaderField("access-control-allow-origin", null);
        STATIC_HEADER_TABLE[21] = new HTTP2HeaderField("age", null);
        STATIC_HEADER_TABLE[22] = new HTTP2HeaderField("allow", null);
        STATIC_HEADER_TABLE[23] = new HTTP2HeaderField("authorization", null);
        STATIC_HEADER_TABLE[24] = new HTTP2HeaderField("cache-control", null);
        STATIC_HEADER_TABLE[25] = new HTTP2HeaderField("content-disposition", null);
        STATIC_HEADER_TABLE[26] = new HTTP2HeaderField("content-encoding", null);
        STATIC_HEADER_TABLE[27] = new HTTP2HeaderField("content-language", null);
        STATIC_HEADER_TABLE[28] = new HTTP2HeaderField("content-length", null);
        STATIC_HEADER_TABLE[29] = new HTTP2HeaderField("content-location", null);
        STATIC_HEADER_TABLE[30] = new HTTP2HeaderField("content-range", null);
        STATIC_HEADER_TABLE[31] = new HTTP2HeaderField("content-type", null);
        STATIC_HEADER_TABLE[32] = new HTTP2HeaderField("cookie", null);
        STATIC_HEADER_TABLE[33] = new HTTP2HeaderField("date", null);
        STATIC_HEADER_TABLE[34] = new HTTP2HeaderField("etag", null);
        STATIC_HEADER_TABLE[35] = new HTTP2HeaderField("expect", null);
        STATIC_HEADER_TABLE[36] = new HTTP2HeaderField("expires", null);
        STATIC_HEADER_TABLE[37] = new HTTP2HeaderField("from", null);
        STATIC_HEADER_TABLE[38] = new HTTP2HeaderField("host", null);
        STATIC_HEADER_TABLE[39] = new HTTP2HeaderField("if-match", null);
        STATIC_HEADER_TABLE[40] = new HTTP2HeaderField("if-modified-since", null);
        STATIC_HEADER_TABLE[41] = new HTTP2HeaderField("if-none-match", null);
        STATIC_HEADER_TABLE[42] = new HTTP2HeaderField("if-range", null);
        STATIC_HEADER_TABLE[43] = new HTTP2HeaderField("if-unmodified-since", null);
        STATIC_HEADER_TABLE[44] = new HTTP2HeaderField("last-modified", null);
        STATIC_HEADER_TABLE[45] = new HTTP2HeaderField("link", null);
        STATIC_HEADER_TABLE[46] = new HTTP2HeaderField("location", null);
        STATIC_HEADER_TABLE[47] = new HTTP2HeaderField("max-forwards", null);
        STATIC_HEADER_TABLE[48] = new HTTP2HeaderField("proxy-authenticate", null);
        STATIC_HEADER_TABLE[49] = new HTTP2HeaderField("proxy-authorization", null);
        STATIC_HEADER_TABLE[50] = new HTTP2HeaderField("range", null);
        STATIC_HEADER_TABLE[51] = new HTTP2HeaderField("referer", null);
        STATIC_HEADER_TABLE[52] = new HTTP2HeaderField("refresh", null);
        STATIC_HEADER_TABLE[53] = new HTTP2HeaderField("retry-after", null);
        STATIC_HEADER_TABLE[54] = new HTTP2HeaderField("server", null);
        STATIC_HEADER_TABLE[55] = new HTTP2HeaderField("set-cookie", null);
        STATIC_HEADER_TABLE[56] = new HTTP2HeaderField("strict-transport-security", null);
        STATIC_HEADER_TABLE[57] = new HTTP2HeaderField("transfer-encoding", null);
        STATIC_HEADER_TABLE[58] = new HTTP2HeaderField("user-agent", null);
        STATIC_HEADER_TABLE[59] = new HTTP2HeaderField("vary", null);
        STATIC_HEADER_TABLE[60] = new HTTP2HeaderField("via", null);
        STATIC_HEADER_TABLE[61] = new HTTP2HeaderField("www-authenticate", null);
    }

    private static final OpenAddressMap<String, Integer> STATIC_HEADER_NAME_TO_INDEX = new OpenAddressMap<>(256);
    static {
        Arrays.stream(STATIC_HEADER_TABLE).filter(v -> v!=null).forEach(v -> STATIC_HEADER_NAME_TO_INDEX.put(v.name, Arrays.asList(STATIC_HEADER_TABLE).indexOf(v)));
    }

    public static Integer getIndex(String name) {
        return STATIC_HEADER_NAME_TO_INDEX.get(name);
    }

    public static HTTP2HeaderField getHeaderField(int index) {
        if (index < 1 || index >= STATIC_HEADER_TABLE.length) {
            return null;
        }
        return STATIC_HEADER_TABLE[index];
    }

    public static String getHeaderFieldName(int index) {
        var field = getHeaderField(index);
        return field == null ? null : field.getName();
    }
}
