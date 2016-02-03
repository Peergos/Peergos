Title: API (old version)
url: api-old
save_as: api-old.html
order: -1
table_of_contents: false
show_in_top_menu: false

The HTTP API is implemented by the [buddycloud HTTP API
server](https://github.com/buddycloud/buddycloud-http-api).

The buddycloud HTTP API presents a simple way to access buddycloud
channels through a REST-like interface. It is thought as an alternative
to buddycloud's [XMPP interface](http://buddycloud.github.io/buddycloud-xep/).

---

<!---
Current used diagrams images:

1 - http://www.websequencediagrams.com/?lz=Y2xpZW50IC0-ICtBUEkgc2VydmVyOiBIVFRQIHJlcXVlc3QKAA8KACMFWE1QUAAgCWNyZWF0ZSAADwVjb25uZWN0aW9uCgAZCyAtLT4gLQBRDAAeDwA9B2QAWBBCdWRkeWNsb3VkACwOAIEPCAAPEQBTF3Jlc3BvbnNlAIE7DS0-IC0AgXYGAIFkCQAfBw&s=default

2 - http://www.websequencediagrams.com/?lz=Y2xpZW50IC0-ICtBUEkgc2VydmVyOiByZXF1ZXN0CgAKCgAeBU1lZGlhABwJZm9yd2FyZAAkCQASDCAtPiAARQxjaGVjawBOCW5vdGUgcmlnaHQgb2YgAEgOWEVQLTAwNzAAbw0tPgAXDwCBGgcgb2sAZhErQnVkZHljbG91ZACBRwlnZXQgUHViU3ViIGFmZmlsaWF0aW9ucwB6DwAmEwCBCAY2MAoARBEAgRAFLQCCBA4ATxQAgigMAIIUDy0-IC0AgmgOc3BvbnNlAIFrEC0AgyAGAIJmDAAiBw&s=default

3 - http://www.websequencediagrams.com/?lz=Y2xpZW50IC0-ICtBUEkgc2VydmVyOiByZXF1ZXN0CgAKCgAeBUNoYW5uZWwgRGlyZWN0b3J5OiBmb3J3YXJkACkJABIRIC0tPiAtAE8Oc3BvbnNlAFENLT4gLQCBBwYASAwAIgc&s=default

4 - http://www.websequencediagrams.com/?lz=Y2xpZW50IC0-ICtBUEkgc2VydmVyOiBIVFRQIHJlcXVlc3QKAA8KACMFWE1QUAAgCWNyZWF0ZSBhY2NvdW50ACoJABkLIC0tPiAtAFEMACMRc3BvbnNlAFsNLT4gLQCBFgYAgQQJAB8H&s=default

5 - http://www.websequencediagrams.com/?lz=Y2xpZW50IC0-ICtBUEkgc2VydmVyOiBIVFRQIHJlcXVlc3QKAA8KACMFUHVzaGVyOiBYTVAAGgoADwYgLS0-IC0APQwAHgdzcG9uc2UAPQ0tPiAtAHgGAGYJAB8H&s=default

6 - http://www.websequencediagrams.com/?lz=Y2xpZW50IC0-ICtBUEkgc2VydmVyOiBIVFRQIHJlcXVlc3QKAA8KACMFRnJpZW5kIEZpbmRlcjogWE1QACEKAA8NIC0tPiAtAEsMACUHc3BvbnNlAEsNLT4gLQCBBgYAdAkAHwc&s=default
-->

General Notes
=============

HTTPS only
:   All requests should be sent using HTTPS.

UTF-8 encoding
:   Each string passed to and from the buddycloud API must be UTF-8
    encoded. (In the case of JSON set "Content-Type" to
    "application/json; charset=utf-8")

Time format
:   All dates accepted and sent by the API are in [ISO
    8601](https://en.wikipedia.org/wiki/ISO_8601) format (example:
    "2012-08-21T22:31:20+0000"). In
    [strftime](http://pubs.opengroup.org/onlinepubs/007908799/xsh/strftime.html)
    format, this equals the following specification:
    *%Y-%m-%dT%H:%M:%SZ*

Tools for experiments
:   An online-tool which can be helpful for experiments with the API:
    [apigee](https://apigee.com/console/others)

---

Finding the API server
======================

Since each domain runs their own buddycloud API server, you can use DNS
to discover the API server for a domain.

A DNS lookup for the `_buddycloud-api._tcp.example.com` [TXT
record](https://en.wikipedia.org/wiki/TXT_record) will give you the
server. <tabber> dig command=

~~~~ bash
$ dig txt _buddycloud-api._tcp.EXAMPLE.COM

;; ANSWER SECTION:
_buddycloud-api._tcp.EXAMPLE.COM.         IN TXT "v=1.0 host=buddycloud.EXAMPLE.COM protocol=https path=/api port=443"
~~~~

This tells a client that any API calls for `example.com` should be made
against `https://buddycloud.example.com:443/api` </tabber>

---

Authenticating
==============

For requests that require authentication, use the [HTTP
Basic](http://en.wikipedia.org/wiki/Basic_access_authentication) method.
The username should be a qualified username, like *user@example.com*.
Note that only users belonging to the API server's "home" domain are
accepted. (You cannot authenticate as *somebody@domain1.com* at the API
server of *domain2.net*.)

---

Content Types
=============

A buddycloud channel is essentially a collection of publish subscribe
nodes. Each post is a complete Atom formatted entry.

A [JSON](http://www.json.org/)-based format is also available that
includes the most important information present in the Atom feeds. To
retrieve - pass the following request headers:

`Accept: application/json`\
`Content-Type: application/json`

The JSON format represents each channel node item as an object with the
following string attributes:

id
:   The item's ID.


author
:   The user ID of the item's author.


updated
:   The date/time the entry was posted or last edited, in [ISO 8601
    format](https://en.wikipedia.org/wiki/ISO_8601).

content
:   The entry's text content.


media
:   Custom structured field, with necessary media information for
    fetching and displaying it.


replyTo
:   The ID of the entry that this entry replies to. Only defined for
    comments.

A JSON channel node feed is simply an array of such item objects.

---

API Endpoints
=============

---

Account
-------

### /account

client->API server: HTTP request
API server->XMPP server: create account request
XMPP server-->API server: create account response
API server-->client: HTTP response

Description
:   Creates or deletes an user account.

Attributes
:   *username* - XMPP username (user@domain or simply user, which will
    use the default domain configured in the API).
:   *password* - A password for the XMPP account.
:   *email* - E-mail address to receive push notifications.

Accept
:   JSON *(application/json)*

Methods
:   **POST** - Creates an user account.
:   **DELETE** - Deletes an user account.

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is required, but not
    provided.

Example

<tabber> POST= Creates an user account

~~~~ bash
POST /account

{
  "username": "alice@shakespeare.lit",
  "password": "pass123",
  "email": "alice@email.com"
}
~~~~

~~~~ javascript
200 OK
~~~~

|-| DELETE= Deletes an user account

~~~~ bash
DELETE /account
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

---

### /account/pw/change

client->API server: HTTP request
API server->XMPP server: create account request
XMPP server-->API server: create account response
API server-->client: HTTP response

Description
:   Allows for user password changes.

Content Types
:   JSON *(application/json)*

Methods
:   **POST** - Changes the user's password.

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is not provided.

Examples

<tabber> JSON POST= Updates user's password:

~~~~ bash
POST /account/pw/change
Content-Type: application/json

{
  "username": "alice@example.com",
  "password": "newpassword"
}
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

---

### /account/pw/reset

client->API server: HTTP request
API server->XMPP server: create account request
XMPP server-->API server: create account response
API server-->client: HTTP response

Description
:   Allows for user password reset.

Content Types
:   JSON *(application/json)*

Methods
:   **POST** - Resets the user's password.

Responses
:   **200 OK** on success.

Notes
:   When its current password is reset, the user receives an email with
    a new (random) password.

Examples

<tabber> JSON POST= Resets user's password:

~~~~ bash
POST /account/pw/reset
Content-Type: application/json

{
  "username": "alice@example.com"
}
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

Channel Content
------------

### /:channel/content/:node

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

#### Description
:   Receives or posts to a channel node, which represents a stream of
    channel content. Every channel has at least a **posts** node, which
    holds all posts published in the channel. Other types of channel
    nodes such as *geoloc* (location information) and *status* will be
    added in the future.

#### Content Types
:   Atom *(application/atom+xml)*
:   JSON *(application/json)*

#### Methods
:   **GET** - Retrieves a channel node's content as Atom feed.
:   **POST** - Adds an Atom entry to the node. The created item's URL is
    returned in the response's *Location* header.

#### Parameters
:   **max** *(optional)* - The maximum number of returned entries. Only
    allowed for GET.
:   **after** *(optional)* - Return only entries older than the entry
    with the specified ID. Only allowed for GET.

#### Responses
:   **200 OK** if GET succeeds.
:   **201 Created** if POST succeeds.
:   **400 Bad Request** if an invalid Atom entry is posted.
:   **401 Unauthorized** if authentication is required, but not
    provided.
:   **403 Forbidden** if the user is not permitted to view or post to
    the channel node (e.g. if the channel is private).
:   **404 Not Found** if the specified channel or node does not exist.

#### Post Threading

For items representing comments, the corresponding Atom entries
include a `replyTo` (JSON) or `<in-reply-to/>` (XML) element as
specified in the [Atom Threading
Extensions](http://www.ietf.org/rfc/rfc4685.txt)

<tabber> JSON= uses `replyTo`. For example:

~~~~ javascript
      { "author" : "alice@example.com",
        "content" : "Ok",
        "id" : "17163726-ea90-453e-ad25-455336a83fd4",
        "media": null,
        "published" : "2012-11-02T03:41:55.484Z",
        "replyTo" : "9b7724d0-7ef5-4331-8974-81754abb7ba0",
        "updated" : "2012-11-02T03:41:55.484Z"
      }
~~~~

|-| XML=

~~~~ xml
<entry xmlns="http://www.w3.org/2005/Atom">

  <!-- This entry is a comment on the post with ID "original-post" -->
  <in-reply-to xmlns="http://purl.org/syndication/thread/1.0" ref="original-post"/>
</entry>
~~~~

</tabber>

Notes
:   When posting an item, the item's ID and author don't need to be
    specified as they are filled in by the channel server.
:   (Observation on 2012-12-09: When only the item's content is
    specified, then published and updated are set to null. That is not
    necessarily how it should be. In such a case the server could set
    published to the current date and time.)
:   To make the item a comment, use the `replyTo` attribute for JSON or
    the `<in-reply-to/>` element (for Atom).

Examples

<tabber> JSON GET=

~~~~ bash
GET /alice@examle.com/content/posts
Accept: application/json
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

[
  {
    "id": "foo",
    "author": "alice@example.com",
    "updated": "2012-06-01T12:00:00Z",
    "content": "This is my newest post!",
    "media": null
  },
  {
    "id": "bar",
    "author": "alice@example.com",
    "updated": "2012-05-31T12:00:00Z",
    "content": "June starts tomorrow.",
    "media": null
  },
  {
    "id": "baz",
    "author": "alice@example.com",
    "updated": "2012-05-30T12:00:00Z",
    "content": "Feeling good today!",
    "media": [{"id": "qwe", "channel": "alice@example.com"}]
  }
]
~~~~

|-| JSON POST=

~~~~ javascript
POST /bob@example.com/content/posts
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
Content-Type: application/json

{
  "content": "Hello JSON!"
}
~~~~

~~~~ xml
201 Created
Location: http://api.example.com/bob@example.com/content/posts/bipp
~~~~

|-| XML= Retrieve all posts from "alice@example.com":

~~~~ bash
GET /alice@example.com/content/posts
~~~~

~~~~ xml
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Alice's posts</title>
  <entry>
    <id>foo</id>
    <author>
      <name>alice@example.com</name>
      <id>acct:alice@example.com</id>
    </author>
    <published>2012-06-01T12:00:00Z</published>
    <content>This is my newest post!</content>
  </entry>
  <entry>
    <id>bar</id>
    <author>
      <name>alice@example.com</name>
      <id>acct:alice@example.com</id>
    </author>
    <published>2012-05-31T12:00:00Z</published>
    <content>June starts tomorrow.</content>
  </entry>
  <entry>
    <id>baz</id>
    <author>
      <name>alice@example.com</name>
      <id>acct:alice@example.com</id>
    </author>
    <published>2012-05-30T12:00:00Z</published>
    <content>Feeling good today!</content>
    <media>
       <item id="qwe" channel="alice@example.com"/>
    </media>
  </entry>
</feed>
~~~~

Retrieve the newest post from "alice@example.com":

~~~~ bash
GET /alice@example.com/content/posts?max=1
~~~~

~~~~ xml
200 OK
Content-Type: application/atom+xml

<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Alice's posts</title>
  <entry>
    <id>foo</id>
    <author>
      <name>alice@example.com</name>
      <id>acct:alice@example.com</id>
    </author>
    <published>2012-06-01T12:00:00Z</published>
    <content>This is my newest post!</content>
  </entry>
</feed>
~~~~

Get the two newest posts that are older than the one retrieved above:

~~~~ bash
GET /alice@example.com/content/posts?max=2&after=foo
~~~~

~~~~ xml
200 OK
Content-Type: application/atom+xml

<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Alice's posts</title>
  <entry>
    <id>bar</id>
    <author>
      <name>alice@example.com</name>
      <id>acct:alice@example.com</id>
    </author>
    <published>2012-05-31T12:00:00Z</published>
    <content>June starts tomorrow.</content>
  </entry>
  <entry>
    <id>baz</id>
    <author>
      <name>alice@example.com</name>
      <id>acct:alice@example.com</id>
    </author>
    <published>2012-05-30T12:00:00Z</published>
    <content>Feeling good today!</content>
  </entry>
</feed>
~~~~

Add a post to "bob@example.com":

~~~~ xml
POST /bob@example.com/content/posts
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
Content-Type: application/atom+xml

<entry xmlns="http://www.w3.org/2005/Atom">
  <content>Hello World!</content>
</entry>
~~~~

~~~~ xml
201 Created
Location: http://api.example.com/channels/bob@example.com/posts/item?id=buux
~~~~

Comment on the newest post of "alice@example.com":

~~~~ xml
POST /alice@example.com/content/posts
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
Content-Type: application/atom+xml

<entry xmlns="http://www.w3.org/2005/Atom">
  <in-reply-to ref="foo" xmlns="http://purl.org/syndication/thread/1.0"/>
  <content>And this is my newest comment ;)</content>
</entry>
~~~~

~~~~ xml
201 Created
Location: http://api.example.com/alice@example.com/content/posts/fooboo
~~~~

</tabber>

---

### /:channel/content/:item/

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Allows access to single items (e.g., posts) of a channel node. Both
    Atom and JSON representations are available (see [\#Channel Content
    Formats](#Channel_Content_Formats "wikilink")).

Content Types
:   Atom *(application/atom+xml)*
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves an item of a channel node.
:   **PUT** *(not implemented)* - Replaces an item with the Atom entry
    specified in the request body.
:   **DELETE** - Deletes an item.

Responses
:   **200 OK** on success.
:   **400 Bad Request** if a PUT request contains an invalid Atom entry.
:   **401 Unauthorized** if authentication is required, but not
    provided.
:   **403 Forbidden** if the user is not permitted to view or edit the
    item.
:   **404 Not Found** if the specified channel, node or item does not
    exist.

Notes
:   A channel node item may only be modified by the user who published
    it, and removed only by its publisher or the channel's owner.

Examples

<tabber> JSON GET= Retrieve a post

~~~~ bash
GET /alice@example.com/content/posts/baz
Accept: application/json
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{
  "id": "baz",
  "author": "alice@example.com",
  "updated": "2012-05-30T12:00:00Z",
  "content": "Feeling good today!"
  "media": [{"id": "qwe", "channel": "alice@example.com"}]
}
~~~~

|-| XML GET= Retrieve the post with ID "baz" of "alice@example.com":

~~~~ bash
GET /alice@example.com/content/posts/baz
~~~~

~~~~ xml
200 OK
Content-Type: application/atom+xml

<entry xmlns="http://www.w3.org/2005/Atom">
  <id>baz</id>
  <author>
    <name>alice@example.com</name>
    <id>acct:alice@example.com</id>
  </author>
  <published>2012-05-30T12:00:00Z</published>
  <content>Feeling good today!</content>
  <media>
    <item id="qwe" channel="alice@example.com"/>
  </media>
</entry>
~~~~

|-| DELETE= Deletes the post with ID "baz" of "alice@example.com":

~~~~ bash
DELETE /alice@example.com/content/posts/baz
~~~~

~~~~ xml
204 OK
No Content
~~~~

</tabber>

Channel Metadata
-------------

### /:channel/metadata/:node

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Gets or sets metadata about a channel node.

Attributes
:   *title* - The channel node's title.
:   *description* - A short string describing the node.
:   *creation\_date* - The date the node was created in
    [RFC339](http://www.ietf.org/rfc/rfc3339.txt) format.
:   *access\_model* - Whether the node is public ("open") or private
    ("authorize").
:   *channel\_type* - The type of the containing channel ("personal" or
    "topic").
:   *default\_affiliation* - New users default affiliation ("publisher"
    or "follower").

Content Type
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves values in the channel node's metadata.
:   **POST** - Updates values in the channel node's metadata.

Parameters
:   (none)

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is required, but not
    provided.
:   **403 Forbidden** if the user is not permitted to access the channel
    node.
:   **404 Not Found** if the specified channel or node does not exist.

Notes
:   The title and description of the channel's *posts* node define the
    respective attributes of the channel itself.

Example

<tabber> JSON= Retrieve the metadata of the "posts" node of
"alice@example.com":

~~~~ bash
GET /alice@example.com/metadata/posts
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{
  "title": "Alice's Posts",
  "description": "Everything about Alice",
  "creation_date": "2012-05-30",
  "access_model": "whitelist",
  "channel_type": "personal",
  "default_affiliation": "publisher"
}
~~~~

</tabber>

Subscriptions
--------------------------

### /:channel/subscribers/:node

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Can be used to get a channel node's subscribers or to change the
    affiliation of a channel node's subscribers.

Content Type
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the list of subscribers as JSON object. The
    object's keys are the subscriber's usernames, the values are their
    member types ("owner", "publisher" or "member").
:   **POST** - Changes the affiliation of the channel node's
    subscribers. The body must be a JSON object with exactly one
    key-value pair of the form returned by GET. The new valid member
    type values provided will be assigned to each existing subscriber.
    Only the channel onwer and moderators of the channel are allowed to
    perform this operation. Only the channel onwer can assign followers
    the member type "moderator" (thus promoting them to moderators) or
    change affiliation roles of moderators.

Parameters
:   (none)

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if the request is not authenticated.
:   **403 Forbidden** if the user is not permitted to access the channel
    node or perform the affiliation change operation.
:   **404 Not Found** if the specified channel or node does not exist.

Examples

<tabber> JSON GET= Retrieve the subscribers of the "posts" node of
"alice@example.com":

~~~~ bash
GET /alice@example.com/subscribers/posts
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{
  "alice@example.com": "owner",
  "bob@example.com": "publisher"
}
~~~~

|-| JSON POST= Change the affiliation role of "bob@example.com" (who
follows the "posts" node of "alice@example.com") to "member":

~~~~ bash
POST /alice@example.com/subscribers/posts
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
Content-Type: application/json

{"bob@example.com": "member"}
~~~~

~~~~ javascript
200 OK
~~~~

The affiliation role of "bob@example.com" should be updated:

~~~~ bash
GET /alice@example.com/subscribers/posts
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{
  "alice@example.com": "owner",
  "bob@example.com": "member"
}
~~~~

</tabber>

---

### /:channel/subscribers/:node/approve

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Lists and modifies the state of subscriptions in a node. Should be
    used in order to list, approve or deny pending subscription
    requests.

Content Type
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the list of subscriptions of a node. Returns a
    list of objects containing the subscriber JID and the values of its
    subscription state ("subscribed" or "pending").
:   **POST** - Changes the subscription state of subscribers. Possible
    states are: "subscribed", in order to approve a pending request; or
    "none", in order to deny it.

Parameters
:   (none)

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if the request is not authenticated.
:   **403 Forbidden** if the user is not permitted to access the channel
    node or perform the subscription change operation.
:   **404 Not Found** if the specified channel or node does not exist.

Examples

<tabber> JSON GET= Retrieve subscription states of the "posts" node of
"alice@example.com":

~~~~ bash
GET /alice@example.com/subscribers/posts/approve
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

[
  {"subscription": "subscribed", "jid": "doe@example.com"},
  {"subscription": "pending", "jid": "bob@example.com"},
  {"subscription": "pending", "jid": "john@example.com"}
]
~~~~

|-| JSON POST= Approves the request from "bob@example.com" and denies
the one from "john@example.com":

~~~~ bash
POST /alice@example.com/subscribers/posts/approve
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
Content-Type: application/json

[
  {"subscription": "subscribed", "jid": "bob@example.com"},
  {"subscription": "none", "jid": "john@example.com"}
]
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

---

### /subscribed

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Returns the user's subscriptions.

Content Type
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the list of subscribed-to channels as a JSON
    object. The object's keys are of the form "{channel}/{node}", the
    values denote the subscription type ("owner", "publisher", "member"
    or "pending").
:   **POST** - Subscribes to or unsubscribes from a node. The body must
    be a JSON object with exactly one key-value pair of the form
    returned by GET. The node will be subscribed to if the value is
    "publisher" or "member", while "none" means unsubscription.

Parameters
:   (none)

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if the request is not authenticated.

Examples

<tabber> JSON GET= Retrieve the own subscribed-to channel nodes as
"alice@example.com":

~~~~ bash
GET /subscribed
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{
  "alice@example.com/posts": "owner",
  "bob@example.com/posts": "publisher",
  "public@topics.example.com/posts": "publisher"
}
~~~~

|-| JSON POST= Subscribe to "bob@example.com":

~~~~ bash
POST /subscribed
Authorization: Basic Ym9iQGV4YW1wbGUuY29tOmJvYg==
Content-Type: application/json

{"bob@example.com/posts": "publisher"}
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

Media
-----

### /:channel/media

client->API server: HTTP: request
Note right of API server: token generation
API server->Media server: HTTP: auth request with token
Media server->API server: XMPP: "dial-back" to confirm
Note right of Media server: XEP-0070
Note right of API server: checking request origin
API server-->Media server: XMPP: confirms request origin
Media server->Buddycloud server: XMPP: check perms
Note right of Buddycloud server: XEP-0060
Buddycloud server-->Media server: XMPP: response
Media server-->API server: HTTP: response
API server-->client: forward response

Description
:   Uploads media to a channel or lists the media in a channel

Methods
:   **GET** - Gets all media metadata information from the specified
    channel.
:   **POST** - Uploads a media file to the specified channel.

Content-Type (POST)
:   *application/x-www-form-urlencoded*
:   *multipart/form-data*

Arguments (POST)
:   ***data*** - the media to upload
:   ***content-type*** - the media content-type
:   ***filename** (optional)* - the media original filename
:   ***title** (optional)* - the title of the media
:   ***description** (optional)* - a description of the media

Parameters
:   ***max** (optional)* - maximum number of returned metadata. Only
    allowed for GET.
:   ***after** (optional)* - only metadata older than the entry with the
    specified ID. Only allowed for GET.

Responses
:   **200 OK** - if get succeeds, including the media metadata.
:   **201 Created** - if post succeeds, including the successfully
    uploaded media metadata.
:   **400 Bad Request** - if the client not provide proper
    authentication fields.
:   **401 Unauthorized** - if authentication is required, but not
    provided.
:   **403 Forbidden** - if the user is not permitted to get or post to
    the ----------channel (e.g. if the channel is private).

Media Metadata
:   **height** *server-set* height of the uploaded image or video. This
    is calculated by the server and not editable.
:   **width** *server-set* width of the uploaded image or video. This is
    calculated by the server and not editable.
:   **author** *required* the buddycloudID of the author
:   **entityId** *required* the buddycloudID of the channel or user that
    owns the media
:   **shaChecksum** *required* SHA1 file checksum
:   **mimeType** *required* mimetype
:   **fileName** *required* the uploaded filename (including extension)
:   **uploadedDate** *required* when the media was uploaded
:   **lastUpdatedDate** *required* last time this media was updated
:   **title** *optional* media title
:   **description** *optional* media description

Notes
:   GET operations for public channels don't need authentication.

Examples

<tabber> JSON GET=

:   retrieve a list of all media in a channel together with metadata

~~~~ bash
GET https://api.example.com/channel@topics.domain.com/media
~~~~

:   response, if everything went ok

~~~~ javascript
// 200 response
{{
  "id":"lETuJi8rPE4IfQrygN6rVtGx3",
  "fileName":"testimage.jpg",
  "author":"alice@domain.com",
  "title":"Test Image",
  "mimeType":"image/jpeg",
  "description":"My Test Image",
  "fileExtension":"jpg",
  "shaChecksum":"bc46e5fac2f1cbb607c8b253a5af33181f161562",
  "fileSize":60892,
  "height":312,
  "width":312,
  "entityId":"channel@topics.domain.com"
 },
 ...
 {
  "id":"QrygN6rlETufVtGx3Ji8rPE4I",
  "fileName":"summer.avi",
  "author":"alice@domain.com",
  "title":"Summer 2012",
  "mimeType":"video/avi",
  "description":"Summer video",
  "fileExtension":"avi",
  "shaChecksum":"c2f1cbb6bc46e5fa07c8b251615623a5af33181f",
  "fileSize":120988,
  "height":640,
  "width":480,
  "entityId":"channel@topics.domain.com"
 }}
~~~~

|-| JSON POST= upload a new item to **channel@topics.domain.com**

~~~~ javascript
POST {"filename": "testimage.jpg",
      "title": "Test Image",
      "description": "My test image",
      "content-type": "image/jpeg",
      "data": image}
      https://api.example.com/channel@topics.domain.com/media
~~~~

:   response

~~~~ javascript
// 201 response
{
  "id":"lETuJi8rPE4IfQrygN6rVtGx3",
  "fileName":"testimage.jpg",
  "author":"alice@domain.com",
  "title":"Test Image",
  "mimeType":"image/jpeg",
  "description":"My Test Image",
  "fileExtension":"jpg",
  "shaChecksum":"bc46e5fac2f1cbb607c8b253a5af33181f161562",
  "fileSize":60892,
  "height":312,
  "width":312,
  "entityId":"channel@topics.domain.com"
}
~~~~

</tabber>

---

### /:channel/media/:media

client->API server: HTTP: request
Note right of API server: token generation
API server->Media server: HTTP: auth request with token
Media server->API server: XMPP: asks for request confirmation
Note right of Media server: XEP-0070
Note right of API server: checking request origin
API server-->Media server: XMPP: confirms request origin
Media server->Buddycloud server: XMPP: check permissions to access media
Note right of Buddycloud server: XEP-0060
Buddycloud server-->Media server: XMPP: response to permission request
Media server-->API server: HTTP: request response
API server-->client: forward response

Description
:   Updates metadata, deletes or gets a channel media.

Methods
:   **POST** - Updates the metadata of the specified media.
:   **GET** - Gets the specified media file.
:   **DELETE** - Deletes the specified media.

Content-Type (POST)
:   *application/x-www-form-urlencoded*

Arguments (POST)
:   ***filename** (optional)* - the new media filename
:   ***title** (optional)* - the new title of the media
:   ***description** (optional)* - a new description for the media

Parameters
:   **maxheight** (optional) - if the media can be previewed (image or video), returns a thumbnail bounded by the specified height. (GET
    only)
:   **maxwidth** (optional) - if the media can be previewed (image or video), returns a thumbnail bounded by specified width. (GET only)

Responses
:   **200 OK** - get or post succeeds, including the media file or the media metadata.
:   **400 Bad Request** - the client not provide correct authentication parameters.
:   **401 Unauthorized** - authentication is required, but not provided.
:   **403 Forbidden** - the user is not permitted to update, get or
    delete the media (e.g. if the user is not a private channel's owner
    or moderator).
:   **404 Not Found** - there is no media with such id.
:   **412 Precondition Failed** - if the client requests a thumbnail of
    a media with a not valid format (different from image or video).

Notes
:   GET operations for public channels don't need authentication.
:   DELETE removes all media's information, including thumbnails and
    metadata.

Examples

<tabber> JSON GET=

:   Get media

~~~~ bash
GET https://api.example.com/channel@topics.domain.com/media/lETuJi8rPE4IfQrygN6rVtGx3?maxwidth=640
~~~~

~~~~ javascript
// 200 response with the media content
~~~~

|-| JSON POST=

:   update the media information

~~~~ javascript
POST {"filename": "newname.jpg",
      "title": "New Title",
      "description": "New description",
      https://api.example.com/channel@topics.domain.com/media/lETuJi8rPE4IfQrygN6rVtGx3
~~~~

:   response, if everything went ok

~~~~ javascript
// 200 response
{
  "id":"lETuJi8rPE4IfQrygN6rVtGx3",
  "fileName":"newname.jpg",
  "author":"alice@domain.com",
  "title":"New Title",
  "mimeType":"image/jpeg",
  "description":"New description",
  "fileExtension":"jpg",
  "shaChecksum":"bc46e5fac2f1cbb607c8b253a5af33181f161562",
  "fileSize":60892,
  "height":312,
  "width":312,
  "entityId":"channel@topics.domain.com"
}
~~~~

|-| JSON DELETE=

:   Delete the media

~~~~ bash
DELETE https://api.example.com/channel@topics.domain.com/media/lETuJi8rPE4IfQrygN6rVtGx3
~~~~

~~~~ javascript
// 200 response
~~~~

</tabber>

---

### /:channel/media/avatar

client->API server: HTTP: request
Note right of API server: token generation
API server->Media server: HTTP: auth request with token
Media server->API server: XMPP: asks for request confirmation
Note right of Media server: XEP-0070
Note right of API server: checking request origin
API server-->Media server: XMPP: confirms request origin
Media server->Buddycloud server: XMPP: check permissions to access media
Note right of Buddycloud server: XEP-0060
Buddycloud server-->Media server: XMPP: response to permission request
Media server-->API server: HTTP: request response
API server-->client: forward response

Description
:   Special endpoint that handles channel's avatars: uploads, updates,
    deletes or gets a channel avatar.

Methods
:   **PUT** - Uploads an image to be the avatar of the specified
    channel. If there is a previous one, it is replaced.
:   **POST** - Updates the avatar *metadata*.
:   **GET** - Gets the avatar from the specified channel.
:   **DELETE** - Deletes the avatar from the specified channel.

Content-Type (PUT)
:   *application/x-www-form-urlencoded*
:   *multipart/form-data*

Arguments (PUT)
:   ***data*** - the avatar to upload
:   ***content-type*** - the avatar content-type
:   ***filename** (optional)* - the avatar original filename
:   ***title** (optional)* - the title of the avatar
:   ***description** (optional)* - a description of the avatar

Arguments (POST)
:   ***filename** (optional)* - the avatar new filename
:   ***title** (optional)* - the new title of the avatar
:   ***description** (optional)* - a new description for the avatar

Parameters
:   **maxheight** (optional) - returns an avatar thumbnail that fits the
    specified height. Only allowed for GET.
:   **maxwidth** (optional) - returns an avatar thumbnail that fits the
    specified width. Only allowed for GET.

Responses
:   **200 OK** - if get succeeds, including the avatar file.
:   **201 Created** - if put succeeds, including the successfully
    uploaded avatar metadata.
:   **400 Bad Request** - if the client not provide proper
    authentication fields.
:   **401 Unauthorized** - if authentication is required, but not
    provided.
:   **403 Forbidden** - if the user is not permitted to put or delete
    the channel's avatar (e.g. if the user is not the channel's owner or
    moderator).
:   **404 Not Found** - there is no avatar set to the specified channel.

Note
:   All avatars are public, that means that the GET operation doesn't
    need authentication.

Examples

<tabber> JSON PUT= upload a new avatar

~~~~ javascript
PUT {"filename": "avatar.jpg",
      "title": "My New Avatar",
      "description": "My brand new avatar",
      "content-type": "image/jpeg",
      "data": image}
      https://api.example.com/alice@domain.com/media/avatar
~~~~

:   response, if everything went ok

~~~~ javascript
// 201 response
{
  "id":"lETuJi8rPE4IfQrygN6rVtGx3",
  "fileName":"avatar.jpg",
  "author":"alice@domain.com",
  "title":"My New Avatar",
  "mimeType":"image/jpeg",
  "description":"My brand new avatar",
  "fileExtension":"jpg",
  "shaChecksum":"bc46e5fac2f1cbb607c8b253a5af33181f161562",
  "fileSize":60892,
  "height":312,
  "width":312,
  "entityId":"alice@domain.com"
}
~~~~

|-| JSON GET=

:   Visualize Alice's new avatar

~~~~ bash
GET https://api.example.com/alice@domain.com/media/avatar
~~~~

~~~~ javascript
// 200 response with the picture content
~~~~

|-| JSON DELETE= Delete the avatar

~~~~ bash
DELETE https://api.example.com/alice@domain.com/media/avatar
~~~~

~~~~ javascript
// 200 response
~~~~

</tabber>

Channel Management
------------------

### /:channel

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Used to create a topic channel.

Methods
:   **POST** - Creates a topic channel with default configuration.

Parameters
:   (none)

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is not provided.
:   **403 Forbidden** if the user is not allowed to create a channel.

Examples

<tabber> POST= Crates the topic channel
"talesofalice@topics.example.com":

~~~~ bash
POST /talesofalice@topics.example.com
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

---

### /:channel/similar

client->API server: HTTP request
API server->Channel Directory: XMPP request
Channel Directory-->API server: XMPP response
API server-->client: HTTP response

Description
:   Retrieves similar channels to a given channel based on the social
    graph indexed by the [Channel
    Directory](Channel Directory Project "wikilink").

Content Type
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the list of similar channels of a given channel.

Parameters
:   ***max*** (optional) - the maximum number of items to be returned.
:   ***index*** (optional) - the index for the first item to be
    returned.

Responses
:   **200 OK** on success.

Examples

<tabber> GET= Retrieve similar channels to node "alice@example.com":

~~~~ bash
GET /alice@example.com/similar
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{ "items" : [ { "channelType" : "topic",
        "creationDate" : "2012-04-24T09:13:51+0000",
        "defaultAffiliation" : "publisher",
        "description" : "/dev/hack",
        "jid" : "team@topics.buddycloud.org",
        "title" : "buddycloud dev team"
      },
      { "channelType" : "personal",
        "creationDate" : "2012-02-05T13:31:12+0000",
        "defaultAffiliation" : "publisher",
        "description" : "Everything about Bob.",
        "jid" : "bob@example.com",
        "title" : "Bob's Posts"
      }
    ],
  "rsm" : { "count" : "2",
      "index" : "0"
    }
}
~~~~

</tabber>

Content Sync
------------

### /sync

client->API server: HTTP request
API server->XMPP server: create XMPP connection
XMPP server-->API server: XMPP connection created
API server->Buddycloud server: XMPP request
Buddycloud server-->API server: XMPP response
API server-->client: HTTP response

Description
:   Allows access to the latest posts of all channel nodes a user
    subscribes to. Only JSON representation is available for this query
    (see [\#Channel Content
    Formats](#Channel_Content_Formats "wikilink")).

Content Types
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the latests items of all channel nodes an user
    subcribes to.

Arguments (GET)
:   ***since*** - is used to filter out messages before a certain
    date/time.
:   ***max*** - the maximum number of posts to be returned per channel.
:   ***summary*** - (optional) if true, returns only a summary
    information per channel.

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is not provided.

Notes
:   The since argument must be given in the ISO 8601, following the
    given format: CCYY-MM-DDThh:mm:ssT. Eg.: 2012-11-01T00:00:00Z

Examples

<tabber> JSON GET=

-   retrieve **all** channel posts since: `2012-11-01T00:00:00Z`
-   but not more than `2` per channel

~~~~ bash
GET /sync?since=2012-11-01T00:00:00Z&max=2
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{ "/user/bob@example.com/posts" : [ { "author" : "bob@example.com",
        "content" : "Nice post.",
        "id" : "a8eb0384-b504-4aab-9b51-6174a7f9efe7",
        "published" : "2012-11-01T20:06:01.787Z",
        "replyTo" : "08795523-b719-4329-b334-3ee1667aa9c3",
        "updated" : "2012-11-01T20:06:01.787Z"
      } ],
  "/user/alice@example.com/posts" : [ { "author" : "bob@example",
        "content" : "Is it a nice post?",
        "id" : "209aceda-32ef-4754-9746-c1dbc5716359",
        "published" : "2012-11-02T03:42:28.510Z",
        "updated" : "2012-11-02T03:42:28.510Z"
      },
      { "author" : "alice@example.com",
        "content" : "Ok",
        "id" : "17163726-ea90-453e-ad25-455336a83fd4",
        "published" : "2012-11-02T03:41:55.484Z",
        "replyTo" : "9b7724d0-7ef5-4331-8974-81754abb7ba0",
        "updated" : "2012-11-02T03:41:55.484Z"
      }
    ]
}
~~~~

|-| JSON GET with summary=true=

-   retrieve **all** post counters since: `2012-11-01T00:00:00Z`

~~~~ bash
GET /sync?since=2012-11-01T00:00:00Z&summary=true&max=1000
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{ "/user/bob@example.com/posts" : {
    "mentionsCount": 2,
    "totalCount": 121,
    "repliesCount": 34,
    "postsThisWeek": [
      "2013-11-08T19:33:33.103Z",
      "2013-11-06T12:12:34.986Z",
      "2013-11-05T23:45:31.609Z",
      "2013-11-05T14:42:30.625Z",
      "2013-11-05T14:34:43.580Z",
      "2013-11-05T14:20:49.844Z",
      "2013-11-03T19:27:16.048Z",
      "2013-11-03T18:46:54.726Z"
    ]
  },
  "/user/alice@example.com/posts" : {
    "mentionsCount": 1,
    "totalCount": 2,
    "repliesCount": 0,
    "postsThisWeek": []
  }
}
~~~~

</tabber>

Notification Settings
---------------------

### /notification\_settings

client->API server: HTTP request
API server->Pusher: XMPP request
Pusher-->API server: XMPP response
API server-->client: HTTP response

Description
:   Allows retrieval and modification on the user's notification
    settings.

Content Types
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the user's notification settings.
:   **POST** - Updates the user's notification settings.
:   **DELETE** - Deletes user's notification settings.

Parameters
:   **type** - Type of notification transport (email, gcm)
:   **target (optional)** - Target address (email address, gcm registration id)

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is not provided.

Notes
:   During a POST, any notification setting can be omitted. Thus, the
    setting will only be updated when provided.

Examples

<tabber> JSON GET= Retrieves notification settings:

~~~~ bash
GET /notification_settings?type=email&target=mail@alice.com
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

[{
  "target": "mail@alice.com",
  "postAfterMe": "true",
  "postMentionedMe": "true",
  "postOnMyChannel": "true",
  "postOnSubscribedChannel": "false",
  "followMyChannel ": "true",
  "followRequest": "true"
}]
~~~~

|-| JSON POST= Updates notification settings:

~~~~ bash
POST /notification_settings
Content-Type: application/json

{
  "type": "email",
  "target": "mail@alice.com",
  "postAfterMe": "true",
  "postMentionedMe": "true",
  "postOnMyChannel": "true",
  "postOnSubscribedChannel": "false",
  "followMyChannel ": "false",
  "followRequest": "false"
}
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

[{
  "target": "mail@alice.com",
  "postAfterMe": "true",
  "postMentionedMe": "true",
  "postOnMyChannel": "true",
  "postOnSubscribedChannel": "false",
  "followMyChannel ": "false",
  "followRequest": "false"
}]
~~~~

|-| JSON DELETE= Deletes notification settings:

~~~~ bash
DELETE /notification_settings
Content-Type: application/json

{
  "type": "email",
  "target": "mail@alice.com",
}
~~~~

~~~~ javascript
200 OK
~~~~

</tabber>

Search and Recommendations
--------------------------

### /search

client->API server: request
API server->Channel Directory: forward request
Channel Directory-->API server: response
API server-->client: forward response

Description
:   Performs a search over channels' metadata and posts' content indexed
    by the [Channel Directory](https://buddycloud.org/wiki/Channel_Directory_Project).

Content Types
:   JSON *(application/json)*

Methods
:   **GET** - Performs a search query to the channel directory. It can
    be performed over posts(type=content) or channels(type=metadata).

Arguments (GET)
:   ***type*** - **metadata** or **content**.
:   ***q*** - the search criteria.
:   ***max*** (optional) - the maximum number of items to be returned.
:   ***index*** (optional) - the index for the first item to be
    returned.

Responses
:   **200 OK** on success.
:   **400 Bad Request** - if the client does not provide the mandatory
    fields.

Examples

<tabber> JSON GET for metadata= Retrieve channels that have the keyword
**hack** in their metadata:

~~~~ bash
GET /search?type=metadata&q=hack
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{"items":
  [{"jid":"team@topics.buddycloud.org",
    "description":"/dev/hack",
    "creationDate":"2012-04-24T09:13:51+0000",
    "title":"buddycloud dev team",
    "channelType":"topic",
    "defaultAffiliation":"publisher"}
  ],
 "rsm":
  {"index":"0","count":"1"}
}
~~~~

|-| JSON GET for content= Retrieve posts that have the keyword **hack**
in their content:

~~~~ bash
GET /search?type=content&q=hack
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{ "items" : [ { "author" : "jayteeuk@wyrddreams.org",
        "content" : "Hacking in the evening of Online Services Sprint, Day One.",
        "id" : "1c6cf8b1-cfad-41fb-864a-73accf614484",
        "in_reply_to" : null,
        "parent_fullid" : "/user/jayteeuk@wyrddreams.org/posts",
        "parent_simpleid" : "jayteeuk@wyrddreams.org",
        "published" : "2012-11-12T21:53:49+0000",
        "updated" : "2012-11-12T21:53:49+0000"
      },
      { "author" : "tester1234@buddycloud.org",
        "content" : "hack hack",
        "id" : "d3cd8bc5-5722-42cd-9b95-1313fbe6644b",
        "in_reply_to" : null,
        "parent_fullid" : "/user/tester1234@buddycloud.org/posts",
        "parent_simpleid" : "tester1234@buddycloud.org",
        "published" : "2012-09-25T23:58:00+0000",
        "updated" : "2012-09-25T23:58:00+0000"
      }
    ],
  "rsm" : { "count" : "2",
      "index" : "0"
    }
}
~~~~

</tabber>

---

### /recommendations

client->API server: request
API server->Channel Directory: forward request
Channel Directory-->API server: response
API server-->client: forward response

Description
:   Recommends channels for a given user based on taste data indexed by
    the [Channel Directory](https://buddycloud.org/wiki/Channel_Directory_Project).

Content Types
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves channels recommendations for a given user.

Arguments (GET)
:   ***user*** - the jid of the user to be recommended.
:   ***max*** (optional) - the maximum number of items to be returned.
:   ***index*** (optional) - the index for the first item to be
    returned.

Responses
:   **200 OK** on success.
:   **400 Bad Request** - if the client does not provide the mandatory
    fields.

Examples

<tabber> JSON GET= Recommend channels for user **alice@example.com**:

~~~~ bash
GET /recommendations?user=alice@example.com
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{ "items" : [ { "channelType" : "topic",
        "creationDate" : "2012-04-24T09:13:51+0000",
        "defaultAffiliation" : "publisher",
        "description" : "/dev/hack",
        "jid" : "team@topics.buddycloud.org",
        "title" : "buddycloud dev team"
      },
      { "channelType" : "personal",
        "creationDate" : "2012-02-05T13:31:12+0000",
        "defaultAffiliation" : "publisher",
        "description" : "Everything about Bob.",
        "jid" : "bob@example.com",
        "title" : "Bob's Posts"
      }
    ],
  "rsm" : { "count" : "2",
      "index" : "0"
    }
}
~~~~

</tabber>

Most Active
-----------

### /most\_active

client->API server: request
API server->Channel Directory: forward request
Channel Directory-->API server: response
API server-->client: forward response

Description
:   Retrieves the most active channels among the channels indexed by the
    [Channel Directory](https://buddycloud.org/wiki/Channel_Directory_Project). The most active channels are the ones that had more posts in the window of a day.

Content Types
:   JSON *(application/json)*

Methods
:   **GET** - Retrieves the most active channels.

Arguments (GET)
:   ***max*** (optional) - the maximum number of items to be returned.
:   ***index*** (optional) - the index for the first item to be
    returned.

Responses
:   **200 OK** on success.

Examples

<tabber> JSON GET= Retrieve the most active channels:

~~~~ bash
GET /most_active
~~~~

~~~~ javascript
200 OK
Content-Type: application/json

{ "items" : [ { "channelType" : "topic",
        "creationDate" : "2012-08-05T14:57:26+0000",
        "defaultAffiliation" : "publisher",
        "description" : "This is an RSS feed from the BBC News - World website.  Original: http://feeds.bbci.co.uk/news/world/rss.xml",
        "jid" : "bbc_news_-_world@topics.buddycloud.org",
        "title" : "BBC News - World"
      },
      { "channelType" : "personal",
        "creationDate" : "2011-10-06T12:02:58+0000",
        "defaultAffiliation" : "publisher",
        "description" : "Flipping you the channel bits. Night and day, the distributed way",
        "jid" : "simon@buddycloud.org",
        "title" : "Simon's chonnel"
      }
    ],
  "rsm" : { "count" : "2",
      "index" : "0"
    }
}
~~~~

</tabber>

Match Contacts
--------------

### /match_contacts

client->API server: HTTP request
API server->Friend Finder: XMPP request
Friend Finder-->API server: XMPP response
API server-->client: HTTP response

Description
:   Publishes user contacts' hashes and matches previously reported hashes to jids. A hash is computed as following: sha256(provider:id), eg: sha256(facebook:1015747641) = 0a22c6c85a47116509f8fbb7688c98ac480651db3c54dd3fcd2ce34d48a5025b.

Attributes
:   *mine* - array of this user's hashes to be reported to the friend finder.
:   *others* - array of hashes to be matched by the friend finder.

Accept
:   JSON *(application/json)*

Methods
:   **POST** - Publishes and matches hashes to jids.

Responses
:   **200 OK** on success.
:   **401 Unauthorized** if authentication is required, but not
    provided.

Example

<tabber> POST= Publishes and matches hashes to jids

~~~~ bash
POST /match_contacts

{
  "mine": ["0a22c6c85a47116509f8fbb7688c98ac480651db3c54dd3fcd2ce34d48a5025b"],
  "others": ["023476e7b8be135f970d65f9bee53bfc66c43742815cdcd2c7f53e51f3937b17",   "bcee94d395fe9cd76dfae390e006a98032144fb48dc8181448c4cd192ec4b75c"]
}
~~~~

~~~~ javascript
200 OK

{
    "items": [
        {
            "jid": "alice@example.com",
            "matched-hash": "023476e7b8be135f970d65f9bee53bfc66c43742815cdcd2c7f53e51f3937b17"
        }
    ]
}
~~~~

</tabber>
