@echo off

title JmProxyServer
java -server -Xms512M -Xmx1024M -XX:MaxMetaspaceSize=256m -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -jar JmProxyServer.jar