#Content Recommendation

You can increase user engagement in your app by using the results of the recommended, similar and popular channel query.

This is useful if a user has just started using your buddycloud App. You can now show them popular channels across all Buddycloud enabled sites, or limit the scope to just popular channels on your Buddycloud site.

Based on what channels a user follows, the recommendation service will then suggest other channels that they might be interested in.

It is also possible to query for similar channels to a channel. For example, querying for similar channels to football@example.com might return, worldcup@example.org and fifa-scandal@other-domain.com.

<aside>Buddycloud enabled domains [that federate with other Buddycloud enabled domains] are reguarly crawled for a list of channels and followers. The crawler uses the list of followers to build a social graph that is then used for recommendation.</aside>


##Recommend Channels

```shell
curl https://demo.buddycloud.org/api/???? \
 --??? \
 --???
```

```javascript
???
???
```

```json
???
???
```

Returns a list of recommended channels based on the channels that the user already follows.

### HTTP Request
`POST https://demo.buddycloud.org/api/????` 

##Similar Channels

```shell
curl https://demo.buddycloud.org/api/???? \
 --??? \
 --???
```

```javascript
???
???
```

```json
???
???
```

Returns a list of channels similar to the queried channel.

### HTTP Request
`POST https://demo.buddycloud.org/api/????` 

##Most active channels
```shell
curl https://demo.buddycloud.org/api/most_active?domain=example.com&period=7 
```
```shell
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
```

```javascript
???
???
```

```json
???
???
```

Returns a list of the most active channels both on the local buddycloud domain (if ```domain``` is specified), or across all buddycloud sites. The most active channels are the channels with the greater number of new posts in the last ```period``` days. 

### HTTP Request
`POST https://demo.buddycloud.org/api/most_active` 
