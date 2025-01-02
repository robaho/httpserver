package robaho.net.httpserver.http2.frame;

import java.util.function.Consumer;

public class SettingsMap {
    private final SettingParameter[] settings = new SettingParameter[SettingIdentifier._values.length];

    public SettingParameter get(SettingIdentifier identifier) {
        return settings[identifier.getValue()];
    }
    public SettingParameter getOrDefault(SettingIdentifier identifier,SettingParameter defaultValue) {
        SettingParameter setting = settings[identifier.getValue()];
        return setting == null ? defaultValue : setting;
    }
    public void set(SettingParameter setting) {
        settings[setting.identifier.getValue()] = setting;
    }
    public void forEach(Consumer<SettingParameter> consumer) {
        for (SettingParameter setting : settings) {
            if (setting != null) {
                consumer.accept(setting);
            }
        }
    }
}