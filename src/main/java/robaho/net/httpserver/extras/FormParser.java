package robaho.net.httpserver.extras;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse url-encoded requests.
 */
public class FormParser {

    // Sets the maximum count of accepted POST params - protection against Hash collision DOS attacks
    private static final int MAX_PARAMS = Integer.getInteger("robaho.net.httpserver.max_form_params", 1000); // 0 == no limit

    /**
     * @param encoding the content encoding or null for the default encoding
     * @param is
     * @return a map of the provided parameters
     * @throws IOException
     */
    public static Map<String, List<String>> parse(String encoding, InputStream is) throws IOException {
        if (encoding == null) {
            encoding = Charset.defaultCharset().name();
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) > 0) {
            os.write(buffer, 0, bytesRead);
        }

        String data = new String(os.toByteArray(), encoding);
        if (data.length() == 0) {
            //data is empty - can skip the rest
            return new HashMap<>(0);
        }

        // data is o the form:
        // a=b&b=c%12...
        // Let us parse in two phases - we wait until everything is parsed before
        // we decoded it - this makes it possible for use to look for the
        // special _charset_ param which can hold the charset the form is encoded in.
        //
        // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
        // https://bugzilla.mozilla.org/show_bug.cgi?id=18643
        //
        // NB: _charset_ must always be used with accept-charset and it must have the same value
        String[] keyValues = data.split("&");

        if (MAX_PARAMS != 0 && keyValues.length > MAX_PARAMS) {
            throw new IllegalStateException("number of request parameters %d is higher than maximum of %d, aborting. Can be configured using 'http.maxParams'".formatted(keyValues.length, MAX_PARAMS));
        }

        for (String keyValue : keyValues) {
            String[] segs = keyValue.split("=", 2);
            String key = segs[0];
            String value = segs.length > 1 ? segs[1] : null;

            List<String> values = params.computeIfAbsent(key, k -> new ArrayList());
            if (value != null) {
                values.add(value);
            }
        }

        // Second phase - look for _charset_ param and do the encoding
        if (params.containsKey("_charset_")) {
            encoding = params.get("_charset_").get(0);
        }

        // We're ready to decode the params
        Map<String, List<String>> decodedParams = new LinkedHashMap<>(params.size());
        for (var e : params.entrySet()) {
            String key = e.getKey();
            key = URLDecoder.decode(e.getKey(), encoding);
            for (String value : e.getValue()) {
                String decodedValue = value == null ? null : URLDecoder.decode(value, encoding);
                List<String> values = decodedParams.computeIfAbsent(key, k -> new ArrayList());
                if (decodedValue != null) {
                    values.add(decodedValue);
                }
            }
        }
        return decodedParams;
    }
}
