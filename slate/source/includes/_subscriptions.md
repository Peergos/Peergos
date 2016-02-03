#Subscriptions

Retrieving a user's channel subscription list will return the nodes that they follow and their affiliation with that node, which can be either:

* `owner`
* `moderator`
* `publisher`
* `member`
* `outcast`

...as well as the nodes where they have a subscription state of `pending` (which means the subscription is waiting to be approved).

### Subscription Privacy

Users can only request their own subscriptions and are unable to view other user's subscriptions.

##Fetch Subscriptions

> `GET` /api/subscribed

> ###Example
> Fetching subscriptions of `juliet@peergos.org`, using `curl`:

```shell
curl https://peergos.org/api/subscribed \
     -X GET \
     -u juliet@peergos.org:romeo-forever \
     -H "Accept: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

{
    "juliet@peergos.org/posts": "owner",
    "juliet@peergos.org/status": "owner",
    "romeo@peergos.org/posts": "pending",
    "capulet@topics.peergos.org/posts": "publisher"
}
```

Returns the user's channel subscriptions as a JSON object.

The object's keys are of the form `{channel}`/`{node}`.

The values denote the subscription type:

* `owner`
* `moderator`
* `publisher`
* `member`
* `pending`

##Follow Channel

> `POST` /api/subscribed

> ###Example
> `juliet@peergos.org` is subscribing to `romeo@peergos.com/posts`, using `curl`:

```shell
curl https://peergos.org/api/subscribed \
     -X POST \
     -u juliet@peergos.org:romeo-forever \
     -H "Content-Type: application/json" \
     -d '{ "romeo@peergos.org/posts": "publisher" }'
```

Following behavior is dependent on the channel type:

* Following an _open_ channel is automatically allowed.
* Following a _private_ channel generates a `pending` subscription request

Following a _private_ channel:

* the user is added to the channel's `pending` subscription list
* the channel's `owner` or a `moderator` receives a subscription request (immediately if they are online or queued up for when they come online)
* either the channels `owner` or any of the channels `moderator`s approves (or rejects) and the result is then sent back to the user trying to follow the channel via a push notification
* the user's subscription state is changed to `subscribed`, `publisher` (or `none` if rejected) depending on the channel's `default_affiliation` 

<aside>It is possible to set the default affiliation for new followers of a node. Users with either <kbd>owner</kbd> and <kbd>moderator</kbd> affiliation can adjust the <kbd>default_affiliation</kbd> by changing a node's metadata.</aside>

##Unfollow Channel

> ###Same endpoint as before, but passing 'none' in the payload

> `POST` /api/subscribed

> ###Example

> `juliet@peergos.org` unfollows `romeo@peergos.com/posts`, using `curl`:

```shell
curl https://peergos.org/api/subscribed \
     -X POST \
     -u juliet@peergos.org:romeo-forever \
     -H "Content-Type: application/json" \
     -d '{ \
             "romeo@peergos.org/posts": "none" \
         }'
```

Unfollowing a private channel removes the ability to read, upvote or delete posts. 

Unfollowing a private channel will require re-requesting a subscription and re-approval of a moderator. 
