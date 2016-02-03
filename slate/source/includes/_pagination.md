#Pagination

```shell
#GET https://buddycloud.com/api/{channelId}?{queryParameter}={value}

curl https://buddycloud.com/api/juliet@buddycloud.org?max=10 \
     -X GET
```

```javascript
#XMPP-FTW event 'xmpp.buddycloud.retrieve'

socket.send(
    'xmpp.buddycloud.retrieve',
    {
        node: '/user/juliet@buddycloud.com/posts',
        rsm: {
            max: 10
        },
        callback
    }
)
```

Buddycloud uses [Result Set Management](http://xmpp.org/extensions/xep-0059.html) for pagination. This is useful when:

* building mobile applications and needing to limit the amount of data that the API sends back. 
* your app needs to retrieve new messages since it was last online.
 
When using the *REST API*, you will pass pagination parameters via the URL query part, while *XMPP-FTW* requires an `rsm` JSON object as part of the outgoing data, as described in <https://xmpp-ftw.jit.su/manual/result-set-management/>.

## Query Parameters

The following query parameters are available:

Parameter | Description
--------- |  -----------
`max`     | The maximum number of returned entries
`before`  | Get posts before this timestamp
`after`   | Return only entries older than the entry with the specified ID

## Response Attributes

The following attributes are returned in a paged query response:

Parameter | Description
--------- |  -----------
`count`   | The total number of entries that the query would return
`first`   | The ID of the first item in the page
`last`    | The ID of the last item in the page
