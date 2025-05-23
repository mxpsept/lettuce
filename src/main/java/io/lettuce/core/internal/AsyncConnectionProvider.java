package io.lettuce.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Non-blocking provider for connection objects. This connection provider is typed with a connection type and connection key
 * type.
 * <p>
 * {@link #getConnection(Object)} Connection requests} are synchronized with a shared {@link Sync synchronzer object} per
 * {@code ConnectionKey}. Multiple threads requesting a connection for the same {@code ConnectionKey} share the same
 * synchronizer and are not required to wait until a previous asynchronous connection is established but participate in existing
 * connection initializations. Shared synchronization leads to a fair synchronization amongst multiple threads waiting to obtain
 * a connection.
 *
 * @author Mark Paluch
 * @param <T> connection type.
 * @param <K> connection key type.
 * @param <F> type of the {@link CompletionStage} handle of the connection progress.
 * @since 5.1
 */
public class AsyncConnectionProvider<K, T extends AsyncCloseable, F extends CompletionStage<T>> {

    private final Function<K, F> connectionFactory;

    private final Map<K, Sync<K, T, F>> connections = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /**
     * Create a new {@link AsyncConnectionProvider}.
     *
     * @param connectionFactory must not be {@code null}.
     */
    @SuppressWarnings("unchecked")
    public AsyncConnectionProvider(Function<? extends K, ? extends F> connectionFactory) {

        LettuceAssert.notNull(connectionFactory, "AsyncConnectionProvider must not be null");
        this.connectionFactory = (Function<K, F>) connectionFactory;
    }

    /**
     * Request a connection for the given the connection {@code key} and return a {@link CompletionStage} that is notified about
     * the connection outcome.
     *
     * @param key the connection {@code key}, must not be {@code null}.
     * @return
     */
    public F getConnection(K key) {
        return getSynchronizer(key).getConnection();
    }

    /**
     * Obtain a connection to a target given the connection {@code key}.
     *
     * @param key the connection {@code key}.
     * @return
     */
    private Sync<K, T, F> getSynchronizer(K key) {

        // 如果连接提供者已经关闭，则抛出异常
        if (closed) {
            throw new IllegalStateException("ConnectionProvider is already closed");
        }

        // 从connections中获取key对应的Sync对象
        Sync<K, T, F> sync = connections.get(key);

        // 如果connections中存在key对应的Sync对象，则直接返回
        if (sync != null) {
            return sync;
        }

        // 创建一个AtomicBoolean对象，用于标记是否已经创建了Sync对象
        AtomicBoolean atomicBoolean = new AtomicBoolean();

        // 如果connections中不存在key对应的Sync对象，则创建一个新的Sync对象，并放入connections中
        sync = connections.computeIfAbsent(key, connectionKey -> {

            // 创建一个新的Sync对象
            Sync<K, T, F> createdSync = new Sync<>(key, connectionFactory.apply(key));

            // 如果连接提供者已经关闭，则取消创建的Sync对象
            if (closed) {
                createdSync.cancel();
            }

            // 返回创建的Sync对象
            return createdSync;
        });

        // 如果atomicBoolean的值为false，则将其设置为true，并执行以下操作
        if (atomicBoolean.compareAndSet(false, true)) {

            // 当Sync对象的连接完成时，执行以下操作
            sync.getConnection().whenComplete((c, t) -> {

                // 如果连接失败，则从connections中移除key对应的Sync对象
                if (t != null) {
                    connections.remove(key);
                }
            });
        }

        // 返回Sync对象
        return sync;
    }

    /**
     * Register a connection identified by {@code key}. Overwrites existing entries.
     *
     * @param key the connection {@code key}.
     * @param connection the connection object.
     */
    public void register(K key, T connection) {
        connections.put(key, new Sync<>(key, connection));
    }

    /**
     * @return number of established connections.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public int getConnectionCount() {

        Sync[] syncs = connections.values().toArray(new Sync[0]);
        int count = 0;

        for (Sync sync : syncs) {
            if (sync.isComplete()) {
                count++;
            }
        }

        return count;
    }

    /**
     * Close all connections. Pending connections are closed using future chaining.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> close() {

        this.closed = true;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        forEach((connectionKey, closeable) -> {

            futures.add(closeable.closeAsync());
            connections.remove(connectionKey);
        });

        return Futures.allOf(futures);
    }

    /**
     * Close a connection by its connection {@code key}. Pending connections are closed using future chaining.
     *
     * @param key the connection {@code key}, must not be {@code null}.
     */
    public void close(K key) {

        LettuceAssert.notNull(key, "ConnectionKey must not be null!");

        Sync<K, T, F> sync = connections.get(key);
        if (sync != null) {
            connections.remove(key);
            sync.doWithConnection(AsyncCloseable::closeAsync);
        }
    }

    /**
     * Execute an action for all established and pending connections.
     *
     * @param action the action.
     */
    public void forEach(Consumer<? super T> action) {

        LettuceAssert.notNull(action, "Action must not be null!");

        connections.values().forEach(sync -> {
            if (sync != null) {
                sync.doWithConnection(action);
            }
        });
    }

    /**
     * Execute an action for all established and pending {@link AsyncCloseable}s.
     *
     * @param action the action.
     */
    public void forEach(BiConsumer<? super K, ? super T> action) {
        connections.forEach((key, sync) -> sync.doWithConnection(action));
    }

    static class Sync<K, T extends AsyncCloseable, F extends CompletionStage<T>> {

        private static final int PHASE_IN_PROGRESS = 0;

        private static final int PHASE_COMPLETE = 1;

        private static final int PHASE_FAILED = 2;

        private static final int PHASE_CANCELED = 3;

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static final AtomicIntegerFieldUpdater<Sync> PHASE = AtomicIntegerFieldUpdater.newUpdater(Sync.class, "phase");

        // Updated with AtomicIntegerFieldUpdater
        @SuppressWarnings("unused")
        private volatile int phase = PHASE_IN_PROGRESS;

        private volatile T connection;

        private final K key;

        private final F future;

        @SuppressWarnings("unchecked")
        public Sync(K key, F future) {

            this.key = key;
            this.future = (F) future.whenComplete((connection, throwable) -> {

                if (throwable != null) {

                    if (throwable instanceof CancellationException) {
                        PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_CANCELED);
                    }

                    PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_FAILED);
                }

                if (PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_COMPLETE)) {

                    if (connection != null) {
                        Sync.this.connection = connection;
                    }
                }
            });
        }

        @SuppressWarnings("unchecked")
        public Sync(K key, T value) {

            this.key = key;
            this.connection = value;
            this.future = (F) CompletableFuture.completedFuture(value);
            PHASE.set(this, PHASE_COMPLETE);
        }

        public void cancel() {
            future.toCompletableFuture().cancel(false);
            doWithConnection(AsyncCloseable::closeAsync);
        }

        public F getConnection() {
            return future;
        }

        void doWithConnection(Consumer<? super T> action) {

            if (isComplete()) {
                action.accept(connection);
            } else {
                future.thenAccept(action);
            }
        }

        void doWithConnection(BiConsumer<? super K, ? super T> action) {

            if (isComplete()) {
                action.accept(key, connection);
            } else {
                future.thenAccept(c -> action.accept(key, c));
            }
        }

        private boolean isComplete() {
            return PHASE.get(this) == PHASE_COMPLETE;
        }

    }

}
