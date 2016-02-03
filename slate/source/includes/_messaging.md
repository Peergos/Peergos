#Direct Messaging

Direct messages give you a quick way to send messages betwen users on your Buddycloud site and to users on remote Buddycloud sites. Direct messaging uses XMPP's native messaging.
 
##Post Message

```javascript
socket.send(
  'xmpp.chat.receipt',
      {
        "to": "other@evilprofessor.co.uk/laptop",
        "id": "message-number-5"
      }
  )
```

> Message delivery receipts are received via the `xmpp.chat.receipt` event

```javascript
socket.on('xmpp.chat.receipt', function(data) {
  console.log(data);
            /*
             * {
             *   from: { domain: 'evilprofessor.co.uk', user: 'lloyd', resource: 'laptop' },
             *   id: 'message-number-5'
             * }
             */
        }
```

```javascript
        socket.on('xmpp.chat.receipt', function(data) {
            console.log(data);
            /*
             * {
             *   from: { domain: 'evilprofessor.co.uk', user: 'lloyd', resource: 'laptop' },
             *   id: 'message-number-5'
             * }
             */
        }
```

This sends a message to another user. Messages will be stored for the user if they are not online. 

##Receive Messages

```javascript
socket.on('xmpp.chat.message', function(data) {
  console.log(data)
  })
```

```json
{
  from: {
    domain: 'buddycloud.com',
    user: 'friend'
    },
    content: 'What time should we go out tonight?',
    format: 'plain',
 /* delay: {
      from: 'evilprofessor.co.uk',
      when: '2013-06-03T19:56Z',
      reason: 'Offline storage'
      }, */
 /* state: 'active' */
 /* archived: [
    { by: { domain: 'buddycloud.com' }, id: 'archive:1' }
    ] */
}
```

To begin receiving messages your application should enable [realtime event](#realtime-events) sending. Messages will then arrive as they are sent without needing to poll.

##Retrieve Message History


```javascript
socket.send(
            'xmpp.mam.query'
            {
             /* "queryId": "query-id", */
             /* "with": "juliet@calulet.lit", */
             /* "start": "2013-10-01T12:00:00Z", */
             /* "end": "2013-11-01T12:00:00Z", */
             /* "rsm": ...RMS payload... */
            },
            function(error, data) { console.log(error, data) }
        )
```

```json
{
            from: {
                domain: 'capulet.lit',
                user: 'juliet'
            },
            content: 'When he shall die,
                      Take him and cut him out in little stars,
                      And he will make the face of heaven so fine
                      That all the world will be in love with night
                      And pay no worship to the garish sun.',
            format: 'plain',
            mam: {
                to: {
                    domain: 'montague.lit',
                    user: 'romeo',
                    resource: 'balcony-phone-app'
                },
                id: '1234',
                queryId: 'query-id'
            },
        }  
```

It's possible to request a message history. This can be between one user `"with": "juliet@calulet.lit"` or a spool of all archived messages. 


       
