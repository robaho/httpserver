package robaho.net.httpserver.extras;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.Headers;

public final class ContentEncoding {

    private static final String defaultCharset = StandardCharsets.ISO_8859_1.name();

    /**
     * @return the provided content encoding or the default
     */
    public static String encoding(Headers headers) {
        List<String> values = headers.get("content-encoding");
        if (values == null) {
            return defaultCharset;
        }
        for (var entry : values) {
            if(entry.startsWith("charset=")) {
                String charset = entry.substring("charset=".length());
                return charset;
            }

        }
        return defaultCharset;
    }
}
