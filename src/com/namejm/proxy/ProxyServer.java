package com.namejm.proxy;

import net.sf.javainetlocator.InetAddressLocator;
import net.sf.javainetlocator.InetAddressLocatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.util.Locale;

public class ProxyServer {
    final private static Logger logger = LoggerFactory.getLogger(ProxyServer.class);
    public static void main(String args[]){
        System.out.println("       _           _____                      _____                          \n" +
                "      | |         |  __ \\                    / ____|                         \n" +
                "      | |_ __ ___ | |__) | __ _____  ___   _| (___   ___ _ ____   _____ _ __ \n" +
                "  _   | | '_ ` _ \\|  ___/ '__/ _ \\ \\/ / | | |\\___ \\ / _ \\ '__\\ \\ / / _ \\ '__|\n" +
                " | |__| | | | | | | |   | | | (_) >  <| |_| |____) |  __/ |   \\ V /  __/ |   \n" +
                "  \\____/|_| |_| |_|_|   |_|  \\___/_/\\_\\\\__, |_____/ \\___|_|    \\_/ \\___|_|   \n" +
                "                                        __/ |                                \n" +
                "                                       |___/                                 \n" +
                "Copyright © 2021 Jm's Corp All rights reserved.\n");

        try {
            // and the local port that we listen for connections on
            String host = "10.1.3.200";
            int remoteport = 80;
            int localport = 8080;
            // Print a start-up message
            logger.info("Starting inbound proxy for {}:{} on port {}", host, remoteport, localport);

            ServerSocket server = new ServerSocket(localport);
            while (true) {
                new ThreadProxy(server.accept(), host, remoteport);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String exceptionAsString = sw.toString();
            logger.error(exceptionAsString);
        }
    }
}
