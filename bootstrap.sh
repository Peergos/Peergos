java -jar DefianceServer.jar -rootGen -password password
make server
java -jar DefianceServer.jar -dirGen -password password -keyfile dir.key 
java -jar DefianceServer.jar -dirSign -csr dir.csr -rootPassword password
make server


