package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.channels.spi.SelectorProvider;

import static ch.qos.logback.core.util.CloseUtil.closeQuietly;

public class ProxyMain {
    private static final Logger logger = LoggerFactory.getLogger(ProxyMain.class);
    private static final int RELAY_BUFFER_SIZE = 16 * 1024;
    private static final int WARM_POOL_REFILL_INTERVAL_MS = 500;
    private static final int WARM_POOL_REFILL_BATCH_PER_TARGET = 2;

    private final ProxyDto config;
    private final ExecutorService executorService;
    private final ConnectionPolicy connectionPolicy;
    private final List<ForwardTarget> forwardTargets;
    private final ForwardTargetSelector targetSelector;
    private final TargetHealthTracker healthTracker;
    private final ExecutorService upstreamConnectExecutor;
    private final ExecutorService transferExecutor;
    private final ExecutorService transferFallbackExecutor;
    private final ScheduledExecutorService transferTimeoutScheduler;
    private final ConcurrentHashMap<String, RelayContext> relayContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> warnLogGates = new ConcurrentHashMap<>();
    private final AtomicInteger activeRelayCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, BlockingQueue<Socket>> warmUpstreamPools;
    private final ScheduledExecutorService warmPoolScheduler;
    private final int warmPoolTargetSizePerTarget;
    private final int acceptBacklog;

    private ScheduledExecutorService healthCheckExecutor;
    private ExecutorService healthCheckProbeExecutor;
    private final AtomicBoolean healthCheckCycleRunning = new AtomicBoolean(false);
    private volatile int healthCheckProbeThreads = 1;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = true;

    public ProxyMain(ProxyDto config, InetAddressLocator inetAddressLocator) {
        this.config = config;
        this.connectionPolicy = new ConnectionPolicy(config, inetAddressLocator);
        this.forwardTargets = ForwardTarget.fromConfig(config);
        this.targetSelector = new ForwardTargetSelector(forwardTargets);
        this.healthTracker = new TargetHealthTracker(
            forwardTargets,
            config.getHealthFailThresholdOrDefault(),
            config.getHealthSuccessThresholdOrDefault(),
            logger,
            config.getName()
        );

        int corePoolSize = config.getExecutorCorePoolSizeOrDefault();
        int maxPoolSize = config.getExecutorMaxPoolSizeOrDefault(corePoolSize);
        long keepAliveTime = config.getExecutorKeepAliveSecondsOrDefault();
        this.acceptBacklog = Math.max(256, Math.min(16384, config.getMaxActiveRelaysOrDefault() * 2));
        LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(config.getExecutorQueueCapacityOrDefault());
        RejectedExecutionHandler rejectionHandler = new ThreadPoolExecutor.AbortPolicy();

        this.executorService = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            workQueue,
            rejectionHandler
        );

        int connectCoreThreads = Math.max(2, corePoolSize);
        int connectMaxThreads = Math.max(connectCoreThreads, maxPoolSize * 2);
        int connectQueueCapacity = Math.max(512, config.getExecutorQueueCapacityOrDefault() * 4);
        RejectedExecutionHandler connectRejectionHandler = new ThreadPoolExecutor.AbortPolicy();
        this.upstreamConnectExecutor = new ThreadPoolExecutor(
            connectCoreThreads,
            connectMaxThreads,
            keepAliveTime,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(connectQueueCapacity),
            r -> {
                Thread thread = new Thread(r);
                thread.setName("ProxyUpstreamConnect-" + config.getName());
                thread.setDaemon(true);
                return thread;
            },
            connectRejectionHandler
        );

        int relayHardLimit = Math.max(64, config.getMaxActiveRelaysOrDefault());
        int transferCoreThreads = Math.max(8, Math.min(relayHardLimit, maxPoolSize * 2));
        int transferMaxThreads = Math.max(transferCoreThreads, Math.min(relayHardLimit * 2, maxPoolSize * 8));
        transferMaxThreads = Math.min(transferMaxThreads, 2048);
        transferCoreThreads = Math.min(transferCoreThreads, transferMaxThreads);
        int transferQueueCapacity = Math.max(256, Math.min(relayHardLimit * 4, config.getExecutorQueueCapacityOrDefault() * 4));
        transferQueueCapacity = Math.min(transferQueueCapacity, 16384);
        RejectedExecutionHandler transferRejectionHandler = new ThreadPoolExecutor.AbortPolicy();
        ThreadPoolExecutor transferPool = new ThreadPoolExecutor(
            transferCoreThreads,
            transferMaxThreads,
            keepAliveTime,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(transferQueueCapacity),
            r -> {
                Thread thread = new Thread(r);
                thread.setName("ProxyTransfer-" + config.getName());
                thread.setDaemon(true);
                return thread;
            },
            transferRejectionHandler
        );
        transferPool.allowCoreThreadTimeOut(true);
        this.transferExecutor = transferPool;

        int fallbackCoreThreads = Math.max(2, Math.min(8, maxPoolSize));
        int fallbackMaxThreads = Math.max(fallbackCoreThreads, Math.min(32, maxPoolSize * 2));
        int fallbackQueueCapacity = Math.max(256, config.getExecutorQueueCapacityOrDefault() * 2);
        RejectedExecutionHandler fallbackRejectionHandler = new ThreadPoolExecutor.AbortPolicy();
        this.transferFallbackExecutor = new ThreadPoolExecutor(
            fallbackCoreThreads,
            fallbackMaxThreads,
            keepAliveTime,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(fallbackQueueCapacity),
            r -> {
                Thread thread = new Thread(r);
                thread.setName("ProxyTransferFallback-" + config.getName());
                thread.setDaemon(true);
                return thread;
            },
            fallbackRejectionHandler
        );

        this.transferTimeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("ProxyTransferTimeout-" + config.getName());
            thread.setDaemon(true);
            return thread;
        });

        int candidatePoolSize = Math.max(0, Math.min(32, maxPoolSize));
        if (!forwardTargets.isEmpty() && candidatePoolSize > 0) {
            this.warmPoolTargetSizePerTarget = candidatePoolSize;
            this.warmUpstreamPools = new ConcurrentHashMap<>();
            for (ForwardTarget target : forwardTargets) {
                warmUpstreamPools.put(target.key(), new LinkedBlockingQueue<>(warmPoolTargetSizePerTarget));
            }
            this.warmPoolScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r);
                thread.setName("ProxyWarmPool-" + config.getName());
                thread.setDaemon(true);
                return thread;
            });
        } else {
            this.warmPoolTargetSizePerTarget = 0;
            this.warmUpstreamPools = null;
            this.warmPoolScheduler = null;
        }
    }

    public void start() throws IOException {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.getBindPort()), acceptBacklog);

            logger.info("Proxy server started on port {}", config.getBindPort());
            logger.info("{} - Accept backlog configured: {}", config.getName(), acceptBacklog);
            logger.info("{} - JDK Selector provider: {}", config.getName(), SelectorProvider.provider().getClass().getName());
            if (transferExecutor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) transferExecutor;
                logger.info("{} - Transfer pool configured: core={}, max={}, queueCapacity={}",
                    config.getName(), tpe.getCorePoolSize(), tpe.getMaximumPoolSize(), tpe.getQueue().remainingCapacity() + tpe.getQueue().size());
            }

            if (forwardTargets.size() > 1) {
                startHealthCheckScheduler();
            } else {
                logger.info("{} - Health check scheduler skipped (single forward target)", config.getName());
            }
            startWarmPoolIfNeeded();

            Thread acceptThread = new Thread(this::acceptConnections);
            acceptThread.setName("ProxyAcceptThread-" + config.getBindPort());
            acceptThread.start();
        } catch (IOException e) {
            shutdown();
            throw e;
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    private void acceptConnections() {
        while (isRunning && !Thread.currentThread().isInterrupted()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(config.getClientSoTimeoutMillisOrDefault());

                try {
                    executorService.submit(() -> handleConnection(clientSocket));
                } catch (RejectedExecutionException e) {
                    if (shouldLogWarn("accept-rejected", 3000L)) {
                        logger.warn("{} - Connection rejected: worker queue is full. Closing client socket.", config.getName());
                    }
                    closeQuietly(clientSocket);
                }
            } catch (IOException e) {
                if (isRunning) {
                    logger.error("Error accepting connection", e);
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        boolean delegated = false;
        try {
            ConnectionPolicy.PolicyDecision decision = connectionPolicy.evaluate(clientSocket);
            logConnection(decision);
            if (!decision.isAllowed()) {
                return;
            }

            delegated = submitUpstreamConnectTask(clientSocket, decision.getRemoteAddr());
        } catch (Exception e) {
            logger.error("Connection processing error", e);
        } finally {
            if (!delegated) {
                closeQuietly(clientSocket);
            }
        }
    }

    private boolean submitUpstreamConnectTask(Socket clientSocket, String clientIp) {
        try {
            upstreamConnectExecutor.submit(() -> establishAndRelay(clientSocket, clientIp));
            return true;
        } catch (RejectedExecutionException e) {
            if (shouldLogWarn("upstream-rejected", 3000L)) {
                logger.warn("{} - Upstream connect rejected: worker queue is full.", config.getName());
            }
            return false;
        }
    }

    private void establishAndRelay(Socket clientSocket, String clientIp) {
        Socket upstreamSocket = null;
        boolean relayStarted = false;
        try {
            upstreamSocket = createServerConnection(clientIp);
            startBidirectionalRelay(clientSocket, upstreamSocket);
            relayStarted = true;
        } catch (Exception e) {
            if (shouldLogWarn("upstream-connect-failed", 2000L)) {
                logger.warn("{} - Upstream connection failed: {}", config.getName(), e.getMessage());
            }
        } finally {
            if (!relayStarted) {
                closeQuietly(clientSocket);
                closeQuietly(upstreamSocket);
            }
        }
    }

    private Socket createServerConnection(String clientIp) throws IOException {
        String lbStrategy = config.getLbStrategyOrDefault();
        List<ForwardTarget> candidates = targetSelector.selectCandidates(healthTracker, lbStrategy, clientIp);
        IOException lastException = null;
        int maxConnectAttemptsPerTarget = candidates.size() <= 1 ? 4 : 2;

        for (ForwardTarget target : candidates) {
            for (int attempt = 1; attempt <= maxConnectAttemptsPerTarget; attempt++) {
                try {
                    Socket warm = borrowWarmUpstream(target);
                    if (warm != null) {
                        healthTracker.markReachable(target);
                        logger.debug("{} - Warm pooled target selected: {} ({}:{})",
                            config.getName(), target.getName(), target.getHost(), target.getPort());
                        return warm;
                    }

                    Socket upstreamSocket = connectToTarget(target, config.getForwardConnectTimeoutMillisOrDefault());
                    healthTracker.markReachable(target);
                    logger.debug("{} - Forward target selected: {} ({}:{})",
                        config.getName(), target.getName(), target.getHost(), target.getPort());
                    return upstreamSocket;
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxConnectAttemptsPerTarget) {
                        try {
                            Thread.sleep(Math.min(10L * attempt, 30L));
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while retrying upstream connection", interruptedException);
                        }
                    }
                }
            }
            healthTracker.markUnreachable(target);
            if (shouldLogWarn("target-failed:" + target.key(), 2000L)) {
                logger.warn("{} - Forward target failed: {} ({}:{})",
                    config.getName(), target.getName(), target.getHost(), target.getPort());
            }
        }

        throw new IOException("No available forward target for proxy " + config.getName(), lastException);
    }

    private void startHealthCheckScheduler() {
        int intervalSeconds = config.getLbHealthCheckIntervalSecondsOrDefault();
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("ProxyHealthCheck-" + config.getName());
            thread.setDaemon(true);
            return thread;
        });

        int probeThreads = Math.max(2, Math.min(8, forwardTargets.size()));
        healthCheckProbeThreads = probeThreads;
        healthCheckProbeExecutor = Executors.newFixedThreadPool(probeThreads, r -> {
            Thread thread = new Thread(r);
            thread.setName("ProxyHealthProbe-" + config.getName());
            thread.setDaemon(true);
            return thread;
        });

        healthCheckExecutor.scheduleWithFixedDelay(
            this::runHealthChecks,
            config.getHealthCheckInitialDelaySecondsOrDefault(),
            intervalSeconds,
            TimeUnit.SECONDS
        );
        logger.info("{} - Health check scheduler started (interval: {}s)", config.getName(), intervalSeconds);
    }

    private void runHealthChecks() {
        if (!isRunning) {
            return;
        }
        if (!healthCheckCycleRunning.compareAndSet(false, true)) {
            logger.debug("{} - Previous health check cycle still running. Skipping this cycle.", config.getName());
            return;
        }

        try {
            CountDownLatch latch = new CountDownLatch(forwardTargets.size());
            Set<String> completedTargets = ConcurrentHashMap.newKeySet();
            for (ForwardTarget target : forwardTargets) {
                try {
                    healthCheckProbeExecutor.submit(() -> {
                        try {
                            Socket socket = connectToTarget(target, config.getHealthCheckConnectTimeoutMillisOrDefault());
                            closeQuietly(socket);
                            healthTracker.markReachable(target);
                        } catch (IOException e) {
                            healthTracker.markUnreachable(target);
                        } finally {
                            completedTargets.add(target.key());
                            latch.countDown();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    latch.countDown();
                    healthTracker.markUnreachable(target);
                    logger.warn("{} - Health check task rejected for target {}. Marked as unreachable for this cycle.",
                        config.getName(), target.getName());
                }
            }

            int rounds = Math.max(1, (forwardTargets.size() + healthCheckProbeThreads - 1) / healthCheckProbeThreads);
            int timeoutMillis = Math.max(
                rounds * config.getHealthCheckConnectTimeoutMillisOrDefault() + 1000,
                1000
            );
            boolean completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                int affected = 0;
                for (ForwardTarget target : forwardTargets) {
                    if (!completedTargets.contains(target.key())) {
                        healthTracker.markUnreachable(target);
                        affected++;
                    }
                }
                logger.warn("{} - Health check cycle timed out after {}ms. Marked {} target(s) as unreachable for this cycle.",
                    config.getName(), timeoutMillis, affected);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            healthCheckCycleRunning.set(false);
        }
    }

    private Socket connectToTarget(ForwardTarget target, int connectTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(target.getHost(), target.getPort()), connectTimeoutMillis);
        socket.setSoTimeout(config.getForwardSoTimeoutMillisOrDefault());
        return socket;
    }

    private void startWarmPoolIfNeeded() {
        if (warmPoolScheduler == null || warmPoolTargetSizePerTarget <= 0 || forwardTargets.isEmpty()) {
            return;
        }
        warmPoolScheduler.scheduleWithFixedDelay(this::fillWarmUpstreamPool, 0, WARM_POOL_REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("{} - Warm upstream pool enabled. perTargetSize={}, targets={}",
            config.getName(), warmPoolTargetSizePerTarget, forwardTargets.size());
    }

    private void fillWarmUpstreamPool() {
        if (!isRunning || warmUpstreamPools == null || forwardTargets.isEmpty()) {
            return;
        }

        for (ForwardTarget target : forwardTargets) {
            BlockingQueue<Socket> queue = warmUpstreamPools.get(target.key());
            if (queue == null) {
                continue;
            }
            int deficit = warmPoolTargetSizePerTarget - queue.size();
            if (deficit <= 0) {
                continue;
            }
            int toFill = Math.min(deficit, WARM_POOL_REFILL_BATCH_PER_TARGET);
            for (int i = 0; isRunning && i < toFill; i++) {
                try {
                    Socket socket = connectToTarget(target, config.getForwardConnectTimeoutMillisOrDefault());
                    healthTracker.markReachable(target);
                    if (!queue.offer(socket)) {
                        closeQuietly(socket);
                        break;
                    }
                } catch (Exception e) {
                    if (shouldLogWarn("warm-fill-failed:" + target.key(), 3000L)) {
                        logger.warn("{} - Warm pool fill failed for target {} ({}:{})",
                            config.getName(), target.getName(), target.getHost(), target.getPort());
                    }
                    break;
                }
            }
        }
    }

    private Socket borrowWarmUpstream(ForwardTarget target) {
        if (warmUpstreamPools == null || target == null) {
            return null;
        }
        BlockingQueue<Socket> queue = warmUpstreamPools.get(target.key());
        if (queue == null) {
            return null;
        }
        while (true) {
            Socket socket = queue.poll();
            if (socket == null) {
                return null;
            }
            if (socket.isClosed() || !socket.isConnected()) {
                closeQuietly(socket);
                continue;
            }
            try {
                socket.setSoTimeout(config.getForwardSoTimeoutMillisOrDefault());
                return socket;
            } catch (Exception e) {
                closeQuietly(socket);
            }
        }
    }

    private void logConnection(ConnectionPolicy.PolicyDecision decision) {
        try {
            String remoteAddr = decision.getRemoteAddr();
            int remotePort = decision.getRemotePort();
            String country = decision.getCountryCode();

            boolean allowed = decision.isAllowed();
            if ("UNKNOWN".equals(country)) {
                if (allowed) {
                    logger.debug("{} - Connection ALLOWED - IP: {}, Port: {}", config.getName(), remoteAddr, remotePort);
                } else {
                    logger.debug("{} - Connection BLOCKED - IP: {}, Port: {}", config.getName(), remoteAddr, remotePort);
                }
                return;
            }

            if (allowed) {
                logger.debug("{} - Connection ALLOWED - IP: {}, Port: {}, Country: {}",
                    config.getName(), remoteAddr, remotePort, country);
            } else {
                logger.debug("{} - Connection BLOCKED - IP: {}, Port: {}, Country: {}",
                    config.getName(), remoteAddr, remotePort, country);
            }
        } catch (Exception e) {
            logger.warn("Connection logging error", e);
        }
    }

    private void startBidirectionalRelay(Socket clientSocket, Socket serverSocket) {
        if (!acquireRelaySlot()) {
            if (shouldLogWarn("relay-cap-reached", 1000L)) {
                logger.warn("{} - Active relay limit reached ({}). Closing new connection.",
                    config.getName(), config.getMaxActiveRelaysOrDefault());
            }
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
            return;
        }

        String relayId = config.getName() + "-" + System.nanoTime();
        RelayContext relayContext = new RelayContext(relayId, clientSocket, serverSocket);
        relayContexts.put(relayId, relayContext);

        int transferTimeoutSeconds = config.getTransferTimeoutSecondsOrDefault();
        if (transferTimeoutSeconds > 0) {
            ScheduledFuture<?> timeoutFuture = transferTimeoutScheduler.schedule(
                () -> {
                    if (relayContext.closeOnce()) {
                        logger.warn("{} - Transfer timeout exceeded: {}s", config.getName(), transferTimeoutSeconds);
                    }
                },
                transferTimeoutSeconds,
                TimeUnit.SECONDS
            );
            relayContext.setTimeoutFuture(timeoutFuture);
        }

        submitRelayTask(relayContext, clientSocket, serverSocket, "Client-to-Server");
        submitRelayTask(relayContext, serverSocket, clientSocket, "Server-to-Client");
    }

    private void submitRelayTask(RelayContext relayContext, Socket sourceSocket, Socket destinationSocket, String directionName) {
        try {
            transferExecutor.submit(() -> relayStream(relayContext, sourceSocket, destinationSocket, directionName));
        } catch (RejectedExecutionException e) {
            if (shouldLogWarn("transfer-primary-rejected", 2000L)) {
                logger.warn("{} - Transfer rejected on primary pool. Trying fallback pool.", config.getName());
            }
            submitFallbackRelayTask(relayContext, sourceSocket, destinationSocket, directionName);
        }
    }

    private void submitFallbackRelayTask(RelayContext relayContext, Socket sourceSocket, Socket destinationSocket, String directionName) {
        try {
            transferFallbackExecutor.submit(() -> relayStream(relayContext, sourceSocket, destinationSocket, directionName));
        } catch (RejectedExecutionException ex) {
            if (shouldLogWarn("transfer-fallback-rejected", 2000L)) {
                logger.warn("{} - Transfer rejected on fallback pool. Closing relay.", config.getName());
            }
            relayContext.closeOnce();
        }
    }

    private void relayStream(RelayContext relayContext, Socket sourceSocket, Socket destinationSocket, String directionName) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = sourceSocket.getInputStream();
            out = destinationSocket.getOutputStream();

            byte[] buffer = new byte[RELAY_BUFFER_SIZE];
            int bytesRead;
            while (!relayContext.isClosed() && (bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            if (!relayContext.isClosed()) {
                safeShutdownOutput(destinationSocket);
            }
        } catch (IOException e) {
            if (!relayContext.isClosed() && !(e instanceof SocketException &&
                ("Socket closed".equals(e.getMessage()) || e.getMessage().contains("Broken pipe")))) {
                logger.warn("{} transfer error", directionName, e);
            }
        } finally {
            relayContext.markDirectionCompleted();
        }
    }

    private void safeShutdownOutput(Socket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private final class RelayContext {
        private final String id;
        private final Socket clientSocket;
        private final Socket serverSocket;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean oneDirectionCompleted = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> timeoutFuture;
        private volatile ScheduledFuture<?> halfCloseFuture;

        private RelayContext(String id, Socket clientSocket, Socket serverSocket) {
            this.id = id;
            this.clientSocket = clientSocket;
            this.serverSocket = serverSocket;
        }

        private boolean isClosed() {
            return closed.get();
        }

        private void setTimeoutFuture(ScheduledFuture<?> timeoutFuture) {
            this.timeoutFuture = timeoutFuture;
        }

        private void markDirectionCompleted() {
            if (!oneDirectionCompleted.getAndSet(true)) {
                if (config.getTransferTimeoutSecondsOrDefault() <= 0) {
                    int lingerSeconds = config.getHalfCloseLingerSecondsOrDefault();
                    if (lingerSeconds > 0) {
                        halfCloseFuture = transferTimeoutScheduler.schedule(
                            this::closeOnce,
                            lingerSeconds,
                            TimeUnit.SECONDS
                        );
                    }
                }
                return;
            }
            closeOnce();
        }

        private boolean closeOnce() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
            ScheduledFuture<?> lingerFuture = halfCloseFuture;
            if (lingerFuture != null) {
                lingerFuture.cancel(false);
            }
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
            relayContexts.remove(id);
            activeRelayCount.decrementAndGet();
            return true;
        }
    }

    public ProxyDto getConfig() {
        return config;
    }

    public void shutdown() {
        isRunning = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing server socket", e);
        }

        relayContexts.forEach((id, relayContext) -> relayContext.closeOnce());
        relayContexts.clear();

        shutdownExecutorGracefully(executorService, "acceptWorkerExecutor");
        shutdownExecutorGracefully(healthCheckProbeExecutor, "healthCheckProbeExecutor");
        shutdownScheduledExecutorGracefully(healthCheckExecutor, "healthCheckExecutor");
        shutdownExecutorGracefully(upstreamConnectExecutor, "upstreamConnectExecutor");
        shutdownExecutorGracefully(transferExecutor, "transferExecutor");
        shutdownExecutorGracefully(transferFallbackExecutor, "transferFallbackExecutor");
        shutdownScheduledExecutorGracefully(transferTimeoutScheduler, "transferTimeoutScheduler");
        shutdownScheduledExecutorGracefully(warmPoolScheduler, "warmPoolScheduler");
        clearWarmUpstreamPool();
    }

    private void shutdownExecutorGracefully(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.getShutdownAwaitSecondsOrDefault(), TimeUnit.SECONDS)) {
                logger.warn("{} - executor did not terminate in time. Forcing shutdown.", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownScheduledExecutorGracefully(ScheduledExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.getShutdownAwaitSecondsOrDefault(), TimeUnit.SECONDS)) {
                logger.warn("{} - scheduled executor did not terminate in time. Forcing shutdown.", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldLogWarn(String key, long intervalMillis) {
        long now = System.currentTimeMillis();
        AtomicLong gate = warnLogGates.computeIfAbsent(key, ignored -> new AtomicLong(0L));
        long previous = gate.get();
        return now - previous >= intervalMillis && gate.compareAndSet(previous, now);
    }

    private void clearWarmUpstreamPool() {
        if (warmUpstreamPools == null) {
            return;
        }
        for (Map.Entry<String, BlockingQueue<Socket>> entry : warmUpstreamPools.entrySet()) {
            BlockingQueue<Socket> queue = entry.getValue();
            if (queue == null) {
                continue;
            }
            Socket socket;
            while ((socket = queue.poll()) != null) {
                closeQuietly(socket);
            }
        }
    }

    private boolean acquireRelaySlot() {
        int maxRelays = config.getMaxActiveRelaysOrDefault();
        while (true) {
            int current = activeRelayCount.get();
            if (current >= maxRelays) {
                return false;
            }
            if (activeRelayCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

}
