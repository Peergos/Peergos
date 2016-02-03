#Discovery

Applications shall work with hardcoded API endpoints but, if need be, there's a way to discover the REST API endpoint for a given Buddycloud domain.

###REST API Discovery

**Problem:** Your application is designed for users on multiple Buddycloud sites each with their own REST API. It is necessary to discover the API endpoint for each user's domain.

* `juliet@capulet.lit` connects to the Capulet Buddycloud API using `https://buddycloud.capulet.lit/api`

* `romeo@montague.lit` connects to the Montague Buddycloud API using `https://montague.lit/buddycloud/api`

**Solution:** Buddycloud solves this by using a DNS lookup to discover the REST API endpoint. 

> ###Example
> In order to resolve the API endpoint for `buddycloud.com`, do:

```plaintext
dig txt +short _buddycloud-api._tcp.buddycloud.com
```

> You shall get a `TXT record` such as the following as response:

```plaintext
"v=1.0" "host=buddycloud.com" "protocol=https" "path=/api" "port=443"
```

> Which means the API endpoint for `buddycloud.com` is:

```plaintext
https://buddycloud.com:443/api
```

To find the API for a user's domain:

- Clients query for the `TXT record` of `_buddycloud-api._tcp.<user's domain>.`
- The results return an [IANA service record](http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml?search=buddycloud) with the information below

###API TXT record information

Parameter | Description        | Example
----------|--------------------|----------
`v`       | API version number | `v=1.0`
`host`    | server to use      | `host=buddycloud.com` 
`protocol`| which connection type to use | `protocol=https`
`path`    | API prefix         | `path=/api-endpoint`
`port`    | port to use        | `port=443`

The following API endpoint reflects the above response:  `https://buddycloud.com:443/api-endpoint`.

<aside>Run the API discvery procedure only if you are building an app for users on multiple Buddycloud sites logging in with their full <kbd>username@domain</kbd>.</aside>
