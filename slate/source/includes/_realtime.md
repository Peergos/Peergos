#Realtime

To get content updates in realtime directly through the REST API, your application can rely on long-polling an endpoint specifically designed with this goal in mind. The first section below depicts it in detail.

But to make your application truly aware of all kinds of Buddycloud events, you'll need more.

If you're building a mobile app, you can set it up so that it will listen for incoming push notifications when important events happen. The last sections below describe every step needed to make your app fully Buddycloud-powered.

Otherwise, the recommended thing is to have your app use [XMPP-FTW](https://xmpp-ftw.jit.su/manual/extensions/buddycloud/). Please also refer to this guide with more on [how to get started](http://buddycloud.com/get-started-javascript).

##New Post Updates

> ###First, get the last known timestamp

> `GET` /api/notifications/posts

> ###Example

> Asking for the last known timestamp, using `curl`:

```shell
curl https://buddycloud.com/api/notifications/posts \
    -X GET
```

> Response would be as follows:

```shell
{
    "last": "$LAST_TIMESTAMP",
    "items": []
}
```

> ###Finally, start long polling

> `GET` /api/notifications/posts?since=`lastTT`

> ###Example

> Using the last known timestamp to start long polling:

```shell
curl https://buddycloud.com/api/notifications/posts?since=$LAST_TIMESTAMP \
    -X GET
```

> Once a new update arrives, response would be as follows:

```shell
{
    "last": "$LAST_TIMESTAMP_UPDATED",
    "items": [
        {
            "id": "$POST_ID",
            "source": "juliet@buddycloud.com/posts"
            "author": "romeo@buddycloud.com"
            "published": "2014-06-24T15:34:54.449Z",
            "updated": "2014-06-24T15:34:54.449Z",
            "content": "This the newest post in town",
            "media": null,
            "replyTo": "$PARENT_POST_ID"
        }
        ...
        [Potentially more posts from this node or other nodes here]
    ]
```

> Don't forget that the last known timestamp now is `$LAST_TIMESTAMP_UPDATED`!

###Get New Post Updates By Long Polling

Use this endpoint to start long polling for new posts updates.
Multiple updates shall arrive at a time, for all nodes the user is subscribed to (the user your app is currently working with).

Your application will need to provide the last known timestamp `lastTT` everytime it makes a new long polling request.
On the right there are good examples of the intended flow of this process of getting new posts updates.

A new posts update should arrive in the form of a JSON response comprised of all the new posts' contents alongside information about the nodes that the new post belong to, respectively (the `source` key of each post has this value).

##Push Notifications

> ###Get SENDER ID From The Pusher

> `GET` /api/notification_metadata?type=gcm

> ###Example

> Retrieving `$SENDER_ID` from the `buddycloud.com`'s Pusher service, using `curl`:

```shell
curl https://buddycloud.com/api/notification_metadata?type=gcm \
    -X GET \
    -u juliet@buddycloud.com:romeo-forever
    -H "Accept: application/json"
```

> Response would be as follows:

```shell
{ "google_project_id": "$SENDER_ID" }
```

> Do some work in your app to obtain a `$REG_ID` and then...

> ###Send REGISTRATION ID To The Pusher

> `POST` /api/notification_settings

> ###Example

> Sending `$REG_ID` when registering with `buddycloud.com`'s Pusher service, using `curl`:

```shell
curl https://buddycloud.com/api/notification_settings \
    -X POST \
    -u juliet@buddycloud.com:romeo-forever \
    -H "Content-Type: application/json" \
    -d '{ \
           "type": "gcm", \
           "target": "$SENDER_ID" \
    }'
```

> Alongside the `type` and `target` keys, you can specify specific events you want to monitor (or not). By default you'll monitor most events. More information about this [can be found here](http://buddycloud.com/get-started-mobile#event_keys).

###Get Push Notifications By Registering With The Pusher Service

There's some work you will have to do in your mobile application in order to enable it to receive Buddycloud updates via push notifications, sent by our Pusher service.
It will need to become a Google Cloud Messaging client so that it's able to receive those updates.
Please refer to [this guide](http://buddycloud.com/get-started-mobile#setup_google_play_services_) and perform instructions given there.

Then, you will use some API calls in order to talk to our Pusher server so that you can register your application with it.
In short, you'll need to get a `SENDER_ID` from the Pusher, use it within your application to register with Google Cloud Messaging and then send your `REG_ID` back to the Pusher to register with it too.
[This guide](http://buddycloud.com/get-started-mobile#register_with_the_pusher_) explains the process more thoroughly and on the right you can find the specs of both endpoints used alongside short examples.
