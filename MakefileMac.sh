#!/bin/sh

CP="."
for jar in `find lib -name "*.jar"`; do
CP="${CP}:$jar"
done

JAVA_BUILD_OPTS="-g -source 1.8 -target 1.8 -cp ${CP}"

CP_SPACE=`ls lib/*.jar`

for java in `find src -name "*.java"`; do
JAVA_SOURCE="${JAVA_SOURCE} $java"
done

if [ "$1" = "backupcerts" ] ; then
    cp RootCertificate.src RootCertificate.backup
    cp CoreCertificates.src CoreCertificates.backup
fi

if [ "$1" = "restorecerts" ] ; then
    cp RootCertificate.backup RootCertificate.src
    cp CoreCertificates.backup CoreCertificates.src
fi

if [ "$1" = "server" ] ; then
    cp RootCertificate.src src/peergos/crypto/RootCertificate.java
    cp CoreCertificates.src src/peergos/crypto/CoreCertificates.java
    mkdir -p build
    echo "Name: Peergos Storage Server" > def.manifest
    echo "Main-Class: peergos.server.Start" >> def.manifest
    echo "Build-Date: " `date` >> def.manifest
    echo "Class-Path: " ${CP_SPACE} >> def.manifest
    javac ${JAVA_BUILD_OPTS} -d build ${JAVA_SOURCE}

    jar -cfm PeergosServer.jar def.manifest ui/ \
-C build peergos
    rm -f def.manifest
fi

if [ "$1" = "tests" ] ; then
    cp RootCertificate.src src/peergos/crypto/RootCertificate.java
    cp CoreCertificates.src src/peergos/crypto/CoreCertificates.java
    mkdir -p build
    echo "Name: Peergos Tests" > def.manifest
    echo "Main-Class: peergos.tests.Tests" >> def.manifest
    echo "Build-Date: " `date` >> def.manifest
    echo "Class-Path: " ${CP_SPACE} >> def.manifest
    javac ${JAVA_BUILD_OPTS} -d build ${JAVA_SOURCE}

    jar -cfm PeergosTests.jar def.manifest ui/ \
-C build peergos
    rm -f def.manifest
fi
