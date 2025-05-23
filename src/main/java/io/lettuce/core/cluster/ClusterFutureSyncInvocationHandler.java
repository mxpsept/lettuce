/*
 * Copyright 2016-Present, Redis Ltd. and Contributors
 * All rights reserved.
 *
 * Licensed under the MIT License.
 *
 * This file contains contributions from third-party contributors
 * licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.cluster;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.NodeSelectionSupport;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.internal.AbstractInvocationHandler;
import io.lettuce.core.internal.DefaultMethods;
import io.lettuce.core.internal.Futures;
import io.lettuce.core.internal.TimeoutProvider;
import io.lettuce.core.protocol.ConnectionIntent;
import io.lettuce.core.protocol.RedisCommand;

/**
 * Invocation-handler to synchronize API calls which use Futures as backend. This class leverages the need to implement a full
 * sync class which just delegates every request.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 3.0
 */
@SuppressWarnings("unchecked")
class ClusterFutureSyncInvocationHandler<K, V> extends AbstractInvocationHandler {

    private final StatefulConnection<K, V> connection;

    private final TimeoutProvider timeoutProvider;

    private final Class<?> asyncCommandsInterface;

    private final Class<?> nodeSelectionInterface;

    private final Class<?> nodeSelectionCommandsInterface;

    private final Object asyncApi;

    private final Map<Method, Method> apiMethodCache = new ConcurrentHashMap<>(RedisClusterCommands.class.getMethods().length,
            1);

    private final Map<Method, Method> connectionMethodCache = new ConcurrentHashMap<>(5, 1);

    private final Map<Method, MethodHandle> methodHandleCache = new ConcurrentHashMap<>(5, 1);

    ClusterFutureSyncInvocationHandler(StatefulConnection<K, V> connection, Class<?> asyncCommandsInterface,
            Class<?> nodeSelectionInterface, Class<?> nodeSelectionCommandsInterface, Object asyncApi) {
        this.connection = connection;
        this.timeoutProvider = new TimeoutProvider(() -> connection.getOptions().getTimeoutOptions(),
                () -> connection.getTimeout().toNanos());
        this.asyncCommandsInterface = asyncCommandsInterface;
        this.nodeSelectionInterface = nodeSelectionInterface;
        this.nodeSelectionCommandsInterface = nodeSelectionCommandsInterface;
        this.asyncApi = asyncApi;
    }

    /**
     * @see AbstractInvocationHandler#handleInvocation(Object, Method, Object[])
     */
    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {

        try {

            // 如果方法是默认方法，则从缓存中查找并调用
            if (method.isDefault()) {
                return methodHandleCache.computeIfAbsent(method, ClusterFutureSyncInvocationHandler::lookupDefaultMethod)
                        .bindTo(proxy).invokeWithArguments(args);
            }

            // 如果方法是getConnection，并且参数长度大于0，则调用getConnection方法
            if (method.getName().equals("getConnection") && args.length > 0) {
                return getConnection(method, args);
            }

            // 如果方法是readonly，并且参数长度为1，则调用nodes方法，连接意图为READ
            if (method.getName().equals("readonly") && args.length == 1) {
                return nodes((Predicate<RedisClusterNode>) args[0], ConnectionIntent.READ, false);
            }

            // 如果方法是nodes，并且参数长度为1，则调用nodes方法，连接意图为WRITE
            if (method.getName().equals("nodes") && args.length == 1) {
                return nodes((Predicate<RedisClusterNode>) args[0], ConnectionIntent.WRITE, false);
            }

            // 如果方法是nodes，并且参数长度为2，则调用nodes方法，连接意图为WRITE，第二个参数为Boolean类型
            if (method.getName().equals("nodes") && args.length == 2) {
                return nodes((Predicate<RedisClusterNode>) args[0], ConnectionIntent.WRITE, (Boolean) args[1]);
            }

            // 从缓存中查找目标方法
            Method targetMethod = apiMethodCache.computeIfAbsent(method, key -> {

                try {
                    return asyncApi.getClass().getMethod(key.getName(), key.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            });

            // 调用目标方法
            Object result = targetMethod.invoke(asyncApi, args);

            // 如果返回结果为RedisFuture类型，则等待结果
            // 判断result是否为RedisFuture类型
            if (result instanceof RedisFuture) {
                // 将result转换为RedisFuture类型
                RedisFuture<?> command = (RedisFuture<?>) result;

                if (!method.getName().equals("exec") && !method.getName().equals("multi")) {
                    // 判断connection是否为StatefulRedisConnection类型，并且是否处于multi状态
                    if (connection instanceof StatefulRedisConnection && ((StatefulRedisConnection) connection).isMulti()) {
                        // 如果是，则返回null
                        return null;
                    }
                }
                // 否则，调用Futures.awaitOrCancel方法，等待command执行完成，或者取消
                return Futures.awaitOrCancel(command, getTimeoutNs(command), TimeUnit.NANOSECONDS);
            }

            // 返回结果
            return result;

        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private long getTimeoutNs(RedisFuture<?> command) {

        if (command instanceof RedisCommand) {
            return timeoutProvider.getTimeoutNs((RedisCommand) command);
        }

        return connection.getTimeout().toNanos();
    }

    private Object getConnection(Method method, Object[] args) throws Exception {

        Method targetMethod = connectionMethodCache.computeIfAbsent(method, this::lookupMethod);

        Object result = targetMethod.invoke(connection, args);
        if (result instanceof StatefulRedisClusterConnection) {
            StatefulRedisClusterConnection<K, V> connection = (StatefulRedisClusterConnection<K, V>) result;
            return connection.sync();
        }

        if (result instanceof StatefulRedisConnection) {
            StatefulRedisConnection<K, V> connection = (StatefulRedisConnection<K, V>) result;
            return connection.sync();
        }

        throw new IllegalArgumentException("Cannot call method " + method);
    }

    private Method lookupMethod(Method key) {
        try {
            return connection.getClass().getMethod(key.getName(), key.getParameterTypes());
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }
    }

    protected Object nodes(Predicate<RedisClusterNode> predicate, ConnectionIntent connectionIntent, boolean dynamic) {

        NodeSelectionSupport<RedisCommands<K, V>, ?> selection = null;

        if (connection instanceof StatefulRedisClusterConnectionImpl) {

            StatefulRedisClusterConnectionImpl<K, V> impl = (StatefulRedisClusterConnectionImpl<K, V>) connection;

            if (dynamic) {
                selection = new DynamicNodeSelection<RedisCommands<K, V>, Object, K, V>(
                        impl.getClusterDistributionChannelWriter(), predicate, connectionIntent, StatefulRedisConnection::sync);
            } else {

                selection = new StaticNodeSelection<RedisCommands<K, V>, Object, K, V>(
                        impl.getClusterDistributionChannelWriter(), predicate, connectionIntent, StatefulRedisConnection::sync);
            }
        }

        if (connection instanceof StatefulRedisClusterPubSubConnectionImpl) {

            StatefulRedisClusterPubSubConnectionImpl<K, V> impl = (StatefulRedisClusterPubSubConnectionImpl<K, V>) connection;
            selection = new StaticNodeSelection<RedisCommands<K, V>, Object, K, V>(impl.getClusterDistributionChannelWriter(),
                    predicate, connectionIntent, StatefulRedisConnection::sync);
        }

        NodeSelectionInvocationHandler h = new NodeSelectionInvocationHandler((AbstractNodeSelection<?, ?, ?, ?>) selection,
                asyncCommandsInterface, timeoutProvider);
        return Proxy.newProxyInstance(NodeSelectionSupport.class.getClassLoader(),
                new Class<?>[] { nodeSelectionCommandsInterface, nodeSelectionInterface }, h);
    }

    private static MethodHandle lookupDefaultMethod(Method method) {

        try {
            return DefaultMethods.lookupMethodHandle(method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
