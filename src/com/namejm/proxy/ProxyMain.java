package com.namejm.proxy;

import com.namejm.proxy.ProxyDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProxyMain {
    private static final Logger logger = LoggerFactory.getLogger(ProxyMain.class);

    private final ProxyDto config;
    private final ExecutorService executorService;

    public ProxyMain(ProxyDto config) throws IOException {
        this.config = config;
        // 스레드 풀 생성 - 동시 연결 처리
        this.executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
        );
        startProxyServer();
    }

    private void startProxyServer() throws IOException {
        try (ServerSocket ss = new ServerSocket(config.getBindPort())) {
            logger.info("Proxy server started on port {}", config.getBindPort());

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = ss.accept();
                    // 각 연결을 별도 스레드로 처리
                    executorService.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    logger.warn("Error accepting connection", e);
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        Socket serverSocket = null;
        try {
            // IP 국가 필터링 로직
            if (!isAllowedConnection(clientSocket)) {
                logger.info("Connection blocked from IP: {}",
                    clientSocket.getInetAddress().getHostAddress());
                clientSocket.close();
                return;
            }

            // 서버 연결
            serverSocket = new Socket(config.getForwardHost(), config.getForwardPort());
            logger.debug("Connection established: {} -> {}",
                clientSocket.getInetAddress(),
                serverSocket.getInetAddress());

            // 양방향 데이터 전송을 위한 스레드 생성
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

            // 스레드가 종료될 때까지 대기
            clientToServerThread.join();
            serverToClientThread.join();

        } catch (Exception e) {
            logger.error("Connection processing error", e);
        } finally {
            // 소켓 안전하게 닫기
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
        }
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
                }
            } catch (IOException e) {
                logger.debug("{} transfer interrupted", threadName, e);
            }
        }, threadName);
        return thread;
    }

    private boolean isAllowedConnection(Socket clientSocket) throws Exception {
        String remoteAddr = clientSocket.getInetAddress().getHostAddress();

        // 국가 확인 로직
        Locale locale = InetAddressLocator.getLocale(remoteAddr);
        String country = locale.getCountry();

        // 허용 조건 체크
        boolean isAllowed = false;
        for (String allowedCondition : config.getAllowedCountries()) {
            switch (allowedCondition) {
                case "Any":
                    isAllowed = true;
                    break;
                case "localhost":
                    isAllowed |= remoteAddr.equals("127.0.0.1");
                    break;
                case "private":
                    isAllowed |= isPrivateIP(remoteAddr);
                    break;
                default:
                    isAllowed |= allowedCondition.equals(country);
            }

            // 이미 허용되었다면 루프 종료
            if (isAllowed) break;
        }

        logger.trace("Connection check - IP: {}, Country: {}, Allowed: {}",
            remoteAddr, country, isAllowed);

        return isAllowed;
    }

    // Private IP 체크 메서드 추가
    private boolean isPrivateIP(String ipAddress) {
        try {
            // 로컬 네트워크 대역 체크
            if (ipAddress.startsWith("192.168.") ||
                ipAddress.startsWith("10.") ||
                ipAddress.startsWith("172.16.")) {
                return true;
            }

            // CIDR 표기법 체크 메서드
            return isInSubnet(ipAddress, "192.168.0.0/24");
        } catch (Exception e) {
            return false;
        }
    }

    // CIDR 서브넷 체크 메서드
    private boolean isInSubnet(String ip, String subnet) {
        String[] parts = subnet.split("/");
        String netAddress = parts[0];
        int subnetBits = Integer.parseInt(parts[1]);

        long ipLong = ipToLong(ip);
        long netAddressLong = ipToLong(netAddress);
        long mask = (-1L) << (32 - subnetBits);

        return (ipLong & mask) == (netAddressLong & mask);
    }

    // IP를 Long 값으로 변환
    private long ipToLong(String ipAddress) {
        String[] octets = ipAddress.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= Long.parseLong(octets[i]) << (24 - (8 * i));
        }
        return result;
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.warn("Error closing resource", e);
            }
        }
    }

    // 서버 종료 메서드
    public void shutdown() {
        logger.info("Shutting down proxy server");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
