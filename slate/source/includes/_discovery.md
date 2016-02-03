#Discovery

Applications shall work with hardcoded API endpoints but, if need be, there's a way to discover the REST API endpoint for a given Peergos domain.

###REST API Discovery

**Problem:** Your application is designed for users on multiple Peergos sites each with their own REST API. It is necessary to discover the API endpoint for each user's domain.

* `juliet@capulet.lit` connects to the Capulet Peergos API using `https://peergos.capulet.lit/api`

* `romeo@montague.lit` connects to the Montague Peergos API using `https://montague.lit/peergos/api`

**Solution:** Peergos solves this by using a DNS lookup to discover the REST API endpoint. 

> ###Example
> In order to resolve the API endpoint for `peergos.org`, do:

```plaintext
dig txt +short _peergos-api._tcp.peergos.org
```

> You shall get a `TXT record` such as the following as response:

```plaintext
"v=1.0" "host=peergos.org" "protocol=https" "path=/api" "port=443"
```

> Which means the API endpoint for `peergos.org` is:

```plaintext
https://peergos.org:443/api
```

To find the API for a user's domain:

- Clients query for the `TXT record` of `_peergos-api._tcp.<user's domain>.`
- The results return an [IANA service record](http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml?search=peergos) with the information below

###API TXT record information

Parameter | Description        | Example
----------|--------------------|----------
`v`       | API version number | `v=1.0`
`host`    | server to use      | `host=peergos.org` 
`protocol`| which connection type to use | `protocol=https`
`path`    | API prefix         | `path=/api-endpoint`
`port`    | port to use        | `port=443`

The following API endpoint reflects the above response:  `https://peergos.org:443/api-endpoint`.

<aside>Run the API discvery procedure only if you are building an app for users on multiple Peergos sites logging in with their full <kbd>username@domain</kbd>.</aside>
