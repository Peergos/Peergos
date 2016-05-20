'use strict';
var Doppio = require('../doppiojvm');
var util = Doppio.VM.Util;
var Long = Doppio.VM.Long;
var sun_management_MemoryImpl = function () {
    function sun_management_MemoryImpl() {
    }
    sun_management_MemoryImpl['getMemoryPools0()[Ljava/lang/management/MemoryPoolMXBean;'] = function (thread) {
        return util.newArrayFromData(thread, thread.getBsCl(), '[Lsun/management/MemoryPoolImpl;', []);
    };
    sun_management_MemoryImpl['getMemoryManagers0()[Ljava/lang/management/MemoryManagerMXBean;'] = function (thread) {
        return util.newArrayFromData(thread, thread.getBsCl(), '[Lsun/management/MemoryManagerImpl;', []);
    };
    sun_management_MemoryImpl['getMemoryUsage0(Z)Ljava/lang/management/MemoryUsage;'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_MemoryImpl['setVerboseGC(Z)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return sun_management_MemoryImpl;
}();
var sun_management_VMManagementImpl = function () {
    function sun_management_VMManagementImpl() {
    }
    sun_management_VMManagementImpl['getVersion0()Ljava/lang/String;'] = function (thread) {
        return thread.getJVM().internString('1.2');
    };
    sun_management_VMManagementImpl['initOptionalSupportFields()V'] = function (thread) {
        var vmManagementStatics = thread.getBsCl().getInitializedClass(thread, 'Lsun/management/VMManagementImpl;').getConstructor(thread);
        vmManagementStatics['sun/management/VMManagementImpl/compTimeMonitoringSupport'] = 0;
        vmManagementStatics['sun/management/VMManagementImpl/threadContentionMonitoringSupport'] = 0;
        vmManagementStatics['sun/management/VMManagementImpl/currentThreadCpuTimeSupport'] = 0;
        vmManagementStatics['sun/management/VMManagementImpl/otherThreadCpuTimeSupport'] = 0;
        vmManagementStatics['sun/management/VMManagementImpl/bootClassPathSupport'] = 0;
        vmManagementStatics['sun/management/VMManagementImpl/objectMonitorUsageSupport'] = 0;
        vmManagementStatics['sun/management/VMManagementImpl/synchronizerUsageSupport'] = 0;
    };
    sun_management_VMManagementImpl['isThreadContentionMonitoringEnabled()Z'] = function (thread, javaThis) {
        return false;
    };
    sun_management_VMManagementImpl['isThreadCpuTimeEnabled()Z'] = function (thread, javaThis) {
        return false;
    };
    sun_management_VMManagementImpl['getTotalClassCount()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getUnloadedClassCount()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getVerboseClass()Z'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_management_VMManagementImpl['getVerboseGC()Z'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_management_VMManagementImpl['getProcessId()I'] = function (thread, javaThis) {
        return 1;
    };
    sun_management_VMManagementImpl['getVmArguments0()[Ljava/lang/String;'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getStartupTime()J'] = function (thread, javaThis) {
        return Long.fromNumber(thread.getJVM().getStartupTime().getTime());
    };
    sun_management_VMManagementImpl['getAvailableProcessors()I'] = function (thread, javaThis) {
        return 1;
    };
    sun_management_VMManagementImpl['getTotalCompileTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getTotalThreadCount()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getLiveThreadCount()I'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_management_VMManagementImpl['getPeakThreadCount()I'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_management_VMManagementImpl['getDaemonThreadCount()I'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_management_VMManagementImpl['getSafepointCount()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getTotalSafepointTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getSafepointSyncTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getTotalApplicationNonStoppedTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getLoadedClassSize()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getUnloadedClassSize()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getClassLoadingTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getMethodDataSize()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getInitializedClassCount()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getClassInitializationTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_management_VMManagementImpl['getClassVerificationTime()J'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    return sun_management_VMManagementImpl;
}();
registerNatives({
    'sun/management/MemoryImpl': sun_management_MemoryImpl,
    'sun/management/VMManagementImpl': sun_management_VMManagementImpl
});
//# sourceMappingURL=sun_management.js.map