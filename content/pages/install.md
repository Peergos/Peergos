Title: Install
url: install
save_as: install.html
order: -1
show_in_top_menu: false
table_of_contents: true

<h2 data-hidden-from-toc="true">Docker?</h2>

If you'd rather run this as a Docker instance, check out <https://github.com/buddycloud/dockerfiles>


Install Requirements
--------------------

-   a server with an internet routable IP address (No public IP, no problem, use the [buddycloud
    hosting](http://hosting.buddycloud.com))
-   a domain name (the instructions assume EXAMPLE.COM)
-   the ability to edit your DNS
-   Ubuntu, 14.04 (LTS)

Getting Help
------------

Please ask if you get stuck

-   chatroom: <https://jappix.com/?r=seehaus@channels.buddycloud.com>
-   mailing list: <https://groups.google.com/forum/#!forum/buddycloud-dev>
-   report an issue on github: <https://github.com/buddycloud>
-   email us at: [reach-a-developer@buddycloud.com](mailto:reach-a-developer@buddycloud.com)

Buddycloud DNS
--------------

<span style="color:green">Estimated time: **10 mins**</span>

### Aim

-   website for buddycloud channels: <http://buddycloud.EXAMPLE.COM>
-   server running buddycloud: buddycloud.EXAMPLE.COM

### Configure

This install will setup a buddycloud webclient at
<http://buddycloud.EXAMPLE.COM>

Log into your registrar or edit your DNS zone file. In this example we use 1.2.3.4 as your server
address.

~~~~ {.text}

buddycloud.EXAMPLE.COM.                    IN A            1.2.3.4 # SRV records must point to A records
_xmpp-server._tcp.EXAMPLE.COM.             IN SRV 5 0 5269 buddycloud.EXAMPLE.COM. # XMPP Server
_xmpp-client._tcp.EXAMPLE.COM.             IN SRV 5 0 5222 buddycloud.EXAMPLE.COM. # XMPP client connections
_xmpp-server._tcp.anon.EXAMPLE.COM.        IN SRV 5 0 5269 buddycloud.EXAMPLE.COM. # XMPP Server anonymous connections
_xmpp-client._tcp.anon.EXAMPLE.COM.        IN SRV 5 0 5222 buddycloud.EXAMPLE.COM. # XMPP anonymous client connections
_xmpp-server._tcp.media.EXAMPLE.COM.       IN SRV 5 0 5269 buddycloud.EXAMPLE.COM. # Media Server XMPP component
_xmpp-server._tcp.buddycloud.EXAMPLE.COM.  IN SRV 5 0 5269 buddycloud.EXAMPLE.COM. # buddycloud Server XMPP component
_buddycloud-api._tcp.EXAMPLE.COM.          IN TXT "v=1.0 host=buddycloud.EXAMPLE.COM protocol=https path=/api port=443" 
_bcloud-server._tcp.EXAMPLE.COM            IN TXT "v=1.0 server=buddycloud.EXAMPLE.COM" # To delegate to a hosting platoform
~~~~

### Test

Test your DNS on <http://protocol.buddycloud.com/EXAMPLE.COM>

(replace `EXAMPLE.COM` by your actual domain)

The following tests must pass:

-   `xmpp_server_srv_lookup`
-   `xmpp_server_anon_srv_lookup`
-   `xmpp_client_srv_lookup`
-   `xmpp_client_anon_srv_lookup`
-   `xmpp_server_a_lookup`

Firewall Setup
--------------

<span style="color:green">Estimated time: **2 mins**</span>

Double check your firewall rules. They should look something like this:

~~~~ bash
# inbound web, server to server and outbound server to server
iptables -A INPUT  -m state --state NEW -s 0.0.0.0/0 -d <your server address> -p tcp --dport 80   -j ACCEPT 
iptables -A INPUT  -m state --state NEW -s 0.0.0.0/0 -d <your server address> -p tcp --dport 443  -j ACCEPT
iptables -A INPUT  -m state --state NEW -s 0.0.0.0/0 -d <your server address> -p tcp --dport 5222 -j ACCEPT
iptables -A INPUT  -m state --state NEW -s 0.0.0.0/0 -d <your server address> -p tcp --dport 5269 -j ACCEPT
iptables -A OUTPUT -m state --state NEW -s <your server address> -d 0.0.0.0/0 -p tcp --dport 5269 -j ACCEPT 
~~~~

Prosody Setup
-------------

<span style="color:green">Estimated time: **10 mins**</span>

### Install

~~~~ bash
apt-get install prosody lua-zlib liblua5.1-cyrussasl0
~~~~

You might need to tell where those Prosody dependencies are. If that's
the case, you can do the following:

~~~~ bash
echo deb http://packages.prosody.im/debian $(lsb_release -sc) main | sudo tee -a /etc/apt/sources.list
wget http://prosody.im/files/prosody-debian-packages.key -O- | sudo apt-key add -
sudo apt-get update
~~~~

More information on what's you'd be doing on the steps above here:
[http://prosody.im/download/package\_repository\#debianubuntu](http://prosody.im/download/package_repository#debianubuntu)

### Configure Prosody

Edit `/etc/prosody/prosody.cfg.lua`:

~~~~ {.lua}
modules_enabled = {
  "saslauth";           -- Authentication for clients and servers. Recommended if you want to log in.
  "tls";                -- Add support for secure TLS on c2s/s2s connections
  "dialback";           -- s2s dialback support
  "disco";              -- Service discovery
  "time";               -- Let others know the time here on this server
  "ping";               -- Replies to XMPP pings with pongs
  "posix";              -- Do posixy things
  "register";           -- Allow users to register on this server using a client and change passwords
  "watchregistrations"; -- Alert admins of registrations (check "admins" option below too)
  "compression";        -- Enable mod_compression
};

storage                 = "internal"
admins                  = { "your-username@EXAMPLE.COM" }  -- who receives registration alerts
pidfile                 = "/var/run/prosody/prosody.pid"
log                     = {{ levels = { min = "error" }, to = "file", filename = "/var/log/prosody/prosody.err" };
                           { levels = { min = "info"  }, to = "file", filename = "/var/log/prosody/prosody.log" };}
registration_whitelist      = { "127.0.0.1" }
whitelist_registration_only = true


VirtualHost "EXAMPLE.COM"
  authentication        = "internal_hashed"
  allow_registration    = true 
  anonymous_login       = false
  ssl                   = {         key = "/etc/apache2/certs/EXAMPLE.COM.key";
                            certificate = "/etc/apache2/certs/EXAMPLE.COM.pem" }

-- for non-logged in browsing of open channels.
VirtualHost "anon.EXAMPLE.COM"
  authentication        = "anonymous"
  allow_registration    = false
  anonymous_login       = true

-- Buddycloud Channel Server XMPP component configuration.
Component "buddycloud.EXAMPLE.COM"
  component_secret      = "tellnoone"
  
-- Buddycloud Channel Server (optional topic channels).
Component "topics.EXAMPLE.COM"
  component_secret      = "tellnoone"

-- Buddycloud Media Server XMPP component configuration.
Component "media.EXAMPLE.COM"
  component_secret      = "tellnoone"

-- Buddycloud Pusher Server XMPP component configuration.
Component "pusher.EXAMPLE.COM"
  component_secret      = "tellnoone"
~~~~

### Restart Prosody

~~~~ bash
/etc/init.d/prosody restart
~~~~

### Test

Check your server is visible on
<http://protocol.buddycloud.com/EXAMPLE.COM>

(replace `EXAMPLE.COM` by your actual domain)

Besides the previous 5 SRV and A record lookup tests, the following test
must pass as well:

-   `xmpp_server_connection`

Check your certificates are working correctly using the [XMPP security checker](https://xmpp.net).

### Debug

~~~~ bash
tail -F /var/log/prosody/prosody.err \
        /var/log/prosody/prosody.log
~~~~

Buddycloud Server
-----------------

<span style="color:green">Estimated time: **5 mins**</span>

### Install

~~~~ bash
# install dependencies
apt-get install postgresql postgresql-client postgresql-contrib openjdk-7-jre dbconfig-common
# Download package from http://downloads.buddycloud.com/packages/debian/nightly/buddycloud-server-java/
dpkg -i buddycloud-server-java*.deb
~~~~

### Configure Server

Edit `/etc/buddycloud-server-java/configuration.properties`.

-   change EXAMPLE.COM to match your domain
-   double check the database connection strings

### Start Server

~~~~ bash
/etc/init.d/buddycloud-server-java start
~~~~

### Test

Check the buddycloud server is discoverable using
<http://protocol.buddycloud.com> (should pass `buddycloud_server_disco`
test)

### Debug

check logs

~~~~ bash
tail -F /var/log/buddycloud-server-java/log 
~~~~

Test the database is installed (password is in
`/etc/buddycloud-server-java/configuration.properties`)

~~~~ bash
psql -h 127.0.0.1 --username buddycloudserverjava -d buddycloudserverjava -c "select * from nodes;" 
node
------
(0 rows)
# this means that your buddycloud server database schema been installed successfully.
~~~~

Buddycloud API Server
---------------------

<span style="color:green">Estimated time: **6 mins**</span>

-   Alternative: [Install from source](http://github.com/buddycloud/buddycloud-http-api)

*Note: SSL and API requests: Chrome and Firefox will **not** work with
self signed certificates when used for API calls. Ordinarily you would
receive a certificate warning. But for API requests, this does not
happen and requests are silently drop.* You have two options:

-   run without SSL on your API server, or
-   get a signed certificate

### Install

~~~~ bash
apt-get install software-properties-common python-software-properties; #needed for PPA access
add-apt-repository ppa:chris-lea/node.js-legacy # to retrieve the correct nodejs version
apt-get update
apt-get install nodejs apache2 apache2.2-common

# ensure you have the correct modules enabled in Apache
a2enmod rewrite proxy_http ssl headers expires deflate
service apache2 reload
# Download from http://downloads.buddycloud.com/packages/debian/nightly/buddycloud-http-api/
dpkg -i buddycloud-http-api*.deb; 
~~~~

### Configure Server

Edit `/etc/buddycloud-http-api/config.js` to use your domain.

### Restart Server

~~~~ bash
/etc/init.d/buddycloud-http-api start
~~~~

### Add Site to Apache

We recommend the usage of a HTTP server in order to make the API
publicly available. As we are using Apache to serve the webclient, we
use the same site to redirect requests to the API. Thus, if you run the
webclient from EXAMPLE.com, the API will be run from EXAMPLE.com/api.

Please refer to [buddycloud Apache virtual host
setup](buddycloud Apache virtual host setup "wikilink") for site setup,
then

~~~~ bash
a2ensite buddycloud-apache-virtual-host
/etc/init.d/apache2 reload
~~~~

### Test

Check your buddycloud API server is discoverable on
<http://protocol.buddycloud.com/EXAMPLE.COM>

(replace EXAMPLE.COM by your actual domain)

The following tests must pass:

-   `api_server_lookup`
-   `api_server_connection`

### Debug

~~~~ bash
node --version # The buddycloud API requires nodejs 0.8.*
tail -F /var/log/buddycloud-http-api/buddycloud-http-api.log
~~~~

Buddycloud Webclient
--------------------

<span style="color:green">Estimated time: **5 mins**</span>

-   Alternative: [buddycloud nginx
    setup](https://github.com/buddycloud/dockerfiles/blob/master/buddycloud-stack/config/buddycloud-nginx-server-block)

### Install

~~~~ bash
# Download from http://downloads.buddycloud.com/packages/debian/releases/buddycloud-webclient/ 
dpkg -i buddycloud-webclient*.deb;
~~~~

### Configure

~~~~ bash
vim /etc/apache2/sites-available/buddycloud-apache-virtual-host
cp /usr/share/buddycloud-webclient/config.js.example /usr/share/buddycloud-webclient/config.js
vim /usr/share/buddycloud-webclient/config.js
a2ensite buddycloud-apache-virtual-host
service apache2 reload
~~~~

### Test

-   [check your SSL setup](https://www.ssllabs.com/ssltest/index.html)
-   open `https://EXAMPLE.COM/` in your browser: you should see the
    login screen
-   open `https://EXAMPLE.COM/team@topics.buddycloud.org` in your
    browser: you should see an open channel displayed

### Debug

~~~~ bash
tail -F /var/log/apache2/EXAMPLE.COM-error.log \ 
        /var/log/apache2/EXAMPLE.COM-error.log \
        /var/log/apache2/api.EXAMPLE.COM-error.log \
        /var/log/apache2/api.EXAMPLE.COM-error.log
~~~~

Buddycloud Pusher
-----------------

<span style="color:green">Estimated time: **5 mins**</span>

### Install

~~~~ bash
# Download from http://downloads.buddycloud.com/packages/debian/ 
wget http://downloads.buddycloud.com/packages/debian/nightly/buddycloud-pusher/buddycloud-pusher-$LATEST/buddycloud-pusher_$LATEST_all.deb
# Install it with dpkg
dpkg -i buddycloud-pusher_$LATEST_all.deb
~~~~

### Configure

Check your Pusher component is in Prosody `/etc/prosody/prosody.cfg.lua`

~~~~ {.lua}
Component "pusher.EXAMPLE.COM"
       component_secret = "tellnoone"
~~~~

Change XMPP settings in the Pusher
`/usr/share/buddycloud-pusher/configuration.properties`

~~~~ {.java}
xmpp.subdomain=pusher.EXAMPLE.COM
xmpp.secretkey=tellnoone
~~~~

Add the Pusher to the API in `/etc/buddycloud-http-api/config.js`

~~~~ {.lua}
pusherComponent: 'pusher.EXAMPLE.COM'
~~~~

Change SMTP settings in the pusher
`/usr/share/buddycloud-pusher/configuration.properties`

~~~~ bash
# Use STMP auth
mail.smtp.auth=true
# Enable start TLS
mail.smtp.starttls.enable=true
# SMTP host
mail.smtp.host=smtp.example.com
# SMTP port
mail.smtp.port=587
# SMTP login user
mail.username=admin@pusher.buddycloud.com
# SMTP login password
mail.password=password
~~~~

Create a GCM project and get an API key as per
`http://developer.android.com/google/gcm/gs.html#create-proj`

Change GCM settings in the pusher
`/usr/share/buddycloud-pusher/configuration.properties`

~~~~ bash
# GCM project id 
gcm.google_project_id=
# GCM API key 
gcm.api_key=
~~~~

### Restart

~~~~ bash
/etc/init.d/buddycloud-pusher restart
/etc/init.d/buddycloud-http-api restart
~~~~

### Test

-   Enable and configure push notifications in the webclient (or via the
    [Pusher API](http://buddycloud.com/api#notification_settings_))
-   After a new post in your channel, for instance, you should get an
    email and/or GCM notification.

### Debug

~~~~ bash
tail -F /usr/share/buddycloud-pusher/logs/log
~~~~

Final Steps
-----------

You are done!

Log-into your buddycloud node at <http://buddycloud.EXAMPLE.COM/> and
follow your first channels.

Sharing Debug Info
------------------

The following commands will generate a file called
`/tmp/buddycloud-debug.txt` to [Pastebin](http://pastebin.com/).

When you have run the commands, please share the link in the [buddycloud
chat room](https://jappix.com/?r=seehaus@channels.buddycloud.com) or on
the
[https://groups.google.com/forum/#!forum/buddycloud-dev](buddycloud-dev mailing list).

~~~~ bash
# buddycloud related configuration files
sudo cat /etc/prosody/prosody.cfg.lua \
         /etc/postgresql/9.1/main/pg_hba.conf \
         /opt/buddycloud-server-java/environment.properties \ 
         /etc/init.d/buddycloud-server-java \ 
         /var/www/<your-domain-name>/config.js \ 
         /etc/apache2/sites-enabled/<your-domain-name>  >> /tmp/buddycloud.debug.txt

# network 
ip addr  show >> /tmp/buddycloud-debug.txt
ip route show >> /tmp/buddycloud-debug.txt

# what is running
ps -efww >> /tmp/buddycloud-debug.txt

# what's listening
sudo netstat -plutn | grep LISTEN >> /tmp/buddycloud-debug.txt

# what's stopping things from listening
sudo iptables -vnL INPUT >> /tmp/buddycloud-debug.txt
sudo iptables -vnL OUTPUT >> /tmp/buddycloud-debug.txt

# the related log files
sudo tail -n 200 /var/log/apache2/<your-domain-name>-access.log \
                 /var/log/apache2/<your-domain-name>-error.log \
                 /var/log/prosody/prosody.log \
                 /var/log/prosody/prosody.err \
                 /var/log/buddycloud-server.log \
                 /var/log/postgresql/postgresql-9.1-main.log >> /tmp/buddycloud-debug.log 

# Edit the file and remove any passwords.
sudo edit /tmp/buddycloud-debug.txt

# To automatically upload the file:
sudo apt-get install pastebinit
sudo cat /tmp/buddycloud-debug.txt | pastebinit
~~~~

