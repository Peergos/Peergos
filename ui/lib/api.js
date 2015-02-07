var desiredBytes = 64;
var n = 8192;//16384;//32768;
var r = 8, p = 1;
var scrypt = scrypt_module_factory();

function getPublicBaseKey(pair) {
    var options = {
        'array': [
            new KJUR.asn1.DERObjectIdentifier({'oid': '1.2.840.113549.1.1.1'}), //RSA Encryption pkcs #1 oid
            new KJUR.asn1.DERNull()
        ]
    };
    var first_sequence = new KJUR.asn1.DERSequence(options);

    options = {
        'array': [
            new KJUR.asn1.DERInteger({'bigint': pair.n}),
            new KJUR.asn1.DERInteger({'int': pair.e})
        ]
    };
    var second_sequence = new KJUR.asn1.DERSequence(options);

    options = {
        'hex': '00' + second_sequence.getEncodedHex()
    };
    var bit_string = new KJUR.asn1.DERBitString(options);

    options = {
        'array': [
            first_sequence,
            bit_string
        ]
    };
    var seq = new KJUR.asn1.DERSequence(options);
    return seq.getEncodedHex();
};

function getPrivateBaseKey(pair) {
    var options = {
        'array': [
            new KJUR.asn1.DERInteger({'int': 0}),
            new KJUR.asn1.DERInteger({'bigint': pair.n}),
            new KJUR.asn1.DERInteger({'int': pair.e}),
                new KJUR.asn1.DERInteger({'bigint': pair.d}),
                new KJUR.asn1.DERInteger({'bigint': pair.p}),
                    new KJUR.asn1.DERInteger({'bigint': pair.q}),
                    new KJUR.asn1.DERInteger({'bigint': pair.dmp1}),
                        new KJUR.asn1.DERInteger({'bigint': pair.dmq1}),
                        new KJUR.asn1.DERInteger({'bigint': pair.coeff})
        ]
    };
    var seq = new KJUR.asn1.DERSequence(options);
    return seq.getEncodedHex();
};

getPublicBaseKeyB64 = function (pair) {
    return hex2b64(getPublicBaseKey(pair));
};

function getPrivateBaseKeyB64(pair) {
    return hex2b64(getPrivateBaseKey(pair));
};

var b64map="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
var b64pad="=";
function hex2b64(h) {
    var i;
    var c;
    var ret = "";
    for(i = 0; i+3 <= h.length; i+=3) {
        c = parseInt(h.substring(i,i+3),16);
        ret += b64map.charAt(c >> 6) + b64map.charAt(c & 63);
    }
    if(i+1 == h.length) {
        c = parseInt(h.substring(i,i+1),16);
        ret += b64map.charAt(c << 2);
    }
    else if(i+2 == h.length) {
        c = parseInt(h.substring(i,i+2),16);
        ret += b64map.charAt(c >> 2) + b64map.charAt((c & 3) << 4);
    }
    while((ret.length & 3) > 0) ret += b64pad;
    return ret;
}

/**
 * wrap the string in block of width chars. The default value for rsa keys is 64
 * characters.
 * @param {string} str the pem encoded string without header and footer
 * @param {Number} [width=64] - the length the string has to be wrapped at
 * @returns {string}
 * @private
 */
function wordwrap(str, width) {
    width = width || 64;
    if (!str) {
        return str;
    }
    var regex = '(.{1,' + width + '})( +|$\n?)|(.{1,' + width + '})';
    return str.match(RegExp(regex, 'g')).join('\n');
};

function hexToBytes(hex) {
    var arr = new Uint8Array(hex.length);
    for (var i=0; i < arr.length; i++)
	arr[i] = intAt(hex, i);
    return arr;
}

function bytesToHex(arr) {
    return scrypt.to_hex(arr);
}

function base64ToBytes(b64) {
    return base64DecToArr(b64);
}

function bytesToBase64(b) {
    return base64EncArr(b);
}

function generateKeyPair(username, password, bits) {
    var pbkdf_bytes = scrypt.crypto_scrypt(scrypt.encode_utf8(password), 
            scrypt.encode_utf8(username), n, r, p, desiredBytes);
    return cryptico.generateRSAKey(scrypt.to_hex(pbkdf_bytes), bits);
}

function encryptMessageForB64(input, pubKey) {
    var encrypt = new JSEncrypt();
    encrypt.setPublicKey(pubKey);
    return encrypt.encrypt(input);
}

function decryptB64Message(cipher, privKey) {
    var decrypt = new JSEncrypt();
    decrypt.setPrivateKey(privKey);
    return decrypt.decrypt(cipher);
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

