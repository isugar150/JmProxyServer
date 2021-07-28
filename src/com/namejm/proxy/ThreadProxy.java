package com.namejm.proxy;

import net.sf.javainetlocator.InetAddressLocator;
import net.sf.javainetlocator.InetAddressLocatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.Locale;

public class ThreadProxy extends Thread {
    final private static Logger logger = LoggerFactory.getLogger(ThreadProxy.class);
    private Socket sClient;
    private final String SERVER_URL;
    private final int SERVER_PORT;
    ThreadProxy(Socket sClient, String ServerUrl, int ServerPort) {
        this.SERVER_URL = ServerUrl;
        this.SERVER_PORT = ServerPort;
        this.sClient = sClient;
        this.start();
    }

    @Override
    public void run() {
        try {
            final byte[] request = new byte[1024];
            byte[] reply = new byte[4096];
            final InputStream inFromClient = sClient.getInputStream();
            final OutputStream outToClient = sClient.getOutputStream();
            Socket client = null, server = null;
            // connects a socket to the server
            try {
                server = new Socket(SERVER_URL, SERVER_PORT);

                String remoteSocketAddr = sClient.getRemoteSocketAddress().toString().replaceAll("/", "").split(":")[0];
                String remoteSocketPort = sClient.getRemoteSocketAddress().toString().replaceAll("/", "").split(":")[1];
                String localSocketAddr = sClient.getLocalAddress().toString().replaceAll("/", "");
                String localSocketPort = String.valueOf(sClient.getLocalPort());

                Locale locale = null;
                try{
                    locale = InetAddressLocator.getLocale(remoteSocketAddr);
                } catch (InetAddressLocatorException e) { locale = null; }
                String country = locale.getCountry();
                if(remoteSocketAddr.equals("127.0.0.1")){
                    country = "local";
                } else if(country.equals("**")){
                    country = "private";
                }

                logger.info("[IN] [{}:{}]({}) => [{}:{}] => [{}:{}])", remoteSocketAddr, remoteSocketPort, country, localSocketAddr, localSocketPort, SERVER_URL, SERVER_PORT);
            } catch (IOException e) {
                PrintWriter out = new PrintWriter(new OutputStreamWriter(
                        outToClient));
                out.flush();
                throw e;
            }
            // a new thread to manage streams from server to client (DOWNLOAD)
            final InputStream inFromServer = server.getInputStream();
            final OutputStream outToServer = server.getOutputStream();
            // a new thread for uploading to the server
            new Thread() {
                public void run() {
                    int bytes_read;
                    try {
                        while ((bytes_read = inFromClient.read(request)) != -1) {
                            outToServer.write(request, 0, bytes_read);
                            outToServer.flush();
                            //TODO CREATE YOUR LOGIC HERE
                        }
                    } catch (IOException e) {
                    }
                    try {
                        outToServer.close();
                    } catch (IOException e) {
                        logger.error(e.getMessage());
                        StringWriter sw = new StringWriter();
                        e.printStackTrace(new PrintWriter(sw));
                        String exceptionAsString = sw.toString();
                        logger.error(exceptionAsString);
                    }
                }
            }.start();
            // current thread manages streams from server to client (DOWNLOAD)
            int bytes_read;
            try {
                while ((bytes_read = inFromServer.read(reply)) != -1) {
                    outToClient.write(reply, 0, bytes_read);
                    outToClient.flush();
                    //TODO CREATE YOUR LOGIC HERE
                }
            } catch (IOException e) {
            } finally {
                try {
                    if (server != null)
                        server.close();
                    if (client != null)
                        client.close();
                } catch (IOException e) {
                    throw e;
                }
            }
            outToClient.close();
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
