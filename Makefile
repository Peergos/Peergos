CP = `find lib -name "*.jar" -printf %p:`
JAVA_BUILD_OPTS = -g -source 1.7 -target 1.7 -cp .:$(CP)
CP_SPACE = `ls lib/*.jar`

.PHONY: build
build: server

.PHONY: server
server: 
	mkdir -p build
	echo "Name: Defiance Storage Server" > def.manifest
	echo "Main-Class: defiance.dht.Start" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find defiance -name \*.java`
	jar -cfm DefianceServer.jar def.manifest \
	    -C build defiance
	rm -f def.manifest

.PHONY: tests
tests: 
	mkdir -p build
	echo "Name: Defiance Tests" > def.manifest
	echo "Main-Class: defiance.tests.Tests" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " $(CP_SPACE)>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find defiance -name \*.java`
	jar -cfm DefianceTests.jar def.manifest \
	    -C build defiance
	rm -f def.manifest
