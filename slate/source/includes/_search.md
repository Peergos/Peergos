#Search

You can search for authors, metadata, channels, and channel content.

There are two search services on Buddycloud that are tuned for different needs,

- *Local search:* finds content on your own Buddycloud domain.
- *Buddycloud-wide search:* finds channels, posts and metadata from public channels using the [Buddycloud crawler](https://github.com/buddycloud/channel-directory).

Search type      | Local search | Buddycloud-wide search
-----------------|--------------|------------------------
Channel content  | public and private channels | public channels
Post author      | yes          | no
Channel metadata | yes          | yes
Location         | no           | yes

##Search by Content
```shell
#POST https://demo.buddycloud.org/api/search?type=content&q={queryKey}

curl https://demo.buddycloud.org/api/search?type=content&q=Romeo \
     -x GET






```

```javascript










```

New posts are crawled and should show up in search results after a few minutes. This will return public posts.

##Search by Author
```shell
#Unsupported Method









```

```javascript










```

Query for a specific user's posts.


##Search by Metadata

```shell
#POST https://demo.buddycloud.org/api/search?type=metadata?q={queryKey}

curl https://demo.buddycloud.org/api/search?type=metadata&q=Romeo \
     -x GET






```

```javascript










```

Query for channels by metadata.

##Search by Location

```shell










```

```javascript
#Unsupported Method









```

Query for channels by location. This search will return channels nearby to a latitude and longitude.

<aside>You can set a channel's latitude and longitude in the in the [metadata](#update-metadata)</aside>
