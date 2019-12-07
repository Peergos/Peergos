#!/bin/bash

hash1=`sha256sum Peergos.jar`
ant dist
hash2=`sha256sum Peergos.jar`
if [[ $hash1 == $hash2 ]];
then
    exit 0
else
    exit -1
fi
