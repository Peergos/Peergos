---
layout: page
title: Security
permalink: /security/
---

Peergos encrypts files locally on your device and your keys never leave your device. To log in, your username and password are (locally) hashed through <a href="https://en.wikipedia.org/wiki/Scrypt">scrypt</a> to derive your root key-pair. This key-pair is never written to disk, and is only used to decrypt your entry points into your filesystem. This design allows you to log in from any device. 

The underlying encryption uses <a href="http://tweetnacl.cr.yp.to/">Tweetnacl</a> for both symmetric and asymmetric encryption. 