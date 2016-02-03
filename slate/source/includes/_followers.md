#Followers

Followers of the nodes in a channel have one of the following affiliations:

Affiliation | User sees      | Description
-----------|----------------|-------------
`owner`    |`owner`         |add/remove moderators 
`moderator`|`moderator`     |approve subscription requests & delete posts
`publisher`|`follower+post` |create new posts
`member`   |`follower`      |only view posts
`pending`  |`pending`       |nothing
`outcast`  | `null`         |only visibile to the `owner` and `moderator` affiliation

The `outcast` affiliation is useful for dealing with abusive users. 

<aside>Once a user is an <kbd>outcast</kbd>, they can no longer post or generate subscripion requests to that channel. Only the channel's <kbd>owner</kbd> and the channel's <kbd>moderator</kbd>s can view users the list of <kbd>outcast</kbd> users.</aside>

##Fetch Followers

> `GET` /api/`channelID`/subscribers/`nodeID`

> ###Example

> Fetching followers of the `romeo@peergos.org/posts` node, using `curl`:

```shell
curl https://peergos.org/api/romeo@peergos.com/subscribers/posts \
     -X GET \
     -u romeo@peergos.org:juliet-forever \
     -H "Content-Type: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

{
    "romeo@peergos.org": "owner",
    "benvolio@peergos.org": "publisher"
}
```

This request returns a list of channel followers and their affiliation in the channel.

Public channels | Private Channels
----------------|------------------
Return the list without authentication | requesting user must also be an `owner` `moderator` `publisher` or `member` of the channel

##Retrieve Pending Followers

> `GET` /api/`channelID`/subscribers/`nodeID`/approve

> ###Example

> Retrieving pending subscriptions to the `romeo@peergos.org` node, using `curl`:

```shell
curl https://peergos.org/api/romeo@peergos.com/subscribers/posts/approve \
     -X GET \
     -u romeo@peergos.org:juliet-forever \
     -H "Content-Type: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type application/json

[
    {
        "subscription": "subscribed",
        "jid": "benvolio@peergos.org"
    },
    {
        "subscription": "pending",
        "jid": "juliet@peergos.org"
    },
    {
        "subscription": "pending",
        "jid": "tybalt@peergos.org"
    }
]
```

Retrieves the list of subscriptions of a node. Returns a list of objects containing the subscriber JID and the values of its subscription state (`subscribed` or `pending`).

##Authorise Pending Followers

> `POST` /api/`channelID`/subscribers/`nodeID`/approve

> ###Example

> Authorising subscription of `juliet@peergos.org` and denying to `tybalt@peergos.com`, using `curl`:

```shell
curl https://peergos.org/api/romeo@peergos.com/subscribers/posts/approve \
     -X POST \
     -u romeo@peergos.org:juliet-forever \
     -H "Content-Type: application/json" \
     -d '[ \
             { \
                 "subscription": "subscribed", \
                 "jid": "juliet@peergos.org" \
             }, \
             { \
                 "subscription": "none", \
                 "jid": "tybalt@peergos.org" \
             } \
         ]'
```

This allows a channel's `owner` or `moderator` to approve or deny incoming subscription requests.

Subscription State | Description
-------------|--------------
`pending`    | No action taken by `owner` or `moderator`.
`subscribed` | Permission to follow channel granted. 
`none`       | Permission to follow channel denied.


##Alter Follower Affiliations

> `POST` /api/`channelID`/subscribers/`nodeID`

> ###Example

> Changing `juliet@peergos.org`'s subscription affiliation to `romeo@peergos.com/posts` to `member`, using `curl`:

```shell
curl https://peergos.org/api/romeo@peergos.com/subscribers/posts \
     -X POST \
     -u romeo@peergos.org:juliet-forever \
     -H "Content-Type: application/json" \
     -d '{ \
             "juliet@peergos.org": "member" \
         }'
```

This enables users to promote (or e) user subscriptions to `publisher`, `member` or even `moderator`. By setting a subscription to `outcast`, the user is banned.
