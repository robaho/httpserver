package robaho.net.httpserver.extras;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * parse multipart form data
 */
public class MultipartFormParser {
    /**
     * a multipart part.
     *
     * either data or file will be non-null, but not both.
     *
     * @param contentType if non-null, the content type of the data
     * @param filename if non-null, the form provided filename
     * @param file if non-null, points to the uploaded file data (the name may
     * differ from filename). This file is marked as delete on exit.
     * @param data if non-null, contains the part data as a String.
     */
    public record Part(String contentType, String filename, String data, File file) {

    }

    private record PartMetadata(String name, String filename) {

    }

    /**
     * parse a multipart input stream, write files to storage. The caller is
     * responsible to delete files when they are no longer needed.
     *
     * @return a map of key to either a String (non-file) or a File
     */
    public static Map<String, List<Part>> parse(String encoding, String content_type, InputStream is, Path storage) throws IOException {
        Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);

        if (!content_type.contains("boundary=")) {
            throw new IllegalStateException("content type does not contain boundary");
        }

        String boundary = content_type.split("boundary=")[1];

        is = new BufferedInputStream(is);

        Map<String, List<Part>> results = new HashMap<>();

        // the CRLF is considered part of the boundary
        byte[] boundaryCheck = ("\r\n--" + boundary).getBytes(charset);

        List<String> headers = new LinkedList<>();

        System.out.println("reading until start of part");
        // read until boundary found
        int matchCount = 2; // starting at 2 allows matching non-compliant senders. rfc says CRLF is part of
                            // boundary marker
        while (true) {
            int c = is.read();
            if (c == -1) {
                return results;
            }
            if (c == boundaryCheck[matchCount]) {
                matchCount++;
                if (matchCount == boundaryCheck.length - 2) {
                    System.out.println("found boundary marker");
                    break;
                }
            } else {
                matchCount = 0;
                if (c == boundaryCheck[matchCount]) {
                    matchCount++;
                }
            }
        }

        // read to end of line
        String s = readLine(charset, is);
        if (s == null || "--".equals(s)) {
            return results;
        }

        headers.clear();

        while (true) {
            // read part headers until blank line
            System.out.println("reading part headers");
            while (true) {
                s = readLine(charset, is);
                if (s == null) {
                    return results;
                }
                if ("".equals(s)) {
                    break;
                }
                headers.add(s);
            }

            System.out.println("reading part data");
            // read part data - need to detect end of part
            PartMetadata meta = parseHeaders(headers);

            Runnable addToResults;
            OutputStream os;
            if (meta.filename == null) {
                var bos = new ByteArrayOutputStream();
                os = bos;
                addToResults = () -> results.computeIfAbsent(meta.name, k -> new LinkedList<Part>()).add(new Part(null, null, bos.toString(charset), null));
            } else {
                File file = Path.of(storage.toString(), meta.filename).toFile();
                file.deleteOnExit();
                os = new BufferedOutputStream(new FileOutputStream(file));
                addToResults = () -> results.computeIfAbsent(meta.name, k -> new LinkedList<Part>()).add(new Part(null, meta.filename, null, file));
            }

            try (os) {
                matchCount = 0;
                while (true) {
                    int c = is.read();
                    if (c == -1) {
                        return results;
                    }
                    if (c == boundaryCheck[matchCount]) {
                        matchCount++;
                        if (matchCount == boundaryCheck.length) {
                            System.out.println("found boundary marker");
                            break;
                        }
                    } else {
                        if (matchCount > 0) {
                            os.write(boundaryCheck, 0, matchCount);
                            matchCount = 0;
                        }
                        if (c == boundaryCheck[matchCount]) {
                            matchCount++;
                        } else {
                            os.write(c);
                        }
                    }
                }
            }

            addToResults.run();

            // read to end of line
            s = readLine(charset, is);
            if ("--".equals(s)) {
                return results;
            }
        }
    }

    private static final Pattern optionPattern = Pattern.compile("\\s(?<key>.*)=\"(?<value>.*)\"");

    private static PartMetadata parseHeaders(List<String> headers) {
        String name = null;
        String filename = null;
        for (var header : headers) {
            String[] parts = header.split(":", 2);
            if ("content-disposition".equalsIgnoreCase(parts[0])) {
                String[] options = parts[1].split(";");
                for (var option : options) {
                    Matcher m = optionPattern.matcher(option);
                    if (m.matches()) {
                        String key = m.group("key");
                        String value = m.group("value");
                        if ("name".equals(key)) {
                            name = value;
                        }
                        if ("filename".equals(key)) {
                            filename = value;
                        }
                    }
                }

            }
        }
        return new PartMetadata(name, filename);
    }

    private static String readLine(Charset charset, InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean prevCR = false;
        while (true) {
            int c = is.read();
            if (c == -1) {
                if (bos.size() > 0) {
                    return bos.toString(charset);
                }
                return null;
            }
            if (c == '\r') {
                prevCR = true;
            } else if (c == '\n') {
                if (prevCR) {
                    return bos.toString(charset);
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
