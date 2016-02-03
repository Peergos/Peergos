#Import friends

```shell
curl https://demo.buddycloud.org/api/match_contacts \
  -H "Content-Type: application/json" \
  -d '{  "mine": ["0a22c6c85a47116509f8fbb7688c98ac480651db3c54dd3fcd2ce34d48a5025b"], \
       "others": ["023476e7b8be135f970d65f9bee53bfc66c43742815cdcd2c7f53e51f3937b17",  \
                  "bcee94d395fe9cd76dfae390e006a98032144fb48dc8181448c4cd192ec4b75c"]}'
```

```
{
  "items": [
    {
      "jid": "alice@capulet.lit",
      "matched-hash": "023476e7b8be135f970d65f9bee53bfc66c43742815cdcd2c7f53e51f3937b17"
    }
  ]
}
```

You can improve your users' onboarding experience by comparing existing social graphs (for example from Facebook or email address books) to find friends already using Buddycloud.

The results of this query are usually displayed to the end user as a "People you may know" screen.

The [friend finder](https://github.com/buddycloud/buddycloud-friend-finder) is social graph agnostic. Examples of providers that are currently in use are:

Social Graph | Calculating
-------------|--------------
`email`      | `sha256(email:<email-address>)`
`phone`      | `sha256(phone:<MSISDN format number>)`
`Twitter`    | `sha256(twitter:<Twitter username without the '@'>)`
`Facebook`   | `sha256(facebook:<numeric-id>)`  (retrieving the [Facebook numeric-id](https://developers.facebook.com/tools/explorer/145634995501895/?method=GET&path=me%3Ffields%3Did%2Cname&version=v2.0))

###Calculating the hash
For example, the phone number `+1 212 555 1212` would be rewritten to the [MSISDN](http://en.wikipedia.org/wiki/MSISDN) equivalent (no spaces, no leading `+`) and calculated as `sha256(phone:12125551212)` to return a hash of `48efa7f270c05714225254fb71b0e8f691379177fc298547c74261faf0ef6d08`.

The `POST` requst should prefix the hash with `mine` and `others` to identify the hash source. Multiple `mine` hashes can be uploaded at the same time.

###Privacy
The [friend finder](https://github.com/buddycloud/buddycloud-friend-finder) service only ever uploads hashes of identifiers; never real `names`, `phone` numbers, `Twitter` or Facebook` identifiers.

Always request your users permission before uploading any identifiers.

### HTTP Request
`POST https://demo.buddycloud.org/api/match_contacts`
