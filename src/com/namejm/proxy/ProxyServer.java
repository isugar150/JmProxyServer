package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProxyServer {
    final private static Logger logger = LoggerFactory.getLogger(ProxyServer.class);
    private static ServerSocket server = null;
    private static List<ProxyDto> config = null;
    private static int count = 0;
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
            Map<String, List<Map<String, Object>>> ymlParser = new Yaml().load(new FileReader("./config/application.yml"));

            config = new ArrayList<>();
            for(int i = 0; i<ymlParser.get("proxy").size(); i++){
                Map<String, Object> tempData = ymlParser.get("proxy").get(i);
                ProxyDto proxyDto = new ProxyDto().hashMapToDto(tempData);
                config.add(i, proxyDto);
            }

            for(int i = 0; i < config.size(); i++){
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            new ProxyMain(config.get(count++));
                        } catch (IOException e) {
                            logger.error(e.getMessage());
                            StringWriter sw = new StringWriter();
                            e.printStackTrace(new PrintWriter(sw));
                            String exceptionAsString = sw.toString();
                            logger.error(exceptionAsString);
                        }
                    }
                }).start();
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
