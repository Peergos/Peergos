JAVA_BUILD_OPTS = -source 1.7 -target 1.7 -cp .:lib/Nereus6.jar

.PHONY: build
build: server

.PHONY: server
server: 
	mkdir -p build
	echo "Name: Defiance Storage Server" > def.manifest
	echo "Main-Class: defiance.server.StorageServer" >> def.manifest
	echo "Build-Date: " `date` >> def.manifest
	echo "Class-Path: " lib/Nereus6.jar >> def.manifest
	javac $(JAVA_BUILD_OPTS) -d build `find defiance -name \*.java`
	jar -cfm DefianceServer.jar def.manifest \
	    -C build defiance
	rm -f def.manifest