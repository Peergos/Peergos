'use strict';
var Doppio = require('../doppiojvm');
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
function doPrivileged(thread, action, ctx) {
    thread.setStatus(ThreadStatus.ASYNC_WAITING);
    action['run()Ljava/lang/Object;'](thread, null, function (e, rv) {
        if (e) {
            var eCls = e.getClass();
            var bsCl = thread.getBsCl();
            var errCls = bsCl.getInitializedClass(thread, 'Ljava/lang/Error;');
            var reCls = bsCl.getInitializedClass(thread, 'Ljava/lang/RuntimeException;');
            if (errCls !== null && eCls.isCastable(errCls) || reCls !== null && eCls.isCastable(reCls)) {
                thread.throwException(e);
            } else {
                thread.import('Ljava/security/PrivilegedActionException;', function (paeCons) {
                    var eobj = new paeCons(thread);
                    thread.setStatus(ThreadStatus.ASYNC_WAITING);
                    eobj['<init>(Ljava/lang/Exception;)V'](thread, [e], function (e) {
                        if (e) {
                            thread.throwException(e);
                        } else {
                            thread.throwException(eobj);
                        }
                    });
                }, false);
            }
        } else {
            thread.asyncReturn(rv);
        }
    });
}
var java_security_AccessController = function () {
    function java_security_AccessController() {
    }
    java_security_AccessController['getStackAccessControlContext()Ljava/security/AccessControlContext;'] = function (thread) {
        return null;
    };
    java_security_AccessController['getInheritedAccessControlContext()Ljava/security/AccessControlContext;'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_security_AccessController['doPrivileged(Ljava/security/PrivilegedAction;)Ljava/lang/Object;'] = doPrivileged;
    java_security_AccessController['doPrivileged(Ljava/security/PrivilegedAction;Ljava/security/AccessControlContext;)Ljava/lang/Object;'] = doPrivileged;
    java_security_AccessController['doPrivileged(Ljava/security/PrivilegedExceptionAction;)Ljava/lang/Object;'] = doPrivileged;
    java_security_AccessController['doPrivileged(Ljava/security/PrivilegedExceptionAction;Ljava/security/AccessControlContext;)Ljava/lang/Object;'] = doPrivileged;
    return java_security_AccessController;
}();
registerNatives({ 'java/security/AccessController': java_security_AccessController });
//# sourceMappingURL=java_security.js.map