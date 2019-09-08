#!/bin/bash

#
# to build a native-image graalvm-ce is required
# and then native-image is installed in a separate step
# note: make sure the graalvm version is up to date
#
#

curl  -L -O https://github.com/oracle/graal/releases/download/vm-19.2.0/graalvm-ce-linux-amd64-19.2.0.tar.gz
tar -zxvf graalvm-ce-linux-amd64-19.2.0.tar.gz
./graalvm-ce-19.2.0/bin/gu install native-image

if [ -f  "graalvm-ce-19.2.0/bin/native-image" ];
then
    echo "native-image installed @ "$(readlink -f graalvm-ce-19.2.0/bin/native-image)
    export NATIVE_IMAGE_BIN=$(readlink -f graalvm-ce-19.2.0/bin/native-image)
else
    echo "native-image not installed..."
fi


