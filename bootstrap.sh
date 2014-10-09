cp RootCertificate.src peergos/crypto/RootCertificate.java
cp DirectoryCertificates.src peergos/crypto/DirectoryCertificates.java
cp CoreCertificates.src peergos/crypto/CoreCertificates.java
echo generating root certificate...
java -jar PeergosServer.jar -rootGen -password password
cp peergos/crypto/RootCertificate.java RootCertificate.src
make server > /dev/null
echo generating directory certificate...
domain=peergos.org
java -jar PeergosServer.jar -dirGen -password password -keyfile dir.key -domain $domain
echo signing directory certificate...
java -jar PeergosServer.jar -dirSign -csr dir.csr -rootPassword password
cp peergos/crypto/DirectoryCertificates.java DirectoryCertificates.src
make server > /dev/null
echo generating core node certificate...
java -jar PeergosServer.jar -coreGen -password password -keyfile core.key -domain $domain
echo signing core node certificate...
java -jar PeergosServer.jar -coreSign -csr core.csr -rootPassword password
cp peergos/crypto/CoreCertificates.java CoreCertificates.src
make server > /dev/null
#
# To run unit tests successfully, re-make tests after running this script
#
