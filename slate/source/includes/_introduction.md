#Introduction 

> In general, the **REST API** is optimised for mobile app development.
> ### You can also try:
> **[XMPP-FTW](https://xmpp-ftw.jit.su/manual/extensions/buddycloud/)**, which is better for realtime web application programming. Refer to [their docs](https://xmpp-ftw.jit.su/manual/extensions/buddycloud) for more information.

###Buddycloud HTTP REST API

Welcome to the Buddycloud REST API documentation.

The listed REST API calls give you access to the entire Buddycloud stack. These API calls are designed to make it easy to add rich in-app messaging and social features to your web or mobile app.

###Getting Help
We really want this API to be useful to you. If you run into problems please [contact](/contact) us. [Documentation fixes](https://github.com/buddycloud/buddycloud.com/tree/master/slate/source/includes) and ideas for improvements are always welcome.

These pages are generated using [Slate](https://github.com/tripit/slate).

##REST API Conventions

###Encoding
The REST API uses UTF-8 character encoding.
Request headers should include a `Content-Type` of `application/json; charset=utf-8`.

###Authentication
The REST API uses HTTP [basic authentication](http://en.wikipedia.org/wiki/Basic_access_authentication).
The `username` should also include the user's `domain` (e.g. `username@example.com`).

###External Authentication
Buddycloud's backend enables you to authenticate your users against your own site by forwarding login requests to your own API.
[Email us](mailto:reach-a-developer@buddycloud.com) to have this feature enabled for your domain.

###Time Format
All timestamps are [ISO-8601](https://en.wikipedia.org/wiki/ISO_8601) formatted.
