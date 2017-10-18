![Peergos Logo](https://peergos.org/theme/img/peergos/logo-main.svg)

Peergos Road Map
========


Centralized Alpha
------------
 - Stable web interface and data formats
 - Streaming E2E encrypted video (streaming disabled by default)
 - Streaming download of arbitrarily large files (disabled by default)
 - Config file for Peergos to store the following:
 - Max user count to deny new user signups after some limit (And display error to user)
 - Max storage quota per user (enforced on puts) and error shown to user
 - Whitelist of users that can write to this server
 - Blacklist of users that can't be read from this server (illegal content guard)
 
Self-hostable storage
------------
 - Dynamically pin all of a users files (for each user in the write whitelist)
 - Tor hidden service which IPFS connects through
 - Connections to core node go through Tor
 - Consider using I2P or Freenet as Tor alternatives (ensure low switching cost in architecture)

Decentralized writes
------------
 - Change MutablePointers to use authenticated pub-sub in IPFS
 - Move to CRDTs for filesystem and btree

Full decentralization
------------
 - Move core node's PKI data to append only log in IPFS
 - Zk-SNARKs for follow requests? https://blog.ethereum.org/2016/12/05/zksnarks-in-a-nutshell/
 - Each user designates a Tor hidden service (publicly in the WriterData) to send follow requests to them to

Keymail
------------
 - Application to display and edit text (ideally by granting app write access only to a hidden keymail folder)
 - Decide format, information compatible with email headers
 - Bridge to email - a client that polls an email account, writing newly received emails into peergos, and sending emails from new files in a particular folder

Group chat
------------
 - owner invite others
 - owner can grant admin to others (admin => they can invite new users)
 - allow inline pictures
 - be careful about leaking social metadata accidentally

Social Feed
------------
 - Essentially just a group chat that is for most (all?) your friends, but similar to a Facebook or Twitter feed

Fully Quantum proof
------------
 - Move asymmetric crypto (follow requests and signing roots) to a post-quantum algorithm
 - http://sphincs.cr.yp.to/software.html
 - https://www.win.tue.nl/~tchou/mcbits/

Sustainable
------------
 - Use cryptocurrency to pay for username claims, must be privacy preserving, and post-quantum
