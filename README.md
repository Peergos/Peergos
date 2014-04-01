Defiance
========

Defiance is a peer-to-peer encrypted filesystem with secure sharing of files. We plan a secure email replacement, with some interoperabiltiy with email. There will also be a totally private and secure social network, where users are in control of who sees what (executed cryptographically). Evnetually we plan an app-store for third party applications to use our API. 

I would very much appreciate any help, be it code, testing, bugs, or advice. 

Project aims
------------
 - To allow individuals to securely and privately store files in a peer to peer network which has no central node and is generally difficult to disrupt or surveil
 - To allow secure sharing of such files with other users of the network without visible meta-data (who shares with who)
 - To have a beautiful user interface that any computer or mobile user can understand
 - To have super fast file transfers by transfering fragments in parallel to/from different sources
 - To enable a new secure form of email
 - To be independent of  the central SSL CA trust architecture
 - A User should be able to easily run Defiance on a machine in their home and get their own secure cloud storage, and social communication platform from it. 
 - A secure web interface
 - To enable secure real time chat, and video conversations

Project anti-aims
-----------------
 - Defiance does not provide anonymity. Anonymity can be achieved by creating and only ever accessing a User account over Tor

Architecture
------------
1.0 Layers of architecture
 - 1: Routing layer - Base layer which handles network creation, maintenance and message routing.
 - 2: Data Layer - Adds "put", "get" and "contains" functions to turn layer 1 into a distributed hash table (no fault tolerance).
 - 3: Distributed file system - Uses erasure codes to add fault tolerance. User's clients are responsible for ensuring that enough fragments of their files survive.
 - 4: Encryption - Strong encryption is done on the user's machine. 
 - 5: Social layer implementing the concept of following or beings friends with another user, without exposing the friend network to anyone.
 - 5: Sharing - Secure cryptographic sharing of files with friends.

2.0 Language
 - Coded to run on JVM to get portability and speed, predominantly Java and maybe Scala

3.0 Nodes
 - Types of node in decreasing order of reliability: Directory node, Core node, Storage node, Client node
 - The Directory nodes' locations and SSL certificates are hard-coded
 - The Core nodes are highly reliable, high bandwith nodes. They maintain the encrypted meta-data store for file fragments, and handle sharing between users
 - A new node contacts one of the directory nodes to get a list of Storage and Core nodes to connect to
 - Storage nodes discover other storage nodes via distributed has table (DHT) network traffic
 - network topology is ring based with random links across circle ~ log(N) routing steps

4.0 Trust
 - There is a global self-signed root certificate used to sign releases, and to sign directory node certificates
 - A new node first generates an SSL certificate and gets it signed by a directory node over http before joining the network
 - All subsequent communication is done over SSL

4.0 User address is a public key
 - private key is required to access the user's files, this is derived from a password for convenience, or user supplied
 - The core nodes maintain a Map from public key to username and vice versa

5.0 Encryption
 - private keys never leave client node
 - encrypted files are duplicated locally, using erasure codes, into multiple fragments to distribute to the DHT

6.0 Incentives
 - Amount of storage individuals are allowed to use is the amount they donate divided by the replication ratio. This amount takes a week of > 70% uptime to be usable, and will disappear if donated storage is ever online for less than 70% in the previous month (as measured by the network)

7.0 Repair after node disappearance
 - User's client is responsible for ensuring enough fragments of their files remain (another incentive to stay online)
 - For paying users, we can keep a copy of the fragments on our servers to 100% guarantee availability

8.0 Friend network
 - Anyone can send anyone else a "friend request". This amounts to "following" someone and is a one way protocol. This is stored in the core codes, but the core nodes cannot see who is sending the friend request. 
 - The target user can respond to friend requests with their own friend request to make it bi-directional (the usual concept of a friend). 
 - There is no way for the core nodes to deduce the friendship graph (who is friends with who). An observer able to monitor the entire network, and run a core node could probably deduce it, unless we route message using an onion protocol like Tor.

9.0 Sharing of a file (with another user, or publicly)
 - Once user A is being followed by user B, then A can share files with user B (B can revoke their following at any time)
 - based on cryptree system used by wuala
 - publicly shared files would constitute a massively scalable web server
 - sharing of a text file with another user could constitute a secure email
 - files are read-only (will implement diff files later to accomodate changes without storage bloat)
 - will have an API for sharing a file with an conventional email address, this sends a conventional email to the target with a link to the file (can be used to encourage viral aspect to spread to friends)

Usage
-----
Run with the following to find out available options:
java -jar DefianceServer.jar -help
