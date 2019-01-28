<img src="https://teamcity.jetbrains.com/app/rest/builds/buildType:(id:OpenSourceProjects_Peergos_Build)/statusIcon"></a>

![Peergos Logo](https://peergos.org/theme/img/peergos/logo-main.svg)

Peergos
========

Peergos is a peer-to-peer encrypted filesystem with secure sharing of files designed to be resistant to surveillance of data content or friendship graphs. It will have a secure email replacement, with some interoperability with email. There will also be a totally private and secure social network, where users are in control of who sees what (executed cryptographically).

The name Peergos comes from the Greek word Πύργος (Pyrgos), which means stronghold or tower, but phonetically spelt with the nice connection to being peer-to-peer. 

There is a single machine demo running at [https://demo.peergos.net](https://demo.peergos.net). 

You can read more detail about our features and architecture in our [book](https://book.peergos.org).

The slides of a talk introducing Peergos are [here](https://speakerdeck.com/ianopolous/peergos-architecture) and [videos here](https://www.youtube.com/watch?v=h54pShffxvI) and [here](https://www.youtube.com/watch?v=dCLboQDlzds).

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
 - 3: Data storage - under a given IPNS key there is a [merkle-champ](https://en.wikipedia.org/wiki/Hash_array_mapped_trie) of encrypted chunks under random labels, without any cross links visible to the network (the network can't deduce the size of files)
 - 4: Encryption - Strong encryption is done on the user's machine using [TweetNaCl](http://tweetnacl.cr.yp.to/), with each 5MiB chunk of a file being encrypted independently. 
 - 5: Social layer implementing the concept of following or being friends with another user, without exposing the friend network to anyone.
 - 5: Sharing - Secure cryptographic sharing of files with friends.

2.0 Language
 - The IPFS layer is currently coded in Go
 - The server is coded to run on JVM to get portability and speed, predominantly Java
 - The web interface is mostly coded in Java and cross compiled to Javascript, with the exception of the tweetnacl, scrypt and sha256 libraries, and a small amount of GUI code in JS for Vue.js. 

3.0 Nodes
 - There is a pki node which ensures unique usernames. This data is mirrored on every peergos server. Eventually we might put this small amount of data in a blockchain for full decentralization.
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

6.0 Friend network
 - Anyone can send anyone else a "friend request". This amounts to "following" someone and is a one way protocol. This is stored in the target user's server, but the server cannot see who is sending the friend request (it is cryptographically blinded). 
 - The target user can respond to friend requests with their own friend request to make it bi-directional (the usual concept of a friend). 
 - Once tor is integrated, there will be no way for an attacker (or us) to deduce the friendship graph (who is friends with who). 
 
7.0 Sharing of a file (with another user, through a secret link, or publicly)
 - Once user A is being followed by user B, then A can share files with user B (B can revoke their following at any time)
 - File access control is based on [cryptree](https://raw.githubusercontent.com/ianopolous/Peergos/master/papers/wuala-cryptree.pdf) system used by Wuala
 - sharing of a text file with another user could constitute a secure email
 - a link can be generated to a file or a folder which can be shared with anyone through any medium. A link is of the form https://demo.peergos.net/#KEY_MATERIAL which has the property that even the link doesn't leak the file contents to the network, as the key material after the # is not sent to the server, but interpreted locally in the browser.
 - a user can publish a capability to a file or folder they control which makes it publicly visible

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
Running tests will install the correct version of IPFS automatically, run the daemon, and terminate it afterwards. 
```shell
ant test
```

Usage
-----
Instructions for self hosting will be updated once it is supported. 

In the meantime you can experiment (BEWARE: we occasionally need to delete the data on this test network, so don't use it as your only copy of anything) with running your own Peergos server in our demo network by downloading a release from https://demo.peergos.net/public/peergos/releases

or

https://demo.peergos.net/#pQd8rmrEhBN1Lq5xTE7FRhfuc9ZasczuJX6msfcGiHEREQJWBgNZdemC/pQd8rmrEhBN1HfWok6UQWTS29DLGA8sjJdzXqs5z7fudsywKLZhFXYbQ/DGypN6uTfHNGeefjY2zLXwCsFeE4CzR4irN4TUkEMbTU/5Pf7SuySq5tU9nZoHfFDKpjWAYntDaEQ1sx4cvtMRP21SsHCMqD

You will need Java >= 8 installed. 

Run Peergos with:
```
java -jar PeergosServer.jar -peergos -pki-node-id QmXZXGXsNhxh2LiWFsa7CLHeRWJS5wb8RHxcTvQhvCzAeu -peergos.identity.hash zdpuArqSqUUncNsLSRVvUhdcT3GKAeA5yhDkPNSMf1bkEujsk -logToConsole true
```
You can then access the web interface over http:/localhost:8000/

Note that whichever Peergos server you sign up through will be storing your data (we plan to enable migration later), so if you don't intend on leaving your Peergos server running permanently, then we recommend signing up on https://demo.peergos.net and then you can log in through a local Peergos instance and all your data will magically end up on the demo.peergos.net server. 

### Development Notes
The ``ant compile`` target will only compile sources in src/peergos/{client,server,shared} folders.
