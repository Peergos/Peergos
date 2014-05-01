cp RootCertificate.src peergos/crypto/RootCertificate.java
cp DirectoryCertificates.src peergos/crypto/DirectoryCertificates.java
cp CoreCertificates.src peergos/crypto/CoreCertificates.java
echo generating root certificate...
java -jar PeergosServer.jar -rootGen -password password
make server > /dev/null
echo generating directory certificate...
java -jar PeergosServer.jar -dirGen -password password -keyfile dir.key 
echo siging directory certificate...
java -jar PeergosServer.jar -dirSign -csr dir.csr -rootPassword password
make server > /dev/null
echo generating core node certificate...
java -jar PeergosServer.jar -coreGen -password password -keyfile core.key 
echo siging core node certificate...
java -jar PeergosServer.jar -coreSign -csr core.csr -rootPassword password
make server > /dev/null

#
# To run unit tests successfully, re-make tests after running this script
#
