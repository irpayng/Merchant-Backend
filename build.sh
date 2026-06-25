#!/bin/bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
echo "Building super-merchant..."
mvn clean package -q
echo "Build complete: target/super-merchant-0.0.1-SNAPSHOT.jar"
