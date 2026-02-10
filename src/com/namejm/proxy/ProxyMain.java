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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
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

import static ch.qos.logback.core.util.CloseUtil.closeQuietly;

public class ProxyMain {
    private static final Logger logger = LoggerFactory.getLogger(ProxyMain.class);

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

    private ScheduledExecutorService healthCheckExecutor;
    private ExecutorService healthCheckProbeExecutor;
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
        LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(config.getExecutorQueueCapacityOrDefault());
        RejectedExecutionHandler rejectionHandler = createBlockingPolicy(config.getName(), "acceptWorker", 200L);

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
        RejectedExecutionHandler connectRejectionHandler = createBlockingPolicy(config.getName(), "upstreamConnect", 300L);
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

        int transferCoreThreads = Math.max(8, maxPoolSize * 2);
        int transferMaxThreads = Math.max(32, maxPoolSize * 4);
        transferMaxThreads = Math.min(transferMaxThreads, 256);
        transferCoreThreads = Math.min(transferCoreThreads, transferMaxThreads);
        int transferQueueCapacity = Math.max(256, config.getExecutorQueueCapacityOrDefault() * 2);
        transferQueueCapacity = Math.min(transferQueueCapacity, 4096);
        RejectedExecutionHandler transferRejectionHandler = createBlockingPolicy(config.getName(), "transferPrimary", 200L);
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
        RejectedExecutionHandler fallbackRejectionHandler = createBlockingPolicy(config.getName(), "transferFallback", 100L);
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
    }

    public void start() throws IOException {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.getBindPort()));

            logger.info("Proxy server started on port {}", config.getBindPort());

            if (forwardTargets.size() > 1) {
                startHealthCheckScheduler();
            } else {
                logger.info("{} - Health check scheduler skipped (single forward target)", config.getName());
            }

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
                    logger.warn("{} - Connection rejected: worker queue is full. Closing client socket.", config.getName());
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
            logger.warn("{} - Upstream connect rejected: worker queue is full.", config.getName());
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
            logger.warn("{} - Upstream connection failed: {}", config.getName(), e.getMessage());
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

        for (ForwardTarget target : candidates) {
            try {
                Socket upstreamSocket = connectToTarget(target, config.getForwardConnectTimeoutMillisOrDefault());
                healthTracker.markReachable(target);
                logger.debug("{} - Forward target selected: {} ({}:{})",
                    config.getName(), target.getName(), target.getHost(), target.getPort());
                return upstreamSocket;
            } catch (IOException e) {
                healthTracker.markUnreachable(target);
                lastException = e;
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

        CountDownLatch latch = new CountDownLatch(forwardTargets.size());
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
                        latch.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                latch.countDown();
                if (isRunning) {
                    healthTracker.markUnreachable(target);
                }
            }
        }

        try {
            int timeoutMillis = Math.max(
                config.getHealthCheckConnectTimeoutMillisOrDefault() + 1000,
                1000
            );
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Socket connectToTarget(ForwardTarget target, int connectTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(target.getHost(), target.getPort()), connectTimeoutMillis);
        socket.setSoTimeout(config.getForwardSoTimeoutMillisOrDefault());
        return socket;
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
            logger.warn("{} - Transfer rejected on primary pool. Trying fallback pool.", config.getName());
            submitFallbackRelayTask(relayContext, sourceSocket, destinationSocket, directionName);
        }
    }

    private void submitFallbackRelayTask(RelayContext relayContext, Socket sourceSocket, Socket destinationSocket, String directionName) {
        try {
            transferFallbackExecutor.submit(() -> relayStream(relayContext, sourceSocket, destinationSocket, directionName));
        } catch (RejectedExecutionException ex) {
            logger.warn("{} - Transfer rejected on fallback pool. Closing relay.", config.getName());
            relayContext.closeOnce();
        }
    }

    private void relayStream(RelayContext relayContext, Socket sourceSocket, Socket destinationSocket, String directionName) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = sourceSocket.getInputStream();
            out = destinationSocket.getOutputStream();

            byte[] buffer = new byte[4096];
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
                    halfCloseFuture = transferTimeoutScheduler.schedule(
                        this::closeOnce,
                        config.getHalfCloseLingerSecondsOrDefault(),
                        TimeUnit.SECONDS
                    );
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

        shutdownExecutorGracefully(executorService, "acceptWorkerExecutor");
        shutdownExecutorGracefully(healthCheckProbeExecutor, "healthCheckProbeExecutor");
        shutdownScheduledExecutorGracefully(healthCheckExecutor, "healthCheckExecutor");

        shutdownExecutorGracefully(upstreamConnectExecutor, "upstreamConnectExecutor");
        shutdownExecutorGracefully(transferExecutor, "transferExecutor");
        shutdownExecutorGracefully(transferFallbackExecutor, "transferFallbackExecutor");
        shutdownScheduledExecutorGracefully(transferTimeoutScheduler, "transferTimeoutScheduler");
        relayContexts.forEach((id, relayContext) -> relayContext.closeOnce());
        relayContexts.clear();
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

    private RejectedExecutionHandler createBlockingPolicy(String proxyName, String poolName, long offerTimeoutMillis) {
        return (runnable, executor) -> {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Executor is shut down.");
            }
            try {
                if (!executor.getQueue().offer(runnable, offerTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException(
                        "Queue offer timed out for " + proxyName + ":" + poolName + " after " + offerTimeoutMillis + "ms"
                    );
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while waiting queue space.", e);
            }
        };
    }
}
