package robaho.net.httpserver.http2.frame;

public enum SettingIdentifier {
	SETTINGS_HEADER_TABLE_SIZE(0x1), 
	SETTINGS_ENABLE_PUSH(0x2), 
	SETTINGS_MAX_CONCURRENT_STREAMS(0x3), 
	SETTINGS_INITIAL_WINDOW_SIZE(0x4), 
	SETTINGS_MAX_FRAME_SIZE(0x5), 
	SETTINGS_MAX_HEADER_LIST_SIZE(0x6), 
	SETTINGS_NONE(0x0); // Not part of RFC																						// RFC

	int value;

	SettingIdentifier(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

    static final SettingIdentifier[] _values = SettingIdentifier.values();

	public static SettingIdentifier getEnum(int value) {
		SettingIdentifier result = SettingIdentifier.SETTINGS_NONE;

		for (SettingIdentifier e : _values) {
			if (e.getValue() == value)
				result = e;
		}
		return result;
	}

    public boolean validateValue(long value) {
        switch (this) {
            case SETTINGS_HEADER_TABLE_SIZE:
                return true;
            case SETTINGS_INITIAL_WINDOW_SIZE:
                // hackish, but need to check in the application, since a different error must be thrown
                return true;
            case SETTINGS_MAX_FRAME_SIZE:
                return value >= 16384 && value <= 16777215;
            case SETTINGS_MAX_HEADER_LIST_SIZE:
                return value >= 0;
            case SETTINGS_ENABLE_PUSH:
                return value == 0 || value == 1;
            case SETTINGS_MAX_CONCURRENT_STREAMS:
                return value >= 0 && value <= 0x7FFFFFFF;
            default:
                return false;
        }
    }

}
