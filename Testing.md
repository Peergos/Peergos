Testing
================

These are in increasing order of difficulty and each assumes nothing but fresh git clone.

###Java - Single machine, single process, in-memory testing
```bash
make tests
java -jar PeergosTests.jar -local
```

###Javascript - Single machine, single process, in-memory testing
```bash
./bootstrap.sh localhost
make tests
java -jar PeergosTests.jar -local
```

###Single machine, persistent testing
Start a 5 node cluster with
```bash
./bootstrap $machine_IP
make tests
make server
java -jar PeergosServer.jar -test 5 -script testscripts/empty.txt -domain $machine_IP
java -jar PeergosTests.jar -clusterAddress $machine_IP -coreAddress $machine_IP
```

###Distributed test


