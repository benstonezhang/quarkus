package io.quarkus.cache.redis.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.cache.CacheException;
import io.quarkus.cache.CompositeCacheKey;
import io.quarkus.cache.runtime.AbstractCache;
import io.quarkus.redis.client.RedisClientName;
import io.quarkus.redis.runtime.datasource.Marshaller;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.smallrye.mutiny.unchecked.UncheckedFunction;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;

/**
 * This class is an internal Quarkus cache implementation using Redis.
 * Do not use it explicitly from your Quarkus application.
 */
public class RedisCacheImpl<K, V> extends AbstractCache implements RedisCache {

    private static final Map<String, Class<?>> PRIMITIVE_TO_CLASS_MAPPING = Map.of(
            "int", Integer.class,
            "byte", Byte.class,
            "char", Character.class,
            "short", Short.class,
            "long", Long.class,
            "float", Float.class,
            "double", Double.class,
            "boolean", Boolean.class);

    private final Redis redis;

    private final RedisCacheInfo cacheInfo;
    private final Class<V> classOfValue;
    private final Class<K> classOfKey;

    private final Marshaller marshaller;

    public RedisCacheImpl(RedisCacheInfo cacheInfo, Optional<String> redisClientName) {

        this(cacheInfo, determineRedisClient(redisClientName));
    }

    private static Redis determineRedisClient(Optional<String> redisClientName) {
        ArcContainer container = Arc.container();
        if (redisClientName.isPresent()) {
            return container.select(Redis.class, RedisClientName.Literal.of(redisClientName.get())).get();
        } else {
            return container.select(Redis.class).get();
        }
    }

    @SuppressWarnings("unchecked")
    public RedisCacheImpl(RedisCacheInfo cacheInfo, Redis redis) {
        this.cacheInfo = cacheInfo;

        try {
            this.classOfKey = (Class<K>) loadClass(this.cacheInfo.keyType);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unable to load the class  " + this.cacheInfo.keyType, e);
        }

        if (this.cacheInfo.valueType != null) {
            try {
                this.classOfValue = (Class<V>) loadClass(this.cacheInfo.valueType);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Unable to load the class  " + this.cacheInfo.valueType, e);
            }
            this.marshaller = new Marshaller(this.classOfValue, this.classOfKey);
        } else {
            this.classOfValue = null;
            this.marshaller = new Marshaller(this.classOfKey);
        }
        this.marshaller.add(CompositeCacheKey.class);
        this.redis = redis;
    }

    private Class<?> loadClass(String type) throws ClassNotFoundException {
        if (PRIMITIVE_TO_CLASS_MAPPING.containsKey(type)) {
            return PRIMITIVE_TO_CLASS_MAPPING.get(type);
        }
        return Thread.currentThread().getContextClassLoader().loadClass(type);
    }

    @Override
    public String getName() {
        return Objects.requireNonNullElse(cacheInfo.name, "default-redis-cache");
    }

    @Override
    public Object getDefaultKey() {
        return "default-cache-key";
    }

    @Override
    public Class<?> getDefaultValueType() {
        return classOfValue;
    }

    private <K> String encodeKey(K key) {
        return new String(marshaller.encode(key), StandardCharsets.UTF_8);
    }

    @Override
    public <K, V> Uni<V> get(K key, Class<V> clazz, Function<K, V> valueLoader) {
        // With optimistic locking:
        // WATCH K
        // val = deserialize(GET K)
        // If val == null
        // MULTI
        //    SET K computation.apply(K)
        // EXEC
        // Without:
        // val = deserialize(GET K)
        // if (val == null) => SET K computation.apply(K)
        byte[] encodedKey = marshaller.encode(computeActualKey(encodeKey(key)));
        return withConnection(new Function<RedisConnection, Uni<V>>() {
            @Override
            public Uni<V> apply(RedisConnection connection) {
                return watch(connection, encodedKey)
                        .chain(new GetFromConnectionSupplier<>(connection, clazz, encodedKey, marshaller))
                        .chain(Unchecked.function(new UncheckedFunction<>() {
                            @Override
                            public Uni<V> apply(V cached) throws Exception {
                                if (cached != null) {
                                    return Uni.createFrom().item(new StaticSupplier<>(cached));
                                } else {
                                    V value = valueLoader.apply(key);
                                    if (value == null) {
                                        throw new IllegalArgumentException("Cannot cache `null` value");
                                    }
                                    byte[] encodedValue = marshaller.encode(value);
                                    if (cacheInfo.useOptimisticLocking) {
                                        return multi(connection, set(connection, encodedKey, encodedValue))
                                                .replaceWith(value);
                                    } else {
                                        return set(connection, encodedKey, encodedValue).replaceWith(value);
                                    }
                                }
                            }
                        }));
            }
        });
    }

    @Override
    public <K, V> Uni<V> getAsync(K key, Class<V> clazz, Function<K, Uni<V>> valueLoader) {
        byte[] encodedKey = marshaller.encode(computeActualKey(encodeKey(key)));
        return withConnection(new Function<RedisConnection, Uni<V>>() {
            @Override
            public Uni<V> apply(RedisConnection connection) {
                return watch(connection, encodedKey)
                        .chain(new GetFromConnectionSupplier<>(connection, clazz, encodedKey, marshaller))
                        .chain(cached -> {
                            if (cached != null) {
                                return Uni.createFrom().item(new StaticSupplier<>(cached));
                            } else {
                                Uni<V> getter = valueLoader.apply(key);
                                return getter
                                        .chain(value -> {
                                            byte[] encodedValue = marshaller.encode(value);
                                            if (cacheInfo.useOptimisticLocking) {
                                                return multi(connection, set(connection, encodedKey, encodedValue))
                                                        .replaceWith(value);
                                            } else {
                                                return set(connection, encodedKey, encodedValue)
                                                        .replaceWith(value);
                                            }
                                        });
                            }
                        });
            }
        });
    }

    @Override
    public <K, V> Uni<Void> put(K key, V value) {
        return put(key, new StaticSupplier<>(value));
    }

    @Override
    public <K, V> Uni<Void> put(K key, Supplier<V> supplier) {
        byte[] encodedKey = marshaller.encode(computeActualKey(encodeKey(key)));
        byte[] encodedValue = marshaller.encode(supplier.get());
        return withConnection(new Function<RedisConnection, Uni<Void>>() {
            @Override
            public Uni<Void> apply(RedisConnection connection) {
                return set(connection, encodedKey, encodedValue);
            }
        });
    }

    private void enforceDefaultType() {
        if (classOfValue == null) {
            throw new UnsupportedOperationException(
                    "Cannot execute the operation without the default type configured in cache " + cacheInfo.name);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> Uni<V> getOrDefault(K key, V defaultValue) {
        enforceDefaultType();
        byte[] encodedKey = marshaller.encode(computeActualKey(encodeKey(key)));
        return withConnection(new Function<RedisConnection, Uni<V>>() {
            @Override
            public Uni<V> apply(RedisConnection redisConnection) {
                return (Uni<V>) doGet(redisConnection, encodedKey, classOfValue, marshaller);
            }
        }).onItem().ifNull().continueWith(new StaticSupplier<>(defaultValue));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Uni<V> getOrNull(K key, Class<V> clazz) {
        enforceDefaultType();
        byte[] encodedKey = marshaller.encode(computeActualKey(encodeKey(key)));
        return withConnection(new Function<RedisConnection, Uni<V>>() {
            @Override
            public Uni<V> apply(RedisConnection redisConnection) {
                return (Uni<V>) doGet(redisConnection, encodedKey, classOfValue, marshaller);
            }
        });
    }

    @Override
    public Uni<Void> invalidate(Object key) {
        byte[] encodedKey = marshaller.encode(computeActualKey(encodeKey(key)));
        return redis.send(Request.cmd(Command.DEL).arg(encodedKey))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidateAll() {
        return invalidateIf(AlwaysTruePredicate.INSTANCE);
    }

    @Override
    public Uni<Void> invalidateIf(Predicate<Object> predicate) {
        return redis.send(Request.cmd(Command.KEYS).arg(getKeyPattern()))
                .map(response -> marshaller.decodeAsList(response, String.class))
                .chain(new Function<List<String>, Uni<?>>() {
                    @Override
                    public Uni<?> apply(List<String> listOfKeys) {
                        var req = Request.cmd(Command.DEL);
                        boolean hasAtLEastOneMatch = false;
                        for (String key : listOfKeys) {
                            K userKey = computeUserKey(key);
                            if (predicate.test(userKey)) {
                                hasAtLEastOneMatch = true;
                                req.arg(marshaller.encode(key));
                            }
                        }
                        if (hasAtLEastOneMatch) {
                            // We cannot send the command with parameters, it would not be a valid command.
                            return redis.send(req);
                        } else {
                            return Uni.createFrom().voidItem();
                        }
                    }
                })
                .replaceWithVoid();
    }

    String computeActualKey(String key) {
        if (cacheInfo.prefix != null) {
            return cacheInfo.prefix + ":" + key;
        } else {
            return "cache:" + getName() + ":" + key;
        }
    }

    K computeUserKey(String key) {
        String prefix = cacheInfo.prefix != null ? cacheInfo.prefix : "cache:" + getName();
        if (!key.startsWith(prefix + ":")) {
            return null; // Not a key handle by the cache.
        }
        String stripped = key.substring(prefix.length() + 1);
        return marshaller.decode(classOfKey, stripped.getBytes(StandardCharsets.UTF_8));
    }

    private String getKeyPattern() {
        if (cacheInfo.prefix != null) {
            return cacheInfo.prefix + ":" + "*";
        } else {
            return "cache:" + getName() + ":" + "*";
        }
    }

    private <X> Uni<X> withConnection(Function<RedisConnection, Uni<X>> function) {
        return redis.connect()
                .chain(new Function<RedisConnection, Uni<? extends X>>() {
                    @Override
                    public Uni<X> apply(RedisConnection con) {
                        Uni<X> res;
                        try {
                            res = function.apply(con);
                        } catch (Exception e) {
                            res = Uni.createFrom().failure(new CacheException(e));
                        }
                        return res
                                .onTermination().call(con::close);
                    }
                });
    }

    private Uni<Void> watch(RedisConnection connection, byte[] keyToWatch) {
        return connection.send(Request.cmd(Command.WATCH).arg(keyToWatch))
                .replaceWithVoid();
    }

    private static <X> Uni<X> doGet(RedisConnection connection1, byte[] encodedKey1, Class<X> clazz,
            Marshaller marshaller) {
        return connection1.send(Request.cmd(Command.GET).arg(encodedKey1))
                .map(new Function<Response, X>() {
                    @Override
                    public X apply(Response r) {
                        return marshaller.decode(clazz, r);
                    }
                });
    }

    private Uni<Void> set(RedisConnection connection, byte[] key, byte[] value) {
        Request request = Request.cmd(Command.SET).arg(key).arg(value);
        if (cacheInfo.ttl.isPresent()) {
            request = request.arg("EX").arg(cacheInfo.ttl.get().toSeconds());
        }
        return connection.send(request).replaceWithVoid();
    }

    private Uni<Void> multi(RedisConnection connection, Uni<Void> operation) {
        return connection.send(Request.cmd(Command.MULTI))
                .chain(() -> operation)
                .onFailure().call(() -> abort(connection))
                .call(() -> exec(connection));
    }

    private Uni<Void> exec(RedisConnection connection) {
        return connection.send(Request.cmd(Command.EXEC))
                .replaceWithVoid();
    }

    private Uni<Void> abort(RedisConnection connection) {
        return connection.send(Request.cmd(Command.DISCARD))
                .replaceWithVoid();
    }

    private static class StaticSupplier<V> implements Supplier<V> {
        private final V cached;

        public StaticSupplier(V cached) {
            this.cached = cached;
        }

        @Override
        public V get() {
            return cached;
        }
    }

    private static class GetFromConnectionSupplier<V> implements Supplier<Uni<? extends V>> {
        private final RedisConnection connection;
        private final Class<V> clazz;
        private final byte[] encodedKey;
        private final Marshaller marshaller;

        public GetFromConnectionSupplier(RedisConnection connection, Class<V> clazz, byte[] encodedKey, Marshaller marshaller) {
            this.connection = connection;
            this.clazz = clazz;
            this.encodedKey = encodedKey;
            this.marshaller = marshaller;
        }

        @Override
        public Uni<V> get() {
            return doGet(connection, encodedKey, clazz, marshaller);
        }
    }

    private static class AlwaysTruePredicate implements Predicate<Object> {

        public static AlwaysTruePredicate INSTANCE = new AlwaysTruePredicate();

        private AlwaysTruePredicate() {
        }

        @Override
        public boolean test(Object o) {
            return true;
        }
    }
}
