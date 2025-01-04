package robaho.net.httpserver;

/** small set designed for efficient negative contains */
public class BloomSet {
    private final int bloomHash;
    private OpenAddressMap<String,Boolean> values;
    private BloomSet(String... values) {
        this.values = new OpenAddressMap<>(values.length*2);
        int bloomHash = 0;
        for(var v : values) {
            bloomHash = bloomHash | v.hashCode();
            this.values.put(v,true);
        }
        this.bloomHash = bloomHash;
    }
    public static BloomSet of(String... values) {
        return new BloomSet(values);
    }
    public boolean contains(String value) {
        return (bloomHash & value.hashCode()) == value.hashCode() && Boolean.TRUE.equals(values.get(value));
    }
    public Iterable<String> values() {
        return values.keys();
    }
}