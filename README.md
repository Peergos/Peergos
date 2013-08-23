Defiance
========

Peer-to-peer encrypted filesystem with secure sharing of files.

I would very much appreciate any help, be it code, testing, bugs, or advice. 

Project aims:
1. To allow individuals to securely and privately store files in a peer to peer network which has no central node and is generally difficult to disrupt
2. To allow sharing of such files with other users of the network
3. Enable a new secure form of email
4. To be independent of any central trust architecture like SSL certificates

Project anti-aims:
1. Defiance does not provide anonymity. Anonymity can be achieved by creating and only ever accessing a User address over Tor

Architecture:
0.0 Language
 - Coded to run on JVM to get portability and speed, predominantly Java and maybe Scala

1.0 Node discovery
 - modelled on the bitcoin network
 - initial nodes are either hard-coded or user-supplied
 - network addresses are shared after connection

2.0 User address modelled on bitcoin addresses
 - 64 alpha-numeric characters
 - uppercase letter "O", uppercase letter "I", lowercase letter "l", and the number "0" are never used
 - has an associated private and public key
 - private key is required to access the user's files
 - several of the characters are a checksum
 - each new address is validated by the network before it can be used to donate space

3.0 Encryption
 - private keys never leave client node
 - encrypted files are split locally, using erasure codes, into multiple fragments to distribute

4.0 Incentives
 - Amount of storage individuals are allowed to use is the amount they donate divided by the replication ratio. This amount takes a week of > 70% uptime to be usable, and will disappear if donated storage is ever online for less than 70% in the previous month

5.0 Repair after node disappearance

6.0 Sharing of a file (with another user, or publicly)
 - based on cryptree system used by wuala
 - publicly shared files would constitute a massively scalable web server
 - sharing of a text file with another user could constitute a secure email
 - files are read-only (will implement diff files later to accomodate changes without storage bloat)
 - have an API for sharing a file with an conventional email address, this sends a conventional email to the target with a link to the file (can be used to encourage viral aspect to spread to friends)