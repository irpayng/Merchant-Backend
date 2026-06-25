#!/bin/bash
# Run super-merchant locally.
#
# Usage: ./run-dev.sh

set -euo pipefail

JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export JAVA_HOME

# Load environment variables from .env
if [ -f .env ]; then
  set -a
  source .env
  set +a
fi

PORT=${APP_PORT:-8110}

# Kill any existing instance
lsof -ti:$PORT 2>/dev/null | xargs kill -9 2>/dev/null || true

# Clean build
echo "Building..."
$JAVA_HOME/bin/java -version 2>&1 | head -1
mvn clean package -DskipTests -q

# Run
echo "Starting on port $PORT..."
exec $JAVA_HOME/bin/java -jar target/super-merchant-0.0.1-SNAPSHOT.jar
