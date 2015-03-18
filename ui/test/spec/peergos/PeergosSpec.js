describe("Peergos", function() {
    require('../../../lib/scrypt');
    require('../../../lib/jquery-2.1.3.min');
    require('../../../lib/blake2s');
    var nacl = require('../../../lib/nacl');
    var api = require('../../../lib/api');

    //var input = "Hola DEA.";

  it("Symmetric key encrypt/decrypt should work.", function() {

    var input = nacl.randomBytes(128);
    var nonce = nacl.randomBytes(24);
//    var key = api.randomSymmetricKey();
    var key = new api.SymmetricKey(nacl.randomBytes(32));
    console.log(key);
    var encrypted = key.encrypt(input, nonce);
    var decrypted = key.decrypt(encrypted, nonce);

    expect(nacl.util.encodeBase64(input)).toEqual(nacl.util.encodeBase64(decrypted));     

  });

});
