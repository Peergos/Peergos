// API for the User interface to use
/////////////////////////////

/////////////////////////////
// UserPublicKey methods
function UserPublicKey(publicKey) {
    this.publicKey = publicKey;

    // KeyPair => Uint8Array
    this.getPublicKey = function(pair) {
	return base64ToBytes(getPublicBaseKeyB64(pair));
    }
    
    // Uint8Array => Uint8Array
    this.encryptMessageFor = function(input) {
    }
    
    // Uint8Array => Uint8Array
    this.unsignMessage = function(input) {
    }
    
    // Uint8Array => Uint8Array
    this.hash = function(input) {
    }
}
    
/////////////////////////////
// User methods
// (string, string, int) => KeyPair
function generateKeyPair(username, password, bits) {
}

    function User(keyPair) {
	UserPublicKey.call(this, keyPair);
	
	
	// Uint8Array => Uint8Array
	this.hashAndSignMessage = function(input) {
	}
	
	// Uint8Array => Uint8Array
	this.signMessage = function(input) {
	}
    
	// Uint8Array => Uint8Array
	this.decryptMessage = function(input) {
	}
    }

/////////////////////////////
// SymmetricKey methods

// () => Uint8Array
function randomSymmetricKey() {
}

// () => Uint8Array
function randomIV() {
}

function SymmetricKey() {

    // (Uint8Array, Uint8Array) => Uint8Array
    this.encrypt = function(data, initVector) {
    }

    // (Uint8Array, Uint8Array) => Uint8Array
    this.decrypt = function(data, initVector) {
    }
}

// byte[] input and return
function encryptBytesToBytes(input, pubKey) {
    return Java.to(encryptUint8ToUint8(input, pubKey), "byte[]");
}

// byte[] cipher and return
function decryptBytesToBytes(cipher, privKey) {
    return Java.to(decryptUint8ToUint8(cipher, privKey), "byte[]");
}

function get(path, onSuccess, onError) {

    var request = new XMLHttpRequest();
    request.open("GET", path);

    request.onreadystatechange=function()
    {
        if (request.readyState != 4)
            return;

        if (request.status == 200) 
            onSuccess(request.response);
        else
            onError(request.status);
    }

    request.send();
}

function post(path, data, onSuccess, onError) {

    var request = new XMLHttpRequest();
    request.open("POST" , path);

    request.onreadystatechange=function()
    {
        if (request.readyState != 4)
            return;

        if (request.status == 200) 
            onSuccess(request.response);
        else
            onError(request.status);
    }

    request.send(data);
}

//Java is Big-endian
function toBigEndianInteger(bytes, position) {
    var count = 0;
    for(var i = position; i <  position +4 ; i++)
        count = count << 8 + bytes[i];
    return count;
}

function arraysEqual(leftArray, rightArray) {
    if (leftArray.length != rightArray.length)
        return false;
    
    for (var i=0; i < leftArray.length; i++) 
        if (leftArray[i] != rightArray[i])
            return false;
    
    return true;
}

