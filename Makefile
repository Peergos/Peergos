CP = `find lib -name "*.jar" -printf %p:`
JAVA_BUILD_OPTS = -g -source 1.8 -target 1.8 -cp .:$(CP)
CP_SPACE = `ls lib/*.jar`

.PHONY: build
build: server

.PHONY: backupcerts
backupcerts: 
	cp RootCertificate.src RootCertificate.backup
	cp CoreCertificates.src CoreCertificates.backup

.PHONY: restorecerts
restorecerts: 
	cp RootCertificate.backup RootCertificate.src
	cp CoreCertificates.backup CoreCertificates.src

.PHONY: server
server: 
	cp RootCertificate.src src/peergos/crypto/RootCertificate.java
	cp CoreCertificates.src src/peergos/crypto/CoreCertificates.java
	mkdir -p build
	echo "Name: Peergos Server" > def.manifest
	echo "Main-Class: peergos.server.Start" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find src -name \*.java`
	jar -cfm PeergosServer.jar def.manifest ui/ \
	    -C build peergos
	rm -f def.manifest

.PHONY: tests
tests: 
	cp RootCertificate.src src/peergos/crypto/RootCertificate.java
	cp CoreCertificates.src src/peergos/crypto/CoreCertificates.java
	mkdir -p build
	echo "Name: Peergos Tests" > def.manifest
	echo "Main-Class: peergos.tests.Tests" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find src -name \*.java`
	jar -cfm PeergosTests.jar def.manifest ui/ \
	    -C build peergos
	rm -f def.manifest
