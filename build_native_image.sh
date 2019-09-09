#!/bin/bash

#
# Compiles ./PeergosServer.jar into a standalone binary using native-image.
# More details @i https://www.graalvm.org/docs/reference-manual/aot-compilation/
# 
# notes:
#   PeergosServer.jar is built separately with the ant script
#   native-image is required 
#   
# usage:
# ./build_native_image.sh <peergos-jar-path> <native-image-path>
#  

PEERGOS_JAR_PATH=${1:-PeergosServer.jar}
NATIVE_IMAGE_BIN=${NATIVE_IMAGE_BIN:-native-image}
if ! type -P $NATIVE_IMAGE_BIN;
then
    echo "Could not find native-image executable! Run ./install-native-image.sh and add it to \$PATH"
    exit 1
fi

# no-fallback is required
$NATIVE_IMAGE_BIN -H:EnableURLProtocols=http -cp "lib/*" -jar "$PEERGOS_JAR_PATH" --no-fallback

    

