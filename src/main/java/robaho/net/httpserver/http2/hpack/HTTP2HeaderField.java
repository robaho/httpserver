package robaho.net.httpserver.http2.hpack;

public class HTTP2HeaderField {

	public String name;
	public String value;

    public HTTP2HeaderField() {
    }
    
    public HTTP2HeaderField(String name, String value) {
        this.name = name;
        this.value = value;
    }

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
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

}
