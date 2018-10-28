<img src="https://teamcity.jetbrains.com/app/rest/builds/buildType:(id:OpenSourceProjects_Peergos_Build)/statusIcon"></a>

![Peergos Logo](https://peergos.org/theme/img/peergos/logo-main.svg)

Peergos
========

Peergos is a peer-to-peer encrypted filesystem with secure sharing of files designed to be resistant to surveillance of data content or friendship graphs. It will have a secure email replacement, with some interoperability with email. There will also be a totally private and secure social network, where users are in control of who sees what (executed cryptographically).

The name Peergos comes from the Greek word Πύργος (Pyrgos), which means stronghold or tower, but phonetically spelt with the nice connection to being peer-to-peer. 

There is a single machine demo running at [https://demo.peergos.net](https://demo.peergos.net). 

You can read more detail about our features and architecture in our [book](https://peergos.github.io/book).

The slides of a talk introducing Peergos are [here](https://speakerdeck.com/ianopolous/peergos-architecture) and [video here](https://www.youtube.com/watch?v=h54pShffxvI).

If you would like to support Peergos development, then please make a 

[recurring donation less than 100 EUR per week](https://liberapay.com/peergos)

or a 

[larger or one off donation](https://donorbox.org/peergos). 

**WARNING:** Peergos is still alpha software, and needs an independent security audit. Don't use it for data you can't afford to lose or expose, yet.

Peergos aims
------------
 - To allow individuals to securely and privately store files in a peer to peer network which has no central node and is generally difficult to disrupt or surveil
 - To allow secure sharing of such files with other users of the network without visible meta-data (who shares with who)
 - To have a beautiful user interface that any computer or mobile user can understand
 - To enable a new secure form of email
 - To be independent of the central SSL CA trust architecture
 - Self hostable - A user should be able to easily run Peergos on a machine in their home and get their own Peergos storage space, and social communication platform from it. 
 - A secure web interface
 - To enable secure real time chat
 - Plausibly deniable dual login to an account, ala Truecrypt
 - Optional use of U2F for securing login

Project anti-aims
-----------------
 - Peergos does not provide anonymity, yet. Anonymity can be achieved by creating and only ever accessing a User account over Tor

Architecture
------------
1.0 Layers of architecture
 - 1: Peer-to-peer and data layer - [IPFS](https://ipfs.io) provides the data storage, routing and retrieval. A User must have at least one peergos instance storing their data for it to be available. 
 - 2: Authorization Layer - a key pair (IPNS) controls who is able to modify parts of the file system (every write is signed)
 - 3: Data storage - under a given IPNS key there is a merkle-btree of encrypted chunks under random labels, without any cross links visible to the network (the network can't deduce the size of files)
 - 4: Encryption - Strong encryption is done on the user's machine using [TweetNaCl](http://tweetnacl.cr.yp.to/), with each 5MiB chunk of a file being encryped independently. 
 - 5: Social layer implementing the concept of following or being friends with another user, without exposing the friend network to anyone.
 - 5: Sharing - Secure cryptographic sharing of files with friends.

2.0 Language
 - The IPFS layer is currently coded in Go
 - The server is coded to run on JVM to get portability and speed, predominantly Java
 - The web interface is mostly coded in Java and cross compiled to Javascript, with the exception of the tweetnacl, scrypt and sha25 libraries, and a small amount of GUI code in JS for Vue.js. 

3.0 Nodes
 - The Core nodes are highly reliable nodes. They store the username <--> public key mapping and the encrypted pending follow requests. Eventually we hope to put this small amount of data in a blockchain for full decentralization.
 - A new node contacts any public Peergos server to join the network

4.0 Trust
 - New versions of the software will be delivered through Peergos itself. (Able to be turned off by the user if desired)
 - A user who trusts a public Peergos server (and the SSL Certificate authority chain) can use the web interface over TLS
 - A less trusting user can run a Peergos server on their own machine and use the web interface over localhost
 - A more paranoid user can run a Peergos server on their own machine and use the native GUI, or the fuse binding and optionally a U2F device

4.0 Logging in
 - A user's username is salted with the hash of their password and then run through scrypt (with parameters 17, 8, 1, 96, though users can choose harder parameters if desired) to generate a symmetric key, an encrypting keypair and a signing keypair. This means that a user can log in from any machine without transfering any keys, and also that their keys are protected from a brute force attack (see slides above for cost estimate).

5.0 Encryption
 - private keys never leave client node, a random key is generated for every file (explicitly not convergent encryption, which leaks information)

5.1 Post-quantum encryption
 - Files that haven't been shared with another user are already resistant to quantum computer based attacks. This is because the operations to decrypt them from logging in, to seeing plain text, include only hashing and symmetric encryption, both of which are currently believed to not be significantly weakened with a quantum computer. 
 - Files that have been shared between users are, currently, vulnerable to a large enough quantum computer if an attacker is able to log the initial follow requests sent between the users (before the user retrieves and deletes them). This will be replaced with a post-quantum asymmetric algorithm as soon as a clear candidate arrives.  

6.0 Incentives
 - Users will be able to earn storage space by donating storage space (through [FileCoin](http://filecoin.io/))

7.0 Repair after node disappearance
 - User's client is responsible for ensuring enough fragments of their files remain (another incentive to stay online)
 - For paying users, we can keep a copy of the (encrypted) fragments on our servers to 100% guarantee no data loss

8.0 Friend network
 - Anyone can send anyone else a "friend request". This amounts to "following" someone and is a one way protocol. This is stored in the core codes, but the core nodes cannot see who is sending the friend request. 
 - The target user can respond to friend requests with their own friend request to make it bi-directional (the usual concept of a friend). 
 - There is no way for the core nodes to deduce the friendship graph (who is friends with who). The plan is to send follow requests over Tor/I2P/Riffle, so even an adversary monitoring the network in realtime couldn't deduce the friendship graph

9.0 Sharing of a file (with another user, or publicly)
 - Once user A is being followed by user B, then A can share files with user B (B can revoke their following at any time)
 - File access control is based on [cryptree](https://raw.githubusercontent.com/ianopolous/Peergos/master/papers/wuala-cryptree.pdf) system used by Wuala
 - sharing of a text file with another user could constitute a secure email
 - a public link can be generated to a file or a folder which can be shared with anyone through any medium. A public link is of the form https://demo.peergos.net/#KEY_MATERIAL which has the property that even a public link doesn't leak the file contents to the network, as the key material after the # is not sent to the server, but interpreted locally in the browser.

Development
--------
### Dependencies
Requires jdk8, ant and javafx to build. Use the following to install dependencies on debian:
```shell
sudo apt-get install ant
sudo apt-get install openjdk-8-jdk
sudo apt-get install openjfx
```
### Build
```shell
ant dist
```
### Cross compile to JS
```shell
ant gwtc
```
### Run tests
You need to have ant-optional installed:
```shell
sudo apt-get install ant-optional
```
To run tests, IPFS daemon must be running on 127.0.0.1 interface. data.service.sh can be used in build servers to automate installing, running and terminating a given ipfs version. 
```shell
ant test
```

Usage
-----
Instructions for self hosting will be written once it is supported. 
