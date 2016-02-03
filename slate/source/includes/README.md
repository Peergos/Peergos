#Conventions to keep us consistient

##Showing variables:

Denote variables with `{variable name}`

- not `:variable name`
- not `:variable name:`

eg: `GET https://demo.buddycloud.org/api/{channelID}/media/{Mediaid}`

##Channel types:

channel access: `public` and `private`

- (not open, or closed)


##Nodes
Use _channel-nodes_ when referring to _pub-sub nodes_ or _nodes_ to give the developer context that these are part of a channel.


##Naming variables:

Variable | Description
---------|------------
`channelID`| (the JID-like part) we call it the `channelID` (not `channel-name`)
`mediaID` | the id of a media object
`username`| user's log in / their jid (we never mention `jid`)

##Examples

Use examples from Romeo and Juliet. (not alice and bob). If you didn't study at school, use the following [character list]([https://en.wikipedia.org/wiki/Characters_in_Romeo_and_Juliet)

## Table headings 

* Capitalise table headings
* arguments should be nicely code formatted eg: `username`
* `true` and `false`  not true and false

eg:

Argument   | Required | Notes
---------- | -------- |------------
`username`   | `true`     | Must contain a domain element that matches the virtual host.
`password`   | `true`     | The API is agnostic to password strength requirements.
`email`      | `false`    | An Email address to receive push notifications and password resets.

## Apps not clients

* wrong: A client might want to just show the latest 10 posts per channel
* right: An app might want to just show the latest 10 posts per channel
