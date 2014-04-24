java -jar PeergosServer.jar -rootGen -password password
make server
java -jar PeergosServer.jar -dirGen -password password -keyfile dir.key 
java -jar PeergosServer.jar -dirSign -csr dir.csr -rootPassword password
make server

#
# To run unit tests successfully, re-make tests after running this script
#
