The Peergos Plan
================
Fundamental aim: Release a P2P secure file storage, sharing and asynchronous commmunication platform.
---------------------

Launch 1 - Alpha 1 (Basic File storage and sharing) 20/5/2015
-------------------------------------------------------------
- [X] UserPublicKey and User implemented in JS using curve25519 and ed25519
- [ ] User context helper functions implemented in JS (10 hours)
- [ ] Web UI: (34)
- [ ] Monitor external IP (of machine or router) and update network) (8)
- [ ] quota tolerance (2)
- [ ] Core node persistence (8)
- [ ] Directory node persistence (8)


Launch 2 - Alpha 2 (KeyMail + file storage and sharing) 21/10/2015
------------------------------------------------------------------
- [ ] version keypair type (2)
- [ ] chaining of directory metadata blocks (to support arbitrarily large directories)
- [ ] benchmarks, scalability tests (16)
- [ ] more efficient small file storage (32)
- [ ] KeyMail (32)


Launch 3 - Beta 1 (KeyMail plus email interop) 14/3/2016
--------------------------------------------------------
- [ ] root key signing of release (including source to allow verifiable build), import root key into java+browser keystore, download from directory servers (16)
- [ ] auto update via root key signed releases (8)
- [ ] fragment storage verification (8)
- [ ] limits per sharing key settable by owner (8)
- [ ] outbound to email (single read/password/...) (unknown)
- [ ] inbound email (encrypted upon receipt, into size limited folder)
- [ ] security audit (40)


Launch 4 - Beta
---------------
- [ ] remove follow spam potential
- [ ] investigate tcp NAT traversal, webrtc, pjnath?
- [ ] transferral of earned storage rights between users
- [ ] facebook layer


- [ ] anonymising circuit construction ala Tor for sharing API (or just use Tor)

