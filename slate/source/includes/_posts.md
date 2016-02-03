#Posts

New posts to a node are automaticaly:

* pushed to the channel's online followers
* spooled up for the channel's offline followers
* archived and retrievable using the [fetch posts](#fetch-posts) method.
* indexed by the [channel crawler](https://github.com/buddycloud/channel-directory)

Different channel types have different post formats.

> Field value examples:

### Post Parameters

Field       | Description | Responsible | <div style="display:none;">Example</div>
------------|-------------|--------|---------
`author`    | the `username` of this post's author, set by the server  | server | <div class="highlight"><pre style="position:absolute; right:0px;"><code>juliet@capulet.lit</code></pre></div>
`content`   | the post's content - usually this is activity stream event | user   | <div class="highlight"><pre style="position:absolute; right:0px;"><code>O Romeo, Romeo! Wherefore art thou Romeo?</code></pre></div>
`id`        | the unique `POST_ID` assigned to this post by the server | server | <div class="highlight"><pre style="position:absolute; right:0px;"><code>17163726-ea90-453e-ad25-455336a83fd4</code></pre>
`media`     | a list of media objects this post might refer to | user | <div class="highlight"><pre style="position:absolute; right:0px;"><code>[ { "id": "romeo-photo-id", "channel": "juliet@capulet.lit" } ]</code></pre></div>
`replyTo`   | the parent `POST_ID` if this post is a reply to another post | user | <div class="highlight"><pre style="position:absolute; right:0px;"><code>9b7724d0-7ef5-4331-8974-81754abb7ba0</code></pre></div>
`published` | the date when this post was created | server | <div class="highlight"><pre style="position:absolute; right:0px;"><code>2012-11-02T03:41:55.484Z</code></pre></div>
`updated`   | the last time there was a reply in this thread or when the post was created | server | <div class="highlight"><pre style="position:absolute; right:0px;"><code>2012-11-02T03:41:55.484Z</code></pre></div>

###Pagination

Buddycloud uses [Result Set Management](http://xmpp.org/extensions/xep-0059.html) for pagination of results obtained by the different endpoints for fetching posts specified below. This is useful when:

* building mobile applications and needing to limit the amount of data that the API sends back. 
* your app needs to retrieve new messages since it was last online.

###Query Parameters

You will pass pagination parameters via the URL query part.

Parameter | Description
--------- |  -----------
`max`     | The maximum number of returned entries
`before`  | Get posts newer than entry with this `POST_ID`
`after`   | Return only entries older than the entry with this `POST_ID`

##Create Post

> `POST` /api/`channelID`/content/`nodeID`

> ###Example
> Creating a new post to the `posts` node of `romeo@buddycloud.com`, using `curl`:

```shell
curl https://buddycloud.com/api/romeo@buddycloud.com/content/posts \
     -X POST \
     -u juliet@buddycloud.com:romeo-forever \
     -H "Content-Type: application/json" \
     -d '{ "content": "O Romeo, Romeo, wherefore art thou Romeo?" }'
```

> Response will be as follows:

```shell
HTTP/1.1 201 Created

Location: https://buddycloud.com/romeo@buddycloud.com/content/posts/$POST_ID
```

Use this endpoint to create new post to a given node.

<aside>You can define your own format for your own application nodes (e.g. <kbd>x-application-chessApp-move-history</kbd>). The default channel nodes have a pre-defined format and will reject posts that are not formated according to what the server expects. For example, the <kbd>posts</kbd> node expects to receive Activity stream events.</aside>

##Delete Post

> `DELETE` /api/`channelID`/content/`nodeID`/`postID`

> ###Example
> Deleting post of id `$POST_ID` from the `posts` node of `romeo@buddycloud.com`, using `curl`:

```shell
curl https://buddycloud.com/api/romeo@buddycloud.com/content/posts/$POST_ID \
     -X DELETE \
     -u juliet@buddycloud.com:romeo-forever 
```

Removes a post from a node.

When a post is deleted,

* the post is deleted from the channel-node's history,
* a retraction message is sent to online users.

<aside>Deleting a post that references a <kbd>mediaID</kbd> will not remove the media object from the media server. That should be done seperately using a delete media query.</aside>

##Fetch Specific Post

> `GET` /api/`channelID`/content/`nodeID`/`postID`

> ###Example
> Fetching specific post of id `$POST_ID` from the `juliet@buddycloud.com/posts` node, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/content/posts/$POST_ID \
    -X GET \
    -H "Accept: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

{
    "id": "$POST_ID",
    "author": "romeo@buddycloud.com",
    "updated": "1595-06-01T12:00:00Z",
    "content": "But, soft! What light through yonder window breaks? It is the east, and Juliet is the sun.",
    "media": null
}
```

If you have interest in fetching a particular post whose `$POST_ID` is already known to you, you can use this endpoint for that matter.

For obvious reasons, this endpoint doesn't support pagination. The other endpoints below potentially return multiple entries, with proper pagination support.

##Fetch a Post's Child Posts

> `GET` /api/`channelID`/content/`nodeID`/`postID`/replyTo

> ###Example
> Fetching child posts of post to node `juliet@buddycloud.com/posts` of id `$POST_ID` using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/content/posts/$POST_ID/replyTo \
     -X GET \
     -H "Accept: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "id": "$NEWEST_CHILD_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1595-06-01T12:00:00Z",
        "content": "But, soft! What light through yonder window breaks? It is the east, and Juliet is the sun.",
        "replyTo": "$POST_ID",
        "media": null
    },
    ...
    {
        "id": "$OLDEST_CHILD_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1591-06-04T12:00:00Z",
        "content": "Thus with a kiss I die.",
        "replyTo": "$POST_ID",
        "media": null
    }
]
```

> ###Same endpoint using pagination

> `GET` /api/`channelID`/content/`nodeID`/`postID`/replyTo?
> `param`=`val`&`param`=`val`...

> ###Example
> Fetching posts from a node using pagination, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/content/posts?after=$NEWEST_POST_ID \
     -X GET \
     -H "Accept: application/json"
```

> Then, response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
    [Post with id == "NEWEST_CHILD_POST_ID" omitted]
    ...
    {
        "id": "$OLDEST_CHILD_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1591-06-04T12:00:00Z",
        "content": "Thus with a kiss I die.",
        "replyTo": "$POST_ID",
        "media": null
    }
]
```


Retrieves one or more posts that are children of a given node specified by `$POST_ID` using pagination ranges.

##Fetch Recent Posts

> `GET` /api/`channelID`/content/`nodeID`

> ###Example
> Fetching the most recent posts from the `juliet@buddycloud.com/posts` node, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/content/posts \
     -X GET \
     -H "Accept: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "id": "$NEWEST_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1595-06-01T12:00:00Z",
        "content": "But, soft! What light through yonder window breaks? It is the east, and Juliet is the sun.",
        "media": null
    },
    ...
    {
        "id": "$OLDEST_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1591-06-04T12:00:00Z",
        "content": "Thus with a kiss I die.",
        "media": null
    }
]
```

> ###Same endpoint using pagination

> `GET` /api/`channelID`/content/`nodeID`?
> `param`=`val`&`param`=`val`...

> ###Example
> Fetching posts from a node using pagination, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/content/posts?max=3 \
     -X GET \
     -H "Accept: application/json"
```

> Then, response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "id": "$NEWEST_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1595-06-01T12:00:00Z",
        "content": "But, soft! What light through yonder window breaks? It is the east, and Juliet is the sun.",
        "media": null
    },
    ...
    {
        "id": "$OLDEST_POST_ID",
        "author": "romeo@buddycloud.com",
        "updated": "1591-06-04T12:00:00Z",
        "content": "Thus with a kiss I die.",
        "media": null
    }
]
```

Retrieves one or more posts using pagination ranges.

This is useful for retrieving recent posts to an individual node.

Often it's useful to quickly show, for example, the 20 most recent posts. However some of these posts may reference a parent post outside of you apps' cache.

<aside>To retrieve a missing parent post, you can use the <kbd>POST_ID</kbd> referenced by the post's <kbd>replyTo</kbd> field in the endpoint for querying a particular post, in the section depicted above.</aside>

For another approach, please refer to the Fetch Recent Post Threads section depicted below.

##Fetch Recent Post Threads

> `GET` /api/`channelID`/content/`nodeID`/threads

> ###Example
> Fetching the most recent post threads from the `juliet@buddycloud.com/posts` node, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/content/posts/threads \
     -X GET \
     -H "Accept: application/json"
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
  {
    "id": "$PARENT_POST_ID"
    "updated": "2015-01-30T01:39:00.070Z",
    "items": [
        {
            "id": "$OLDEST_CHILD_POST_ID",
            "author": "romeo@buddycloud.com",
            "updated": "1595-06-01T12:00:00Z",
            "content": "First reply.",
            "replyTo": "$PARENT_POST_ID"
            "media": null
        },
        ...
        [More Child Posts...]
        ...
        {
            "id": "$NEWEST_CHILD_POST_ID",
            "author": "romeo@buddycloud.com",
            "updated": "1595-06-01T12:00:00Z",
            "content": "Last thing ever said.",
            "replyTo": "$PARENT_POST_ID"
            "media": null
        },
        {
            "id": "$PARENT_POST_ID",
            "author": "romeo@buddycloud.com",
            "updated": "1591-06-04T12:00:00Z",
            "content": "First thing ever said.",
            "media": null
        }
    ]
  }
  ...
  [More Post Threads]
]
```

Retrieves one or more post threads, that is, collections of chained posts, using pagination ranges (where `max` depicts the maximum number of threads expected in the response).

<aside>Each thread response is comprised of metadata and a JSON array of posts with the <kbd>items</kbd> key. This array contains all child posts belonging to the thread, ordered from oldest child post to newest child post and then, the parent post, which is the root of the thread.</aside>
