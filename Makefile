CP = `find lib -name "*.jar" -printf %p:`
JAVA_BUILD_OPTS = -g -source 1.8 -target 1.8 -cp .:$(CP)
CP_SPACE = `ls lib/*.jar`

.PHONY: build
build: server

.PHONY: backupcerts
backupcerts: 
	cp RootCertificate.src RootCertificate.backup
	cp DirectoryCertificates.src DirectoryCertificates.backup
	cp CoreCertificates.src CoreCertificates.backup

.PHONY: restorecerts
restorecerts: 
	cp RootCertificate.backup RootCertificate.src
	cp DirectoryCertificates.backup DirectoryCertificates.src
	cp CoreCertificates.backup CoreCertificates.src

.PHONY: server
server: 
	cp RootCertificate.src peergos/crypto/RootCertificate.java
	cp DirectoryCertificates.src peergos/crypto/DirectoryCertificates.java
	cp CoreCertificates.src peergos/crypto/CoreCertificates.java
	mkdir -p build
	echo "Name: Peergos Storage Server" > def.manifest
	echo "Main-Class: peergos.storage.dht.Start" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find peergos -name \*.java`
	jar -cfm PeergosServer.jar def.manifest \
	    -C build peergos
	rm -f def.manifest

.PHONY: tests
tests: 
	cp RootCertificate.src peergos/crypto/RootCertificate.java
	cp DirectoryCertificates.src peergos/crypto/DirectoryCertificates.java
	cp CoreCertificates.src peergos/crypto/CoreCertificates.java
	mkdir -p build
	echo "Name: Peergos Tests" > def.manifest
	echo "Main-Class: peergos.tests.Tests" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find peergos -name \*.java`
	jar -cfm PeergosTests.jar def.manifest \
	    -C build peergos
	rm -f def.manifest

.PHONY: net
net: 
	cp RootCertificate.src peergos/crypto/RootCertificate.java
	cp DirectoryCertificates.src peergos/crypto/DirectoryCertificates.java
	cp CoreCertificates.src peergos/crypto/CoreCertificates.java
	mkdir -p build
	echo "Name: Peergos Net Tests" > def.manifest
	echo "Main-Class: peergos.net.upnp.Upnp" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find peergos -name \*.java` `find test -name \*.java` `find org -name \*.java`
	jar -cfm Net.jar def.manifest \
	    -C build peergos -C build test -C build org
	rm -f def.manifest
