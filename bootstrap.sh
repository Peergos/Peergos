#!/bin/bash
cp RootCertificate.src src/peergos/crypto/RootCertificate.java
cp CoreCertificates.src src/peergos/crypto/CoreCertificates.java
domain=$1
if [ "$domain" = "" ];then
   domain=peergos.org
fi
echo "Using domain $domain."
echo generating root certificate...
java -jar PeergosServer.jar -rootGen -password password
cp src/peergos/crypto/RootCertificate.java RootCertificate.src
make server > /dev/null
echo generating core node certificate...
java -jar PeergosServer.jar -coreGen -password password -keyfile core.key -domain $domain
echo signing core node certificate...
java -jar PeergosServer.jar -coreSign -csr core.csr -rootPassword password
cp src/peergos/crypto/CoreCertificates.java CoreCertificates.src
make server > /dev/null
rm -f storage.csr
rm -f storage.p12
echo
echo "***********************************************************************"
echo To run unit tests successfully, re-make tests after running this script
echo If you are switching between localhost and peergos.org, run this again.
