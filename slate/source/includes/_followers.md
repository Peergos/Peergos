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

> Fetching followers of the `romeo@buddycloud.com/posts` node, using `curl`:

```shell
curl https://buddycloud.com/api/romeo@buddycloud.com/subscribers/posts \
     -X GET \
     -u romeo@buddycloud.com:juliet-forever \
     -H "Content-Type: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

{
    "romeo@buddycloud.com": "owner",
    "benvolio@buddycloud.com": "publisher"
}
```

This request returns a list of channel followers and their affiliation in the channel.

Public channels | Private Channels
----------------|------------------
Return the list without authentication | requesting user must also be an `owner` `moderator` `publisher` or `member` of the channel

##Retrieve Pending Followers

> `GET` /api/`channelID`/subscribers/`nodeID`/approve

> ###Example

> Retrieving pending subscriptions to the `romeo@buddycloud.com` node, using `curl`:

```shell
curl https://buddycloud.com/api/romeo@buddycloud.com/subscribers/posts/approve \
     -X GET \
     -u romeo@buddycloud.com:juliet-forever \
     -H "Content-Type: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type application/json

[
    {
        "subscription": "subscribed",
        "jid": "benvolio@buddycloud.com"
    },
    {
        "subscription": "pending",
        "jid": "juliet@buddycloud.com"
    },
    {
        "subscription": "pending",
        "jid": "tybalt@buddycloud.com"
    }
]
```

Retrieves the list of subscriptions of a node. Returns a list of objects containing the subscriber JID and the values of its subscription state (`subscribed` or `pending`).

##Authorise Pending Followers

> `POST` /api/`channelID`/subscribers/`nodeID`/approve

> ###Example

> Authorising subscription of `juliet@buddycloud.com` and denying to `tybalt@buddycloud.com`, using `curl`:

```shell
curl https://buddycloud.com/api/romeo@buddycloud.com/subscribers/posts/approve \
     -X POST \
     -u romeo@buddycloud.com:juliet-forever \
     -H "Content-Type: application/json" \
     -d '[ \
             { \
                 "subscription": "subscribed", \
                 "jid": "juliet@buddycloud.com" \
             }, \
             { \
                 "subscription": "none", \
                 "jid": "tybalt@buddycloud.com" \
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

> Changing `juliet@buddycloud.com`'s subscription affiliation to `romeo@buddycloud.com/posts` to `member`, using `curl`:

```shell
curl https://buddycloud.com/api/romeo@buddycloud.com/subscribers/posts \
     -X POST \
     -u romeo@buddycloud.com:juliet-forever \
     -H "Content-Type: application/json" \
     -d '{ \
             "juliet@buddycloud.com": "member" \
         }'
```

This enables users to promote (or e) user subscriptions to `publisher`, `member` or even `moderator`. By setting a subscription to `outcast`, the user is banned.
