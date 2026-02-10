#!/bin/bash

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_PATH="$BASE_DIR/config/application.yml"
LOGBACK_CONFIG="$BASE_DIR/config/logback.xml"
java -server -Xms512M -Xmx1024M -XX:MaxMetaspaceSize=256m -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dlogback.configurationFile="$LOGBACK_CONFIG" -jar "$BASE_DIR/JmProxyServer.jar" "$CONFIG_PATH"
