#!/bin/bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
echo "Starting super-merchant on port ${APP_PORT:-8110}..."
java -jar target/super-merchant-0.0.1-SNAPSHOT.jar
