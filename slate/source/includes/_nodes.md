#Nodes

Nodes are publish-subscribe streams of posts with the same content type.

Each node has:

- `publishers` who can post new content;
- `followers` consumers of content;
- metadata (e.g. `title`, `description` and `location` fields).

### Personal Nodes v. Topic Nodes

Buddycloud divides nodes into two categories: `topic` and `personal`.

For example, the channel `juliet@capulet.lit` comprises personal nodes for each type of information that `juliet` wants to share, such as her social activities, reflections on her mood, and the media she comments on. 

 _Personal_ Node              | _Topic_ Node
---------------------------------|-----------------------
 e.g. `juliet@capulet.lit/{nodeID}`       | e.g. `the-montagues@topics.montague.org/posts`
 represents a real person        | represents a topic
 `<channelID>@example.com` | `<channelID>@topics.example.com`
 named after a user's `username`| not tied to a user's `username`
 owned by the matching `username`| can be owned by any user
 can receive private chat messages| not applicable
 geolocation optionally shared with followers| anyone can search for nearby channels

### Node Privacy Settings

Nodes may be private or public. Node Privacy is controlled by the node's `access_model `:

               |Public Node | Private Node
---------------|---------------|-----------------
**access model**   |open           |authorize
**visibility**     |anyone can view | requires a subscription request to view

The node metadata for _public_ and _private_ nodes is always publicly accessible.

### Who creates the set of nodes for a channel?

Your application is responsible for creating the set of nodes it is going to use for a given channel. For example, the social application running at <https://buddycloud.com> automatically creates the following nodes everytime a new channel is created:

Name             | Personal Channel |Topic Channel | Description 
-----------------|:---------------: |:------------:|----------------
`status`           | ✓                | ✓            | a one-line status message 
`posts`            | ✓                | ✓            | ATOM formatted activity stream 
`geoloc-previous`  | ✓                | ✗            | where they were              
`geoloc-current`   | ✓                | ✗            | where they are              
`geoloc-future`    | ✓                | ✗            | where they will go next   
`public-key`       | ✓                | ✗            | public key for secure messaging

##Create Node

> `POST` /api/`channelID`/`nodeID`

> ###Example
> Creating a node called `new-node` using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/new-node \
    -X POST \
    -u juliet@buddycloud.com:romeo-forever
```

This allows creation of nodes.

##Delete Node

> `DELETE` /api/`channelID`/`nodeID`

> ###Example
> Deleting the node called `new-node` using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/new-node \
    -X DELETE \
    -u juliet@buddycloud.com:romeo-forever
```

This will remove a node.

##Fetch Metadata

> `GET` /api/`channelID`/metadata/`nodeID`

> ###Example
> Fetching the node `new-node`'s metadata using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/metadata/new-node \
     -X GET \
     -u juliet@buddycloud.com:romeo-forever
```

Metadata allows you to describe the node, set defaults and even add a location to the node so that it will show up in nearby queries.

Node metadata is always visible for both public and private nodes.

##Update Metadata

> `POST` /api/`channelID`/metadata/`nodeID`

> ###Example
> Updating the node `new-node`'s metadata using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/metadata/new-node \
     -X POST \
     -u juliet@buddycloud.com:romeo-forever \
     -H "Content-Type: application/json" \
     -d '{ \
            "title": "New Node Title", \
            "description": "Everything about Juliet", \
            "default_affiliation": "publisher" \
         }'
```

Use this endpoint to update a given node's metadata.

### Parameters

Field            | Edit? | Values | Description
------------------- | -------- | -------| -----------
`channelID`           | false    | ≤1023 bytes | `user@<domain>` or `topic@topics.<domain>`
`title`               | true     | up to 50 characters | the node's title
`description`         | true     | up to 200 characters | a short string describing the node 
`creation_date`       | false    | [RFC3399](https://tools.ietf.org/html/rfc3339) timestamp | when the node was created
`access_model`        | true    | `open`, `authorize` | whether the node is `public` or `private`
`channel_type`       | false   | `personal`, `topic` | whether this is a `personal` node or a `topic` node
`default_` `affiliation` | true | `publisher`, `follower` | the permissions a new subscriber is granted

A complete set of node metadata is available from the [Buddycloud protocol specification](http://buddycloud.github.io/buddycloud-xep/#default-roles). 

