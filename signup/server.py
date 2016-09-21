from flask import Flask, request, session, redirect, url_for, escape, Session, make_response, jsonify, send_from_directory
from flask.ext.mail import Mail, Message
from itsdangerous import URLSafeSerializer, JSONWebSignatureSerializer, BadSignature

import json, re
import config
from signup import UserRepository

app = Flask(__name__)

def success():
    return {"status": "success"}
def error(msg=None):
    return {"status": "error", "message": msg}

def main():
    app.debug = config.debug 

    app.secret_key = config.secretKey 
    app.urlSerializer = JSONWebSignatureSerializer(app.secret_key)

    app.repository = UserRepository(config.dbUrl)
    
    app.config.update(dict(
        MAIL_SERVER = config.mailServer,
        MAIL_PORT = config.mailPort ,
        MAIL_USE_TLS = config.mailUseTls ,
        MAIL_USE_SSL = config.mailUseSsl ,
        MAIL_USERNAME = config.mailUsername,
        MAIL_PASSWORD = config.mailPassword, 
    ))
    app.mail = Mail(app)
    
    try :
        app.serverName = config.serverName
    except AttributeError:
        app.serverName = None 
    app.run(port=config.port, threaded=config.threaded)

def sendMessage(recipient, subject, message):
    msg = Message(subject, sender=app.config["MAIL_USERNAME"], recipients=[recipient])
    msg.body = message 
    with app.app_context():
        app.mail.send(msg)
        
def sendVerification(recipient, validationUrl):
    subject = "Peergos verification"
    message = "Thank you for registering for Peergos. Click this URL to complete your registration.\n\n"
    message += validationUrl
    message += "\n\nIf you didn't sign up to Peergos, then ignore this email."
    message += "\n\nThanks,\nThe Peergos team"

    sendMessage(recipient, subject, message)

def generateActivationStem(username, address):
    payload = app.urlSerializer.dumps({"username": username, "address": address})
    print("payload dumps", payload)
    return "activation/"+payload

emailRegex = re.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9]+.[A-Za-z0-9]+")
def isValidEmailAddress(address):
    return emailRegex.match(address) is not None 

@app.route("/signup/<username>/<address>")
def signup(username, address):
    if not isValidEmailAddress(address):
        return jsonify(error("Invalid email address"))
    if app.repository.contains(address):
        return jsonify(error("Username is already registered."))
        
    activationStem = generateActivationStem(username, address)
    if app.serverName is None:
        host = request.host_url
    else:
        host = app.serverName+"/"
    activationUrl =  host + activationStem
    sendVerification(address, activationUrl)

    return jsonify(success())

@app.route('/activation/<payload>')
def activate_user(payload):
    try:
        print("payload loads", payload)
        user = app.urlSerializer.loads(payload)
        print(user)
        if not "username" in user or not "address" in user: abort(404)
        username, address = user["username"],  user["address"]
        app.repository.put(username, address)
        return redirect("https://demo.peergos.net/", code=303)
    except BadSignature:
        abort(401)

if __name__ == "__main__":
    main()

