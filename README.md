Defiance
========

Peer-to-peer encrypted filesystem with secure sharing of files.

I would very much appreciate any help, be it code, testing, bugs, or advice. 

Project aims
------------
 - To allow individuals to securely and privately store files in a peer to peer network which has no central node and is generally difficult to disrupt
 - To allow secure sharing of such files with other users of the network
 - To enable a new secure form of email
 - To be independent of any central trust architecture like SSL certificates

Project anti-aims
-----------------
 - Defiance does not provide anonymity. Anonymity can be achieved by creating and only ever accessing a User account over Tor

Architecture
------------
1.0 Layers of architecture
 - 1: Routing layer - Base layer which handles network creation and maintenance and message routing.
 - 2: Data Layer - Adds put, get and contains functions to turn 1.0 into a distributed hash table (no fault tolerance).
 - 3: Distributed file system - Erasure codes used to add fault tolerance, User's client responsible for ensuring enough fragments of their files survive
 - 4: Encryption - Strong encryption on users machine - convergent encryption -> deduplication for free
 - 5: Sharing - secure sharing of files with other users via cryptree key management

2.0 Language
 - Coded to run on JVM to get portability and speed, predominantly Java and maybe Scala

3.0 Node discovery
 - initial nodes are either hard-coded or user-supplied
 - network addresses are shared after connection
 - network topology is learned through normal traffic (ring based with random links across circle ~ log(N))

4.0 User address modelled on bitcoin addresses
 - 64 alpha-numeric characters
 - uppercase letter "O", uppercase letter "I", lowercase letter "l", and the number "0" are never used
 - has an associated private and public key
 - private key is required to access the user's files
 - several of the characters are a checksum
 - each new address is validated by the network before it can be used to donate space

5.0 Encryption
 - private keys never leave client node
 - encrypted files are duplicated locally, using erasure codes, into multiple fragments to distribute

6.0 Incentives
 - Amount of storage individuals are allowed to use is the amount they donate divided by the replication ratio. This amount takes a week of > 70% uptime to be usable, and will disappear if donated storage is ever online for less than 70% in the previous month

7.0 Repair after node disappearance
 - User's client is responsible for ensuring enough fragments of their files remain (another incentive to stay online)

8.0 Sharing of a file (with another user, or publicly)
 - based on cryptree system used by wuala
 - publicly shared files would constitute a massively scalable web server
 - sharing of a text file with another user could constitute a secure email
 - files are read-only (will implement diff files later to accomodate changes without storage bloat)
 - have an API for sharing a file with an conventional email address, this sends a conventional email to the target with a link to the file (can be used to encourage viral aspect to spread to friends)
