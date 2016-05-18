"use strict";

var _typeof = typeof Symbol === "function" && typeof Symbol.iterator === "symbol" ? function (obj) { return typeof obj; } : function (obj) { return obj && typeof Symbol === "function" && obj.constructor === Symbol ? "symbol" : typeof obj; };

module.exports = function (fs, path) {
    'use strict';

    var _0777 = parseInt('0777', 8);

    /**
     * mkdir that creates folders recursive
     * it's based on https://github.com/substack/node-mkdirp
     */
    var rmkdirSync = function sync(p, opts, made) {
        if (!opts || (typeof opts === 'undefined' ? 'undefined' : _typeof(opts)) !== 'object') {
            opts = { mode: opts };
        }

        var mode = opts.mode;
        var xfs = opts.fs || fs;

        if (!made) made = null;

        p = path.resolve(p);

        try {
            xfs.mkdirSync(p, mode);
            made = made || p;
        } catch (err0) {
            switch (err0.code) {
                case 'ENOENT':
                    made = sync(path.dirname(p), opts, made);
                    sync(p, opts, made);
                    break;

                // In the case of any other error, just see if there's a dir
                // there already.  If so, then hooray!  If not, then something
                // is borked.
                default:
                    var stat;
                    try {
                        stat = xfs.statSync(p);
                    } catch (err1) {
                        throw err0;
                    }
                    if (!stat.isDirectory()) throw err0;
                    break;
            }
        }

        return made;
    };

    return {
        rmkdirSync: rmkdirSync
    };
};
