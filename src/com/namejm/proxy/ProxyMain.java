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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ch.qos.logback.core.util.CloseUtil.closeQuietly;

public class ProxyMain {
    private static final Logger logger = LoggerFactory.getLogger(ProxyMain.class);

    private final ProxyDto config;
    private final ExecutorService executorService;
    private final ConnectionPolicy connectionPolicy;
    private final List<ForwardTarget> forwardTargets;
    private final ForwardTargetSelector targetSelector;
    private final TargetHealthTracker healthTracker;

    private ScheduledExecutorService healthCheckExecutor;
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
        RejectedExecutionHandler rejectionHandler = new ThreadPoolExecutor.AbortPolicy();

        this.executorService = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            workQueue,
            rejectionHandler
        );
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.getBindPort());
        serverSocket.setReuseAddress(true);

        logger.info("Proxy server started on port {}", config.getBindPort());

        startHealthCheckScheduler();

        Thread acceptThread = new Thread(this::acceptConnections);
        acceptThread.setName("ProxyAcceptThread-" + config.getBindPort());
        acceptThread.start();
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
        Socket upstreamSocket = null;
        try {
            boolean connectionAllowed = connectionPolicy.isAllowed(clientSocket);
            logConnection(clientSocket, connectionAllowed);
            if (!connectionAllowed) {
                return;
            }

            upstreamSocket = createServerConnection(clientSocket);
            transferData(clientSocket, upstreamSocket);
        } catch (Exception e) {
            logger.error("Connection processing error", e);
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(upstreamSocket);
        }
    }

    private Socket createServerConnection(Socket clientSocket) throws IOException {
        String clientIp = clientSocket.getInetAddress() != null ? clientSocket.getInetAddress().getHostAddress() : "";
        String lbStrategy = config.getLbStrategyOrDefault();
        List<ForwardTarget> candidates = targetSelector.selectCandidates(healthTracker, lbStrategy, clientIp);
        IOException lastException = null;

        for (ForwardTarget target : candidates) {
            try {
                Socket upstreamSocket = connectToTarget(target, config.getForwardConnectTimeoutMillisOrDefault());
                healthTracker.markReachable(target);
                logger.info("{} - Forward target selected: {} ({}:{})",
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
        healthCheckExecutor.scheduleAtFixedRate(
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

        for (ForwardTarget target : forwardTargets) {
            try {
                Socket socket = connectToTarget(target, config.getHealthCheckConnectTimeoutMillisOrDefault());
                closeQuietly(socket);
                healthTracker.markReachable(target);
            } catch (IOException e) {
                healthTracker.markUnreachable(target);
            }
        }
    }

    private Socket connectToTarget(ForwardTarget target, int connectTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(target.getHost(), target.getPort()), connectTimeoutMillis);
        socket.setSoTimeout(config.getForwardSoTimeoutMillisOrDefault());
        return socket;
    }

    private void logConnection(Socket clientSocket, boolean allowed) {
        try {
            String remoteAddr = clientSocket.getInetAddress().getHostAddress();
            int remotePort = clientSocket.getPort();
            String country = connectionPolicy.resolveCountryCode(remoteAddr);

            if ("UNKNOWN".equals(country)) {
                logger.info("{} - Connection {} - IP: {}, Port: {}",
                    config.getName(),
                    allowed ? "ALLOWED" : "BLOCKED",
                    remoteAddr,
                    remotePort
                );
            } else {
                logger.info("{} - Connection {} - IP: {}, Port: {}, Country: {}",
                    config.getName(),
                    allowed ? "ALLOWED" : "BLOCKED",
                    remoteAddr,
                    remotePort,
                    country
                );
            }
        } catch (Exception e) {
            logger.warn("Connection logging error", e);
        }
    }

    private void transferData(Socket clientSocket, Socket serverSocket) throws Exception {
        Thread clientToServerThread = createDataTransferThread(
            clientSocket.getInputStream(),
            serverSocket.getOutputStream(),
            "Client-to-Server"
        );

        Thread serverToClientThread = createDataTransferThread(
            serverSocket.getInputStream(),
            clientSocket.getOutputStream(),
            "Server-to-Client"
        );

        clientToServerThread.start();
        serverToClientThread.start();

        int transferTimeoutSeconds = config.getTransferTimeoutSecondsOrDefault();
        if (transferTimeoutSeconds <= 0) {
            clientToServerThread.join();
            serverToClientThread.join();
            return;
        }

        long timeoutMillis = transferTimeoutSeconds * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMillis;

        joinUntilDeadline(clientToServerThread, deadline);
        joinUntilDeadline(serverToClientThread, deadline);

        if (clientToServerThread.isAlive() || serverToClientThread.isAlive()) {
            clientToServerThread.interrupt();
            serverToClientThread.interrupt();
            throw new SocketException("Transfer timeout exceeded: " + transferTimeoutSeconds + "s");
        }
    }

    private void joinUntilDeadline(Thread thread, long deadlineMillis) throws InterruptedException {
        long remaining = deadlineMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        thread.join(remaining);
    }

    private Thread createDataTransferThread(InputStream in, OutputStream out, String threadName) {
        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();

                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!(e instanceof SocketException &&
                    (e.getMessage().equals("Socket closed") || e.getMessage().contains("Broken pipe")))) {
                    logger.warn("{} transfer error", threadName, e);
                }
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    logger.warn("Error closing input stream", e);
                }

                try {
                    out.close();
                } catch (IOException e) {
                    logger.warn("Error closing output stream", e);
                }
            }
        }, threadName);
        thread.setDaemon(true);
        return thread;
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

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(config.getShutdownAwaitSecondsOrDefault(), TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
        }
    }
}
