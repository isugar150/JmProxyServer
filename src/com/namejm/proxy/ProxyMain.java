package com.namejm.proxy;

import net.sf.javainetlocator.InetAddressLocator;
import net.sf.javainetlocator.InetAddressLocatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

public class ProxyMain {
    final private static Logger logger = LoggerFactory.getLogger(ProxyMain.class);
    public ProxyMain(ProxyDto config) throws IOException {
        String forwardHost = config.getForwardHost();
        int forwardPort = config.getForwardPort();
        int bindPort = config.getBindPort();
        String name = config.getName();
        String[] allowedCountries = config.getAllowedCountries();

        logger.info("Starting inbound proxy for {}:{} on port {}", config.getForwardHost(), config.getForwardPort(), config.getBindPort());

        // Create a ServerSocket to listen for connections with
        ServerSocket ss = new ServerSocket(bindPort);

        final byte[] request = new byte[1024];
        byte[] reply = new byte[4096];

        while (true) {
            Socket sClient = null, server = null;
            try {
                // Wait for a connection on the local port
                sClient = ss.accept();

                final InputStream streamFromClient = sClient.getInputStream();
                final OutputStream streamToClient = sClient.getOutputStream();

                // Make a connection to the real server.
                // If we cannot connect to the server, send an error to the
                // client, disconnect, and continue waiting for connections.
                try {
                    server = new Socket(forwardHost, forwardPort);

                    String remoteSocketAddr = sClient.getRemoteSocketAddress().toString().replaceAll("/", "").split(":")[0];
                    String remoteSocketPort = sClient.getRemoteSocketAddress().toString().replaceAll("/", "").split(":")[1];
                    String localSocketAddr = sClient.getLocalAddress().toString().replaceAll("/", "");
                    String localSocketPort = String.valueOf(sClient.getLocalPort());

                    Locale locale = null;
                    try {
                        locale = InetAddressLocator.getLocale(remoteSocketAddr);
                    } catch (InetAddressLocatorException e) {
                        locale = null;
                    }
                    String country = locale.getCountry();
                    if (remoteSocketAddr.equals("127.0.0.1")) {
                        country = "localhost";
                    } else if (country.equals("**")) {
                        country = "private";
                    }

                    boolean keepOn = false;
                    for (int i = 0; i < allowedCountries.length; i++) {
                        if (allowedCountries[i].equals(country)) {
                            keepOn = true;
                            break;
                        }
                    }
                    if (!keepOn) {
                        logger.info("[{}][IN] Blocked non-allowed IP. (IP: {}) (Country/Division: {})", name, remoteSocketAddr, country);
                        continue;
                    }

                    logger.info("[{}][IN] {}:{}({}) => {}:{} => {}:{})", name, remoteSocketAddr, remoteSocketPort, country, localSocketAddr, localSocketPort, forwardHost, forwardPort);
                } catch (IOException e) {
                    PrintWriter out = new PrintWriter(streamToClient);
                    out.print("Proxy server cannot connect to " + forwardHost + ":" + forwardPort + ":\n" + e + "\n");
                    out.flush();
                    sClient.close();
                    continue;
                }

                // Get server streams.
                final InputStream streamFromServer = server.getInputStream();
                final OutputStream streamToServer = server.getOutputStream();

                // a thread to read the client's requests and pass them
                // to the server. A separate thread for asynchronous.
                Thread t = new Thread() {
                    public void run() {
                        int bytesRead;
                        try {
                            while ((bytesRead = streamFromClient.read(request)) != -1) {
                                streamToServer.write(request, 0, bytesRead);
                                streamToServer.flush();
                            }
                        } catch (IOException e) {
                        }

                        // the client closed the connection to us, so close our
                        // connection to the server.
                        try {
                            streamToServer.close();
                        } catch (IOException e) {
                        }
                    }
                };

                // Start the client-to-server request thread running
                t.start();

                // Read the server's responses
                // and pass them back to the client.
                int bytesRead;
                try {
                    while ((bytesRead = streamFromServer.read(reply)) != -1) {
                        streamToClient.write(reply, 0, bytesRead);
                        streamToClient.flush();
                    }
                } catch (IOException e) {
                }

                // The server closed its connection to us, so we close our
                // connection to our client.
                streamToClient.close();
            } catch (IOException e) {
                logger.error(e.getMessage());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String exceptionAsString = sw.toString();
                logger.error(exceptionAsString);
            } finally {
                try {
                    if (server != null)
                        server.close();
                    if (sClient != null)
                        sClient.close();
                } catch (IOException e) {
                    logger.error(e.getMessage());
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String exceptionAsString = sw.toString();
                    logger.error(exceptionAsString);
                }
            }
        }
    }
}
