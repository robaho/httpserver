package robaho.net.httpserver.extras;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** This is a work in progress and does not work!!! */
public class MultipartFormParser {

    /**
     * parse a multipart input stream, write files to storage. The caller is
     * responsible to delete files when they are no longer needed.
     *
     * @return a map of key to either a String (non-file) or a File
     */
    public static Map<String, List<Object>> parse(String encoding, String content_type, InputStream is, Path storage) throws IOException {
        if (encoding == null) {
            encoding = StandardCharsets.ISO_8859_1.name();
        }

        is = new BufferedInputStream(is);

        String boundary = content_type.split("boundary=")[1];

        byte[] boundaryCheck = ("--" + boundary).getBytes(encoding);
        byte[] buffer = new byte[8192];

        while (true) {
            System.out.println("reading part header");
            while (true) {
                String s = readLine(encoding, is);
                if (s == null) {
                    break;
                }
                System.out.println(":::" + s);
                if ("".equals(s)) {
                    break;
                }
            }
            System.out.println("reading part data");
            while (true) {
                String s = readLine(encoding, is);
                if (s == null) {
                    break;
                }
                System.out.println(">>>" + s);
                if ("".equals(s)) {
                    break;
                }
            }
            if (is.available() == 0) {
                break;
            }
        }
        System.out.println("finished reading form");
        return Collections.EMPTY_MAP;
    }

    private static String readLine(String encoding, InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean prevCR = false;
        while (true) {
            int c = is.read();
            if (c == -1) {
                if (bos.size() > 0) {
                    return bos.toString(encoding);
                }
                return null;
            }
            if (c == '\r') {
                prevCR = true;
            } else if (c == '\n') {
                if (prevCR) {
                    return bos.toString(encoding);
                } else {
                    bos.write(c);
                }
            } else {
                if (prevCR) {
                    bos.write('\r');
                    prevCR = false;
                }
                bos.write(c);
            }
        }
    }
}
