package robaho.net.httpserver.http2.hpack;

public class HTTP2HeaderField {

	public String name;
	public String value;
    public String normalizedName;

    public HTTP2HeaderField() {
    }
    
    public HTTP2HeaderField(String name, String value) {
        this.name = name;
        this.value = value;
        this.normalizedName = normalize(name);
    }

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
        this.normalizedName = normalize(name);
	}
	public void setName(String name,String normalizedName) {
		this.name = name;
        this.normalizedName = normalizedName;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
    
    @Override
    public String toString() {
        return name + ": " + value;
    }

    public boolean isPseudoHeader() {
        return name.startsWith(":");
    }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
