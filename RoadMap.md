![Peergos Logo](https://peergos.org/theme/img/peergos/logo-main.svg)

Peergos Road Map
========


Centralized Alpha
------------
 - &#10004; Stable web interface and data formats (at least pre-crdt)
 - &#10004; Streaming E2E encrypted video (streaming disabled by default)
 - &#10004; Streaming download of arbitrarily large files (disabled by default)
 - &#10004; Max user count to deny new user signups after some limit (And display error to user)
 - &#10004; Max storage quota per user (enforced on puts) and error shown to user
 - &#10004; Whitelist of users that can write to this server
 - &#10004; Blacklist of users that can't be read from this server (illegal content guard)
 
Decentralized writes + Self-hostable storage
------------
 - &#10004;Each user stores an ipfs node id (cid) in their PKI which is the server responsible for storing their data
 - &#10004;Implement MutablePointers and SocialNetwork in terms of ipfs p2p stream
 - &#10004;Mirror core node PKI on every node for private friend lookups
 - &#10004;Implement corenode in terms of ipfs p2p stream to allow self hosting in ipfs itself

Keymail
------------
 - Initially don't need much UI other than upload text file for keymail an select recipient
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
  - Zk-SNARKs for follow requests? https://blog.ethereum.org/2016/12/05/zksnarks-in-a-nutshell/

Sustainable
------------
 - Use cryptocurrency to pay for username claims, must be privacy preserving, and post-quantum
