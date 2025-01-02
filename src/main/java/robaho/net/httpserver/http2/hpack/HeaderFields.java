package robaho.net.httpserver.http2.hpack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import robaho.net.httpserver.OpenAddressMap;
import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

/**
 * container for emitted HTTP2HeaderField from field block decoding. Many of the rules of valid
 * headers require multi-field inspection, so aggregating and validating at the end is an easier
 * design
 */
public class HeaderFields implements Iterable<HTTP2HeaderField> {
    private static final Set<String> prohibitedHeaderFields = Set.of("connection");
    private static final Set<String> requiredHeaderFields = Set.of(":path",":method",":scheme");
    private static final Set<String> pseudoHeadersIn = Set.of(":authority", ":method", ":path", ":scheme");

    private final List<HTTP2HeaderField> fields = new ArrayList();
    private final OpenAddressMap pseudoHeaders = new OpenAddressMap(8);

    private boolean hasNonPseudoHeader = false;

    /**
     * add a HTTP2HeaderField to the collection performing any per field validation
     */
    public void addHeaderField(HTTP2HeaderField field) throws HTTP2Exception{
        if(field.isPseudoHeader() && !pseudoHeadersIn.contains(field.getName())) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "invalid pseudo header " + field.getName());
        }
        if(prohibitedHeaderFields.contains(field.getName())) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"prohibited header field "+field.getName());
        }
        if(field.getName().equals("te") && !field.getValue().equals("trailers")) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"prohibited header field "+field.getName());
        }
        if(!field.isPseudoHeader()) {
            hasNonPseudoHeader = true;
        }
        if(field.isPseudoHeader() && hasNonPseudoHeader) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"pseudo-header fields must appear before regular header fields");
        }
        if(field.isPseudoHeader()) {
            if(pseudoHeaders.put(field.getName(),field)!=null && requiredHeaderFields.contains(field.getName())) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"invalid duplicate header "+field.getName());
            }
        }
        fields.add(field);
    }
    private static boolean isEmpty(String s) {
        return s == null || s.trim().equals("");
    }
    public void addAll(List<HTTP2HeaderField> httpFields) throws HTTP2Exception {
        for(HTTP2HeaderField field : httpFields) {
            addHeaderField(field);
        }
    }
    @Override
    public Iterator<HTTP2HeaderField> iterator() {
        return fields.iterator();
    }
    public void clear() {
        fields.clear();
        hasNonPseudoHeader = false;
    }
    /**
     * perform the multi-field validation of the collection of header fields
     */
    public void validate() throws HTTP2Exception {
        for(var fieldName : requiredHeaderFields) {
            var ph = pseudoHeaders.get(fieldName);
            if(ph==null || isEmpty(((HTTP2HeaderField)ph).getValue())) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"missing required header field "+fieldName);
            }
        }
    }
    public int size() {
        return fields.size();
    }
}