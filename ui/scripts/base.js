var desiredBytes = 64;
var n = 8192;//16384;//32768;
var r = 8, p = 1;
var scrypt = scrypt_module_factory();

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
