package cat.nyaa.nyaacore.database.provider;

import cat.nyaa.nyaacore.database.DatabaseProvider;
import cat.nyaa.nyaacore.database.DatabaseUtils;
import org.bukkit.plugin.Plugin;

import javax.persistence.Table;
import java.util.Map;

public class SQLiteProvider implements DatabaseProvider {
    @Override
    public SQLiteDatabase get(Plugin plugin, Map<String, Object> configuration) {
        Class<?>[] classes = DatabaseUtils.scanClasses(plugin, configuration, Table.class);
        return new SQLiteDatabase(plugin, (String)configuration.get("file"), classes);
    }
}