#Accounts

Your users will need to authenticate against the server with a username that looks like `username@domain`. For example `username@example.buddycloud.com`.

##Create User

> `POST` /api/account

> ###Example
> Creating the `juliet@buddycloud.com` account using `curl`:

```shell
curl https://buddycloud.com/api/account \
     -X POST \
     -H "Content-Type: application/json; charset=utf-8" \
     -d '{ \
            "username": "juliet@buddycloud.com", \
            "password": "romeo-forever", \
            "email": "juliet@buddycloud.com" \
         }'
```

This will create a new account for `username` and set their `password` and `email`. The email is used for password resets and for optional alerts from the [push notification](#push-notifications) system.

### Payload Parameters

Argument   | Required | Notes
---------- |:--------:|------------
`username` | ✓        | always of form `user@example.com`; ≤1023 bytes
`password` | ✓        | ≤1023 bytes
`email`    | ✓        | an email for password resets and optional push notifications

##Check User Credentials

> `GET` /api/

> ###Example
> Checking if `juliet@buddycloud.com` matches with password `romeo-forever`, using `curl`:

```shell
curl https://buddycloud.com/api/ \
    -X GET \
    -u juliet@buddycloud.com:romeo-forever
```

Use this endpoint to check if you have valid User credentials (`username` and `password`).

If you're returned 200 OK, the credentials are valid.

<aside>Once your application's got valid credentials, it should use these in subsequent API calls.</aside>

##Delete User

> `DELETE` /api/account

> ###Example
> Deleting the `juliet@buddycloud.com` account using `curl`:

```shell
curl https://buddycloud.com/api/account \
     -X DELETE \
     -u juliet@buddycloud.com:romeo-forever
```

Removes a user account. 

<aside class="warning">Your application should first delete their channel(s) then remove the user account. Deleting a user will delete their account, _not_ their channels.</aside>

## Change Password

> `POST` /api/account/pw/change

> ###Example
> Changing the `juliet@buddycloud.com`'s password using `curl`:

```shell 
curl https://buddycloud.com/api/account/pw/change \
     -X POST \
     -u juliet@buddycloud.com:romeo-forever \
     -H "Content-Type: application/json; charset=utf-8" \
     -d '{ \
            "username": "juliet@buddycloud.com", \
            "password": "new-password" \
         }'
```

Changes the user's `password`.

##Reset Password

> `POST` /api/account/pw/reset

> ###Example
> Resetting the `juliet@buddycloud.com`'s password using `curl`:

```shell 
curl https://buddycloud.com/api/account/pw/reset \
     -X POST \
     -H "Content-Type: application/json" \
     -d '{ "username": "juliet@buddycloud.com" }'
```

This resets the user's `password` by sending a new password to the `email` address provided by the user during registration.
