package cat.nyaa.nyaacore.database;

import cat.nyaa.nyaacore.database.provider.MapProvider;
import cat.nyaa.nyaacore.database.provider.MysqlProvider;
import cat.nyaa.nyaacore.database.provider.SQLiteProvider;
import cat.nyaa.nyaacore.utils.ClassPathUtils;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URISyntaxException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Database utils that provide database access according to plugin's configuration
 */
public class DatabaseUtils {
    private static Map<String, DatabaseProvider> providerRegistry = new HashMap<>();

    /**
     * Register provider.
     *
     * @param name     provider name
     * @param provider provider instance
     */
    public static void registerProvider(String name, DatabaseProvider provider){
        providerRegistry.put(name, provider);
    }

    public static boolean hasProvider(String name){
        return providerRegistry.containsKey(name);
    }

    public static DatabaseProvider unregisterProvider(String name){
        return providerRegistry.remove(name);
    }

    static {
        registerProvider("map", new MapProvider());
        registerProvider("sqlite", new SQLiteProvider());
        registerProvider("mysql", new MysqlProvider());
    }

    /**
     * Get database instance from provider and configuration specified
     *
     * @param <T>           generic type for return different subtype of {@link Database}}
     * @param plugin        plugin requesting. may be null if provider
     * @param provider      provider name
     * @param configuration configuration
     * @return database instance
     */
    @SuppressWarnings("unchecked")
    public static <T extends Database> T get(String provider, JavaPlugin plugin, Map<String, Object> configuration){
        DatabaseProvider p = providerRegistry.get(provider);
        Validate.notNull(p, "Provider '" + provider + "' not found");
        Database db = p.get(plugin, configuration);
        Validate.notNull(db, "Provider '" + provider + "' returned null");
        return (T) db;
    }

    /**
     * Get database instance from plugin's configuration section specified
     *
     * @param <T>           generic type for return different subtype of {@link Database}}
     * @param plugin        plugin requesting. not null
     * @param sectionName   configuration section in plugin's config
     * @return database instance
     */
    public static <T extends Database> T get(JavaPlugin plugin, String sectionName){
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionName);
        Validate.notNull(section, "Please add a '" + sectionName + "' section containing a 'provider' value and (if provider requires) a 'connection' section to your " + plugin.getName() + "'s config file");
        ConfigurationSection conn = section.getConfigurationSection("connection");
        String provider = section.getString("provider");
        Validate.notNull(provider, "Please add a 'provider' value in 'database' section. Available: " + providerRegistry.keySet().stream().reduce("", (s, s2) -> s + ", " + s2));
        return get(provider, plugin, conn == null ? null : conn.getValues(false));
    }

    /**
     * Get database instance from plugin's configuration section specified. Inferring plugin from callstack.
     *
     * @param <T>           generic type for return different subtype of {@link Database}}
     * @param sectionName   configuration section in plugin's config
     * @return database instance
     */
    public static <T extends Database> T get(String sectionName){
        try {
            return get(JavaPlugin.getProvidingPlugin(Class.forName(Thread.currentThread().getStackTrace()[2].getClassName())), sectionName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException();
        }
    }

    /**
     * Get database instance from plugin's configuration section 'database'. Inferring plugin from callstack.
     *
     * @param <T>           generic type for return different subtype of {@link Database}}
     * @return database instance
     */
    public static <T extends Database> T get(){
        try {
            return get(JavaPlugin.getProvidingPlugin(Class.forName(Thread.currentThread().getStackTrace()[2].getClassName())), "database");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException();
        }
    }

    @SuppressWarnings("unchecked")
    public static Class<?>[] scanClasses(Plugin plugin, Map<String, Object> configuration, Class<? extends Annotation> annotation) {
        Class<?>[] classes;
        if(Boolean.parseBoolean(configuration.get("autoscan").toString())){
            Object pack = configuration.get("package");
            try {
                Set<ClassPathUtils.ClassInfo> classInfos = ClassPathUtils.from(new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()), plugin.getClass().getClassLoader()).getAllClasses();
                classes = classInfos
                           .stream()
                           .filter(c -> pack == null || c.getPackageName().startsWith((String) pack))
                           .map(ClassPathUtils.ClassInfo::load)
                           .filter(c -> c != null && c.getAnnotation(annotation) != null)
                           .toArray(Class<?>[]::new);
            } catch (IOException|URISyntaxException e) {
                throw new RuntimeException(e);
            }
        } else {
            Object obj = configuration.get("tables");
            if(!(obj instanceof Collection)){
                throw new IllegalArgumentException();
            }
            Collection<String> tables = ((Collection<String>) configuration.get("tables"));
            classes = tables.stream().map(s -> {
                try {
                    return Class.forName(s);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException(e);
                }
            }).toArray(Class<?>[]::new);
        }
        return classes;
    }

    public static BukkitTask dumpDatabaseAsync(Plugin plugin, RelationalDB from, RelationalDB to, BiConsumer<Class<?>, Integer> progressCallback){
        List<Class<?>> fromTables = Arrays.asList(from.getTables());
        List<Class<?>> toTables = Arrays.asList(to.getTables());
        if(!toTables.containsAll(fromTables)){
            throw new IllegalArgumentException("Destination database do not contains all tables to be dumped");
        }
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                from.beginTransaction();
                to.beginTransaction();

                for(Class<?> table : fromTables){
                    dumpTable(from, to, progressCallback, table);
                }

                from.commitTransaction();
                to.commitTransaction();
                progressCallback.accept(null, 0);

        });
    }

    private static <T> void dumpTable(RelationalDB from, RelationalDB to, BiConsumer<Class<?>, Integer> progressCallback, Class<T> table) {
        List<T> rows = from.query(table).select();
        int r = rows.size();
        progressCallback.accept(table, r);
        for (T row : rows) {
            r--;
            to.query(table).insert(row);
            if(r %100 == 0){
                progressCallback.accept(table, r);
            }
        }
    }
}