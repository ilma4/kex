#!/bin/bash

cp runtime-deps/lib/libz3.so /usr/lib/
cp runtime-deps/lib/libz3java.so /usr/lib

# switch to environment JVM as needed
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/

export JAVA_HOME=$JAVA_HOME
./kex.sh
