// API for the User interface to use
/////////////////////////////

// string => Uint8Array
function hexToBytes(hex) {
    var arr = new Uint8Array(hex.length/2);
    for (var i=0; i < arr.length; i++)
	arr[i] = ((0xf & intAt(hex, 2*i)) << 4) | (0xf & intAt(hex, 2*i+1));
    return arr;
}

// Uint8Array => string
function bytesToHex(arr) {
    return scrypt.to_hex(arr);
}

// string => Uint8Array
function base64ToBytes(b64) {
    return base64DecToArr(b64);
}

// Uint8Array => string
function bytesToBase64(b) {
    return base64EncArr(b);
}

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
    
    // KeyPair => byte[]
    this.getPublicKeyBytes = function(pair) {
	return Java.to(getPublicKey(pair), "byte[]");
    }
    
/////////////////////////////
// User methods
// (string, string, int) => KeyPair
function generateKeyPair(username, password, bits) {
    var pbkdf_bytes = scrypt.crypto_scrypt(scrypt.encode_utf8(password), 
            scrypt.encode_utf8(username), n, r, p, desiredBytes);
    return new User(cryptico.generateRSAKey(scrypt.to_hex(pbkdf_bytes), bits));
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

// (Uint8Array, Uint8Array) => Uint8Array
function encrypt(data, initVector) {
}

// (Uint8Array, Uint8Array) => Uint8Array
function decrypt(data, initVector) {
}

// WARNING do not encrypt using this in nashorn, as the random number generation is repeatable
// i.e. same input => same output
// string input, return hex string
function encryptStringToHex(input, pubKey) {
    var encrypt = new JSEncrypt();
    encrypt.setPublicKey(pubKey);
    return encrypt.encrypt(input);
}

// hex string cipher, return string
function decryptHexToString(cipher, privKey) {
    var decrypt = new JSEncrypt();
    decrypt.setPrivateKey(privKey);
    return decrypt.decrypt(cipher);
}

// Uint8Array input and return value
function encryptUint8ToUint8(input, pubKey) {
    return hexToBytes(encryptStringToHex(bytesToBase64(input), pubKey));
}

// Uint8Array cipher and return value
function decryptUint8ToUint8(cipher, privKey) {
    return base64ToBytes(decryptHexToString(bytesToHex(cipher), privKey));
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

