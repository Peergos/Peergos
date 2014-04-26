CP = `find lib -name "*.jar" -printf %p:`
JAVA_BUILD_OPTS = -g -source 1.7 -target 1.7 -cp .:$(CP)
CP_SPACE = `ls lib/*.jar`

.PHONY: build
build: server

.PHONY: server
server: 
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
	mkdir -p build
	echo "Name: Peergos Tests" > def.manifest
	echo "Main-Class: peergos.tests.Tests" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find peergos -name \*.java`
	jar -cfm PeergosTests.jar def.manifest \
	    -C build peergos
	rm -f def.manifest
