package org.jivesoftware.util.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import org.jivesoftware.util.TaskEngine;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.*;

public class GuavaCache<K, V> implements Cache<K, V> {

    static private final LinkedBlockingQueue<com.google.common.cache.Cache<?,?>> caches;

    static {
        // manually clean up caches to reduce memory footprint
        caches = new LinkedBlockingQueue<com.google.common.cache.Cache<?,?>>();
        TaskEngine.getInstance().schedule( new TimerTask() {
            @Override
            public void run() {
                for (com.google.common.cache.Cache<?,?> c : caches) {
                    c.cleanUp();
                }
            }
        }, 5000, 10000);
    }

    /**
     * The map the keys and values are stored in.
     */
    protected com.google.common.cache.Cache<K,V> map;

    /**
     * Maximum size in bytes that the cache can grow to.
     */
    private long maxCacheSize;

    /**
     * Maximum length of time objects can exist in cache before expiring.
     */
    protected long maxLifetime;

    /**
     * The name of the cache.
     */
    private String name;


    public GuavaCache(String name, long maxSize, long maxLifetime) {
        this.name = name;
        this.maxCacheSize = maxSize;
        this.maxLifetime = maxLifetime;
        initializeMap();
    }

    private void initializeMap() {
        CacheBuilder<K,V> builder = (CacheBuilder<K, V>) CacheBuilder.newBuilder();
        if (maxCacheSize >= 0) {
            builder.maximumSize(maxCacheSize);
        }
        if (maxLifetime >= 0) {
            builder.expireAfterAccess(maxLifetime, TimeUnit.MILLISECONDS);
        }
        builder.recordStats();
        map = builder.build();

        // save cache reference for clean up
        caches.offer(map);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public long getMaxCacheSize() {
        return maxCacheSize;
    }

    @Override
    public void setMaxCacheSize(int maxSize) {
        maxCacheSize = maxSize;
        initializeMap();
    }

    @Override
    public long getMaxLifetime() {
        return maxLifetime;
    }

    @Override
    public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime;
        initializeMap();
    }

    @Override
    public int getCacheSize() {
        return (int) map.size();
    }

    @Override
    public long getCacheHits() {
        return map.stats().hitCount();
    }

    @Override
    public long getCacheMisses() {
        return map.stats().missCount();
    }

    @Override
    public int size() {
        return (int) map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return map.getIfPresent(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return map.asMap().containsValue(value);
    }

    @Override
    public V get(Object key) {
        return map.getIfPresent(key);
    }

    @Override
    public V put(K key, V value) {
        V old = map.getIfPresent(key);
        if (value != null) {
            map.put(key, value);
        } else {
            map.invalidate(key);
        }
        return old;
    }

    @Override
    public V remove(Object key) {
        V old = map.getIfPresent(key);
        map.invalidate(key);
        return old;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        map.putAll(m);
    }

    @Override
    public void clear() {
        map.invalidateAll();
    }

    @Override
    public Set<K> keySet() {
        return map.asMap().keySet();
    }

    @Override
    public Collection<V> values() {
        return map.asMap().values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return map.asMap().entrySet();
    }
}
