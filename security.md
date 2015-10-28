---
layout: page
title: Security
permalink: /security/
---

Peergos encrypts files locally on your device and your keys never leave your device. To log in, your username and password are (locally) hashed through <a href="https://en.wikipedia.org/wiki/Scrypt">scrypt</a> to derive your root key-pair and root symmetric key. This key-pair is never written to disk, and is only used to decrypt follow requests sent to you. The symmetric key is used to decrypt your entry points into your filesystem. This design allows you to log in from any device. 

The underlying encryption uses <a href="http://tweetnacl.cr.yp.to/">Tweetnacl</a> for both symmetric and asymmetric encryption. 

Peergos hides the contents of files, but also the friendship graph. If a user shares a file with another user, the network shouldn't be able to deduce this relationship. 

Quantum Cryptography?
---------------------
Your entire file system in Peergos is encrypted with symmetric cryptography, which is believed to be resistant to quantum computer attacks. We will be switching the asymmetric cryptography to a post-quantum algorithm as soon as a clear candidate arrives. 

Threat Model
------------

1. Peergos is designed to be secure against passive network adversaries, even ones with state level computational resources, who are able to store all data on the Peergos network indefinitely. Despite all the encrypted data being publicly accessible, no one but the intended recipients can deduce any data or friendship graphs. 

2. Peergos does not defend against a compromised user's machine. 

3. Users willing to trust our SSL certificate, can access Peergos through our web interface over https. The more paranoid user can download and run the server locally and browse to localhost.

4. Peergos should be secure against attackers with read access to a users machine which is not synchronous with the web user interface being open in a browser. 