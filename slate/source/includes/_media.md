#Media

File's inherit the permissions of the channel they are shared with. For example sharing files onto a private channel will mean that *only* that channel's followers can download them.

Media can be any type of file, and any file size (Buddycloud site administrators usually set about 1GB as a maximum size.)

<aside class="notice">Authentication is not required for requesting media from a public channel. Authentication is required for private channels.</aside>

###Special MediaIDs

The media `id` of `avatar` is currently reserved and used for storing a channels avatar. Uploading with the `avatar` `id` will replace the user's avatar.

##List Media

> `GET` /api/`channelID`/media

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media \
     -X GET \
     -u juliet@buddycloud.com:romeo-forever
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "id": "lETuJi8rPE4IfQrygN8rVtGx3",
        "fileName": "photo.jpg",
        "author": "juliet@buddycloud.com",
        "title": "Juliet's pic",
        "mimeType": "image/jpeg",
        "description": "Juliet's picture 1595/06/01",
        "fileExtension": "jpg",
        "shaChecksum": "bc46e5fac2f1cbb607c8b253a5af33181f161562",
        "fileSize": "60892",
        "height": "312",
        "width": "312",
        "entityId": "capulet@topics.buddycloud.com"
    }
]
```

This returns a list of all avaliable media objects in a channel.

##Fetch Media

> ###Get the raw media

> `GET` /api/`channelID`/media/`mediaID`

> ###Example

> Retrieving the raw media of id `$MEDIA_ID`, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media/$MEDIA_ID \
     -X GET \
     -u juliet@buddycloud.com:romeo-forever
```

> ###Get the media thumbnail preview

> `GET` /api/`channelID`/media/`mediaID`?maxheight=`val`&maxwidth=`val`

> ###Example

> Retrieving a preview of the media of id `$MEDIA_ID`, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media/$MEDIA_ID?maxheight=150&maxwidth=150 \
     -X GET \
     -u juliet@buddycloud.com:romeo-forever
```

> ###Get avatar

> `GET` /api/`channelID`/media/avatar

> ###Example

> Retrieving avatar of `juliet@buddycloud.com`, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media/avatar \
     -X GET \
     -u juliet@buddycloud.com:romeo-forever
```

This request returns a media file.

The request can also be used to return an image preview or small user avatar sized files by adding the `maxheight` and `maxwidth` parameter to the request.

If the media object belongs to a public channel, you don't need an Authorization header. Notice that if you're building a web frontend, embedding public media from the media-server means just creating an ```<img>``` tag.

Parameter        | Required   | Description
-----------------|------------|--------------------------------------------
`maxheight`      | optional   | Bound the ouput by a maximum height
`maxwidth`       | optional   | Bound the output by a maximum width

When both `maxheight` and `maxwidth` are requested the server will return a file smaller than or equal to both parameters.

##Post Media

> `POST` /api/`channelID`/media

> ###Example

> Posting new media to the `capulet@topics.buddycloud.com` channel, using `curl`:

```shell
curl https://buddycloud.com/api/capulet@topics.buddycloud.com/media \
     -X POST \
     -u juliet@buddycloud.com:romeo-forever \
     -H "Content-Type: application/json" \
     -d '{ \
             "data": "media data in bytes", \
             "content-type": "image/png", \
             "filename": "prom.png", \
             "title": "Juliet's prom pic", \
             "description": "Juliet's beautiful prom pic!" \
         }'
```

> Response would be as follows:

```shell
HTTP/1.1 201 Created
Content-Type: application/json

{
    "id": "lETuJi8rPE4IfQrygN6rVtGx3",
    "fileName": "prom.png",
    "author": "juliet@buddycloud.com",
    "title": "Juliet's prom pic",
    "mimeType": "image/png",
    "description": "Juliet's beautiful prom pic!",
    "fileExtension": "png",
    "shaChecksum": "bc46e5fac2f1cbb607c8b253a5af33181f161562",
    "fileSize": 60892,
    "height": 312,
    "width": 312,
    "entityId": "capulet@topics.buddycloud.com"
}
```

This call enables media and media-metadata uploading and modification.

Posting new media will return the object `id` and metadata.

Updating existing media with the same `id` will overwrite the existing media content.

##Delete Media

> `DELETE` /api/`channelID`/media/`mediaID`

> ###Example

> Deleting media of id `$MEDIA_ID` from the `juliet@buddycloud.com` channel, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media/$MEDIA_ID \
     -x DELETE \
     -u juliet@buddycloud.com:romeo-forever
```

Removes media from the channel.

Deleting media will remove it from the requested channel. This does not remove it from other channels where it has been reshared.

## Fetch Media Metadata

> `GET` /api/`channelID`/media/`mediaID`/metadata

> ###Example

> Fetching metadata of media of id `$MEDIA_ID`, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media/$MEDIA_ID/metadata \
     -X GET \
     -u juliet@buddycloud.com:romeo-forever
```

> Response would be as follows:

```shell
HTTP/1.1 200 OK
Content-Type: application/json

[
    {
        "id": "lETuJi8rPE4IfQrygN8rVtGx3",
        "fileName": "photo.jpg",
        "author": "juliet@buddycloud.com",
        "title": "Juliet's pic",
        "mimeType": "image/jpeg",
        "description": "Juliet's picture 1595/06/01",
        "fileExtension": "jpg",
        "shaChecksum": "bc46e5fac2f1cbb607c8b253a5af33181f161562",
        "fileSize": "60892",
        "height": "312",
        "width": "312",
        "entityId": "capulet@topics.buddycloud.com"
    }
]
```

Endpoint used to fetch a given media's metadata. 

## Update Media Metadata

> `PUT` /api/`channelID`/media/`mediaID`

> ###Example

> Updating the media of id `$MEDIA_ID`'s name and title, using `curl`:

```shell
curl https://buddycloud.com/api/juliet@buddycloud.com/media/$MEDIA_ID \
     -X PUT \
     -u juliet@buddycloud.com:romeo-forever \
     -d '{ \
            "filename": "A good name for that picture of Jules", \
            "title": "A new title" \
        }' 
```

Use this endpoint to update a give media's metadata.

The possible metadata parameters are:

Parameter        | Required   | Description
-----------------|------------|--------------------------------------------
`height`           | server-set | Height of the uploaded image or video. This is calculated by the server and not user-editable.
`width`            | server-set | Width of the uploaded image or video. This is calculated by the server and not user-editable.
`author`           | server-set | the `username` of the uploader
`shaChecksum`      | server-set | SHA1 file checksum
`uploadedDate`     | server-set | when the media was uploaded
`lastUpdatedDate`  | server-set | when the media was updated / re-uploaded
`mimeType`         | required   | The file mimetype (e.g. `image/jpeg`)
`fileName`         | required   | The uploaded filename and extension.
`entityId`         | required   | The channel where the media object was posted.
`title`            | optional   | a short title of the object
`description`      | optional   | a longer form description of the object
