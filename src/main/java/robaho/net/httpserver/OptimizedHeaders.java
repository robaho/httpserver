package robaho.net.httpserver;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.sun.net.httpserver.Headers;

public class OptimizedHeaders extends Headers {
    private final OpenAddressMap<String,Object> map;
    public OptimizedHeaders() {
        super();
        map = new OpenAddressMap(16);
    }
    public OptimizedHeaders(int capacity) {
        super();
        map = new OpenAddressMap(capacity);
    }
    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.size() == 0;
    }

    @Override
    public List<String> get(Object key) {
        Object o = map.get(normalize((String)key));
        return o == null ? null : (o instanceof String) ? Arrays.asList((String)o) : (List<String>)o;
    }

    @Override
    public List<String> put(String key, List<String> value) {
        Object o = map.put(normalize(key), value);
        return o == null ? null : (o instanceof String) ? Arrays.asList((String)o) : (List<String>)o;
    }

    @Override
    public List<String> remove(Object key) {
        Object o = map.put(normalize((String)key),null);
        return o == null ? null : (o instanceof String) ? Arrays.asList((String)o) : (List<String>)o;
    }

    @Override
    public String getFirst(String key) {
        Object o = map.get(normalize(key));
        return o == null ? null : (o instanceof String) ? (String)o : ((List<String>)o).getFirst();
    }

    /**
     * Normalize the key by converting to following form.
     * First {@code char} upper case, rest lower case.
     * key is presumed to be {@code ASCII}.
     */
    private static String normalize(String key) {
        int len = key.length();
        if(len==0) return key;

        int i=0;

        for(;i<len;i++) {
            char c = key.charAt(i);
            if (c == '\r' || c == '\n')
                throw new IllegalArgumentException("illegal character in key");
            if(i==0) {
                if (c >= 'a' && c <= 'z') {
                    break;
                }
            } else {
                if (c >= 'A' && c <= 'Z') {
                    break;
                }
            }
        }
        if(i==len) return key;
        return Character.toUpperCase(key.charAt(0))+key.substring(1).toLowerCase();
    }

    @Override
    public void add(String key, String value) {
        var normalized = normalize(key);
        Object o = map.get(normalized);
        if (o == null) {
            map.put(normalized, value);
        } else if(o instanceof String s) {
            map.put(normalized, new ArrayList<>(Arrays.asList(s,value)));
        } else {
            ((List<String>)o).add(value);
        }
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void set(String key, String value) {
        map.put(normalize(key), value);
    }

    @Override
    public boolean containsKey(Object key) {
        return map.get(normalize((String)key)) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return entrySet().stream().anyMatch(e -> e.getValue().contains((String)value));
    }
    @Override
    public Set<String> keySet() {
        return entrySet().stream().map(e -> e.getKey()).collect(Collectors.toSet());
    }

    @Override
    public Collection<List<String>> values() {
        return entrySet().stream().map(e -> e.getValue()).collect(Collectors.toSet());
   }

    @Override
    public Set<Map.Entry<String, List<String>>> entrySet() {
        Set<Map.Entry<String,List<String>>> set = new HashSet();
        forEach((k,v) -> set.add(new AbstractMap.SimpleEntry<>(k,v)));
        return set;
    }

    @Override
    public boolean equals(Object o) {
        return (this == o);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public void forEach(BiConsumer<? super String,? super List<String>> action) {
        map.forEach((k,v) -> action.accept(k, (v instanceof String) ? List.of((String)v) : (List<String>)v));
    }
}