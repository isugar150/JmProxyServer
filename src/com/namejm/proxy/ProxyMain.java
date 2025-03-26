package com.namejm.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.RequiredArgsConstructor;

import static ch.qos.logback.core.util.CloseUtil.closeQuietly;

@RequiredArgsConstructor
public class ProxyMain {
    private static final Logger logger = LoggerFactory.getLogger(ProxyMain.class);

    private ProxyDto config;
    private ExecutorService executorService;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = true;

    public ProxyMain(ProxyDto config) {
        this.config = config;
    }

    public void start() throws IOException {
        // 스레드 풀 설정
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = corePoolSize * 2;
        long keepAliveTime = 60L;

        // 대기 큐와 거부 핸들러
        LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(500);
        RejectedExecutionHandler rejectionHandler = new ThreadPoolExecutor.CallerRunsPolicy();

        executorService = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            workQueue,
            rejectionHandler
        );

        // 서버 소켓 생성 및 설정
        serverSocket = new ServerSocket(config.getBindPort());
        serverSocket.setReuseAddress(true);

        logger.info("Proxy server started on port {} with thread pool: core={}, max={}",
            config.getBindPort(), corePoolSize, maxPoolSize);

        // 연결 수락 스레드
        Thread acceptThread = new Thread(this::acceptConnections);
        acceptThread.setName("ProxyAcceptThread-" + config.getBindPort());
        acceptThread.start();
    }

    private void acceptConnections() {
        while (isRunning && !Thread.currentThread().isInterrupted()) {
            try {
                // 연결 수락
                Socket clientSocket = serverSocket.accept();

                // 타임아웃 설정
                clientSocket.setSoTimeout(30000);

                executorService.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (isRunning) {
                    logger.error("Error accepting connection", e);
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        Socket serverSocket = null;
        boolean connectionAllowed = false;

        try {
            // 연결 허용 체크
            connectionAllowed = isAllowedConnection(clientSocket);

            logConnection(clientSocket, connectionAllowed);

            if (!connectionAllowed) {
                return;
            }
            serverSocket = createServerConnection();

            transferData(clientSocket, serverSocket);

        } catch (Exception e) {
            logger.error("Connection processing error", e);
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
        }
    }

    private Socket createServerConnection() throws IOException {
        Socket serverSocket = new Socket(
            config.getForwardHost(),
            config.getForwardPort()
        );

        serverSocket.setSoTimeout(30000);

        return serverSocket;
    }

    private void logConnection(Socket clientSocket, boolean allowed) {
        try {
            String remoteAddr = clientSocket.getInetAddress().getHostAddress();
            int remotePort = clientSocket.getPort();

            Locale locale = InetAddressLocator.getLocale(remoteAddr);
            String country = locale.getCountry();

            logger.info("Connection {} - IP: {}, Port: {}, Country: {}",
                allowed ? "ALLOWED" : "BLOCKED",
                remoteAddr,
                remotePort,
                country
            );
        } catch (Exception e) {
            logger.warn("Connection logging error", e);
        }
    }

    private void transferData(Socket clientSocket, Socket serverSocket) throws Exception {
        // 데이터 전송 스레드 생성
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

        // 스레드 시작
        clientToServerThread.start();
        serverToClientThread.start();

        // 스레드 종료 대기 (타임아웃 포함)
        clientToServerThread.join(30000);
        serverToClientThread.join(30000);
    }

    // 기존의 데이터 전송 스레드 메서드 유지

    private boolean isAllowedConnection(Socket clientSocket) {
        String remoteAddr = clientSocket.getInetAddress().getHostAddress();

        try {
            // 국가 확인
            Locale locale = InetAddressLocator.getLocale(remoteAddr);
            String country = locale.getCountry();

            // 허용 조건 체크
            for (String allowedCondition : config.getAllowedCountries()) {
                switch (allowedCondition) {
                    case "Any":
                        return true;
                    case "localhost":
                        if (remoteAddr.equals("127.0.0.1")) return true;
                        break;
                    case "private":
                        if (isPrivateIP(remoteAddr)) return true;
                        break;
                    default:
                        // 국가 코드 확인
                        if (allowedCondition.equals(country)) return true;
                }
            }

            return false;
        } catch (Exception e) {
            logger.warn("Connection check failed for IP: {}", remoteAddr, e);
            return false;
        }
    }

    private boolean isPrivateIP(String ipAddress) {
        try {
            // 로컬 네트워크 대역 체크
            return ipAddress.startsWith("192.168.") ||
                   ipAddress.startsWith("10.") ||
                   ipAddress.startsWith("172.16.") ||
                   ipAddress.startsWith("127.0.0.1") ||
                   isInSubnet(ipAddress, "192.168.0.0/24");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInSubnet(String ip, String subnet) {
        String[] parts = subnet.split("/");
        String netAddress = parts[0];
        int subnetBits = Integer.parseInt(parts[1]);

        long ipLong = ipToLong(ip);
        long netAddressLong = ipToLong(netAddress);
        long mask = (-1L) << (32 - subnetBits);

        return (ipLong & mask) == (netAddressLong & mask);
    }

    private long ipToLong(String ipAddress) {
        String[] octets = ipAddress.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= Long.parseLong(octets[i]) << (24 - (8 * i));
        }
        return result;
    }

    private Thread createDataTransferThread(
        InputStream in,
        OutputStream out,
        String threadName
    ) {
        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();

                    // 스레드 인터럽트 체크
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                }
            } catch (IOException e) {
                // 특정 예외 무시
                if (!(e instanceof SocketException &&
                      (e.getMessage().equals("Socket closed") ||
                       e.getMessage().contains("Broken pipe")))) {
                    logger.warn("{} transfer error", threadName, e);
                } else {
                    logger.debug("{} transfer completed", threadName);
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

    // 서버 종료 메서드
    public void shutdown() {
        isRunning = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing server socket", e);
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
