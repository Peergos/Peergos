JAVA_BUILD_OPTS = -g -source 1.7 -target 1.7 -cp .:lib/Nereus6.jar:lib/junit-4.11.jar:lib/hamcrest-core-1.3.jar

.PHONY: build
build: server

.PHONY: server
server: 
	mkdir -p build
	echo "Name: Defiance Storage Server" > def.manifest
	echo "Main-Class: defiance.dht.RoutingServer" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " lib/Nereus6.jar lib/junit-4.11.jar lib/hamcrest-core-1.3.jar>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find defiance -name \*.java`
	cp lib/Nereus6.jar DefianceServer.jar
	jar -ufm DefianceServer.jar def.manifest \
	    -C build defiance
	rm -f def.manifest

.PHONY: tests
tests: 
	mkdir -p build
	echo "Name: Defiance Tests" > def.manifest
	echo "Main-Class: defiance.tests.Tests" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " lib/Nereus6.jar lib/junit-4.11.jar lib/hamcrest-core-1.3.jar>> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find defiance -name \*.java`
	cp lib/Nereus6.jar DefianceServer.jar
	jar -cfm DefianceTests.jar def.manifest \
	    -C build defiance
	rm -f def.manifest
