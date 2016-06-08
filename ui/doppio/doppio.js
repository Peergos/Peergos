(function webpackUniversalModuleDefinition(root, factory) {
	if(typeof exports === 'object' && typeof module === 'object')
		module.exports = factory(require("BrowserFS"));
	else if(typeof define === 'function' && define.amd)
		define(["BrowserFS"], factory);
	else if(typeof exports === 'object')
		exports["Doppio"] = factory(require("BrowserFS"));
	else
		root["Doppio"] = factory(root["BrowserFS"]);
})(this, function(__WEBPACK_EXTERNAL_MODULE_4__) {
return /******/ (function(modules) { // webpackBootstrap
/******/ 	// The module cache
/******/ 	var installedModules = {};
/******/
/******/ 	// The require function
/******/ 	function __webpack_require__(moduleId) {
/******/
/******/ 		// Check if module is in cache
/******/ 		if(installedModules[moduleId])
/******/ 			return installedModules[moduleId].exports;
/******/
/******/ 		// Create a new module (and put it into the cache)
/******/ 		var module = installedModules[moduleId] = {
/******/ 			exports: {},
/******/ 			id: moduleId,
/******/ 			loaded: false
/******/ 		};
/******/
/******/ 		// Execute the module function
/******/ 		modules[moduleId].call(module.exports, module, module.exports, __webpack_require__);
/******/
/******/ 		// Flag the module as loaded
/******/ 		module.loaded = true;
/******/
/******/ 		// Return the exports of the module
/******/ 		return module.exports;
/******/ 	}
/******/
/******/
/******/ 	// expose the modules object (__webpack_modules__)
/******/ 	__webpack_require__.m = modules;
/******/
/******/ 	// expose the module cache
/******/ 	__webpack_require__.c = installedModules;
/******/
/******/ 	// __webpack_public_path__
/******/ 	__webpack_require__.p = "";
/******/
/******/ 	// Load entry module and return exports
/******/ 	return __webpack_require__(0);
/******/ })
/************************************************************************/
/******/ ([
/* 0 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var doppiojvm = __webpack_require__(1);
	module.exports = doppiojvm;


/***/ },
/* 1 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var Testing = __webpack_require__(2);
	exports.Testing = Testing;
	var Heap = __webpack_require__(30);
	exports.Heap = Heap;
	var VM = __webpack_require__(46);
	exports.VM = VM;
	var Debug = __webpack_require__(51);
	exports.Debug = Debug;


/***/ },
/* 2 */
/***/ function(module, exports, __webpack_require__) {

	/* WEBPACK VAR INJECTION */(function(process) {'use strict';
	var JVM = __webpack_require__(5);
	var util = __webpack_require__(6);
	var difflib = __webpack_require__(45);
	var path = __webpack_require__(28);
	var fs = __webpack_require__(27);
	function makeTestingError(msg, origErr, fatal) {
	    var err = new Error(msg);
	    err.originalError = origErr;
	    err.fatal = fatal;
	    return err;
	}
	var OutputCapturer = function () {
	    function OutputCapturer() {
	        this._stdoutWrite = process.stdout.write;
	        this._stderrWrite = process.stderr.write;
	        this._data = '';
	        this._isCapturing = false;
	    }
	    OutputCapturer.prototype.debugWrite = function (str) {
	        this._stdoutWrite.apply(process.stdout, [
	            str,
	            'utf8'
	        ]);
	    };
	    OutputCapturer.prototype.start = function (clear) {
	        var _this = this;
	        if (this._isCapturing) {
	            throw new Error('Already capturing.');
	        }
	        this._isCapturing = true;
	        if (clear) {
	            this._data = '';
	        }
	        process.stderr.write = process.stdout.write = function (data, arg2, arg3) {
	            if (typeof data !== 'string') {
	                data = data.toString();
	            }
	            _this._data += data;
	            return true;
	        };
	    };
	    OutputCapturer.prototype.stop = function () {
	        if (!this._isCapturing) {
	            return;
	        }
	        this._isCapturing = false;
	        process.stderr.write = this._stderrWrite;
	        process.stdout.write = this._stdoutWrite;
	    };
	    OutputCapturer.prototype.getOutput = function (clear) {
	        var data = this._data;
	        if (clear) {
	            this._data = '';
	        }
	        return data;
	    };
	    return OutputCapturer;
	}();
	var DoppioTest = function () {
	    function DoppioTest(opts, cls) {
	        this.outputCapturer = new OutputCapturer();
	        this.opts = opts;
	        if (cls.indexOf('.') !== -1) {
	            cls = util.descriptor2typestr(util.int_classname(cls));
	        }
	        this.cls = cls;
	        this.outFile = path.resolve(opts.doppioHomePath, cls) + '.runout';
	    }
	    DoppioTest.prototype.constructJVM = function (cb) {
	        new JVM(util.merge(JVM.getDefaultOptions(this.opts.doppioHomePath), this.opts, {
	            classpath: [this.opts.doppioHomePath],
	            enableAssertions: true,
	            enableSystemAssertions: true
	        }), cb);
	    };
	    DoppioTest.prototype.run = function (registerGlobalErrorTrap, cb) {
	        var _this = this;
	        var outputCapturer = this.outputCapturer, _jvm = null, terminated = false, jvmConstructHasFinished = false, hasFinished = false;
	        registerGlobalErrorTrap(function (err) {
	            if (_jvm) {
	                try {
	                    _jvm.halt(1);
	                } catch (e) {
	                    err.message += '\n\nAdditionally, test runner received the following error while trying to halt the JVM: ' + e + (e.stack ? '\n\n' + e.stack : '') + '\n\nOriginal error\'s stack trace:';
	                }
	            }
	            outputCapturer.stop();
	            cb(makeTestingError('Uncaught error. Aborting further tests.\n\t' + err + (err.stack ? '\n\n' + err.stack : ''), err, true));
	        });
	        this.constructJVM(function (err, jvm) {
	            _jvm = jvm;
	            if (terminated) {
	                return;
	            }
	            if (jvmConstructHasFinished) {
	                return cb(makeTestingError('constructJVM returned twice. Aborting further tests.', null, true));
	            }
	            jvmConstructHasFinished = true;
	            if (err) {
	                cb(makeTestingError('Could not construct JVM:\n' + err, err));
	            } else {
	                outputCapturer.start(true);
	                jvm.runClass(_this.cls, [], function (status) {
	                    if (terminated) {
	                        return;
	                    }
	                    outputCapturer.stop();
	                    if (hasFinished) {
	                        return cb(makeTestingError('JVM triggered completion callback twice. Aborting further tests.', null, true));
	                    }
	                    hasFinished = true;
	                    var actual = outputCapturer.getOutput(true);
	                    fs.readFile(_this.outFile, { encoding: 'utf8' }, function (err, expected) {
	                        if (err) {
	                            cb(makeTestingError('Could not read runout file:\n' + err, err));
	                        } else {
	                            var diffText = diff(actual, expected), errMsg = null;
	                            if (diffText !== null) {
	                                errMsg = 'Output does not match native JVM.';
	                            }
	                            cb(errMsg ? makeTestingError(errMsg) : null, actual, expected, diffText);
	                        }
	                    });
	                });
	            }
	        });
	    };
	    return DoppioTest;
	}();
	exports.DoppioTest = DoppioTest;
	function findTestClasses(doppioDir, cb) {
	    var testDir = path.resolve(doppioDir, path.join('classes', 'test'));
	    fs.readdir(testDir, function (err, files) {
	        if (err) {
	            cb([]);
	        } else {
	            cb(files.filter(function (file) {
	                return path.extname(file) === '.java';
	            }).map(function (file) {
	                return path.join('classes', 'test', path.basename(file, '.java'));
	            }));
	        }
	    });
	}
	function getTests(opts, cb) {
	    var testClasses = opts.testClasses, tests;
	    if (testClasses == null || testClasses.length === 0) {
	        findTestClasses(opts.doppioHomePath, function (testClasses) {
	            opts.testClasses = testClasses;
	            getTests(opts, cb);
	        });
	    } else {
	        cb(testClasses.map(function (testClass) {
	            return new DoppioTest(opts, testClass);
	        }));
	    }
	}
	exports.getTests = getTests;
	function diff(doppioOut, nativeOut) {
	    var doppioLines = doppioOut.split(/\n/), jvmLines = nativeOut.split(/\n/), diff = difflib.text_diff(doppioLines, jvmLines, 2);
	    if (diff.length > 0) {
	        return 'Doppio | Java\n' + diff.join('\n');
	    }
	    return null;
	}
	exports.diff = diff;
	function runTests(opts, quiet, continueAfterFailure, hideDiffs, registerGlobalErrorTrap, cb) {
	    function print(str) {
	        if (!quiet) {
	            process.stdout.write(str);
	        }
	    }
	    getTests(opts, function (tests) {
	        util.asyncForEach(tests, function (test, nextTest) {
	            var hasFinished = false;
	            print('[' + test.cls + ']: Running... ');
	            test.run(registerGlobalErrorTrap, function (err, actual, expected, diff) {
	                if (err && !hideDiffs && diff) {
	                    err.message += '\n' + diff;
	                }
	                if (err) {
	                    print('fail.\n\t' + err.message + '\n');
	                    if (err.originalError && err.originalError.stack) {
	                        print(err.stack + '\n');
	                    }
	                    if (!continueAfterFailure || err['fatal']) {
	                        err.message = 'Failed ' + test.cls + ': ' + err.message;
	                        nextTest(err);
	                    } else {
	                        nextTest();
	                    }
	                } else {
	                    print('pass.\n');
	                    nextTest();
	                }
	            });
	        }, cb);
	    });
	}
	exports.runTests = runTests;
	
	/* WEBPACK VAR INJECTION */}.call(exports, __webpack_require__(3)))

/***/ },
/* 3 */
/***/ function(module, exports, __webpack_require__) {

	var BrowserFS = __webpack_require__(4);module.exports=BrowserFS.BFSRequire('process');


/***/ },
/* 4 */
/***/ function(module, exports) {

	module.exports = __WEBPACK_EXTERNAL_MODULE_4__;

/***/ },
/* 5 */
/***/ function(module, exports, __webpack_require__) {

	var require;/* WEBPACK VAR INJECTION */(function(process) {'use strict';
	var util = __webpack_require__(6);
	var SafeMap = __webpack_require__(10);
	var methods = __webpack_require__(11);
	var ClassLoader = __webpack_require__(20);
	var fs = __webpack_require__(27);
	var path = __webpack_require__(28);
	var buffer = __webpack_require__(29);
	var threading_1 = __webpack_require__(15);
	var enums_1 = __webpack_require__(9);
	var Heap = __webpack_require__(30);
	var assert = __webpack_require__(13);
	var Parker = __webpack_require__(31);
	var threadpool_1 = __webpack_require__(32);
	var JDKInfo = __webpack_require__(33);
	var BrowserFS = __webpack_require__(4);
	var deflate = __webpack_require__(34);
	var inflate = __webpack_require__(40);
	var zstream = __webpack_require__(43);
	var crc32 = __webpack_require__(38);
	var adler32 = __webpack_require__(37);
	var pkg;
	if (util.are_in_browser()) {
	    pkg = __webpack_require__(44);
	} else {
	    pkg = __webpack_require__(44);
	}
	var coreClasses = [
	    'Ljava/lang/String;',
	    'Ljava/lang/Class;',
	    'Ljava/lang/ClassLoader;',
	    'Ljava/lang/reflect/Constructor;',
	    'Ljava/lang/reflect/Field;',
	    'Ljava/lang/reflect/Method;',
	    'Ljava/lang/Error;',
	    'Ljava/lang/StackTraceElement;',
	    'Ljava/lang/System;',
	    'Ljava/lang/Thread;',
	    'Ljava/lang/ThreadGroup;',
	    'Ljava/lang/Throwable;',
	    'Ljava/nio/ByteOrder;',
	    'Lsun/misc/VM;',
	    'Lsun/reflect/ConstantPool;',
	    'Ljava/lang/Byte;',
	    'Ljava/lang/Character;',
	    'Ljava/lang/Double;',
	    'Ljava/lang/Float;',
	    'Ljava/lang/Integer;',
	    'Ljava/lang/Long;',
	    'Ljava/lang/Short;',
	    'Ljava/lang/Void;',
	    'Ljava/io/FileDescriptor;',
	    'Ljava/lang/Boolean;',
	    '[Lsun/management/MemoryManagerImpl;',
	    '[Lsun/management/MemoryPoolImpl;',
	    'Lsun/nio/fs/UnixConstants;'
	];
	var JVM = function () {
	    function JVM(opts, cb) {
	        var _this = this;
	        this.systemProperties = null;
	        this.internedStrings = new SafeMap();
	        this.bsCl = null;
	        this.threadPool = null;
	        this.natives = {};
	        this.heap = new Heap(20 * 1024 * 1024);
	        this.nativeClasspath = null;
	        this.startupTime = new Date();
	        this.terminationCb = null;
	        this.firstThread = null;
	        this.responsiveness = null;
	        this.enableSystemAssertions = false;
	        this.enabledAssertions = false;
	        this.disabledAssertions = [];
	        this.printJITCompilation = false;
	        this.systemClassLoader = null;
	        this.nextRef = 0;
	        this.vtraceMethods = {};
	        this.dumpCompiledCodeDir = null;
	        this.parker = new Parker();
	        this.status = enums_1.JVMStatus.BOOTING;
	        this.exitCode = 0;
	        this.jitDisabled = false;
	        this.dumpJITStats = false;
	        if (typeof opts.doppioHomePath !== 'string') {
	            throw new TypeError('opts.doppioHomePath *must* be specified.');
	        }
	        opts = util.merge(JVM.getDefaultOptions(opts.doppioHomePath), opts);
	        this.jitDisabled = opts.intMode;
	        this.dumpJITStats = opts.dumpJITStats;
	        var bootstrapClasspath = opts.bootstrapClasspath.map(function (p) {
	                return path.resolve(p);
	            }), bootupTasks = [], firstThread, firstThreadObj;
	        if (!Array.isArray(opts.bootstrapClasspath) || opts.bootstrapClasspath.length === 0) {
	            throw new TypeError('opts.bootstrapClasspath must be specified as an array of file paths.');
	        }
	        if (!Array.isArray(opts.classpath)) {
	            throw new TypeError('opts.classpath must be specified as an array of file paths.');
	        }
	        if (typeof opts.javaHomePath !== 'string') {
	            throw new TypeError('opts.javaHomePath must be specified.');
	        }
	        if (!Array.isArray(opts.nativeClasspath) || opts.nativeClasspath.length === 0) {
	            throw new TypeError('opts.nativeClasspath must be specified as an array of file paths.');
	        }
	        this.nativeClasspath = opts.nativeClasspath;
	        if (opts.enableSystemAssertions) {
	            this.enableSystemAssertions = opts.enableSystemAssertions;
	        }
	        if (opts.enableAssertions) {
	            this.enabledAssertions = opts.enableAssertions;
	        }
	        if (opts.disableAssertions) {
	            this.disabledAssertions = opts.disableAssertions;
	        }
	        this.responsiveness = opts.responsiveness;
	        this._initSystemProperties(bootstrapClasspath, opts.classpath.map(function (p) {
	            return path.resolve(p);
	        }), path.resolve(opts.javaHomePath), path.resolve(opts.tmpDir), opts.properties);
	        bootupTasks.push(function (next) {
	            _this.initializeNatives(next);
	        });
	        bootupTasks.push(function (next) {
	            _this.bsCl = new ClassLoader.BootstrapClassLoader(_this.systemProperties['java.home'], bootstrapClasspath, next);
	        });
	        bootupTasks.push(function (next) {
	            _this.threadPool = new threadpool_1['default'](function () {
	                return _this.threadPoolIsEmpty();
	            });
	            _this.bsCl.resolveClass(null, 'Ljava/lang/Thread;', function (threadCdata) {
	                if (threadCdata == null) {
	                    next('Failed to resolve java/lang/Thread.');
	                } else {
	                    firstThreadObj = new (threadCdata.getConstructor(null))(null);
	                    firstThreadObj.$thread = firstThread = _this.firstThread = new threading_1.JVMThread(_this, _this.threadPool, firstThreadObj);
	                    firstThreadObj.ref = 1;
	                    firstThreadObj['java/lang/Thread/priority'] = 5;
	                    firstThreadObj['java/lang/Thread/name'] = util.initCarr(_this.bsCl, 'main');
	                    firstThreadObj['java/lang/Thread/blockerLock'] = new (_this.bsCl.getResolvedClass('Ljava/lang/Object;').getConstructor(firstThread))(firstThread);
	                    next();
	                }
	            });
	        });
	        bootupTasks.push(function (next) {
	            util.asyncForEach(coreClasses, function (coreClass, nextItem) {
	                _this.bsCl.initializeClass(firstThread, coreClass, function (cdata) {
	                    if (cdata == null) {
	                        nextItem('Failed to initialize ' + coreClass);
	                    } else {
	                        if (coreClass === 'Ljava/lang/ThreadGroup;') {
	                            var threadGroupCons = cdata.getConstructor(firstThread), groupObj = new threadGroupCons(firstThread);
	                            groupObj['<init>()V'](firstThread, null, function (e) {
	                                firstThreadObj['java/lang/Thread/group'] = groupObj;
	                                nextItem(e);
	                            });
	                        } else {
	                            nextItem();
	                        }
	                    }
	                });
	            }, next);
	        });
	        bootupTasks.push(function (next) {
	            var sysInit = _this.bsCl.getInitializedClass(firstThread, 'Ljava/lang/System;').getConstructor(firstThread);
	            sysInit['java/lang/System/initializeSystemClass()V'](firstThread, null, next);
	            ;
	        });
	        bootupTasks.push(function (next) {
	            var clCons = _this.bsCl.getInitializedClass(firstThread, 'Ljava/lang/ClassLoader;').getConstructor(firstThread);
	            clCons['java/lang/ClassLoader/getSystemClassLoader()Ljava/lang/ClassLoader;'](firstThread, null, function (e, rv) {
	                if (e) {
	                    next(e);
	                } else {
	                    _this.systemClassLoader = rv.$loader;
	                    firstThreadObj['java/lang/Thread/contextClassLoader'] = rv;
	                    var defaultAssertionStatus = _this.enabledAssertions === true ? 1 : 0;
	                    rv['java/lang/ClassLoader/setDefaultAssertionStatus(Z)V'](firstThread, [defaultAssertionStatus], next);
	                }
	            });
	        });
	        util.asyncSeries(bootupTasks, function (err) {
	            setImmediate(function () {
	                if (err) {
	                    _this.status = enums_1.JVMStatus.TERMINATED;
	                    cb(err);
	                } else {
	                    _this.status = enums_1.JVMStatus.BOOTED;
	                    cb(null, _this);
	                }
	            });
	        });
	    }
	    JVM.prototype.getResponsiveness = function () {
	        var resp = this.responsiveness;
	        if (typeof resp === 'number') {
	            return resp;
	        } else if (typeof resp === 'function') {
	            return resp();
	        }
	    };
	    JVM.getDefaultOptions = function (doppioHome) {
	        var javaHome = path.join(doppioHome, 'vendor', 'java_home');
	        return {
	            doppioHomePath: doppioHome,
	            classpath: ['.'],
	            bootstrapClasspath: JDKInfo.classpath.map(function (item) {
	                return path.join(javaHome, item);
	            }),
	            javaHomePath: javaHome,
	            nativeClasspath: [path.join(doppioHome, 'natives')],
	            enableSystemAssertions: false,
	            enableAssertions: false,
	            disableAssertions: null,
	            properties: {},
	            tmpDir: '/tmp',
	            responsiveness: 1000,
	            intMode: false,
	            dumpJITStats: false
	        };
	    };
	    JVM.getCompiledJDKURL = function () {
	        return JDKInfo.url;
	    };
	    JVM.getJDKInfo = function () {
	        return JDKInfo;
	    };
	    JVM.prototype.getSystemClassLoader = function () {
	        return this.systemClassLoader;
	    };
	    JVM.isReleaseBuild = function () {
	        return typeof RELEASE !== 'undefined' && RELEASE;
	    };
	    JVM.prototype.getNextRef = function () {
	        return this.nextRef++;
	    };
	    JVM.prototype.getParker = function () {
	        return this.parker;
	    };
	    JVM.prototype.runClass = function (className, args, cb) {
	        var _this = this;
	        if (this.status !== enums_1.JVMStatus.BOOTED) {
	            switch (this.status) {
	            case enums_1.JVMStatus.BOOTING:
	                throw new Error('JVM is currently booting up. Please wait for it to call the bootup callback, which you passed to the constructor.');
	            case enums_1.JVMStatus.RUNNING:
	                throw new Error('JVM is already running.');
	            case enums_1.JVMStatus.TERMINATED:
	                throw new Error('This JVM has already terminated. Please create a new JVM.');
	            case enums_1.JVMStatus.TERMINATING:
	                throw new Error('This JVM is currently terminating. You should create a new JVM for each class you wish to run.');
	            }
	        }
	        this.terminationCb = cb;
	        var thread = this.firstThread;
	        assert(thread != null, 'Thread isn\'t created yet?');
	        className = util.int_classname(className);
	        this.systemClassLoader.initializeClass(thread, className, function (cdata) {
	            if (cdata != null) {
	                var strArrCons = _this.bsCl.getInitializedClass(thread, '[Ljava/lang/String;').getConstructor(thread), jvmifiedArgs = new strArrCons(thread, args.length), i;
	                for (i = 0; i < args.length; i++) {
	                    jvmifiedArgs.array[i] = util.initString(_this.bsCl, args[i]);
	                }
	                _this.status = enums_1.JVMStatus.RUNNING;
	                var cdataStatics = cdata.getConstructor(thread);
	                if (cdataStatics['main([Ljava/lang/String;)V']) {
	                    cdataStatics['main([Ljava/lang/String;)V'](thread, [jvmifiedArgs]);
	                } else {
	                    thread.throwNewException('Ljava/lang/NoSuchMethodError;', 'Could not find main method in class ' + cdata.getExternalName() + '.');
	                }
	            } else {
	                process.stdout.write('Error: Could not find or load main class ' + util.ext_classname(className) + '\n');
	                _this.terminationCb(1);
	            }
	        });
	    };
	    JVM.prototype.isJITDisabled = function () {
	        return this.jitDisabled;
	    };
	    JVM.prototype.shouldVtrace = function (sig) {
	        return this.vtraceMethods[sig] === true;
	    };
	    JVM.prototype.vtraceMethod = function (sig) {
	        this.vtraceMethods[sig] = true;
	    };
	    JVM.prototype.runJar = function (args, cb) {
	        this.runClass('doppio.JarLauncher', args, cb);
	    };
	    JVM.prototype.threadPoolIsEmpty = function () {
	        var systemClass, systemCons;
	        switch (this.status) {
	        case enums_1.JVMStatus.BOOTING:
	            return false;
	        case enums_1.JVMStatus.BOOTED:
	            assert(false, 'Thread pool should not become empty after JVM is booted, but before it begins to run.');
	            return false;
	        case enums_1.JVMStatus.RUNNING:
	            this.status = enums_1.JVMStatus.TERMINATING;
	            systemClass = this.bsCl.getInitializedClass(this.firstThread, 'Ljava/lang/System;');
	            assert(systemClass !== null, 'Invariant failure: System class must be initialized when JVM is in RUNNING state.');
	            systemCons = systemClass.getConstructor(this.firstThread);
	            systemCons['java/lang/System/exit(I)V'](this.firstThread, [0]);
	            return false;
	        case enums_1.JVMStatus.TERMINATED:
	            assert(false, 'Invariant failure: Thread pool cannot be emptied post-JVM termination.');
	            return false;
	        case enums_1.JVMStatus.TERMINATING:
	            if (!RELEASE && this.dumpJITStats) {
	                methods.dumpStats();
	            }
	            this.status = enums_1.JVMStatus.TERMINATED;
	            if (this.terminationCb) {
	                this.terminationCb(this.exitCode);
	            }
	            this.firstThread.close();
	            return true;
	        }
	    };
	    JVM.prototype.hasVMBooted = function () {
	        return !(this.status === enums_1.JVMStatus.BOOTING || this.status === enums_1.JVMStatus.BOOTED);
	    };
	    JVM.prototype.halt = function (status) {
	        this.exitCode = status;
	        this.status = enums_1.JVMStatus.TERMINATING;
	        this.threadPool.getThreads().forEach(function (t) {
	            t.setStatus(enums_1.ThreadStatus.TERMINATED);
	        });
	    };
	    JVM.prototype.getSystemProperty = function (prop) {
	        return this.systemProperties[prop];
	    };
	    JVM.prototype.getSystemPropertyNames = function () {
	        return Object.keys(this.systemProperties);
	    };
	    JVM.prototype.getHeap = function () {
	        return this.heap;
	    };
	    JVM.prototype.internString = function (str, javaObj) {
	        if (this.internedStrings.has(str)) {
	            return this.internedStrings.get(str);
	        } else {
	            if (!javaObj) {
	                javaObj = util.initString(this.bsCl, str);
	            }
	            this.internedStrings.set(str, javaObj);
	            return javaObj;
	        }
	    };
	    JVM.prototype.evalNativeModule = function (mod) {
	        'use strict';
	        var rv, DoppioJVM = __webpack_require__(1), Buffer = buffer.Buffer, process2 = process, savedRequire = typeof require !== 'undefined' ? require : function (moduleName) {
	                throw new Error('Cannot find module ' + moduleName);
	            };
	        (function () {
	            function registerNatives(defs) {
	                rv = defs;
	            }
	            eval('\nvar process = process2;\nfunction require(name) {\n  switch(name) {\n    case \'doppiojvm\':\n    case \'../doppiojvm\':\n      return DoppioJVM;\n    case \'fs\':\n      return fs;\n    case \'path\':\n      return path;\n    case \'buffer\':\n      return buffer;\n    case \'browserfs\':\n      return BrowserFS;\n    case \'pako/lib/zlib/zstream\':\n      return zstream;\n    case \'pako/lib/zlib/inflate\':\n      return inflate;\n    case \'pako/lib/zlib/deflate\':\n      return deflate;\n    case \'pako/lib/zlib/crc32\':\n      return crc32;\n    case \'pako/lib/zlib/adler32\':\n      return adler32;\n    default:\n      return savedRequire(name);\n  }\n}\n/**\n * Emulate AMD module \'define\' function for natives compiled as AMD modules.\n */\nfunction define(resources, module) {\n  var args = [];\n  resources.forEach(function(resource) {\n    switch (resource) {\n      case \'require\':\n        args.push(require);\n        break;\n      case \'exports\':\n        args.push({});\n        break;\n      default:\n        args.push(require(resource));\n        break;\n    }\n  });\n  module.apply(null, args);\n}\neval(mod);\n');
	        }());
	        return rv;
	    };
	    JVM.prototype.registerNatives = function (newNatives) {
	        var clsName, methSig;
	        for (clsName in newNatives) {
	            if (newNatives.hasOwnProperty(clsName)) {
	                if (!this.natives.hasOwnProperty(clsName)) {
	                    this.natives[clsName] = {};
	                }
	                var clsMethods = newNatives[clsName];
	                for (methSig in clsMethods) {
	                    if (clsMethods.hasOwnProperty(methSig)) {
	                        this.natives[clsName][methSig] = clsMethods[methSig];
	                    }
	                }
	            }
	        }
	    };
	    JVM.prototype.registerNative = function (clsName, methSig, native) {
	        this.registerNatives({ clsName: { methSig: native } });
	    };
	    JVM.prototype.getNative = function (clsName, methSig) {
	        clsName = util.descriptor2typestr(clsName);
	        if (this.natives.hasOwnProperty(clsName)) {
	            var clsMethods = this.natives[clsName];
	            if (clsMethods.hasOwnProperty(methSig)) {
	                return clsMethods[methSig];
	            }
	        }
	        return null;
	    };
	    JVM.prototype.getNatives = function () {
	        return this.natives;
	    };
	    JVM.prototype.initializeNatives = function (doneCb) {
	        var _this = this;
	        var nextDir = function () {
	                if (i === _this.nativeClasspath.length) {
	                    var count = processFiles.length;
	                    processFiles.forEach(function (file) {
	                        fs.readFile(file, function (err, data) {
	                            if (!err) {
	                                _this.registerNatives(_this.evalNativeModule(data.toString()));
	                            }
	                            if (--count === 0) {
	                                doneCb();
	                            }
	                        });
	                    });
	                } else {
	                    var dir = _this.nativeClasspath[i++];
	                    fs.readdir(dir, function (err, files) {
	                        if (err) {
	                            return doneCb();
	                        }
	                        var j, file;
	                        for (j = 0; j < files.length; j++) {
	                            file = files[j];
	                            if (file.substring(file.length - 3, file.length) === '.js') {
	                                processFiles.push(path.join(dir, file));
	                            }
	                        }
	                        nextDir();
	                    });
	                }
	            }, i = 0, processFiles = [];
	        nextDir();
	    };
	    JVM.prototype._initSystemProperties = function (bootstrapClasspath, javaClassPath, javaHomePath, tmpDir, opts) {
	        this.systemProperties = util.merge({
	            'java.class.path': javaClassPath.join(':'),
	            'java.home': javaHomePath,
	            'java.ext.dirs': path.join(javaHomePath, 'lib', 'ext'),
	            'java.io.tmpdir': tmpDir,
	            'sun.boot.class.path': bootstrapClasspath.join(':'),
	            'file.encoding': 'UTF-8',
	            'java.vendor': 'Doppio',
	            'java.version': '1.8',
	            'java.vendor.url': 'https://github.com/plasma-umass/doppio',
	            'java.class.version': '52.0',
	            'java.specification.version': '1.8',
	            'line.separator': '\n',
	            'file.separator': path.sep,
	            'path.separator': ':',
	            'user.dir': path.resolve('.'),
	            'user.home': '.',
	            'user.name': 'DoppioUser',
	            'os.name': 'doppio',
	            'os.arch': 'js',
	            'os.version': '0',
	            'java.vm.name': 'DoppioJVM 32-bit VM',
	            'java.vm.version': pkg.version,
	            'java.vm.vendor': 'PLASMA@UMass',
	            'java.awt.headless': util.are_in_browser().toString(),
	            'java.awt.graphicsenv': 'classes.awt.CanvasGraphicsEnvironment',
	            'jline.terminal': 'jline.UnsupportedTerminal',
	            'sun.arch.data.model': '32',
	            'sun.jnu.encoding': 'UTF-8'
	        }, opts);
	    };
	    JVM.prototype.getBootstrapClassLoader = function () {
	        return this.bsCl;
	    };
	    JVM.prototype.getStartupTime = function () {
	        return this.startupTime;
	    };
	    JVM.prototype.areSystemAssertionsEnabled = function () {
	        return this.enableSystemAssertions;
	    };
	    JVM.prototype.getEnabledAssertions = function () {
	        return this.enabledAssertions;
	    };
	    JVM.prototype.getDisabledAssertions = function () {
	        return this.disabledAssertions;
	    };
	    JVM.prototype.setPrintJITCompilation = function (enabledOrNot) {
	        this.printJITCompilation = enabledOrNot;
	    };
	    JVM.prototype.shouldPrintJITCompilation = function () {
	        return this.printJITCompilation;
	    };
	    JVM.prototype.dumpCompiledCode = function (dir) {
	        this.dumpCompiledCodeDir = dir;
	    };
	    JVM.prototype.shouldDumpCompiledCode = function () {
	        return this.dumpCompiledCodeDir !== null;
	    };
	    JVM.prototype.dumpObjectDefinition = function (cls, evalText) {
	        if (this.shouldDumpCompiledCode()) {
	            fs.writeFile(path.resolve(this.dumpCompiledCodeDir, cls.getExternalName() + '_object.dump'), evalText, function () {
	            });
	        }
	    };
	    JVM.prototype.dumpBridgeMethod = function (methodSig, evalText) {
	        if (this.shouldDumpCompiledCode()) {
	            fs.appendFile(path.resolve(this.dumpCompiledCodeDir, 'vmtarget_bridge_methods.dump'), methodSig + ':\n' + evalText + '\n\n', function () {
	            });
	        }
	    };
	    JVM.prototype.dumpState = function (filename, cb) {
	        fs.appendFile(filename, this.threadPool.getThreads().map(function (t) {
	            return 'Thread ' + t.getRef() + ':\n' + t.getPrintableStackTrace();
	        }).join('\n\n'), cb);
	    };
	    return JVM;
	}();
	module.exports = JVM;
	
	/* WEBPACK VAR INJECTION */}.call(exports, __webpack_require__(3)))

/***/ },
/* 6 */
/***/ function(module, exports, __webpack_require__) {

	/* WEBPACK VAR INJECTION */(function(process, Buffer) {'use strict';
	var gLong = __webpack_require__(8);
	var enums = __webpack_require__(9);
	function merge() {
	    var literals = [];
	    for (var _i = 0; _i < arguments.length; _i++) {
	        literals[_i - 0] = arguments[_i];
	    }
	    var newObject = {};
	    literals.forEach(function (literal) {
	        Object.keys(literal).forEach(function (key) {
	            newObject[key] = literal[key];
	        });
	    });
	    return newObject;
	}
	exports.merge = merge;
	function are_in_browser() {
	    return process.platform === 'browser';
	}
	exports.are_in_browser = are_in_browser;
	exports.typedArraysSupported = typeof ArrayBuffer !== 'undefined';
	function jvmName2JSName(jvmName) {
	    switch (jvmName[0]) {
	    case 'L':
	        return jvmName.slice(1, jvmName.length - 1).replace(/_/g, '__').replace(/[\/.;$<>\[\]:\\=^-]/g, '_');
	    case '[':
	        return 'ARR_' + jvmName2JSName(jvmName.slice(1));
	    default:
	        return jvmName;
	    }
	}
	exports.jvmName2JSName = jvmName2JSName;
	function reescapeJVMName(jvmName) {
	    return jvmName.replace(/\\/g, '\\\\');
	}
	exports.reescapeJVMName = reescapeJVMName;
	function asyncForEach(lst, fn, done_cb) {
	    var i = -1;
	    function process(err) {
	        if (err) {
	            done_cb(err);
	        } else {
	            i++;
	            if (i < lst.length) {
	                fn(lst[i], process);
	            } else {
	                done_cb();
	            }
	        }
	    }
	    process();
	}
	exports.asyncForEach = asyncForEach;
	function asyncSeries(tasks, doneCb) {
	    var i = -1;
	    function process(err) {
	        if (err) {
	            doneCb(err);
	        } else {
	            i++;
	            if (i < tasks.length) {
	                tasks[i](process);
	            } else {
	                doneCb();
	            }
	        }
	    }
	    process();
	}
	exports.asyncSeries = asyncSeries;
	function asyncFind(lst, fn, done_cb) {
	    var i = -1;
	    function process(success) {
	        if (success) {
	            done_cb(lst[i]);
	        } else {
	            i++;
	            if (i < lst.length) {
	                fn(lst[i], process);
	            } else {
	                done_cb();
	            }
	        }
	    }
	    process(false);
	}
	exports.asyncFind = asyncFind;
	if (!Math['imul']) {
	    Math['imul'] = function (a, b) {
	        var ah = a >>> 16 & 65535;
	        var al = a & 65535;
	        var bh = b >>> 16 & 65535;
	        var bl = b & 65535;
	        return al * bl + (ah * bl + al * bh << 16 >>> 0) | 0;
	    };
	}
	if (!Math['expm1']) {
	    Math['expm1'] = function (x) {
	        if (Math.abs(x) < 0.00001) {
	            return x + 0.5 * x * x;
	        } else {
	            return Math.exp(x) - 1;
	        }
	    };
	}
	if (!Math['sinh']) {
	    Math['sinh'] = function (a) {
	        var exp = Math.exp(a);
	        return (exp - 1 / exp) / 2;
	    };
	}
	if (!Array.prototype.indexOf) {
	    Array.prototype.indexOf = function (searchElement, fromIndex) {
	        if (this == null) {
	            throw new TypeError();
	        }
	        var t = Object(this);
	        var len = t.length >>> 0;
	        if (len === 0) {
	            return -1;
	        }
	        var n = 0;
	        if (fromIndex !== undefined) {
	            n = Number(fromIndex);
	            if (n != n) {
	                n = 0;
	            } else if (n != 0 && n != Infinity && n != -Infinity) {
	                n = ((n > 0 ? 1 : 0) || -1) * Math.floor(Math.abs(n));
	            }
	        }
	        if (n >= len) {
	            return -1;
	        }
	        var k = n >= 0 ? n : Math.max(len - Math.abs(n), 0);
	        for (; k < len; k++) {
	            if (k in t && t[k] === searchElement) {
	                return k;
	            }
	        }
	        return -1;
	    };
	}
	function checkAccess(accessingCls, owningCls, accessFlags) {
	    if (accessFlags.isPublic()) {
	        return true;
	    } else if (accessFlags.isProtected()) {
	        return accessingCls.getPackageName() === owningCls.getPackageName() || accessingCls.isSubclass(owningCls);
	    } else if (accessFlags.isPrivate()) {
	        return accessingCls === owningCls;
	    } else {
	        return accessingCls.getPackageName() === owningCls.getPackageName();
	    }
	}
	exports.checkAccess = checkAccess;
	function float2int(a) {
	    if (a > enums.Constants.INT_MAX) {
	        return enums.Constants.INT_MAX;
	    } else if (a < enums.Constants.INT_MIN) {
	        return enums.Constants.INT_MIN;
	    } else {
	        return a | 0;
	    }
	}
	exports.float2int = float2int;
	var supportsArrayBuffers = typeof ArrayBuffer !== 'undefined';
	function byteArray2Buffer(bytes, offset, len) {
	    if (offset === void 0) {
	        offset = 0;
	    }
	    if (len === void 0) {
	        len = bytes.length;
	    }
	    if (supportsArrayBuffers && ArrayBuffer.isView(bytes)) {
	        var offset_1 = bytes.byteOffset;
	        return new Buffer(bytes.buffer.slice(offset_1, offset_1 + bytes.length));
	    } else {
	        var buff = new Buffer(len), i;
	        for (i = 0; i < len; i++) {
	            buff.writeInt8(bytes[offset + i], i);
	        }
	        return buff;
	    }
	}
	exports.byteArray2Buffer = byteArray2Buffer;
	function wrapFloat(a) {
	    if (a > 3.4028234663852886e+38) {
	        return Number.POSITIVE_INFINITY;
	    }
	    if (0 < a && a < 1.401298464324817e-45) {
	        return 0;
	    }
	    if (a < -3.4028234663852886e+38) {
	        return Number.NEGATIVE_INFINITY;
	    }
	    if (0 > a && a > -1.401298464324817e-45) {
	        return 0;
	    }
	    return a;
	}
	exports.wrapFloat = wrapFloat;
	function chars2jsStr(jvmCarr, offset, count) {
	    if (offset === void 0) {
	        offset = 0;
	    }
	    if (count === void 0) {
	        count = jvmCarr.array.length;
	    }
	    var i, carrArray = jvmCarr.array, rv = '', endOffset = offset + count;
	    for (i = offset; i < endOffset; i++) {
	        rv += String.fromCharCode(carrArray[i]);
	    }
	    return rv;
	}
	exports.chars2jsStr = chars2jsStr;
	function bytestr2Array(byteStr) {
	    var rv = [];
	    for (var i = 0; i < byteStr.length; i++) {
	        rv.push(byteStr.charCodeAt(i));
	    }
	    return rv;
	}
	exports.bytestr2Array = bytestr2Array;
	function array2bytestr(byteArray) {
	    var rv = '';
	    for (var i = 0; i < byteArray.length; i++) {
	        rv += String.fromCharCode(byteArray[i]);
	    }
	    return rv;
	}
	exports.array2bytestr = array2bytestr;
	(function (FlagMasks) {
	    FlagMasks[FlagMasks['PUBLIC'] = 1] = 'PUBLIC';
	    FlagMasks[FlagMasks['PRIVATE'] = 2] = 'PRIVATE';
	    FlagMasks[FlagMasks['PROTECTED'] = 4] = 'PROTECTED';
	    FlagMasks[FlagMasks['STATIC'] = 8] = 'STATIC';
	    FlagMasks[FlagMasks['FINAL'] = 16] = 'FINAL';
	    FlagMasks[FlagMasks['SYNCHRONIZED'] = 32] = 'SYNCHRONIZED';
	    FlagMasks[FlagMasks['SUPER'] = 32] = 'SUPER';
	    FlagMasks[FlagMasks['VOLATILE'] = 64] = 'VOLATILE';
	    FlagMasks[FlagMasks['TRANSIENT'] = 128] = 'TRANSIENT';
	    FlagMasks[FlagMasks['VARARGS'] = 128] = 'VARARGS';
	    FlagMasks[FlagMasks['NATIVE'] = 256] = 'NATIVE';
	    FlagMasks[FlagMasks['INTERFACE'] = 512] = 'INTERFACE';
	    FlagMasks[FlagMasks['ABSTRACT'] = 1024] = 'ABSTRACT';
	    FlagMasks[FlagMasks['STRICT'] = 2048] = 'STRICT';
	}(exports.FlagMasks || (exports.FlagMasks = {})));
	var FlagMasks = exports.FlagMasks;
	var Flags = function () {
	    function Flags(byte) {
	        this.byte = byte;
	    }
	    Flags.prototype.isPublic = function () {
	        return (this.byte & FlagMasks.PUBLIC) > 0;
	    };
	    Flags.prototype.isPrivate = function () {
	        return (this.byte & FlagMasks.PRIVATE) > 0;
	    };
	    Flags.prototype.isProtected = function () {
	        return (this.byte & FlagMasks.PROTECTED) > 0;
	    };
	    Flags.prototype.isStatic = function () {
	        return (this.byte & FlagMasks.STATIC) > 0;
	    };
	    Flags.prototype.isFinal = function () {
	        return (this.byte & FlagMasks.FINAL) > 0;
	    };
	    Flags.prototype.isSynchronized = function () {
	        return (this.byte & FlagMasks.SYNCHRONIZED) > 0;
	    };
	    Flags.prototype.isSuper = function () {
	        return (this.byte & FlagMasks.SUPER) > 0;
	    };
	    Flags.prototype.isVolatile = function () {
	        return (this.byte & FlagMasks.VOLATILE) > 0;
	    };
	    Flags.prototype.isTransient = function () {
	        return (this.byte & FlagMasks.TRANSIENT) > 0;
	    };
	    Flags.prototype.isNative = function () {
	        return (this.byte & FlagMasks.NATIVE) > 0;
	    };
	    Flags.prototype.isInterface = function () {
	        return (this.byte & FlagMasks.INTERFACE) > 0;
	    };
	    Flags.prototype.isAbstract = function () {
	        return (this.byte & FlagMasks.ABSTRACT) > 0;
	    };
	    Flags.prototype.isStrict = function () {
	        return (this.byte & FlagMasks.STRICT) > 0;
	    };
	    Flags.prototype.setNative = function (n) {
	        if (n) {
	            this.byte = this.byte | FlagMasks.NATIVE;
	        } else {
	            this.byte = this.byte & ~FlagMasks.NATIVE;
	        }
	    };
	    Flags.prototype.isVarArgs = function () {
	        return (this.byte & FlagMasks.VARARGS) > 0;
	    };
	    Flags.prototype.getRawByte = function () {
	        return this.byte;
	    };
	    return Flags;
	}();
	exports.Flags = Flags;
	function initialValue(type_str) {
	    if (type_str === 'J')
	        return gLong.ZERO;
	    var c = type_str[0];
	    if (c === '[' || c === 'L')
	        return null;
	    return 0;
	}
	exports.initialValue = initialValue;
	function ext_classname(str) {
	    return descriptor2typestr(str).replace(/\//g, '.');
	}
	exports.ext_classname = ext_classname;
	function int_classname(str) {
	    return typestr2descriptor(str.replace(/\./g, '/'));
	}
	exports.int_classname = int_classname;
	function verify_int_classname(str) {
	    var array_nesting = str.match(/^\[*/)[0].length;
	    if (array_nesting > 255) {
	        return false;
	    }
	    if (array_nesting > 0) {
	        str = str.slice(array_nesting);
	    }
	    if (str[0] === 'L') {
	        if (str[str.length - 1] !== ';') {
	            return false;
	        }
	        str = str.slice(1, -1);
	    }
	    if (str in exports.internal2external) {
	        return true;
	    }
	    if (str.match(/\/{2,}/)) {
	        return false;
	    }
	    var parts = str.split('/');
	    for (var i = 0; i < parts.length; i++) {
	        if (parts[i].match(/[^$_a-z0-9]/i)) {
	            return false;
	        }
	    }
	    return true;
	}
	exports.verify_int_classname = verify_int_classname;
	exports.internal2external = {
	    B: 'byte',
	    C: 'char',
	    D: 'double',
	    F: 'float',
	    I: 'int',
	    J: 'long',
	    S: 'short',
	    V: 'void',
	    Z: 'boolean'
	};
	exports.external2internal = {};
	for (var k in exports.internal2external) {
	    exports.external2internal[exports.internal2external[k]] = k;
	}
	function getTypes(methodDescriptor) {
	    var i = 0, types = [], endIdx;
	    for (i = 0; i < methodDescriptor.length; i++) {
	        switch (methodDescriptor.charAt(i)) {
	        case '(':
	        case ')':
	            break;
	        case 'L':
	            endIdx = methodDescriptor.indexOf(';', i);
	            types.push(methodDescriptor.slice(i, endIdx + 1));
	            i = endIdx;
	            break;
	        case '[':
	            endIdx = i + 1;
	            while (methodDescriptor.charAt(endIdx) === '[') {
	                endIdx++;
	            }
	            if (methodDescriptor.charAt(endIdx) === 'L') {
	                endIdx = methodDescriptor.indexOf(';', endIdx);
	                types.push(methodDescriptor.slice(i, endIdx + 1));
	            } else {
	                types.push(methodDescriptor.slice(i, endIdx + 1));
	            }
	            i = endIdx;
	            break;
	        default:
	            types.push(methodDescriptor.charAt(i));
	            break;
	        }
	    }
	    return types;
	}
	exports.getTypes = getTypes;
	function get_component_type(type_str) {
	    return type_str.slice(1);
	}
	exports.get_component_type = get_component_type;
	function is_array_type(type_str) {
	    return type_str[0] === '[';
	}
	exports.is_array_type = is_array_type;
	function is_primitive_type(type_str) {
	    return type_str in exports.internal2external;
	}
	exports.is_primitive_type = is_primitive_type;
	function is_reference_type(type_str) {
	    return type_str[0] === 'L';
	}
	exports.is_reference_type = is_reference_type;
	function descriptor2typestr(type_str) {
	    var c = type_str[0];
	    if (c in exports.internal2external)
	        return exports.internal2external[c];
	    if (c === 'L')
	        return type_str.slice(1, -1);
	    if (c === '[')
	        return type_str;
	    throw new Error('Unrecognized type string: ' + type_str);
	}
	exports.descriptor2typestr = descriptor2typestr;
	function carr2descriptor(carr) {
	    var c = carr.shift();
	    if (c == null)
	        return null;
	    if (exports.internal2external[c] !== void 0)
	        return c;
	    if (c === 'L') {
	        var rv = 'L';
	        while ((c = carr.shift()) !== ';') {
	            rv += c;
	        }
	        return rv + ';';
	    }
	    if (c === '[')
	        return '[' + carr2descriptor(carr);
	    carr.unshift(c);
	    throw new Error('Unrecognized descriptor: ' + carr.join(''));
	}
	exports.carr2descriptor = carr2descriptor;
	function typestr2descriptor(type_str) {
	    if (exports.external2internal[type_str] !== void 0) {
	        return exports.external2internal[type_str];
	    } else if (type_str[0] === '[') {
	        return type_str;
	    } else {
	        return 'L' + type_str + ';';
	    }
	}
	exports.typestr2descriptor = typestr2descriptor;
	function unboxArguments(thread, paramTypes, args) {
	    var rv = [], i, type, arg;
	    for (i = 0; i < paramTypes.length; i++) {
	        type = paramTypes[i];
	        arg = args[i];
	        if (is_primitive_type(type)) {
	            rv.push(arg.unbox());
	            if (type === 'J' || type === 'D') {
	                rv.push(null);
	            }
	        } else {
	            rv.push(arg);
	        }
	    }
	    return rv;
	}
	exports.unboxArguments = unboxArguments;
	function createMethodType(thread, cl, descriptor, cb) {
	    cl.initializeClass(thread, 'Ljava/lang/invoke/MethodHandleNatives;', function (cdata) {
	        if (cdata !== null) {
	            var jsCons = cdata.getConstructor(thread), classes = getTypes(descriptor);
	            classes.push('[Ljava/lang/Class;');
	            cl.resolveClasses(thread, classes, function (classMap) {
	                var types = classes.map(function (cls) {
	                    return classMap[cls].getClassObject(thread);
	                });
	                types.pop();
	                var rtype = types.pop(), clsArrCons = classMap['[Ljava/lang/Class;'].getConstructor(thread), ptypes = new clsArrCons(thread, types.length);
	                ptypes.array = types;
	                jsCons['java/lang/invoke/MethodHandleNatives/findMethodHandleType(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;'](thread, [
	                    rtype,
	                    ptypes
	                ], cb);
	            });
	        }
	    });
	}
	exports.createMethodType = createMethodType;
	function getMethodDescriptorWordSize(descriptor) {
	    var parsedDescriptor = getTypes(descriptor), words = parsedDescriptor.length - 1, i, p;
	    parsedDescriptor.pop();
	    for (i = 0; i < parsedDescriptor.length; i++) {
	        p = parsedDescriptor[i];
	        if (p === 'D' || p === 'J') {
	            words++;
	        }
	    }
	    return words;
	}
	exports.getMethodDescriptorWordSize = getMethodDescriptorWordSize;
	function getDescriptorString(rtype, ptypes) {
	    var rv = '(';
	    if (ptypes !== undefined && ptypes !== null) {
	        ptypes.array.forEach(function (ptype) {
	            rv += ptype.$cls.getInternalName();
	        });
	    }
	    rv += ')' + rtype.$cls.getInternalName();
	    return rv;
	}
	exports.getDescriptorString = getDescriptorString;
	function getLoader(thread, jclo) {
	    if (jclo != null && jclo.$loader != null) {
	        return jclo.$loader;
	    }
	    return thread.getBsCl();
	}
	exports.getLoader = getLoader;
	function arraycopyNoCheck(src, srcPos, dest, destPos, length) {
	    var j = destPos;
	    var end = srcPos + length;
	    for (var i = srcPos; i < end; i++) {
	        dest.array[j++] = src.array[i];
	    }
	}
	exports.arraycopyNoCheck = arraycopyNoCheck;
	function arraycopyCheck(thread, src, srcPos, dest, destPos, length) {
	    var j = destPos;
	    var end = srcPos + length;
	    var destCompCls = dest.getClass().getComponentClass();
	    for (var i = srcPos; i < end; i++) {
	        if (src.array[i] === null || src.array[i].getClass().isCastable(destCompCls)) {
	            dest.array[j] = src.array[i];
	        } else {
	            thread.throwNewException('Ljava/lang/ArrayStoreException;', 'Array element in src cannot be cast to dest array type.');
	            return;
	        }
	        j++;
	    }
	}
	exports.arraycopyCheck = arraycopyCheck;
	function initString(cl, str) {
	    var carr = initCarr(cl, str);
	    var strCons = cl.getResolvedClass('Ljava/lang/String;').getConstructor(null);
	    var strObj = new strCons(null);
	    strObj['java/lang/String/value'] = carr;
	    return strObj;
	}
	exports.initString = initString;
	function initCarr(cl, str) {
	    var arrClsCons = cl.getInitializedClass(null, '[C').getConstructor(null), carr = new arrClsCons(null, str.length), carrArray = carr.array;
	    for (var i = 0; i < str.length; i++) {
	        carrArray[i] = str.charCodeAt(i);
	    }
	    return carr;
	}
	exports.initCarr = initCarr;
	function newArrayFromClass(thread, clazz, length) {
	    return new (clazz.getConstructor(thread))(thread, length);
	}
	exports.newArrayFromClass = newArrayFromClass;
	function newArray(thread, cl, desc, length) {
	    var cls = cl.getInitializedClass(thread, desc);
	    return newArrayFromClass(thread, cls, length);
	}
	exports.newArray = newArray;
	function multiNewArray(thread, cl, desc, lengths) {
	    var cls = cl.getInitializedClass(thread, desc);
	    return new (cls.getConstructor(thread))(thread, lengths);
	}
	exports.multiNewArray = multiNewArray;
	function newObjectFromClass(thread, clazz) {
	    return new (clazz.getConstructor(thread))(thread);
	}
	exports.newObjectFromClass = newObjectFromClass;
	function newObject(thread, cl, desc) {
	    var cls = cl.getInitializedClass(thread, desc);
	    return newObjectFromClass(thread, cls);
	}
	exports.newObject = newObject;
	function getStaticFields(thread, cl, desc) {
	    return cl.getInitializedClass(thread, desc).getConstructor(thread);
	}
	exports.getStaticFields = getStaticFields;
	function newArrayFromDataWithClass(thread, cls, data) {
	    var arr = newArrayFromClass(thread, cls, 0);
	    arr.array = data;
	    return arr;
	}
	exports.newArrayFromDataWithClass = newArrayFromDataWithClass;
	function newArrayFromData(thread, cl, desc, data) {
	    var arr = newArray(thread, cl, desc, 0);
	    arr.array = data;
	    return arr;
	}
	exports.newArrayFromData = newArrayFromData;
	function boxClassName(primType) {
	    switch (primType) {
	    case 'B':
	        return 'Ljava/lang/Byte;';
	    case 'C':
	        return 'Ljava/lang/Character;';
	    case 'D':
	        return 'Ljava/lang/Double;';
	    case 'F':
	        return 'Ljava/lang/Float;';
	    case 'I':
	        return 'Ljava/lang/Integer;';
	    case 'J':
	        return 'Ljava/lang/Long;';
	    case 'S':
	        return 'Ljava/lang/Short;';
	    case 'Z':
	        return 'Ljava/lang/Boolean;';
	    case 'V':
	        return 'Ljava/lang/Void;';
	    default:
	        throw new Error('Tried to box a non-primitive class: ' + this.className);
	    }
	}
	exports.boxClassName = boxClassName;
	function boxPrimitiveValue(thread, type, val) {
	    var primCls = thread.getBsCl().getInitializedClass(thread, boxClassName(type)), primClsCons = primCls.getConstructor(thread);
	    return primClsCons.box(val);
	}
	exports.boxPrimitiveValue = boxPrimitiveValue;
	function boxArguments(thread, objArrCls, descriptor, data, isStatic, skipArgs) {
	    if (skipArgs === void 0) {
	        skipArgs = 0;
	    }
	    var paramTypes = getTypes(descriptor), boxedArgs = newArrayFromClass(thread, objArrCls, paramTypes.length - (isStatic ? 1 : 2) - skipArgs), i, j = 0, boxedArgsArr = boxedArgs.array, type;
	    paramTypes.pop();
	    if (!isStatic) {
	        paramTypes.shift();
	    }
	    if (skipArgs > 0) {
	        paramTypes = paramTypes.slice(skipArgs);
	        data = data.slice(skipArgs);
	    }
	    for (i = 0; i < paramTypes.length; i++) {
	        type = paramTypes[i];
	        switch (type[0]) {
	        case '[':
	        case 'L':
	            boxedArgsArr[i] = data[j];
	            break;
	        case 'J':
	        case 'D':
	            boxedArgsArr[i] = boxPrimitiveValue(thread, type, data[j]);
	            j++;
	            break;
	        default:
	            boxedArgsArr[i] = boxPrimitiveValue(thread, type, data[j]);
	            break;
	        }
	        j++;
	    }
	    return boxedArgs;
	}
	exports.boxArguments = boxArguments;
	function forwardResult(thread) {
	    return function (e, rv) {
	        if (e) {
	            thread.throwException(e);
	        } else {
	            thread.asyncReturn(rv);
	        }
	    };
	}
	exports.forwardResult = forwardResult;
	
	/* WEBPACK VAR INJECTION */}.call(exports, __webpack_require__(3), __webpack_require__(7)))

/***/ },
/* 7 */
/***/ function(module, exports, __webpack_require__) {

	var BrowserFS = __webpack_require__(4);module.exports=BrowserFS.BFSRequire('buffer').Buffer;

/***/ },
/* 8 */
/***/ function(module, exports) {

	'use strict';
	var gLong = function () {
	    function gLong(low, high) {
	        this.low_ = low | 0;
	        this.high_ = high | 0;
	    }
	    gLong.fromInt = function (value) {
	        if (-128 <= value && value < 128) {
	            var cachedObj = gLong.IntCache_[value];
	            if (cachedObj) {
	                return cachedObj;
	            }
	        }
	        var obj = new gLong(value, value < 0 ? -1 : 0);
	        if (-128 <= value && value < 128) {
	            gLong.IntCache_[value] = obj;
	        }
	        return obj;
	    };
	    gLong.fromNumber = function (value) {
	        if (isNaN(value) || !isFinite(value)) {
	            return gLong.ZERO;
	        } else if (value <= -gLong.TWO_PWR_63_DBL_) {
	            return gLong.MIN_VALUE;
	        } else if (value + 1 >= gLong.TWO_PWR_63_DBL_) {
	            return gLong.MAX_VALUE;
	        } else if (value < 0) {
	            return gLong.fromNumber(-value).negate();
	        } else {
	            return new gLong(value % gLong.TWO_PWR_32_DBL_ | 0, value / gLong.TWO_PWR_32_DBL_ | 0);
	        }
	    };
	    gLong.fromBits = function (lowBits, highBits) {
	        return new gLong(lowBits, highBits);
	    };
	    gLong.fromString = function (str, opt_radix) {
	        if (str.length == 0) {
	            throw Error('number format error: empty string');
	        }
	        var radix = opt_radix || 10;
	        if (radix < 2 || 36 < radix) {
	            throw Error('radix out of range: ' + radix);
	        }
	        if (str.charAt(0) == '-') {
	            return gLong.fromString(str.substring(1), radix).negate();
	        } else if (str.indexOf('-') >= 0) {
	            throw Error('number format error: interior "-" character: ' + str);
	        }
	        var radixToPower = gLong.fromNumber(Math.pow(radix, 8));
	        var result = gLong.ZERO;
	        for (var i = 0; i < str.length; i += 8) {
	            var size = Math.min(8, str.length - i);
	            var value = parseInt(str.substring(i, i + size), radix);
	            if (size < 8) {
	                var power = gLong.fromNumber(Math.pow(radix, size));
	                result = result.multiply(power).add(gLong.fromNumber(value));
	            } else {
	                result = result.multiply(radixToPower);
	                result = result.add(gLong.fromNumber(value));
	            }
	        }
	        return result;
	    };
	    gLong.prototype.toInt = function () {
	        return this.low_;
	    };
	    gLong.prototype.toNumber = function () {
	        return this.high_ * gLong.TWO_PWR_32_DBL_ + this.getLowBitsUnsigned();
	    };
	    gLong.prototype.toString = function (opt_radix) {
	        var radix = opt_radix || 10;
	        if (radix < 2 || 36 < radix) {
	            throw Error('radix out of range: ' + radix);
	        }
	        if (this.isZero()) {
	            return '0';
	        }
	        if (this.isNegative()) {
	            if (this.equals(gLong.MIN_VALUE)) {
	                var radixLong = gLong.fromNumber(radix);
	                var div = this.div(radixLong);
	                var rem = div.multiply(radixLong).subtract(this);
	                return div.toString(radix) + rem.toInt().toString(radix);
	            } else {
	                return '-' + this.negate().toString(radix);
	            }
	        }
	        var radixToPower = gLong.fromNumber(Math.pow(radix, 6));
	        var rem = this;
	        var result = '';
	        while (true) {
	            var remDiv = rem.div(radixToPower);
	            var intval = rem.subtract(remDiv.multiply(radixToPower)).toInt();
	            var digits = intval.toString(radix);
	            rem = remDiv;
	            if (rem.isZero()) {
	                return digits + result;
	            } else {
	                while (digits.length < 6) {
	                    digits = '0' + digits;
	                }
	                result = '' + digits + result;
	            }
	        }
	    };
	    gLong.prototype.getHighBits = function () {
	        return this.high_;
	    };
	    gLong.prototype.getLowBits = function () {
	        return this.low_;
	    };
	    gLong.prototype.getLowBitsUnsigned = function () {
	        return this.low_ >= 0 ? this.low_ : gLong.TWO_PWR_32_DBL_ + this.low_;
	    };
	    gLong.prototype.getNumBitsAbs = function () {
	        if (this.isNegative()) {
	            if (this.equals(gLong.MIN_VALUE)) {
	                return 64;
	            } else {
	                return this.negate().getNumBitsAbs();
	            }
	        } else {
	            var val = this.high_ != 0 ? this.high_ : this.low_;
	            for (var bit = 31; bit > 0; bit--) {
	                if ((val & 1 << bit) != 0) {
	                    break;
	                }
	            }
	            return this.high_ != 0 ? bit + 33 : bit + 1;
	        }
	    };
	    gLong.prototype.isZero = function () {
	        return this.high_ == 0 && this.low_ == 0;
	    };
	    gLong.prototype.isNegative = function () {
	        return this.high_ < 0;
	    };
	    gLong.prototype.isOdd = function () {
	        return (this.low_ & 1) == 1;
	    };
	    gLong.prototype.equals = function (other) {
	        return this.high_ == other.high_ && this.low_ == other.low_;
	    };
	    gLong.prototype.notEquals = function (other) {
	        return this.high_ != other.high_ || this.low_ != other.low_;
	    };
	    gLong.prototype.lessThan = function (other) {
	        return this.compare(other) < 0;
	    };
	    gLong.prototype.lessThanOrEqual = function (other) {
	        return this.compare(other) <= 0;
	    };
	    gLong.prototype.greaterThan = function (other) {
	        return this.compare(other) > 0;
	    };
	    gLong.prototype.greaterThanOrEqual = function (other) {
	        return this.compare(other) >= 0;
	    };
	    gLong.prototype.compare = function (other) {
	        if (this.equals(other)) {
	            return 0;
	        }
	        var thisNeg = this.isNegative();
	        var otherNeg = other.isNegative();
	        if (thisNeg && !otherNeg) {
	            return -1;
	        }
	        if (!thisNeg && otherNeg) {
	            return 1;
	        }
	        if (this.subtract(other).isNegative()) {
	            return -1;
	        } else {
	            return 1;
	        }
	    };
	    gLong.prototype.negate = function () {
	        if (this.equals(gLong.MIN_VALUE)) {
	            return gLong.MIN_VALUE;
	        } else {
	            return this.not().add(gLong.ONE);
	        }
	    };
	    gLong.prototype.add = function (other) {
	        var a48 = this.high_ >>> 16;
	        var a32 = this.high_ & 65535;
	        var a16 = this.low_ >>> 16;
	        var a00 = this.low_ & 65535;
	        var b48 = other.high_ >>> 16;
	        var b32 = other.high_ & 65535;
	        var b16 = other.low_ >>> 16;
	        var b00 = other.low_ & 65535;
	        var c48 = 0, c32 = 0, c16 = 0, c00 = 0;
	        c00 += a00 + b00;
	        c16 += c00 >>> 16;
	        c00 &= 65535;
	        c16 += a16 + b16;
	        c32 += c16 >>> 16;
	        c16 &= 65535;
	        c32 += a32 + b32;
	        c48 += c32 >>> 16;
	        c32 &= 65535;
	        c48 += a48 + b48;
	        c48 &= 65535;
	        return gLong.fromBits(c16 << 16 | c00, c48 << 16 | c32);
	    };
	    gLong.prototype.subtract = function (other) {
	        return this.add(other.negate());
	    };
	    gLong.prototype.multiply = function (other) {
	        if (this.isZero()) {
	            return gLong.ZERO;
	        } else if (other.isZero()) {
	            return gLong.ZERO;
	        }
	        if (this.equals(gLong.MIN_VALUE)) {
	            return other.isOdd() ? gLong.MIN_VALUE : gLong.ZERO;
	        } else if (other.equals(gLong.MIN_VALUE)) {
	            return this.isOdd() ? gLong.MIN_VALUE : gLong.ZERO;
	        }
	        if (this.isNegative()) {
	            if (other.isNegative()) {
	                return this.negate().multiply(other.negate());
	            } else {
	                return this.negate().multiply(other).negate();
	            }
	        } else if (other.isNegative()) {
	            return this.multiply(other.negate()).negate();
	        }
	        if (this.lessThan(gLong.TWO_PWR_24_) && other.lessThan(gLong.TWO_PWR_24_)) {
	            return gLong.fromNumber(this.toNumber() * other.toNumber());
	        }
	        var a48 = this.high_ >>> 16;
	        var a32 = this.high_ & 65535;
	        var a16 = this.low_ >>> 16;
	        var a00 = this.low_ & 65535;
	        var b48 = other.high_ >>> 16;
	        var b32 = other.high_ & 65535;
	        var b16 = other.low_ >>> 16;
	        var b00 = other.low_ & 65535;
	        var c48 = 0, c32 = 0, c16 = 0, c00 = 0;
	        c00 += a00 * b00;
	        c16 += c00 >>> 16;
	        c00 &= 65535;
	        c16 += a16 * b00;
	        c32 += c16 >>> 16;
	        c16 &= 65535;
	        c16 += a00 * b16;
	        c32 += c16 >>> 16;
	        c16 &= 65535;
	        c32 += a32 * b00;
	        c48 += c32 >>> 16;
	        c32 &= 65535;
	        c32 += a16 * b16;
	        c48 += c32 >>> 16;
	        c32 &= 65535;
	        c32 += a00 * b32;
	        c48 += c32 >>> 16;
	        c32 &= 65535;
	        c48 += a48 * b00 + a32 * b16 + a16 * b32 + a00 * b48;
	        c48 &= 65535;
	        return gLong.fromBits(c16 << 16 | c00, c48 << 16 | c32);
	    };
	    gLong.prototype.div = function (other) {
	        if (other.isZero()) {
	            throw Error('division by zero');
	        } else if (this.isZero()) {
	            return gLong.ZERO;
	        }
	        if (this.equals(gLong.MIN_VALUE)) {
	            if (other.equals(gLong.ONE) || other.equals(gLong.NEG_ONE)) {
	                return gLong.MIN_VALUE;
	            } else if (other.equals(gLong.MIN_VALUE)) {
	                return gLong.ONE;
	            } else {
	                var halfThis = this.shiftRight(1);
	                var l_approx = halfThis.div(other).shiftLeft(1);
	                if (l_approx.equals(gLong.ZERO)) {
	                    return other.isNegative() ? gLong.ONE : gLong.NEG_ONE;
	                } else {
	                    var rem = this.subtract(other.multiply(l_approx));
	                    var result = l_approx.add(rem.div(other));
	                    return result;
	                }
	            }
	        } else if (other.equals(gLong.MIN_VALUE)) {
	            return gLong.ZERO;
	        }
	        if (this.isNegative()) {
	            if (other.isNegative()) {
	                return this.negate().div(other.negate());
	            } else {
	                return this.negate().div(other).negate();
	            }
	        } else if (other.isNegative()) {
	            return this.div(other.negate()).negate();
	        }
	        var res = gLong.ZERO;
	        var rem = this;
	        while (rem.greaterThanOrEqual(other)) {
	            var approx = Math.max(1, Math.floor(rem.toNumber() / other.toNumber()));
	            var log2 = Math.ceil(Math.log(approx) / Math.LN2);
	            var delta = 1;
	            if (log2 > 48)
	                delta = Math.pow(2, log2 - 48);
	            var approxRes = gLong.fromNumber(approx);
	            var approxRem = approxRes.multiply(other);
	            while (approxRem.isNegative() || approxRem.greaterThan(rem)) {
	                approx -= delta;
	                approxRes = gLong.fromNumber(approx);
	                approxRem = approxRes.multiply(other);
	            }
	            if (approxRes.isZero()) {
	                approxRes = gLong.ONE;
	            }
	            res = res.add(approxRes);
	            rem = rem.subtract(approxRem);
	        }
	        return res;
	    };
	    gLong.prototype.modulo = function (other) {
	        return this.subtract(this.div(other).multiply(other));
	    };
	    gLong.prototype.not = function () {
	        return gLong.fromBits(~this.low_, ~this.high_);
	    };
	    gLong.prototype.and = function (other) {
	        return gLong.fromBits(this.low_ & other.low_, this.high_ & other.high_);
	    };
	    gLong.prototype.or = function (other) {
	        return gLong.fromBits(this.low_ | other.low_, this.high_ | other.high_);
	    };
	    gLong.prototype.xor = function (other) {
	        return gLong.fromBits(this.low_ ^ other.low_, this.high_ ^ other.high_);
	    };
	    gLong.prototype.shiftLeft = function (numBits) {
	        numBits &= 63;
	        if (numBits == 0) {
	            return this;
	        } else {
	            var low = this.low_;
	            if (numBits < 32) {
	                var high = this.high_;
	                return gLong.fromBits(low << numBits, high << numBits | low >>> 32 - numBits);
	            } else {
	                return gLong.fromBits(0, low << numBits - 32);
	            }
	        }
	    };
	    gLong.prototype.shiftRight = function (numBits) {
	        numBits &= 63;
	        if (numBits == 0) {
	            return this;
	        } else {
	            var high = this.high_;
	            if (numBits < 32) {
	                var low = this.low_;
	                return gLong.fromBits(low >>> numBits | high << 32 - numBits, high >> numBits);
	            } else {
	                return gLong.fromBits(high >> numBits - 32, high >= 0 ? 0 : -1);
	            }
	        }
	    };
	    gLong.prototype.shiftRightUnsigned = function (numBits) {
	        numBits &= 63;
	        if (numBits == 0) {
	            return this;
	        } else {
	            var high = this.high_;
	            if (numBits < 32) {
	                var low = this.low_;
	                return gLong.fromBits(low >>> numBits | high << 32 - numBits, high >>> numBits);
	            } else if (numBits == 32) {
	                return gLong.fromBits(high, 0);
	            } else {
	                return gLong.fromBits(high >>> numBits - 32, 0);
	            }
	        }
	    };
	    gLong.IntCache_ = {};
	    gLong.TWO_PWR_16_DBL_ = 1 << 16;
	    gLong.TWO_PWR_24_DBL_ = 1 << 24;
	    gLong.TWO_PWR_32_DBL_ = gLong.TWO_PWR_16_DBL_ * gLong.TWO_PWR_16_DBL_;
	    gLong.TWO_PWR_31_DBL_ = gLong.TWO_PWR_32_DBL_ / 2;
	    gLong.TWO_PWR_48_DBL_ = gLong.TWO_PWR_32_DBL_ * gLong.TWO_PWR_16_DBL_;
	    gLong.TWO_PWR_64_DBL_ = gLong.TWO_PWR_32_DBL_ * gLong.TWO_PWR_32_DBL_;
	    gLong.TWO_PWR_63_DBL_ = gLong.TWO_PWR_64_DBL_ / 2;
	    gLong.ZERO = gLong.fromInt(0);
	    gLong.ONE = gLong.fromInt(1);
	    gLong.NEG_ONE = gLong.fromInt(-1);
	    gLong.MAX_VALUE = gLong.fromBits(4294967295, 2147483647);
	    gLong.MIN_VALUE = gLong.fromBits(0, 2147483648);
	    gLong.TWO_PWR_24_ = gLong.fromInt(gLong.TWO_PWR_24_DBL_);
	    return gLong;
	}();
	module.exports = gLong;


/***/ },
/* 9 */
/***/ function(module, exports) {

	'use strict';
	(function (ClassState) {
	    ClassState[ClassState['NOT_LOADED'] = 0] = 'NOT_LOADED';
	    ClassState[ClassState['LOADED'] = 1] = 'LOADED';
	    ClassState[ClassState['RESOLVED'] = 2] = 'RESOLVED';
	    ClassState[ClassState['INITIALIZED'] = 3] = 'INITIALIZED';
	}(exports.ClassState || (exports.ClassState = {})));
	var ClassState = exports.ClassState;
	(function (ThreadStatus) {
	    ThreadStatus[ThreadStatus['NEW'] = 0] = 'NEW';
	    ThreadStatus[ThreadStatus['RUNNABLE'] = 1] = 'RUNNABLE';
	    ThreadStatus[ThreadStatus['BLOCKED'] = 2] = 'BLOCKED';
	    ThreadStatus[ThreadStatus['UNINTERRUPTABLY_BLOCKED'] = 3] = 'UNINTERRUPTABLY_BLOCKED';
	    ThreadStatus[ThreadStatus['WAITING'] = 4] = 'WAITING';
	    ThreadStatus[ThreadStatus['TIMED_WAITING'] = 5] = 'TIMED_WAITING';
	    ThreadStatus[ThreadStatus['ASYNC_WAITING'] = 6] = 'ASYNC_WAITING';
	    ThreadStatus[ThreadStatus['PARKED'] = 7] = 'PARKED';
	    ThreadStatus[ThreadStatus['TERMINATED'] = 8] = 'TERMINATED';
	}(exports.ThreadStatus || (exports.ThreadStatus = {})));
	var ThreadStatus = exports.ThreadStatus;
	(function (JVMTIThreadState) {
	    JVMTIThreadState[JVMTIThreadState['ALIVE'] = 1] = 'ALIVE';
	    JVMTIThreadState[JVMTIThreadState['TERMINATED'] = 2] = 'TERMINATED';
	    JVMTIThreadState[JVMTIThreadState['RUNNABLE'] = 4] = 'RUNNABLE';
	    JVMTIThreadState[JVMTIThreadState['BLOCKED_ON_MONITOR_ENTER'] = 1024] = 'BLOCKED_ON_MONITOR_ENTER';
	    JVMTIThreadState[JVMTIThreadState['WAITING_INDEFINITELY'] = 16] = 'WAITING_INDEFINITELY';
	    JVMTIThreadState[JVMTIThreadState['WAITING_WITH_TIMEOUT'] = 32] = 'WAITING_WITH_TIMEOUT';
	}(exports.JVMTIThreadState || (exports.JVMTIThreadState = {})));
	var JVMTIThreadState = exports.JVMTIThreadState;
	(function (TriState) {
	    TriState[TriState['TRUE'] = 0] = 'TRUE';
	    TriState[TriState['FALSE'] = 1] = 'FALSE';
	    TriState[TriState['INDETERMINATE'] = 2] = 'INDETERMINATE';
	}(exports.TriState || (exports.TriState = {})));
	var TriState = exports.TriState;
	(function (JVMStatus) {
	    JVMStatus[JVMStatus['BOOTING'] = 0] = 'BOOTING';
	    JVMStatus[JVMStatus['BOOTED'] = 1] = 'BOOTED';
	    JVMStatus[JVMStatus['RUNNING'] = 2] = 'RUNNING';
	    JVMStatus[JVMStatus['TERMINATING'] = 3] = 'TERMINATING';
	    JVMStatus[JVMStatus['TERMINATED'] = 4] = 'TERMINATED';
	}(exports.JVMStatus || (exports.JVMStatus = {})));
	var JVMStatus = exports.JVMStatus;
	(function (StackFrameType) {
	    StackFrameType[StackFrameType['INTERNAL'] = 0] = 'INTERNAL';
	    StackFrameType[StackFrameType['BYTECODE'] = 1] = 'BYTECODE';
	    StackFrameType[StackFrameType['NATIVE'] = 2] = 'NATIVE';
	}(exports.StackFrameType || (exports.StackFrameType = {})));
	var StackFrameType = exports.StackFrameType;
	(function (Constants) {
	    Constants[Constants['INT_MAX'] = Math.pow(2, 31) - 1] = 'INT_MAX';
	    Constants[Constants['INT_MIN'] = -Constants.INT_MAX - 1] = 'INT_MIN';
	    Constants[Constants['FLOAT_POS_INFINITY'] = Math.pow(2, 128)] = 'FLOAT_POS_INFINITY';
	    Constants[Constants['FLOAT_NEG_INFINITY'] = -1 * Constants.FLOAT_POS_INFINITY] = 'FLOAT_NEG_INFINITY';
	    Constants[Constants['FLOAT_POS_INFINITY_AS_INT'] = 2139095040] = 'FLOAT_POS_INFINITY_AS_INT';
	    Constants[Constants['FLOAT_NEG_INFINITY_AS_INT'] = -8388608] = 'FLOAT_NEG_INFINITY_AS_INT';
	    Constants[Constants['FLOAT_NaN_AS_INT'] = 2143289344] = 'FLOAT_NaN_AS_INT';
	}(exports.Constants || (exports.Constants = {})));
	var Constants = exports.Constants;
	(function (ConstantPoolItemType) {
	    ConstantPoolItemType[ConstantPoolItemType['CLASS'] = 7] = 'CLASS';
	    ConstantPoolItemType[ConstantPoolItemType['FIELDREF'] = 9] = 'FIELDREF';
	    ConstantPoolItemType[ConstantPoolItemType['METHODREF'] = 10] = 'METHODREF';
	    ConstantPoolItemType[ConstantPoolItemType['INTERFACE_METHODREF'] = 11] = 'INTERFACE_METHODREF';
	    ConstantPoolItemType[ConstantPoolItemType['STRING'] = 8] = 'STRING';
	    ConstantPoolItemType[ConstantPoolItemType['INTEGER'] = 3] = 'INTEGER';
	    ConstantPoolItemType[ConstantPoolItemType['FLOAT'] = 4] = 'FLOAT';
	    ConstantPoolItemType[ConstantPoolItemType['LONG'] = 5] = 'LONG';
	    ConstantPoolItemType[ConstantPoolItemType['DOUBLE'] = 6] = 'DOUBLE';
	    ConstantPoolItemType[ConstantPoolItemType['NAME_AND_TYPE'] = 12] = 'NAME_AND_TYPE';
	    ConstantPoolItemType[ConstantPoolItemType['UTF8'] = 1] = 'UTF8';
	    ConstantPoolItemType[ConstantPoolItemType['METHOD_HANDLE'] = 15] = 'METHOD_HANDLE';
	    ConstantPoolItemType[ConstantPoolItemType['METHOD_TYPE'] = 16] = 'METHOD_TYPE';
	    ConstantPoolItemType[ConstantPoolItemType['INVOKE_DYNAMIC'] = 18] = 'INVOKE_DYNAMIC';
	}(exports.ConstantPoolItemType || (exports.ConstantPoolItemType = {})));
	var ConstantPoolItemType = exports.ConstantPoolItemType;
	(function (StackMapTableEntryType) {
	    StackMapTableEntryType[StackMapTableEntryType['SAME_FRAME'] = 0] = 'SAME_FRAME';
	    StackMapTableEntryType[StackMapTableEntryType['SAME_LOCALS_1_STACK_ITEM_FRAME'] = 1] = 'SAME_LOCALS_1_STACK_ITEM_FRAME';
	    StackMapTableEntryType[StackMapTableEntryType['SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED'] = 2] = 'SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED';
	    StackMapTableEntryType[StackMapTableEntryType['CHOP_FRAME'] = 3] = 'CHOP_FRAME';
	    StackMapTableEntryType[StackMapTableEntryType['SAME_FRAME_EXTENDED'] = 4] = 'SAME_FRAME_EXTENDED';
	    StackMapTableEntryType[StackMapTableEntryType['APPEND_FRAME'] = 5] = 'APPEND_FRAME';
	    StackMapTableEntryType[StackMapTableEntryType['FULL_FRAME'] = 6] = 'FULL_FRAME';
	}(exports.StackMapTableEntryType || (exports.StackMapTableEntryType = {})));
	var StackMapTableEntryType = exports.StackMapTableEntryType;
	(function (MethodHandleReferenceKind) {
	    MethodHandleReferenceKind[MethodHandleReferenceKind['GETFIELD'] = 1] = 'GETFIELD';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['GETSTATIC'] = 2] = 'GETSTATIC';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['PUTFIELD'] = 3] = 'PUTFIELD';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['PUTSTATIC'] = 4] = 'PUTSTATIC';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['INVOKEVIRTUAL'] = 5] = 'INVOKEVIRTUAL';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['INVOKESTATIC'] = 6] = 'INVOKESTATIC';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['INVOKESPECIAL'] = 7] = 'INVOKESPECIAL';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['NEWINVOKESPECIAL'] = 8] = 'NEWINVOKESPECIAL';
	    MethodHandleReferenceKind[MethodHandleReferenceKind['INVOKEINTERFACE'] = 9] = 'INVOKEINTERFACE';
	}(exports.MethodHandleReferenceKind || (exports.MethodHandleReferenceKind = {})));
	var MethodHandleReferenceKind = exports.MethodHandleReferenceKind;
	(function (OpCode) {
	    OpCode[OpCode['AALOAD'] = 50] = 'AALOAD';
	    OpCode[OpCode['AASTORE'] = 83] = 'AASTORE';
	    OpCode[OpCode['ACONST_NULL'] = 1] = 'ACONST_NULL';
	    OpCode[OpCode['ALOAD'] = 25] = 'ALOAD';
	    OpCode[OpCode['ALOAD_0'] = 42] = 'ALOAD_0';
	    OpCode[OpCode['ALOAD_1'] = 43] = 'ALOAD_1';
	    OpCode[OpCode['ALOAD_2'] = 44] = 'ALOAD_2';
	    OpCode[OpCode['ALOAD_3'] = 45] = 'ALOAD_3';
	    OpCode[OpCode['ANEWARRAY'] = 189] = 'ANEWARRAY';
	    OpCode[OpCode['ARETURN'] = 176] = 'ARETURN';
	    OpCode[OpCode['ARRAYLENGTH'] = 190] = 'ARRAYLENGTH';
	    OpCode[OpCode['ASTORE'] = 58] = 'ASTORE';
	    OpCode[OpCode['ASTORE_0'] = 75] = 'ASTORE_0';
	    OpCode[OpCode['ASTORE_1'] = 76] = 'ASTORE_1';
	    OpCode[OpCode['ASTORE_2'] = 77] = 'ASTORE_2';
	    OpCode[OpCode['ASTORE_3'] = 78] = 'ASTORE_3';
	    OpCode[OpCode['ATHROW'] = 191] = 'ATHROW';
	    OpCode[OpCode['BALOAD'] = 51] = 'BALOAD';
	    OpCode[OpCode['BASTORE'] = 84] = 'BASTORE';
	    OpCode[OpCode['BIPUSH'] = 16] = 'BIPUSH';
	    OpCode[OpCode['BREAKPOINT'] = 202] = 'BREAKPOINT';
	    OpCode[OpCode['CALOAD'] = 52] = 'CALOAD';
	    OpCode[OpCode['CASTORE'] = 85] = 'CASTORE';
	    OpCode[OpCode['CHECKCAST'] = 192] = 'CHECKCAST';
	    OpCode[OpCode['D2F'] = 144] = 'D2F';
	    OpCode[OpCode['D2I'] = 142] = 'D2I';
	    OpCode[OpCode['D2L'] = 143] = 'D2L';
	    OpCode[OpCode['DADD'] = 99] = 'DADD';
	    OpCode[OpCode['DALOAD'] = 49] = 'DALOAD';
	    OpCode[OpCode['DASTORE'] = 82] = 'DASTORE';
	    OpCode[OpCode['DCMPG'] = 152] = 'DCMPG';
	    OpCode[OpCode['DCMPL'] = 151] = 'DCMPL';
	    OpCode[OpCode['DCONST_0'] = 14] = 'DCONST_0';
	    OpCode[OpCode['DCONST_1'] = 15] = 'DCONST_1';
	    OpCode[OpCode['DDIV'] = 111] = 'DDIV';
	    OpCode[OpCode['DLOAD'] = 24] = 'DLOAD';
	    OpCode[OpCode['DLOAD_0'] = 38] = 'DLOAD_0';
	    OpCode[OpCode['DLOAD_1'] = 39] = 'DLOAD_1';
	    OpCode[OpCode['DLOAD_2'] = 40] = 'DLOAD_2';
	    OpCode[OpCode['DLOAD_3'] = 41] = 'DLOAD_3';
	    OpCode[OpCode['DMUL'] = 107] = 'DMUL';
	    OpCode[OpCode['DNEG'] = 119] = 'DNEG';
	    OpCode[OpCode['DREM'] = 115] = 'DREM';
	    OpCode[OpCode['DRETURN'] = 175] = 'DRETURN';
	    OpCode[OpCode['DSTORE'] = 57] = 'DSTORE';
	    OpCode[OpCode['DSTORE_0'] = 71] = 'DSTORE_0';
	    OpCode[OpCode['DSTORE_1'] = 72] = 'DSTORE_1';
	    OpCode[OpCode['DSTORE_2'] = 73] = 'DSTORE_2';
	    OpCode[OpCode['DSTORE_3'] = 74] = 'DSTORE_3';
	    OpCode[OpCode['DSUB'] = 103] = 'DSUB';
	    OpCode[OpCode['DUP'] = 89] = 'DUP';
	    OpCode[OpCode['DUP_X1'] = 90] = 'DUP_X1';
	    OpCode[OpCode['DUP_X2'] = 91] = 'DUP_X2';
	    OpCode[OpCode['DUP2'] = 92] = 'DUP2';
	    OpCode[OpCode['DUP2_X1'] = 93] = 'DUP2_X1';
	    OpCode[OpCode['DUP2_X2'] = 94] = 'DUP2_X2';
	    OpCode[OpCode['F2D'] = 141] = 'F2D';
	    OpCode[OpCode['F2I'] = 139] = 'F2I';
	    OpCode[OpCode['F2L'] = 140] = 'F2L';
	    OpCode[OpCode['FADD'] = 98] = 'FADD';
	    OpCode[OpCode['FALOAD'] = 48] = 'FALOAD';
	    OpCode[OpCode['FASTORE'] = 81] = 'FASTORE';
	    OpCode[OpCode['FCMPG'] = 150] = 'FCMPG';
	    OpCode[OpCode['FCMPL'] = 149] = 'FCMPL';
	    OpCode[OpCode['FCONST_0'] = 11] = 'FCONST_0';
	    OpCode[OpCode['FCONST_1'] = 12] = 'FCONST_1';
	    OpCode[OpCode['FCONST_2'] = 13] = 'FCONST_2';
	    OpCode[OpCode['FDIV'] = 110] = 'FDIV';
	    OpCode[OpCode['FLOAD'] = 23] = 'FLOAD';
	    OpCode[OpCode['FLOAD_0'] = 34] = 'FLOAD_0';
	    OpCode[OpCode['FLOAD_1'] = 35] = 'FLOAD_1';
	    OpCode[OpCode['FLOAD_2'] = 36] = 'FLOAD_2';
	    OpCode[OpCode['FLOAD_3'] = 37] = 'FLOAD_3';
	    OpCode[OpCode['FMUL'] = 106] = 'FMUL';
	    OpCode[OpCode['FNEG'] = 118] = 'FNEG';
	    OpCode[OpCode['FREM'] = 114] = 'FREM';
	    OpCode[OpCode['FRETURN'] = 174] = 'FRETURN';
	    OpCode[OpCode['FSTORE'] = 56] = 'FSTORE';
	    OpCode[OpCode['FSTORE_0'] = 67] = 'FSTORE_0';
	    OpCode[OpCode['FSTORE_1'] = 68] = 'FSTORE_1';
	    OpCode[OpCode['FSTORE_2'] = 69] = 'FSTORE_2';
	    OpCode[OpCode['FSTORE_3'] = 70] = 'FSTORE_3';
	    OpCode[OpCode['FSUB'] = 102] = 'FSUB';
	    OpCode[OpCode['GETFIELD'] = 180] = 'GETFIELD';
	    OpCode[OpCode['GETSTATIC'] = 178] = 'GETSTATIC';
	    OpCode[OpCode['GOTO'] = 167] = 'GOTO';
	    OpCode[OpCode['GOTO_W'] = 200] = 'GOTO_W';
	    OpCode[OpCode['I2B'] = 145] = 'I2B';
	    OpCode[OpCode['I2C'] = 146] = 'I2C';
	    OpCode[OpCode['I2D'] = 135] = 'I2D';
	    OpCode[OpCode['I2F'] = 134] = 'I2F';
	    OpCode[OpCode['I2L'] = 133] = 'I2L';
	    OpCode[OpCode['I2S'] = 147] = 'I2S';
	    OpCode[OpCode['IADD'] = 96] = 'IADD';
	    OpCode[OpCode['IALOAD'] = 46] = 'IALOAD';
	    OpCode[OpCode['IAND'] = 126] = 'IAND';
	    OpCode[OpCode['IASTORE'] = 79] = 'IASTORE';
	    OpCode[OpCode['ICONST_M1'] = 2] = 'ICONST_M1';
	    OpCode[OpCode['ICONST_0'] = 3] = 'ICONST_0';
	    OpCode[OpCode['ICONST_1'] = 4] = 'ICONST_1';
	    OpCode[OpCode['ICONST_2'] = 5] = 'ICONST_2';
	    OpCode[OpCode['ICONST_3'] = 6] = 'ICONST_3';
	    OpCode[OpCode['ICONST_4'] = 7] = 'ICONST_4';
	    OpCode[OpCode['ICONST_5'] = 8] = 'ICONST_5';
	    OpCode[OpCode['IDIV'] = 108] = 'IDIV';
	    OpCode[OpCode['IF_ACMPEQ'] = 165] = 'IF_ACMPEQ';
	    OpCode[OpCode['IF_ACMPNE'] = 166] = 'IF_ACMPNE';
	    OpCode[OpCode['IF_ICMPEQ'] = 159] = 'IF_ICMPEQ';
	    OpCode[OpCode['IF_ICMPGE'] = 162] = 'IF_ICMPGE';
	    OpCode[OpCode['IF_ICMPGT'] = 163] = 'IF_ICMPGT';
	    OpCode[OpCode['IF_ICMPLE'] = 164] = 'IF_ICMPLE';
	    OpCode[OpCode['IF_ICMPLT'] = 161] = 'IF_ICMPLT';
	    OpCode[OpCode['IF_ICMPNE'] = 160] = 'IF_ICMPNE';
	    OpCode[OpCode['IFEQ'] = 153] = 'IFEQ';
	    OpCode[OpCode['IFGE'] = 156] = 'IFGE';
	    OpCode[OpCode['IFGT'] = 157] = 'IFGT';
	    OpCode[OpCode['IFLE'] = 158] = 'IFLE';
	    OpCode[OpCode['IFLT'] = 155] = 'IFLT';
	    OpCode[OpCode['IFNE'] = 154] = 'IFNE';
	    OpCode[OpCode['IFNONNULL'] = 199] = 'IFNONNULL';
	    OpCode[OpCode['IFNULL'] = 198] = 'IFNULL';
	    OpCode[OpCode['IINC'] = 132] = 'IINC';
	    OpCode[OpCode['ILOAD'] = 21] = 'ILOAD';
	    OpCode[OpCode['ILOAD_0'] = 26] = 'ILOAD_0';
	    OpCode[OpCode['ILOAD_1'] = 27] = 'ILOAD_1';
	    OpCode[OpCode['ILOAD_2'] = 28] = 'ILOAD_2';
	    OpCode[OpCode['ILOAD_3'] = 29] = 'ILOAD_3';
	    OpCode[OpCode['IMUL'] = 104] = 'IMUL';
	    OpCode[OpCode['INEG'] = 116] = 'INEG';
	    OpCode[OpCode['INSTANCEOF'] = 193] = 'INSTANCEOF';
	    OpCode[OpCode['INVOKEDYNAMIC'] = 186] = 'INVOKEDYNAMIC';
	    OpCode[OpCode['INVOKEINTERFACE'] = 185] = 'INVOKEINTERFACE';
	    OpCode[OpCode['INVOKESPECIAL'] = 183] = 'INVOKESPECIAL';
	    OpCode[OpCode['INVOKESTATIC'] = 184] = 'INVOKESTATIC';
	    OpCode[OpCode['INVOKEVIRTUAL'] = 182] = 'INVOKEVIRTUAL';
	    OpCode[OpCode['IOR'] = 128] = 'IOR';
	    OpCode[OpCode['IREM'] = 112] = 'IREM';
	    OpCode[OpCode['IRETURN'] = 172] = 'IRETURN';
	    OpCode[OpCode['ISHL'] = 120] = 'ISHL';
	    OpCode[OpCode['ISHR'] = 122] = 'ISHR';
	    OpCode[OpCode['ISTORE'] = 54] = 'ISTORE';
	    OpCode[OpCode['ISTORE_0'] = 59] = 'ISTORE_0';
	    OpCode[OpCode['ISTORE_1'] = 60] = 'ISTORE_1';
	    OpCode[OpCode['ISTORE_2'] = 61] = 'ISTORE_2';
	    OpCode[OpCode['ISTORE_3'] = 62] = 'ISTORE_3';
	    OpCode[OpCode['ISUB'] = 100] = 'ISUB';
	    OpCode[OpCode['IUSHR'] = 124] = 'IUSHR';
	    OpCode[OpCode['IXOR'] = 130] = 'IXOR';
	    OpCode[OpCode['JSR'] = 168] = 'JSR';
	    OpCode[OpCode['JSR_W'] = 201] = 'JSR_W';
	    OpCode[OpCode['L2D'] = 138] = 'L2D';
	    OpCode[OpCode['L2F'] = 137] = 'L2F';
	    OpCode[OpCode['L2I'] = 136] = 'L2I';
	    OpCode[OpCode['LADD'] = 97] = 'LADD';
	    OpCode[OpCode['LALOAD'] = 47] = 'LALOAD';
	    OpCode[OpCode['LAND'] = 127] = 'LAND';
	    OpCode[OpCode['LASTORE'] = 80] = 'LASTORE';
	    OpCode[OpCode['LCMP'] = 148] = 'LCMP';
	    OpCode[OpCode['LCONST_0'] = 9] = 'LCONST_0';
	    OpCode[OpCode['LCONST_1'] = 10] = 'LCONST_1';
	    OpCode[OpCode['LDC'] = 18] = 'LDC';
	    OpCode[OpCode['LDC_W'] = 19] = 'LDC_W';
	    OpCode[OpCode['LDC2_W'] = 20] = 'LDC2_W';
	    OpCode[OpCode['LDIV'] = 109] = 'LDIV';
	    OpCode[OpCode['LLOAD'] = 22] = 'LLOAD';
	    OpCode[OpCode['LLOAD_0'] = 30] = 'LLOAD_0';
	    OpCode[OpCode['LLOAD_1'] = 31] = 'LLOAD_1';
	    OpCode[OpCode['LLOAD_2'] = 32] = 'LLOAD_2';
	    OpCode[OpCode['LLOAD_3'] = 33] = 'LLOAD_3';
	    OpCode[OpCode['LMUL'] = 105] = 'LMUL';
	    OpCode[OpCode['LNEG'] = 117] = 'LNEG';
	    OpCode[OpCode['LOOKUPSWITCH'] = 171] = 'LOOKUPSWITCH';
	    OpCode[OpCode['LOR'] = 129] = 'LOR';
	    OpCode[OpCode['LREM'] = 113] = 'LREM';
	    OpCode[OpCode['LRETURN'] = 173] = 'LRETURN';
	    OpCode[OpCode['LSHL'] = 121] = 'LSHL';
	    OpCode[OpCode['LSHR'] = 123] = 'LSHR';
	    OpCode[OpCode['LSTORE'] = 55] = 'LSTORE';
	    OpCode[OpCode['LSTORE_0'] = 63] = 'LSTORE_0';
	    OpCode[OpCode['LSTORE_1'] = 64] = 'LSTORE_1';
	    OpCode[OpCode['LSTORE_2'] = 65] = 'LSTORE_2';
	    OpCode[OpCode['LSTORE_3'] = 66] = 'LSTORE_3';
	    OpCode[OpCode['LSUB'] = 101] = 'LSUB';
	    OpCode[OpCode['LUSHR'] = 125] = 'LUSHR';
	    OpCode[OpCode['LXOR'] = 131] = 'LXOR';
	    OpCode[OpCode['MONITORENTER'] = 194] = 'MONITORENTER';
	    OpCode[OpCode['MONITOREXIT'] = 195] = 'MONITOREXIT';
	    OpCode[OpCode['MULTIANEWARRAY'] = 197] = 'MULTIANEWARRAY';
	    OpCode[OpCode['NEW'] = 187] = 'NEW';
	    OpCode[OpCode['NEWARRAY'] = 188] = 'NEWARRAY';
	    OpCode[OpCode['NOP'] = 0] = 'NOP';
	    OpCode[OpCode['POP'] = 87] = 'POP';
	    OpCode[OpCode['POP2'] = 88] = 'POP2';
	    OpCode[OpCode['PUTFIELD'] = 181] = 'PUTFIELD';
	    OpCode[OpCode['PUTSTATIC'] = 179] = 'PUTSTATIC';
	    OpCode[OpCode['RET'] = 169] = 'RET';
	    OpCode[OpCode['RETURN'] = 177] = 'RETURN';
	    OpCode[OpCode['SALOAD'] = 53] = 'SALOAD';
	    OpCode[OpCode['SASTORE'] = 86] = 'SASTORE';
	    OpCode[OpCode['SIPUSH'] = 17] = 'SIPUSH';
	    OpCode[OpCode['SWAP'] = 95] = 'SWAP';
	    OpCode[OpCode['TABLESWITCH'] = 170] = 'TABLESWITCH';
	    OpCode[OpCode['WIDE'] = 196] = 'WIDE';
	    OpCode[OpCode['GETSTATIC_FAST32'] = 208] = 'GETSTATIC_FAST32';
	    OpCode[OpCode['GETSTATIC_FAST64'] = 209] = 'GETSTATIC_FAST64';
	    OpCode[OpCode['NEW_FAST'] = 210] = 'NEW_FAST';
	    OpCode[OpCode['ANEWARRAY_FAST'] = 213] = 'ANEWARRAY_FAST';
	    OpCode[OpCode['CHECKCAST_FAST'] = 214] = 'CHECKCAST_FAST';
	    OpCode[OpCode['INSTANCEOF_FAST'] = 215] = 'INSTANCEOF_FAST';
	    OpCode[OpCode['MULTIANEWARRAY_FAST'] = 216] = 'MULTIANEWARRAY_FAST';
	    OpCode[OpCode['PUTSTATIC_FAST32'] = 217] = 'PUTSTATIC_FAST32';
	    OpCode[OpCode['PUTSTATIC_FAST64'] = 218] = 'PUTSTATIC_FAST64';
	    OpCode[OpCode['GETFIELD_FAST32'] = 219] = 'GETFIELD_FAST32';
	    OpCode[OpCode['GETFIELD_FAST64'] = 220] = 'GETFIELD_FAST64';
	    OpCode[OpCode['PUTFIELD_FAST32'] = 221] = 'PUTFIELD_FAST32';
	    OpCode[OpCode['PUTFIELD_FAST64'] = 222] = 'PUTFIELD_FAST64';
	    OpCode[OpCode['INVOKENONVIRTUAL_FAST'] = 223] = 'INVOKENONVIRTUAL_FAST';
	    OpCode[OpCode['INVOKESTATIC_FAST'] = 240] = 'INVOKESTATIC_FAST';
	    OpCode[OpCode['INVOKEVIRTUAL_FAST'] = 241] = 'INVOKEVIRTUAL_FAST';
	    OpCode[OpCode['INVOKEINTERFACE_FAST'] = 242] = 'INVOKEINTERFACE_FAST';
	    OpCode[OpCode['INVOKEHANDLE'] = 243] = 'INVOKEHANDLE';
	    OpCode[OpCode['INVOKEBASIC'] = 244] = 'INVOKEBASIC';
	    OpCode[OpCode['LINKTOSPECIAL'] = 245] = 'LINKTOSPECIAL';
	    OpCode[OpCode['LINKTOVIRTUAL'] = 247] = 'LINKTOVIRTUAL';
	    OpCode[OpCode['INVOKEDYNAMIC_FAST'] = 248] = 'INVOKEDYNAMIC_FAST';
	}(exports.OpCode || (exports.OpCode = {})));
	var OpCode = exports.OpCode;
	(function (OpcodeLayoutType) {
	    OpcodeLayoutType[OpcodeLayoutType['OPCODE_ONLY'] = 0] = 'OPCODE_ONLY';
	    OpcodeLayoutType[OpcodeLayoutType['CONSTANT_POOL_UINT8'] = 1] = 'CONSTANT_POOL_UINT8';
	    OpcodeLayoutType[OpcodeLayoutType['CONSTANT_POOL'] = 2] = 'CONSTANT_POOL';
	    OpcodeLayoutType[OpcodeLayoutType['CONSTANT_POOL_AND_UINT8_VALUE'] = 3] = 'CONSTANT_POOL_AND_UINT8_VALUE';
	    OpcodeLayoutType[OpcodeLayoutType['UINT8_VALUE'] = 4] = 'UINT8_VALUE';
	    OpcodeLayoutType[OpcodeLayoutType['UINT8_AND_INT8_VALUE'] = 5] = 'UINT8_AND_INT8_VALUE';
	    OpcodeLayoutType[OpcodeLayoutType['INT8_VALUE'] = 6] = 'INT8_VALUE';
	    OpcodeLayoutType[OpcodeLayoutType['INT16_VALUE'] = 7] = 'INT16_VALUE';
	    OpcodeLayoutType[OpcodeLayoutType['INT32_VALUE'] = 8] = 'INT32_VALUE';
	    OpcodeLayoutType[OpcodeLayoutType['ARRAY_TYPE'] = 9] = 'ARRAY_TYPE';
	    OpcodeLayoutType[OpcodeLayoutType['WIDE'] = 10] = 'WIDE';
	}(exports.OpcodeLayoutType || (exports.OpcodeLayoutType = {})));
	var OpcodeLayoutType = exports.OpcodeLayoutType;
	var olt = new Array(255);
	(function () {
	    for (var i = 0; i < 255; i++) {
	        olt[i] = OpcodeLayoutType.OPCODE_ONLY;
	    }
	}());
	function assignOpcodeLayout(layoutType, opcodes) {
	    opcodes.forEach(function (opcode) {
	        olt[opcode] = layoutType;
	    });
	}
	assignOpcodeLayout(OpcodeLayoutType.UINT8_VALUE, [
	    OpCode.ALOAD,
	    OpCode.ASTORE,
	    OpCode.DLOAD,
	    OpCode.DSTORE,
	    OpCode.FLOAD,
	    OpCode.FSTORE,
	    OpCode.ILOAD,
	    OpCode.ISTORE,
	    OpCode.LLOAD,
	    OpCode.LSTORE,
	    OpCode.RET
	]);
	assignOpcodeLayout(OpcodeLayoutType.CONSTANT_POOL_UINT8, [OpCode.LDC]);
	assignOpcodeLayout(OpcodeLayoutType.CONSTANT_POOL, [
	    OpCode.LDC_W,
	    OpCode.LDC2_W,
	    OpCode.ANEWARRAY,
	    OpCode.CHECKCAST,
	    OpCode.GETFIELD,
	    OpCode.GETSTATIC,
	    OpCode.INSTANCEOF,
	    OpCode.INVOKEDYNAMIC,
	    OpCode.INVOKESPECIAL,
	    OpCode.INVOKESTATIC,
	    OpCode.INVOKEVIRTUAL,
	    OpCode.NEW,
	    OpCode.PUTFIELD,
	    OpCode.PUTSTATIC,
	    OpCode.MULTIANEWARRAY_FAST,
	    OpCode.INVOKENONVIRTUAL_FAST,
	    OpCode.INVOKESTATIC_FAST,
	    OpCode.CHECKCAST_FAST,
	    OpCode.NEW_FAST,
	    OpCode.ANEWARRAY_FAST,
	    OpCode.INSTANCEOF_FAST,
	    OpCode.GETSTATIC_FAST32,
	    OpCode.GETSTATIC_FAST64,
	    OpCode.PUTSTATIC_FAST32,
	    OpCode.PUTSTATIC_FAST64,
	    OpCode.PUTFIELD_FAST32,
	    OpCode.PUTFIELD_FAST64,
	    OpCode.GETFIELD_FAST32,
	    OpCode.GETFIELD_FAST64,
	    OpCode.INVOKEVIRTUAL_FAST
	]);
	assignOpcodeLayout(OpcodeLayoutType.CONSTANT_POOL_AND_UINT8_VALUE, [
	    OpCode.INVOKEINTERFACE,
	    OpCode.INVOKEINTERFACE_FAST,
	    OpCode.MULTIANEWARRAY
	]);
	assignOpcodeLayout(OpcodeLayoutType.INT8_VALUE, [OpCode.BIPUSH]);
	assignOpcodeLayout(OpcodeLayoutType.INT16_VALUE, [
	    OpCode.SIPUSH,
	    OpCode.GOTO,
	    OpCode.IFGT,
	    OpCode.IFEQ,
	    OpCode.IFGE,
	    OpCode.IFLE,
	    OpCode.IFLT,
	    OpCode.IFNE,
	    OpCode.IFNULL,
	    OpCode.IFNONNULL,
	    OpCode.IF_ICMPLE,
	    OpCode.IF_ACMPEQ,
	    OpCode.IF_ACMPNE,
	    OpCode.IF_ICMPEQ,
	    OpCode.IF_ICMPGE,
	    OpCode.IF_ICMPGT,
	    OpCode.IF_ICMPLT,
	    OpCode.IF_ICMPNE,
	    OpCode.JSR
	]);
	assignOpcodeLayout(OpcodeLayoutType.INT32_VALUE, [
	    OpCode.GOTO_W,
	    OpCode.JSR_W
	]);
	assignOpcodeLayout(OpcodeLayoutType.UINT8_AND_INT8_VALUE, [OpCode.IINC]);
	assignOpcodeLayout(OpcodeLayoutType.ARRAY_TYPE, [OpCode.NEWARRAY]);
	exports.OpcodeLayouts = olt;


/***/ },
/* 10 */
/***/ function(module, exports) {

	'use strict';
	var SafeMap = function () {
	    function SafeMap() {
	        this.cache = Object.create(null);
	    }
	    SafeMap.prototype.fixKey = function (key) {
	        return ';' + key;
	    };
	    SafeMap.prototype.get = function (key) {
	        key = this.fixKey(key);
	        if (this.cache[key] !== undefined) {
	            return this.cache[key];
	        }
	        return undefined;
	    };
	    SafeMap.prototype.has = function (key) {
	        return this.get(key) !== undefined;
	    };
	    SafeMap.prototype.set = function (key, value) {
	        this.cache[this.fixKey(key)] = value;
	    };
	    return SafeMap;
	}();
	module.exports = SafeMap;


/***/ },
/* 11 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var __extends = this && this.__extends || function (d, b) {
	    for (var p in b)
	        if (b.hasOwnProperty(p))
	            d[p] = b[p];
	    function __() {
	        this.constructor = d;
	    }
	    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
	};
	var util = __webpack_require__(6);
	var attributes = __webpack_require__(12);
	var threading = __webpack_require__(15);
	var assert = __webpack_require__(13);
	var enums = __webpack_require__(9);
	var StringOutputStream = __webpack_require__(18);
	var global = __webpack_require__(14);
	var jit_1 = __webpack_require__(19);
	if (typeof RELEASE === 'undefined')
	    global.RELEASE = false;
	var trapped_methods = {
	    'java/lang/ref/Reference': {
	        '<clinit>()V': function (thread) {
	        }
	    },
	    'java/lang/System': {
	        'loadLibrary(Ljava/lang/String;)V': function (thread, libName) {
	            var lib = libName.toString();
	            switch (lib) {
	            case 'zip':
	            case 'net':
	            case 'nio':
	            case 'awt':
	            case 'fontmanager':
	            case 'management':
	                return;
	            default:
	                thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'no ' + lib + ' in java.library.path');
	                break;
	            }
	        }
	    },
	    'java/lang/Terminator': {
	        'setup()V': function (thread) {
	        }
	    },
	    'java/nio/charset/Charset$3': {
	        'run()Ljava/lang/Object;': function (thread, javaThis) {
	            return null;
	        }
	    },
	    'sun/nio/fs/DefaultFileSystemProvider': {
	        'create()Ljava/nio/file/spi/FileSystemProvider;': function (thread) {
	            thread.setStatus(enums.ThreadStatus.ASYNC_WAITING);
	            var dfsp = thread.getBsCl().getInitializedClass(thread, 'Lsun/nio/fs/DefaultFileSystemProvider;'), dfspCls = dfsp.getConstructor(thread);
	            dfspCls['createProvider(Ljava/lang/String;)Ljava/nio/file/spi/FileSystemProvider;'](thread, [thread.getJVM().internString('sun.nio.fs.LinuxFileSystemProvider')], util.forwardResult(thread));
	        }
	    }
	};
	function getTrappedMethod(clsName, methSig) {
	    clsName = util.descriptor2typestr(clsName);
	    if (trapped_methods.hasOwnProperty(clsName) && trapped_methods[clsName].hasOwnProperty(methSig)) {
	        return trapped_methods[clsName][methSig];
	    }
	    return null;
	}
	var AbstractMethodField = function () {
	    function AbstractMethodField(cls, constantPool, slot, byteStream) {
	        this.cls = cls;
	        this.slot = slot;
	        this.accessFlags = new util.Flags(byteStream.getUint16());
	        this.name = constantPool.get(byteStream.getUint16()).value;
	        this.rawDescriptor = constantPool.get(byteStream.getUint16()).value;
	        this.attrs = attributes.makeAttributes(byteStream, constantPool);
	    }
	    AbstractMethodField.prototype.getAttribute = function (name) {
	        for (var i = 0; i < this.attrs.length; i++) {
	            var attr = this.attrs[i];
	            if (attr.getName() === name) {
	                return attr;
	            }
	        }
	        return null;
	    };
	    AbstractMethodField.prototype.getAttributes = function (name) {
	        return this.attrs.filter(function (attr) {
	            return attr.getName() === name;
	        });
	    };
	    AbstractMethodField.prototype.getAnnotationType = function (thread, name) {
	        var annotation = this.getAttribute(name);
	        if (annotation === null) {
	            return null;
	        }
	        var byteArrCons = thread.getBsCl().getInitializedClass(thread, '[B').getConstructor(thread), rv = new byteArrCons(thread, 0);
	        var i, len = annotation.rawBytes.length, arr = new Array(len);
	        for (i = 0; i < len; i++) {
	            arr[i] = annotation.rawBytes.readInt8(i);
	        }
	        rv.array = arr;
	        return rv;
	    };
	    AbstractMethodField.prototype.parseDescriptor = function (raw_descriptor) {
	        throw new Error('Unimplemented error.');
	    };
	    return AbstractMethodField;
	}();
	exports.AbstractMethodField = AbstractMethodField;
	var Field = function (_super) {
	    __extends(Field, _super);
	    function Field(cls, constantPool, slot, byteStream) {
	        _super.call(this, cls, constantPool, slot, byteStream);
	        this.fullName = util.descriptor2typestr(cls.getInternalName()) + '/' + this.name;
	    }
	    Field.prototype.reflector = function (thread, cb) {
	        var _this = this;
	        var signatureAttr = this.getAttribute('Signature'), jvm = thread.getJVM(), bsCl = thread.getBsCl();
	        var createObj = function (typeObj) {
	            var fieldCls = bsCl.getInitializedClass(thread, 'Ljava/lang/reflect/Field;'), fieldObj = new (fieldCls.getConstructor(thread))(thread);
	            fieldObj['java/lang/reflect/Field/clazz'] = _this.cls.getClassObject(thread);
	            fieldObj['java/lang/reflect/Field/name'] = jvm.internString(_this.name);
	            fieldObj['java/lang/reflect/Field/type'] = typeObj;
	            fieldObj['java/lang/reflect/Field/modifiers'] = _this.accessFlags.getRawByte();
	            fieldObj['java/lang/reflect/Field/slot'] = _this.slot;
	            fieldObj['java/lang/reflect/Field/signature'] = signatureAttr !== null ? util.initString(bsCl, signatureAttr.sig) : null;
	            fieldObj['java/lang/reflect/Field/annotations'] = _this.getAnnotationType(thread, 'RuntimeVisibleAnnotations');
	            return fieldObj;
	        };
	        this.cls.getLoader().resolveClass(thread, this.rawDescriptor, function (cdata) {
	            if (cdata != null) {
	                cb(createObj(cdata.getClassObject(thread)));
	            } else {
	                cb(null);
	            }
	        });
	    };
	    Field.prototype.getDefaultFieldValue = function () {
	        var desc = this.rawDescriptor;
	        if (desc === 'J')
	            return 'gLongZero';
	        var c = desc[0];
	        if (c === '[' || c === 'L')
	            return 'null';
	        return '0';
	    };
	    Field.prototype.outputJavaScriptField = function (jsConsName, outputStream) {
	        if (this.accessFlags.isStatic()) {
	            outputStream.write(jsConsName + '["' + util.reescapeJVMName(this.fullName) + '"] = cls._getInitialStaticFieldValue(thread, "' + util.reescapeJVMName(this.name) + '");\n');
	        } else {
	            outputStream.write('this["' + util.reescapeJVMName(this.fullName) + '"] = ' + this.getDefaultFieldValue() + ';\n');
	        }
	    };
	    return Field;
	}(AbstractMethodField);
	exports.Field = Field;
	var opcodeSize = function () {
	    var table = [];
	    var layoutType = enums.OpcodeLayoutType;
	    table[layoutType.OPCODE_ONLY] = 1;
	    table[layoutType.CONSTANT_POOL_UINT8] = 2;
	    table[layoutType.CONSTANT_POOL] = 3;
	    table[layoutType.CONSTANT_POOL_AND_UINT8_VALUE] = 4;
	    table[layoutType.UINT8_VALUE] = 2;
	    table[layoutType.UINT8_AND_INT8_VALUE] = 3;
	    table[layoutType.INT8_VALUE] = 2;
	    table[layoutType.INT16_VALUE] = 3;
	    table[layoutType.INT32_VALUE] = 5;
	    table[layoutType.ARRAY_TYPE] = 2;
	    table[layoutType.WIDE] = 1;
	    return table;
	}();
	var TraceInfo = function () {
	    function TraceInfo(pc, jitInfo) {
	        this.pc = pc;
	        this.jitInfo = jitInfo;
	        this.pops = [];
	        this.pushes = [];
	        this.prefixEmit = '';
	    }
	    return TraceInfo;
	}();
	var Trace = function () {
	    function Trace(startPC, code, method) {
	        this.startPC = startPC;
	        this.code = code;
	        this.method = method;
	        this.infos = [];
	    }
	    Trace.prototype.addOp = function (pc, jitInfo) {
	        this.infos.push(new TraceInfo(pc, jitInfo));
	    };
	    Trace.prototype.close = function (thread) {
	        if (this.infos.length > 1) {
	            var symbolicStack = [];
	            var symbolCount = 0;
	            var emitted = '';
	            for (var i = 0; i < this.infos.length; i++) {
	                var info = this.infos[i];
	                var jitInfo = info.jitInfo;
	                var pops = info.pops;
	                var normalizedPops = jitInfo.pops < 0 ? Math.min(-jitInfo.pops, symbolicStack.length) : jitInfo.pops;
	                for (var j = 0; j < normalizedPops; j++) {
	                    if (symbolicStack.length > 0) {
	                        pops.push(symbolicStack.pop());
	                    } else {
	                        var symbol = 's' + symbolCount++;
	                        info.prefixEmit += 'var ' + symbol + ' = f.opStack.pop();';
	                        pops.push(symbol);
	                    }
	                }
	                info.onErrorPushes = symbolicStack.slice();
	                var pushes = info.pushes;
	                for (var j = 0; j < jitInfo.pushes; j++) {
	                    var symbol = 's' + symbolCount++;
	                    symbolicStack.push(symbol);
	                    pushes.push(symbol);
	                }
	            }
	            if (symbolicStack.length === 1) {
	                emitted += 'f.opStack.push(' + symbolicStack[0] + ');';
	            } else if (symbolicStack.length > 1) {
	                emitted += 'f.opStack.pushAll(' + symbolicStack.join(',') + ');';
	            }
	            for (var i = this.infos.length - 1; i >= 0; i--) {
	                var info = this.infos[i];
	                var jitInfo = info.jitInfo;
	                emitted = info.prefixEmit + jitInfo.emit(info.pops, info.pushes, '' + i, emitted, this.code, info.pc, info.onErrorPushes, this.method);
	            }
	            if (!RELEASE && thread.getJVM().shouldPrintJITCompilation()) {
	                console.log('Emitted trace of ' + this.infos.length + ' ops: ' + emitted);
	            }
	            return new Function('f', 't', 'u', emitted);
	        } else {
	            if (!RELEASE && thread.getJVM().shouldPrintJITCompilation()) {
	                console.log('Trace was cancelled');
	            }
	            return null;
	        }
	    };
	    return Trace;
	}();
	var Method = function (_super) {
	    __extends(Method, _super);
	    function Method(cls, constantPool, slot, byteStream) {
	        _super.call(this, cls, constantPool, slot, byteStream);
	        this.numBBEntries = 0;
	        this.compiledFunctions = [];
	        this.failedCompile = [];
	        var parsedDescriptor = util.getTypes(this.rawDescriptor), i, p;
	        this.signature = this.name + this.rawDescriptor;
	        this.fullSignature = util.descriptor2typestr(this.cls.getInternalName()) + '/' + this.signature;
	        this.returnType = parsedDescriptor.pop();
	        this.parameterTypes = parsedDescriptor;
	        this.parameterWords = parsedDescriptor.length;
	        for (i = 0; i < this.parameterTypes.length; i++) {
	            p = this.parameterTypes[i];
	            if (p === 'D' || p === 'J') {
	                this.parameterWords++;
	            }
	        }
	        var clsName = this.cls.getInternalName();
	        if (getTrappedMethod(clsName, this.signature) !== null) {
	            this.code = getTrappedMethod(clsName, this.signature);
	            this.accessFlags.setNative(true);
	        } else if (this.accessFlags.isNative()) {
	            if (this.signature.indexOf('registerNatives()V', 0) < 0 && this.signature.indexOf('initIDs()V', 0) < 0) {
	                var self = this;
	                this.code = function (thread) {
	                    var jvm = thread.getJVM(), c = jvm.getNative(clsName, self.signature);
	                    if (c == null) {
	                        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method \'' + self.getFullSignature() + '\' not implemented.\nPlease fix or file a bug at https://github.com/plasma-umass/doppio/issues');
	                    } else {
	                        self.code = c;
	                        return c.apply(self, arguments);
	                    }
	                };
	            } else {
	                this.code = function () {
	                };
	            }
	        } else if (!this.accessFlags.isAbstract()) {
	            this.code = this.getAttribute('Code');
	            var codeLength = this.code.code.length;
	            this.numBBEntries = codeLength > 3 ? 200 : 1000 * codeLength;
	        }
	    }
	    Method.prototype.incrBBEntries = function () {
	        this.numBBEntries--;
	    };
	    Method.prototype.isDefault = function () {
	        return this.accessFlags.isPublic() && !this.accessFlags.isAbstract() && !this.accessFlags.isStatic() && this.cls.accessFlags.isInterface();
	    };
	    Method.prototype.getFullSignature = function () {
	        return this.cls.getExternalName() + '.' + this.name + this.rawDescriptor;
	    };
	    Method.prototype.isHidden = function () {
	        var rva = this.getAttribute('RuntimeVisibleAnnotations');
	        return rva !== null && rva.isHidden;
	    };
	    Method.prototype.isCallerSensitive = function () {
	        var rva = this.getAttribute('RuntimeVisibleAnnotations');
	        return rva !== null && rva.isCallerSensitive;
	    };
	    Method.prototype.getParamWordSize = function () {
	        return this.parameterWords;
	    };
	    Method.prototype.getCodeAttribute = function () {
	        assert(!this.accessFlags.isNative() && !this.accessFlags.isAbstract());
	        return this.code;
	    };
	    Method.prototype.getOp = function (pc, codeBuffer, thread) {
	        if (this.numBBEntries <= 0) {
	            if (!this.failedCompile[pc]) {
	                var cachedCompiledFunction = this.compiledFunctions[pc];
	                if (!cachedCompiledFunction) {
	                    var compiledFunction = this.jitCompileFrom(pc, thread);
	                    if (compiledFunction) {
	                        return compiledFunction;
	                    } else {
	                        this.failedCompile[pc] = true;
	                    }
	                } else {
	                    return cachedCompiledFunction;
	                }
	            }
	        }
	        return codeBuffer.readUInt8(pc);
	    };
	    Method.prototype.makeInvokeStaticJitInfo = function (code, pc) {
	        var index = code.readUInt16BE(pc + 1);
	        var methodReference = this.cls.constantPool.get(index);
	        var paramSize = methodReference.paramWordSize;
	        var method = methodReference.jsConstructor[methodReference.fullSignature];
	        return {
	            hasBranch: true,
	            pops: -paramSize,
	            pushes: 0,
	            emit: function (pops, pushes, suffix, onSuccess) {
	                var argInitialiser = paramSize > pops.length ? 'f.opStack.sliceAndDropFromTop(' + (paramSize - pops.length) + ');' : '[' + pops.reduce(function (a, b) {
	                    return b + ',' + a;
	                }, '') + '];';
	                var argMaker = 'var args' + suffix + '=' + argInitialiser;
	                if (paramSize > pops.length && pops.length > 0) {
	                    argMaker += 'args' + suffix + '.push(' + pops.slice().reverse().join(',') + ');';
	                }
	                return argMaker + ('\nvar methodReference' + suffix + '=f.method.cls.constantPool.get(' + index + ');\nmethodReference' + suffix + '.jsConstructor[methodReference' + suffix + '.fullSignature](t,args' + suffix + ');\nf.returnToThreadLoop=true;\n' + onSuccess);
	            }
	        };
	    };
	    Method.prototype.makeInvokeVirtualJitInfo = function (code, pc) {
	        var index = code.readUInt16BE(pc + 1);
	        var methodReference = this.cls.constantPool.get(index);
	        var paramSize = methodReference.paramWordSize;
	        return {
	            hasBranch: true,
	            pops: -(paramSize + 1),
	            pushes: 0,
	            emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	                var onError = makeOnError(onErrorPushes);
	                var argInitialiser = paramSize > pops.length ? 'f.opStack.sliceAndDropFromTop(' + (paramSize - pops.length) + ');' : '[' + pops.slice(0, paramSize).reduce(function (a, b) {
	                    return b + ',' + a;
	                }, '') + '];';
	                var argMaker = 'var args' + suffix + '=' + argInitialiser;
	                if (paramSize > pops.length && pops.length > 0) {
	                    argMaker += 'args' + suffix + '.push(' + pops.slice().reverse().join(',') + ');';
	                }
	                return argMaker + ('var obj' + suffix + '=' + (paramSize + 1 === pops.length ? pops[paramSize] : 'f.opStack.pop();') + '\nif(!u.isNull(t,f,obj' + suffix + ')){obj' + suffix + '[\'' + methodReference.signature + '\'](t,args' + suffix + ');f.returnToThreadLoop=true;' + onSuccess + '}else{' + onError + '}');
	            }
	        };
	    };
	    Method.prototype.makeInvokeNonVirtualJitInfo = function (code, pc) {
	        var index = code.readUInt16BE(pc + 1);
	        var methodReference = this.cls.constantPool.get(index);
	        var paramSize = methodReference.paramWordSize;
	        return {
	            hasBranch: true,
	            pops: -(paramSize + 1),
	            pushes: 0,
	            emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	                var onError = makeOnError(onErrorPushes);
	                var argInitialiser = paramSize > pops.length ? 'f.opStack.sliceAndDropFromTop(' + (paramSize - pops.length) + ');' : '[' + pops.slice(0, paramSize).reduce(function (a, b) {
	                    return b + ',' + a;
	                }, '') + '];';
	                var argMaker = 'var args' + suffix + '=' + argInitialiser;
	                if (paramSize > pops.length && pops.length > 0) {
	                    argMaker += 'args' + suffix + '.push(' + pops.slice().reverse().join(',') + ');';
	                }
	                return argMaker + ('var obj' + suffix + '=' + (paramSize + 1 === pops.length ? pops[paramSize] : 'f.opStack.pop();') + '\nif(!u.isNull(t,f,obj' + suffix + ')){obj' + suffix + '[\'' + methodReference.fullSignature + '\'](t, args' + suffix + ');f.returnToThreadLoop=true;' + onSuccess + '}else{' + onError + '}');
	            }
	        };
	    };
	    Method.prototype.jitCompileFrom = function (startPC, thread) {
	        if (!RELEASE && thread.getJVM().shouldPrintJITCompilation()) {
	            console.log('Planning to JIT: ' + this.fullSignature + ' from ' + startPC);
	        }
	        var code = this.code.code;
	        var trace = null;
	        var _this = this;
	        var done = false;
	        function closeCurrentTrace() {
	            if (trace !== null) {
	                var compiledFunction = trace.close(thread);
	                if (compiledFunction) {
	                    _this.compiledFunctions[trace.startPC] = compiledFunction;
	                }
	                trace = null;
	            }
	            done = true;
	        }
	        for (var i = startPC; i < code.length && !done;) {
	            var op = code.readUInt8(i);
	            if (!RELEASE && thread.getJVM().shouldPrintJITCompilation()) {
	                console.log(i + ': ' + threading.annotateOpcode(op, this, code, i));
	            }
	            var jitInfo = jit_1.opJitInfo[op];
	            if (jitInfo) {
	                if (trace === null) {
	                    trace = new Trace(i, code, _this);
	                }
	                trace.addOp(i, jitInfo);
	                if (jitInfo.hasBranch) {
	                    this.failedCompile[i] = true;
	                    closeCurrentTrace();
	                }
	            } else if (op === enums.OpCode.INVOKESTATIC_FAST && trace !== null) {
	                var invokeJitInfo = this.makeInvokeStaticJitInfo(code, i);
	                trace.addOp(i, invokeJitInfo);
	                this.failedCompile[i] = true;
	                closeCurrentTrace();
	            } else if ((op === enums.OpCode.INVOKEVIRTUAL_FAST || op === enums.OpCode.INVOKEINTERFACE_FAST) && trace !== null) {
	                var invokeJitInfo = this.makeInvokeVirtualJitInfo(code, i);
	                trace.addOp(i, invokeJitInfo);
	                this.failedCompile[i] = true;
	                closeCurrentTrace();
	            } else if (op === enums.OpCode.INVOKENONVIRTUAL_FAST && trace !== null) {
	                var invokeJitInfo = this.makeInvokeNonVirtualJitInfo(code, i);
	                trace.addOp(i, invokeJitInfo);
	                this.failedCompile[i] = true;
	                closeCurrentTrace();
	            } else {
	                if (!RELEASE) {
	                    if (trace !== null) {
	                        statTraceCloser[op]++;
	                    }
	                }
	                this.failedCompile[i] = true;
	                closeCurrentTrace();
	            }
	            i += opcodeSize[enums.OpcodeLayouts[op]];
	        }
	        return _this.compiledFunctions[startPC];
	    };
	    Method.prototype.getNativeFunction = function () {
	        assert(this.accessFlags.isNative() && typeof this.code === 'function');
	        return this.code;
	    };
	    Method.prototype._resolveReferencedClasses = function (thread, cb) {
	        var toResolve = this.parameterTypes.concat(this.returnType), code = this.code, exceptionAttribute = this.getAttribute('Exceptions');
	        if (!this.accessFlags.isNative() && !this.accessFlags.isAbstract() && code.exceptionHandlers.length > 0) {
	            toResolve.push('Ljava/lang/Throwable;');
	            toResolve = toResolve.concat(code.exceptionHandlers.filter(function (handler) {
	                return handler.catchType !== '<any>';
	            }).map(function (handler) {
	                return handler.catchType;
	            }));
	        }
	        if (exceptionAttribute !== null) {
	            toResolve = toResolve.concat(exceptionAttribute.exceptions);
	        }
	        this.cls.getLoader().resolveClasses(thread, toResolve, function (classes) {
	            thread.getBsCl().resolveClasses(thread, [
	                'Ljava/lang/reflect/Method;',
	                'Ljava/lang/reflect/Constructor;'
	            ], function (classes2) {
	                if (classes === null || classes2 === null) {
	                    cb(null);
	                } else {
	                    classes['Ljava/lang/reflect/Method;'] = classes2['Ljava/lang/reflect/Method;'];
	                    classes['Ljava/lang/reflect/Constructor;'] = classes2['Ljava/lang/reflect/Constructor;'];
	                    cb(classes);
	                }
	            });
	        });
	    };
	    Method.prototype.reflector = function (thread, cb) {
	        var _this = this;
	        var bsCl = thread.getBsCl(), clazzArray = bsCl.getInitializedClass(thread, '[Ljava/lang/Class;').getConstructor(thread), jvm = thread.getJVM(), signatureAttr = this.getAttribute('Signature'), exceptionAttr = this.getAttribute('Exceptions');
	        this._resolveReferencedClasses(thread, function (classes) {
	            if (classes === null) {
	                return cb(null);
	            }
	            var clazz = _this.cls.getClassObject(thread), name = jvm.internString(_this.name), parameterTypes = new clazzArray(thread, 0), returnType = classes[_this.returnType].getClassObject(thread), exceptionTypes = new clazzArray(thread, 0), modifiers = _this.accessFlags.getRawByte(), signature = signatureAttr !== null ? jvm.internString(signatureAttr.sig) : null;
	            parameterTypes.array = _this.parameterTypes.map(function (ptype) {
	                return classes[ptype].getClassObject(thread);
	            });
	            if (exceptionAttr !== null) {
	                exceptionTypes.array = exceptionAttr.exceptions.map(function (eType) {
	                    return classes[eType].getClassObject(thread);
	                });
	            }
	            if (_this.name === '<init>') {
	                var consCons = classes['Ljava/lang/reflect/Constructor;'].getConstructor(thread), consObj = new consCons(thread);
	                consObj['java/lang/reflect/Constructor/clazz'] = clazz;
	                consObj['java/lang/reflect/Constructor/parameterTypes'] = parameterTypes;
	                consObj['java/lang/reflect/Constructor/exceptionTypes'] = exceptionTypes;
	                consObj['java/lang/reflect/Constructor/modifiers'] = modifiers;
	                consObj['java/lang/reflect/Constructor/slot'] = _this.slot;
	                consObj['java/lang/reflect/Constructor/signature'] = signature;
	                consObj['java/lang/reflect/Constructor/annotations'] = _this.getAnnotationType(thread, 'RuntimeVisibleAnnotations');
	                consObj['java/lang/reflect/Constructor/parameterAnnotations'] = _this.getAnnotationType(thread, 'RuntimeVisibleParameterAnnotations');
	                cb(consObj);
	            } else {
	                var methodCons = classes['Ljava/lang/reflect/Method;'].getConstructor(thread), methodObj = new methodCons(thread);
	                methodObj['java/lang/reflect/Method/clazz'] = clazz;
	                methodObj['java/lang/reflect/Method/name'] = name;
	                methodObj['java/lang/reflect/Method/parameterTypes'] = parameterTypes;
	                methodObj['java/lang/reflect/Method/returnType'] = returnType;
	                methodObj['java/lang/reflect/Method/exceptionTypes'] = exceptionTypes;
	                methodObj['java/lang/reflect/Method/modifiers'] = modifiers;
	                methodObj['java/lang/reflect/Method/slot'] = _this.slot;
	                methodObj['java/lang/reflect/Method/signature'] = signature;
	                methodObj['java/lang/reflect/Method/annotations'] = _this.getAnnotationType(thread, 'RuntimeVisibleAnnotations');
	                methodObj['java/lang/reflect/Method/annotationDefault'] = _this.getAnnotationType(thread, 'AnnotationDefault');
	                methodObj['java/lang/reflect/Method/parameterAnnotations'] = _this.getAnnotationType(thread, 'RuntimeVisibleParameterAnnotations');
	                cb(methodObj);
	            }
	        });
	    };
	    Method.prototype.convertArgs = function (thread, params) {
	        if (this.isSignaturePolymorphic()) {
	            params.unshift(thread);
	            return params;
	        }
	        var convertedArgs = [thread], argIdx = 0, i;
	        if (!this.accessFlags.isStatic()) {
	            convertedArgs.push(params[0]);
	            argIdx = 1;
	        }
	        for (i = 0; i < this.parameterTypes.length; i++) {
	            var p = this.parameterTypes[i];
	            convertedArgs.push(params[argIdx]);
	            argIdx += p === 'J' || p === 'D' ? 2 : 1;
	        }
	        return convertedArgs;
	    };
	    Method.prototype.methodLock = function (thread, frame) {
	        if (this.accessFlags.isStatic()) {
	            return this.cls.getClassObject(thread).getMonitor();
	        } else {
	            return frame.locals[0].getMonitor();
	        }
	    };
	    Method.prototype.isSignaturePolymorphic = function () {
	        return this.cls.getInternalName() === 'Ljava/lang/invoke/MethodHandle;' && this.accessFlags.isNative() && this.accessFlags.isVarArgs() && this.rawDescriptor === '([Ljava/lang/Object;)Ljava/lang/Object;';
	    };
	    Method.prototype.getVMTargetBridgeMethod = function (thread, refKind) {
	        var outStream = new StringOutputStream(), virtualDispatch = !(refKind === enums.MethodHandleReferenceKind.INVOKESTATIC || refKind === enums.MethodHandleReferenceKind.INVOKESPECIAL);
	        outStream.write('function _create(thread, cls, util) {\n');
	        if (this.accessFlags.isStatic()) {
	            assert(!virtualDispatch, 'Can\'t have static virtual dispatch.');
	            outStream.write('  var jsCons = cls.getConstructor(thread);\n');
	        }
	        outStream.write('  function bridgeMethod(thread, descriptor, args, cb) {\n');
	        if (!this.accessFlags.isStatic()) {
	            outStream.write('    var obj = args.shift();\n');
	            outStream.write('    if (obj === null) { return thread.throwNewException(\'Ljava/lang/NullPointerException;\', \'\'); }\n');
	            outStream.write('    obj["' + util.reescapeJVMName(virtualDispatch ? this.signature : this.fullSignature) + '"](thread, ');
	        } else {
	            outStream.write('    jsCons["' + util.reescapeJVMName(this.fullSignature) + '"](thread, ');
	        }
	        outStream.write('args');
	        outStream.write(', cb);\n  }\n  return bridgeMethod;\n}\n_create');
	        var evalText = outStream.flush();
	        if (typeof RELEASE === 'undefined' && thread !== null && thread.getJVM().shouldDumpCompiledCode()) {
	            thread.getJVM().dumpBridgeMethod(this.fullSignature, evalText);
	        }
	        return eval(evalText)(thread, this.cls, util);
	    };
	    Method.prototype.outputJavaScriptFunction = function (jsConsName, outStream, nonVirtualOnly) {
	        if (nonVirtualOnly === void 0) {
	            nonVirtualOnly = false;
	        }
	        var i;
	        if (this.accessFlags.isStatic()) {
	            outStream.write(jsConsName + '["' + util.reescapeJVMName(this.fullSignature) + '"] = ' + jsConsName + '["' + util.reescapeJVMName(this.signature) + '"] = ');
	        } else {
	            if (!nonVirtualOnly) {
	                outStream.write(jsConsName + '.prototype["' + util.reescapeJVMName(this.signature) + '"] = ');
	            }
	            outStream.write(jsConsName + '.prototype["' + util.reescapeJVMName(this.fullSignature) + '"] = ');
	        }
	        outStream.write('(function(method) {\n  return function(thread, args, cb) {\n    if (typeof cb === \'function\') {\n      thread.stack.push(new InternalStackFrame(cb));\n    }\n    thread.stack.push(new ' + (this.accessFlags.isNative() ? 'NativeStackFrame' : 'BytecodeStackFrame') + '(method, ');
	        if (!this.accessFlags.isStatic()) {
	            outStream.write('[this');
	            for (i = 0; i < this.parameterWords; i++) {
	                outStream.write(', args[' + i + ']');
	            }
	            outStream.write(']');
	        } else {
	            if (this.parameterWords > 0) {
	                outStream.write('args');
	            } else {
	                outStream.write('[]');
	            }
	        }
	        outStream.write('));\n    thread.setStatus(' + enums.ThreadStatus.RUNNABLE + ');\n  };\n})(cls.getSpecificMethod("' + util.reescapeJVMName(this.cls.getInternalName()) + '", "' + util.reescapeJVMName(this.signature) + '"));\n');
	    };
	    return Method;
	}(AbstractMethodField);
	exports.Method = Method;
	function makeOnError(onErrorPushes) {
	    return onErrorPushes.length > 0 ? 'f.opStack.pushAll(' + onErrorPushes.join(',') + ');' : '';
	}
	var statTraceCloser = new Array(256);
	if (!RELEASE) {
	    for (var i = 0; i < 256; i++) {
	        statTraceCloser[i] = 0;
	    }
	}
	function dumpStats() {
	    var range = new Array(256);
	    for (var i = 0; i < 256; i++) {
	        range[i] = i;
	    }
	    range.sort(function (x, y) {
	        return statTraceCloser[y] - statTraceCloser[x];
	    });
	    var top = range.slice(0, 24);
	    console.log('Opcodes that closed a trace (number of times encountered):');
	    for (var i = 0; i < top.length; i++) {
	        var op = top[i];
	        if (statTraceCloser[op] > 0) {
	            console.log(enums.OpCode[op], statTraceCloser[op]);
	        }
	    }
	}
	exports.dumpStats = dumpStats;


/***/ },
/* 12 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var util = __webpack_require__(6);
	var enums = __webpack_require__(9);
	var assert = __webpack_require__(13);
	var global = __webpack_require__(14);
	if (typeof RELEASE === 'undefined')
	    global.RELEASE = false;
	var ExceptionHandler = function () {
	    function ExceptionHandler(startPC, endPC, handlerPC, catchType) {
	        this.startPC = startPC;
	        this.endPC = endPC;
	        this.handlerPC = handlerPC;
	        this.catchType = catchType;
	    }
	    ExceptionHandler.prototype.getName = function () {
	        return 'ExceptionHandler';
	    };
	    ExceptionHandler.parse = function (bytesArray, constantPool) {
	        var startPC = bytesArray.getUint16(), endPC = bytesArray.getUint16(), handlerPC = bytesArray.getUint16(), cti = bytesArray.getUint16(), catchType = cti === 0 ? '<any>' : constantPool.get(cti).name;
	        return new this(startPC, endPC, handlerPC, catchType);
	    };
	    return ExceptionHandler;
	}();
	exports.ExceptionHandler = ExceptionHandler;
	var Code = function () {
	    function Code(maxStack, maxLocals, exceptionHandlers, attrs, code) {
	        this.maxStack = maxStack;
	        this.maxLocals = maxLocals;
	        this.exceptionHandlers = exceptionHandlers;
	        this.attrs = attrs;
	        this.code = code;
	    }
	    Code.prototype.getName = function () {
	        return 'Code';
	    };
	    Code.prototype.getMaxStack = function () {
	        return this.maxStack;
	    };
	    Code.parse = function (byteStream, constantPool) {
	        var maxStack = byteStream.getUint16(), maxLocals = byteStream.getUint16(), codeLen = byteStream.getUint32();
	        if (codeLen === 0) {
	            if (RELEASE) {
	                throw 'Error parsing code: Code length is zero';
	            }
	        }
	        var code = byteStream.slice(codeLen).getBuffer(), exceptLen = byteStream.getUint16(), exceptionHandlers = [];
	        for (var i = 0; i < exceptLen; i++) {
	            exceptionHandlers.push(ExceptionHandler.parse(byteStream, constantPool));
	        }
	        var attrs = makeAttributes(byteStream, constantPool);
	        return new this(maxStack, maxLocals, exceptionHandlers, attrs, code);
	    };
	    Code.prototype.getCode = function () {
	        return this.code;
	    };
	    Code.prototype.getAttribute = function (name) {
	        for (var i = 0; i < this.attrs.length; i++) {
	            var attr = this.attrs[i];
	            if (attr.getName() === name) {
	                return attr;
	            }
	        }
	        return null;
	    };
	    return Code;
	}();
	exports.Code = Code;
	var LineNumberTable = function () {
	    function LineNumberTable(entries) {
	        this.entries = entries;
	    }
	    LineNumberTable.prototype.getName = function () {
	        return 'LineNumberTable';
	    };
	    LineNumberTable.prototype.getLineNumber = function (pc) {
	        var j, lineNumber = -1;
	        for (j = 0; j < this.entries.length; j++) {
	            var entry = this.entries[j];
	            if (entry.startPC <= pc) {
	                lineNumber = entry.lineNumber;
	            } else {
	                break;
	            }
	        }
	        return lineNumber;
	    };
	    LineNumberTable.parse = function (byteStream, constantPool) {
	        var entries = [];
	        var lntLen = byteStream.getUint16();
	        for (var i = 0; i < lntLen; i++) {
	            var spc = byteStream.getUint16();
	            var ln = byteStream.getUint16();
	            entries.push({
	                'startPC': spc,
	                'lineNumber': ln
	            });
	        }
	        return new this(entries);
	    };
	    return LineNumberTable;
	}();
	exports.LineNumberTable = LineNumberTable;
	var SourceFile = function () {
	    function SourceFile(filename) {
	        this.filename = filename;
	    }
	    SourceFile.prototype.getName = function () {
	        return 'SourceFile';
	    };
	    SourceFile.parse = function (byteStream, constantPool) {
	        return new this(constantPool.get(byteStream.getUint16()).value);
	    };
	    return SourceFile;
	}();
	exports.SourceFile = SourceFile;
	var StackMapTable = function () {
	    function StackMapTable(entries) {
	        this.entries = entries;
	    }
	    StackMapTable.prototype.getName = function () {
	        return 'StackMapTable';
	    };
	    StackMapTable.parse = function (byteStream, constantPool) {
	        var numEntries = byteStream.getUint16(), entries = [];
	        for (var i = 0; i < numEntries; i++) {
	            entries.push(this.parseEntry(byteStream, constantPool));
	        }
	        return new this(entries);
	    };
	    StackMapTable.parseEntry = function (byteStream, constantPool) {
	        var frameType = byteStream.getUint8(), locals, offsetDelta, i;
	        if (frameType < 64) {
	            return {
	                type: enums.StackMapTableEntryType.SAME_FRAME,
	                offsetDelta: frameType
	            };
	        } else if (frameType < 128) {
	            return {
	                type: enums.StackMapTableEntryType.SAME_LOCALS_1_STACK_ITEM_FRAME,
	                offsetDelta: frameType - 64,
	                stack: [this.parseVerificationTypeInfo(byteStream, constantPool)]
	            };
	        } else if (frameType < 247) {
	        } else if (frameType === 247) {
	            return {
	                type: enums.StackMapTableEntryType.SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED,
	                offsetDelta: byteStream.getUint16(),
	                stack: [this.parseVerificationTypeInfo(byteStream, constantPool)]
	            };
	        } else if (frameType < 251) {
	            return {
	                type: enums.StackMapTableEntryType.CHOP_FRAME,
	                offsetDelta: byteStream.getUint16(),
	                k: 251 - frameType
	            };
	        } else if (frameType === 251) {
	            return {
	                type: enums.StackMapTableEntryType.SAME_FRAME_EXTENDED,
	                offsetDelta: byteStream.getUint16()
	            };
	        } else if (frameType < 255) {
	            offsetDelta = byteStream.getUint16();
	            locals = [];
	            for (i = 0; i < frameType - 251; i++) {
	                locals.push(this.parseVerificationTypeInfo(byteStream, constantPool));
	            }
	            return {
	                type: enums.StackMapTableEntryType.APPEND_FRAME,
	                offsetDelta: offsetDelta,
	                locals: locals
	            };
	        } else if (frameType === 255) {
	            offsetDelta = byteStream.getUint16();
	            var numLocals = byteStream.getUint16();
	            locals = [];
	            for (i = 0; i < numLocals; i++) {
	                locals.push(this.parseVerificationTypeInfo(byteStream, constantPool));
	            }
	            var numStackItems = byteStream.getUint16();
	            var stack = [];
	            for (i = 0; i < numStackItems; i++) {
	                stack.push(this.parseVerificationTypeInfo(byteStream, constantPool));
	            }
	            return {
	                type: enums.StackMapTableEntryType.FULL_FRAME,
	                offsetDelta: offsetDelta,
	                numLocals: numLocals,
	                locals: locals,
	                numStackItems: numStackItems,
	                stack: stack
	            };
	        }
	    };
	    StackMapTable.parseVerificationTypeInfo = function (byteStream, constantPool) {
	        var tag = byteStream.getUint8();
	        if (tag === 7) {
	            var cls = constantPool.get(byteStream.getUint16()).name;
	            return 'class ' + (/\w/.test(cls[0]) ? util.descriptor2typestr(cls) : '"' + cls + '"');
	        } else if (tag === 8) {
	            return 'uninitialized ' + byteStream.getUint16();
	        } else {
	            var tagToType = [
	                'bogus',
	                'int',
	                'float',
	                'double',
	                'long',
	                'null',
	                'this',
	                'object',
	                'uninitialized'
	            ];
	            return tagToType[tag];
	        }
	    };
	    return StackMapTable;
	}();
	exports.StackMapTable = StackMapTable;
	var LocalVariableTable = function () {
	    function LocalVariableTable(entries) {
	        this.entries = entries;
	    }
	    LocalVariableTable.prototype.getName = function () {
	        return 'LocalVariableTable';
	    };
	    LocalVariableTable.parse = function (byteStream, constantPool) {
	        var numEntries = byteStream.getUint16(), entries = [];
	        for (var i = 0; i < numEntries; i++) {
	            entries.push(this.parseEntries(byteStream, constantPool));
	        }
	        return new this(entries);
	    };
	    LocalVariableTable.parseEntries = function (bytes_array, constant_pool) {
	        return {
	            startPC: bytes_array.getUint16(),
	            length: bytes_array.getUint16(),
	            name: constant_pool.get(bytes_array.getUint16()).value,
	            descriptor: constant_pool.get(bytes_array.getUint16()).value,
	            ref: bytes_array.getUint16()
	        };
	    };
	    return LocalVariableTable;
	}();
	exports.LocalVariableTable = LocalVariableTable;
	var LocalVariableTypeTable = function () {
	    function LocalVariableTypeTable(entries) {
	        this.entries = entries;
	    }
	    LocalVariableTypeTable.prototype.getName = function () {
	        return 'LocalVariableTypeTable';
	    };
	    LocalVariableTypeTable.parse = function (byteStream, constantPool) {
	        var numEntries = byteStream.getUint16(), i, entries = [];
	        for (i = 0; i < numEntries; i++) {
	            entries.push(this.parseTableEntry(byteStream, constantPool));
	        }
	        return new this(entries);
	    };
	    LocalVariableTypeTable.parseTableEntry = function (byteStream, constantPool) {
	        return {
	            startPC: byteStream.getUint16(),
	            length: byteStream.getUint16(),
	            name: constantPool.get(byteStream.getUint16()).value,
	            signature: constantPool.get(byteStream.getUint16()).value,
	            index: byteStream.getUint16()
	        };
	    };
	    return LocalVariableTypeTable;
	}();
	exports.LocalVariableTypeTable = LocalVariableTypeTable;
	var Exceptions = function () {
	    function Exceptions(exceptions) {
	        this.exceptions = exceptions;
	    }
	    Exceptions.prototype.getName = function () {
	        return 'Exceptions';
	    };
	    Exceptions.parse = function (byteStream, constantPool) {
	        var numExceptions = byteStream.getUint16();
	        var excRefs = [];
	        for (var i = 0; i < numExceptions; i++) {
	            excRefs.push(byteStream.getUint16());
	        }
	        return new this(excRefs.map(function (ref) {
	            return constantPool.get(ref).name;
	        }));
	    };
	    return Exceptions;
	}();
	exports.Exceptions = Exceptions;
	var InnerClasses = function () {
	    function InnerClasses(classes) {
	        this.classes = classes;
	    }
	    InnerClasses.prototype.getName = function () {
	        return 'InnerClasses';
	    };
	    InnerClasses.parse = function (bytes_array, constant_pool) {
	        var numClasses = bytes_array.getUint16(), classes = [];
	        for (var i = 0; i < numClasses; i++) {
	            classes.push(this.parseClass(bytes_array, constant_pool));
	        }
	        return new this(classes);
	    };
	    InnerClasses.parseClass = function (byteStream, constantPool) {
	        return {
	            innerInfoIndex: byteStream.getUint16(),
	            outerInfoIndex: byteStream.getUint16(),
	            innerNameIndex: byteStream.getUint16(),
	            innerAccessFlags: byteStream.getUint16()
	        };
	    };
	    return InnerClasses;
	}();
	exports.InnerClasses = InnerClasses;
	var ConstantValue = function () {
	    function ConstantValue(value) {
	        this.value = value;
	    }
	    ConstantValue.prototype.getName = function () {
	        return 'ConstantValue';
	    };
	    ConstantValue.parse = function (bytes_array, constant_pool) {
	        var ref = bytes_array.getUint16();
	        return new this(constant_pool.get(ref));
	    };
	    return ConstantValue;
	}();
	exports.ConstantValue = ConstantValue;
	var Synthetic = function () {
	    function Synthetic() {
	    }
	    Synthetic.prototype.getName = function () {
	        return 'Synthetic';
	    };
	    Synthetic.parse = function (byteStream, constantPool) {
	        return new this();
	    };
	    return Synthetic;
	}();
	exports.Synthetic = Synthetic;
	var Deprecated = function () {
	    function Deprecated() {
	    }
	    Deprecated.prototype.getName = function () {
	        return 'Deprecated';
	    };
	    Deprecated.parse = function (byteStream, constantPool) {
	        return new this();
	    };
	    return Deprecated;
	}();
	exports.Deprecated = Deprecated;
	var Signature = function () {
	    function Signature(sig) {
	        this.sig = sig;
	    }
	    Signature.prototype.getName = function () {
	        return 'Signature';
	    };
	    Signature.parse = function (byteStream, constantPool) {
	        return new this(constantPool.get(byteStream.getUint16()).value);
	    };
	    return Signature;
	}();
	exports.Signature = Signature;
	var RuntimeVisibleAnnotations = function () {
	    function RuntimeVisibleAnnotations(rawBytes, isHidden, isCallerSensitive, isCompiled) {
	        this.rawBytes = rawBytes;
	        this.isHidden = isHidden;
	        this.isCallerSensitive = isCallerSensitive;
	        this.isCompiled = isCompiled;
	    }
	    RuntimeVisibleAnnotations.prototype.getName = function () {
	        return 'RuntimeVisibleAnnotations';
	    };
	    RuntimeVisibleAnnotations.parse = function (byteStream, constantPool, attrLen) {
	        function skipAnnotation() {
	            byteStream.skip(2);
	            var numValuePairs = byteStream.getUint16(), i;
	            for (i = 0; i < numValuePairs; i++) {
	                byteStream.skip(2);
	                skipElementValue();
	            }
	        }
	        function skipElementValue() {
	            var tag = String.fromCharCode(byteStream.getUint8());
	            switch (tag) {
	            case 'e':
	                byteStream.skip(2);
	            case 'Z':
	            case 'B':
	            case 'C':
	            case 'S':
	            case 'I':
	            case 'F':
	            case 'J':
	            case 'D':
	            case 's':
	            case 'c':
	                byteStream.skip(2);
	                break;
	            case '@':
	                skipAnnotation();
	                break;
	            case '[':
	                var numValues = byteStream.getUint16(), i;
	                for (i = 0; i < numValues; i++) {
	                    skipElementValue();
	                }
	                break;
	            }
	        }
	        var rawBytes = byteStream.read(attrLen), isHidden = false, isCompiled = false, isCallerSensitive = false;
	        byteStream.seek(byteStream.pos() - rawBytes.length);
	        var numAttributes = byteStream.getUint16(), i;
	        for (i = 0; i < numAttributes; i++) {
	            var typeName = constantPool.get(byteStream.getUint16());
	            byteStream.seek(byteStream.pos() - 2);
	            skipAnnotation();
	            switch (typeName.value) {
	            case 'Ljava/lang/invoke/LambdaForm$Hidden;':
	                isHidden = true;
	                break;
	            case 'Lsig/sun/reflect/CallerSensitive;':
	                isCallerSensitive = true;
	                break;
	            case 'Lsig/java/lang/invoke/LambdaForm$Compiled':
	                isCompiled = true;
	                break;
	            }
	        }
	        return new this(rawBytes, isHidden, isCallerSensitive, isCompiled);
	    };
	    return RuntimeVisibleAnnotations;
	}();
	exports.RuntimeVisibleAnnotations = RuntimeVisibleAnnotations;
	var AnnotationDefault = function () {
	    function AnnotationDefault(rawBytes) {
	        this.rawBytes = rawBytes;
	    }
	    AnnotationDefault.prototype.getName = function () {
	        return 'AnnotationDefault';
	    };
	    AnnotationDefault.parse = function (byteStream, constantPool, attrLen) {
	        return new this(byteStream.read(attrLen));
	    };
	    return AnnotationDefault;
	}();
	exports.AnnotationDefault = AnnotationDefault;
	var EnclosingMethod = function () {
	    function EnclosingMethod(encClass, encMethod) {
	        this.encClass = encClass;
	        this.encMethod = encMethod;
	    }
	    EnclosingMethod.prototype.getName = function () {
	        return 'EnclosingMethod';
	    };
	    EnclosingMethod.parse = function (byteStream, constantPool) {
	        var encClass = constantPool.get(byteStream.getUint16()), methodRef = byteStream.getUint16(), encMethod = null;
	        if (methodRef > 0) {
	            encMethod = constantPool.get(methodRef);
	            assert(encMethod.getType() === enums.ConstantPoolItemType.NAME_AND_TYPE, 'Enclosing method must be a name and type info.');
	        }
	        return new this(encClass, encMethod);
	    };
	    return EnclosingMethod;
	}();
	exports.EnclosingMethod = EnclosingMethod;
	var BootstrapMethods = function () {
	    function BootstrapMethods(bootstrapMethods) {
	        this.bootstrapMethods = bootstrapMethods;
	    }
	    BootstrapMethods.prototype.getName = function () {
	        return 'BootstrapMethods';
	    };
	    BootstrapMethods.parse = function (byteStream, constantPool) {
	        var numBootstrapMethods = byteStream.getUint16(), bootstrapMethods = [];
	        for (var i = 0; i < numBootstrapMethods; i++) {
	            var methodHandle = constantPool.get(byteStream.getUint16());
	            var numArgs = byteStream.getUint16();
	            var args = [];
	            for (var j = 0; j < numArgs; j++) {
	                args.push(constantPool.get(byteStream.getUint16()));
	            }
	            bootstrapMethods.push([
	                methodHandle,
	                args
	            ]);
	        }
	        return new this(bootstrapMethods);
	    };
	    return BootstrapMethods;
	}();
	exports.BootstrapMethods = BootstrapMethods;
	var RuntimeVisibleParameterAnnotations = function () {
	    function RuntimeVisibleParameterAnnotations(rawBytes) {
	        this.rawBytes = rawBytes;
	    }
	    RuntimeVisibleParameterAnnotations.prototype.getName = function () {
	        return 'RuntimeVisibleParameterAnnotations';
	    };
	    RuntimeVisibleParameterAnnotations.parse = function (byteStream, constantPool, attrLen) {
	        return new this(byteStream.read(attrLen));
	    };
	    return RuntimeVisibleParameterAnnotations;
	}();
	exports.RuntimeVisibleParameterAnnotations = RuntimeVisibleParameterAnnotations;
	function makeAttributes(byteStream, constantPool) {
	    var attrTypes = {
	        'Code': Code,
	        'LineNumberTable': LineNumberTable,
	        'SourceFile': SourceFile,
	        'StackMapTable': StackMapTable,
	        'LocalVariableTable': LocalVariableTable,
	        'LocalVariableTypeTable': LocalVariableTypeTable,
	        'ConstantValue': ConstantValue,
	        'Exceptions': Exceptions,
	        'InnerClasses': InnerClasses,
	        'Synthetic': Synthetic,
	        'Deprecated': Deprecated,
	        'Signature': Signature,
	        'RuntimeVisibleAnnotations': RuntimeVisibleAnnotations,
	        'AnnotationDefault': AnnotationDefault,
	        'EnclosingMethod': EnclosingMethod,
	        'BootstrapMethods': BootstrapMethods,
	        'RuntimeVisibleParameterAnnotations': RuntimeVisibleParameterAnnotations
	    };
	    var numAttrs = byteStream.getUint16();
	    var attrs = [];
	    for (var i = 0; i < numAttrs; i++) {
	        var name = constantPool.get(byteStream.getUint16()).value;
	        var attrLen = byteStream.getUint32();
	        if (attrTypes[name] != null) {
	            var oldLen = byteStream.size();
	            var attr = attrTypes[name].parse(byteStream, constantPool, attrLen, name);
	            var newLen = byteStream.size();
	            assert(oldLen - newLen <= attrLen, 'A parsed attribute read beyond its data! ' + name);
	            if (oldLen - newLen !== attrLen) {
	                byteStream.skip(attrLen - oldLen + newLen);
	            }
	            attrs.push(attr);
	        } else {
	            byteStream.skip(attrLen);
	        }
	    }
	    return attrs;
	}
	exports.makeAttributes = makeAttributes;


/***/ },
/* 13 */
/***/ function(module, exports) {

	'use strict';
	function assert(assertion, msg, thread) {
	    if (!assertion) {
	        throw new Error('Assertion failed: ' + msg + '\n' + (thread ? thread.getPrintableStackTrace() : ''));
	    }
	}
	module.exports = assert;


/***/ },
/* 14 */
/***/ function(module, exports) {

	/* WEBPACK VAR INJECTION */(function(global) {'use strict';
	var toExport;
	if (typeof window !== 'undefined') {
	    toExport = window;
	} else if (typeof self !== 'undefined') {
	    toExport = self;
	} else {
	    toExport = global;
	}
	module.exports = toExport;
	
	/* WEBPACK VAR INJECTION */}.call(exports, (function() { return this; }())))

/***/ },
/* 15 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var enums = __webpack_require__(9);
	var assert = __webpack_require__(13);
	var gLong = __webpack_require__(8);
	var opcodes = __webpack_require__(16);
	var logging = __webpack_require__(17);
	var util = __webpack_require__(6);
	var ThreadStatus = enums.ThreadStatus;
	var global = __webpack_require__(14);
	if (typeof RELEASE === 'undefined')
	    global.RELEASE = false;
	var debug = logging.debug, vtrace = logging.vtrace, trace = logging.trace, maxMethodResumes = 10000, methodResumesLeft = maxMethodResumes, numSamples = 1;
	var PreAllocatedStack = function () {
	    function PreAllocatedStack(initialSize) {
	        this.curr = 0;
	        this.store = new Array(initialSize);
	    }
	    PreAllocatedStack.prototype.push = function (x) {
	        this.store[this.curr++] = x;
	    };
	    PreAllocatedStack.prototype.pushAll = function () {
	        var n = arguments.length;
	        for (var i = 0; i < n; i++) {
	            this.store[this.curr++] = arguments[i];
	        }
	    };
	    PreAllocatedStack.prototype.pushWithNull = function (x) {
	        this.store[this.curr] = x;
	        this.curr += 2;
	    };
	    PreAllocatedStack.prototype.push6 = function (x, y, z, z1, z2, z3) {
	        this.store[this.curr++] = x;
	        this.store[this.curr++] = y;
	        this.store[this.curr++] = z;
	        this.store[this.curr++] = z1;
	        this.store[this.curr++] = z2;
	        this.store[this.curr++] = z3;
	    };
	    PreAllocatedStack.prototype.swap = function () {
	        var tmp = this.store[this.curr - 1];
	        this.store[this.curr - 1] = this.store[this.curr - 2];
	        this.store[this.curr - 2] = tmp;
	    };
	    PreAllocatedStack.prototype.dup = function () {
	        this.store[this.curr] = this.store[this.curr - 1];
	        this.curr++;
	    };
	    PreAllocatedStack.prototype.dup2 = function () {
	        this.store[this.curr] = this.store[this.curr - 2];
	        this.store[this.curr + 1] = this.store[this.curr - 1];
	        this.curr += 2;
	    };
	    PreAllocatedStack.prototype.dup_x1 = function () {
	        var v1 = this.store[this.curr - 1];
	        this.store[this.curr - 1] = this.store[this.curr - 2];
	        this.store[this.curr] = v1;
	        this.store[this.curr - 2] = v1;
	        this.curr++;
	    };
	    PreAllocatedStack.prototype.dup_x2 = function () {
	        var v1 = this.store[this.curr - 1];
	        this.store[this.curr - 1] = this.store[this.curr - 2];
	        this.store[this.curr - 2] = this.store[this.curr - 3];
	        this.store[this.curr] = v1;
	        this.store[this.curr - 3] = v1;
	        this.curr++;
	    };
	    PreAllocatedStack.prototype.dup2_x1 = function () {
	        var v1 = this.store[this.curr - 1];
	        var v2 = this.store[this.curr - 2];
	        this.store[this.curr] = v2;
	        this.store[this.curr + 1] = v1;
	        this.store[this.curr - 1] = this.store[this.curr - 3];
	        this.store[this.curr - 2] = v1;
	        this.store[this.curr - 3] = v2;
	        this.curr += 2;
	    };
	    PreAllocatedStack.prototype.pop = function () {
	        return this.store[--this.curr];
	    };
	    PreAllocatedStack.prototype.pop2 = function () {
	        this.curr -= 2;
	        return this.store[this.curr];
	    };
	    PreAllocatedStack.prototype.bottom = function () {
	        return this.store[0];
	    };
	    PreAllocatedStack.prototype.top = function () {
	        return this.store[this.curr - 1];
	    };
	    PreAllocatedStack.prototype.fromTop = function (n) {
	        return this.store[this.curr - (n + 1)];
	    };
	    PreAllocatedStack.prototype.sliceFromBottom = function (n) {
	        return this.store.slice(n, this.curr);
	    };
	    PreAllocatedStack.prototype.sliceFromTop = function (n) {
	        return this.store.slice(this.curr - n, this.curr);
	    };
	    PreAllocatedStack.prototype.dropFromTop = function (n) {
	        this.curr -= n;
	    };
	    PreAllocatedStack.prototype.sliceAndDropFromTop = function (n) {
	        var curr = this.curr;
	        this.curr -= n;
	        return this.store.slice(curr - n, curr);
	    };
	    PreAllocatedStack.prototype.getRaw = function () {
	        return this.store.slice(0, this.curr);
	    };
	    PreAllocatedStack.prototype.clear = function () {
	        this.curr = 0;
	    };
	    return PreAllocatedStack;
	}();
	exports.PreAllocatedStack = PreAllocatedStack;
	var jitUtil = {
	    isNull: opcodes.isNull,
	    resolveCPItem: opcodes.resolveCPItem,
	    throwException: opcodes.throwException,
	    gLong: gLong,
	    float2int: util.float2int,
	    wrapFloat: util.wrapFloat,
	    Constants: enums.Constants
	};
	var BytecodeStackFrame = function () {
	    function BytecodeStackFrame(method, args) {
	        this.pc = 0;
	        this.returnToThreadLoop = false;
	        this.lockedMethodLock = false;
	        this.type = enums.StackFrameType.BYTECODE;
	        this.method = method;
	        method.incrBBEntries();
	        assert(!method.accessFlags.isNative(), 'Cannot run a native method using a BytecodeStackFrame.');
	        assert(!method.accessFlags.isAbstract(), 'Cannot run an abstract method!');
	        this.locals = args;
	        this.opStack = new PreAllocatedStack(method.getCodeAttribute().getMaxStack());
	    }
	    BytecodeStackFrame.prototype.run = function (thread) {
	        var _this = this;
	        var method = this.method, code = this.method.getCodeAttribute().getCode(), opcodeTable = opcodes.LookupTable;
	        if (!RELEASE && logging.log_level >= logging.TRACE) {
	            if (this.pc === 0) {
	                ;
	            } else {
	                ;
	            }
	            ;
	        }
	        if (method.accessFlags.isSynchronized() && !this.lockedMethodLock) {
	            this.lockedMethodLock = method.methodLock(thread, this).enter(thread, function () {
	                _this.lockedMethodLock = true;
	            });
	            if (!this.lockedMethodLock) {
	                assert(thread.getStatus() === ThreadStatus.BLOCKED, 'Failed to enter a monitor. Thread must be BLOCKED.');
	                return;
	            }
	        }
	        this.returnToThreadLoop = false;
	        if (thread.getJVM().isJITDisabled()) {
	            while (!this.returnToThreadLoop) {
	                var opCode = code.readUInt8(this.pc);
	                if (!RELEASE && logging.log_level === logging.VTRACE) {
	                    ;
	                }
	                opcodeTable[opCode](thread, this, code);
	                if (!RELEASE && !this.returnToThreadLoop && logging.log_level === logging.VTRACE) {
	                    ;
	                }
	            }
	        } else {
	            while (!this.returnToThreadLoop) {
	                var op = method.getOp(this.pc, code, thread);
	                if (typeof op === 'function') {
	                    if (!RELEASE && logging.log_level === logging.VTRACE) {
	                        ;
	                    }
	                    op(this, thread, jitUtil);
	                } else {
	                    if (!RELEASE && logging.log_level === logging.VTRACE) {
	                        ;
	                    }
	                    opcodeTable[op](thread, this, code);
	                }
	                if (!RELEASE && !this.returnToThreadLoop && logging.log_level === logging.VTRACE) {
	                    ;
	                }
	            }
	        }
	    };
	    BytecodeStackFrame.prototype.scheduleResume = function (thread, rv, rv2) {
	        var prevOp = this.method.getCodeAttribute().getCode().readUInt8(this.pc);
	        switch (prevOp) {
	        case enums.OpCode.INVOKEINTERFACE:
	        case enums.OpCode.INVOKEINTERFACE_FAST:
	            this.pc += 5;
	            break;
	        case enums.OpCode.INVOKESPECIAL:
	        case enums.OpCode.INVOKESTATIC:
	        case enums.OpCode.INVOKEVIRTUAL:
	        case enums.OpCode.INVOKESTATIC_FAST:
	        case enums.OpCode.INVOKENONVIRTUAL_FAST:
	        case enums.OpCode.INVOKEVIRTUAL_FAST:
	        case enums.OpCode.INVOKEHANDLE:
	        case enums.OpCode.INVOKEBASIC:
	        case enums.OpCode.LINKTOSPECIAL:
	        case enums.OpCode.LINKTOVIRTUAL:
	        case enums.OpCode.INVOKEDYNAMIC:
	        case enums.OpCode.INVOKEDYNAMIC_FAST:
	            this.pc += 3;
	            break;
	        default:
	            assert(false, 'Resuming from a non-invoke opcode! Opcode: ' + enums.OpCode[prevOp] + ' [' + prevOp + ']');
	            break;
	        }
	        if (rv !== undefined) {
	            this.opStack.push(rv);
	        }
	        if (rv2 !== undefined) {
	            this.opStack.push(rv2);
	        }
	    };
	    BytecodeStackFrame.prototype.scheduleException = function (thread, e) {
	        var codeAttr = this.method.getCodeAttribute(), pc = this.pc, method = this.method, exceptionHandlers = codeAttr.exceptionHandlers, ecls = e.getClass(), handler;
	        for (var i = 0; i < exceptionHandlers.length; i++) {
	            var eh = exceptionHandlers[i];
	            if (eh.startPC <= pc && pc < eh.endPC) {
	                if (eh.catchType === '<any>') {
	                    handler = eh;
	                    break;
	                } else {
	                    var resolvedCatchType = method.cls.getLoader().getResolvedClass(eh.catchType);
	                    if (resolvedCatchType != null) {
	                        if (ecls.isCastable(resolvedCatchType)) {
	                            handler = eh;
	                            break;
	                        }
	                    } else {
	                        ;
	                        var handlerClasses = [];
	                        for (var i_1 = 0; i_1 < exceptionHandlers.length; i_1++) {
	                            var handler_1 = exceptionHandlers[i_1];
	                            if (handler_1.catchType !== '<any>') {
	                                handlerClasses.push(handler_1.catchType);
	                            }
	                        }
	                        ;
	                        thread.setStatus(ThreadStatus.ASYNC_WAITING);
	                        method.cls.getLoader().resolveClasses(thread, handlerClasses, function (classes) {
	                            if (classes !== null) {
	                                ;
	                                thread.throwException(e);
	                            }
	                        });
	                        return true;
	                    }
	                }
	            }
	        }
	        if (handler != null) {
	            ;
	            this.opStack.clear();
	            this.opStack.push(e);
	            this.pc = handler.handlerPC;
	            return true;
	        } else {
	            ;
	            if (method.accessFlags.isSynchronized()) {
	                method.methodLock(thread, this).exit(thread);
	            }
	            return false;
	        }
	    };
	    BytecodeStackFrame.prototype.getLoader = function () {
	        return this.method.cls.getLoader();
	    };
	    BytecodeStackFrame.prototype.getStackTraceFrame = function () {
	        return {
	            method: this.method,
	            pc: this.pc,
	            stack: this.opStack.sliceFromBottom(0),
	            locals: this.locals.slice(0)
	        };
	    };
	    return BytecodeStackFrame;
	}();
	exports.BytecodeStackFrame = BytecodeStackFrame;
	var NativeStackFrame = function () {
	    function NativeStackFrame(method, args) {
	        this.type = enums.StackFrameType.NATIVE;
	        this.method = method;
	        this.args = args;
	        assert(method.accessFlags.isNative());
	        this.nativeMethod = method.getNativeFunction();
	    }
	    NativeStackFrame.prototype.run = function (thread) {
	        ;
	        var rv = this.nativeMethod.apply(null, this.method.convertArgs(thread, this.args));
	        if (thread.getStatus() === ThreadStatus.RUNNABLE && thread.currentMethod() === this.method) {
	            var returnType = this.method.returnType;
	            switch (returnType) {
	            case 'J':
	            case 'D':
	                thread.asyncReturn(rv, null);
	                break;
	            case 'Z':
	                thread.asyncReturn(rv ? 1 : 0);
	                break;
	            default:
	                thread.asyncReturn(rv);
	                break;
	            }
	        }
	    };
	    NativeStackFrame.prototype.scheduleResume = function (thread, rv, rv2) {
	    };
	    NativeStackFrame.prototype.scheduleException = function (thread, e) {
	        return false;
	    };
	    NativeStackFrame.prototype.getStackTraceFrame = function () {
	        return {
	            method: this.method,
	            pc: -1,
	            stack: [],
	            locals: []
	        };
	    };
	    NativeStackFrame.prototype.getLoader = function () {
	        return this.method.cls.getLoader();
	    };
	    return NativeStackFrame;
	}();
	exports.NativeStackFrame = NativeStackFrame;
	var InternalStackFrame = function () {
	    function InternalStackFrame(cb) {
	        this.isException = false;
	        this.type = enums.StackFrameType.INTERNAL;
	        this.cb = cb;
	    }
	    InternalStackFrame.prototype.run = function (thread) {
	        thread.framePop();
	        thread.setStatus(ThreadStatus.ASYNC_WAITING);
	        if (this.isException) {
	            this.cb(this.val);
	        } else {
	            this.cb(null, this.val);
	        }
	    };
	    InternalStackFrame.prototype.scheduleResume = function (thread, rv) {
	        this.isException = false;
	        this.val = rv;
	    };
	    InternalStackFrame.prototype.scheduleException = function (thread, e) {
	        this.isException = true;
	        this.val = e;
	        return true;
	    };
	    InternalStackFrame.prototype.getStackTraceFrame = function () {
	        return null;
	    };
	    InternalStackFrame.prototype.getLoader = function () {
	        throw new Error('Internal stack frames have no loader.');
	    };
	    return InternalStackFrame;
	}();
	exports.InternalStackFrame = InternalStackFrame;
	var JVMThread = function () {
	    function JVMThread(jvm, tpool, threadObj) {
	        this.status = ThreadStatus.NEW;
	        this.stack = [];
	        this.interrupted = false;
	        this.monitor = null;
	        this.jvm = jvm;
	        this.bsCl = jvm.getBootstrapClassLoader();
	        this.tpool = tpool;
	        this.jvmThreadObj = threadObj;
	    }
	    JVMThread.prototype.getJVMObject = function () {
	        return this.jvmThreadObj;
	    };
	    JVMThread.prototype.isDaemon = function () {
	        return this.jvmThreadObj['java/lang/Thread/daemon'] !== 0;
	    };
	    JVMThread.prototype.getPriority = function () {
	        return this.jvmThreadObj['java/lang/Thread/priority'];
	    };
	    JVMThread.prototype.setJVMObject = function (obj) {
	        obj['java/lang/Thread/threadStatus'] = this.jvmThreadObj['java/lang/Thread/threadStatus'];
	        this.jvmThreadObj = obj;
	    };
	    JVMThread.prototype.getRef = function () {
	        return this.jvmThreadObj.ref;
	    };
	    JVMThread.prototype.isInterrupted = function () {
	        return this.interrupted;
	    };
	    JVMThread.prototype.currentMethod = function () {
	        var stack = this.stack, idx = stack.length, method;
	        while (--idx >= 0) {
	            method = stack[idx].getStackTraceFrame().method;
	            if (method !== null) {
	                return method;
	            }
	        }
	        return null;
	    };
	    JVMThread.prototype.setInterrupted = function (interrupted) {
	        this.interrupted = interrupted;
	    };
	    JVMThread.prototype.getBsCl = function () {
	        return this.bsCl;
	    };
	    JVMThread.prototype.getLoader = function () {
	        var loader = this.stack[this.stack.length - 1].getLoader();
	        if (loader) {
	            return loader;
	        } else {
	            var len = this.stack.length;
	            for (var i = 2; i <= len; i++) {
	                loader = this.stack[len - i].getLoader();
	                if (loader) {
	                    return loader;
	                }
	            }
	            throw new Error('Unable to find loader.');
	        }
	    };
	    JVMThread.prototype.import = function (names, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        var loader = this.getLoader();
	        this.setStatus(ThreadStatus.ASYNC_WAITING);
	        if (Array.isArray(names)) {
	            var rv_1 = [];
	            util.asyncForEach(names, function (name, nextItem) {
	                _this._import(name, loader, function (cons) {
	                    rv_1.push(cons);
	                    nextItem();
	                }, explicit);
	            }, function (e) {
	                cb(rv_1);
	            });
	        } else {
	            this._import(names, loader, cb, explicit);
	        }
	    };
	    JVMThread.prototype._import = function (name, loader, cb, explicit) {
	        var _this = this;
	        var cls = loader.getInitializedClass(this, name);
	        if (cls) {
	            setImmediate(function () {
	                return cb(cls.getConstructor(_this));
	            });
	        } else {
	            loader.initializeClass(this, name, function (cdata) {
	                if (cdata) {
	                    cb(cdata.getConstructor(_this));
	                }
	            }, explicit);
	        }
	    };
	    JVMThread.prototype.getJVM = function () {
	        return this.jvm;
	    };
	    JVMThread.prototype.getThreadPool = function () {
	        return this.tpool;
	    };
	    JVMThread.prototype.getStackTrace = function () {
	        var trace = [], i, frame;
	        for (i = 0; i < this.stack.length; i++) {
	            frame = this.stack[i].getStackTraceFrame();
	            if (frame != null) {
	                trace.push(frame);
	            }
	        }
	        return trace;
	    };
	    JVMThread.prototype.getPrintableStackTrace = function () {
	        var rv = '';
	        this.getStackTrace().reverse().forEach(function (trace) {
	            rv += '\tat ' + util.ext_classname(trace.method.cls.getInternalName()) + '::' + trace.method.name + '(';
	            if (trace.pc >= 0) {
	                var code = trace.method.getCodeAttribute();
	                var table = code.getAttribute('LineNumberTable');
	                var srcAttr = trace.method.cls.getAttribute('SourceFile');
	                if (srcAttr != null) {
	                    rv += srcAttr.filename;
	                } else {
	                    rv += 'unknown';
	                }
	                if (table != null) {
	                    var lineNumber = table.getLineNumber(trace.pc);
	                    rv += ':' + lineNumber;
	                    rv += ' Bytecode offset: ' + trace.pc;
	                }
	            } else {
	                rv += 'native';
	            }
	            rv += ')\n';
	        });
	        return rv;
	    };
	    JVMThread.prototype.run = function () {
	        var stack = this.stack, startTime = new Date().getTime();
	        methodResumesLeft = maxMethodResumes;
	        while (this.status === ThreadStatus.RUNNABLE && stack.length > 0) {
	            var sf = stack[stack.length - 1];
	            if (!RELEASE) {
	                if (sf.type === enums.StackFrameType.BYTECODE && this.jvm.shouldVtrace(sf.method.fullSignature)) {
	                    var oldLevel = logging.log_level;
	                    logging.log_level = logging.VTRACE;
	                    sf.run(this);
	                    logging.log_level = oldLevel;
	                } else {
	                    sf.run(this);
	                }
	            } else {
	                sf.run(this);
	            }
	            if (--methodResumesLeft === 0) {
	                var endTime = new Date().getTime();
	                var duration = endTime - startTime;
	                var estMaxMethodResumes = maxMethodResumes / duration * this.jvm.getResponsiveness() | 0;
	                maxMethodResumes = (estMaxMethodResumes + numSamples * maxMethodResumes) / (numSamples + 1) | 0;
	                if (maxMethodResumes <= 0) {
	                    maxMethodResumes = 10;
	                }
	                ;
	                numSamples++;
	                this.tpool.quantumOver(this);
	                break;
	            }
	        }
	        if (stack.length === 0) {
	            this.setStatus(ThreadStatus.TERMINATED);
	        }
	    };
	    JVMThread.prototype.sanityCheck = function () {
	        switch (this.status) {
	        case ThreadStatus.NEW:
	            return true;
	        case ThreadStatus.RUNNABLE:
	            assert(this.stack.length > 0, 'A runnable thread must not have an empty stack.');
	            return true;
	        case ThreadStatus.TIMED_WAITING:
	            assert(this.monitor != null && this.monitor.isTimedWaiting(this), 'A timed waiting thread must be waiting on a monitor.');
	            return true;
	        case ThreadStatus.WAITING:
	            assert(this.monitor != null && this.monitor.isWaiting(this), 'A waiting thread must be waiting on a monitor.');
	            return true;
	        case ThreadStatus.BLOCKED:
	        case ThreadStatus.UNINTERRUPTABLY_BLOCKED:
	            assert(this.monitor != null && this.monitor.isBlocked(this), 'A blocked thread must be blocked on a monitor');
	            return true;
	        case ThreadStatus.ASYNC_WAITING:
	            return true;
	        case ThreadStatus.TERMINATED:
	            assert(this.stack.length === 0, 'A terminated thread must have an empty stack.');
	            return true;
	        case ThreadStatus.PARKED:
	            assert(this.jvm.getParker().isParked(this), 'A parked thread must be parked.');
	            return true;
	        default:
	            return false;
	        }
	    };
	    JVMThread.prototype.rawSetStatus = function (newStatus) {
	        var jvmNewStatus = 0, oldStatus = this.status;
	        if (logging.log_level === logging.VTRACE) {
	            ;
	        }
	        assert(validateThreadTransition(oldStatus, newStatus), 'Invalid thread transition: ' + ThreadStatus[oldStatus] + ' => ' + ThreadStatus[newStatus]);
	        this.status = newStatus;
	        switch (newStatus) {
	        case ThreadStatus.NEW:
	            jvmNewStatus |= enums.JVMTIThreadState.ALIVE;
	            break;
	        case ThreadStatus.RUNNABLE:
	            jvmNewStatus |= enums.JVMTIThreadState.RUNNABLE;
	            break;
	        case ThreadStatus.BLOCKED:
	        case ThreadStatus.UNINTERRUPTABLY_BLOCKED:
	            jvmNewStatus |= enums.JVMTIThreadState.BLOCKED_ON_MONITOR_ENTER;
	            break;
	        case ThreadStatus.WAITING:
	        case ThreadStatus.ASYNC_WAITING:
	        case ThreadStatus.PARKED:
	            jvmNewStatus |= enums.JVMTIThreadState.WAITING_INDEFINITELY;
	            break;
	        case ThreadStatus.TIMED_WAITING:
	            jvmNewStatus |= enums.JVMTIThreadState.WAITING_WITH_TIMEOUT;
	            break;
	        case ThreadStatus.TERMINATED:
	            jvmNewStatus |= enums.JVMTIThreadState.TERMINATED;
	            break;
	        default:
	            jvmNewStatus = enums.JVMTIThreadState.RUNNABLE;
	            break;
	        }
	        this.jvmThreadObj['java/lang/Thread/threadStatus'] = jvmNewStatus;
	        this.tpool.statusChange(this, oldStatus, this.status);
	    };
	    JVMThread.prototype.setStatus = function (status, monitor) {
	        if (monitor === void 0) {
	            monitor = null;
	        }
	        if (this.status !== status) {
	            var oldStatus = this.status;
	            this.monitor = monitor;
	            if (status !== ThreadStatus.TERMINATED) {
	                this.rawSetStatus(status);
	            } else {
	                this.exit();
	            }
	            assert(this.sanityCheck(), 'Invalid thread status.');
	        }
	    };
	    JVMThread.prototype.exit = function () {
	        var _this = this;
	        var monitor = this.jvmThreadObj.getMonitor();
	        if (monitor.isBlocked(this) || monitor.getOwner() === this || this.status === ThreadStatus.TERMINATED) {
	            return;
	        }
	        if (this.stack.length === 0) {
	            this.setStatus(ThreadStatus.ASYNC_WAITING);
	            if (this.jvm.hasVMBooted()) {
	                ;
	                var phase2 = function () {
	                    ;
	                    _this.jvmThreadObj['exit()V'](_this, null, function (e) {
	                        monitor.notifyAll(_this);
	                        monitor.exit(_this);
	                        ;
	                        _this.rawSetStatus(ThreadStatus.TERMINATED);
	                    });
	                };
	                if (monitor.enter(this, phase2)) {
	                    phase2();
	                }
	            } else {
	                ;
	            }
	        } else {
	            while (this.stack.length > 0) {
	                this.stack.pop();
	            }
	            ;
	            this.rawSetStatus(ThreadStatus.TERMINATED);
	        }
	    };
	    JVMThread.prototype.signalPriorityChange = function () {
	        this.tpool.priorityChange(this);
	    };
	    JVMThread.prototype.getMonitorBlock = function () {
	        return this.monitor;
	    };
	    JVMThread.prototype.getStatus = function () {
	        return this.status;
	    };
	    JVMThread.prototype.asyncReturn = function (rv, rv2) {
	        var stack = this.stack;
	        assert(this.status === ThreadStatus.RUNNABLE || this.status === ThreadStatus.ASYNC_WAITING);
	        assert(typeof rv !== 'boolean' && rv2 == null);
	        var frame = stack.pop();
	        if (frame.type != enums.StackFrameType.INTERNAL) {
	            var frameCast = frame;
	            if (frame.type === enums.StackFrameType.BYTECODE) {
	                ;
	            }
	            ;
	            assert(validateReturnValue(this, frameCast.method, frameCast.method.returnType, this.bsCl, frameCast.method.cls.getLoader(), rv, rv2), 'Invalid return value for method ' + frameCast.method.getFullSignature());
	        }
	        var idx = stack.length - 1;
	        if (idx >= 0) {
	            stack[idx].scheduleResume(this, rv, rv2);
	        }
	        this.setStatus(ThreadStatus.RUNNABLE);
	    };
	    JVMThread.prototype.framePop = function () {
	        this.stack.pop();
	    };
	    JVMThread.prototype.throwException = function (exception) {
	        assert(this.status === ThreadStatus.RUNNABLE || this.status === ThreadStatus.ASYNC_WAITING, 'Tried to throw exception while thread was in state ' + ThreadStatus[this.status]);
	        var stack = this.stack, idx = stack.length - 1;
	        if (idx >= 0) {
	            if (stack[idx].type === enums.StackFrameType.INTERNAL) {
	                stack.pop();
	                idx--;
	            }
	            this.setStatus(ThreadStatus.RUNNABLE);
	            while (stack.length > 0 && !stack[idx].scheduleException(this, exception)) {
	                stack.pop();
	                idx--;
	            }
	        }
	        if (stack.length === 0) {
	            this.handleUncaughtException(exception);
	        }
	    };
	    JVMThread.prototype.throwNewException = function (clsName, msg) {
	        var _this = this;
	        var cls = this.bsCl.getInitializedClass(this, clsName), throwException = function () {
	                var eCons = cls.getConstructor(_this), e = new eCons(_this);
	                e['<init>(Ljava/lang/String;)V'](_this, [util.initString(_this.bsCl, msg)], function (err) {
	                    if (err) {
	                        _this.throwException(err);
	                    } else {
	                        _this.throwException(e);
	                    }
	                });
	            };
	        if (cls != null) {
	            throwException();
	        } else {
	            this.setStatus(ThreadStatus.ASYNC_WAITING);
	            this.bsCl.initializeClass(this, clsName, function (cdata) {
	                if (cdata != null) {
	                    cls = cdata;
	                    throwException();
	                }
	            }, false);
	        }
	    };
	    JVMThread.prototype.handleUncaughtException = function (exception) {
	        this.jvmThreadObj['dispatchUncaughtException(Ljava/lang/Throwable;)V'](this, [exception]);
	    };
	    JVMThread.prototype.close = function () {
	        this.jvm = null;
	    };
	    return JVMThread;
	}();
	exports.JVMThread = JVMThread;
	exports.validTransitions = {};
	exports.validTransitions[ThreadStatus.NEW] = {};
	exports.validTransitions[ThreadStatus.NEW][ThreadStatus.RUNNABLE] = 'RunMethod invoked on new thread';
	exports.validTransitions[ThreadStatus.NEW][ThreadStatus.ASYNC_WAITING] = '[JVM bootup only] Internal operation occurs on new thread';
	exports.validTransitions[ThreadStatus.NEW][ThreadStatus.TERMINATED] = '[JVM halt0 only] When the JVM shuts down, it terminates all threads, including those that have never been run.';
	exports.validTransitions[ThreadStatus.ASYNC_WAITING] = {};
	exports.validTransitions[ThreadStatus.ASYNC_WAITING][ThreadStatus.RUNNABLE] = 'Async operation completes';
	exports.validTransitions[ThreadStatus.ASYNC_WAITING][ThreadStatus.TERMINATED] = 'RunMethod completes and callstack is empty';
	exports.validTransitions[ThreadStatus.BLOCKED] = {};
	exports.validTransitions[ThreadStatus.BLOCKED][ThreadStatus.RUNNABLE] = 'Acquires monitor, or is interrupted';
	exports.validTransitions[ThreadStatus.BLOCKED][ThreadStatus.TERMINATED] = 'Thread is terminated whilst blocked.';
	exports.validTransitions[ThreadStatus.PARKED] = {};
	exports.validTransitions[ThreadStatus.PARKED][ThreadStatus.ASYNC_WAITING] = 'Balancing unpark, or is interrupted';
	exports.validTransitions[ThreadStatus.PARKED][ThreadStatus.TERMINATED] = 'Thread is terminated whilst parked.';
	exports.validTransitions[ThreadStatus.RUNNABLE] = {};
	exports.validTransitions[ThreadStatus.RUNNABLE][ThreadStatus.ASYNC_WAITING] = 'Thread performs an asynchronous JavaScript operation';
	exports.validTransitions[ThreadStatus.RUNNABLE][ThreadStatus.TERMINATED] = 'Callstack is empty';
	exports.validTransitions[ThreadStatus.RUNNABLE][ThreadStatus.BLOCKED] = 'Thread waits to acquire monitor';
	exports.validTransitions[ThreadStatus.RUNNABLE][ThreadStatus.WAITING] = 'Thread waits on monitor (Object.wait)';
	exports.validTransitions[ThreadStatus.RUNNABLE][ThreadStatus.TIMED_WAITING] = 'Thread waits on monitor with timeout (Object.wait)';
	exports.validTransitions[ThreadStatus.RUNNABLE][ThreadStatus.PARKED] = 'Thread parks itself';
	exports.validTransitions[ThreadStatus.TERMINATED] = {};
	exports.validTransitions[ThreadStatus.TERMINATED][ThreadStatus.NEW] = 'Thread is resurrected for re-use';
	exports.validTransitions[ThreadStatus.TERMINATED][ThreadStatus.RUNNABLE] = 'Thread is resurrected for re-use';
	exports.validTransitions[ThreadStatus.TERMINATED][ThreadStatus.ASYNC_WAITING] = '[JVM Bootup] Thread is resurrected for internal operation';
	exports.validTransitions[ThreadStatus.TIMED_WAITING] = {};
	exports.validTransitions[ThreadStatus.TIMED_WAITING][ThreadStatus.RUNNABLE] = 'Timer expires, or thread is interrupted, and thread immediately acquires lock';
	exports.validTransitions[ThreadStatus.TIMED_WAITING][ThreadStatus.UNINTERRUPTABLY_BLOCKED] = 'Thread is interrupted or notified, or timer expires, and lock already owned';
	exports.validTransitions[ThreadStatus.TIMED_WAITING][ThreadStatus.TERMINATED] = 'Thread is terminated whilst waiting.';
	exports.validTransitions[ThreadStatus.UNINTERRUPTABLY_BLOCKED] = {};
	exports.validTransitions[ThreadStatus.UNINTERRUPTABLY_BLOCKED][ThreadStatus.RUNNABLE] = 'Thread acquires monitor';
	exports.validTransitions[ThreadStatus.UNINTERRUPTABLY_BLOCKED][ThreadStatus.TERMINATED] = 'Thread is terminated whilst blocked.';
	exports.validTransitions[ThreadStatus.WAITING] = {};
	exports.validTransitions[ThreadStatus.WAITING][ThreadStatus.RUNNABLE] = 'Thread is interrupted, and immediately acquires lock';
	exports.validTransitions[ThreadStatus.WAITING][ThreadStatus.UNINTERRUPTABLY_BLOCKED] = 'Thread is notified or interrupted, and does not immediately acquire lock';
	exports.validTransitions[ThreadStatus.WAITING][ThreadStatus.TERMINATED] = 'Thread is terminated whilst waiting.';
	function validateThreadTransition(oldStatus, newStatus) {
	    var rv = exports.validTransitions.hasOwnProperty('' + oldStatus) && exports.validTransitions[oldStatus].hasOwnProperty('' + newStatus);
	    return rv;
	}
	function validateReturnValue(thread, method, returnType, bsCl, cl, rv1, rv2) {
	    if (method.fullSignature === 'java/lang/invoke/MethodHandle/invokeBasic([Ljava/lang/Object;)Ljava/lang/Object;') {
	        return true;
	    }
	    var cls;
	    if (util.is_primitive_type(returnType)) {
	        switch (returnType) {
	        case 'Z':
	            assert(rv2 === undefined, 'Second return value must be undefined for Boolean type.');
	            assert(rv1 === 1 || rv1 === 0, 'Booleans must be 0 or 1.');
	            break;
	        case 'B':
	            assert(rv2 === undefined, 'Second return value must be undefined for Byte type.');
	            assert(rv1 <= 127 && rv1 >= -128, 'Byte value for method ' + method.name + ' is out of bounds: ' + rv1);
	            break;
	        case 'C':
	            assert(rv2 === undefined, 'Second return value must be undefined for Character type.');
	            assert(rv1 <= 65535 && rv1 >= 0, 'Character value is out of bounds: ' + rv1);
	            break;
	        case 'S':
	            assert(rv2 === undefined, 'Second return value must be undefined for Short type.');
	            assert(rv1 <= 32767 && rv1 >= -32768, 'Short value is out of bounds: ' + rv1);
	            break;
	        case 'I':
	            assert(rv2 === undefined, 'Second return value must be undefined for Int type.');
	            assert(rv1 <= 2147483647 && rv1 >= -2147483648, 'Int value is out of bounds: ' + rv1);
	            break;
	        case 'J':
	            assert(rv2 === null, 'Second return value must be NULL for Long type.');
	            assert(rv1.lessThanOrEqual(gLong.MAX_VALUE) && rv1.greaterThanOrEqual(gLong.MIN_VALUE), 'Long value is out of bounds: ' + rv1);
	            break;
	        case 'F':
	            assert(rv2 === undefined, 'Second return value must be undefined for Float type.');
	            assert(util.wrapFloat(rv1) === rv1 || isNaN(rv1) && isNaN(util.wrapFloat(rv1)), 'Float value is out of bounds: ' + rv1);
	            break;
	        case 'D':
	            assert(rv2 === null, 'Second return value must be NULL for Double type.');
	            assert(typeof rv1 === 'number', 'Invalid double value: ' + rv1);
	            break;
	        case 'V':
	            assert(rv1 === undefined && rv2 === undefined, 'Return values must be undefined for Void type');
	            break;
	        }
	    } else if (util.is_array_type(returnType)) {
	        assert(rv2 === undefined, 'Second return value must be undefined for array type.');
	        assert(rv1 === null || typeof rv1 === 'object' && typeof rv1['getClass'] === 'function', 'Invalid array object: ' + rv1);
	        if (rv1 != null) {
	            cls = assertClassInitializedOrResolved(thread, cl, returnType, true);
	            assert(rv1.getClass().isCastable(cls), 'Return value of type ' + rv1.getClass().getInternalName() + ' unable to be cast to return type ' + returnType + '.');
	        }
	    } else {
	        assert(util.is_reference_type(returnType), 'Invalid reference type: ' + returnType);
	        assert(rv2 === undefined, 'Second return value must be undefined for reference type.');
	        assert(rv1 === null || rv1 instanceof bsCl.getInitializedClass(thread, 'Ljava/lang/Object;').getConstructor(thread), 'Reference return type must be an instance of Object; value: ' + rv1);
	        if (rv1 != null) {
	            cls = assertClassInitializedOrResolved(thread, cl, returnType, false);
	            if (!cls.accessFlags.isInterface()) {
	                assertClassInitializedOrResolved(thread, cl, returnType, true);
	            }
	            assert(rv1.getClass().isCastable(cls), 'Unable to cast ' + rv1.getClass().getInternalName() + ' to ' + returnType + '.');
	        }
	    }
	    return true;
	}
	function assertClassInitializedOrResolved(thread, cl, type, initialized) {
	    var cls = null;
	    while (cls === null) {
	        cls = initialized ? cl.getInitializedClass(thread, type) : cl.getResolvedClass(type);
	        if (cl.getLoaderObject() !== null) {
	            if (cl.getLoaderObject()['java/lang/ClassLoader/parent'] === null) {
	                cl = thread.getBsCl();
	            } else {
	                cl = cl.getLoaderObject()['java/lang/ClassLoader/parent'].$loader;
	            }
	        } else {
	            assert(cls !== null, 'Unable to get initialized class for type ' + type + '.');
	        }
	    }
	    return cls;
	}
	function printConstantPoolItem(cpi) {
	    switch (cpi.getType()) {
	    case enums.ConstantPoolItemType.METHODREF:
	        var cpiMR = cpi;
	        return util.ext_classname(cpiMR.classInfo.name) + '.' + cpiMR.signature;
	    case enums.ConstantPoolItemType.INTERFACE_METHODREF:
	        var cpiIM = cpi;
	        return util.ext_classname(cpiIM.classInfo.name) + '.' + cpiIM.signature;
	    case enums.ConstantPoolItemType.FIELDREF:
	        var cpiFR = cpi;
	        return util.ext_classname(cpiFR.classInfo.name) + '.' + cpiFR.nameAndTypeInfo.name + ':' + util.ext_classname(cpiFR.nameAndTypeInfo.descriptor);
	    case enums.ConstantPoolItemType.NAME_AND_TYPE:
	        var cpiNAT = cpi;
	        return cpiNAT.name + ':' + cpiNAT.descriptor;
	    case enums.ConstantPoolItemType.CLASS:
	        var cpiClass = cpi;
	        return util.ext_classname(cpiClass.name);
	    default:
	        return logging.debug_var(cpi.value);
	    }
	}
	exports.OpcodeLayoutPrinters = {};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.OPCODE_ONLY] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase();
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.CONSTANT_POOL] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + printConstantPoolItem(method.cls.constantPool.get(code.readUInt16BE(pc + 1)));
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.CONSTANT_POOL_UINT8] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + printConstantPoolItem(method.cls.constantPool.get(code.readUInt8(pc + 1)));
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.CONSTANT_POOL_AND_UINT8_VALUE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + printConstantPoolItem(method.cls.constantPool.get(code.readUInt16BE(pc + 1))) + ' ' + code.readUInt8(pc + 3);
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.UINT8_VALUE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + code.readUInt8(pc + 1);
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.UINT8_AND_INT8_VALUE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + code.readUInt8(pc + 1) + ' ' + code.readInt8(pc + 2);
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.INT8_VALUE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + code.readInt8(pc + 1);
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.INT16_VALUE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + code.readInt16BE(pc + 1);
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.INT32_VALUE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + code.readInt32BE(pc + 1);
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.ARRAY_TYPE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase() + ' ' + opcodes.ArrayTypes[code.readUInt8(pc + 1)];
	};
	exports.OpcodeLayoutPrinters[enums.OpcodeLayoutType.WIDE] = function (method, code, pc) {
	    return enums.OpCode[code.readUInt8(pc)].toLowerCase();
	};
	function annotateOpcode(op, method, code, pc) {
	    return exports.OpcodeLayoutPrinters[enums.OpcodeLayouts[op]](method, code, pc);
	}
	exports.annotateOpcode = annotateOpcode;


/***/ },
/* 16 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var gLong = __webpack_require__(8);
	var util = __webpack_require__(6);
	var enums = __webpack_require__(9);
	var assert = __webpack_require__(13);
	function isNull(thread, frame, obj) {
	    if (obj == null) {
	        throwException(thread, frame, 'Ljava/lang/NullPointerException;', '');
	        return true;
	    }
	    return false;
	}
	exports.isNull = isNull;
	function pop2(opStack) {
	    opStack.pop();
	    return opStack.pop();
	}
	exports.pop2 = pop2;
	function resolveCPItem(thread, frame, cpItem) {
	    thread.setStatus(enums.ThreadStatus.ASYNC_WAITING);
	    cpItem.resolve(thread, frame.getLoader(), frame.method.cls, function (status) {
	        if (status) {
	            thread.setStatus(enums.ThreadStatus.RUNNABLE);
	        }
	    }, false);
	    frame.returnToThreadLoop = true;
	}
	exports.resolveCPItem = resolveCPItem;
	function initializeClassFromClass(thread, frame, cls) {
	    thread.setStatus(enums.ThreadStatus.ASYNC_WAITING);
	    cls.initialize(thread, function (cdata) {
	        if (cdata != null) {
	            thread.setStatus(enums.ThreadStatus.RUNNABLE);
	        }
	    }, false);
	    frame.returnToThreadLoop = true;
	}
	exports.initializeClassFromClass = initializeClassFromClass;
	function initializeClass(thread, frame, clsRef) {
	    thread.setStatus(enums.ThreadStatus.ASYNC_WAITING);
	    function initialize(cls) {
	        cls.initialize(thread, function (cdata) {
	            if (cdata != null) {
	                thread.setStatus(enums.ThreadStatus.RUNNABLE);
	            }
	        });
	    }
	    if (!clsRef.isResolved()) {
	        clsRef.resolve(thread, frame.getLoader(), frame.method.cls, function (status) {
	            if (status) {
	                initialize(clsRef.cls);
	            }
	        }, false);
	    } else {
	        initialize(clsRef.cls);
	    }
	    frame.returnToThreadLoop = true;
	}
	exports.initializeClass = initializeClass;
	function throwException(thread, frame, clsName, msg) {
	    thread.throwNewException(clsName, msg);
	    frame.returnToThreadLoop = true;
	}
	exports.throwException = throwException;
	exports.ArrayTypes = {
	    4: 'Z',
	    5: 'C',
	    6: 'F',
	    7: 'D',
	    8: 'B',
	    9: 'S',
	    10: 'I',
	    11: 'J'
	};
	var Opcodes = function () {
	    function Opcodes() {
	    }
	    Opcodes._aload_32 = function (thread, frame) {
	        var opStack = frame.opStack, idx = opStack.pop(), obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            var len = obj.array.length;
	            if (idx < 0 || idx >= len) {
	                throwException(thread, frame, 'Ljava/lang/ArrayIndexOutOfBoundsException;', idx + ' not in length ' + len + ' array of type ' + obj.getClass().getInternalName());
	            } else {
	                opStack.push(obj.array[idx]);
	                frame.pc++;
	            }
	        }
	    };
	    Opcodes._aload_64 = function (thread, frame) {
	        var opStack = frame.opStack, idx = opStack.pop(), obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            var len = obj.array.length;
	            if (idx < 0 || idx >= len) {
	                throwException(thread, frame, 'Ljava/lang/ArrayIndexOutOfBoundsException;', idx + ' not in length ' + len + ' array of type ' + obj.getClass().getInternalName());
	            } else {
	                opStack.push(obj.array[idx]);
	                opStack.push(null);
	                frame.pc++;
	            }
	        }
	    };
	    Opcodes._astore_32 = function (thread, frame) {
	        var opStack = frame.opStack, value = opStack.pop(), idx = opStack.pop(), obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            var len = obj.array.length;
	            if (idx < 0 || idx >= len) {
	                throwException(thread, frame, 'Ljava/lang/ArrayIndexOutOfBoundsException;', idx + ' not in length ' + len + ' array of type ' + obj.getClass().getInternalName());
	            } else {
	                obj.array[idx] = value;
	                frame.pc++;
	            }
	        }
	    };
	    Opcodes._astore_64 = function (thread, frame) {
	        var opStack = frame.opStack, value = opStack.pop2(), idx = opStack.pop(), obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            var len = obj.array.length;
	            if (idx < 0 || idx >= len) {
	                throwException(thread, frame, 'Ljava/lang/ArrayIndexOutOfBoundsException;', idx + ' not in length ' + len + ' array of type ' + obj.getClass().getInternalName());
	            } else {
	                obj.array[idx] = value;
	                frame.pc++;
	            }
	        }
	    };
	    Opcodes.aconst_null = function (thread, frame) {
	        frame.opStack.push(null);
	        frame.pc++;
	    };
	    Opcodes._const_0_32 = function (thread, frame) {
	        frame.opStack.push(0);
	        frame.pc++;
	    };
	    Opcodes._const_1_32 = function (thread, frame) {
	        frame.opStack.push(1);
	        frame.pc++;
	    };
	    Opcodes._const_2_32 = function (thread, frame) {
	        frame.opStack.push(2);
	        frame.pc++;
	    };
	    Opcodes.iconst_m1 = function (thread, frame) {
	        frame.opStack.push(-1);
	        frame.pc++;
	    };
	    Opcodes.iconst_3 = function (thread, frame) {
	        frame.opStack.push(3);
	        frame.pc++;
	    };
	    Opcodes.iconst_4 = function (thread, frame) {
	        frame.opStack.push(4);
	        frame.pc++;
	    };
	    Opcodes.iconst_5 = function (thread, frame) {
	        frame.opStack.push(5);
	        frame.pc++;
	    };
	    Opcodes.lconst_0 = function (thread, frame) {
	        frame.opStack.pushWithNull(gLong.ZERO);
	        frame.pc++;
	    };
	    Opcodes.lconst_1 = function (thread, frame) {
	        frame.opStack.pushWithNull(gLong.ONE);
	        frame.pc++;
	    };
	    Opcodes.dconst_0 = function (thread, frame) {
	        frame.opStack.pushWithNull(0);
	        frame.pc++;
	    };
	    Opcodes.dconst_1 = function (thread, frame) {
	        frame.opStack.pushWithNull(1);
	        frame.pc++;
	    };
	    Opcodes._load_32 = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.opStack.push(frame.locals[code.readUInt8(pc + 1)]);
	        frame.pc += 2;
	    };
	    Opcodes._load_0_32 = function (thread, frame) {
	        frame.opStack.push(frame.locals[0]);
	        frame.pc++;
	    };
	    Opcodes._load_1_32 = function (thread, frame) {
	        frame.opStack.push(frame.locals[1]);
	        frame.pc++;
	    };
	    Opcodes._load_2_32 = function (thread, frame) {
	        frame.opStack.push(frame.locals[2]);
	        frame.pc++;
	    };
	    Opcodes._load_3_32 = function (thread, frame) {
	        frame.opStack.push(frame.locals[3]);
	        frame.pc++;
	    };
	    Opcodes._load_64 = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.opStack.pushWithNull(frame.locals[code.readUInt8(pc + 1)]);
	        frame.pc += 2;
	    };
	    Opcodes._load_0_64 = function (thread, frame) {
	        frame.opStack.pushWithNull(frame.locals[0]);
	        frame.pc++;
	    };
	    Opcodes._load_1_64 = function (thread, frame) {
	        frame.opStack.pushWithNull(frame.locals[1]);
	        frame.pc++;
	    };
	    Opcodes._load_2_64 = function (thread, frame) {
	        frame.opStack.pushWithNull(frame.locals[2]);
	        frame.pc++;
	    };
	    Opcodes._load_3_64 = function (thread, frame) {
	        frame.opStack.pushWithNull(frame.locals[3]);
	        frame.pc++;
	    };
	    Opcodes._store_32 = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.locals[code.readUInt8(pc + 1)] = frame.opStack.pop();
	        frame.pc += 2;
	    };
	    Opcodes._store_0_32 = function (thread, frame) {
	        frame.locals[0] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_1_32 = function (thread, frame) {
	        frame.locals[1] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_2_32 = function (thread, frame) {
	        frame.locals[2] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_3_32 = function (thread, frame) {
	        frame.locals[3] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_64 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var offset = code.readUInt8(pc + 1);
	        frame.locals[offset + 1] = frame.opStack.pop();
	        frame.locals[offset] = frame.opStack.pop();
	        frame.pc += 2;
	    };
	    Opcodes._store_0_64 = function (thread, frame) {
	        frame.locals[1] = frame.opStack.pop();
	        frame.locals[0] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_1_64 = function (thread, frame) {
	        frame.locals[2] = frame.opStack.pop();
	        frame.locals[1] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_2_64 = function (thread, frame) {
	        frame.locals[3] = frame.opStack.pop();
	        frame.locals[2] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes._store_3_64 = function (thread, frame) {
	        frame.locals[4] = frame.opStack.pop();
	        frame.locals[3] = frame.opStack.pop();
	        frame.pc++;
	    };
	    Opcodes.sipush = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.opStack.push(code.readInt16BE(pc + 1));
	        frame.pc += 3;
	    };
	    Opcodes.bipush = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.opStack.push(code.readInt8(pc + 1));
	        frame.pc += 2;
	    };
	    Opcodes.pop = function (thread, frame) {
	        frame.opStack.dropFromTop(1);
	        frame.pc++;
	    };
	    Opcodes.pop2 = function (thread, frame) {
	        frame.opStack.dropFromTop(2);
	        frame.pc++;
	    };
	    Opcodes.dup = function (thread, frame) {
	        frame.opStack.dup();
	        frame.pc++;
	    };
	    Opcodes.dup_x1 = function (thread, frame) {
	        frame.opStack.dup_x1();
	        frame.pc++;
	    };
	    Opcodes.dup_x2 = function (thread, frame) {
	        frame.opStack.dup_x2();
	        frame.pc++;
	    };
	    Opcodes.dup2 = function (thread, frame) {
	        frame.opStack.dup2();
	        frame.pc++;
	    };
	    Opcodes.dup2_x1 = function (thread, frame) {
	        frame.opStack.dup2_x1();
	        frame.pc++;
	    };
	    Opcodes.dup2_x2 = function (thread, frame) {
	        var opStack = frame.opStack, v1 = opStack.pop(), v2 = opStack.pop(), v3 = opStack.pop(), v4 = opStack.pop();
	        opStack.push6(v2, v1, v4, v3, v2, v1);
	        frame.pc++;
	    };
	    Opcodes.swap = function (thread, frame) {
	        frame.opStack.swap();
	        frame.pc++;
	    };
	    Opcodes.iadd = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() + opStack.pop() | 0);
	        frame.pc++;
	    };
	    Opcodes.ladd = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().add(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.fadd = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(util.wrapFloat(opStack.pop() + opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.dadd = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2() + opStack.pop2());
	        frame.pc++;
	    };
	    Opcodes.isub = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(-opStack.pop() + opStack.pop() | 0);
	        frame.pc++;
	    };
	    Opcodes.fsub = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(util.wrapFloat(-opStack.pop() + opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.dsub = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(-opStack.pop2() + opStack.pop2());
	        frame.pc++;
	    };
	    Opcodes.lsub = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().negate().add(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.imul = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(Math.imul(opStack.pop(), opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.lmul = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().multiply(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.fmul = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(util.wrapFloat(opStack.pop() * opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.dmul = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2() * opStack.pop2());
	        frame.pc++;
	    };
	    Opcodes.idiv = function (thread, frame) {
	        var opStack = frame.opStack, b = opStack.pop(), a = opStack.pop();
	        if (b === 0) {
	            throwException(thread, frame, 'Ljava/lang/ArithmeticException;', '/ by zero');
	        } else {
	            if (a === enums.Constants.INT_MIN && b === -1) {
	                opStack.push(a);
	            } else {
	                opStack.push(a / b | 0);
	            }
	            frame.pc++;
	        }
	    };
	    Opcodes.ldiv = function (thread, frame) {
	        var opStack = frame.opStack, b = opStack.pop2(), a = opStack.pop2();
	        if (b.isZero()) {
	            throwException(thread, frame, 'Ljava/lang/ArithmeticException;', '/ by zero');
	        } else {
	            opStack.pushWithNull(a.div(b));
	            frame.pc++;
	        }
	    };
	    Opcodes.fdiv = function (thread, frame) {
	        var opStack = frame.opStack, a = opStack.pop();
	        opStack.push(util.wrapFloat(opStack.pop() / a));
	        frame.pc++;
	    };
	    Opcodes.ddiv = function (thread, frame) {
	        var opStack = frame.opStack, v = opStack.pop2();
	        opStack.pushWithNull(opStack.pop2() / v);
	        frame.pc++;
	    };
	    Opcodes.irem = function (thread, frame) {
	        var opStack = frame.opStack, b = opStack.pop(), a = opStack.pop();
	        if (b === 0) {
	            throwException(thread, frame, 'Ljava/lang/ArithmeticException;', '/ by zero');
	        } else {
	            opStack.push(a % b);
	            frame.pc++;
	        }
	    };
	    Opcodes.lrem = function (thread, frame) {
	        var opStack = frame.opStack, b = opStack.pop2(), a = opStack.pop2();
	        if (b.isZero()) {
	            throwException(thread, frame, 'Ljava/lang/ArithmeticException;', '/ by zero');
	        } else {
	            opStack.pushWithNull(a.modulo(b));
	            frame.pc++;
	        }
	    };
	    Opcodes.frem = function (thread, frame) {
	        var opStack = frame.opStack, b = opStack.pop();
	        opStack.push(opStack.pop() % b);
	        frame.pc++;
	    };
	    Opcodes.drem = function (thread, frame) {
	        var opStack = frame.opStack, b = opStack.pop2();
	        opStack.pushWithNull(opStack.pop2() % b);
	        frame.pc++;
	    };
	    Opcodes.ineg = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(-opStack.pop() | 0);
	        frame.pc++;
	    };
	    Opcodes.lneg = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().negate());
	        frame.pc++;
	    };
	    Opcodes.fneg = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(-opStack.pop());
	        frame.pc++;
	    };
	    Opcodes.dneg = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(-opStack.pop2());
	        frame.pc++;
	    };
	    Opcodes.ishl = function (thread, frame) {
	        var opStack = frame.opStack, s = opStack.pop();
	        opStack.push(opStack.pop() << s);
	        frame.pc++;
	    };
	    Opcodes.lshl = function (thread, frame) {
	        var opStack = frame.opStack, s = opStack.pop();
	        opStack.pushWithNull(opStack.pop2().shiftLeft(gLong.fromInt(s)));
	        frame.pc++;
	    };
	    Opcodes.ishr = function (thread, frame) {
	        var opStack = frame.opStack, s = opStack.pop();
	        opStack.push(opStack.pop() >> s);
	        frame.pc++;
	    };
	    Opcodes.lshr = function (thread, frame) {
	        var opStack = frame.opStack, s = opStack.pop();
	        opStack.pushWithNull(opStack.pop2().shiftRight(gLong.fromInt(s)));
	        frame.pc++;
	    };
	    Opcodes.iushr = function (thread, frame) {
	        var opStack = frame.opStack, s = opStack.pop();
	        opStack.push(opStack.pop() >>> s | 0);
	        frame.pc++;
	    };
	    Opcodes.lushr = function (thread, frame) {
	        var opStack = frame.opStack, s = opStack.pop();
	        opStack.pushWithNull(opStack.pop2().shiftRightUnsigned(gLong.fromInt(s)));
	        frame.pc++;
	    };
	    Opcodes.iand = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() & opStack.pop());
	        frame.pc++;
	    };
	    Opcodes.land = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().and(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.ior = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() | opStack.pop());
	        frame.pc++;
	    };
	    Opcodes.lor = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().or(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.ixor = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() ^ opStack.pop());
	        frame.pc++;
	    };
	    Opcodes.lxor = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().xor(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.iinc = function (thread, frame, code) {
	        var pc = frame.pc;
	        var idx = code.readUInt8(pc + 1), val = code.readInt8(pc + 2);
	        frame.locals[idx] = frame.locals[idx] + val | 0;
	        frame.pc += 3;
	    };
	    Opcodes.i2l = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(gLong.fromInt(opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.i2f = function (thread, frame) {
	        frame.pc++;
	    };
	    Opcodes.i2d = function (thread, frame) {
	        frame.opStack.push(null);
	        frame.pc++;
	    };
	    Opcodes.l2i = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop2().toInt());
	        frame.pc++;
	    };
	    Opcodes.l2f = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop2().toNumber());
	        frame.pc++;
	    };
	    Opcodes.l2d = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(opStack.pop2().toNumber());
	        frame.pc++;
	    };
	    Opcodes.f2i = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(util.float2int(opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.f2l = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pushWithNull(gLong.fromNumber(opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.f2d = function (thread, frame) {
	        frame.opStack.push(null);
	        frame.pc++;
	    };
	    Opcodes.d2i = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(util.float2int(opStack.pop2()));
	        frame.pc++;
	    };
	    Opcodes.d2l = function (thread, frame) {
	        var opStack = frame.opStack, d_val = opStack.pop2();
	        if (d_val === Number.POSITIVE_INFINITY) {
	            opStack.pushWithNull(gLong.MAX_VALUE);
	        } else if (d_val === Number.NEGATIVE_INFINITY) {
	            opStack.pushWithNull(gLong.MIN_VALUE);
	        } else {
	            opStack.pushWithNull(gLong.fromNumber(d_val));
	        }
	        frame.pc++;
	    };
	    Opcodes.d2f = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.pop();
	        opStack.push(util.wrapFloat(opStack.pop()));
	        frame.pc++;
	    };
	    Opcodes.i2b = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() << 24 >> 24);
	        frame.pc++;
	    };
	    Opcodes.i2c = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() & 65535);
	        frame.pc++;
	    };
	    Opcodes.i2s = function (thread, frame) {
	        var opStack = frame.opStack;
	        opStack.push(opStack.pop() << 16 >> 16);
	        frame.pc++;
	    };
	    Opcodes.lcmp = function (thread, frame) {
	        var opStack = frame.opStack, v2 = opStack.pop2();
	        opStack.push(opStack.pop2().compare(v2));
	        frame.pc++;
	    };
	    Opcodes.fcmpl = function (thread, frame) {
	        var opStack = frame.opStack, v2 = opStack.pop(), v1 = opStack.pop();
	        if (v1 === v2) {
	            opStack.push(0);
	        } else if (v1 > v2) {
	            opStack.push(1);
	        } else {
	            opStack.push(-1);
	        }
	        frame.pc++;
	    };
	    Opcodes.fcmpg = function (thread, frame) {
	        var opStack = frame.opStack, v2 = opStack.pop(), v1 = opStack.pop();
	        if (v1 === v2) {
	            opStack.push(0);
	        } else if (v1 < v2) {
	            opStack.push(-1);
	        } else {
	            opStack.push(1);
	        }
	        frame.pc++;
	    };
	    Opcodes.dcmpl = function (thread, frame) {
	        var opStack = frame.opStack, v2 = opStack.pop2(), v1 = opStack.pop2();
	        if (v1 === v2) {
	            opStack.push(0);
	        } else if (v1 > v2) {
	            opStack.push(1);
	        } else {
	            opStack.push(-1);
	        }
	        frame.pc++;
	    };
	    Opcodes.dcmpg = function (thread, frame) {
	        var opStack = frame.opStack, v2 = opStack.pop2(), v1 = opStack.pop2();
	        if (v1 === v2) {
	            opStack.push(0);
	        } else if (v1 < v2) {
	            opStack.push(-1);
	        } else {
	            opStack.push(1);
	        }
	        frame.pc++;
	    };
	    Opcodes.ifeq = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() === 0) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.ifne = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() !== 0) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.iflt = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() < 0) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.ifge = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() >= 0) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.ifgt = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() > 0) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.ifle = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() <= 0) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_icmpeq = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 === v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_icmpne = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 !== v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_icmplt = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 < v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_icmpge = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 >= v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_icmpgt = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 > v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_icmple = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 <= v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_acmpeq = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 === v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.if_acmpne = function (thread, frame, code) {
	        var pc = frame.pc;
	        var v2 = frame.opStack.pop();
	        var v1 = frame.opStack.pop();
	        if (v1 !== v2) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.goto = function (thread, frame, code) {
	        var pc = frame.pc;
	        var offset = code.readInt16BE(pc + 1);
	        frame.pc += offset;
	        if (offset < 0) {
	            frame.method.incrBBEntries();
	        }
	    };
	    Opcodes.jsr = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.opStack.push(pc + 3);
	        var offset = code.readInt16BE(pc + 1);
	        frame.pc += offset;
	        if (offset < 0) {
	            frame.method.incrBBEntries();
	        }
	    };
	    Opcodes.ret = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.pc = frame.locals[code.readUInt8(pc + 1)];
	    };
	    Opcodes.tableswitch = function (thread, frame, code) {
	        var pc = frame.pc;
	        pc += (4 - (pc + 1) % 4) % 4 + 1;
	        var defaultOffset = code.readInt32BE(pc), low = code.readInt32BE(pc + 4), high = code.readInt32BE(pc + 8), offset = frame.opStack.pop();
	        if (offset >= low && offset <= high) {
	            frame.pc += code.readInt32BE(pc + 12 + (offset - low) * 4);
	        } else {
	            frame.pc += defaultOffset;
	        }
	    };
	    Opcodes.lookupswitch = function (thread, frame, code) {
	        var pc = frame.pc;
	        pc += (4 - (pc + 1) % 4) % 4 + 1;
	        var defaultOffset = code.readInt32BE(pc), nPairs = code.readInt32BE(pc + 4), i, v = frame.opStack.pop();
	        pc += 8;
	        for (i = 0; i < nPairs; i++) {
	            if (code.readInt32BE(pc) === v) {
	                var offset = code.readInt32BE(pc + 4);
	                frame.pc += offset;
	                if (offset < 0) {
	                    frame.method.incrBBEntries();
	                }
	                return;
	            }
	            pc += 8;
	        }
	        frame.pc += defaultOffset;
	    };
	    Opcodes.return = function (thread, frame) {
	        frame.returnToThreadLoop = true;
	        if (frame.method.accessFlags.isSynchronized()) {
	            if (!frame.method.methodLock(thread, frame).exit(thread)) {
	                return;
	            }
	        }
	        thread.asyncReturn();
	    };
	    Opcodes._return_32 = function (thread, frame) {
	        frame.returnToThreadLoop = true;
	        if (frame.method.accessFlags.isSynchronized()) {
	            if (!frame.method.methodLock(thread, frame).exit(thread)) {
	                return;
	            }
	        }
	        thread.asyncReturn(frame.opStack.bottom());
	    };
	    Opcodes._return_64 = function (thread, frame) {
	        frame.returnToThreadLoop = true;
	        if (frame.method.accessFlags.isSynchronized()) {
	            if (!frame.method.methodLock(thread, frame).exit(thread)) {
	                return;
	            }
	        }
	        thread.asyncReturn(frame.opStack.bottom(), null);
	    };
	    Opcodes.getstatic = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        assert(fieldInfo.getType() === enums.ConstantPoolItemType.FIELDREF);
	        if (fieldInfo.isResolved()) {
	            var fieldOwnerCls = fieldInfo.field.cls;
	            if (fieldOwnerCls.isInitialized(thread)) {
	                if (fieldInfo.nameAndTypeInfo.descriptor === 'J' || fieldInfo.nameAndTypeInfo.descriptor === 'D') {
	                    code.writeUInt8(enums.OpCode.GETSTATIC_FAST64, pc);
	                } else {
	                    code.writeUInt8(enums.OpCode.GETSTATIC_FAST32, pc);
	                }
	                fieldInfo.fieldOwnerConstructor = fieldOwnerCls.getConstructor(thread);
	            } else {
	                initializeClassFromClass(thread, frame, fieldOwnerCls);
	            }
	        } else {
	            resolveCPItem(thread, frame, fieldInfo);
	        }
	    };
	    Opcodes.getstatic_fast32 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        frame.opStack.push(fieldInfo.fieldOwnerConstructor[fieldInfo.fullFieldName]);
	        frame.pc += 3;
	    };
	    Opcodes.getstatic_fast64 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        frame.opStack.pushWithNull(fieldInfo.fieldOwnerConstructor[fieldInfo.fullFieldName]);
	        frame.pc += 3;
	    };
	    Opcodes.putstatic = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        assert(fieldInfo.getType() === enums.ConstantPoolItemType.FIELDREF);
	        if (fieldInfo.isResolved()) {
	            var fieldOwnerCls = fieldInfo.field.cls;
	            if (fieldOwnerCls.isInitialized(thread)) {
	                if (fieldInfo.nameAndTypeInfo.descriptor === 'J' || fieldInfo.nameAndTypeInfo.descriptor === 'D') {
	                    code.writeUInt8(enums.OpCode.PUTSTATIC_FAST64, pc);
	                } else {
	                    code.writeUInt8(enums.OpCode.PUTSTATIC_FAST32, pc);
	                }
	                fieldInfo.fieldOwnerConstructor = fieldOwnerCls.getConstructor(thread);
	            } else {
	                initializeClassFromClass(thread, frame, fieldOwnerCls);
	            }
	        } else {
	            resolveCPItem(thread, frame, fieldInfo);
	        }
	    };
	    Opcodes.putstatic_fast32 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        fieldInfo.fieldOwnerConstructor[fieldInfo.fullFieldName] = frame.opStack.pop();
	        frame.pc += 3;
	    };
	    Opcodes.putstatic_fast64 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        fieldInfo.fieldOwnerConstructor[fieldInfo.fullFieldName] = frame.opStack.pop2();
	        frame.pc += 3;
	    };
	    Opcodes.getfield = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), loader = frame.getLoader(), obj = frame.opStack.top();
	        assert(fieldInfo.getType() === enums.ConstantPoolItemType.FIELDREF);
	        if (!isNull(thread, frame, obj)) {
	            if (fieldInfo.isResolved()) {
	                var field = fieldInfo.field;
	                if (field.rawDescriptor == 'J' || field.rawDescriptor == 'D') {
	                    code.writeUInt8(enums.OpCode.GETFIELD_FAST64, pc);
	                } else {
	                    code.writeUInt8(enums.OpCode.GETFIELD_FAST32, pc);
	                }
	            } else {
	                resolveCPItem(thread, frame, fieldInfo);
	            }
	        }
	    };
	    Opcodes.getfield_fast32 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            opStack.push(obj[fieldInfo.fullFieldName]);
	            frame.pc += 3;
	        }
	    };
	    Opcodes.getfield_fast64 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            opStack.pushWithNull(obj[fieldInfo.fullFieldName]);
	            frame.pc += 3;
	        }
	    };
	    Opcodes.putfield = function (thread, frame, code) {
	        var pc = frame.pc;
	        var fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), loader = frame.getLoader(), isLong = fieldInfo.nameAndTypeInfo.descriptor == 'J' || fieldInfo.nameAndTypeInfo.descriptor == 'D', obj = frame.opStack.fromTop(isLong ? 2 : 1);
	        assert(fieldInfo.getType() === enums.ConstantPoolItemType.FIELDREF);
	        if (!isNull(thread, frame, obj)) {
	            if (fieldInfo.isResolved()) {
	                var field = fieldInfo.field;
	                if (isLong) {
	                    code.writeUInt8(enums.OpCode.PUTFIELD_FAST64, pc);
	                } else {
	                    code.writeUInt8(enums.OpCode.PUTFIELD_FAST32, pc);
	                }
	                fieldInfo.fullFieldName = util.descriptor2typestr(field.cls.getInternalName()) + '/' + fieldInfo.nameAndTypeInfo.name;
	            } else {
	                resolveCPItem(thread, frame, fieldInfo);
	            }
	        }
	    };
	    Opcodes.putfield_fast32 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var opStack = frame.opStack, val = opStack.pop(), obj = opStack.pop(), fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (!isNull(thread, frame, obj)) {
	            obj[fieldInfo.fullFieldName] = val;
	            frame.pc += 3;
	        }
	    };
	    Opcodes.putfield_fast64 = function (thread, frame, code) {
	        var pc = frame.pc;
	        var opStack = frame.opStack, val = opStack.pop2(), obj = opStack.pop(), fieldInfo = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (!isNull(thread, frame, obj)) {
	            obj[fieldInfo.fullFieldName] = val;
	            frame.pc += 3;
	        }
	    };
	    Opcodes.invokevirtual = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (methodReference.isResolved()) {
	            var m = methodReference.method;
	            if (m.isSignaturePolymorphic()) {
	                switch (m.name) {
	                case 'invokeBasic':
	                    code.writeUInt8(enums.OpCode.INVOKEBASIC, pc);
	                    break;
	                case 'invoke':
	                case 'invokeExact':
	                    code.writeUInt8(enums.OpCode.INVOKEHANDLE, pc);
	                    break;
	                default:
	                    throwException(thread, frame, 'Ljava/lang/AbstractMethodError;', 'Invalid signature polymorphic method: ' + m.cls.getExternalName() + '.' + m.name);
	                    break;
	                }
	            } else {
	                code.writeUInt8(enums.OpCode.INVOKEVIRTUAL_FAST, pc);
	            }
	        } else {
	            resolveCPItem(thread, frame, methodReference);
	        }
	    };
	    Opcodes.invokeinterface = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (methodReference.isResolved()) {
	            if (methodReference.method.cls.isInitialized(thread)) {
	                code.writeUInt8(enums.OpCode.INVOKEINTERFACE_FAST, pc);
	            } else {
	                initializeClass(thread, frame, methodReference.classInfo);
	            }
	        } else {
	            resolveCPItem(thread, frame, methodReference);
	        }
	    };
	    Opcodes.invokedynamic = function (thread, frame, code) {
	        var pc = frame.pc;
	        var callSiteSpecifier = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        thread.setStatus(enums.ThreadStatus.ASYNC_WAITING);
	        callSiteSpecifier.constructCallSiteObject(thread, frame.getLoader(), frame.method.cls, pc, function (status) {
	            if (status) {
	                assert(typeof callSiteSpecifier.getCallSiteObject(pc)[0].vmtarget === 'function', 'MethodName should be resolved...');
	                code.writeUInt8(enums.OpCode.INVOKEDYNAMIC_FAST, pc);
	                thread.setStatus(enums.ThreadStatus.RUNNABLE);
	            }
	        });
	        frame.returnToThreadLoop = true;
	    };
	    Opcodes.invokespecial = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (methodReference.isResolved()) {
	            code.writeUInt8(enums.OpCode.INVOKENONVIRTUAL_FAST, pc);
	        } else {
	            resolveCPItem(thread, frame, methodReference);
	        }
	    };
	    Opcodes.invokestatic = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (methodReference.isResolved()) {
	            var m = methodReference.method;
	            if (m.cls.isInitialized(thread)) {
	                var newOpcode = enums.OpCode.INVOKESTATIC_FAST;
	                if (methodReference.method.isSignaturePolymorphic()) {
	                    switch (methodReference.method.name) {
	                    case 'linkToInterface':
	                    case 'linkToVirtual':
	                        newOpcode = enums.OpCode.LINKTOVIRTUAL;
	                        break;
	                    case 'linkToStatic':
	                    case 'linkToSpecial':
	                        newOpcode = enums.OpCode.LINKTOSPECIAL;
	                        break;
	                    default:
	                        assert(false, 'Should be impossible.');
	                        break;
	                    }
	                }
	                code.writeUInt8(newOpcode, pc);
	            } else {
	                initializeClassFromClass(thread, frame, m.cls);
	            }
	        } else {
	            resolveCPItem(thread, frame, methodReference);
	        }
	    };
	    Opcodes.invokenonvirtual_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, paramSize = methodReference.paramWordSize, obj = opStack.fromTop(paramSize);
	        if (!isNull(thread, frame, obj)) {
	            var args = opStack.sliceFromTop(paramSize);
	            opStack.dropFromTop(paramSize + 1);
	            assert(typeof obj[methodReference.fullSignature] === 'function', 'Resolved method ' + methodReference.fullSignature + ' isn\'t defined?!', thread);
	            obj[methodReference.fullSignature](thread, args);
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.invokestatic_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, paramSize = methodReference.paramWordSize, args = opStack.sliceAndDropFromTop(paramSize);
	        assert(methodReference.jsConstructor != null, 'jsConstructor is missing?!');
	        assert(typeof methodReference.jsConstructor[methodReference.fullSignature] === 'function', 'Resolved method isn\'t defined?!');
	        methodReference.jsConstructor[methodReference.fullSignature](thread, args);
	        frame.returnToThreadLoop = true;
	    };
	    Opcodes.invokevirtual_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), count = methodReference.paramWordSize, opStack = frame.opStack, obj = opStack.fromTop(count);
	        if (!isNull(thread, frame, obj)) {
	            assert(typeof obj[methodReference.signature] === 'function', 'Resolved method ' + methodReference.signature + ' isn\'t defined?!');
	            obj[methodReference.signature](thread, opStack.sliceFromTop(count));
	            opStack.dropFromTop(count + 1);
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.invokedynamic_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var callSiteSpecifier = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), cso = callSiteSpecifier.getCallSiteObject(pc), appendix = cso[1], fcn = cso[0].vmtarget, opStack = frame.opStack, paramSize = callSiteSpecifier.paramWordSize, args = opStack.sliceAndDropFromTop(paramSize);
	        if (appendix !== null) {
	            args.push(appendix);
	        }
	        fcn(thread, null, args);
	        frame.returnToThreadLoop = true;
	    };
	    Opcodes.invokehandle = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, fcn = methodReference.memberName.vmtarget, paramSize = methodReference.paramWordSize + 1, appendix = methodReference.appendix, args = opStack.sliceFromTop(paramSize);
	        if (appendix !== null) {
	            args.push(appendix);
	        }
	        if (!isNull(thread, frame, args[0])) {
	            opStack.dropFromTop(paramSize);
	            fcn(thread, null, args);
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.invokebasic = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), paramSize = methodReference.getParamWordSize(), opStack = frame.opStack, obj = opStack.fromTop(paramSize), args = opStack.sliceFromTop(paramSize + 1), lmbdaForm, mn, m;
	        if (!isNull(thread, frame, obj)) {
	            opStack.dropFromTop(paramSize + 1);
	            lmbdaForm = obj['java/lang/invoke/MethodHandle/form'];
	            mn = lmbdaForm['java/lang/invoke/LambdaForm/vmentry'];
	            assert(mn.vmtarget !== null && mn.vmtarget !== undefined, 'vmtarget must be defined');
	            mn.vmtarget(thread, methodReference.nameAndTypeInfo.descriptor, args);
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.linktospecial = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, paramSize = methodReference.paramWordSize, args = opStack.sliceFromTop(paramSize), memberName = args.pop(), desc = methodReference.nameAndTypeInfo.descriptor;
	        if (!isNull(thread, frame, memberName)) {
	            opStack.dropFromTop(paramSize);
	            assert(memberName.getClass().getInternalName() === 'Ljava/lang/invoke/MemberName;');
	            memberName.vmtarget(thread, desc.replace('Ljava/lang/invoke/MemberName;)', ')'), args);
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.linktovirtual = function (thread, frame, code) {
	        var pc = frame.pc;
	        var methodReference = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), paramSize = methodReference.paramWordSize, opStack = frame.opStack, args = opStack.sliceFromTop(paramSize), memberName = args.pop(), desc = methodReference.nameAndTypeInfo.descriptor;
	        if (!isNull(thread, frame, memberName)) {
	            opStack.dropFromTop(paramSize);
	            assert(memberName.getClass().getInternalName() === 'Ljava/lang/invoke/MemberName;');
	            memberName.vmtarget(thread, desc.replace('Ljava/lang/invoke/MemberName;)', ')'), args);
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.breakpoint = function (thread, frame) {
	        throwException(thread, frame, 'Ljava/lang/Error;', 'breakpoint not implemented.');
	    };
	    Opcodes.new = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (classRef.isResolved()) {
	            var cls = classRef.cls;
	            if (cls.isInitialized(thread)) {
	                code.writeUInt8(enums.OpCode.NEW_FAST, pc);
	            } else {
	                initializeClassFromClass(thread, frame, cls);
	            }
	        } else {
	            resolveCPItem(thread, frame, classRef);
	        }
	    };
	    Opcodes.new_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        frame.opStack.push(new classRef.clsConstructor(thread));
	        frame.pc += 3;
	    };
	    Opcodes.newarray = function (thread, frame, code) {
	        var pc = frame.pc;
	        var opStack = frame.opStack, type = '[' + exports.ArrayTypes[code.readUInt8(pc + 1)], cls = frame.getLoader().getInitializedClass(thread, type), length = opStack.pop();
	        if (length >= 0) {
	            opStack.push(new (cls.getConstructor(thread))(thread, length));
	            frame.pc += 2;
	        } else {
	            throwException(thread, frame, 'Ljava/lang/NegativeArraySizeException;', 'Tried to init ' + type + ' array with length ' + length);
	        }
	    };
	    Opcodes.anewarray = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (classRef.isResolved()) {
	            code.writeUInt8(enums.OpCode.ANEWARRAY_FAST, pc);
	            classRef.arrayClass = frame.getLoader().getInitializedClass(thread, '[' + classRef.cls.getInternalName());
	            classRef.arrayClassConstructor = classRef.arrayClass.getConstructor(thread);
	        } else {
	            resolveCPItem(thread, frame, classRef);
	        }
	    };
	    Opcodes.anewarray_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var opStack = frame.opStack, classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), length = opStack.pop();
	        if (length >= 0) {
	            opStack.push(new classRef.arrayClassConstructor(thread, length));
	            frame.pc += 3;
	        } else {
	            throwException(thread, frame, 'Ljava/lang/NegativeArraySizeException;', 'Tried to init ' + classRef.arrayClass.getInternalName() + ' array with length ' + length);
	        }
	    };
	    Opcodes.arraylength = function (thread, frame) {
	        var opStack = frame.opStack, obj = opStack.pop();
	        if (!isNull(thread, frame, obj)) {
	            opStack.push(obj.array.length);
	            frame.pc++;
	        }
	    };
	    Opcodes.athrow = function (thread, frame) {
	        thread.throwException(frame.opStack.pop());
	        frame.returnToThreadLoop = true;
	    };
	    Opcodes.checkcast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (classRef.isResolved()) {
	            code.writeUInt8(enums.OpCode.CHECKCAST_FAST, pc);
	        } else {
	            resolveCPItem(thread, frame, classRef);
	        }
	    };
	    Opcodes.checkcast_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), cls = classRef.cls, opStack = frame.opStack, o = opStack.top();
	        if (o != null && !o.getClass().isCastable(cls)) {
	            var targetClass = cls.getExternalName();
	            var candidateClass = o.getClass().getExternalName();
	            throwException(thread, frame, 'Ljava/lang/ClassCastException;', candidateClass + ' cannot be cast to ' + targetClass);
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.instanceof = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (classRef.isResolved()) {
	            code.writeUInt8(enums.OpCode.INSTANCEOF_FAST, pc);
	        } else {
	            resolveCPItem(thread, frame, classRef);
	        }
	    };
	    Opcodes.instanceof_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), cls = classRef.cls, opStack = frame.opStack, o = opStack.pop();
	        opStack.push(o !== null ? o.getClass().isCastable(cls) ? 1 : 0 : 0);
	        frame.pc += 3;
	    };
	    Opcodes.monitorenter = function (thread, frame) {
	        var opStack = frame.opStack, monitorObj = opStack.pop(), monitorEntered = function () {
	                frame.pc++;
	            };
	        if (!monitorObj.getMonitor().enter(thread, monitorEntered)) {
	            frame.returnToThreadLoop = true;
	        } else {
	            monitorEntered();
	        }
	    };
	    Opcodes.monitorexit = function (thread, frame) {
	        var monitorObj = frame.opStack.pop();
	        if (monitorObj.getMonitor().exit(thread)) {
	            frame.pc++;
	        } else {
	            frame.returnToThreadLoop = true;
	        }
	    };
	    Opcodes.multianewarray = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (classRef.isResolved()) {
	            code.writeUInt8(enums.OpCode.MULTIANEWARRAY_FAST, pc);
	        } else {
	            resolveCPItem(thread, frame, classRef);
	        }
	    };
	    Opcodes.multianewarray_fast = function (thread, frame, code) {
	        var pc = frame.pc;
	        var classRef = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1)), opStack = frame.opStack, dim = code.readUInt8(pc + 3), i, args = new Array(dim), dimSize;
	        for (i = 0; i < dim; i++) {
	            dimSize = opStack.pop();
	            args[dim - i - 1] = dimSize;
	            if (dimSize < 0) {
	                throwException(thread, frame, 'Ljava/lang/NegativeArraySizeException;', 'Tried to init ' + classRef.cls.getInternalName() + ' array with a dimension of length ' + dimSize);
	                return;
	            }
	        }
	        opStack.push(new (classRef.cls.getConstructor(thread))(thread, args));
	        frame.pc += 4;
	    };
	    Opcodes.ifnull = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() == null) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.ifnonnull = function (thread, frame, code) {
	        var pc = frame.pc;
	        if (frame.opStack.pop() != null) {
	            var offset = code.readInt16BE(pc + 1);
	            frame.pc += offset;
	            if (offset < 0) {
	                frame.method.incrBBEntries();
	            }
	        } else {
	            frame.pc += 3;
	        }
	    };
	    Opcodes.goto_w = function (thread, frame, code) {
	        var pc = frame.pc;
	        var offset = code.readInt32BE(pc + 1);
	        frame.pc += offset;
	        if (offset < 0) {
	            frame.method.incrBBEntries();
	        }
	    };
	    Opcodes.jsr_w = function (thread, frame, code) {
	        var pc = frame.pc;
	        frame.opStack.push(frame.pc + 5);
	        frame.pc += code.readInt32BE(pc + 1);
	    };
	    Opcodes.nop = function (thread, frame) {
	        frame.pc += 1;
	    };
	    Opcodes.ldc = function (thread, frame, code) {
	        var pc = frame.pc;
	        var constant = frame.method.cls.constantPool.get(code.readUInt8(pc + 1));
	        if (constant.isResolved()) {
	            assert(function () {
	                switch (constant.getType()) {
	                case enums.ConstantPoolItemType.STRING:
	                case enums.ConstantPoolItemType.CLASS:
	                case enums.ConstantPoolItemType.METHOD_HANDLE:
	                case enums.ConstantPoolItemType.METHOD_TYPE:
	                case enums.ConstantPoolItemType.INTEGER:
	                case enums.ConstantPoolItemType.FLOAT:
	                    return true;
	                default:
	                    return false;
	                }
	            }(), 'Constant pool item ' + enums.ConstantPoolItemType[constant.getType()] + ' is not appropriate for LDC.');
	            frame.opStack.push(constant.getConstant(thread));
	            frame.pc += 2;
	        } else {
	            resolveCPItem(thread, frame, constant);
	        }
	    };
	    Opcodes.ldc_w = function (thread, frame, code) {
	        var pc = frame.pc;
	        var constant = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        if (constant.isResolved()) {
	            assert(function () {
	                switch (constant.getType()) {
	                case enums.ConstantPoolItemType.STRING:
	                case enums.ConstantPoolItemType.CLASS:
	                case enums.ConstantPoolItemType.METHOD_HANDLE:
	                case enums.ConstantPoolItemType.METHOD_TYPE:
	                case enums.ConstantPoolItemType.INTEGER:
	                case enums.ConstantPoolItemType.FLOAT:
	                    return true;
	                default:
	                    return false;
	                }
	            }(), 'Constant pool item ' + enums.ConstantPoolItemType[constant.getType()] + ' is not appropriate for LDC_W.');
	            frame.opStack.push(constant.getConstant(thread));
	            frame.pc += 3;
	        } else {
	            resolveCPItem(thread, frame, constant);
	        }
	    };
	    Opcodes.ldc2_w = function (thread, frame, code) {
	        var pc = frame.pc;
	        var constant = frame.method.cls.constantPool.get(code.readUInt16BE(pc + 1));
	        assert(constant.getType() === enums.ConstantPoolItemType.LONG || constant.getType() === enums.ConstantPoolItemType.DOUBLE, 'Invalid ldc_w constant pool type: ' + enums.ConstantPoolItemType[constant.getType()]);
	        frame.opStack.pushWithNull(constant.value);
	        frame.pc += 3;
	    };
	    Opcodes.wide = function (thread, frame, code) {
	        var pc = frame.pc;
	        var index = code.readUInt16BE(pc + 2);
	        frame.pc += 4;
	        switch (code.readUInt8(pc + 1)) {
	        case enums.OpCode.ILOAD:
	        case enums.OpCode.FLOAD:
	        case enums.OpCode.ALOAD:
	            frame.opStack.push(frame.locals[index]);
	            break;
	        case enums.OpCode.LLOAD:
	        case enums.OpCode.DLOAD:
	            frame.opStack.pushWithNull(frame.locals[index]);
	            break;
	        case enums.OpCode.ISTORE:
	        case enums.OpCode.FSTORE:
	        case enums.OpCode.ASTORE:
	            frame.locals[index] = frame.opStack.pop();
	            break;
	        case enums.OpCode.LSTORE:
	        case enums.OpCode.DSTORE:
	            frame.locals[index + 1] = frame.opStack.pop();
	            frame.locals[index] = frame.opStack.pop();
	            break;
	        case enums.OpCode.RET:
	            frame.pc = frame.locals[index];
	            break;
	        case enums.OpCode.IINC:
	            var value = code.readInt16BE(pc + 4);
	            frame.locals[index] = frame.locals[index] + value | 0;
	            frame.pc += 2;
	            break;
	        default:
	            assert(false, 'Unknown wide opcode: ' + code.readUInt8(pc + 1));
	            break;
	        }
	    };
	    Opcodes.iaload = Opcodes._aload_32;
	    Opcodes.faload = Opcodes._aload_32;
	    Opcodes.aaload = Opcodes._aload_32;
	    Opcodes.baload = Opcodes._aload_32;
	    Opcodes.caload = Opcodes._aload_32;
	    Opcodes.saload = Opcodes._aload_32;
	    Opcodes.daload = Opcodes._aload_64;
	    Opcodes.laload = Opcodes._aload_64;
	    Opcodes.iastore = Opcodes._astore_32;
	    Opcodes.fastore = Opcodes._astore_32;
	    Opcodes.aastore = Opcodes._astore_32;
	    Opcodes.bastore = Opcodes._astore_32;
	    Opcodes.castore = Opcodes._astore_32;
	    Opcodes.sastore = Opcodes._astore_32;
	    Opcodes.lastore = Opcodes._astore_64;
	    Opcodes.dastore = Opcodes._astore_64;
	    Opcodes.iconst_0 = Opcodes._const_0_32;
	    Opcodes.iconst_1 = Opcodes._const_1_32;
	    Opcodes.iconst_2 = Opcodes._const_2_32;
	    Opcodes.fconst_0 = Opcodes._const_0_32;
	    Opcodes.fconst_1 = Opcodes._const_1_32;
	    Opcodes.fconst_2 = Opcodes._const_2_32;
	    Opcodes.iload = Opcodes._load_32;
	    Opcodes.iload_0 = Opcodes._load_0_32;
	    Opcodes.iload_1 = Opcodes._load_1_32;
	    Opcodes.iload_2 = Opcodes._load_2_32;
	    Opcodes.iload_3 = Opcodes._load_3_32;
	    Opcodes.fload = Opcodes._load_32;
	    Opcodes.fload_0 = Opcodes._load_0_32;
	    Opcodes.fload_1 = Opcodes._load_1_32;
	    Opcodes.fload_2 = Opcodes._load_2_32;
	    Opcodes.fload_3 = Opcodes._load_3_32;
	    Opcodes.aload = Opcodes._load_32;
	    Opcodes.aload_0 = Opcodes._load_0_32;
	    Opcodes.aload_1 = Opcodes._load_1_32;
	    Opcodes.aload_2 = Opcodes._load_2_32;
	    Opcodes.aload_3 = Opcodes._load_3_32;
	    Opcodes.lload = Opcodes._load_64;
	    Opcodes.lload_0 = Opcodes._load_0_64;
	    Opcodes.lload_1 = Opcodes._load_1_64;
	    Opcodes.lload_2 = Opcodes._load_2_64;
	    Opcodes.lload_3 = Opcodes._load_3_64;
	    Opcodes.dload = Opcodes._load_64;
	    Opcodes.dload_0 = Opcodes._load_0_64;
	    Opcodes.dload_1 = Opcodes._load_1_64;
	    Opcodes.dload_2 = Opcodes._load_2_64;
	    Opcodes.dload_3 = Opcodes._load_3_64;
	    Opcodes.istore = Opcodes._store_32;
	    Opcodes.istore_0 = Opcodes._store_0_32;
	    Opcodes.istore_1 = Opcodes._store_1_32;
	    Opcodes.istore_2 = Opcodes._store_2_32;
	    Opcodes.istore_3 = Opcodes._store_3_32;
	    Opcodes.fstore = Opcodes._store_32;
	    Opcodes.fstore_0 = Opcodes._store_0_32;
	    Opcodes.fstore_1 = Opcodes._store_1_32;
	    Opcodes.fstore_2 = Opcodes._store_2_32;
	    Opcodes.fstore_3 = Opcodes._store_3_32;
	    Opcodes.astore = Opcodes._store_32;
	    Opcodes.astore_0 = Opcodes._store_0_32;
	    Opcodes.astore_1 = Opcodes._store_1_32;
	    Opcodes.astore_2 = Opcodes._store_2_32;
	    Opcodes.astore_3 = Opcodes._store_3_32;
	    Opcodes.lstore = Opcodes._store_64;
	    Opcodes.lstore_0 = Opcodes._store_0_64;
	    Opcodes.lstore_1 = Opcodes._store_1_64;
	    Opcodes.lstore_2 = Opcodes._store_2_64;
	    Opcodes.lstore_3 = Opcodes._store_3_64;
	    Opcodes.dstore = Opcodes._store_64;
	    Opcodes.dstore_0 = Opcodes._store_0_64;
	    Opcodes.dstore_1 = Opcodes._store_1_64;
	    Opcodes.dstore_2 = Opcodes._store_2_64;
	    Opcodes.dstore_3 = Opcodes._store_3_64;
	    Opcodes.ireturn = Opcodes._return_32;
	    Opcodes.freturn = Opcodes._return_32;
	    Opcodes.areturn = Opcodes._return_32;
	    Opcodes.lreturn = Opcodes._return_64;
	    Opcodes.dreturn = Opcodes._return_64;
	    Opcodes.invokeinterface_fast = Opcodes.invokevirtual_fast;
	    return Opcodes;
	}();
	exports.Opcodes = Opcodes;
	exports.LookupTable = new Array(255);
	(function () {
	    for (var i = 0; i < 255; i++) {
	        if (enums.OpCode.hasOwnProperty('' + i)) {
	            exports.LookupTable[i] = Opcodes[enums.OpCode[i].toLowerCase()];
	            assert(exports.LookupTable[i] != null, 'Missing implementation of opcode ' + enums.OpCode[i]);
	        }
	    }
	}());


/***/ },
/* 17 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var gLong = __webpack_require__(8);
	function debug_var(e) {
	    if (e === null) {
	        return '!';
	    } else if (e === void 0) {
	        return 'undef';
	    } else if (e.ref != null) {
	        return '*' + e.ref;
	    } else if (e instanceof gLong) {
	        return e + 'L';
	    }
	    return e;
	}
	exports.debug_var = debug_var;
	function debug_vars(arr) {
	    return arr.map(debug_var);
	}
	exports.debug_vars = debug_vars;
	exports.VTRACE = 10;
	exports.TRACE = 9;
	exports.DEBUG = 5;
	exports.ERROR = 1;
	exports.log_level = exports.ERROR;
	function log(level, msgs) {
	    if (level <= exports.log_level) {
	        var msg = msgs.join(' ');
	        if (level == 1) {
	            console.error(msg);
	        } else {
	            console.log(msg);
	        }
	    }
	}
	function vtrace() {
	    var msgs = [];
	    for (var _i = 0; _i < arguments.length; _i++) {
	        msgs[_i - 0] = arguments[_i];
	    }
	    log(exports.VTRACE, msgs);
	}
	exports.vtrace = vtrace;
	function trace() {
	    var msgs = [];
	    for (var _i = 0; _i < arguments.length; _i++) {
	        msgs[_i - 0] = arguments[_i];
	    }
	    log(exports.TRACE, msgs);
	}
	exports.trace = trace;
	function debug() {
	    var msgs = [];
	    for (var _i = 0; _i < arguments.length; _i++) {
	        msgs[_i - 0] = arguments[_i];
	    }
	    log(exports.DEBUG, msgs);
	}
	exports.debug = debug;
	function error() {
	    var msgs = [];
	    for (var _i = 0; _i < arguments.length; _i++) {
	        msgs[_i - 0] = arguments[_i];
	    }
	    log(exports.ERROR, msgs);
	}
	exports.error = error;


/***/ },
/* 18 */
/***/ function(module, exports) {

	'use strict';
	var StringOutputStream = function () {
	    function StringOutputStream() {
	        this._data = [];
	    }
	    StringOutputStream.prototype.write = function (data) {
	        this._data.push(data);
	    };
	    StringOutputStream.prototype.flush = function () {
	        var rv = this._data.join('');
	        this._data = [];
	        return rv;
	    };
	    return StringOutputStream;
	}();
	module.exports = StringOutputStream;


/***/ },
/* 19 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var enums = __webpack_require__(9);
	var opcodes = __webpack_require__(16);
	function makeOnError(onErrorPushes) {
	    return onErrorPushes.length > 0 ? 'f.opStack.pushAll(' + onErrorPushes.join(',') + ');' : '';
	}
	var escapeStringRegEx = /\\/g;
	exports.opJitInfo = function () {
	    var table = [];
	    var OpCode = enums.OpCode;
	    table[OpCode.ACONST_NULL] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ICONST_M1] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=-1;f.pc++;' + onSuccess;
	        }
	    };
	    var load0_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[0];f.pc++;' + onSuccess;
	        }
	    };
	    var load1_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[1];f.pc++;' + onSuccess;
	        }
	    };
	    var load2_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[2];f.pc++;' + onSuccess;
	        }
	    };
	    var load3_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[3];f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ALOAD_0] = load0_32;
	    table[OpCode.ILOAD_0] = load0_32;
	    table[OpCode.FLOAD_0] = load0_32;
	    table[OpCode.ALOAD_1] = load1_32;
	    table[OpCode.ILOAD_1] = load1_32;
	    table[OpCode.FLOAD_1] = load1_32;
	    table[OpCode.ALOAD_2] = load2_32;
	    table[OpCode.ILOAD_2] = load2_32;
	    table[OpCode.FLOAD_2] = load2_32;
	    table[OpCode.ALOAD_3] = load3_32;
	    table[OpCode.ILOAD_3] = load3_32;
	    table[OpCode.FLOAD_3] = load3_32;
	    var load0_64 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[0],' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    var load1_64 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[1],' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    var load2_64 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[2],' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    var load3_64 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=f.locals[3],' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LLOAD_0] = load0_64;
	    table[OpCode.DLOAD_0] = load0_64;
	    table[OpCode.LLOAD_1] = load1_64;
	    table[OpCode.DLOAD_1] = load1_64;
	    table[OpCode.LLOAD_2] = load2_64;
	    table[OpCode.DLOAD_2] = load2_64;
	    table[OpCode.LLOAD_3] = load3_64;
	    table[OpCode.DLOAD_3] = load3_64;
	    var store0_32 = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[0]=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    var store1_32 = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[1]=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    var store2_32 = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[2]=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    var store3_32 = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[3]=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ASTORE_0] = store0_32;
	    table[OpCode.ISTORE_0] = store0_32;
	    table[OpCode.FSTORE_0] = store0_32;
	    table[OpCode.ASTORE_1] = store1_32;
	    table[OpCode.ISTORE_1] = store1_32;
	    table[OpCode.FSTORE_1] = store1_32;
	    table[OpCode.ASTORE_2] = store2_32;
	    table[OpCode.ISTORE_2] = store2_32;
	    table[OpCode.FSTORE_2] = store2_32;
	    table[OpCode.ASTORE_3] = store3_32;
	    table[OpCode.ISTORE_3] = store3_32;
	    table[OpCode.FSTORE_3] = store3_32;
	    var store_64 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var offset = code.readUInt8(pc + 1);
	            return 'f.locals[' + (offset + 1) + ']=' + pops[0] + ';f.locals[' + offset + ']=' + pops[1] + ';f.pc+=2;' + onSuccess;
	        }
	    };
	    var store0_64 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[1]=' + pops[0] + ';f.locals[0]=' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    var store1_64 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[2]=' + pops[0] + ';f.locals[1]=' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    var store2_64 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[3]=' + pops[0] + ';f.locals[2]=' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    var store3_64 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.locals[4]=' + pops[0] + ';f.locals[3]=' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LSTORE] = store_64;
	    table[OpCode.DSTORE] = store_64;
	    table[OpCode.LSTORE_0] = store0_64;
	    table[OpCode.DSTORE_0] = store0_64;
	    table[OpCode.LSTORE_1] = store1_64;
	    table[OpCode.DSTORE_1] = store1_64;
	    table[OpCode.LSTORE_2] = store2_64;
	    table[OpCode.DSTORE_2] = store2_64;
	    table[OpCode.LSTORE_3] = store3_64;
	    table[OpCode.DSTORE_3] = store3_64;
	    var const0_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=0;f.pc++;' + onSuccess;
	        }
	    };
	    var const1_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=1;f.pc++;' + onSuccess;
	        }
	    };
	    var const2_32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=2;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ICONST_0] = const0_32;
	    table[OpCode.ICONST_1] = const1_32;
	    table[OpCode.ICONST_2] = const2_32;
	    table[OpCode.FCONST_0] = const0_32;
	    table[OpCode.FCONST_1] = const1_32;
	    table[OpCode.FCONST_2] = const2_32;
	    table[OpCode.ICONST_3] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=3;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ICONST_4] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=4;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ICONST_5] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=5;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LCONST_0] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=u.gLong.ZERO,' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LCONST_1] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=u.gLong.ONE,' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DCONST_0] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=0,' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DCONST_1] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=1,' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    var aload32 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return '\nif(!u.isNull(t,f,' + pops[1] + ')){\nvar len' + suffix + '=' + pops[1] + '.array.length;\nif(' + pops[0] + '<0||' + pops[0] + '>=len' + suffix + '){\n' + onError + '\nu.throwException(t,f,\'Ljava/lang/ArrayIndexOutOfBoundsException;\',""+' + pops[0] + '+" not in length "+len' + suffix + '+" array of type "+' + pops[1] + '.getClass().getInternalName());\n}else{var ' + pushes[0] + '=' + pops[1] + '.array[' + pops[0] + '];f.pc++;' + onSuccess + '}\n}else{' + onError + '}';
	        }
	    };
	    table[OpCode.IALOAD] = aload32;
	    table[OpCode.FALOAD] = aload32;
	    table[OpCode.AALOAD] = aload32;
	    table[OpCode.BALOAD] = aload32;
	    table[OpCode.CALOAD] = aload32;
	    table[OpCode.SALOAD] = aload32;
	    var aload64 = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return '\nif(!u.isNull(t,f,' + pops[1] + ')){\nvar len' + suffix + '=' + pops[1] + '.array.length;\nif(' + pops[0] + '<0||' + pops[0] + '>=len' + suffix + '){\n' + onError + '\nu.throwException(t,f,\'Ljava/lang/ArrayIndexOutOfBoundsException;\',""+' + pops[0] + '+" not in length "+len' + suffix + '+" array of type "+' + pops[1] + '.getClass().getInternalName());\n}else{var ' + pushes[0] + '=' + pops[1] + '.array[' + pops[0] + '],' + pushes[1] + '=null;f.pc++;' + onSuccess + '}\n}else{' + onError + '}';
	        }
	    };
	    table[OpCode.DALOAD] = aload64;
	    table[OpCode.LALOAD] = aload64;
	    var astore32 = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return '\nif(!u.isNull(t,f,' + pops[2] + ')){\nvar len' + suffix + '=' + pops[2] + '.array.length;\nif(' + pops[1] + '<0||' + pops[1] + '>=len' + suffix + '){\n' + onError + '\nu.throwException(t,f,\'Ljava/lang/ArrayIndexOutOfBoundsException;\',""+' + pops[1] + '+" not in length "+len' + suffix + '+" array of type "+' + pops[2] + '.getClass().getInternalName());\n}else{' + pops[2] + '.array[' + pops[1] + ']=' + pops[0] + ';f.pc++;' + onSuccess + '}\n}else{' + onError + '}';
	        }
	    };
	    table[OpCode.IASTORE] = astore32;
	    table[OpCode.FASTORE] = astore32;
	    table[OpCode.AASTORE] = astore32;
	    table[OpCode.BASTORE] = astore32;
	    table[OpCode.CASTORE] = astore32;
	    table[OpCode.SASTORE] = astore32;
	    var astore64 = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return '\nif(!u.isNull(t,f,' + pops[3] + ')){\nvar len' + suffix + '=' + pops[3] + '.array.length;\nif(' + pops[2] + '<0||' + pops[2] + '>=len' + suffix + '){\n' + onError + '\nu.throwException(t,f,\'Ljava/lang/ArrayIndexOutOfBoundsException;\',""+' + pops[2] + '+" not in length "+len' + suffix + '+" array of type "+' + pops[3] + '.getClass().getInternalName());\n}else{' + pops[3] + '.array[' + pops[2] + ']=' + pops[1] + ';f.pc++;' + onSuccess + '}\n}else{' + onError + '}';
	        }
	    };
	    table[OpCode.DASTORE] = astore64;
	    table[OpCode.LASTORE] = astore64;
	    table[OpCode.LDC] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var index = code.readUInt8(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return '\nvar cnst' + suffix + '=f.method.cls.constantPool.get(' + index + ');\nif(cnst' + suffix + '.isResolved()){var ' + pushes[0] + '=cnst' + suffix + '.getConstant(t);f.pc+=2;' + onSuccess + '\n}else{' + onError + 'u.resolveCPItem(t,f,cnst' + suffix + ');}';
	        }
	    };
	    table[OpCode.LDC_W] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var index = code.readUInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return '\nvar cnst' + suffix + '=f.method.cls.constantPool.get(' + index + ');\nif(cnst' + suffix + '.isResolved()){var ' + pushes[0] + '=cnst' + suffix + '.getConstant(t);f.pc+=3;' + onSuccess + '\n}else{' + onError + 'u.resolveCPItem(t,f,cnst' + suffix + ');}';
	        }
	    };
	    table[OpCode.LDC2_W] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt16BE(pc + 1);
	            return 'var ' + pushes[0] + '=f.method.cls.constantPool.get(' + index + ').value,' + pushes[1] + '=null;f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.GETSTATIC_FAST32] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt16BE(pc + 1);
	            return 'var fi' + suffix + '=f.method.cls.constantPool.get(' + index + '),' + pushes[0] + '=fi' + suffix + '.fieldOwnerConstructor[fi' + suffix + '.fullFieldName];f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.GETSTATIC_FAST64] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt16BE(pc + 1);
	            return '\nvar fi' + suffix + '=f.method.cls.constantPool.get(' + index + '),' + pushes[0] + '=fi' + suffix + '.fieldOwnerConstructor[fi' + suffix + '.fullFieldName],\n' + pushes[1] + '=null;f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.GETFIELD_FAST32] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            var onError = makeOnError(onErrorPushes);
	            var index = code.readUInt16BE(pc + 1);
	            var fieldInfo = method.cls.constantPool.get(index);
	            var name = fieldInfo.fullFieldName.replace(escapeStringRegEx, '\\\\');
	            return 'if(!u.isNull(t,f,' + pops[0] + ')){var ' + pushes[0] + '=' + pops[0] + '[\'' + name + '\'];f.pc+=3;' + onSuccess + '}else{' + onError + '}';
	        }
	    };
	    table[OpCode.GETFIELD_FAST64] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            var onError = makeOnError(onErrorPushes);
	            var index = code.readUInt16BE(pc + 1);
	            var fieldInfo = method.cls.constantPool.get(index);
	            var name = fieldInfo.fullFieldName.replace(escapeStringRegEx, '\\\\');
	            return 'if(!u.isNull(t,f,' + pops[0] + ')){var ' + pushes[0] + '=' + pops[0] + '[\'' + name + '\'],' + pushes[1] + '=null;f.pc+=3;' + onSuccess + '}else{' + onError + '}';
	        }
	    };
	    table[OpCode.PUTFIELD_FAST32] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            var onError = makeOnError(onErrorPushes);
	            var index = code.readUInt16BE(pc + 1);
	            var fieldInfo = method.cls.constantPool.get(index);
	            var name = fieldInfo.fullFieldName.replace(escapeStringRegEx, '\\\\');
	            return 'if(!u.isNull(t,f,' + pops[1] + ')){' + pops[1] + '[\'' + name + '\']=' + pops[0] + ';f.pc+=3;' + onSuccess + '}else{' + onError + '}';
	        }
	    };
	    table[OpCode.PUTFIELD_FAST64] = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            var onError = makeOnError(onErrorPushes);
	            var index = code.readUInt16BE(pc + 1);
	            var fieldInfo = method.cls.constantPool.get(index);
	            var name = fieldInfo.fullFieldName.replace(escapeStringRegEx, '\\\\');
	            return 'if(!u.isNull(t,f,' + pops[2] + ')){' + pops[2] + '[\'' + name + '\']=' + pops[1] + ';f.pc+=3;' + onSuccess + '}else{' + onError + '}';
	        }
	    };
	    table[OpCode.INSTANCEOF_FAST] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt16BE(pc + 1);
	            return 'var cls' + suffix + '=f.method.cls.constantPool.get(' + index + ').cls,' + pushes[0] + '=' + pops[0] + '!==null?(' + pops[0] + '.getClass().isCastable(cls' + suffix + ')?1:0):0;f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.CHECKCAST_FAST] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            var index = code.readUInt16BE(pc + 1);
	            var classRef = method.cls.constantPool.get(index), targetClass = classRef.cls.getExternalName();
	            return 'var cls' + suffix + '=f.method.cls.constantPool.get(' + index + ').cls;\nif((' + pops[0] + '!=null)&&!' + pops[0] + '.getClass().isCastable(cls' + suffix + ')){\nu.throwException(t,f,\'Ljava/lang/ClassCastException;\',' + pops[0] + '.getClass().getExternalName()+\' cannot be cast to ' + targetClass + '\');\n}else{f.pc+=3;var ' + pushes[0] + '=' + pops[0] + ';' + onSuccess + '}';
	        }
	    };
	    table[OpCode.ARRAYLENGTH] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return 'if(!u.isNull(t,f,' + pops[0] + ')){var ' + pushes[0] + '=' + pops[0] + '.array.length;f.pc++;' + onSuccess + '}else{' + onError + '}';
	        }
	    };
	    var load32 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt8(pc + 1);
	            return 'var ' + pushes[0] + '=f.locals[' + index + '];f.pc+=2;' + onSuccess;
	        }
	    };
	    table[OpCode.ILOAD] = load32;
	    table[OpCode.ALOAD] = load32;
	    table[OpCode.FLOAD] = load32;
	    var load64 = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt8(pc + 1);
	            return 'var ' + pushes[0] + '=f.locals[' + index + '],' + pushes[1] + '=null;f.pc+=2;' + onSuccess;
	        }
	    };
	    table[OpCode.LLOAD] = load64;
	    table[OpCode.DLOAD] = load64;
	    var store32 = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt8(pc + 1);
	            return 'f.locals[' + index + ']=' + pops[0] + ';f.pc+=2;' + onSuccess;
	        }
	    };
	    table[OpCode.ISTORE] = store32;
	    table[OpCode.ASTORE] = store32;
	    table[OpCode.FSTORE] = store32;
	    table[OpCode.BIPUSH] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var value = code.readInt8(pc + 1);
	            return 'var ' + pushes[0] + '=' + value + ';f.pc+=2;' + onSuccess;
	        }
	    };
	    table[OpCode.SIPUSH] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var value = code.readInt16BE(pc + 1);
	            return 'var ' + pushes[0] + '=' + value + ';f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.IINC] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var idx = code.readUInt8(pc + 1);
	            var val = code.readInt8(pc + 2);
	            return 'f.locals[' + idx + ']=(f.locals[' + idx + ']+' + val + ')|0;f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.ATHROW] = {
	        hasBranch: true,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return onError + 't.throwException(' + pops[0] + ');f.returnToThreadLoop=true;';
	        }
	    };
	    table[OpCode.GOTO] = {
	        hasBranch: true,
	        pops: 0,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var offset = code.readInt16BE(pc + 1);
	            return 'f.pc+=' + offset + ';' + onSuccess;
	        }
	    };
	    table[OpCode.TABLESWITCH] = {
	        hasBranch: true,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var alignedPC = pc + (4 - (pc + 1) % 4) % 4 + 1;
	            var defaultOffset = code.readInt32BE(alignedPC), low = code.readInt32BE(alignedPC + 4), high = code.readInt32BE(alignedPC + 8);
	            if (high - low < 8) {
	                var emitted = 'switch(' + pops[0] + '){';
	                for (var i = low; i <= high; i++) {
	                    var offset = code.readInt32BE(alignedPC + 12 + (i - low) * 4);
	                    emitted += 'case ' + i + ':f.pc+=' + offset + ';break;';
	                }
	                emitted += 'default:f.pc+=' + defaultOffset + '}' + onSuccess;
	                return emitted;
	            } else {
	                return 'if(' + pops[0] + '>=' + low + '&&' + pops[0] + '<=' + high + '){f.pc+=f.method.getCodeAttribute().getCode().readInt32BE(' + (alignedPC + 12) + '+((' + pops[0] + '-' + low + ')*4))}else{f.pc+=' + defaultOffset + '}' + onSuccess;
	            }
	        }
	    };
	    var cmpeq = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '===' + pops[1] + '){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IF_ICMPEQ] = cmpeq;
	    table[OpCode.IF_ACMPEQ] = cmpeq;
	    var cmpne = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '!==' + pops[1] + '){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IF_ICMPNE] = cmpne;
	    table[OpCode.IF_ACMPNE] = cmpne;
	    table[OpCode.IF_ICMPGE] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[1] + '>=' + pops[0] + '){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IF_ICMPGT] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[1] + '>' + pops[0] + '){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IF_ICMPLE] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[1] + '<=' + pops[0] + '){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IF_ICMPLT] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[1] + '<' + pops[0] + '){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFNULL] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '==null){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFNONNULL] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '!=null){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFEQ] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '===0){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFNE] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '!==0){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFGT] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '>0){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFLT] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '<0){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFGE] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '>=0){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.IFLE] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var offset = code.readInt16BE(pc + 1);
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '<=0){f.pc+=' + offset + ';' + onError + '}else{f.pc+=3;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.LCMP] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '.compare(' + pops[1] + ');f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.FCMPL] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + '===' + pops[1] + '?0:(' + pops[1] + '>' + pops[0] + '?1:-1);f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DCMPL] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '===' + pops[1] + '?0:(' + pops[3] + '>' + pops[1] + '?1:-1);f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.FCMPG] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + '===' + pops[1] + '?0:(' + pops[1] + '<' + pops[0] + '?-1:1);f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DCMPG] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '===' + pops[1] + '?0:(' + pops[3] + '<' + pops[1] + '?-1:1);f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.RETURN] = {
	        hasBranch: true,
	        pops: 0,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            if (method.accessFlags.isSynchronized()) {
	                return 'f.returnToThreadLoop=true;if(!f.method.methodLock(t,f).exit(t)){return}t.asyncReturn();';
	            } else {
	                return 'f.returnToThreadLoop=true;t.asyncReturn();';
	            }
	        }
	    };
	    var return32 = {
	        hasBranch: true,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            if (method.accessFlags.isSynchronized()) {
	                return 'f.returnToThreadLoop=true;if(!f.method.methodLock(t,f).exit(t)){return}t.asyncReturn(' + pops[0] + ');';
	            } else {
	                return 'f.returnToThreadLoop=true;t.asyncReturn(' + pops[0] + ');';
	            }
	        }
	    };
	    table[OpCode.IRETURN] = return32;
	    table[OpCode.FRETURN] = return32;
	    table[OpCode.ARETURN] = return32;
	    var return64 = {
	        hasBranch: true,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes, method) {
	            if (method.accessFlags.isSynchronized()) {
	                return 'f.returnToThreadLoop=true;if(!f.method.methodLock(t,f).exit(t)){return}t.asyncReturn(' + pops[1] + ',null);';
	            } else {
	                return 'f.returnToThreadLoop=true;t.asyncReturn(' + pops[1] + ',null);';
	            }
	        }
	    };
	    table[OpCode.LRETURN] = return64;
	    table[OpCode.DRETURN] = return64;
	    table[OpCode.MONITOREXIT] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '.getMonitor().exit(t)){f.pc++;' + onSuccess + '}else{' + onError + 'f.returnToThreadLoop=true;}';
	        }
	    };
	    table[OpCode.IXOR] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + '^' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LXOR] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '.xor(' + pops[3] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IOR] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + '|' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LOR] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '.or(' + pops[1] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IAND] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + '&' + pops[1] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LAND] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '.and(' + pops[1] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IADD] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=(' + pops[0] + '+' + pops[1] + ')|0;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LADD] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '.add(' + pops[3] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DADD] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '+' + pops[3] + ',' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IMUL] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=Math.imul(' + pops[0] + ', ' + pops[1] + ');f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.FMUL] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=u.wrapFloat(' + pops[0] + '*' + pops[1] + ');f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LMUL] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '.multiply(' + pops[1] + '),' + pushes[1] + '= null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DMUL] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '*' + pops[1] + ',' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IDIV] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return '\nif(' + pops[0] + '===0){' + onError + 'u.throwException(t,f,\'Ljava/lang/ArithmeticException;\',\'/ by zero\');\n}else{var ' + pushes[0] + '=(' + pops[1] + '===u.Constants.INT_MIN&&' + pops[0] + '===-1)?' + pops[1] + ':((' + pops[1] + '/' + pops[0] + ')|0);f.pc++;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.LDIV] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return '\nif(' + pops[1] + '.isZero()){' + onError + 'u.throwException(t,f,\'Ljava/lang/ArithmeticException;\',\'/ by zero\');\n}else{var ' + pushes[0] + '=' + pops[3] + '.div(' + pops[1] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.DDIV] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '/' + pops[1] + ',' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ISUB] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=(' + pops[1] + '-' + pops[0] + ')|0;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LSUB] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '.negate().add(' + pops[3] + '),' + pushes[1] + '= null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DSUB] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '-' + pops[1] + ',' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IREM] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[0] + '===0){' + onError + 'u.throwException(t,f,\'Ljava/lang/ArithmeticException;\',\'/ by zero\');\n}else{var ' + pushes[0] + '=' + pops[1] + '%' + pops[0] + ';f.pc++;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.LREM] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var onError = makeOnError(onErrorPushes);
	            return 'if(' + pops[1] + '.isZero()){' + onError + 'u.throwException(t,f,\'Ljava/lang/ArithmeticException;\',\'/ by zero\');\n}else{var ' + pushes[0] + '=' + pops[3] + '.modulo(' + pops[1] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess + '}';
	        }
	    };
	    table[OpCode.DREM] = {
	        hasBranch: false,
	        pops: 4,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[3] + '%' + pops[1] + ',' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.INEG] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=(-' + pops[0] + ')|0;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LNEG] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '.negate(),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ISHL] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '<<' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LSHL] = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[2] + '.shiftLeft(u.gLong.fromInt(' + pops[0] + ')),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.ISHR] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '>>' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LSHR] = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[2] + '.shiftRight(u.gLong.fromInt(' + pops[0] + ')),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.IUSHR] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=(' + pops[1] + '>>>' + pops[0] + ')|0;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.LUSHR] = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[2] + '.shiftRightUnsigned(u.gLong.fromInt(' + pops[0] + ')),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.I2B] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=(' + pops[0] + '<<24)>>24;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.I2S] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=(' + pops[0] + '<<16)>>16;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.I2C] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + '&0xFFFF;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.I2L] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=u.gLong.fromInt(' + pops[0] + '),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.I2F] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.I2D] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.F2I] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=u.float2int(' + pops[0] + ');f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.F2D] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.L2I] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '.toInt();f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.L2D] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + '.toNumber(),' + pushes[1] + '=null;f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.D2I] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=u.float2int(' + pops[1] + ');f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DUP] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 2,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + ',' + pushes[1] + '=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DUP2] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 4,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + ',' + pushes[1] + '=' + pops[0] + ',' + pushes[2] + '=' + pops[1] + ',' + pushes[3] + '=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DUP_X1] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 3,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + ',' + pushes[1] + '=' + pops[1] + ',' + pushes[2] + '=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DUP_X2] = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 4,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[0] + ',' + pushes[1] + '=' + pops[2] + ',' + pushes[2] + '=' + pops[1] + ',' + pushes[3] + '=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.DUP2_X1] = {
	        hasBranch: false,
	        pops: 3,
	        pushes: 5,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'var ' + pushes[0] + '=' + pops[1] + ',' + pushes[1] + '=' + pops[0] + ',' + pushes[2] + '=' + pops[2] + ',' + pushes[3] + '=' + pops[1] + ',' + pushes[4] + '=' + pops[0] + ';f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.NEW_FAST] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc) {
	            var index = code.readUInt16BE(pc + 1);
	            return 'var cr' + suffix + '=f.method.cls.constantPool.get(' + index + '),' + pushes[0] + '=(new cr' + suffix + '.clsConstructor(t));f.pc+=3;' + onSuccess;
	        }
	    };
	    table[OpCode.NEWARRAY] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var index = code.readUInt8(pc + 1);
	            var arrayType = '[' + opcodes.ArrayTypes[index];
	            var onError = makeOnError(onErrorPushes);
	            return '\nvar cls' + suffix + '=f.getLoader().getInitializedClass(t,\'' + arrayType + '\');\nif(' + pops[0] + '>=0){var ' + pushes[0] + '=new (cls' + suffix + '.getConstructor(t))(t,' + pops[0] + ');f.pc+=2;' + onSuccess + '\n}else{' + onError + 'u.throwException(t,f,\'Ljava/lang/NegativeArraySizeException;\',\'Tried to init ' + arrayType + ' array with length \'+' + pops[0] + ');}';
	        }
	    };
	    table[OpCode.ANEWARRAY_FAST] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 1,
	        emit: function (pops, pushes, suffix, onSuccess, code, pc, onErrorPushes) {
	            var index = code.readUInt16BE(pc + 1);
	            var arrayType = '[' + opcodes.ArrayTypes[index];
	            var onError = makeOnError(onErrorPushes);
	            return '\nvar cr' + suffix + '=f.method.cls.constantPool.get(' + index + ');\nif(' + pops[0] + '>=0){var ' + pushes[0] + '=new cr' + suffix + '.arrayClassConstructor(t,' + pops[0] + ');f.pc+=3;' + onSuccess + '\n}else{' + onError + 'u.throwException(t,f,\'Ljava/lang/NegativeArraySizeException;\',\'Tried to init \'+cr' + suffix + '.arrayClass.getInternalName()+\' array with length \'+' + pops[0] + ');}';
	        }
	    };
	    table[OpCode.NOP] = {
	        hasBranch: false,
	        pops: 0,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.POP] = {
	        hasBranch: false,
	        pops: 1,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.pc++;' + onSuccess;
	        }
	    };
	    table[OpCode.POP2] = {
	        hasBranch: false,
	        pops: 2,
	        pushes: 0,
	        emit: function (pops, pushes, suffix, onSuccess) {
	            return 'f.pc++;' + onSuccess;
	        }
	    };
	    return table;
	}();


/***/ },
/* 20 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var __extends = this && this.__extends || function (d, b) {
	    for (var p in b)
	        if (b.hasOwnProperty(p))
	            d[p] = b[p];
	    function __() {
	        this.constructor = d;
	    }
	    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
	};
	var ClassData_1 = __webpack_require__(21);
	var ClassLock = __webpack_require__(24);
	var classpath_1 = __webpack_require__(26);
	var enums_1 = __webpack_require__(9);
	var util = __webpack_require__(6);
	var logging = __webpack_require__(17);
	var assert = __webpack_require__(13);
	var debug = logging.debug;
	var ClassLocks = function () {
	    function ClassLocks() {
	        this.locks = {};
	    }
	    ClassLocks.prototype.tryLock = function (typeStr, thread, cb) {
	        if (typeof this.locks[typeStr] === 'undefined') {
	            this.locks[typeStr] = new ClassLock();
	        }
	        return this.locks[typeStr].tryLock(thread, cb);
	    };
	    ClassLocks.prototype.unlock = function (typeStr, cdata) {
	        this.locks[typeStr].unlock(cdata);
	        delete this.locks[typeStr];
	    };
	    ClassLocks.prototype.getOwner = function (typeStr) {
	        if (this.locks[typeStr]) {
	            return this.locks[typeStr].getOwner();
	        }
	        return null;
	    };
	    return ClassLocks;
	}();
	var ClassLoader = function () {
	    function ClassLoader(bootstrap) {
	        this.bootstrap = bootstrap;
	        this.loadedClasses = {};
	        this.loadClassLocks = new ClassLocks();
	    }
	    ClassLoader.prototype.getLoadedClassNames = function () {
	        return Object.keys(this.loadedClasses);
	    };
	    ClassLoader.prototype.addClass = function (typeStr, classData) {
	        assert(this.loadedClasses[typeStr] != null ? this.loadedClasses[typeStr] === classData : true);
	        this.loadedClasses[typeStr] = classData;
	    };
	    ClassLoader.prototype.getClass = function (typeStr) {
	        return this.loadedClasses[typeStr];
	    };
	    ClassLoader.prototype.defineClass = function (thread, typeStr, data, protectionDomain) {
	        try {
	            var classData = new ClassData_1.ReferenceClassData(data, protectionDomain, this);
	            this.addClass(typeStr, classData);
	            if (this instanceof BootstrapClassLoader) {
	                ;
	            } else {
	                ;
	            }
	            return classData;
	        } catch (e) {
	            if (thread === null) {
	                logging.error('JVM initialization failed: ' + e);
	                logging.error(e.stack);
	            } else {
	                thread.throwNewException('Ljava/lang/ClassFormatError;', e);
	            }
	            return null;
	        }
	    };
	    ClassLoader.prototype.defineArrayClass = function (typeStr) {
	        assert(this.getLoadedClass(util.get_component_type(typeStr)) != null);
	        var arrayClass = new ClassData_1.ArrayClassData(util.get_component_type(typeStr), this);
	        this.addClass(typeStr, arrayClass);
	        return arrayClass;
	    };
	    ClassLoader.prototype.getLoadedClass = function (typeStr) {
	        var cls = this.loadedClasses[typeStr];
	        if (cls != null) {
	            return cls;
	        } else {
	            if (util.is_primitive_type(typeStr)) {
	                return this.bootstrap.getPrimitiveClass(typeStr);
	            } else if (util.is_array_type(typeStr)) {
	                var component = this.getLoadedClass(util.get_component_type(typeStr));
	                if (component != null) {
	                    var componentCl = component.getLoader();
	                    if (componentCl === this) {
	                        return this.defineArrayClass(typeStr);
	                    } else {
	                        cls = componentCl.getLoadedClass(typeStr);
	                        this.addClass(typeStr, cls);
	                        return cls;
	                    }
	                }
	            }
	            return null;
	        }
	    };
	    ClassLoader.prototype.getResolvedClass = function (typeStr) {
	        var cls = this.getLoadedClass(typeStr);
	        if (cls !== null) {
	            if (cls.isResolved() || cls.tryToResolve()) {
	                return cls;
	            } else {
	                return null;
	            }
	        } else {
	            return null;
	        }
	    };
	    ClassLoader.prototype.getInitializedClass = function (thread, typeStr) {
	        var cls = this.getLoadedClass(typeStr);
	        if (cls !== null) {
	            if (cls.isInitialized(thread) || cls.tryToInitialize()) {
	                return cls;
	            } else {
	                return null;
	            }
	        } else {
	            return cls;
	        }
	    };
	    ClassLoader.prototype.loadClass = function (thread, typeStr, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        var cdata = this.getLoadedClass(typeStr);
	        if (cdata) {
	            setImmediate(function () {
	                cb(cdata);
	            });
	        } else {
	            if (this.loadClassLocks.tryLock(typeStr, thread, cb)) {
	                if (util.is_reference_type(typeStr)) {
	                    this._loadClass(thread, typeStr, function (cdata) {
	                        _this.loadClassLocks.unlock(typeStr, cdata);
	                    }, explicit);
	                } else {
	                    this.loadClass(thread, util.get_component_type(typeStr), function (cdata) {
	                        if (cdata != null) {
	                            _this.loadClassLocks.unlock(typeStr, _this.getLoadedClass(typeStr));
	                        }
	                    }, explicit);
	                }
	            }
	        }
	    };
	    ClassLoader.prototype.resolveClasses = function (thread, typeStrs, cb) {
	        var _this = this;
	        var classes = {};
	        util.asyncForEach(typeStrs, function (typeStr, next_item) {
	            _this.resolveClass(thread, typeStr, function (cdata) {
	                if (cdata === null) {
	                    next_item('Error resolving class: ' + typeStr);
	                } else {
	                    classes[typeStr] = cdata;
	                    next_item();
	                }
	            });
	        }, function (err) {
	            if (err) {
	                cb(null);
	            } else {
	                cb(classes);
	            }
	        });
	    };
	    ClassLoader.prototype.resolveClass = function (thread, typeStr, cb, explicit) {
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        this.loadClass(thread, typeStr, function (cdata) {
	            if (cdata === null || cdata.isResolved()) {
	                setImmediate(function () {
	                    cb(cdata);
	                });
	            } else {
	                cdata.resolve(thread, cb, explicit);
	            }
	        }, explicit);
	    };
	    ClassLoader.prototype.initializeClass = function (thread, typeStr, cb, explicit) {
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        this.resolveClass(thread, typeStr, function (cdata) {
	            if (cdata === null || cdata.isInitialized(thread)) {
	                setImmediate(function () {
	                    cb(cdata);
	                });
	            } else {
	                assert(util.is_reference_type(typeStr));
	                cdata.initialize(thread, cb, explicit);
	            }
	        }, explicit);
	    };
	    ClassLoader.prototype.throwClassNotFoundException = function (thread, typeStr, explicit) {
	        thread.throwNewException(explicit ? 'Ljava/lang/ClassNotFoundException;' : 'Ljava/lang/NoClassDefFoundError;', 'Cannot load class: ' + util.ext_classname(typeStr));
	    };
	    return ClassLoader;
	}();
	exports.ClassLoader = ClassLoader;
	var BootstrapClassLoader = function (_super) {
	    __extends(BootstrapClassLoader, _super);
	    function BootstrapClassLoader(javaHome, classpath, cb) {
	        var _this = this;
	        _super.call(this, null);
	        this.bootstrap = this;
	        this.classpath = null;
	        this.loadedPackages = {};
	        classpath_1.ClasspathFactory(javaHome, classpath, function (items) {
	            _this.classpath = items.reverse();
	            cb();
	        });
	    }
	    BootstrapClassLoader.prototype._registerLoadedClass = function (clsType, cpItem) {
	        var pkgName = clsType.slice(0, clsType.lastIndexOf('/')), itemLoader = this.loadedPackages[pkgName];
	        if (!itemLoader) {
	            this.loadedPackages[pkgName] = [cpItem];
	        } else if (itemLoader[0] !== cpItem && itemLoader.indexOf(cpItem) === -1) {
	            itemLoader.push(cpItem);
	        }
	    };
	    BootstrapClassLoader.prototype.getPackages = function () {
	        var _this = this;
	        return Object.keys(this.loadedPackages).map(function (pkgName) {
	            return [
	                pkgName,
	                _this.loadedPackages[pkgName].map(function (item) {
	                    return item.getPath();
	                })
	            ];
	        });
	    };
	    BootstrapClassLoader.prototype.getPrimitiveClass = function (typeStr) {
	        var cdata = this.getClass(typeStr);
	        if (cdata == null) {
	            cdata = new ClassData_1.PrimitiveClassData(typeStr, this);
	            this.addClass(typeStr, cdata);
	        }
	        return cdata;
	    };
	    BootstrapClassLoader.prototype._loadClass = function (thread, typeStr, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        ;
	        assert(util.is_reference_type(typeStr));
	        var clsFilePath = util.descriptor2typestr(typeStr), cPathLen = this.classpath.length, toSearch = [], clsData;
	        searchLoop:
	            for (var i = 0; i < cPathLen; i++) {
	                var item = this.classpath[i];
	                switch (item.hasClass(clsFilePath)) {
	                case enums_1.TriState.INDETERMINATE:
	                    toSearch.push(item);
	                    break;
	                case enums_1.TriState.TRUE:
	                    toSearch.push(item);
	                    break searchLoop;
	                }
	            }
	        util.asyncFind(toSearch, function (pItem, callback) {
	            pItem.loadClass(clsFilePath, function (err, data) {
	                if (err) {
	                    callback(false);
	                } else {
	                    clsData = data;
	                    callback(true);
	                }
	            });
	        }, function (pItem) {
	            if (pItem) {
	                var cls = _this.defineClass(thread, typeStr, clsData, null);
	                if (cls !== null) {
	                    _this._registerLoadedClass(clsFilePath, pItem);
	                }
	                cb(cls);
	            } else {
	                ;
	                _this.throwClassNotFoundException(thread, typeStr, explicit);
	                cb(null);
	            }
	        });
	    };
	    BootstrapClassLoader.prototype.getLoadedClassFiles = function () {
	        var loadedClasses = this.getLoadedClassNames();
	        return loadedClasses.filter(function (clsName) {
	            return util.is_reference_type(clsName);
	        });
	    };
	    BootstrapClassLoader.prototype.getLoaderObject = function () {
	        return null;
	    };
	    BootstrapClassLoader.prototype.getClassPath = function () {
	        var cpLen = this.classpath.length, cpStrings = new Array(cpLen);
	        for (var i = 0; i < cpLen; i++) {
	            cpStrings[i] = this.classpath[cpLen - i - 1].getPath();
	        }
	        return cpStrings;
	    };
	    BootstrapClassLoader.prototype.getClassPathItems = function () {
	        return this.classpath.slice(0);
	    };
	    return BootstrapClassLoader;
	}(ClassLoader);
	exports.BootstrapClassLoader = BootstrapClassLoader;
	var CustomClassLoader = function (_super) {
	    __extends(CustomClassLoader, _super);
	    function CustomClassLoader(bootstrap, loaderObj) {
	        _super.call(this, bootstrap);
	        this.loaderObj = loaderObj;
	    }
	    CustomClassLoader.prototype._loadClass = function (thread, typeStr, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        ;
	        assert(util.is_reference_type(typeStr));
	        this.loaderObj['loadClass(Ljava/lang/String;)Ljava/lang/Class;'](thread, [util.initString(this.bootstrap, util.ext_classname(typeStr))], function (e, jco) {
	            if (e) {
	                _this.throwClassNotFoundException(thread, typeStr, explicit);
	                cb(null);
	            } else {
	                var cls = jco.$cls;
	                _this.addClass(typeStr, cls);
	                cb(cls);
	            }
	        });
	    };
	    CustomClassLoader.prototype.getLoaderObject = function () {
	        return this.loaderObj;
	    };
	    return CustomClassLoader;
	}(ClassLoader);
	exports.CustomClassLoader = CustomClassLoader;


/***/ },
/* 21 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var __extends = this && this.__extends || function (d, b) {
	    for (var p in b)
	        if (b.hasOwnProperty(p))
	            d[p] = b[p];
	    function __() {
	        this.constructor = d;
	    }
	    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
	};
	var util = __webpack_require__(6);
	var ByteStream = __webpack_require__(22);
	var ConstantPool = __webpack_require__(23);
	var attributes = __webpack_require__(12);
	var threading_1 = __webpack_require__(15);
	var logging = __webpack_require__(17);
	var methods = __webpack_require__(11);
	var enums = __webpack_require__(9);
	var ClassLock = __webpack_require__(24);
	var assert = __webpack_require__(13);
	var gLong = __webpack_require__(8);
	var StringOutputStream = __webpack_require__(18);
	var ClassState = enums.ClassState;
	var trace = logging.trace;
	var debug = logging.debug;
	var global = __webpack_require__(14);
	if (typeof RELEASE === 'undefined')
	    global.RELEASE = false;
	var ref = 1;
	var injectedFields = {
	    'Ljava/lang/invoke/MemberName;': {
	        vmtarget: [
	            '(thread: JVMThread, descriptor: string, args: any[], cb?: (e?: JVMTypes.java_lang_Throwable, rv?: any) => void) => void',
	            'null'
	        ],
	        vmindex: [
	            'number',
	            '-1'
	        ]
	    },
	    'Ljava/lang/Object;': {
	        'ref': [
	            'number',
	            'ref++'
	        ],
	        '$monitor': [
	            'Monitor',
	            'null'
	        ]
	    },
	    'Ljava/net/PlainSocketImpl;': {
	        '$is_shutdown': [
	            'boolean',
	            'false'
	        ],
	        '$ws': [
	            'Interfaces.IWebsock',
	            'null'
	        ]
	    },
	    'Ljava/io/FileDescriptor;': {
	        '$pos': [
	            'number',
	            '-1'
	        ]
	    },
	    'Ljava/lang/Class;': {
	        '$cls': [
	            'ClassData',
	            'null'
	        ]
	    },
	    'Ljava/lang/ClassLoader;': {
	        '$loader': [
	            'ClassLoader',
	            'new ClassLoader.CustomClassLoader(thread.getBsCl(), this);'
	        ]
	    },
	    'Ljava/lang/Thread;': {
	        '$thread': [
	            'JVMThread',
	            'thread ? new thread.constructor(thread.getJVM(), thread.getThreadPool(), this) : null'
	        ]
	    }
	};
	var injectedMethods = {
	    'Ljava/lang/Object;': {
	        'getClass': [
	            '(): ClassData',
	            'function() { return this.constructor.cls }'
	        ],
	        'getMonitor': [
	            '(): Monitor',
	            'function() {\n  if (this.$monitor === null) {\n    this.$monitor = new Monitor();\n  }\n  return this.$monitor;\n}'
	        ]
	    },
	    'Ljava/lang/String;': {
	        'toString': [
	            '(): string',
	            'function() { return util.chars2jsStr(this[\'java/lang/String/value\']); }'
	        ]
	    },
	    'Ljava/lang/Byte;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Byte/value\']; }'
	        ]
	    },
	    'Ljava/lang/Character;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Character/value\']; }'
	        ]
	    },
	    'Ljava/lang/Double;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Double/value\']; }'
	        ]
	    },
	    'Ljava/lang/Float;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Float/value\']; }'
	        ]
	    },
	    'Ljava/lang/Integer;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Integer/value\']; }'
	        ]
	    },
	    'Ljava/lang/Long;': {
	        'unbox': [
	            '(): Long',
	            'function() { return this[\'java/lang/Long/value\']; }'
	        ]
	    },
	    'Ljava/lang/Short;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Short/value\']; }'
	        ]
	    },
	    'Ljava/lang/Boolean;': {
	        'unbox': [
	            '(): number',
	            'function() { return this[\'java/lang/Boolean/value\']; }'
	        ]
	    },
	    'Ljava/lang/Void;': {
	        'unbox': [
	            '(): number',
	            'function() { throw new Error("Cannot unbox a Void type."); }'
	        ]
	    },
	    'Ljava/lang/invoke/MethodType;': {
	        'toString': [
	            '(): string',
	            'function() { return "(" + this[\'java/lang/invoke/MethodType/ptypes\'].array.map(function (type) { return type.$cls.getInternalName(); }).join("") + ")" + this[\'java/lang/invoke/MethodType/rtype\'].$cls.getInternalName(); }'
	        ]
	    }
	};
	var injectedStaticMethods = {
	    'Ljava/lang/Byte;': {
	        'box': [
	            '(val: number): java_lang_Byte',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Byte/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Character;': {
	        'box': [
	            '(val: number): java_lang_Character',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Character/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Double;': {
	        'box': [
	            '(val: number): java_lang_Double',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Double/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Float;': {
	        'box': [
	            '(val: number): java_lang_Float',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Float/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Integer;': {
	        'box': [
	            '(val: number): java_lang_Integer',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Integer/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Long;': {
	        'box': [
	            '(val: Long): java_lang_Long',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Long/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Short;': {
	        'box': [
	            '(val: number): java_lang_Short',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Short/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Boolean;': {
	        'box': [
	            '(val: number): java_lang_Boolean',
	            'function(val) { var rv = new this(null); rv[\'java/lang/Boolean/value\'] = val; return rv; }'
	        ]
	    },
	    'Ljava/lang/Void;': {
	        'box': [
	            '(): java_lang_Void',
	            'function() { return new this(null); }'
	        ]
	    }
	};
	function extendClass(cls, superCls) {
	    function __() {
	        this.constructor = cls;
	    }
	    __.prototype = superCls.prototype;
	    cls.prototype = new __();
	}
	var ClassData = function () {
	    function ClassData(loader) {
	        this.accessFlags = null;
	        this.state = enums.ClassState.LOADED;
	        this.jco = null;
	        this.superClass = null;
	        this.loader = loader;
	    }
	    ClassData.prototype.getExternalName = function () {
	        return util.ext_classname(this.className);
	    };
	    ClassData.prototype.getInternalName = function () {
	        return this.className;
	    };
	    ClassData.prototype.getPackageName = function () {
	        var extName = this.getExternalName(), i;
	        for (i = extName.length - 1; i >= 0 && extName[i] !== '.'; i--) {
	        }
	        if (i >= 0) {
	            return extName.slice(0, i);
	        } else {
	            return '';
	        }
	    };
	    ClassData.prototype.getLoader = function () {
	        return this.loader;
	    };
	    ClassData.prototype.getSuperClass = function () {
	        return this.superClass;
	    };
	    ClassData.prototype.getInterfaces = function () {
	        return [];
	    };
	    ClassData.prototype.getInjectedFields = function () {
	        var rv = {};
	        if (injectedFields[this.getInternalName()] !== undefined) {
	            var fields = injectedFields[this.getInternalName()];
	            Object.keys(fields).forEach(function (fieldName) {
	                rv[fieldName] = fields[fieldName][0];
	            });
	        }
	        return rv;
	    };
	    ClassData.prototype.getInjectedMethods = function () {
	        var rv = {}, lookupName = this.getInternalName();
	        if (lookupName[0] === '[') {
	            lookupName = '[';
	        }
	        if (injectedMethods[lookupName] !== undefined) {
	            var methods = injectedMethods[lookupName];
	            Object.keys(methods).forEach(function (methodName) {
	                rv[methodName] = methods[methodName][0];
	            });
	        }
	        return rv;
	    };
	    ClassData.prototype.getInjectedStaticMethods = function () {
	        var rv = {}, lookupName = this.getInternalName();
	        if (lookupName[0] === '[') {
	            lookupName = '[';
	        }
	        if (injectedStaticMethods[lookupName] !== undefined) {
	            var methods = injectedStaticMethods[lookupName];
	            Object.keys(methods).forEach(function (methodName) {
	                rv[methodName] = methods[methodName][0];
	            });
	        }
	        return rv;
	    };
	    ClassData.prototype.getClassObject = function (thread) {
	        if (this.jco === null) {
	            this.jco = new (thread.getBsCl().getResolvedClass('Ljava/lang/Class;').getConstructor(thread))(thread);
	            this.jco.$cls = this;
	            this.jco['java/lang/Class/classLoader'] = this.getLoader().getLoaderObject();
	        }
	        return this.jco;
	    };
	    ClassData.prototype.getProtectionDomain = function () {
	        return null;
	    };
	    ClassData.prototype.getMethod = function (methodSignature) {
	        return null;
	    };
	    ClassData.prototype.getMethods = function () {
	        return [];
	    };
	    ClassData.prototype.getFields = function () {
	        return [];
	    };
	    ClassData.prototype.setState = function (state) {
	        this.state = state;
	    };
	    ClassData.prototype.getState = function () {
	        if (this.state === ClassState.RESOLVED && this.getMethod('<clinit>()V') === null) {
	            var scls = this.getSuperClass();
	            if (scls !== null && scls.getState() === ClassState.INITIALIZED) {
	                this.state = ClassState.INITIALIZED;
	            }
	        }
	        return this.state;
	    };
	    ClassData.prototype.isInitialized = function (thread) {
	        return this.getState() === ClassState.INITIALIZED;
	    };
	    ClassData.prototype.isResolved = function () {
	        return this.getState() !== ClassState.LOADED;
	    };
	    ClassData.prototype.isSubinterface = function (target) {
	        return false;
	    };
	    ClassData.prototype.isSubclass = function (target) {
	        if (this === target) {
	            return true;
	        }
	        if (this.getSuperClass() === null) {
	            return false;
	        }
	        return this.getSuperClass().isSubclass(target);
	    };
	    ClassData.prototype.resolve = function (thread, cb, explicit) {
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        throw new Error('Unimplemented.');
	    };
	    ClassData.prototype.initialize = function (thread, cb, explicit) {
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        throw new Error('Unimplemented.');
	    };
	    ClassData.prototype.outputInjectedMethods = function (jsClassName, outputStream) {
	        var lookupName = this.getInternalName();
	        if (lookupName[0] === '[') {
	            lookupName = '[';
	        }
	        if (injectedMethods[lookupName] !== undefined) {
	            var methods = injectedMethods[lookupName];
	            Object.keys(methods).forEach(function (methodName) {
	                outputStream.write('  ' + jsClassName + '.prototype.' + methodName + ' = ' + methods[methodName][1] + ';\n');
	            });
	        }
	        if (injectedStaticMethods[lookupName] !== undefined) {
	            var staticMethods = injectedStaticMethods[lookupName];
	            Object.keys(staticMethods).forEach(function (methodName) {
	                outputStream.write('  ' + jsClassName + '.' + methodName + ' = ' + staticMethods[methodName][1] + ';\n');
	            });
	        }
	    };
	    return ClassData;
	}();
	exports.ClassData = ClassData;
	var PrimitiveClassData = function (_super) {
	    __extends(PrimitiveClassData, _super);
	    function PrimitiveClassData(className, loader) {
	        _super.call(this, loader);
	        this.className = className;
	        this.accessFlags = new util.Flags(1041);
	        this.setState(ClassState.INITIALIZED);
	    }
	    PrimitiveClassData.prototype.isCastable = function (target) {
	        return this.className === target.getInternalName();
	    };
	    PrimitiveClassData.prototype.boxClassName = function () {
	        return util.boxClassName(this.className);
	    };
	    PrimitiveClassData.prototype.createWrapperObject = function (thread, value) {
	        var boxName = this.boxClassName();
	        var boxCls = thread.getBsCl().getInitializedClass(thread, boxName);
	        var boxCons = boxCls.getConstructor(thread);
	        var wrapped = new boxCons(thread);
	        if (boxName !== 'V') {
	            wrapped[util.descriptor2typestr(boxName) + '/value'] = value;
	            assert(typeof value === 'number' || typeof value === 'boolean' || typeof value.low_ === 'number', 'Invalid primitive value: ' + value);
	        }
	        return wrapped;
	    };
	    PrimitiveClassData.prototype.tryToResolve = function () {
	        return true;
	    };
	    PrimitiveClassData.prototype.tryToInitialize = function () {
	        return true;
	    };
	    PrimitiveClassData.prototype.resolve = function (thread, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        setImmediate(function () {
	            return cb(_this);
	        });
	    };
	    return PrimitiveClassData;
	}(ClassData);
	exports.PrimitiveClassData = PrimitiveClassData;
	var ArrayClassData = function (_super) {
	    __extends(ArrayClassData, _super);
	    function ArrayClassData(componentType, loader) {
	        _super.call(this, loader);
	        this._constructor = null;
	        this.className = '[' + componentType;
	        this.accessFlags = new util.Flags(1041);
	        this.componentClassName = componentType;
	    }
	    ArrayClassData.prototype.methodLookup = function (signature) {
	        return this.superClass.methodLookup(signature);
	    };
	    ArrayClassData.prototype.fieldLookup = function (name) {
	        return this.superClass.fieldLookup(name);
	    };
	    ArrayClassData.prototype.resolve = function (thread, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        if (this.isResolved()) {
	            setImmediate(function () {
	                return cb(_this);
	            });
	            return;
	        }
	        util.asyncForEach([
	            'Ljava/lang/Object;',
	            this.componentClassName
	        ], function (cls, nextItem) {
	            _this.loader.resolveClass(thread, cls, function (cdata) {
	                if (cdata !== null) {
	                    nextItem();
	                } else {
	                    nextItem('Failed.');
	                }
	            });
	        }, function (err) {
	            if (!err) {
	                _this.setResolved(_this.loader.getResolvedClass('Ljava/lang/Object;'), _this.loader.getResolvedClass(_this.componentClassName));
	                cb(_this);
	            } else {
	                cb(null);
	            }
	        });
	    };
	    ArrayClassData.prototype.getComponentClass = function () {
	        return this.componentClass;
	    };
	    ArrayClassData.prototype.setResolved = function (super_class_cdata, component_class_cdata) {
	        this.superClass = super_class_cdata;
	        this.componentClass = component_class_cdata;
	        this.setState(ClassState.INITIALIZED);
	    };
	    ArrayClassData.prototype.tryToResolve = function () {
	        var loader = this.loader, superClassCdata = loader.getResolvedClass('Ljava/lang/Object;'), componentClassCdata = loader.getResolvedClass(this.componentClassName);
	        if (superClassCdata === null || componentClassCdata === null) {
	            return false;
	        } else {
	            this.setResolved(superClassCdata, componentClassCdata);
	            return true;
	        }
	    };
	    ArrayClassData.prototype.tryToInitialize = function () {
	        return this.tryToResolve();
	    };
	    ArrayClassData.prototype.isCastable = function (target) {
	        if (!(target instanceof ArrayClassData)) {
	            if (target instanceof PrimitiveClassData) {
	                return false;
	            }
	            if (target.accessFlags.isInterface()) {
	                var type = target.getInternalName();
	                return type === 'Ljava/lang/Cloneable;' || type === 'Ljava/io/Serializable;';
	            }
	            return target.getInternalName() === 'Ljava/lang/Object;';
	        }
	        return this.getComponentClass().isCastable(target.getComponentClass());
	    };
	    ArrayClassData.prototype.initialize = function (thread, cb, explicit) {
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        this.resolve(thread, cb, explicit);
	    };
	    ArrayClassData.prototype.getJSArrayConstructor = function () {
	        if (!util.typedArraysSupported) {
	            return 'Array';
	        }
	        switch (this.componentClassName) {
	        case 'B':
	            return 'Int8Array';
	        case 'C':
	            return 'Uint16Array';
	        case 'S':
	            return 'Int16Array';
	        case 'I':
	            return 'Int32Array';
	        case 'F':
	            return 'Float32Array';
	        case 'D':
	            return 'Float64Array';
	        default:
	            return 'Array';
	        }
	    };
	    ArrayClassData.prototype.getJSDefaultArrayElement = function () {
	        switch (this.componentClassName[0]) {
	        case '[':
	            return 'new (cls.getComponentClass().getConstructor())(thread, otherLengths)';
	        case 'L':
	            return 'null';
	        case 'J':
	            return 'gLongZero';
	        default:
	            return '0';
	        }
	    };
	    ArrayClassData.prototype._getSliceMethod = function () {
	        var output = new StringOutputStream(), jsArrCons = this.getJSArrayConstructor();
	        output.write('function(start, end) {\n    var newObj = new this.constructor(null, 0);\n');
	        if (jsArrCons === 'Array') {
	            output.write('    newObj.array = this.array.slice(start, end);\n');
	        } else {
	            var elementSize;
	            switch (jsArrCons) {
	            case 'Int8Array':
	                elementSize = 1;
	                break;
	            case 'Int16Array':
	            case 'Uint16Array':
	                elementSize = 2;
	                break;
	            case 'Int32Array':
	            case 'Float32Array':
	                elementSize = 4;
	                break;
	            case 'Float64Array':
	                elementSize = 8;
	                break;
	            default:
	                assert(false, 'Illegal array type returned??');
	            }
	            output.write('    if (end === undefined) end = this.array.length;\n      ' + (elementSize > 1 ? 'start *= ' + elementSize + ';\nend *= ' + elementSize + ';' : '') + '\n      newObj.array = new ' + jsArrCons + '(this.array.buffer.slice(start, end));\n');
	        }
	        output.write('    return newObj;\n  }');
	        return output.flush();
	    };
	    ArrayClassData.prototype._constructConstructor = function (thread) {
	        assert(this._constructor === null, 'Tried to construct constructor twice for ' + this.getExternalName() + '!');
	        var outputStream = new StringOutputStream(), jsClassName = util.jvmName2JSName(this.getInternalName());
	        outputStream.write('function _create(extendClass, cls, superCls, gLongZero, thread) {\n  extendClass(' + jsClassName + ', superCls.getConstructor(thread));\n  function ' + jsClassName + '(thread, lengths) {\n');
	        this.superClass.outputInjectedFields(outputStream);
	        if (this.componentClassName[0] !== '[') {
	            outputStream.write('    this.array = new ' + this.getJSArrayConstructor() + '(lengths);\n');
	            if (this.getJSArrayConstructor() === 'Array') {
	                outputStream.write('    for (var i = 0; i < lengths; i++) {\n      this.array[i] = ' + this.getJSDefaultArrayElement() + ';\n    }\n');
	            }
	        } else {
	            outputStream.write('    if (typeof lengths === \'number\') {\n        this.array = new ' + this.getJSArrayConstructor() + '(lengths);\n        for (var i = 0; i < length; i++) {\n          this.array[i] = null;\n        }\n      } else {\n        var length = lengths[0], otherLengths = lengths.length > 2 ? lengths.slice(1) : lengths[1];\n        this.array = new ' + this.getJSArrayConstructor() + '(length);\n        for (var i = 0; i < length; i++) {\n          this.array[i] = ' + this.getJSDefaultArrayElement() + ';\n        }\n      }\n');
	        }
	        outputStream.write('  }\n\n  ' + jsClassName + '.prototype.slice = ' + this._getSliceMethod() + ';\n  ' + jsClassName + '.cls = cls;\n');
	        this.outputInjectedMethods(jsClassName, outputStream);
	        outputStream.write('\n  return ' + jsClassName + ';\n}\n// Last statement is return value of eval.\n_create');
	        return eval(outputStream.flush())(extendClass, this, this.superClass, gLong.ZERO, thread);
	    };
	    ArrayClassData.prototype.getConstructor = function (thread) {
	        assert(this.isResolved(), 'Tried to get constructor for class ' + this.getInternalName() + ' before it was resolved.');
	        if (this._constructor === null) {
	            this._constructor = this._constructConstructor(thread);
	        }
	        return this._constructor;
	    };
	    return ArrayClassData;
	}(ClassData);
	exports.ArrayClassData = ArrayClassData;
	var ReferenceClassData = function (_super) {
	    __extends(ReferenceClassData, _super);
	    function ReferenceClassData(buffer, protectionDomain, loader, cpPatches) {
	        _super.call(this, loader);
	        this.interfaceClasses = null;
	        this.superClassRef = null;
	        this.initLock = new ClassLock();
	        this._constructor = null;
	        this._fieldLookup = {};
	        this._objectFields = [];
	        this._staticFields = [];
	        this._methodLookup = {};
	        this._vmTable = [];
	        this._uninheritedDefaultMethods = [];
	        this._protectionDomain = protectionDomain ? protectionDomain : null;
	        var byteStream = new ByteStream(buffer), i = 0;
	        if (byteStream.getUint32() !== 3405691582) {
	            throw new Error('Magic number invalid');
	        }
	        this.minorVersion = byteStream.getUint16();
	        this.majorVersion = byteStream.getUint16();
	        if (!(45 <= this.majorVersion && this.majorVersion <= 52)) {
	            throw new Error('Major version invalid');
	        }
	        this.constantPool = new ConstantPool.ConstantPool();
	        this.constantPool.parse(byteStream, cpPatches);
	        this.accessFlags = new util.Flags(byteStream.getUint16());
	        this.className = this.constantPool.get(byteStream.getUint16()).name;
	        var superRef = byteStream.getUint16();
	        if (superRef !== 0) {
	            this.superClassRef = this.constantPool.get(superRef);
	        }
	        var isize = byteStream.getUint16();
	        this.interfaceRefs = new Array(isize);
	        for (i = 0; i < isize; ++i) {
	            this.interfaceRefs[i] = this.constantPool.get(byteStream.getUint16());
	        }
	        var numFields = byteStream.getUint16();
	        this.fields = new Array(numFields);
	        for (i = 0; i < numFields; ++i) {
	            this.fields[i] = new methods.Field(this, this.constantPool, i, byteStream);
	        }
	        var numMethods = byteStream.getUint16();
	        this.methods = new Array(numMethods);
	        for (i = 0; i < numMethods; i++) {
	            var m = new methods.Method(this, this.constantPool, i, byteStream);
	            this.methods[i] = m;
	        }
	        this.attrs = attributes.makeAttributes(byteStream, this.constantPool);
	        if (byteStream.hasBytes()) {
	            throw 'Leftover bytes in classfile: ' + byteStream;
	        }
	    }
	    ReferenceClassData.prototype.getSuperClassReference = function () {
	        return this.superClassRef;
	    };
	    ReferenceClassData.prototype.getInterfaceClassReferences = function () {
	        return this.interfaceRefs.slice(0);
	    };
	    ReferenceClassData.prototype.getInterfaces = function () {
	        return this.interfaceClasses;
	    };
	    ReferenceClassData.prototype.getFields = function () {
	        return this.fields;
	    };
	    ReferenceClassData.prototype.getVMTable = function () {
	        return this._vmTable;
	    };
	    ReferenceClassData.prototype.getVMIndexForMethod = function (m) {
	        return this._vmTable.indexOf(this.methodLookup(m.signature));
	    };
	    ReferenceClassData.prototype.getMethodFromVMIndex = function (i) {
	        if (this._vmTable[i] !== undefined) {
	            return this._vmTable[i];
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.getVMIndexForField = function (f) {
	        if (f.accessFlags.isStatic()) {
	            assert(f.cls === this, 'Looks like we actually need to support static field lookups!');
	            return this._staticFields.indexOf(f);
	        } else {
	            return this._objectFields.indexOf(f);
	        }
	    };
	    ReferenceClassData.prototype.getStaticFieldFromVMIndex = function (index) {
	        var f = this._staticFields[index];
	        if (f !== undefined) {
	            return f;
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.getObjectFieldFromVMIndex = function (index) {
	        var f = this._objectFields[index];
	        if (f !== undefined) {
	            return f;
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.getFieldFromSlot = function (slot) {
	        return this.fields[slot];
	    };
	    ReferenceClassData.prototype.getMethodFromSlot = function (slot) {
	        return this.methods[slot];
	    };
	    ReferenceClassData.prototype.getMethod = function (sig) {
	        var m = this._methodLookup[sig];
	        if (m.cls === this) {
	            return m;
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.getSpecificMethod = function (definingCls, sig) {
	        if (this.getInternalName() === definingCls) {
	            return this.getMethod(sig);
	        }
	        var searchClasses = this.interfaceClasses.slice(0), m;
	        if (this.superClass) {
	            searchClasses.push(this.superClass);
	        }
	        for (var i = 0; i < searchClasses.length; i++) {
	            if (null !== (m = searchClasses[i].getSpecificMethod(definingCls, sig))) {
	                return m;
	            }
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.getMethods = function () {
	        return this.methods;
	    };
	    ReferenceClassData.prototype.getUninheritedDefaultMethods = function () {
	        return this._uninheritedDefaultMethods;
	    };
	    ReferenceClassData.prototype.getProtectionDomain = function () {
	        return this._protectionDomain;
	    };
	    ReferenceClassData.prototype._resolveMethods = function () {
	        var _this = this;
	        if (this.superClass !== null) {
	            this._vmTable = this._vmTable.concat(this.superClass._vmTable);
	            Object.keys(this.superClass._methodLookup).forEach(function (m) {
	                _this._methodLookup[m] = _this.superClass._methodLookup[m];
	            });
	        }
	        this.methods.forEach(function (m) {
	            var superM = _this._methodLookup[m.signature];
	            if (!m.accessFlags.isStatic() && m.name !== '<init>') {
	                if (superM === undefined) {
	                    _this._vmTable.push(m);
	                } else {
	                    _this._vmTable[_this._vmTable.indexOf(superM)] = m;
	                }
	            }
	            _this._methodLookup[m.signature] = m;
	        });
	        this.interfaceClasses.forEach(function (iface) {
	            Object.keys(iface._methodLookup).forEach(function (ifaceMethodSig) {
	                var ifaceM = iface._methodLookup[ifaceMethodSig];
	                if (_this._methodLookup[ifaceMethodSig] === undefined) {
	                    if (!ifaceM.accessFlags.isStatic()) {
	                        _this._vmTable.push(ifaceM);
	                    }
	                    _this._methodLookup[ifaceMethodSig] = ifaceM;
	                } else if (ifaceM.isDefault()) {
	                    _this._uninheritedDefaultMethods.push(ifaceM);
	                }
	            });
	        });
	    };
	    ReferenceClassData.prototype._resolveFields = function () {
	        var _this = this;
	        if (this.superClass !== null) {
	            this._objectFields = this._objectFields.concat(this.superClass._objectFields);
	            Object.keys(this.superClass._fieldLookup).forEach(function (f) {
	                _this._fieldLookup[f] = _this.superClass._fieldLookup[f];
	            });
	        }
	        this.interfaceClasses.forEach(function (iface) {
	            Object.keys(iface._fieldLookup).forEach(function (ifaceFieldName) {
	                var ifaceF = iface._fieldLookup[ifaceFieldName];
	                assert(ifaceF.accessFlags.isStatic(), 'Interface fields must be static.');
	                _this._fieldLookup[ifaceFieldName] = ifaceF;
	            });
	        });
	        this.fields.forEach(function (f) {
	            _this._fieldLookup[f.name] = f;
	            if (f.accessFlags.isStatic()) {
	                _this._staticFields.push(f);
	            } else {
	                _this._objectFields.push(f);
	            }
	        });
	    };
	    ReferenceClassData.prototype.methodLookup = function (signature) {
	        var m = this._methodLookup[signature];
	        if (m !== undefined) {
	            return m;
	        } else {
	            return null;
	        }
	    };
	    ReferenceClassData.prototype.signaturePolymorphicAwareMethodLookup = function (signature) {
	        var m;
	        if (null !== (m = this.methodLookup(signature))) {
	            return m;
	        } else if (this.className === 'Ljava/lang/invoke/MethodHandle;') {
	            var polySig = signature.slice(0, signature.indexOf('(')) + '([Ljava/lang/Object;)Ljava/lang/Object;', m = this._methodLookup[polySig];
	            if (m !== undefined && m.accessFlags.isNative() && m.accessFlags.isVarArgs() && m.cls === this) {
	                return m;
	            }
	        } else if (this.superClass !== null) {
	            return this.superClass.signaturePolymorphicAwareMethodLookup(signature);
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.fieldLookup = function (name) {
	        var f = this._fieldLookup[name];
	        if (f !== undefined) {
	            return f;
	        } else {
	            return null;
	        }
	    };
	    ReferenceClassData.prototype.getAttribute = function (name) {
	        var attrs = this.attrs;
	        for (var i = 0; i < attrs.length; i++) {
	            var attr = attrs[i];
	            if (attr.getName() === name) {
	                return attr;
	            }
	        }
	        return null;
	    };
	    ReferenceClassData.prototype.getAttributes = function (name) {
	        var attrs = this.attrs;
	        var results = [];
	        for (var i = 0; i < attrs.length; i++) {
	            var attr = attrs[i];
	            if (attr.getName() === name) {
	                results.push(attr);
	            }
	        }
	        return results;
	    };
	    ReferenceClassData.prototype.getBootstrapMethod = function (idx) {
	        var bms = this.getAttribute('BootstrapMethods');
	        return bms.bootstrapMethods[idx];
	    };
	    ReferenceClassData.prototype._getInitialStaticFieldValue = function (thread, name) {
	        var f = this.fieldLookup(name);
	        if (f !== null && f.accessFlags.isStatic()) {
	            var cva = f.getAttribute('ConstantValue');
	            if (cva !== null) {
	                switch (cva.value.getType()) {
	                case enums.ConstantPoolItemType.STRING:
	                    var stringCPI = cva.value;
	                    if (stringCPI.value === null) {
	                        stringCPI.value = thread.getJVM().internString(stringCPI.stringValue);
	                    }
	                    return stringCPI.value;
	                default:
	                    return cva.value.value;
	                }
	            } else {
	                return util.initialValue(f.rawDescriptor);
	            }
	        }
	        assert(false, 'Tried to construct a static field value that ' + (f !== null ? 'isn\'t static' : 'doesn\'t exist') + ': ' + (f !== null ? f.cls.getInternalName() : this.getInternalName()) + ' ' + name);
	    };
	    ReferenceClassData.prototype.setResolved = function (superClazz, interfaceClazzes) {
	        this.superClass = superClazz;
	        ;
	        this.interfaceClasses = interfaceClazzes;
	        this._resolveMethods();
	        this._resolveFields();
	        this.setState(ClassState.RESOLVED);
	    };
	    ReferenceClassData.prototype.tryToResolve = function () {
	        if (this.getState() === ClassState.LOADED) {
	            var loader = this.loader, toResolve = this.superClassRef !== null ? this.interfaceRefs.concat(this.superClassRef) : this.interfaceRefs, allGood = true, resolvedItems = [], i, item;
	            for (i = 0; i < toResolve.length; i++) {
	                item = toResolve[i];
	                if (item.tryResolve(loader)) {
	                    resolvedItems.push(item.cls);
	                } else {
	                    return false;
	                }
	            }
	            this.setResolved(this.superClassRef !== null ? resolvedItems.pop() : null, resolvedItems);
	        }
	        return true;
	    };
	    ReferenceClassData.prototype.tryToInitialize = function () {
	        if (this.getState() === ClassState.INITIALIZED) {
	            return true;
	        }
	        if (this.getState() === ClassState.RESOLVED || this.tryToResolve()) {
	            if (this.superClass !== null && !this.superClass.tryToInitialize()) {
	                return false;
	            }
	            var clinit = this.getMethod('<clinit>()V');
	            if (clinit !== null) {
	                return false;
	            } else {
	                this.setState(ClassState.INITIALIZED);
	                return true;
	            }
	        }
	        return false;
	    };
	    ReferenceClassData.prototype.isCastable = function (target) {
	        if (!(target instanceof ReferenceClassData)) {
	            return false;
	        }
	        if (this.accessFlags.isInterface()) {
	            if (target.accessFlags.isInterface()) {
	                return this.isSubinterface(target);
	            }
	            if (!target.accessFlags.isInterface()) {
	                return target.getInternalName() === 'Ljava/lang/Object;';
	            }
	        } else {
	            if (target.accessFlags.isInterface()) {
	                return this.isSubinterface(target);
	            }
	            return this.isSubclass(target);
	        }
	    };
	    ReferenceClassData.prototype.isSubinterface = function (target) {
	        if (this.className === target.getInternalName()) {
	            return true;
	        }
	        var ifaces = this.getInterfaces();
	        for (var i = 0; i < ifaces.length; i++) {
	            var superIface = ifaces[i];
	            if (superIface.isSubinterface(target)) {
	                return true;
	            }
	        }
	        if (this.getSuperClass() == null) {
	            return false;
	        }
	        return this.getSuperClass().isSubinterface(target);
	    };
	    ReferenceClassData.prototype.initialize = function (thread, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        if (this.isResolved()) {
	            if (this.isInitialized(thread)) {
	                setImmediate(function () {
	                    cb(_this);
	                });
	            } else if (this.initLock.tryLock(thread, cb)) {
	                if (this.superClass != null) {
	                    this.superClass.initialize(thread, function (cdata) {
	                        if (cdata == null) {
	                            _this.initLock.unlock(null);
	                        } else {
	                            _this._initialize(thread, function (cdata) {
	                                _this.initLock.unlock(cdata);
	                            });
	                        }
	                    }, explicit);
	                } else {
	                    this._initialize(thread, function (cdata) {
	                        _this.initLock.unlock(cdata);
	                    });
	                }
	            }
	        } else {
	            this.resolve(thread, function (cdata) {
	                if (cdata !== null) {
	                    _this.initialize(thread, cb, explicit);
	                } else {
	                    cb(cdata);
	                }
	            }, explicit);
	        }
	    };
	    ReferenceClassData.prototype._initialize = function (thread, cb) {
	        var _this = this;
	        var cons = this.getConstructor(thread);
	        if (cons['<clinit>()V'] !== undefined) {
	            ;
	            cons['<clinit>()V'](thread, null, function (e) {
	                if (e) {
	                    ;
	                    _this.setState(enums.ClassState.RESOLVED);
	                    if (e.getClass().isCastable(thread.getBsCl().getResolvedClass('Ljava/lang/Error;'))) {
	                        thread.throwException(e);
	                        cb(null);
	                    } else {
	                        thread.getBsCl().initializeClass(thread, 'Ljava/lang/ExceptionInInitializerError;', function (cdata) {
	                            if (cdata == null) {
	                                cb(null);
	                            } else {
	                                var eCons = cdata.getConstructor(thread), e2 = new eCons(thread);
	                                e2['<init>(Ljava/lang/Throwable;)V'](thread, [e], function (e) {
	                                    thread.throwException(e2);
	                                    cb(null);
	                                });
	                            }
	                        });
	                    }
	                } else {
	                    _this.setState(enums.ClassState.INITIALIZED);
	                    ;
	                    cb(_this);
	                }
	            });
	        } else {
	            this.setState(enums.ClassState.INITIALIZED);
	            cb(this);
	        }
	    };
	    ReferenceClassData.prototype.isInitialized = function (thread) {
	        return this.getState() === ClassState.INITIALIZED || this.initLock.getOwner() === thread;
	    };
	    ReferenceClassData.prototype.resolve = function (thread, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        var toResolve = this.interfaceRefs.slice(0);
	        if (this.superClassRef !== null) {
	            toResolve.push(this.superClassRef);
	        }
	        toResolve = toResolve.filter(function (item) {
	            return !item.isResolved();
	        });
	        util.asyncForEach(toResolve, function (clsRef, nextItem) {
	            clsRef.resolve(thread, _this.loader, _this, function (status) {
	                if (!status) {
	                    nextItem('Failed.');
	                } else {
	                    nextItem();
	                }
	            }, explicit);
	        }, function (err) {
	            if (!err) {
	                _this.setResolved(_this.superClassRef !== null ? _this.superClassRef.cls : null, _this.interfaceRefs.map(function (ref) {
	                    return ref.cls;
	                }));
	                cb(_this);
	            } else {
	                cb(null);
	            }
	        });
	    };
	    ReferenceClassData.prototype.getMirandaAndDefaultMethods = function () {
	        var _this = this;
	        var superClsMethodTable = this.superClass !== null ? this.superClass.getVMTable() : [];
	        return this.getVMTable().slice(superClsMethodTable.length).filter(function (method) {
	            return method.cls !== _this;
	        });
	    };
	    ReferenceClassData.prototype.outputInjectedFields = function (outputStream) {
	        if (this.superClass !== null) {
	            this.superClass.outputInjectedFields(outputStream);
	        }
	        var injected = injectedFields[this.getInternalName()];
	        if (injected !== undefined) {
	            Object.keys(injected).forEach(function (fieldName) {
	                outputStream.write('this.' + fieldName + ' = ' + injected[fieldName][1] + ';\n');
	            });
	        }
	    };
	    ReferenceClassData.prototype._constructConstructor = function (thread) {
	        assert(this._constructor === null, 'Attempted to construct constructor twice for class ' + this.getExternalName() + '!');
	        var jsClassName = util.jvmName2JSName(this.getInternalName()), outputStream = new StringOutputStream();
	        outputStream.write('function _create(extendClass, cls, InternalStackFrame, NativeStackFrame, BytecodeStackFrame, gLongZero, ClassLoader, Monitor, thread) {\n  if (cls.superClass !== null) {\n    extendClass(' + jsClassName + ', cls.superClass.getConstructor(thread));\n  }\n  function ' + jsClassName + '(thread) {\n');
	        this.outputInjectedFields(outputStream);
	        this._objectFields.forEach(function (f) {
	            return f.outputJavaScriptField(jsClassName, outputStream);
	        });
	        outputStream.write('  }\n  ' + jsClassName + '.cls = cls;\n');
	        this.outputInjectedMethods(jsClassName, outputStream);
	        this._staticFields.forEach(function (f) {
	            return f.outputJavaScriptField(jsClassName, outputStream);
	        });
	        this.getMethods().forEach(function (m) {
	            return m.outputJavaScriptFunction(jsClassName, outputStream);
	        });
	        this.getMirandaAndDefaultMethods().forEach(function (m) {
	            return m.outputJavaScriptFunction(jsClassName, outputStream);
	        });
	        this.getUninheritedDefaultMethods().forEach(function (m) {
	            return m.outputJavaScriptFunction(jsClassName, outputStream, true);
	        });
	        outputStream.write('  return ' + jsClassName + ';\n}\n_create');
	        var evalText = outputStream.flush();
	        if (typeof RELEASE === 'undefined' && thread !== null && thread.getJVM().shouldDumpCompiledCode()) {
	            thread.getJVM().dumpObjectDefinition(this, evalText);
	        }
	        return eval(evalText)(extendClass, this, threading_1.InternalStackFrame, threading_1.NativeStackFrame, threading_1.BytecodeStackFrame, gLong.ZERO, __webpack_require__(20), __webpack_require__(25), thread);
	    };
	    ReferenceClassData.prototype.getConstructor = function (thread) {
	        if (this._constructor == null) {
	            assert(this.isResolved(), 'Cannot construct ' + this.getInternalName() + '\'s constructor until it is resolved.');
	            this._constructor = this._constructConstructor(thread);
	        }
	        return this._constructor;
	    };
	    return ReferenceClassData;
	}(ClassData);
	exports.ReferenceClassData = ReferenceClassData;


/***/ },
/* 22 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var gLong = __webpack_require__(8);
	var assert = __webpack_require__(13);
	var ByteStream = function () {
	    function ByteStream(buffer) {
	        this.buffer = buffer;
	        this._index = 0;
	    }
	    ByteStream.prototype.incIndex = function (inc) {
	        var readIndex = this._index;
	        this._index += inc;
	        return readIndex;
	    };
	    ByteStream.prototype.rewind = function () {
	        this._index = 0;
	    };
	    ByteStream.prototype.seek = function (idx) {
	        assert(idx >= 0 && idx < this.buffer.length, 'Invalid seek position.');
	        this._index = idx;
	    };
	    ByteStream.prototype.pos = function () {
	        return this._index;
	    };
	    ByteStream.prototype.skip = function (bytesCount) {
	        this._index += bytesCount;
	    };
	    ByteStream.prototype.hasBytes = function () {
	        return this._index < this.buffer.length;
	    };
	    ByteStream.prototype.getFloat = function () {
	        return this.buffer.readFloatBE(this.incIndex(4));
	    };
	    ByteStream.prototype.getDouble = function () {
	        return this.buffer.readDoubleBE(this.incIndex(8));
	    };
	    ByteStream.prototype.getUint = function (byteCount) {
	        switch (byteCount) {
	        case 1:
	            return this.getUint8();
	        case 2:
	            return this.getUint16();
	        case 4:
	            return this.getUint32();
	        default:
	            throw new Error('Invalid byte count for getUint: ' + byteCount);
	        }
	    };
	    ByteStream.prototype.getInt = function (byteCount) {
	        switch (byteCount) {
	        case 1:
	            return this.getInt8();
	        case 2:
	            return this.getInt16();
	        case 4:
	            return this.getInt32();
	        default:
	            throw new Error('Invalid byte count for getUint: ' + byteCount);
	        }
	    };
	    ByteStream.prototype.getUint8 = function () {
	        return this.buffer.readUInt8(this.incIndex(1));
	    };
	    ByteStream.prototype.getUint16 = function () {
	        return this.buffer.readUInt16BE(this.incIndex(2));
	    };
	    ByteStream.prototype.getUint32 = function () {
	        return this.buffer.readUInt32BE(this.incIndex(4));
	    };
	    ByteStream.prototype.getInt8 = function () {
	        return this.buffer.readInt8(this.incIndex(1));
	    };
	    ByteStream.prototype.getInt16 = function () {
	        return this.buffer.readInt16BE(this.incIndex(2));
	    };
	    ByteStream.prototype.getInt32 = function () {
	        return this.buffer.readInt32BE(this.incIndex(4));
	    };
	    ByteStream.prototype.getInt64 = function () {
	        var high = this.getUint32();
	        var low = this.getUint32();
	        return gLong.fromBits(low, high);
	    };
	    ByteStream.prototype.read = function (bytesCount) {
	        var rv = this.buffer.slice(this._index, this._index + bytesCount);
	        this._index += bytesCount;
	        return rv;
	    };
	    ByteStream.prototype.peek = function () {
	        return this.buffer.readUInt8(this._index);
	    };
	    ByteStream.prototype.size = function () {
	        return this.buffer.length - this._index;
	    };
	    ByteStream.prototype.slice = function (len) {
	        var arr = new ByteStream(this.buffer.slice(this._index, this._index + len));
	        this._index += len;
	        return arr;
	    };
	    ByteStream.prototype.getBuffer = function () {
	        return this.buffer;
	    };
	    return ByteStream;
	}();
	module.exports = ByteStream;


/***/ },
/* 23 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var util = __webpack_require__(6);
	var enums = __webpack_require__(9);
	var assert = __webpack_require__(13);
	var CP_CLASSES = {};
	var ConstUTF8 = function () {
	    function ConstUTF8(rawBytes) {
	        this.value = this.bytes2str(rawBytes);
	    }
	    ConstUTF8.prototype.bytes2str = function (bytes) {
	        var y, z, v, w, x, charCode, idx = 0, rv = '';
	        while (idx < bytes.length) {
	            x = bytes.readUInt8(idx++) & 255;
	            if (x <= 127) {
	                charCode = x;
	            } else if (x <= 223) {
	                y = bytes.readUInt8(idx++);
	                charCode = ((x & 31) << 6) + (y & 63);
	            } else {
	                y = bytes.readUInt8(idx++);
	                z = bytes.readUInt8(idx++);
	                charCode = ((x & 15) << 12) + ((y & 63) << 6) + (z & 63);
	            }
	            rv += String.fromCharCode(charCode);
	        }
	        return rv;
	    };
	    ConstUTF8.prototype.getType = function () {
	        return enums.ConstantPoolItemType.UTF8;
	    };
	    ConstUTF8.prototype.getConstant = function (thread) {
	        return this.value;
	    };
	    ConstUTF8.prototype.isResolved = function () {
	        return true;
	    };
	    ConstUTF8.fromBytes = function (byteStream, constantPool) {
	        var strlen = byteStream.getUint16();
	        return new this(byteStream.read(strlen));
	    };
	    ConstUTF8.size = 1;
	    ConstUTF8.infoByteSize = 0;
	    return ConstUTF8;
	}();
	exports.ConstUTF8 = ConstUTF8;
	CP_CLASSES[enums.ConstantPoolItemType.UTF8] = ConstUTF8;
	var ConstInt32 = function () {
	    function ConstInt32(value) {
	        this.value = value;
	    }
	    ConstInt32.prototype.getType = function () {
	        return enums.ConstantPoolItemType.INTEGER;
	    };
	    ConstInt32.prototype.getConstant = function (thread) {
	        return this.value;
	    };
	    ConstInt32.prototype.isResolved = function () {
	        return true;
	    };
	    ConstInt32.fromBytes = function (byteStream, constantPool) {
	        return new this(byteStream.getInt32());
	    };
	    ConstInt32.size = 1;
	    ConstInt32.infoByteSize = 4;
	    return ConstInt32;
	}();
	exports.ConstInt32 = ConstInt32;
	CP_CLASSES[enums.ConstantPoolItemType.INTEGER] = ConstInt32;
	var ConstFloat = function () {
	    function ConstFloat(value) {
	        this.value = value;
	    }
	    ConstFloat.prototype.getType = function () {
	        return enums.ConstantPoolItemType.FLOAT;
	    };
	    ConstFloat.prototype.getConstant = function (thread) {
	        return this.value;
	    };
	    ConstFloat.prototype.isResolved = function () {
	        return true;
	    };
	    ConstFloat.fromBytes = function (byteStream, constantPool) {
	        return new this(byteStream.getFloat());
	    };
	    ConstFloat.size = 1;
	    ConstFloat.infoByteSize = 4;
	    return ConstFloat;
	}();
	exports.ConstFloat = ConstFloat;
	CP_CLASSES[enums.ConstantPoolItemType.FLOAT] = ConstFloat;
	var ConstLong = function () {
	    function ConstLong(value) {
	        this.value = value;
	    }
	    ConstLong.prototype.getType = function () {
	        return enums.ConstantPoolItemType.LONG;
	    };
	    ConstLong.prototype.getConstant = function (thread) {
	        return this.value;
	    };
	    ConstLong.prototype.isResolved = function () {
	        return true;
	    };
	    ConstLong.fromBytes = function (byteStream, constantPool) {
	        return new this(byteStream.getInt64());
	    };
	    ConstLong.size = 2;
	    ConstLong.infoByteSize = 8;
	    return ConstLong;
	}();
	exports.ConstLong = ConstLong;
	CP_CLASSES[enums.ConstantPoolItemType.LONG] = ConstLong;
	var ConstDouble = function () {
	    function ConstDouble(value) {
	        this.value = value;
	    }
	    ConstDouble.prototype.getType = function () {
	        return enums.ConstantPoolItemType.DOUBLE;
	    };
	    ConstDouble.prototype.getConstant = function (thread) {
	        return this.value;
	    };
	    ConstDouble.prototype.isResolved = function () {
	        return true;
	    };
	    ConstDouble.fromBytes = function (byteStream, constantPool) {
	        return new this(byteStream.getDouble());
	    };
	    ConstDouble.size = 2;
	    ConstDouble.infoByteSize = 8;
	    return ConstDouble;
	}();
	exports.ConstDouble = ConstDouble;
	CP_CLASSES[enums.ConstantPoolItemType.DOUBLE] = ConstDouble;
	var ClassReference = function () {
	    function ClassReference(name) {
	        this.cls = null;
	        this.clsConstructor = null;
	        this.arrayClass = null;
	        this.arrayClassConstructor = null;
	        this.name = name;
	    }
	    ClassReference.prototype.tryResolve = function (loader) {
	        if (this.cls === null) {
	            this.cls = loader.getResolvedClass(this.name);
	        }
	        return this.cls !== null;
	    };
	    ClassReference.prototype.resolve = function (thread, loader, caller, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        if (thread !== null) {
	            var currentMethod = thread.currentMethod();
	            if (currentMethod !== null && this.name === currentMethod.cls.getInternalName()) {
	                this.setResolved(thread, thread.currentMethod().cls);
	                return cb(true);
	            }
	        }
	        loader.resolveClass(thread, this.name, function (cdata) {
	            _this.setResolved(thread, cdata);
	            cb(cdata !== null);
	        }, explicit);
	    };
	    ClassReference.prototype.setResolved = function (thread, cls) {
	        this.cls = cls;
	        if (cls !== null) {
	            this.clsConstructor = cls.getConstructor(thread);
	        }
	    };
	    ClassReference.prototype.getType = function () {
	        return enums.ConstantPoolItemType.CLASS;
	    };
	    ClassReference.prototype.getConstant = function (thread) {
	        return this.cls.getClassObject(thread);
	    };
	    ClassReference.prototype.isResolved = function () {
	        return this.cls !== null;
	    };
	    ClassReference.fromBytes = function (byteStream, constantPool) {
	        var nameIndex = byteStream.getUint16(), cpItem = constantPool.get(nameIndex);
	        assert(cpItem.getType() === enums.ConstantPoolItemType.UTF8, 'ConstantPool ClassReference type != UTF8');
	        return new this(util.typestr2descriptor(cpItem.value));
	    };
	    ClassReference.size = 1;
	    ClassReference.infoByteSize = 2;
	    return ClassReference;
	}();
	exports.ClassReference = ClassReference;
	CP_CLASSES[enums.ConstantPoolItemType.CLASS] = ClassReference;
	var NameAndTypeInfo = function () {
	    function NameAndTypeInfo(name, descriptor) {
	        this.name = name;
	        this.descriptor = descriptor;
	    }
	    NameAndTypeInfo.prototype.getType = function () {
	        return enums.ConstantPoolItemType.NAME_AND_TYPE;
	    };
	    NameAndTypeInfo.prototype.isResolved = function () {
	        return true;
	    };
	    NameAndTypeInfo.fromBytes = function (byteStream, constantPool) {
	        var nameIndex = byteStream.getUint16(), descriptorIndex = byteStream.getUint16(), nameConst = constantPool.get(nameIndex), descriptorConst = constantPool.get(descriptorIndex);
	        assert(nameConst.getType() === enums.ConstantPoolItemType.UTF8 && descriptorConst.getType() === enums.ConstantPoolItemType.UTF8, 'ConstantPool NameAndTypeInfo types != UTF8');
	        return new this(nameConst.value, descriptorConst.value);
	    };
	    NameAndTypeInfo.size = 1;
	    NameAndTypeInfo.infoByteSize = 4;
	    return NameAndTypeInfo;
	}();
	exports.NameAndTypeInfo = NameAndTypeInfo;
	CP_CLASSES[enums.ConstantPoolItemType.NAME_AND_TYPE] = NameAndTypeInfo;
	var ConstString = function () {
	    function ConstString(stringValue) {
	        this.value = null;
	        this.stringValue = stringValue;
	    }
	    ConstString.prototype.getType = function () {
	        return enums.ConstantPoolItemType.STRING;
	    };
	    ConstString.prototype.resolve = function (thread, loader, caller, cb) {
	        this.value = thread.getJVM().internString(this.stringValue);
	        setImmediate(function () {
	            return cb(true);
	        });
	    };
	    ConstString.prototype.getConstant = function (thread) {
	        return this.value;
	    };
	    ConstString.prototype.isResolved = function () {
	        return this.value !== null;
	    };
	    ConstString.fromBytes = function (byteStream, constantPool) {
	        var stringIndex = byteStream.getUint16(), utf8Info = constantPool.get(stringIndex);
	        assert(utf8Info.getType() === enums.ConstantPoolItemType.UTF8, 'ConstantPool ConstString type != UTF8');
	        return new this(utf8Info.value);
	    };
	    ConstString.size = 1;
	    ConstString.infoByteSize = 2;
	    return ConstString;
	}();
	exports.ConstString = ConstString;
	CP_CLASSES[enums.ConstantPoolItemType.STRING] = ConstString;
	var MethodType = function () {
	    function MethodType(descriptor) {
	        this.methodType = null;
	        this.descriptor = descriptor;
	    }
	    MethodType.prototype.resolve = function (thread, cl, caller, cb) {
	        var _this = this;
	        util.createMethodType(thread, cl, this.descriptor, function (e, type) {
	            if (e) {
	                thread.throwException(e);
	                cb(false);
	            } else {
	                _this.methodType = type;
	                cb(true);
	            }
	        });
	    };
	    MethodType.prototype.getConstant = function (thread) {
	        return this.methodType;
	    };
	    MethodType.prototype.getType = function () {
	        return enums.ConstantPoolItemType.METHOD_TYPE;
	    };
	    MethodType.prototype.isResolved = function () {
	        return this.methodType !== null;
	    };
	    MethodType.fromBytes = function (byteStream, constantPool) {
	        var descriptorIndex = byteStream.getUint16(), utf8Info = constantPool.get(descriptorIndex);
	        assert(utf8Info.getType() === enums.ConstantPoolItemType.UTF8, 'ConstantPool MethodType type != UTF8');
	        return new this(utf8Info.value);
	    };
	    MethodType.size = 1;
	    MethodType.infoByteSize = 2;
	    return MethodType;
	}();
	exports.MethodType = MethodType;
	CP_CLASSES[enums.ConstantPoolItemType.METHOD_TYPE] = MethodType;
	var MethodReference = function () {
	    function MethodReference(classInfo, nameAndTypeInfo) {
	        this.method = null;
	        this.fullSignature = null;
	        this.paramWordSize = -1;
	        this.memberName = null;
	        this.appendix = null;
	        this.jsConstructor = null;
	        this.classInfo = classInfo;
	        this.nameAndTypeInfo = nameAndTypeInfo;
	        this.signature = this.nameAndTypeInfo.name + this.nameAndTypeInfo.descriptor;
	    }
	    MethodReference.prototype.getType = function () {
	        return enums.ConstantPoolItemType.METHODREF;
	    };
	    MethodReference.prototype.hasAccess = function (thread, frame, isStatic) {
	        var method = this.method, accessingCls = frame.method.cls;
	        if (method.accessFlags.isStatic() !== isStatic) {
	            thread.throwNewException('Ljava/lang/IncompatibleClassChangeError;', 'Method ' + method.name + ' from class ' + method.cls.getExternalName() + ' is ' + (isStatic ? 'not ' : '') + 'static.');
	            frame.returnToThreadLoop = true;
	            return false;
	        } else if (!util.checkAccess(accessingCls, method.cls, method.accessFlags)) {
	            thread.throwNewException('Ljava/lang/IllegalAccessError;', accessingCls.getExternalName() + ' cannot access ' + method.cls.getExternalName() + '.' + method.name);
	            frame.returnToThreadLoop = true;
	            return false;
	        }
	        return true;
	    };
	    MethodReference.prototype.resolveMemberName = function (method, thread, cl, caller, cb) {
	        var _this = this;
	        var memberHandleNatives = thread.getBsCl().getInitializedClass(thread, 'Ljava/lang/invoke/MethodHandleNatives;').getConstructor(thread), appendix = new (thread.getBsCl().getInitializedClass(thread, '[Ljava/lang/Object;').getConstructor(thread))(thread, 1);
	        util.createMethodType(thread, cl, this.nameAndTypeInfo.descriptor, function (e, type) {
	            if (e) {
	                thread.throwException(e);
	                cb(false);
	            } else {
	                memberHandleNatives['java/lang/invoke/MethodHandleNatives/linkMethod(Ljava/lang/Class;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;'](thread, [
	                    caller.getClassObject(thread),
	                    enums.MethodHandleReferenceKind.INVOKEVIRTUAL,
	                    _this.classInfo.cls.getClassObject(thread),
	                    thread.getJVM().internString(_this.nameAndTypeInfo.name),
	                    type,
	                    appendix
	                ], function (e, rv) {
	                    if (e !== null) {
	                        thread.throwException(e);
	                        cb(false);
	                    } else {
	                        _this.appendix = appendix.array[0];
	                        _this.memberName = rv;
	                        cb(true);
	                    }
	                });
	            }
	        });
	    };
	    MethodReference.prototype.resolve = function (thread, loader, caller, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        if (!this.classInfo.isResolved()) {
	            this.classInfo.resolve(thread, loader, caller, function (status) {
	                if (!status) {
	                    cb(false);
	                } else {
	                    _this.resolve(thread, loader, caller, cb, explicit);
	                }
	            }, explicit);
	        } else {
	            var cls = this.classInfo.cls, method = cls.methodLookup(this.signature);
	            if (method === null) {
	                if (util.is_reference_type(cls.getInternalName())) {
	                    method = cls.signaturePolymorphicAwareMethodLookup(this.signature);
	                    if (method !== null && (method.name === 'invoke' || method.name === 'invokeExact')) {
	                        return this.resolveMemberName(method, thread, loader, caller, function (status) {
	                            if (status === true) {
	                                _this.setResolved(thread, method);
	                            } else {
	                                thread.throwNewException('Ljava/lang/NoSuchMethodError;', 'Method ' + _this.signature + ' does not exist in class ' + _this.classInfo.cls.getExternalName() + '.');
	                            }
	                            cb(status);
	                        });
	                    }
	                }
	            }
	            if (method !== null) {
	                this.setResolved(thread, method);
	                cb(true);
	            } else {
	                thread.throwNewException('Ljava/lang/NoSuchMethodError;', 'Method ' + this.signature + ' does not exist in class ' + this.classInfo.cls.getExternalName() + '.');
	                cb(false);
	            }
	        }
	    };
	    MethodReference.prototype.setResolved = function (thread, method) {
	        this.method = method;
	        this.paramWordSize = util.getMethodDescriptorWordSize(this.nameAndTypeInfo.descriptor);
	        this.fullSignature = this.method.fullSignature;
	        this.jsConstructor = this.method.cls.getConstructor(thread);
	    };
	    MethodReference.prototype.isResolved = function () {
	        return this.method !== null;
	    };
	    MethodReference.prototype.getParamWordSize = function () {
	        if (this.paramWordSize === -1) {
	            this.paramWordSize = util.getMethodDescriptorWordSize(this.nameAndTypeInfo.descriptor);
	        }
	        return this.paramWordSize;
	    };
	    MethodReference.fromBytes = function (byteStream, constantPool) {
	        var classIndex = byteStream.getUint16(), nameAndTypeIndex = byteStream.getUint16(), classInfo = constantPool.get(classIndex), nameAndTypeInfo = constantPool.get(nameAndTypeIndex);
	        assert(classInfo.getType() === enums.ConstantPoolItemType.CLASS && nameAndTypeInfo.getType() === enums.ConstantPoolItemType.NAME_AND_TYPE, 'ConstantPool MethodReference types mismatch');
	        return new this(classInfo, nameAndTypeInfo);
	    };
	    MethodReference.size = 1;
	    MethodReference.infoByteSize = 4;
	    return MethodReference;
	}();
	exports.MethodReference = MethodReference;
	CP_CLASSES[enums.ConstantPoolItemType.METHODREF] = MethodReference;
	var InterfaceMethodReference = function () {
	    function InterfaceMethodReference(classInfo, nameAndTypeInfo) {
	        this.fullSignature = null;
	        this.method = null;
	        this.paramWordSize = -1;
	        this.jsConstructor = null;
	        this.classInfo = classInfo;
	        this.nameAndTypeInfo = nameAndTypeInfo;
	        this.signature = this.nameAndTypeInfo.name + this.nameAndTypeInfo.descriptor;
	    }
	    InterfaceMethodReference.prototype.getType = function () {
	        return enums.ConstantPoolItemType.INTERFACE_METHODREF;
	    };
	    InterfaceMethodReference.prototype.hasAccess = function (thread, frame, isStatic) {
	        var method = this.method, accessingCls = frame.method.cls;
	        if (method.accessFlags.isStatic() !== isStatic) {
	            thread.throwNewException('Ljava/lang/IncompatibleClassChangeError;', 'Method ' + method.name + ' from class ' + method.cls.getExternalName() + ' is ' + (isStatic ? 'not ' : '') + 'static.');
	            frame.returnToThreadLoop = true;
	            return false;
	        } else if (!util.checkAccess(accessingCls, method.cls, method.accessFlags)) {
	            thread.throwNewException('Ljava/lang/IllegalAccessError;', accessingCls.getExternalName() + ' cannot access ' + method.cls.getExternalName() + '.' + method.name);
	            frame.returnToThreadLoop = true;
	            return false;
	        }
	        return true;
	    };
	    InterfaceMethodReference.prototype.resolve = function (thread, loader, caller, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        if (!this.classInfo.isResolved()) {
	            this.classInfo.resolve(thread, loader, caller, function (status) {
	                if (!status) {
	                    cb(false);
	                } else {
	                    _this.resolve(thread, loader, caller, cb, explicit);
	                }
	            }, explicit);
	        } else {
	            var cls = this.classInfo.cls, method = cls.methodLookup(this.signature);
	            this.paramWordSize = util.getMethodDescriptorWordSize(this.nameAndTypeInfo.descriptor);
	            if (method !== null) {
	                this.setResolved(thread, method);
	                cb(true);
	            } else {
	                thread.throwNewException('Ljava/lang/NoSuchMethodError;', 'Method ' + this.signature + ' does not exist in class ' + this.classInfo.cls.getExternalName() + '.');
	                cb(false);
	            }
	        }
	    };
	    InterfaceMethodReference.prototype.setResolved = function (thread, method) {
	        this.method = method;
	        this.paramWordSize = util.getMethodDescriptorWordSize(this.nameAndTypeInfo.descriptor);
	        this.fullSignature = this.method.fullSignature;
	        this.jsConstructor = this.method.cls.getConstructor(thread);
	    };
	    InterfaceMethodReference.prototype.getParamWordSize = function () {
	        if (this.paramWordSize === -1) {
	            this.paramWordSize = util.getMethodDescriptorWordSize(this.nameAndTypeInfo.descriptor);
	        }
	        return this.paramWordSize;
	    };
	    InterfaceMethodReference.prototype.isResolved = function () {
	        return this.method !== null;
	    };
	    InterfaceMethodReference.fromBytes = function (byteStream, constantPool) {
	        var classIndex = byteStream.getUint16(), nameAndTypeIndex = byteStream.getUint16(), classInfo = constantPool.get(classIndex), nameAndTypeInfo = constantPool.get(nameAndTypeIndex);
	        assert(classInfo.getType() === enums.ConstantPoolItemType.CLASS && nameAndTypeInfo.getType() === enums.ConstantPoolItemType.NAME_AND_TYPE, 'ConstantPool InterfaceMethodReference types mismatch');
	        return new this(classInfo, nameAndTypeInfo);
	    };
	    InterfaceMethodReference.size = 1;
	    InterfaceMethodReference.infoByteSize = 4;
	    return InterfaceMethodReference;
	}();
	exports.InterfaceMethodReference = InterfaceMethodReference;
	CP_CLASSES[enums.ConstantPoolItemType.INTERFACE_METHODREF] = InterfaceMethodReference;
	var FieldReference = function () {
	    function FieldReference(classInfo, nameAndTypeInfo) {
	        this.field = null;
	        this.fullFieldName = null;
	        this.fieldOwnerConstructor = null;
	        this.classInfo = classInfo;
	        this.nameAndTypeInfo = nameAndTypeInfo;
	    }
	    FieldReference.prototype.getType = function () {
	        return enums.ConstantPoolItemType.FIELDREF;
	    };
	    FieldReference.prototype.hasAccess = function (thread, frame, isStatic) {
	        var field = this.field, accessingCls = frame.method.cls;
	        if (field.accessFlags.isStatic() !== isStatic) {
	            thread.throwNewException('Ljava/lang/IncompatibleClassChangeError;', 'Field ' + name + ' from class ' + field.cls.getExternalName() + ' is ' + (isStatic ? 'not ' : '') + 'static.');
	            frame.returnToThreadLoop = true;
	            return false;
	        } else if (!util.checkAccess(accessingCls, field.cls, field.accessFlags)) {
	            thread.throwNewException('Ljava/lang/IllegalAccessError;', accessingCls.getExternalName() + ' cannot access ' + field.cls.getExternalName() + '.' + name);
	            frame.returnToThreadLoop = true;
	            return false;
	        }
	        return true;
	    };
	    FieldReference.prototype.resolve = function (thread, loader, caller, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        if (!this.classInfo.isResolved()) {
	            this.classInfo.resolve(thread, loader, caller, function (status) {
	                if (!status) {
	                    cb(false);
	                } else {
	                    _this.resolve(thread, loader, caller, cb, explicit);
	                }
	            }, explicit);
	        } else {
	            var cls = this.classInfo.cls, field = cls.fieldLookup(this.nameAndTypeInfo.name);
	            if (field !== null) {
	                this.fullFieldName = util.descriptor2typestr(field.cls.getInternalName()) + '/' + field.name;
	                this.field = field;
	                cb(true);
	            } else {
	                thread.throwNewException('Ljava/lang/NoSuchFieldError;', 'Field ' + this.nameAndTypeInfo.name + ' does not exist in class ' + this.classInfo.cls.getExternalName() + '.');
	                cb(false);
	            }
	        }
	    };
	    FieldReference.prototype.isResolved = function () {
	        return this.field !== null;
	    };
	    FieldReference.fromBytes = function (byteStream, constantPool) {
	        var classIndex = byteStream.getUint16(), nameAndTypeIndex = byteStream.getUint16(), classInfo = constantPool.get(classIndex), nameAndTypeInfo = constantPool.get(nameAndTypeIndex);
	        assert(classInfo.getType() === enums.ConstantPoolItemType.CLASS && nameAndTypeInfo.getType() === enums.ConstantPoolItemType.NAME_AND_TYPE, 'ConstantPool FieldReference types mismatch');
	        return new this(classInfo, nameAndTypeInfo);
	    };
	    FieldReference.size = 1;
	    FieldReference.infoByteSize = 4;
	    return FieldReference;
	}();
	exports.FieldReference = FieldReference;
	CP_CLASSES[enums.ConstantPoolItemType.FIELDREF] = FieldReference;
	var InvokeDynamic = function () {
	    function InvokeDynamic(bootstrapMethodAttrIndex, nameAndTypeInfo) {
	        this.callSiteObjects = {};
	        this.methodType = null;
	        this.bootstrapMethodAttrIndex = bootstrapMethodAttrIndex;
	        this.nameAndTypeInfo = nameAndTypeInfo;
	        this.paramWordSize = util.getMethodDescriptorWordSize(this.nameAndTypeInfo.descriptor);
	    }
	    InvokeDynamic.prototype.getType = function () {
	        return enums.ConstantPoolItemType.INVOKE_DYNAMIC;
	    };
	    InvokeDynamic.prototype.isResolved = function () {
	        return this.methodType !== null;
	    };
	    InvokeDynamic.prototype.resolve = function (thread, loader, caller, cb) {
	        var _this = this;
	        util.createMethodType(thread, loader, this.nameAndTypeInfo.descriptor, function (e, rv) {
	            if (e) {
	                thread.throwException(e);
	                cb(false);
	            } else {
	                _this.methodType = rv;
	                cb(true);
	            }
	        });
	    };
	    InvokeDynamic.prototype.getCallSiteObject = function (pc) {
	        var cso = this.callSiteObjects[pc];
	        if (cso) {
	            return cso;
	        } else {
	            return null;
	        }
	    };
	    InvokeDynamic.prototype.constructCallSiteObject = function (thread, cl, clazz, pc, cb, explicit) {
	        var _this = this;
	        if (explicit === void 0) {
	            explicit = true;
	        }
	        var bootstrapMethod = clazz.getBootstrapMethod(this.bootstrapMethodAttrIndex), unresolvedItems = bootstrapMethod[1].concat(bootstrapMethod[0], this).filter(function (item) {
	                return !item.isResolved();
	            });
	        if (unresolvedItems.length > 0) {
	            return util.asyncForEach(unresolvedItems, function (cpItem, nextItem) {
	                cpItem.resolve(thread, cl, clazz, function (status) {
	                    if (!status) {
	                        nextItem('Failed.');
	                    } else {
	                        nextItem();
	                    }
	                }, explicit);
	            }, function (err) {
	                if (err) {
	                    cb(false);
	                } else {
	                    _this.constructCallSiteObject(thread, cl, clazz, pc, cb, explicit);
	                }
	            });
	        }
	        function getArguments() {
	            var cpItems = bootstrapMethod[1], i, cpItem, rvObj = new (thread.getBsCl().getInitializedClass(thread, '[Ljava/lang/Object;').getConstructor(thread))(thread, cpItems.length), rv = rvObj.array;
	            for (i = 0; i < cpItems.length; i++) {
	                cpItem = cpItems[i];
	                switch (cpItem.getType()) {
	                case enums.ConstantPoolItemType.CLASS:
	                    rv[i] = cpItem.cls.getClassObject(thread);
	                    break;
	                case enums.ConstantPoolItemType.METHOD_HANDLE:
	                    rv[i] = cpItem.methodHandle;
	                    break;
	                case enums.ConstantPoolItemType.METHOD_TYPE:
	                    rv[i] = cpItem.methodType;
	                    break;
	                case enums.ConstantPoolItemType.STRING:
	                    rv[i] = cpItem.value;
	                    break;
	                case enums.ConstantPoolItemType.UTF8:
	                    rv[i] = thread.getJVM().internString(cpItem.value);
	                    break;
	                case enums.ConstantPoolItemType.INTEGER:
	                    rv[i] = cl.getInitializedClass(thread, 'I').createWrapperObject(thread, cpItem.value);
	                    break;
	                case enums.ConstantPoolItemType.LONG:
	                    rv[i] = cl.getInitializedClass(thread, 'J').createWrapperObject(thread, cpItem.value);
	                    break;
	                case enums.ConstantPoolItemType.FLOAT:
	                    rv[i] = cl.getInitializedClass(thread, 'F').createWrapperObject(thread, cpItem.value);
	                    break;
	                case enums.ConstantPoolItemType.DOUBLE:
	                    rv[i] = cl.getInitializedClass(thread, 'D').createWrapperObject(thread, cpItem.value);
	                    break;
	                default:
	                    assert(false, 'Invalid CPItem for static args: ' + enums.ConstantPoolItemType[cpItem.getType()]);
	                    break;
	                }
	            }
	            assert(function () {
	                var status = true;
	                cpItems.forEach(function (cpItem, i) {
	                    if (rv[i] === undefined) {
	                        console.log('Undefined item at arg ' + i + ': ' + enums.ConstantPoolItemType[cpItem.getType()]);
	                        status = false;
	                    } else if (rv[i] === null) {
	                        console.log('Null item at arg ' + i + ': ' + enums.ConstantPoolItemType[cpItem.getType()]);
	                        status = false;
	                    }
	                });
	                return status;
	            }(), 'Arguments cannot be undefined or null.');
	            return rvObj;
	        }
	        var methodName = thread.getJVM().internString(this.nameAndTypeInfo.name), appendixArr = new (cl.getInitializedClass(thread, '[Ljava/lang/Object;').getConstructor(thread))(thread, 1), staticArgs = getArguments(), mhn = cl.getInitializedClass(thread, 'Ljava/lang/invoke/MethodHandleNatives;').getConstructor(thread);
	        mhn['java/lang/invoke/MethodHandleNatives/linkCallSite(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;'](thread, [
	            clazz.getClassObject(thread),
	            bootstrapMethod[0].methodHandle,
	            methodName,
	            this.methodType,
	            staticArgs,
	            appendixArr
	        ], function (e, rv) {
	            if (e) {
	                thread.throwException(e);
	                cb(false);
	            } else {
	                _this.setResolved(pc, [
	                    rv,
	                    appendixArr.array[0]
	                ]);
	                cb(true);
	            }
	        });
	    };
	    InvokeDynamic.prototype.setResolved = function (pc, cso) {
	        if (this.callSiteObjects[pc] === undefined) {
	            this.callSiteObjects[pc] = cso;
	        }
	    };
	    InvokeDynamic.fromBytes = function (byteStream, constantPool) {
	        var bootstrapMethodAttrIndex = byteStream.getUint16(), nameAndTypeIndex = byteStream.getUint16(), nameAndTypeInfo = constantPool.get(nameAndTypeIndex);
	        assert(nameAndTypeInfo.getType() === enums.ConstantPoolItemType.NAME_AND_TYPE, 'ConstantPool InvokeDynamic types mismatch');
	        return new this(bootstrapMethodAttrIndex, nameAndTypeInfo);
	    };
	    InvokeDynamic.size = 1;
	    InvokeDynamic.infoByteSize = 4;
	    return InvokeDynamic;
	}();
	exports.InvokeDynamic = InvokeDynamic;
	CP_CLASSES[enums.ConstantPoolItemType.INVOKE_DYNAMIC] = InvokeDynamic;
	var MethodHandle = function () {
	    function MethodHandle(reference, referenceType) {
	        this.methodHandle = null;
	        this.reference = reference;
	        this.referenceType = referenceType;
	    }
	    MethodHandle.prototype.getType = function () {
	        return enums.ConstantPoolItemType.METHOD_HANDLE;
	    };
	    MethodHandle.prototype.isResolved = function () {
	        return this.methodHandle !== null;
	    };
	    MethodHandle.prototype.getConstant = function (thread) {
	        return this.methodHandle;
	    };
	    MethodHandle.prototype.resolve = function (thread, cl, caller, cb, explicit) {
	        var _this = this;
	        if (!this.reference.isResolved()) {
	            return this.reference.resolve(thread, cl, caller, function (status) {
	                if (!status) {
	                    cb(false);
	                } else {
	                    _this.resolve(thread, cl, caller, cb, explicit);
	                }
	            }, explicit);
	        }
	        this.constructMethodHandleType(thread, cl, function (type) {
	            if (type === null) {
	                cb(false);
	            } else {
	                var methodHandleNatives = cl.getInitializedClass(thread, 'Ljava/lang/invoke/MethodHandleNatives;').getConstructor(thread);
	                methodHandleNatives['linkMethodHandleConstant(Ljava/lang/Class;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;'](thread, [
	                    caller.getClassObject(thread),
	                    _this.referenceType,
	                    _this.getDefiningClassObj(thread),
	                    thread.getJVM().internString(_this.reference.nameAndTypeInfo.name),
	                    type
	                ], function (e, methodHandle) {
	                    if (e) {
	                        thread.throwException(e);
	                        cb(false);
	                    } else {
	                        _this.methodHandle = methodHandle;
	                        cb(true);
	                    }
	                });
	            }
	        });
	    };
	    MethodHandle.prototype.getDefiningClassObj = function (thread) {
	        if (this.reference.getType() === enums.ConstantPoolItemType.FIELDREF) {
	            return this.reference.field.cls.getClassObject(thread);
	        } else {
	            return this.reference.method.cls.getClassObject(thread);
	        }
	    };
	    MethodHandle.prototype.constructMethodHandleType = function (thread, cl, cb) {
	        if (this.reference.getType() === enums.ConstantPoolItemType.FIELDREF) {
	            var resolveObj = this.reference.nameAndTypeInfo.descriptor;
	            cl.resolveClass(thread, resolveObj, function (cdata) {
	                if (cdata !== null) {
	                    cb(cdata.getClassObject(thread));
	                } else {
	                    cb(null);
	                }
	            });
	        } else {
	            util.createMethodType(thread, cl, this.reference.nameAndTypeInfo.descriptor, function (e, rv) {
	                if (e) {
	                    thread.throwException(e);
	                    cb(null);
	                } else {
	                    cb(rv);
	                }
	            });
	        }
	    };
	    MethodHandle.fromBytes = function (byteStream, constantPool) {
	        var referenceKind = byteStream.getUint8(), referenceIndex = byteStream.getUint16(), reference = constantPool.get(referenceIndex);
	        assert(0 < referenceKind && referenceKind < 10, 'ConstantPool MethodHandle invalid referenceKind: ' + referenceKind);
	        assert(function () {
	            switch (referenceKind) {
	            case enums.MethodHandleReferenceKind.GETFIELD:
	            case enums.MethodHandleReferenceKind.GETSTATIC:
	            case enums.MethodHandleReferenceKind.PUTFIELD:
	            case enums.MethodHandleReferenceKind.PUTSTATIC:
	                return reference.getType() === enums.ConstantPoolItemType.FIELDREF;
	            case enums.MethodHandleReferenceKind.INVOKEINTERFACE:
	                return reference.getType() === enums.ConstantPoolItemType.INTERFACE_METHODREF && reference.nameAndTypeInfo.name[0] !== '<';
	            case enums.MethodHandleReferenceKind.INVOKEVIRTUAL:
	            case enums.MethodHandleReferenceKind.INVOKESTATIC:
	            case enums.MethodHandleReferenceKind.INVOKESPECIAL:
	                return (reference.getType() === enums.ConstantPoolItemType.METHODREF || reference.getType() === enums.ConstantPoolItemType.INTERFACE_METHODREF) && reference.nameAndTypeInfo.name[0] !== '<';
	            case enums.MethodHandleReferenceKind.NEWINVOKESPECIAL:
	                return reference.getType() === enums.ConstantPoolItemType.METHODREF && reference.nameAndTypeInfo.name === '<init>';
	            }
	            return true;
	        }(), 'Invalid constant pool reference for method handle reference type: ' + enums.MethodHandleReferenceKind[referenceKind]);
	        return new this(reference, referenceKind);
	    };
	    MethodHandle.size = 1;
	    MethodHandle.infoByteSize = 3;
	    return MethodHandle;
	}();
	exports.MethodHandle = MethodHandle;
	CP_CLASSES[enums.ConstantPoolItemType.METHOD_HANDLE] = MethodHandle;
	var CONSTANT_POOL_TIER = [
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0,
	    0
	];
	(function (tierInfos) {
	    tierInfos.forEach(function (tierInfo, index) {
	        tierInfo.forEach(function (type) {
	            CONSTANT_POOL_TIER[type] = index;
	        });
	    });
	}([
	    [
	        enums.ConstantPoolItemType.UTF8,
	        enums.ConstantPoolItemType.INTEGER,
	        enums.ConstantPoolItemType.FLOAT,
	        enums.ConstantPoolItemType.LONG,
	        enums.ConstantPoolItemType.DOUBLE
	    ],
	    [
	        enums.ConstantPoolItemType.CLASS,
	        enums.ConstantPoolItemType.STRING,
	        enums.ConstantPoolItemType.NAME_AND_TYPE,
	        enums.ConstantPoolItemType.METHOD_TYPE
	    ],
	    [
	        enums.ConstantPoolItemType.FIELDREF,
	        enums.ConstantPoolItemType.METHODREF,
	        enums.ConstantPoolItemType.INTERFACE_METHODREF,
	        enums.ConstantPoolItemType.INVOKE_DYNAMIC
	    ],
	    [enums.ConstantPoolItemType.METHOD_HANDLE]
	]));
	var ConstantPool = function () {
	    function ConstantPool() {
	    }
	    ConstantPool.prototype.parse = function (byteStream, cpPatches) {
	        var _this = this;
	        if (cpPatches === void 0) {
	            cpPatches = null;
	        }
	        var cpCount = byteStream.getUint16(), deferredQueue = [
	                [],
	                [],
	                []
	            ], endIdx = 0, idx = 1, tag = 0, itemOffset = 0, itemTier = 0;
	        this.constantPool = new Array(cpCount);
	        while (idx < cpCount) {
	            itemOffset = byteStream.pos();
	            tag = byteStream.getUint8();
	            assert(CP_CLASSES[tag] !== null && CP_CLASSES[tag] !== undefined, 'Unknown ConstantPool tag: ' + tag);
	            itemTier = CONSTANT_POOL_TIER[tag];
	            if (itemTier > 0) {
	                deferredQueue[itemTier - 1].push({
	                    offset: itemOffset,
	                    index: idx
	                });
	                byteStream.skip(CP_CLASSES[tag].infoByteSize);
	            } else {
	                this.constantPool[idx] = CP_CLASSES[tag].fromBytes(byteStream, this);
	            }
	            idx += CP_CLASSES[tag].size;
	        }
	        endIdx = byteStream.pos();
	        deferredQueue.forEach(function (deferredItems) {
	            deferredItems.forEach(function (item) {
	                byteStream.seek(item.offset);
	                tag = byteStream.getUint8();
	                _this.constantPool[item.index] = CP_CLASSES[tag].fromBytes(byteStream, _this);
	                if (cpPatches !== null && cpPatches.array[item.index] !== null && cpPatches.array[item.index] !== undefined) {
	                    var patchObj = cpPatches.array[item.index];
	                    switch (patchObj.getClass().getInternalName()) {
	                    case 'Ljava/lang/Integer;':
	                        assert(tag === enums.ConstantPoolItemType.INTEGER);
	                        _this.constantPool[item.index].value = patchObj['java/lang/Integer/value'];
	                        break;
	                    case 'Ljava/lang/Long;':
	                        assert(tag === enums.ConstantPoolItemType.LONG);
	                        _this.constantPool[item.index].value = patchObj['java/lang/Long/value'];
	                        break;
	                    case 'Ljava/lang/Float;':
	                        assert(tag === enums.ConstantPoolItemType.FLOAT);
	                        _this.constantPool[item.index].value = patchObj['java/lang/Float/value'];
	                        break;
	                    case 'Ljava/lang/Double;':
	                        assert(tag === enums.ConstantPoolItemType.DOUBLE);
	                        _this.constantPool[item.index].value = patchObj['java/lang/Double/value'];
	                        break;
	                    case 'Ljava/lang/String;':
	                        assert(tag === enums.ConstantPoolItemType.UTF8);
	                        _this.constantPool[item.index].value = patchObj.toString();
	                        break;
	                    case 'Ljava/lang/Class;':
	                        assert(tag === enums.ConstantPoolItemType.CLASS);
	                        _this.constantPool[item.index].name = patchObj.$cls.getInternalName();
	                        _this.constantPool[item.index].cls = patchObj.$cls;
	                        break;
	                    default:
	                        assert(tag === enums.ConstantPoolItemType.STRING);
	                        _this.constantPool[item.index].stringValue = '';
	                        _this.constantPool[item.index].value = patchObj;
	                        break;
	                    }
	                }
	            });
	        });
	        byteStream.seek(endIdx);
	        return byteStream;
	    };
	    ConstantPool.prototype.get = function (idx) {
	        assert(this.constantPool[idx] !== undefined, 'Invalid ConstantPool reference.');
	        return this.constantPool[idx];
	    };
	    ConstantPool.prototype.each = function (fn) {
	        this.constantPool.forEach(function (item, idx) {
	            if (item !== undefined) {
	                fn(idx, item);
	            }
	        });
	    };
	    return ConstantPool;
	}();
	exports.ConstantPool = ConstantPool;


/***/ },
/* 24 */
/***/ function(module, exports) {

	'use strict';
	var ClassLock = function () {
	    function ClassLock() {
	        this.queue = [];
	    }
	    ClassLock.prototype.tryLock = function (thread, cb) {
	        return this.queue.push({
	            thread: thread,
	            cb: cb
	        }) === 1;
	    };
	    ClassLock.prototype.unlock = function (cdata) {
	        var i, num = this.queue.length;
	        for (i = 0; i < num; i++) {
	            this.queue[i].cb(cdata);
	        }
	        this.queue = [];
	    };
	    ClassLock.prototype.getOwner = function () {
	        if (this.queue.length > 0) {
	            return this.queue[0].thread;
	        }
	        return null;
	    };
	    return ClassLock;
	}();
	module.exports = ClassLock;


/***/ },
/* 25 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var enums = __webpack_require__(9);
	var assert = __webpack_require__(13);
	var Monitor = function () {
	    function Monitor() {
	        this.owner = null;
	        this.count = 0;
	        this.blocked = {};
	        this.waiting = {};
	    }
	    Monitor.prototype.enter = function (thread, cb) {
	        if (this.owner === thread) {
	            this.count++;
	            return true;
	        } else {
	            return this.contendForLock(thread, 1, enums.ThreadStatus.BLOCKED, cb);
	        }
	    };
	    Monitor.prototype.contendForLock = function (thread, count, blockStatus, cb) {
	        var owner = this.owner;
	        assert(owner != thread, 'Thread attempting to contend for lock it already owns!');
	        if (owner === null) {
	            assert(this.count === 0);
	            this.owner = thread;
	            this.count = count;
	            return true;
	        } else {
	            this.blocked[thread.getRef()] = {
	                thread: thread,
	                cb: cb,
	                count: count
	            };
	            thread.setStatus(blockStatus, this);
	            return false;
	        }
	    };
	    Monitor.prototype.exit = function (thread) {
	        var owner = this.owner;
	        if (owner === thread) {
	            if (--this.count === 0) {
	                this.owner = null;
	                this.appointNewOwner();
	            }
	        } else {
	            thread.throwNewException('Ljava/lang/IllegalMonitorStateException;', 'Cannot exit a monitor that you do not own.');
	        }
	        return owner === thread;
	    };
	    Monitor.prototype.appointNewOwner = function () {
	        var blockedThreadRefs = Object.keys(this.blocked);
	        if (blockedThreadRefs.length > 0) {
	            var unblockedRef = blockedThreadRefs[Math.floor(Math.random() * blockedThreadRefs.length)], unblocked = this.blocked[unblockedRef];
	            this.unblock(unblocked.thread, false);
	        }
	    };
	    Monitor.prototype.wait = function (thread, cb, timeoutMs, timeoutNs) {
	        var _this = this;
	        if (this.getOwner() === thread) {
	            assert(thread.getStatus() !== enums.ThreadStatus.BLOCKED);
	            this.waiting[thread.getRef()] = {
	                thread: thread,
	                cb: cb,
	                count: this.count,
	                isTimed: timeoutMs != null && timeoutMs !== 0
	            };
	            this.owner = null;
	            this.count = 0;
	            if (timeoutMs != null && timeoutMs !== 0) {
	                this.waiting[thread.getRef()].timer = setTimeout(function () {
	                    _this.unwait(thread, true);
	                }, timeoutMs);
	                thread.setStatus(enums.ThreadStatus.TIMED_WAITING, this);
	            } else {
	                thread.setStatus(enums.ThreadStatus.WAITING, this);
	            }
	            this.appointNewOwner();
	            return true;
	        } else {
	            thread.throwNewException('Ljava/lang/IllegalMonitorStateException;', 'Cannot wait on an object that you do not own.');
	            return false;
	        }
	    };
	    Monitor.prototype.unwait = function (thread, fromTimer, interrupting, unwaitCb) {
	        if (interrupting === void 0) {
	            interrupting = false;
	        }
	        if (unwaitCb === void 0) {
	            unwaitCb = null;
	        }
	        var waitEntry = this.waiting[thread.getRef()], blockStatus = enums.ThreadStatus.UNINTERRUPTABLY_BLOCKED, blockCb = function () {
	                thread.setStatus(enums.ThreadStatus.RUNNABLE);
	                if (interrupting) {
	                    unwaitCb();
	                } else {
	                    waitEntry.cb(fromTimer);
	                }
	            };
	        assert(waitEntry != null);
	        delete this.waiting[thread.getRef()];
	        if (thread.getStatus() === enums.ThreadStatus.TIMED_WAITING && !fromTimer) {
	            var timerId = waitEntry.timer;
	            assert(timerId != null);
	            clearTimeout(timerId);
	        }
	        if (this.contendForLock(thread, waitEntry.count, blockStatus, blockCb)) {
	            blockCb();
	        }
	    };
	    Monitor.prototype.unblock = function (thread, interrupting) {
	        if (interrupting === void 0) {
	            interrupting = false;
	        }
	        var blockEntry = this.blocked[thread.getRef()];
	        assert(interrupting ? thread.getStatus() === enums.ThreadStatus.BLOCKED : true);
	        if (blockEntry != null) {
	            delete this.blocked[thread.getRef()];
	            thread.setStatus(enums.ThreadStatus.RUNNABLE);
	            if (!interrupting) {
	                assert(this.owner == null && this.count === 0, 'T' + thread.getRef() + ': We\'re not interrupting a block, but someone else owns the monitor?! Owned by ' + (this.owner == null ? '[no one]' : '' + this.owner.getRef()) + ' Count: ' + this.count);
	                this.owner = thread;
	                this.count = blockEntry.count;
	                blockEntry.cb();
	            }
	        }
	    };
	    Monitor.prototype.notify = function (thread) {
	        if (this.owner === thread) {
	            var waitingRefs = Object.keys(this.waiting);
	            if (waitingRefs.length > 0) {
	                this.unwait(this.waiting[waitingRefs[Math.floor(Math.random() * waitingRefs.length)]].thread, false);
	            }
	        } else {
	            thread.throwNewException('Ljava/lang/IllegalMonitorStateException;', 'Cannot notify on a monitor that you do not own.');
	        }
	    };
	    Monitor.prototype.notifyAll = function (thread) {
	        if (this.owner === thread) {
	            var waitingRefs = Object.keys(this.waiting), i;
	            for (i = 0; i < waitingRefs.length; i++) {
	                this.unwait(this.waiting[waitingRefs[i]].thread, false);
	            }
	        } else {
	            thread.throwNewException('Ljava/lang/IllegalMonitorStateException;', 'Cannot notifyAll on a monitor that you do not own.');
	        }
	    };
	    Monitor.prototype.getOwner = function () {
	        return this.owner;
	    };
	    Monitor.prototype.isWaiting = function (thread) {
	        return this.waiting[thread.getRef()] != null && !this.waiting[thread.getRef()].isTimed;
	    };
	    Monitor.prototype.isTimedWaiting = function (thread) {
	        return this.waiting[thread.getRef()] != null && this.waiting[thread.getRef()].isTimed;
	    };
	    Monitor.prototype.isBlocked = function (thread) {
	        return this.blocked[thread.getRef()] != null;
	    };
	    return Monitor;
	}();
	module.exports = Monitor;


/***/ },
/* 26 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var __extends = this && this.__extends || function (d, b) {
	    for (var p in b)
	        if (b.hasOwnProperty(p))
	            d[p] = b[p];
	    function __() {
	        this.constructor = d;
	    }
	    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
	};
	var enums_1 = __webpack_require__(9);
	var assert = __webpack_require__(13);
	var fs = __webpack_require__(27);
	var path = __webpack_require__(28);
	var BrowserFS = __webpack_require__(4);
	var util = __webpack_require__(6);
	var BFSFS = BrowserFS.BFSRequire('fs');
	var ZipFS = BrowserFS.FileSystem.ZipFS;
	function win2nix(p) {
	    return p.replace(/\\/g, '/');
	}
	var AbstractClasspathJar = function () {
	    function AbstractClasspathJar(path) {
	        this._fs = new BFSFS.FS();
	        this._jarRead = enums_1.TriState.INDETERMINATE;
	        this._path = path;
	    }
	    AbstractClasspathJar.prototype.getPath = function () {
	        return this._path;
	    };
	    AbstractClasspathJar.prototype.loadJar = function (cb) {
	        var _this = this;
	        if (this._jarRead !== enums_1.TriState.TRUE) {
	            fs.readFile(this._path, function (e, data) {
	                if (e) {
	                    _this._jarRead = enums_1.TriState.FALSE;
	                    cb(e);
	                } else {
	                    try {
	                        ZipFS.computeIndex(data, function (index) {
	                            try {
	                                _this._fs.initialize(new ZipFS(index, path.basename(_this._path)));
	                                _this._jarRead = enums_1.TriState.TRUE;
	                                cb();
	                            } catch (e) {
	                                _this._jarRead = enums_1.TriState.FALSE;
	                                cb(e);
	                            }
	                        });
	                    } catch (e) {
	                        _this._jarRead = enums_1.TriState.FALSE;
	                        cb(e);
	                    }
	                }
	            });
	        } else {
	            setImmediate(function () {
	                return cb(_this._jarRead === enums_1.TriState.TRUE ? null : new Error('Failed to load JAR file.'));
	            });
	        }
	    };
	    AbstractClasspathJar.prototype.tryLoadClassSync = function (type) {
	        if (this._jarRead === enums_1.TriState.TRUE) {
	            if (this.hasClass(type) !== enums_1.TriState.FALSE) {
	                try {
	                    return this._fs.readFileSync('/' + type + '.class');
	                } catch (e) {
	                    return null;
	                }
	            } else {
	                return null;
	            }
	        } else {
	            return null;
	        }
	    };
	    AbstractClasspathJar.prototype._wrapOp = function (op, failCb) {
	        var _this = this;
	        switch (this._jarRead) {
	        case enums_1.TriState.TRUE:
	            op();
	            break;
	        case enums_1.TriState.FALSE:
	            setImmediate(function () {
	                return failCb(new Error('Unable to load JAR file.'));
	            });
	            break;
	        default:
	            this.loadJar(function () {
	                _this._wrapOp(op, failCb);
	            });
	            break;
	        }
	    };
	    AbstractClasspathJar.prototype._wrapSyncOp = function (op) {
	        if (this._jarRead === enums_1.TriState.TRUE) {
	            try {
	                return op();
	            } catch (e) {
	                return null;
	            }
	        } else {
	            return null;
	        }
	    };
	    AbstractClasspathJar.prototype.loadClass = function (type, cb) {
            //console.log("[Loaded " + type + " ]");
	        var _this = this;
	        this._wrapOp(function () {
	            _this._fs.readFile('/' + type + '.class', cb);
	        }, cb);
	    };
	    AbstractClasspathJar.prototype.statResource = function (p, cb) {
	        var _this = this;
	        this._wrapOp(function () {
	            _this._fs.stat(p, cb);
	        }, cb);
	    };
	    AbstractClasspathJar.prototype.readdir = function (p, cb) {
	        var _this = this;
	        this._wrapOp(function () {
	            _this._fs.readdir(win2nix(p), cb);
	        }, cb);
	    };
	    AbstractClasspathJar.prototype.tryReaddirSync = function (p) {
	        var _this = this;
	        return this._wrapSyncOp(function () {
	            return _this._fs.readdirSync(win2nix(p));
	        });
	    };
	    AbstractClasspathJar.prototype.tryStatSync = function (p) {
	        var _this = this;
	        return this._wrapSyncOp(function () {
	            return _this._fs.statSync(win2nix(p));
	        });
	    };
	    AbstractClasspathJar.prototype.getFS = function () {
	        return this._fs.getRootFS();
	    };
	    return AbstractClasspathJar;
	}();
	exports.AbstractClasspathJar = AbstractClasspathJar;
	var UnindexedClasspathJar = function (_super) {
	    __extends(UnindexedClasspathJar, _super);
	    function UnindexedClasspathJar(p) {
	        _super.call(this, p);
	        this._classList = null;
	    }
	    UnindexedClasspathJar.prototype.hasClass = function (type) {
	        if (this._jarRead === enums_1.TriState.FALSE) {
	            return enums_1.TriState.FALSE;
	        } else {
	            return this._hasClass(type);
	        }
	    };
	    UnindexedClasspathJar.prototype._hasClass = function (type) {
	        if (this._classList) {
	            return this._classList[type] ? enums_1.TriState.TRUE : enums_1.TriState.FALSE;
	        }
	        return enums_1.TriState.INDETERMINATE;
	    };
	    UnindexedClasspathJar.prototype.initializeWithClasslist = function (classes) {
	        assert(this._classList === null, 'Initializing a classpath item twice!');
	        this._classList = {};
	        var len = classes.length;
	        for (var i = 0; i < len; i++) {
	            this._classList[classes[i]] = true;
	        }
	    };
	    UnindexedClasspathJar.prototype.initialize = function (cb) {
	        var _this = this;
	        this.loadJar(function (err) {
	            if (err) {
	                cb();
	            } else {
	                var pathStack = ['/'];
	                var classlist = [];
	                var fs_1 = _this._fs;
	                while (pathStack.length > 0) {
	                    var p = pathStack.pop();
	                    try {
	                        var stat = fs_1.statSync(p);
	                        if (stat.isDirectory()) {
	                            var listing = fs_1.readdirSync(p);
	                            for (var i = 0; i < listing.length; i++) {
	                                pathStack.push(path.join(p, listing[i]));
	                            }
	                        } else if (path.extname(p) === '.class') {
	                            classlist.push(p.slice(1, p.length - 6));
	                        }
	                    } catch (e) {
	                    }
	                }
	                _this.initializeWithClasslist(classlist);
	                cb();
	            }
	        });
	    };
	    return UnindexedClasspathJar;
	}(AbstractClasspathJar);
	exports.UnindexedClasspathJar = UnindexedClasspathJar;
	var IndexedClasspathJar = function (_super) {
	    __extends(IndexedClasspathJar, _super);
	    function IndexedClasspathJar(metaIndex, p) {
	        _super.call(this, p);
	        this._metaIndex = metaIndex;
	        this._metaName = path.basename(p);
	    }
	    IndexedClasspathJar.prototype.initialize = function (cb) {
	        setImmediate(function () {
	            return cb();
	        });
	    };
	    IndexedClasspathJar.prototype.hasClass = function (type) {
	        if (this._jarRead === enums_1.TriState.FALSE) {
	            return enums_1.TriState.FALSE;
	        } else {
	            var pkgComponents = type.split('/');
	            var search = this._metaIndex;
	            pkgComponents.pop();
	            for (var i = 0; i < pkgComponents.length; i++) {
	                var item = search[pkgComponents[i]];
	                if (!item) {
	                    return enums_1.TriState.FALSE;
	                } else if (item === true) {
	                    return enums_1.TriState.INDETERMINATE;
	                } else {
	                    search = item;
	                }
	            }
	            return enums_1.TriState.FALSE;
	        }
	    };
	    return IndexedClasspathJar;
	}(AbstractClasspathJar);
	exports.IndexedClasspathJar = IndexedClasspathJar;
	var ClasspathFolder = function () {
	    function ClasspathFolder(path) {
	        this._path = path;
	    }
	    ClasspathFolder.prototype.getPath = function () {
	        return this._path;
	    };
	    ClasspathFolder.prototype.hasClass = function (type) {
	        return enums_1.TriState.INDETERMINATE;
	    };
	    ClasspathFolder.prototype.initialize = function (cb) {
	        setImmediate(cb);
	    };
	    ClasspathFolder.prototype.tryLoadClassSync = function (type) {
	        try {
	            return fs.readFileSync(path.resolve(this._path, type + '.class'));
	        } catch (e) {
	            return null;
	        }
	    };
	    ClasspathFolder.prototype.loadClass = function (type, cb) {
	        fs.readFile(path.resolve(this._path, type + '.class'), cb);
	    };
	    ClasspathFolder.prototype.statResource = function (p, cb) {
	        fs.stat(path.resolve(this._path, p), cb);
	    };
	    ClasspathFolder.prototype.readdir = function (p, cb) {
	        fs.readdir(path.resolve(this._path, p), cb);
	    };
	    ClasspathFolder.prototype.tryReaddirSync = function (p) {
	        try {
	            return fs.readdirSync(path.resolve(this._path, p));
	        } catch (e) {
	            return null;
	        }
	    };
	    ClasspathFolder.prototype.tryStatSync = function (p) {
	        try {
	            return fs.statSync(path.resolve(this._path, p));
	        } catch (e) {
	            return null;
	        }
	    };
	    return ClasspathFolder;
	}();
	exports.ClasspathFolder = ClasspathFolder;
	var ClasspathNotFound = function () {
	    function ClasspathNotFound(path) {
	        this._path = path;
	    }
	    ClasspathNotFound.prototype.getPath = function () {
	        return this._path;
	    };
	    ClasspathNotFound.prototype.hasClass = function (type) {
	        return enums_1.TriState.FALSE;
	    };
	    ClasspathNotFound.prototype.initialize = function (cb) {
	        setImmediate(cb);
	    };
	    ClasspathNotFound.prototype.initializeWithClasslist = function (classlist) {
	    };
	    ClasspathNotFound.prototype.tryLoadClassSync = function (type) {
	        return null;
	    };
	    ClasspathNotFound.prototype._notFoundError = function (cb) {
	        setImmediate(function () {
	            return cb(new Error('Class cannot be found.'));
	        });
	    };
	    ClasspathNotFound.prototype.loadClass = function (type, cb) {
	        this._notFoundError(cb);
	    };
	    ClasspathNotFound.prototype.statResource = function (p, cb) {
	        this._notFoundError(cb);
	    };
	    ClasspathNotFound.prototype.readdir = function (p, cb) {
	        this._notFoundError(cb);
	    };
	    ClasspathNotFound.prototype.tryReaddirSync = function (p) {
	        return null;
	    };
	    ClasspathNotFound.prototype.tryStatSync = function (p) {
	        return null;
	    };
	    return ClasspathNotFound;
	}();
	exports.ClasspathNotFound = ClasspathNotFound;
	function parseMetaIndex(metaIndex) {
	    var lines = metaIndex.split('\n');
	    var rv = {};
	    var currentJar = null;
	    for (var i = 0; i < lines.length; i++) {
	        var line = lines[i];
	        if (line.length > 0) {
	            switch (line[0]) {
	            case '%':
	            case '@':
	                continue;
	            case '!':
	            case '#':
	                var jarName = line.slice(2);
	                rv[jarName] = currentJar = {};
	                break;
	            default:
	                if (line[line.length - 1] === '/') {
	                    line = line.slice(0, line.length - 1);
	                }
	                var pkgComponents = line.split('/');
	                var current = currentJar;
	                var i_1 = void 0;
	                for (i_1 = 0; i_1 < pkgComponents.length - 1; i_1++) {
	                    var cmp = pkgComponents[i_1], next = current[cmp];
	                    if (!next) {
	                        current = current[cmp] = {};
	                    } else {
	                        current = current[cmp];
	                    }
	                }
	                current[pkgComponents[i_1]] = true;
	                break;
	            }
	        }
	    }
	    return rv;
	}
	function ClasspathFactory(javaHomePath, paths, cb) {
	    var classpathItems = new Array(paths.length), i = 0;
	    fs.readFile(path.join(javaHomePath, 'lib', 'meta-index'), function (err, data) {
	        var metaIndex = {};
	        if (!err) {
	            metaIndex = parseMetaIndex(data.toString());
	        }
	        util.asyncForEach(paths, function (p, nextItem) {
	            var pRelToHome = path.relative(javaHomePath + '/lib', p);
	            fs.stat(p, function (err, stats) {
	                var cpItem;
	                if (err) {
	                    cpItem = new ClasspathNotFound(p);
	                } else if (stats.isDirectory()) {
	                    cpItem = new ClasspathFolder(p);
	                } else {
	                    if (metaIndex[pRelToHome]) {
	                        cpItem = new IndexedClasspathJar(metaIndex[pRelToHome], p);
	                    } else {
	                        cpItem = new UnindexedClasspathJar(p);
	                    }
	                }
	                classpathItems[i++] = cpItem;
	                cpItem.initialize(nextItem);
	            });
	        }, function (e) {
	            cb(classpathItems);
	        });
	    });
	}
	exports.ClasspathFactory = ClasspathFactory;


/***/ },
/* 27 */
/***/ function(module, exports, __webpack_require__) {

	var BrowserFS = __webpack_require__(4);module.exports=BrowserFS.BFSRequire('fs');


/***/ },
/* 28 */
/***/ function(module, exports, __webpack_require__) {

	var BrowserFS = __webpack_require__(4);module.exports=BrowserFS.BFSRequire('path');


/***/ },
/* 29 */
/***/ function(module, exports, __webpack_require__) {

	var BrowserFS = __webpack_require__(4);module.exports=BrowserFS.BFSRequire('buffer');


/***/ },
/* 30 */
/***/ function(module, exports, __webpack_require__) {

	/* WEBPACK VAR INJECTION */(function(Buffer) {'use strict';
	var Heap = function () {
	    function Heap(size) {
	        this.size = size;
	        this._sizeMap = {};
	        this._buffer = new Buffer(size);
	        this._remaining = size;
	        this._offset = 0;
	        this._freeLists = new Array(Heap._numSizeClasses);
	        for (var i = 0; i < Heap._numSizeClasses; i++) {
	            this._freeLists[i] = [];
	        }
	    }
	    Heap.prototype.malloc = function (size) {
	        if (size <= 4) {
	            size = 4;
	        }
	        if (this._remaining < size) {
	            throw 'out of memory';
	        }
	        var addr;
	        var cl;
	        cl = Heap.size_to_class(size);
	        addr = this._freeLists[cl].pop();
	        if (addr === undefined) {
	            addr = this.refill(cl);
	        }
	        return addr;
	    };
	    Heap.prototype.free = function (addr) {
	        var masked = addr & ~(Heap._chunkSize - 1);
	        var cl = this._sizeMap[masked];
	        this._freeLists[cl].push(addr);
	    };
	    Heap.prototype.store_word = function (addr, value) {
	        this._buffer.writeInt32LE(value, addr);
	    };
	    Heap.prototype.get_byte = function (addr) {
	        return this._buffer.readUInt8(addr);
	    };
	    Heap.prototype.get_word = function (addr) {
	        return this._buffer.readInt32LE(addr);
	    };
	    Heap.prototype.get_buffer = function (addr, len) {
	        return this._buffer.slice(addr, addr + len);
	    };
	    Heap.prototype.get_signed_byte = function (addr) {
	        return this._buffer.readInt8(addr);
	    };
	    Heap.prototype.set_byte = function (addr, value) {
	        this._buffer.writeUInt8(value, addr);
	    };
	    Heap.prototype.set_signed_byte = function (addr, value) {
	        this._buffer.writeInt8(value, addr);
	    };
	    Heap.prototype.memcpy = function (srcAddr, dstAddr, len) {
	        this._buffer.copy(this._buffer, dstAddr, srcAddr, srcAddr + len);
	    };
	    Heap.prototype.refill = function (cl) {
	        var sz = this.cl_to_size(cl);
	        var count = Math.floor(Heap._chunkSize / sz);
	        if (count < 1) {
	            count = 1;
	        }
	        var addr = this._offset;
	        this._sizeMap[addr] = cl;
	        for (var i = 0; i < count; i++) {
	            this._remaining -= sz;
	            addr = this._offset;
	            this._freeLists[cl].push(addr);
	            this._offset += sz;
	        }
	        return addr;
	    };
	    Heap.ilog2 = function (num) {
	        var log2 = 0;
	        var value = 1;
	        while (value < num) {
	            value <<= 1;
	            log2++;
	        }
	        return log2;
	    };
	    Heap.size_to_class = function (size) {
	        return Heap.ilog2(size);
	    };
	    Heap.prototype.cl_to_size = function (cl) {
	        return 1 << cl;
	    };
	    Heap._numSizeClasses = 64;
	    Heap._chunkSize = 4096;
	    return Heap;
	}();
	module.exports = Heap;
	
	/* WEBPACK VAR INJECTION */}.call(exports, __webpack_require__(7)))

/***/ },
/* 31 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var enums_1 = __webpack_require__(9);
	var assert = __webpack_require__(13);
	var Parker = function () {
	    function Parker() {
	        this._parkCounts = {};
	        this._parkCallbacks = {};
	    }
	    Parker.prototype.park = function (thread, cb) {
	        var ref = thread.getRef();
	        assert(!this._parkCallbacks[ref] && thread.getStatus() !== enums_1.ThreadStatus.PARKED, 'Thread ' + ref + ' is doubly parked? Should be impossible.');
	        this._parkCallbacks[ref] = cb;
	        this._mutateParkCount(thread, 1);
	        if (this.isParked(thread)) {
	            thread.setStatus(enums_1.ThreadStatus.PARKED);
	        }
	    };
	    Parker.prototype.unpark = function (thread) {
	        this._mutateParkCount(thread, -1);
	    };
	    Parker.prototype.completelyUnpark = function (thread) {
	        var ref = thread.getRef(), count = this._parkCounts[ref];
	        if (count) {
	            this._mutateParkCount(thread, -count);
	        }
	    };
	    Parker.prototype._mutateParkCount = function (thread, delta) {
	        var ref = thread.getRef(), cb;
	        if (!this._parkCounts[ref]) {
	            this._parkCounts[ref] = 0;
	        }
	        if (0 === (this._parkCounts[ref] += delta)) {
	            assert(!!this._parkCallbacks[ref], 'Balancing unpark for thread ' + ref + ' with no callback? Should be impossible.');
	            cb = this._parkCallbacks[ref];
	            delete this._parkCounts[ref];
	            delete this._parkCallbacks[ref];
	            if (thread.getStatus() === enums_1.ThreadStatus.PARKED) {
	                thread.setStatus(enums_1.ThreadStatus.ASYNC_WAITING);
	                cb();
	            }
	        }
	    };
	    Parker.prototype.isParked = function (thread) {
	        return !!this._parkCounts[thread.getRef()];
	    };
	    return Parker;
	}();
	module.exports = Parker;


/***/ },
/* 32 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var enums_1 = __webpack_require__(9);
	var assert = __webpack_require__(13);
	function isRunnable(status) {
	    return status === enums_1.ThreadStatus.RUNNABLE;
	}
	var WeightedRoundRobinScheduler = function () {
	    function WeightedRoundRobinScheduler() {
	        this._count = 0;
	        this._queue = [];
	        this._threadScheduled = false;
	    }
	    WeightedRoundRobinScheduler.prototype.scheduleThread = function (thread) {
	        this._queue.push(thread);
	        if (this._queue.length === 1) {
	            this.runThread();
	        }
	    };
	    WeightedRoundRobinScheduler.prototype.runThread = function () {
	        var _this = this;
	        if (this._threadScheduled) {
	            return;
	        }
	        this._threadScheduled = true;
	        setImmediate(function () {
	            var queue = _this._queue;
	            _this._threadScheduled = false;
	            if (queue.length > 0) {
	                var thread = _this._queue[0];
	                assert(thread.getStatus() === enums_1.ThreadStatus.RUNNABLE, 'Attempted to run non-runnable thread.');
	                thread.run();
	            }
	        });
	    };
	    WeightedRoundRobinScheduler.prototype.unscheduleThread = function (thread) {
	        var queue = this._queue;
	        var isRunningThread = queue[0] === thread;
	        assert(queue.indexOf(thread) > -1, 'Tried to unschedule thread that was not scheduled.');
	        if (isRunningThread) {
	            queue.shift();
	            this._count = 0;
	            this.runThread();
	        } else {
	            queue.splice(queue.indexOf(thread), 1);
	        }
	    };
	    WeightedRoundRobinScheduler.prototype.getRunningThread = function () {
	        var queue = this._queue;
	        if (queue.length > 0) {
	            return queue[0];
	        } else {
	            return null;
	        }
	    };
	    WeightedRoundRobinScheduler.prototype.priorityChange = function (thread) {
	    };
	    WeightedRoundRobinScheduler.prototype.quantumOver = function (thread) {
	        assert(this._queue[0] === thread, 'A non-running thread has an expired quantum?');
	        this._count++;
	        if (this._count >= thread.getPriority() || thread.getStatus() !== enums_1.ThreadStatus.RUNNABLE) {
	            this._count = 0;
	            this._queue.push(this._queue.shift());
	        }
	        this.runThread();
	    };
	    return WeightedRoundRobinScheduler;
	}();
	var ThreadPool = function () {
	    function ThreadPool(emptyCallback) {
	        this.threads = [];
	        this.scheduler = new WeightedRoundRobinScheduler();
	        this.emptyCallback = emptyCallback;
	    }
	    ThreadPool.prototype.getThreads = function () {
	        return this.threads.slice(0);
	    };
	    ThreadPool.prototype.anyNonDaemonicThreads = function () {
	        for (var i = 0; i < this.threads.length; i++) {
	            var t = this.threads[i];
	            if (t.isDaemon()) {
	                continue;
	            }
	            var status_1 = t.getStatus();
	            if (status_1 !== enums_1.ThreadStatus.NEW && status_1 !== enums_1.ThreadStatus.TERMINATED) {
	                return true;
	            }
	        }
	        return false;
	    };
	    ThreadPool.prototype.threadTerminated = function (thread) {
	        var idx = this.threads.indexOf(thread);
	        assert(idx >= 0);
	        this.threads.splice(idx, 1);
	        if (!this.anyNonDaemonicThreads()) {
	            var close_1 = this.emptyCallback();
	            if (close_1) {
	                this.emptyCallback = null;
	            }
	        }
	    };
	    ThreadPool.prototype.statusChange = function (thread, oldStatus, newStatus) {
	        var wasRunnable = isRunnable(oldStatus), nowRunnable = isRunnable(newStatus);
	        if (oldStatus === enums_1.ThreadStatus.NEW || oldStatus === enums_1.ThreadStatus.TERMINATED) {
	            if (this.threads.indexOf(thread) === -1) {
	                this.threads.push(thread);
	            }
	        }
	        if (wasRunnable !== nowRunnable) {
	            if (wasRunnable) {
	                this.scheduler.unscheduleThread(thread);
	            } else {
	                this.scheduler.scheduleThread(thread);
	            }
	        }
	        if (newStatus === enums_1.ThreadStatus.TERMINATED) {
	            this.threadTerminated(thread);
	        }
	    };
	    ThreadPool.prototype.priorityChange = function (thread) {
	        this.scheduler.priorityChange(thread);
	    };
	    ThreadPool.prototype.quantumOver = function (thread) {
	        this.scheduler.quantumOver(thread);
	    };
	    return ThreadPool;
	}();
	exports.__esModule = true;
	exports['default'] = ThreadPool;


/***/ },
/* 33 */
/***/ function(module, exports) {

	module.exports = {
		"url": "https://github.com/plasma-umass/doppio_jcl/releases/download/v3.2/java_home.tar.gz",
		"classpath": [
			"lib/rt.jar",
			"lib/charsets.jar",
			"lib/doppio.jar",
			"lib/jce.jar",
			"lib/jsse.jar",
			"lib/resources.jar",
		]
	};

/***/ },
/* 34 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	
	var utils   = __webpack_require__(35);
	var trees   = __webpack_require__(36);
	var adler32 = __webpack_require__(37);
	var crc32   = __webpack_require__(38);
	var msg     = __webpack_require__(39);
	
	/* Public constants ==========================================================*/
	/* ===========================================================================*/
	
	
	/* Allowed flush values; see deflate() and inflate() below for details */
	var Z_NO_FLUSH      = 0;
	var Z_PARTIAL_FLUSH = 1;
	//var Z_SYNC_FLUSH    = 2;
	var Z_FULL_FLUSH    = 3;
	var Z_FINISH        = 4;
	var Z_BLOCK         = 5;
	//var Z_TREES         = 6;
	
	
	/* Return codes for the compression/decompression functions. Negative values
	 * are errors, positive values are used for special but normal events.
	 */
	var Z_OK            = 0;
	var Z_STREAM_END    = 1;
	//var Z_NEED_DICT     = 2;
	//var Z_ERRNO         = -1;
	var Z_STREAM_ERROR  = -2;
	var Z_DATA_ERROR    = -3;
	//var Z_MEM_ERROR     = -4;
	var Z_BUF_ERROR     = -5;
	//var Z_VERSION_ERROR = -6;
	
	
	/* compression levels */
	//var Z_NO_COMPRESSION      = 0;
	//var Z_BEST_SPEED          = 1;
	//var Z_BEST_COMPRESSION    = 9;
	var Z_DEFAULT_COMPRESSION = -1;
	
	
	var Z_FILTERED            = 1;
	var Z_HUFFMAN_ONLY        = 2;
	var Z_RLE                 = 3;
	var Z_FIXED               = 4;
	var Z_DEFAULT_STRATEGY    = 0;
	
	/* Possible values of the data_type field (though see inflate()) */
	//var Z_BINARY              = 0;
	//var Z_TEXT                = 1;
	//var Z_ASCII               = 1; // = Z_TEXT
	var Z_UNKNOWN             = 2;
	
	
	/* The deflate compression method */
	var Z_DEFLATED  = 8;
	
	/*============================================================================*/
	
	
	var MAX_MEM_LEVEL = 9;
	/* Maximum value for memLevel in deflateInit2 */
	var MAX_WBITS = 15;
	/* 32K LZ77 window */
	var DEF_MEM_LEVEL = 8;
	
	
	var LENGTH_CODES  = 29;
	/* number of length codes, not counting the special END_BLOCK code */
	var LITERALS      = 256;
	/* number of literal bytes 0..255 */
	var L_CODES       = LITERALS + 1 + LENGTH_CODES;
	/* number of Literal or Length codes, including the END_BLOCK code */
	var D_CODES       = 30;
	/* number of distance codes */
	var BL_CODES      = 19;
	/* number of codes used to transfer the bit lengths */
	var HEAP_SIZE     = 2 * L_CODES + 1;
	/* maximum heap size */
	var MAX_BITS  = 15;
	/* All codes must not exceed MAX_BITS bits */
	
	var MIN_MATCH = 3;
	var MAX_MATCH = 258;
	var MIN_LOOKAHEAD = (MAX_MATCH + MIN_MATCH + 1);
	
	var PRESET_DICT = 0x20;
	
	var INIT_STATE = 42;
	var EXTRA_STATE = 69;
	var NAME_STATE = 73;
	var COMMENT_STATE = 91;
	var HCRC_STATE = 103;
	var BUSY_STATE = 113;
	var FINISH_STATE = 666;
	
	var BS_NEED_MORE      = 1; /* block not completed, need more input or more output */
	var BS_BLOCK_DONE     = 2; /* block flush performed */
	var BS_FINISH_STARTED = 3; /* finish started, need only more output at next deflate */
	var BS_FINISH_DONE    = 4; /* finish done, accept no more input or output */
	
	var OS_CODE = 0x03; // Unix :) . Don't detect, use this default.
	
	function err(strm, errorCode) {
	  strm.msg = msg[errorCode];
	  return errorCode;
	}
	
	function rank(f) {
	  return ((f) << 1) - ((f) > 4 ? 9 : 0);
	}
	
	function zero(buf) { var len = buf.length; while (--len >= 0) { buf[len] = 0; } }
	
	
	/* =========================================================================
	 * Flush as much pending output as possible. All deflate() output goes
	 * through this function so some applications may wish to modify it
	 * to avoid allocating a large strm->output buffer and copying into it.
	 * (See also read_buf()).
	 */
	function flush_pending(strm) {
	  var s = strm.state;
	
	  //_tr_flush_bits(s);
	  var len = s.pending;
	  if (len > strm.avail_out) {
	    len = strm.avail_out;
	  }
	  if (len === 0) { return; }
	
	  utils.arraySet(strm.output, s.pending_buf, s.pending_out, len, strm.next_out);
	  strm.next_out += len;
	  s.pending_out += len;
	  strm.total_out += len;
	  strm.avail_out -= len;
	  s.pending -= len;
	  if (s.pending === 0) {
	    s.pending_out = 0;
	  }
	}
	
	
	function flush_block_only(s, last) {
	  trees._tr_flush_block(s, (s.block_start >= 0 ? s.block_start : -1), s.strstart - s.block_start, last);
	  s.block_start = s.strstart;
	  flush_pending(s.strm);
	}
	
	
	function put_byte(s, b) {
	  s.pending_buf[s.pending++] = b;
	}
	
	
	/* =========================================================================
	 * Put a short in the pending buffer. The 16-bit value is put in MSB order.
	 * IN assertion: the stream state is correct and there is enough room in
	 * pending_buf.
	 */
	function putShortMSB(s, b) {
	//  put_byte(s, (Byte)(b >> 8));
	//  put_byte(s, (Byte)(b & 0xff));
	  s.pending_buf[s.pending++] = (b >>> 8) & 0xff;
	  s.pending_buf[s.pending++] = b & 0xff;
	}
	
	
	/* ===========================================================================
	 * Read a new buffer from the current input stream, update the adler32
	 * and total number of bytes read.  All deflate() input goes through
	 * this function so some applications may wish to modify it to avoid
	 * allocating a large strm->input buffer and copying from it.
	 * (See also flush_pending()).
	 */
	function read_buf(strm, buf, start, size) {
	  var len = strm.avail_in;
	
	  if (len > size) { len = size; }
	  if (len === 0) { return 0; }
	
	  strm.avail_in -= len;
	
	  utils.arraySet(buf, strm.input, strm.next_in, len, start);
	  if (strm.state.wrap === 1) {
	    strm.adler = adler32(strm.adler, buf, len, start);
	  }
	
	  else if (strm.state.wrap === 2) {
	    strm.adler = crc32(strm.adler, buf, len, start);
	  }
	
	  strm.next_in += len;
	  strm.total_in += len;
	
	  return len;
	}
	
	
	/* ===========================================================================
	 * Set match_start to the longest match starting at the given string and
	 * return its length. Matches shorter or equal to prev_length are discarded,
	 * in which case the result is equal to prev_length and match_start is
	 * garbage.
	 * IN assertions: cur_match is the head of the hash chain for the current
	 *   string (strstart) and its distance is <= MAX_DIST, and prev_length >= 1
	 * OUT assertion: the match length is not greater than s->lookahead.
	 */
	function longest_match(s, cur_match) {
	  var chain_length = s.max_chain_length;      /* max hash chain length */
	  var scan = s.strstart; /* current string */
	  var match;                       /* matched string */
	  var len;                           /* length of current match */
	  var best_len = s.prev_length;              /* best match length so far */
	  var nice_match = s.nice_match;             /* stop if match long enough */
	  var limit = (s.strstart > (s.w_size - MIN_LOOKAHEAD)) ?
	      s.strstart - (s.w_size - MIN_LOOKAHEAD) : 0/*NIL*/;
	
	  var _win = s.window; // shortcut
	
	  var wmask = s.w_mask;
	  var prev  = s.prev;
	
	  /* Stop when cur_match becomes <= limit. To simplify the code,
	   * we prevent matches with the string of window index 0.
	   */
	
	  var strend = s.strstart + MAX_MATCH;
	  var scan_end1  = _win[scan + best_len - 1];
	  var scan_end   = _win[scan + best_len];
	
	  /* The code is optimized for HASH_BITS >= 8 and MAX_MATCH-2 multiple of 16.
	   * It is easy to get rid of this optimization if necessary.
	   */
	  // Assert(s->hash_bits >= 8 && MAX_MATCH == 258, "Code too clever");
	
	  /* Do not waste too much time if we already have a good match: */
	  if (s.prev_length >= s.good_match) {
	    chain_length >>= 2;
	  }
	  /* Do not look for matches beyond the end of the input. This is necessary
	   * to make deflate deterministic.
	   */
	  if (nice_match > s.lookahead) { nice_match = s.lookahead; }
	
	  // Assert((ulg)s->strstart <= s->window_size-MIN_LOOKAHEAD, "need lookahead");
	
	  do {
	    // Assert(cur_match < s->strstart, "no future");
	    match = cur_match;
	
	    /* Skip to next match if the match length cannot increase
	     * or if the match length is less than 2.  Note that the checks below
	     * for insufficient lookahead only occur occasionally for performance
	     * reasons.  Therefore uninitialized memory will be accessed, and
	     * conditional jumps will be made that depend on those values.
	     * However the length of the match is limited to the lookahead, so
	     * the output of deflate is not affected by the uninitialized values.
	     */
	
	    if (_win[match + best_len]     !== scan_end  ||
	        _win[match + best_len - 1] !== scan_end1 ||
	        _win[match]                !== _win[scan] ||
	        _win[++match]              !== _win[scan + 1]) {
	      continue;
	    }
	
	    /* The check at best_len-1 can be removed because it will be made
	     * again later. (This heuristic is not always a win.)
	     * It is not necessary to compare scan[2] and match[2] since they
	     * are always equal when the other bytes match, given that
	     * the hash keys are equal and that HASH_BITS >= 8.
	     */
	    scan += 2;
	    match++;
	    // Assert(*scan == *match, "match[2]?");
	
	    /* We check for insufficient lookahead only every 8th comparison;
	     * the 256th check will be made at strstart+258.
	     */
	    do {
	      /*jshint noempty:false*/
	    } while (_win[++scan] === _win[++match] && _win[++scan] === _win[++match] &&
	             _win[++scan] === _win[++match] && _win[++scan] === _win[++match] &&
	             _win[++scan] === _win[++match] && _win[++scan] === _win[++match] &&
	             _win[++scan] === _win[++match] && _win[++scan] === _win[++match] &&
	             scan < strend);
	
	    // Assert(scan <= s->window+(unsigned)(s->window_size-1), "wild scan");
	
	    len = MAX_MATCH - (strend - scan);
	    scan = strend - MAX_MATCH;
	
	    if (len > best_len) {
	      s.match_start = cur_match;
	      best_len = len;
	      if (len >= nice_match) {
	        break;
	      }
	      scan_end1  = _win[scan + best_len - 1];
	      scan_end   = _win[scan + best_len];
	    }
	  } while ((cur_match = prev[cur_match & wmask]) > limit && --chain_length !== 0);
	
	  if (best_len <= s.lookahead) {
	    return best_len;
	  }
	  return s.lookahead;
	}
	
	
	/* ===========================================================================
	 * Fill the window when the lookahead becomes insufficient.
	 * Updates strstart and lookahead.
	 *
	 * IN assertion: lookahead < MIN_LOOKAHEAD
	 * OUT assertions: strstart <= window_size-MIN_LOOKAHEAD
	 *    At least one byte has been read, or avail_in == 0; reads are
	 *    performed for at least two bytes (required for the zip translate_eol
	 *    option -- not supported here).
	 */
	function fill_window(s) {
	  var _w_size = s.w_size;
	  var p, n, m, more, str;
	
	  //Assert(s->lookahead < MIN_LOOKAHEAD, "already enough lookahead");
	
	  do {
	    more = s.window_size - s.lookahead - s.strstart;
	
	    // JS ints have 32 bit, block below not needed
	    /* Deal with !@#$% 64K limit: */
	    //if (sizeof(int) <= 2) {
	    //    if (more == 0 && s->strstart == 0 && s->lookahead == 0) {
	    //        more = wsize;
	    //
	    //  } else if (more == (unsigned)(-1)) {
	    //        /* Very unlikely, but possible on 16 bit machine if
	    //         * strstart == 0 && lookahead == 1 (input done a byte at time)
	    //         */
	    //        more--;
	    //    }
	    //}
	
	
	    /* If the window is almost full and there is insufficient lookahead,
	     * move the upper half to the lower one to make room in the upper half.
	     */
	    if (s.strstart >= _w_size + (_w_size - MIN_LOOKAHEAD)) {
	
	      utils.arraySet(s.window, s.window, _w_size, _w_size, 0);
	      s.match_start -= _w_size;
	      s.strstart -= _w_size;
	      /* we now have strstart >= MAX_DIST */
	      s.block_start -= _w_size;
	
	      /* Slide the hash table (could be avoided with 32 bit values
	       at the expense of memory usage). We slide even when level == 0
	       to keep the hash table consistent if we switch back to level > 0
	       later. (Using level 0 permanently is not an optimal usage of
	       zlib, so we don't care about this pathological case.)
	       */
	
	      n = s.hash_size;
	      p = n;
	      do {
	        m = s.head[--p];
	        s.head[p] = (m >= _w_size ? m - _w_size : 0);
	      } while (--n);
	
	      n = _w_size;
	      p = n;
	      do {
	        m = s.prev[--p];
	        s.prev[p] = (m >= _w_size ? m - _w_size : 0);
	        /* If n is not on any hash chain, prev[n] is garbage but
	         * its value will never be used.
	         */
	      } while (--n);
	
	      more += _w_size;
	    }
	    if (s.strm.avail_in === 0) {
	      break;
	    }
	
	    /* If there was no sliding:
	     *    strstart <= WSIZE+MAX_DIST-1 && lookahead <= MIN_LOOKAHEAD - 1 &&
	     *    more == window_size - lookahead - strstart
	     * => more >= window_size - (MIN_LOOKAHEAD-1 + WSIZE + MAX_DIST-1)
	     * => more >= window_size - 2*WSIZE + 2
	     * In the BIG_MEM or MMAP case (not yet supported),
	     *   window_size == input_size + MIN_LOOKAHEAD  &&
	     *   strstart + s->lookahead <= input_size => more >= MIN_LOOKAHEAD.
	     * Otherwise, window_size == 2*WSIZE so more >= 2.
	     * If there was sliding, more >= WSIZE. So in all cases, more >= 2.
	     */
	    //Assert(more >= 2, "more < 2");
	    n = read_buf(s.strm, s.window, s.strstart + s.lookahead, more);
	    s.lookahead += n;
	
	    /* Initialize the hash value now that we have some input: */
	    if (s.lookahead + s.insert >= MIN_MATCH) {
	      str = s.strstart - s.insert;
	      s.ins_h = s.window[str];
	
	      /* UPDATE_HASH(s, s->ins_h, s->window[str + 1]); */
	      s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[str + 1]) & s.hash_mask;
	//#if MIN_MATCH != 3
	//        Call update_hash() MIN_MATCH-3 more times
	//#endif
	      while (s.insert) {
	        /* UPDATE_HASH(s, s->ins_h, s->window[str + MIN_MATCH-1]); */
	        s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[str + MIN_MATCH - 1]) & s.hash_mask;
	
	        s.prev[str & s.w_mask] = s.head[s.ins_h];
	        s.head[s.ins_h] = str;
	        str++;
	        s.insert--;
	        if (s.lookahead + s.insert < MIN_MATCH) {
	          break;
	        }
	      }
	    }
	    /* If the whole input has less than MIN_MATCH bytes, ins_h is garbage,
	     * but this is not important since only literal bytes will be emitted.
	     */
	
	  } while (s.lookahead < MIN_LOOKAHEAD && s.strm.avail_in !== 0);
	
	  /* If the WIN_INIT bytes after the end of the current data have never been
	   * written, then zero those bytes in order to avoid memory check reports of
	   * the use of uninitialized (or uninitialised as Julian writes) bytes by
	   * the longest match routines.  Update the high water mark for the next
	   * time through here.  WIN_INIT is set to MAX_MATCH since the longest match
	   * routines allow scanning to strstart + MAX_MATCH, ignoring lookahead.
	   */
	//  if (s.high_water < s.window_size) {
	//    var curr = s.strstart + s.lookahead;
	//    var init = 0;
	//
	//    if (s.high_water < curr) {
	//      /* Previous high water mark below current data -- zero WIN_INIT
	//       * bytes or up to end of window, whichever is less.
	//       */
	//      init = s.window_size - curr;
	//      if (init > WIN_INIT)
	//        init = WIN_INIT;
	//      zmemzero(s->window + curr, (unsigned)init);
	//      s->high_water = curr + init;
	//    }
	//    else if (s->high_water < (ulg)curr + WIN_INIT) {
	//      /* High water mark at or above current data, but below current data
	//       * plus WIN_INIT -- zero out to current data plus WIN_INIT, or up
	//       * to end of window, whichever is less.
	//       */
	//      init = (ulg)curr + WIN_INIT - s->high_water;
	//      if (init > s->window_size - s->high_water)
	//        init = s->window_size - s->high_water;
	//      zmemzero(s->window + s->high_water, (unsigned)init);
	//      s->high_water += init;
	//    }
	//  }
	//
	//  Assert((ulg)s->strstart <= s->window_size - MIN_LOOKAHEAD,
	//    "not enough room for search");
	}
	
	/* ===========================================================================
	 * Copy without compression as much as possible from the input stream, return
	 * the current block state.
	 * This function does not insert new strings in the dictionary since
	 * uncompressible data is probably not useful. This function is used
	 * only for the level=0 compression option.
	 * NOTE: this function should be optimized to avoid extra copying from
	 * window to pending_buf.
	 */
	function deflate_stored(s, flush) {
	  /* Stored blocks are limited to 0xffff bytes, pending_buf is limited
	   * to pending_buf_size, and each stored block has a 5 byte header:
	   */
	  var max_block_size = 0xffff;
	
	  if (max_block_size > s.pending_buf_size - 5) {
	    max_block_size = s.pending_buf_size - 5;
	  }
	
	  /* Copy as much as possible from input to output: */
	  for (;;) {
	    /* Fill the window as much as possible: */
	    if (s.lookahead <= 1) {
	
	      //Assert(s->strstart < s->w_size+MAX_DIST(s) ||
	      //  s->block_start >= (long)s->w_size, "slide too late");
	//      if (!(s.strstart < s.w_size + (s.w_size - MIN_LOOKAHEAD) ||
	//        s.block_start >= s.w_size)) {
	//        throw  new Error("slide too late");
	//      }
	
	      fill_window(s);
	      if (s.lookahead === 0 && flush === Z_NO_FLUSH) {
	        return BS_NEED_MORE;
	      }
	
	      if (s.lookahead === 0) {
	        break;
	      }
	      /* flush the current block */
	    }
	    //Assert(s->block_start >= 0L, "block gone");
	//    if (s.block_start < 0) throw new Error("block gone");
	
	    s.strstart += s.lookahead;
	    s.lookahead = 0;
	
	    /* Emit a stored block if pending_buf will be full: */
	    var max_start = s.block_start + max_block_size;
	
	    if (s.strstart === 0 || s.strstart >= max_start) {
	      /* strstart == 0 is possible when wraparound on 16-bit machine */
	      s.lookahead = s.strstart - max_start;
	      s.strstart = max_start;
	      /*** FLUSH_BLOCK(s, 0); ***/
	      flush_block_only(s, false);
	      if (s.strm.avail_out === 0) {
	        return BS_NEED_MORE;
	      }
	      /***/
	
	
	    }
	    /* Flush if we may have to slide, otherwise block_start may become
	     * negative and the data will be gone:
	     */
	    if (s.strstart - s.block_start >= (s.w_size - MIN_LOOKAHEAD)) {
	      /*** FLUSH_BLOCK(s, 0); ***/
	      flush_block_only(s, false);
	      if (s.strm.avail_out === 0) {
	        return BS_NEED_MORE;
	      }
	      /***/
	    }
	  }
	
	  s.insert = 0;
	
	  if (flush === Z_FINISH) {
	    /*** FLUSH_BLOCK(s, 1); ***/
	    flush_block_only(s, true);
	    if (s.strm.avail_out === 0) {
	      return BS_FINISH_STARTED;
	    }
	    /***/
	    return BS_FINISH_DONE;
	  }
	
	  if (s.strstart > s.block_start) {
	    /*** FLUSH_BLOCK(s, 0); ***/
	    flush_block_only(s, false);
	    if (s.strm.avail_out === 0) {
	      return BS_NEED_MORE;
	    }
	    /***/
	  }
	
	  return BS_NEED_MORE;
	}
	
	/* ===========================================================================
	 * Compress as much as possible from the input stream, return the current
	 * block state.
	 * This function does not perform lazy evaluation of matches and inserts
	 * new strings in the dictionary only for unmatched strings or for short
	 * matches. It is used only for the fast compression options.
	 */
	function deflate_fast(s, flush) {
	  var hash_head;        /* head of the hash chain */
	  var bflush;           /* set if current block must be flushed */
	
	  for (;;) {
	    /* Make sure that we always have enough lookahead, except
	     * at the end of the input file. We need MAX_MATCH bytes
	     * for the next match, plus MIN_MATCH bytes to insert the
	     * string following the next match.
	     */
	    if (s.lookahead < MIN_LOOKAHEAD) {
	      fill_window(s);
	      if (s.lookahead < MIN_LOOKAHEAD && flush === Z_NO_FLUSH) {
	        return BS_NEED_MORE;
	      }
	      if (s.lookahead === 0) {
	        break; /* flush the current block */
	      }
	    }
	
	    /* Insert the string window[strstart .. strstart+2] in the
	     * dictionary, and set hash_head to the head of the hash chain:
	     */
	    hash_head = 0/*NIL*/;
	    if (s.lookahead >= MIN_MATCH) {
	      /*** INSERT_STRING(s, s.strstart, hash_head); ***/
	      s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[s.strstart + MIN_MATCH - 1]) & s.hash_mask;
	      hash_head = s.prev[s.strstart & s.w_mask] = s.head[s.ins_h];
	      s.head[s.ins_h] = s.strstart;
	      /***/
	    }
	
	    /* Find the longest match, discarding those <= prev_length.
	     * At this point we have always match_length < MIN_MATCH
	     */
	    if (hash_head !== 0/*NIL*/ && ((s.strstart - hash_head) <= (s.w_size - MIN_LOOKAHEAD))) {
	      /* To simplify the code, we prevent matches with the string
	       * of window index 0 (in particular we have to avoid a match
	       * of the string with itself at the start of the input file).
	       */
	      s.match_length = longest_match(s, hash_head);
	      /* longest_match() sets match_start */
	    }
	    if (s.match_length >= MIN_MATCH) {
	      // check_match(s, s.strstart, s.match_start, s.match_length); // for debug only
	
	      /*** _tr_tally_dist(s, s.strstart - s.match_start,
	                     s.match_length - MIN_MATCH, bflush); ***/
	      bflush = trees._tr_tally(s, s.strstart - s.match_start, s.match_length - MIN_MATCH);
	
	      s.lookahead -= s.match_length;
	
	      /* Insert new strings in the hash table only if the match length
	       * is not too large. This saves time but degrades compression.
	       */
	      if (s.match_length <= s.max_lazy_match/*max_insert_length*/ && s.lookahead >= MIN_MATCH) {
	        s.match_length--; /* string at strstart already in table */
	        do {
	          s.strstart++;
	          /*** INSERT_STRING(s, s.strstart, hash_head); ***/
	          s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[s.strstart + MIN_MATCH - 1]) & s.hash_mask;
	          hash_head = s.prev[s.strstart & s.w_mask] = s.head[s.ins_h];
	          s.head[s.ins_h] = s.strstart;
	          /***/
	          /* strstart never exceeds WSIZE-MAX_MATCH, so there are
	           * always MIN_MATCH bytes ahead.
	           */
	        } while (--s.match_length !== 0);
	        s.strstart++;
	      } else
	      {
	        s.strstart += s.match_length;
	        s.match_length = 0;
	        s.ins_h = s.window[s.strstart];
	        /* UPDATE_HASH(s, s.ins_h, s.window[s.strstart+1]); */
	        s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[s.strstart + 1]) & s.hash_mask;
	
	//#if MIN_MATCH != 3
	//                Call UPDATE_HASH() MIN_MATCH-3 more times
	//#endif
	        /* If lookahead < MIN_MATCH, ins_h is garbage, but it does not
	         * matter since it will be recomputed at next deflate call.
	         */
	      }
	    } else {
	      /* No match, output a literal byte */
	      //Tracevv((stderr,"%c", s.window[s.strstart]));
	      /*** _tr_tally_lit(s, s.window[s.strstart], bflush); ***/
	      bflush = trees._tr_tally(s, 0, s.window[s.strstart]);
	
	      s.lookahead--;
	      s.strstart++;
	    }
	    if (bflush) {
	      /*** FLUSH_BLOCK(s, 0); ***/
	      flush_block_only(s, false);
	      if (s.strm.avail_out === 0) {
	        return BS_NEED_MORE;
	      }
	      /***/
	    }
	  }
	  s.insert = ((s.strstart < (MIN_MATCH - 1)) ? s.strstart : MIN_MATCH - 1);
	  if (flush === Z_FINISH) {
	    /*** FLUSH_BLOCK(s, 1); ***/
	    flush_block_only(s, true);
	    if (s.strm.avail_out === 0) {
	      return BS_FINISH_STARTED;
	    }
	    /***/
	    return BS_FINISH_DONE;
	  }
	  if (s.last_lit) {
	    /*** FLUSH_BLOCK(s, 0); ***/
	    flush_block_only(s, false);
	    if (s.strm.avail_out === 0) {
	      return BS_NEED_MORE;
	    }
	    /***/
	  }
	  return BS_BLOCK_DONE;
	}
	
	/* ===========================================================================
	 * Same as above, but achieves better compression. We use a lazy
	 * evaluation for matches: a match is finally adopted only if there is
	 * no better match at the next window position.
	 */
	function deflate_slow(s, flush) {
	  var hash_head;          /* head of hash chain */
	  var bflush;              /* set if current block must be flushed */
	
	  var max_insert;
	
	  /* Process the input block. */
	  for (;;) {
	    /* Make sure that we always have enough lookahead, except
	     * at the end of the input file. We need MAX_MATCH bytes
	     * for the next match, plus MIN_MATCH bytes to insert the
	     * string following the next match.
	     */
	    if (s.lookahead < MIN_LOOKAHEAD) {
	      fill_window(s);
	      if (s.lookahead < MIN_LOOKAHEAD && flush === Z_NO_FLUSH) {
	        return BS_NEED_MORE;
	      }
	      if (s.lookahead === 0) { break; } /* flush the current block */
	    }
	
	    /* Insert the string window[strstart .. strstart+2] in the
	     * dictionary, and set hash_head to the head of the hash chain:
	     */
	    hash_head = 0/*NIL*/;
	    if (s.lookahead >= MIN_MATCH) {
	      /*** INSERT_STRING(s, s.strstart, hash_head); ***/
	      s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[s.strstart + MIN_MATCH - 1]) & s.hash_mask;
	      hash_head = s.prev[s.strstart & s.w_mask] = s.head[s.ins_h];
	      s.head[s.ins_h] = s.strstart;
	      /***/
	    }
	
	    /* Find the longest match, discarding those <= prev_length.
	     */
	    s.prev_length = s.match_length;
	    s.prev_match = s.match_start;
	    s.match_length = MIN_MATCH - 1;
	
	    if (hash_head !== 0/*NIL*/ && s.prev_length < s.max_lazy_match &&
	        s.strstart - hash_head <= (s.w_size - MIN_LOOKAHEAD)/*MAX_DIST(s)*/) {
	      /* To simplify the code, we prevent matches with the string
	       * of window index 0 (in particular we have to avoid a match
	       * of the string with itself at the start of the input file).
	       */
	      s.match_length = longest_match(s, hash_head);
	      /* longest_match() sets match_start */
	
	      if (s.match_length <= 5 &&
	         (s.strategy === Z_FILTERED || (s.match_length === MIN_MATCH && s.strstart - s.match_start > 4096/*TOO_FAR*/))) {
	
	        /* If prev_match is also MIN_MATCH, match_start is garbage
	         * but we will ignore the current match anyway.
	         */
	        s.match_length = MIN_MATCH - 1;
	      }
	    }
	    /* If there was a match at the previous step and the current
	     * match is not better, output the previous match:
	     */
	    if (s.prev_length >= MIN_MATCH && s.match_length <= s.prev_length) {
	      max_insert = s.strstart + s.lookahead - MIN_MATCH;
	      /* Do not insert strings in hash table beyond this. */
	
	      //check_match(s, s.strstart-1, s.prev_match, s.prev_length);
	
	      /***_tr_tally_dist(s, s.strstart - 1 - s.prev_match,
	                     s.prev_length - MIN_MATCH, bflush);***/
	      bflush = trees._tr_tally(s, s.strstart - 1 - s.prev_match, s.prev_length - MIN_MATCH);
	      /* Insert in hash table all strings up to the end of the match.
	       * strstart-1 and strstart are already inserted. If there is not
	       * enough lookahead, the last two strings are not inserted in
	       * the hash table.
	       */
	      s.lookahead -= s.prev_length - 1;
	      s.prev_length -= 2;
	      do {
	        if (++s.strstart <= max_insert) {
	          /*** INSERT_STRING(s, s.strstart, hash_head); ***/
	          s.ins_h = ((s.ins_h << s.hash_shift) ^ s.window[s.strstart + MIN_MATCH - 1]) & s.hash_mask;
	          hash_head = s.prev[s.strstart & s.w_mask] = s.head[s.ins_h];
	          s.head[s.ins_h] = s.strstart;
	          /***/
	        }
	      } while (--s.prev_length !== 0);
	      s.match_available = 0;
	      s.match_length = MIN_MATCH - 1;
	      s.strstart++;
	
	      if (bflush) {
	        /*** FLUSH_BLOCK(s, 0); ***/
	        flush_block_only(s, false);
	        if (s.strm.avail_out === 0) {
	          return BS_NEED_MORE;
	        }
	        /***/
	      }
	
	    } else if (s.match_available) {
	      /* If there was no match at the previous position, output a
	       * single literal. If there was a match but the current match
	       * is longer, truncate the previous match to a single literal.
	       */
	      //Tracevv((stderr,"%c", s->window[s->strstart-1]));
	      /*** _tr_tally_lit(s, s.window[s.strstart-1], bflush); ***/
	      bflush = trees._tr_tally(s, 0, s.window[s.strstart - 1]);
	
	      if (bflush) {
	        /*** FLUSH_BLOCK_ONLY(s, 0) ***/
	        flush_block_only(s, false);
	        /***/
	      }
	      s.strstart++;
	      s.lookahead--;
	      if (s.strm.avail_out === 0) {
	        return BS_NEED_MORE;
	      }
	    } else {
	      /* There is no previous match to compare with, wait for
	       * the next step to decide.
	       */
	      s.match_available = 1;
	      s.strstart++;
	      s.lookahead--;
	    }
	  }
	  //Assert (flush != Z_NO_FLUSH, "no flush?");
	  if (s.match_available) {
	    //Tracevv((stderr,"%c", s->window[s->strstart-1]));
	    /*** _tr_tally_lit(s, s.window[s.strstart-1], bflush); ***/
	    bflush = trees._tr_tally(s, 0, s.window[s.strstart - 1]);
	
	    s.match_available = 0;
	  }
	  s.insert = s.strstart < MIN_MATCH - 1 ? s.strstart : MIN_MATCH - 1;
	  if (flush === Z_FINISH) {
	    /*** FLUSH_BLOCK(s, 1); ***/
	    flush_block_only(s, true);
	    if (s.strm.avail_out === 0) {
	      return BS_FINISH_STARTED;
	    }
	    /***/
	    return BS_FINISH_DONE;
	  }
	  if (s.last_lit) {
	    /*** FLUSH_BLOCK(s, 0); ***/
	    flush_block_only(s, false);
	    if (s.strm.avail_out === 0) {
	      return BS_NEED_MORE;
	    }
	    /***/
	  }
	
	  return BS_BLOCK_DONE;
	}
	
	
	/* ===========================================================================
	 * For Z_RLE, simply look for runs of bytes, generate matches only of distance
	 * one.  Do not maintain a hash table.  (It will be regenerated if this run of
	 * deflate switches away from Z_RLE.)
	 */
	function deflate_rle(s, flush) {
	  var bflush;            /* set if current block must be flushed */
	  var prev;              /* byte at distance one to match */
	  var scan, strend;      /* scan goes up to strend for length of run */
	
	  var _win = s.window;
	
	  for (;;) {
	    /* Make sure that we always have enough lookahead, except
	     * at the end of the input file. We need MAX_MATCH bytes
	     * for the longest run, plus one for the unrolled loop.
	     */
	    if (s.lookahead <= MAX_MATCH) {
	      fill_window(s);
	      if (s.lookahead <= MAX_MATCH && flush === Z_NO_FLUSH) {
	        return BS_NEED_MORE;
	      }
	      if (s.lookahead === 0) { break; } /* flush the current block */
	    }
	
	    /* See how many times the previous byte repeats */
	    s.match_length = 0;
	    if (s.lookahead >= MIN_MATCH && s.strstart > 0) {
	      scan = s.strstart - 1;
	      prev = _win[scan];
	      if (prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan]) {
	        strend = s.strstart + MAX_MATCH;
	        do {
	          /*jshint noempty:false*/
	        } while (prev === _win[++scan] && prev === _win[++scan] &&
	                 prev === _win[++scan] && prev === _win[++scan] &&
	                 prev === _win[++scan] && prev === _win[++scan] &&
	                 prev === _win[++scan] && prev === _win[++scan] &&
	                 scan < strend);
	        s.match_length = MAX_MATCH - (strend - scan);
	        if (s.match_length > s.lookahead) {
	          s.match_length = s.lookahead;
	        }
	      }
	      //Assert(scan <= s->window+(uInt)(s->window_size-1), "wild scan");
	    }
	
	    /* Emit match if have run of MIN_MATCH or longer, else emit literal */
	    if (s.match_length >= MIN_MATCH) {
	      //check_match(s, s.strstart, s.strstart - 1, s.match_length);
	
	      /*** _tr_tally_dist(s, 1, s.match_length - MIN_MATCH, bflush); ***/
	      bflush = trees._tr_tally(s, 1, s.match_length - MIN_MATCH);
	
	      s.lookahead -= s.match_length;
	      s.strstart += s.match_length;
	      s.match_length = 0;
	    } else {
	      /* No match, output a literal byte */
	      //Tracevv((stderr,"%c", s->window[s->strstart]));
	      /*** _tr_tally_lit(s, s.window[s.strstart], bflush); ***/
	      bflush = trees._tr_tally(s, 0, s.window[s.strstart]);
	
	      s.lookahead--;
	      s.strstart++;
	    }
	    if (bflush) {
	      /*** FLUSH_BLOCK(s, 0); ***/
	      flush_block_only(s, false);
	      if (s.strm.avail_out === 0) {
	        return BS_NEED_MORE;
	      }
	      /***/
	    }
	  }
	  s.insert = 0;
	  if (flush === Z_FINISH) {
	    /*** FLUSH_BLOCK(s, 1); ***/
	    flush_block_only(s, true);
	    if (s.strm.avail_out === 0) {
	      return BS_FINISH_STARTED;
	    }
	    /***/
	    return BS_FINISH_DONE;
	  }
	  if (s.last_lit) {
	    /*** FLUSH_BLOCK(s, 0); ***/
	    flush_block_only(s, false);
	    if (s.strm.avail_out === 0) {
	      return BS_NEED_MORE;
	    }
	    /***/
	  }
	  return BS_BLOCK_DONE;
	}
	
	/* ===========================================================================
	 * For Z_HUFFMAN_ONLY, do not look for matches.  Do not maintain a hash table.
	 * (It will be regenerated if this run of deflate switches away from Huffman.)
	 */
	function deflate_huff(s, flush) {
	  var bflush;             /* set if current block must be flushed */
	
	  for (;;) {
	    /* Make sure that we have a literal to write. */
	    if (s.lookahead === 0) {
	      fill_window(s);
	      if (s.lookahead === 0) {
	        if (flush === Z_NO_FLUSH) {
	          return BS_NEED_MORE;
	        }
	        break;      /* flush the current block */
	      }
	    }
	
	    /* Output a literal byte */
	    s.match_length = 0;
	    //Tracevv((stderr,"%c", s->window[s->strstart]));
	    /*** _tr_tally_lit(s, s.window[s.strstart], bflush); ***/
	    bflush = trees._tr_tally(s, 0, s.window[s.strstart]);
	    s.lookahead--;
	    s.strstart++;
	    if (bflush) {
	      /*** FLUSH_BLOCK(s, 0); ***/
	      flush_block_only(s, false);
	      if (s.strm.avail_out === 0) {
	        return BS_NEED_MORE;
	      }
	      /***/
	    }
	  }
	  s.insert = 0;
	  if (flush === Z_FINISH) {
	    /*** FLUSH_BLOCK(s, 1); ***/
	    flush_block_only(s, true);
	    if (s.strm.avail_out === 0) {
	      return BS_FINISH_STARTED;
	    }
	    /***/
	    return BS_FINISH_DONE;
	  }
	  if (s.last_lit) {
	    /*** FLUSH_BLOCK(s, 0); ***/
	    flush_block_only(s, false);
	    if (s.strm.avail_out === 0) {
	      return BS_NEED_MORE;
	    }
	    /***/
	  }
	  return BS_BLOCK_DONE;
	}
	
	/* Values for max_lazy_match, good_match and max_chain_length, depending on
	 * the desired pack level (0..9). The values given below have been tuned to
	 * exclude worst case performance for pathological files. Better values may be
	 * found for specific files.
	 */
	function Config(good_length, max_lazy, nice_length, max_chain, func) {
	  this.good_length = good_length;
	  this.max_lazy = max_lazy;
	  this.nice_length = nice_length;
	  this.max_chain = max_chain;
	  this.func = func;
	}
	
	var configuration_table;
	
	configuration_table = [
	  /*      good lazy nice chain */
	  new Config(0, 0, 0, 0, deflate_stored),          /* 0 store only */
	  new Config(4, 4, 8, 4, deflate_fast),            /* 1 max speed, no lazy matches */
	  new Config(4, 5, 16, 8, deflate_fast),           /* 2 */
	  new Config(4, 6, 32, 32, deflate_fast),          /* 3 */
	
	  new Config(4, 4, 16, 16, deflate_slow),          /* 4 lazy matches */
	  new Config(8, 16, 32, 32, deflate_slow),         /* 5 */
	  new Config(8, 16, 128, 128, deflate_slow),       /* 6 */
	  new Config(8, 32, 128, 256, deflate_slow),       /* 7 */
	  new Config(32, 128, 258, 1024, deflate_slow),    /* 8 */
	  new Config(32, 258, 258, 4096, deflate_slow)     /* 9 max compression */
	];
	
	
	/* ===========================================================================
	 * Initialize the "longest match" routines for a new zlib stream
	 */
	function lm_init(s) {
	  s.window_size = 2 * s.w_size;
	
	  /*** CLEAR_HASH(s); ***/
	  zero(s.head); // Fill with NIL (= 0);
	
	  /* Set the default configuration parameters:
	   */
	  s.max_lazy_match = configuration_table[s.level].max_lazy;
	  s.good_match = configuration_table[s.level].good_length;
	  s.nice_match = configuration_table[s.level].nice_length;
	  s.max_chain_length = configuration_table[s.level].max_chain;
	
	  s.strstart = 0;
	  s.block_start = 0;
	  s.lookahead = 0;
	  s.insert = 0;
	  s.match_length = s.prev_length = MIN_MATCH - 1;
	  s.match_available = 0;
	  s.ins_h = 0;
	}
	
	
	function DeflateState() {
	  this.strm = null;            /* pointer back to this zlib stream */
	  this.status = 0;            /* as the name implies */
	  this.pending_buf = null;      /* output still pending */
	  this.pending_buf_size = 0;  /* size of pending_buf */
	  this.pending_out = 0;       /* next pending byte to output to the stream */
	  this.pending = 0;           /* nb of bytes in the pending buffer */
	  this.wrap = 0;              /* bit 0 true for zlib, bit 1 true for gzip */
	  this.gzhead = null;         /* gzip header information to write */
	  this.gzindex = 0;           /* where in extra, name, or comment */
	  this.method = Z_DEFLATED; /* can only be DEFLATED */
	  this.last_flush = -1;   /* value of flush param for previous deflate call */
	
	  this.w_size = 0;  /* LZ77 window size (32K by default) */
	  this.w_bits = 0;  /* log2(w_size)  (8..16) */
	  this.w_mask = 0;  /* w_size - 1 */
	
	  this.window = null;
	  /* Sliding window. Input bytes are read into the second half of the window,
	   * and move to the first half later to keep a dictionary of at least wSize
	   * bytes. With this organization, matches are limited to a distance of
	   * wSize-MAX_MATCH bytes, but this ensures that IO is always
	   * performed with a length multiple of the block size.
	   */
	
	  this.window_size = 0;
	  /* Actual size of window: 2*wSize, except when the user input buffer
	   * is directly used as sliding window.
	   */
	
	  this.prev = null;
	  /* Link to older string with same hash index. To limit the size of this
	   * array to 64K, this link is maintained only for the last 32K strings.
	   * An index in this array is thus a window index modulo 32K.
	   */
	
	  this.head = null;   /* Heads of the hash chains or NIL. */
	
	  this.ins_h = 0;       /* hash index of string to be inserted */
	  this.hash_size = 0;   /* number of elements in hash table */
	  this.hash_bits = 0;   /* log2(hash_size) */
	  this.hash_mask = 0;   /* hash_size-1 */
	
	  this.hash_shift = 0;
	  /* Number of bits by which ins_h must be shifted at each input
	   * step. It must be such that after MIN_MATCH steps, the oldest
	   * byte no longer takes part in the hash key, that is:
	   *   hash_shift * MIN_MATCH >= hash_bits
	   */
	
	  this.block_start = 0;
	  /* Window position at the beginning of the current output block. Gets
	   * negative when the window is moved backwards.
	   */
	
	  this.match_length = 0;      /* length of best match */
	  this.prev_match = 0;        /* previous match */
	  this.match_available = 0;   /* set if previous match exists */
	  this.strstart = 0;          /* start of string to insert */
	  this.match_start = 0;       /* start of matching string */
	  this.lookahead = 0;         /* number of valid bytes ahead in window */
	
	  this.prev_length = 0;
	  /* Length of the best match at previous step. Matches not greater than this
	   * are discarded. This is used in the lazy match evaluation.
	   */
	
	  this.max_chain_length = 0;
	  /* To speed up deflation, hash chains are never searched beyond this
	   * length.  A higher limit improves compression ratio but degrades the
	   * speed.
	   */
	
	  this.max_lazy_match = 0;
	  /* Attempt to find a better match only when the current match is strictly
	   * smaller than this value. This mechanism is used only for compression
	   * levels >= 4.
	   */
	  // That's alias to max_lazy_match, don't use directly
	  //this.max_insert_length = 0;
	  /* Insert new strings in the hash table only if the match length is not
	   * greater than this length. This saves time but degrades compression.
	   * max_insert_length is used only for compression levels <= 3.
	   */
	
	  this.level = 0;     /* compression level (1..9) */
	  this.strategy = 0;  /* favor or force Huffman coding*/
	
	  this.good_match = 0;
	  /* Use a faster search when the previous match is longer than this */
	
	  this.nice_match = 0; /* Stop searching when current match exceeds this */
	
	              /* used by trees.c: */
	
	  /* Didn't use ct_data typedef below to suppress compiler warning */
	
	  // struct ct_data_s dyn_ltree[HEAP_SIZE];   /* literal and length tree */
	  // struct ct_data_s dyn_dtree[2*D_CODES+1]; /* distance tree */
	  // struct ct_data_s bl_tree[2*BL_CODES+1];  /* Huffman tree for bit lengths */
	
	  // Use flat array of DOUBLE size, with interleaved fata,
	  // because JS does not support effective
	  this.dyn_ltree  = new utils.Buf16(HEAP_SIZE * 2);
	  this.dyn_dtree  = new utils.Buf16((2 * D_CODES + 1) * 2);
	  this.bl_tree    = new utils.Buf16((2 * BL_CODES + 1) * 2);
	  zero(this.dyn_ltree);
	  zero(this.dyn_dtree);
	  zero(this.bl_tree);
	
	  this.l_desc   = null;         /* desc. for literal tree */
	  this.d_desc   = null;         /* desc. for distance tree */
	  this.bl_desc  = null;         /* desc. for bit length tree */
	
	  //ush bl_count[MAX_BITS+1];
	  this.bl_count = new utils.Buf16(MAX_BITS + 1);
	  /* number of codes at each bit length for an optimal tree */
	
	  //int heap[2*L_CODES+1];      /* heap used to build the Huffman trees */
	  this.heap = new utils.Buf16(2 * L_CODES + 1);  /* heap used to build the Huffman trees */
	  zero(this.heap);
	
	  this.heap_len = 0;               /* number of elements in the heap */
	  this.heap_max = 0;               /* element of largest frequency */
	  /* The sons of heap[n] are heap[2*n] and heap[2*n+1]. heap[0] is not used.
	   * The same heap array is used to build all trees.
	   */
	
	  this.depth = new utils.Buf16(2 * L_CODES + 1); //uch depth[2*L_CODES+1];
	  zero(this.depth);
	  /* Depth of each subtree used as tie breaker for trees of equal frequency
	   */
	
	  this.l_buf = 0;          /* buffer index for literals or lengths */
	
	  this.lit_bufsize = 0;
	  /* Size of match buffer for literals/lengths.  There are 4 reasons for
	   * limiting lit_bufsize to 64K:
	   *   - frequencies can be kept in 16 bit counters
	   *   - if compression is not successful for the first block, all input
	   *     data is still in the window so we can still emit a stored block even
	   *     when input comes from standard input.  (This can also be done for
	   *     all blocks if lit_bufsize is not greater than 32K.)
	   *   - if compression is not successful for a file smaller than 64K, we can
	   *     even emit a stored file instead of a stored block (saving 5 bytes).
	   *     This is applicable only for zip (not gzip or zlib).
	   *   - creating new Huffman trees less frequently may not provide fast
	   *     adaptation to changes in the input data statistics. (Take for
	   *     example a binary file with poorly compressible code followed by
	   *     a highly compressible string table.) Smaller buffer sizes give
	   *     fast adaptation but have of course the overhead of transmitting
	   *     trees more frequently.
	   *   - I can't count above 4
	   */
	
	  this.last_lit = 0;      /* running index in l_buf */
	
	  this.d_buf = 0;
	  /* Buffer index for distances. To simplify the code, d_buf and l_buf have
	   * the same number of elements. To use different lengths, an extra flag
	   * array would be necessary.
	   */
	
	  this.opt_len = 0;       /* bit length of current block with optimal trees */
	  this.static_len = 0;    /* bit length of current block with static trees */
	  this.matches = 0;       /* number of string matches in current block */
	  this.insert = 0;        /* bytes at end of window left to insert */
	
	
	  this.bi_buf = 0;
	  /* Output buffer. bits are inserted starting at the bottom (least
	   * significant bits).
	   */
	  this.bi_valid = 0;
	  /* Number of valid bits in bi_buf.  All bits above the last valid bit
	   * are always zero.
	   */
	
	  // Used for window memory init. We safely ignore it for JS. That makes
	  // sense only for pointers and memory check tools.
	  //this.high_water = 0;
	  /* High water mark offset in window for initialized bytes -- bytes above
	   * this are set to zero in order to avoid memory check warnings when
	   * longest match routines access bytes past the input.  This is then
	   * updated to the new high water mark.
	   */
	}
	
	
	function deflateResetKeep(strm) {
	  var s;
	
	  if (!strm || !strm.state) {
	    return err(strm, Z_STREAM_ERROR);
	  }
	
	  strm.total_in = strm.total_out = 0;
	  strm.data_type = Z_UNKNOWN;
	
	  s = strm.state;
	  s.pending = 0;
	  s.pending_out = 0;
	
	  if (s.wrap < 0) {
	    s.wrap = -s.wrap;
	    /* was made negative by deflate(..., Z_FINISH); */
	  }
	  s.status = (s.wrap ? INIT_STATE : BUSY_STATE);
	  strm.adler = (s.wrap === 2) ?
	    0  // crc32(0, Z_NULL, 0)
	  :
	    1; // adler32(0, Z_NULL, 0)
	  s.last_flush = Z_NO_FLUSH;
	  trees._tr_init(s);
	  return Z_OK;
	}
	
	
	function deflateReset(strm) {
	  var ret = deflateResetKeep(strm);
	  if (ret === Z_OK) {
	    lm_init(strm.state);
	  }
	  return ret;
	}
	
	
	function deflateSetHeader(strm, head) {
	  if (!strm || !strm.state) { return Z_STREAM_ERROR; }
	  if (strm.state.wrap !== 2) { return Z_STREAM_ERROR; }
	  strm.state.gzhead = head;
	  return Z_OK;
	}
	
	
	function deflateInit2(strm, level, method, windowBits, memLevel, strategy) {
	  if (!strm) { // === Z_NULL
	    return Z_STREAM_ERROR;
	  }
	  var wrap = 1;
	
	  if (level === Z_DEFAULT_COMPRESSION) {
	    level = 6;
	  }
	
	  if (windowBits < 0) { /* suppress zlib wrapper */
	    wrap = 0;
	    windowBits = -windowBits;
	  }
	
	  else if (windowBits > 15) {
	    wrap = 2;           /* write gzip wrapper instead */
	    windowBits -= 16;
	  }
	
	
	  if (memLevel < 1 || memLevel > MAX_MEM_LEVEL || method !== Z_DEFLATED ||
	    windowBits < 8 || windowBits > 15 || level < 0 || level > 9 ||
	    strategy < 0 || strategy > Z_FIXED) {
	    return err(strm, Z_STREAM_ERROR);
	  }
	
	
	  if (windowBits === 8) {
	    windowBits = 9;
	  }
	  /* until 256-byte window bug fixed */
	
	  var s = new DeflateState();
	
	  strm.state = s;
	  s.strm = strm;
	
	  s.wrap = wrap;
	  s.gzhead = null;
	  s.w_bits = windowBits;
	  s.w_size = 1 << s.w_bits;
	  s.w_mask = s.w_size - 1;
	
	  s.hash_bits = memLevel + 7;
	  s.hash_size = 1 << s.hash_bits;
	  s.hash_mask = s.hash_size - 1;
	  s.hash_shift = ~~((s.hash_bits + MIN_MATCH - 1) / MIN_MATCH);
	
	  s.window = new utils.Buf8(s.w_size * 2);
	  s.head = new utils.Buf16(s.hash_size);
	  s.prev = new utils.Buf16(s.w_size);
	
	  // Don't need mem init magic for JS.
	  //s.high_water = 0;  /* nothing written to s->window yet */
	
	  s.lit_bufsize = 1 << (memLevel + 6); /* 16K elements by default */
	
	  s.pending_buf_size = s.lit_bufsize * 4;
	  s.pending_buf = new utils.Buf8(s.pending_buf_size);
	
	  s.d_buf = s.lit_bufsize >> 1;
	  s.l_buf = (1 + 2) * s.lit_bufsize;
	
	  s.level = level;
	  s.strategy = strategy;
	  s.method = method;
	
	  return deflateReset(strm);
	}
	
	function deflateInit(strm, level) {
	  return deflateInit2(strm, level, Z_DEFLATED, MAX_WBITS, DEF_MEM_LEVEL, Z_DEFAULT_STRATEGY);
	}
	
	
	function deflate(strm, flush) {
	  var old_flush, s;
	  var beg, val; // for gzip header write only
	
	  if (!strm || !strm.state ||
	    flush > Z_BLOCK || flush < 0) {
	    return strm ? err(strm, Z_STREAM_ERROR) : Z_STREAM_ERROR;
	  }
	
	  s = strm.state;
	
	  if (!strm.output ||
	      (!strm.input && strm.avail_in !== 0) ||
	      (s.status === FINISH_STATE && flush !== Z_FINISH)) {
	    return err(strm, (strm.avail_out === 0) ? Z_BUF_ERROR : Z_STREAM_ERROR);
	  }
	
	  s.strm = strm; /* just in case */
	  old_flush = s.last_flush;
	  s.last_flush = flush;
	
	  /* Write the header */
	  if (s.status === INIT_STATE) {
	
	    if (s.wrap === 2) { // GZIP header
	      strm.adler = 0;  //crc32(0L, Z_NULL, 0);
	      put_byte(s, 31);
	      put_byte(s, 139);
	      put_byte(s, 8);
	      if (!s.gzhead) { // s->gzhead == Z_NULL
	        put_byte(s, 0);
	        put_byte(s, 0);
	        put_byte(s, 0);
	        put_byte(s, 0);
	        put_byte(s, 0);
	        put_byte(s, s.level === 9 ? 2 :
	                    (s.strategy >= Z_HUFFMAN_ONLY || s.level < 2 ?
	                     4 : 0));
	        put_byte(s, OS_CODE);
	        s.status = BUSY_STATE;
	      }
	      else {
	        put_byte(s, (s.gzhead.text ? 1 : 0) +
	                    (s.gzhead.hcrc ? 2 : 0) +
	                    (!s.gzhead.extra ? 0 : 4) +
	                    (!s.gzhead.name ? 0 : 8) +
	                    (!s.gzhead.comment ? 0 : 16)
	                );
	        put_byte(s, s.gzhead.time & 0xff);
	        put_byte(s, (s.gzhead.time >> 8) & 0xff);
	        put_byte(s, (s.gzhead.time >> 16) & 0xff);
	        put_byte(s, (s.gzhead.time >> 24) & 0xff);
	        put_byte(s, s.level === 9 ? 2 :
	                    (s.strategy >= Z_HUFFMAN_ONLY || s.level < 2 ?
	                     4 : 0));
	        put_byte(s, s.gzhead.os & 0xff);
	        if (s.gzhead.extra && s.gzhead.extra.length) {
	          put_byte(s, s.gzhead.extra.length & 0xff);
	          put_byte(s, (s.gzhead.extra.length >> 8) & 0xff);
	        }
	        if (s.gzhead.hcrc) {
	          strm.adler = crc32(strm.adler, s.pending_buf, s.pending, 0);
	        }
	        s.gzindex = 0;
	        s.status = EXTRA_STATE;
	      }
	    }
	    else // DEFLATE header
	    {
	      var header = (Z_DEFLATED + ((s.w_bits - 8) << 4)) << 8;
	      var level_flags = -1;
	
	      if (s.strategy >= Z_HUFFMAN_ONLY || s.level < 2) {
	        level_flags = 0;
	      } else if (s.level < 6) {
	        level_flags = 1;
	      } else if (s.level === 6) {
	        level_flags = 2;
	      } else {
	        level_flags = 3;
	      }
	      header |= (level_flags << 6);
	      if (s.strstart !== 0) { header |= PRESET_DICT; }
	      header += 31 - (header % 31);
	
	      s.status = BUSY_STATE;
	      putShortMSB(s, header);
	
	      /* Save the adler32 of the preset dictionary: */
	      if (s.strstart !== 0) {
	        putShortMSB(s, strm.adler >>> 16);
	        putShortMSB(s, strm.adler & 0xffff);
	      }
	      strm.adler = 1; // adler32(0L, Z_NULL, 0);
	    }
	  }
	
	//#ifdef GZIP
	  if (s.status === EXTRA_STATE) {
	    if (s.gzhead.extra/* != Z_NULL*/) {
	      beg = s.pending;  /* start of bytes to update crc */
	
	      while (s.gzindex < (s.gzhead.extra.length & 0xffff)) {
	        if (s.pending === s.pending_buf_size) {
	          if (s.gzhead.hcrc && s.pending > beg) {
	            strm.adler = crc32(strm.adler, s.pending_buf, s.pending - beg, beg);
	          }
	          flush_pending(strm);
	          beg = s.pending;
	          if (s.pending === s.pending_buf_size) {
	            break;
	          }
	        }
	        put_byte(s, s.gzhead.extra[s.gzindex] & 0xff);
	        s.gzindex++;
	      }
	      if (s.gzhead.hcrc && s.pending > beg) {
	        strm.adler = crc32(strm.adler, s.pending_buf, s.pending - beg, beg);
	      }
	      if (s.gzindex === s.gzhead.extra.length) {
	        s.gzindex = 0;
	        s.status = NAME_STATE;
	      }
	    }
	    else {
	      s.status = NAME_STATE;
	    }
	  }
	  if (s.status === NAME_STATE) {
	    if (s.gzhead.name/* != Z_NULL*/) {
	      beg = s.pending;  /* start of bytes to update crc */
	      //int val;
	
	      do {
	        if (s.pending === s.pending_buf_size) {
	          if (s.gzhead.hcrc && s.pending > beg) {
	            strm.adler = crc32(strm.adler, s.pending_buf, s.pending - beg, beg);
	          }
	          flush_pending(strm);
	          beg = s.pending;
	          if (s.pending === s.pending_buf_size) {
	            val = 1;
	            break;
	          }
	        }
	        // JS specific: little magic to add zero terminator to end of string
	        if (s.gzindex < s.gzhead.name.length) {
	          val = s.gzhead.name.charCodeAt(s.gzindex++) & 0xff;
	        } else {
	          val = 0;
	        }
	        put_byte(s, val);
	      } while (val !== 0);
	
	      if (s.gzhead.hcrc && s.pending > beg) {
	        strm.adler = crc32(strm.adler, s.pending_buf, s.pending - beg, beg);
	      }
	      if (val === 0) {
	        s.gzindex = 0;
	        s.status = COMMENT_STATE;
	      }
	    }
	    else {
	      s.status = COMMENT_STATE;
	    }
	  }
	  if (s.status === COMMENT_STATE) {
	    if (s.gzhead.comment/* != Z_NULL*/) {
	      beg = s.pending;  /* start of bytes to update crc */
	      //int val;
	
	      do {
	        if (s.pending === s.pending_buf_size) {
	          if (s.gzhead.hcrc && s.pending > beg) {
	            strm.adler = crc32(strm.adler, s.pending_buf, s.pending - beg, beg);
	          }
	          flush_pending(strm);
	          beg = s.pending;
	          if (s.pending === s.pending_buf_size) {
	            val = 1;
	            break;
	          }
	        }
	        // JS specific: little magic to add zero terminator to end of string
	        if (s.gzindex < s.gzhead.comment.length) {
	          val = s.gzhead.comment.charCodeAt(s.gzindex++) & 0xff;
	        } else {
	          val = 0;
	        }
	        put_byte(s, val);
	      } while (val !== 0);
	
	      if (s.gzhead.hcrc && s.pending > beg) {
	        strm.adler = crc32(strm.adler, s.pending_buf, s.pending - beg, beg);
	      }
	      if (val === 0) {
	        s.status = HCRC_STATE;
	      }
	    }
	    else {
	      s.status = HCRC_STATE;
	    }
	  }
	  if (s.status === HCRC_STATE) {
	    if (s.gzhead.hcrc) {
	      if (s.pending + 2 > s.pending_buf_size) {
	        flush_pending(strm);
	      }
	      if (s.pending + 2 <= s.pending_buf_size) {
	        put_byte(s, strm.adler & 0xff);
	        put_byte(s, (strm.adler >> 8) & 0xff);
	        strm.adler = 0; //crc32(0L, Z_NULL, 0);
	        s.status = BUSY_STATE;
	      }
	    }
	    else {
	      s.status = BUSY_STATE;
	    }
	  }
	//#endif
	
	  /* Flush as much pending output as possible */
	  if (s.pending !== 0) {
	    flush_pending(strm);
	    if (strm.avail_out === 0) {
	      /* Since avail_out is 0, deflate will be called again with
	       * more output space, but possibly with both pending and
	       * avail_in equal to zero. There won't be anything to do,
	       * but this is not an error situation so make sure we
	       * return OK instead of BUF_ERROR at next call of deflate:
	       */
	      s.last_flush = -1;
	      return Z_OK;
	    }
	
	    /* Make sure there is something to do and avoid duplicate consecutive
	     * flushes. For repeated and useless calls with Z_FINISH, we keep
	     * returning Z_STREAM_END instead of Z_BUF_ERROR.
	     */
	  } else if (strm.avail_in === 0 && rank(flush) <= rank(old_flush) &&
	    flush !== Z_FINISH) {
	    return err(strm, Z_BUF_ERROR);
	  }
	
	  /* User must not provide more input after the first FINISH: */
	  if (s.status === FINISH_STATE && strm.avail_in !== 0) {
	    return err(strm, Z_BUF_ERROR);
	  }
	
	  /* Start a new block or continue the current one.
	   */
	  if (strm.avail_in !== 0 || s.lookahead !== 0 ||
	    (flush !== Z_NO_FLUSH && s.status !== FINISH_STATE)) {
	    var bstate = (s.strategy === Z_HUFFMAN_ONLY) ? deflate_huff(s, flush) :
	      (s.strategy === Z_RLE ? deflate_rle(s, flush) :
	        configuration_table[s.level].func(s, flush));
	
	    if (bstate === BS_FINISH_STARTED || bstate === BS_FINISH_DONE) {
	      s.status = FINISH_STATE;
	    }
	    if (bstate === BS_NEED_MORE || bstate === BS_FINISH_STARTED) {
	      if (strm.avail_out === 0) {
	        s.last_flush = -1;
	        /* avoid BUF_ERROR next call, see above */
	      }
	      return Z_OK;
	      /* If flush != Z_NO_FLUSH && avail_out == 0, the next call
	       * of deflate should use the same flush parameter to make sure
	       * that the flush is complete. So we don't have to output an
	       * empty block here, this will be done at next call. This also
	       * ensures that for a very small output buffer, we emit at most
	       * one empty block.
	       */
	    }
	    if (bstate === BS_BLOCK_DONE) {
	      if (flush === Z_PARTIAL_FLUSH) {
	        trees._tr_align(s);
	      }
	      else if (flush !== Z_BLOCK) { /* FULL_FLUSH or SYNC_FLUSH */
	
	        trees._tr_stored_block(s, 0, 0, false);
	        /* For a full flush, this empty block will be recognized
	         * as a special marker by inflate_sync().
	         */
	        if (flush === Z_FULL_FLUSH) {
	          /*** CLEAR_HASH(s); ***/             /* forget history */
	          zero(s.head); // Fill with NIL (= 0);
	
	          if (s.lookahead === 0) {
	            s.strstart = 0;
	            s.block_start = 0;
	            s.insert = 0;
	          }
	        }
	      }
	      flush_pending(strm);
	      if (strm.avail_out === 0) {
	        s.last_flush = -1; /* avoid BUF_ERROR at next call, see above */
	        return Z_OK;
	      }
	    }
	  }
	  //Assert(strm->avail_out > 0, "bug2");
	  //if (strm.avail_out <= 0) { throw new Error("bug2");}
	
	  if (flush !== Z_FINISH) { return Z_OK; }
	  if (s.wrap <= 0) { return Z_STREAM_END; }
	
	  /* Write the trailer */
	  if (s.wrap === 2) {
	    put_byte(s, strm.adler & 0xff);
	    put_byte(s, (strm.adler >> 8) & 0xff);
	    put_byte(s, (strm.adler >> 16) & 0xff);
	    put_byte(s, (strm.adler >> 24) & 0xff);
	    put_byte(s, strm.total_in & 0xff);
	    put_byte(s, (strm.total_in >> 8) & 0xff);
	    put_byte(s, (strm.total_in >> 16) & 0xff);
	    put_byte(s, (strm.total_in >> 24) & 0xff);
	  }
	  else
	  {
	    putShortMSB(s, strm.adler >>> 16);
	    putShortMSB(s, strm.adler & 0xffff);
	  }
	
	  flush_pending(strm);
	  /* If avail_out is zero, the application will call deflate again
	   * to flush the rest.
	   */
	  if (s.wrap > 0) { s.wrap = -s.wrap; }
	  /* write the trailer only once! */
	  return s.pending !== 0 ? Z_OK : Z_STREAM_END;
	}
	
	function deflateEnd(strm) {
	  var status;
	
	  if (!strm/*== Z_NULL*/ || !strm.state/*== Z_NULL*/) {
	    return Z_STREAM_ERROR;
	  }
	
	  status = strm.state.status;
	  if (status !== INIT_STATE &&
	    status !== EXTRA_STATE &&
	    status !== NAME_STATE &&
	    status !== COMMENT_STATE &&
	    status !== HCRC_STATE &&
	    status !== BUSY_STATE &&
	    status !== FINISH_STATE
	  ) {
	    return err(strm, Z_STREAM_ERROR);
	  }
	
	  strm.state = null;
	
	  return status === BUSY_STATE ? err(strm, Z_DATA_ERROR) : Z_OK;
	}
	
	/* =========================================================================
	 * Copy the source state to the destination state
	 */
	//function deflateCopy(dest, source) {
	//
	//}
	
	exports.deflateInit = deflateInit;
	exports.deflateInit2 = deflateInit2;
	exports.deflateReset = deflateReset;
	exports.deflateResetKeep = deflateResetKeep;
	exports.deflateSetHeader = deflateSetHeader;
	exports.deflate = deflate;
	exports.deflateEnd = deflateEnd;
	exports.deflateInfo = 'pako deflate (from Nodeca project)';
	
	/* Not implemented
	exports.deflateBound = deflateBound;
	exports.deflateCopy = deflateCopy;
	exports.deflateSetDictionary = deflateSetDictionary;
	exports.deflateParams = deflateParams;
	exports.deflatePending = deflatePending;
	exports.deflatePrime = deflatePrime;
	exports.deflateTune = deflateTune;
	*/


/***/ },
/* 35 */
/***/ function(module, exports) {

	'use strict';
	
	
	var TYPED_OK =  (typeof Uint8Array !== 'undefined') &&
	                (typeof Uint16Array !== 'undefined') &&
	                (typeof Int32Array !== 'undefined');
	
	
	exports.assign = function (obj /*from1, from2, from3, ...*/) {
	  var sources = Array.prototype.slice.call(arguments, 1);
	  while (sources.length) {
	    var source = sources.shift();
	    if (!source) { continue; }
	
	    if (typeof source !== 'object') {
	      throw new TypeError(source + 'must be non-object');
	    }
	
	    for (var p in source) {
	      if (source.hasOwnProperty(p)) {
	        obj[p] = source[p];
	      }
	    }
	  }
	
	  return obj;
	};
	
	
	// reduce buffer size, avoiding mem copy
	exports.shrinkBuf = function (buf, size) {
	  if (buf.length === size) { return buf; }
	  if (buf.subarray) { return buf.subarray(0, size); }
	  buf.length = size;
	  return buf;
	};
	
	
	var fnTyped = {
	  arraySet: function (dest, src, src_offs, len, dest_offs) {
	    if (src.subarray && dest.subarray) {
	      dest.set(src.subarray(src_offs, src_offs + len), dest_offs);
	      return;
	    }
	    // Fallback to ordinary array
	    for (var i = 0; i < len; i++) {
	      dest[dest_offs + i] = src[src_offs + i];
	    }
	  },
	  // Join array of chunks to single array.
	  flattenChunks: function (chunks) {
	    var i, l, len, pos, chunk, result;
	
	    // calculate data length
	    len = 0;
	    for (i = 0, l = chunks.length; i < l; i++) {
	      len += chunks[i].length;
	    }
	
	    // join chunks
	    result = new Uint8Array(len);
	    pos = 0;
	    for (i = 0, l = chunks.length; i < l; i++) {
	      chunk = chunks[i];
	      result.set(chunk, pos);
	      pos += chunk.length;
	    }
	
	    return result;
	  }
	};
	
	var fnUntyped = {
	  arraySet: function (dest, src, src_offs, len, dest_offs) {
	    for (var i = 0; i < len; i++) {
	      dest[dest_offs + i] = src[src_offs + i];
	    }
	  },
	  // Join array of chunks to single array.
	  flattenChunks: function (chunks) {
	    return [].concat.apply([], chunks);
	  }
	};
	
	
	// Enable/Disable typed arrays use, for testing
	//
	exports.setTyped = function (on) {
	  if (on) {
	    exports.Buf8  = Uint8Array;
	    exports.Buf16 = Uint16Array;
	    exports.Buf32 = Int32Array;
	    exports.assign(exports, fnTyped);
	  } else {
	    exports.Buf8  = Array;
	    exports.Buf16 = Array;
	    exports.Buf32 = Array;
	    exports.assign(exports, fnUntyped);
	  }
	};
	
	exports.setTyped(TYPED_OK);


/***/ },
/* 36 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	
	
	var utils = __webpack_require__(35);
	
	/* Public constants ==========================================================*/
	/* ===========================================================================*/
	
	
	//var Z_FILTERED          = 1;
	//var Z_HUFFMAN_ONLY      = 2;
	//var Z_RLE               = 3;
	var Z_FIXED               = 4;
	//var Z_DEFAULT_STRATEGY  = 0;
	
	/* Possible values of the data_type field (though see inflate()) */
	var Z_BINARY              = 0;
	var Z_TEXT                = 1;
	//var Z_ASCII             = 1; // = Z_TEXT
	var Z_UNKNOWN             = 2;
	
	/*============================================================================*/
	
	
	function zero(buf) { var len = buf.length; while (--len >= 0) { buf[len] = 0; } }
	
	// From zutil.h
	
	var STORED_BLOCK = 0;
	var STATIC_TREES = 1;
	var DYN_TREES    = 2;
	/* The three kinds of block type */
	
	var MIN_MATCH    = 3;
	var MAX_MATCH    = 258;
	/* The minimum and maximum match lengths */
	
	// From deflate.h
	/* ===========================================================================
	 * Internal compression state.
	 */
	
	var LENGTH_CODES  = 29;
	/* number of length codes, not counting the special END_BLOCK code */
	
	var LITERALS      = 256;
	/* number of literal bytes 0..255 */
	
	var L_CODES       = LITERALS + 1 + LENGTH_CODES;
	/* number of Literal or Length codes, including the END_BLOCK code */
	
	var D_CODES       = 30;
	/* number of distance codes */
	
	var BL_CODES      = 19;
	/* number of codes used to transfer the bit lengths */
	
	var HEAP_SIZE     = 2 * L_CODES + 1;
	/* maximum heap size */
	
	var MAX_BITS      = 15;
	/* All codes must not exceed MAX_BITS bits */
	
	var Buf_size      = 16;
	/* size of bit buffer in bi_buf */
	
	
	/* ===========================================================================
	 * Constants
	 */
	
	var MAX_BL_BITS = 7;
	/* Bit length codes must not exceed MAX_BL_BITS bits */
	
	var END_BLOCK   = 256;
	/* end of block literal code */
	
	var REP_3_6     = 16;
	/* repeat previous bit length 3-6 times (2 bits of repeat count) */
	
	var REPZ_3_10   = 17;
	/* repeat a zero length 3-10 times  (3 bits of repeat count) */
	
	var REPZ_11_138 = 18;
	/* repeat a zero length 11-138 times  (7 bits of repeat count) */
	
	/* eslint-disable comma-spacing,array-bracket-spacing */
	var extra_lbits =   /* extra bits for each length code */
	  [0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0];
	
	var extra_dbits =   /* extra bits for each distance code */
	  [0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13];
	
	var extra_blbits =  /* extra bits for each bit length code */
	  [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,2,3,7];
	
	var bl_order =
	  [16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15];
	/* eslint-enable comma-spacing,array-bracket-spacing */
	
	/* The lengths of the bit length codes are sent in order of decreasing
	 * probability, to avoid transmitting the lengths for unused bit length codes.
	 */
	
	/* ===========================================================================
	 * Local data. These are initialized only once.
	 */
	
	// We pre-fill arrays with 0 to avoid uninitialized gaps
	
	var DIST_CODE_LEN = 512; /* see definition of array dist_code below */
	
	// !!!! Use flat array insdead of structure, Freq = i*2, Len = i*2+1
	var static_ltree  = new Array((L_CODES + 2) * 2);
	zero(static_ltree);
	/* The static literal tree. Since the bit lengths are imposed, there is no
	 * need for the L_CODES extra codes used during heap construction. However
	 * The codes 286 and 287 are needed to build a canonical tree (see _tr_init
	 * below).
	 */
	
	var static_dtree  = new Array(D_CODES * 2);
	zero(static_dtree);
	/* The static distance tree. (Actually a trivial tree since all codes use
	 * 5 bits.)
	 */
	
	var _dist_code    = new Array(DIST_CODE_LEN);
	zero(_dist_code);
	/* Distance codes. The first 256 values correspond to the distances
	 * 3 .. 258, the last 256 values correspond to the top 8 bits of
	 * the 15 bit distances.
	 */
	
	var _length_code  = new Array(MAX_MATCH - MIN_MATCH + 1);
	zero(_length_code);
	/* length code for each normalized match length (0 == MIN_MATCH) */
	
	var base_length   = new Array(LENGTH_CODES);
	zero(base_length);
	/* First normalized length for each code (0 = MIN_MATCH) */
	
	var base_dist     = new Array(D_CODES);
	zero(base_dist);
	/* First normalized distance for each code (0 = distance of 1) */
	
	
	function StaticTreeDesc(static_tree, extra_bits, extra_base, elems, max_length) {
	
	  this.static_tree  = static_tree;  /* static tree or NULL */
	  this.extra_bits   = extra_bits;   /* extra bits for each code or NULL */
	  this.extra_base   = extra_base;   /* base index for extra_bits */
	  this.elems        = elems;        /* max number of elements in the tree */
	  this.max_length   = max_length;   /* max bit length for the codes */
	
	  // show if `static_tree` has data or dummy - needed for monomorphic objects
	  this.has_stree    = static_tree && static_tree.length;
	}
	
	
	var static_l_desc;
	var static_d_desc;
	var static_bl_desc;
	
	
	function TreeDesc(dyn_tree, stat_desc) {
	  this.dyn_tree = dyn_tree;     /* the dynamic tree */
	  this.max_code = 0;            /* largest code with non zero frequency */
	  this.stat_desc = stat_desc;   /* the corresponding static tree */
	}
	
	
	
	function d_code(dist) {
	  return dist < 256 ? _dist_code[dist] : _dist_code[256 + (dist >>> 7)];
	}
	
	
	/* ===========================================================================
	 * Output a short LSB first on the stream.
	 * IN assertion: there is enough room in pendingBuf.
	 */
	function put_short(s, w) {
	//    put_byte(s, (uch)((w) & 0xff));
	//    put_byte(s, (uch)((ush)(w) >> 8));
	  s.pending_buf[s.pending++] = (w) & 0xff;
	  s.pending_buf[s.pending++] = (w >>> 8) & 0xff;
	}
	
	
	/* ===========================================================================
	 * Send a value on a given number of bits.
	 * IN assertion: length <= 16 and value fits in length bits.
	 */
	function send_bits(s, value, length) {
	  if (s.bi_valid > (Buf_size - length)) {
	    s.bi_buf |= (value << s.bi_valid) & 0xffff;
	    put_short(s, s.bi_buf);
	    s.bi_buf = value >> (Buf_size - s.bi_valid);
	    s.bi_valid += length - Buf_size;
	  } else {
	    s.bi_buf |= (value << s.bi_valid) & 0xffff;
	    s.bi_valid += length;
	  }
	}
	
	
	function send_code(s, c, tree) {
	  send_bits(s, tree[c * 2]/*.Code*/, tree[c * 2 + 1]/*.Len*/);
	}
	
	
	/* ===========================================================================
	 * Reverse the first len bits of a code, using straightforward code (a faster
	 * method would use a table)
	 * IN assertion: 1 <= len <= 15
	 */
	function bi_reverse(code, len) {
	  var res = 0;
	  do {
	    res |= code & 1;
	    code >>>= 1;
	    res <<= 1;
	  } while (--len > 0);
	  return res >>> 1;
	}
	
	
	/* ===========================================================================
	 * Flush the bit buffer, keeping at most 7 bits in it.
	 */
	function bi_flush(s) {
	  if (s.bi_valid === 16) {
	    put_short(s, s.bi_buf);
	    s.bi_buf = 0;
	    s.bi_valid = 0;
	
	  } else if (s.bi_valid >= 8) {
	    s.pending_buf[s.pending++] = s.bi_buf & 0xff;
	    s.bi_buf >>= 8;
	    s.bi_valid -= 8;
	  }
	}
	
	
	/* ===========================================================================
	 * Compute the optimal bit lengths for a tree and update the total bit length
	 * for the current block.
	 * IN assertion: the fields freq and dad are set, heap[heap_max] and
	 *    above are the tree nodes sorted by increasing frequency.
	 * OUT assertions: the field len is set to the optimal bit length, the
	 *     array bl_count contains the frequencies for each bit length.
	 *     The length opt_len is updated; static_len is also updated if stree is
	 *     not null.
	 */
	function gen_bitlen(s, desc)
	//    deflate_state *s;
	//    tree_desc *desc;    /* the tree descriptor */
	{
	  var tree            = desc.dyn_tree;
	  var max_code        = desc.max_code;
	  var stree           = desc.stat_desc.static_tree;
	  var has_stree       = desc.stat_desc.has_stree;
	  var extra           = desc.stat_desc.extra_bits;
	  var base            = desc.stat_desc.extra_base;
	  var max_length      = desc.stat_desc.max_length;
	  var h;              /* heap index */
	  var n, m;           /* iterate over the tree elements */
	  var bits;           /* bit length */
	  var xbits;          /* extra bits */
	  var f;              /* frequency */
	  var overflow = 0;   /* number of elements with bit length too large */
	
	  for (bits = 0; bits <= MAX_BITS; bits++) {
	    s.bl_count[bits] = 0;
	  }
	
	  /* In a first pass, compute the optimal bit lengths (which may
	   * overflow in the case of the bit length tree).
	   */
	  tree[s.heap[s.heap_max] * 2 + 1]/*.Len*/ = 0; /* root of the heap */
	
	  for (h = s.heap_max + 1; h < HEAP_SIZE; h++) {
	    n = s.heap[h];
	    bits = tree[tree[n * 2 + 1]/*.Dad*/ * 2 + 1]/*.Len*/ + 1;
	    if (bits > max_length) {
	      bits = max_length;
	      overflow++;
	    }
	    tree[n * 2 + 1]/*.Len*/ = bits;
	    /* We overwrite tree[n].Dad which is no longer needed */
	
	    if (n > max_code) { continue; } /* not a leaf node */
	
	    s.bl_count[bits]++;
	    xbits = 0;
	    if (n >= base) {
	      xbits = extra[n - base];
	    }
	    f = tree[n * 2]/*.Freq*/;
	    s.opt_len += f * (bits + xbits);
	    if (has_stree) {
	      s.static_len += f * (stree[n * 2 + 1]/*.Len*/ + xbits);
	    }
	  }
	  if (overflow === 0) { return; }
	
	  // Trace((stderr,"\nbit length overflow\n"));
	  /* This happens for example on obj2 and pic of the Calgary corpus */
	
	  /* Find the first bit length which could increase: */
	  do {
	    bits = max_length - 1;
	    while (s.bl_count[bits] === 0) { bits--; }
	    s.bl_count[bits]--;      /* move one leaf down the tree */
	    s.bl_count[bits + 1] += 2; /* move one overflow item as its brother */
	    s.bl_count[max_length]--;
	    /* The brother of the overflow item also moves one step up,
	     * but this does not affect bl_count[max_length]
	     */
	    overflow -= 2;
	  } while (overflow > 0);
	
	  /* Now recompute all bit lengths, scanning in increasing frequency.
	   * h is still equal to HEAP_SIZE. (It is simpler to reconstruct all
	   * lengths instead of fixing only the wrong ones. This idea is taken
	   * from 'ar' written by Haruhiko Okumura.)
	   */
	  for (bits = max_length; bits !== 0; bits--) {
	    n = s.bl_count[bits];
	    while (n !== 0) {
	      m = s.heap[--h];
	      if (m > max_code) { continue; }
	      if (tree[m * 2 + 1]/*.Len*/ !== bits) {
	        // Trace((stderr,"code %d bits %d->%d\n", m, tree[m].Len, bits));
	        s.opt_len += (bits - tree[m * 2 + 1]/*.Len*/) * tree[m * 2]/*.Freq*/;
	        tree[m * 2 + 1]/*.Len*/ = bits;
	      }
	      n--;
	    }
	  }
	}
	
	
	/* ===========================================================================
	 * Generate the codes for a given tree and bit counts (which need not be
	 * optimal).
	 * IN assertion: the array bl_count contains the bit length statistics for
	 * the given tree and the field len is set for all tree elements.
	 * OUT assertion: the field code is set for all tree elements of non
	 *     zero code length.
	 */
	function gen_codes(tree, max_code, bl_count)
	//    ct_data *tree;             /* the tree to decorate */
	//    int max_code;              /* largest code with non zero frequency */
	//    ushf *bl_count;            /* number of codes at each bit length */
	{
	  var next_code = new Array(MAX_BITS + 1); /* next code value for each bit length */
	  var code = 0;              /* running code value */
	  var bits;                  /* bit index */
	  var n;                     /* code index */
	
	  /* The distribution counts are first used to generate the code values
	   * without bit reversal.
	   */
	  for (bits = 1; bits <= MAX_BITS; bits++) {
	    next_code[bits] = code = (code + bl_count[bits - 1]) << 1;
	  }
	  /* Check that the bit counts in bl_count are consistent. The last code
	   * must be all ones.
	   */
	  //Assert (code + bl_count[MAX_BITS]-1 == (1<<MAX_BITS)-1,
	  //        "inconsistent bit counts");
	  //Tracev((stderr,"\ngen_codes: max_code %d ", max_code));
	
	  for (n = 0;  n <= max_code; n++) {
	    var len = tree[n * 2 + 1]/*.Len*/;
	    if (len === 0) { continue; }
	    /* Now reverse the bits */
	    tree[n * 2]/*.Code*/ = bi_reverse(next_code[len]++, len);
	
	    //Tracecv(tree != static_ltree, (stderr,"\nn %3d %c l %2d c %4x (%x) ",
	    //     n, (isgraph(n) ? n : ' '), len, tree[n].Code, next_code[len]-1));
	  }
	}
	
	
	/* ===========================================================================
	 * Initialize the various 'constant' tables.
	 */
	function tr_static_init() {
	  var n;        /* iterates over tree elements */
	  var bits;     /* bit counter */
	  var length;   /* length value */
	  var code;     /* code value */
	  var dist;     /* distance index */
	  var bl_count = new Array(MAX_BITS + 1);
	  /* number of codes at each bit length for an optimal tree */
	
	  // do check in _tr_init()
	  //if (static_init_done) return;
	
	  /* For some embedded targets, global variables are not initialized: */
	/*#ifdef NO_INIT_GLOBAL_POINTERS
	  static_l_desc.static_tree = static_ltree;
	  static_l_desc.extra_bits = extra_lbits;
	  static_d_desc.static_tree = static_dtree;
	  static_d_desc.extra_bits = extra_dbits;
	  static_bl_desc.extra_bits = extra_blbits;
	#endif*/
	
	  /* Initialize the mapping length (0..255) -> length code (0..28) */
	  length = 0;
	  for (code = 0; code < LENGTH_CODES - 1; code++) {
	    base_length[code] = length;
	    for (n = 0; n < (1 << extra_lbits[code]); n++) {
	      _length_code[length++] = code;
	    }
	  }
	  //Assert (length == 256, "tr_static_init: length != 256");
	  /* Note that the length 255 (match length 258) can be represented
	   * in two different ways: code 284 + 5 bits or code 285, so we
	   * overwrite length_code[255] to use the best encoding:
	   */
	  _length_code[length - 1] = code;
	
	  /* Initialize the mapping dist (0..32K) -> dist code (0..29) */
	  dist = 0;
	  for (code = 0; code < 16; code++) {
	    base_dist[code] = dist;
	    for (n = 0; n < (1 << extra_dbits[code]); n++) {
	      _dist_code[dist++] = code;
	    }
	  }
	  //Assert (dist == 256, "tr_static_init: dist != 256");
	  dist >>= 7; /* from now on, all distances are divided by 128 */
	  for (; code < D_CODES; code++) {
	    base_dist[code] = dist << 7;
	    for (n = 0; n < (1 << (extra_dbits[code] - 7)); n++) {
	      _dist_code[256 + dist++] = code;
	    }
	  }
	  //Assert (dist == 256, "tr_static_init: 256+dist != 512");
	
	  /* Construct the codes of the static literal tree */
	  for (bits = 0; bits <= MAX_BITS; bits++) {
	    bl_count[bits] = 0;
	  }
	
	  n = 0;
	  while (n <= 143) {
	    static_ltree[n * 2 + 1]/*.Len*/ = 8;
	    n++;
	    bl_count[8]++;
	  }
	  while (n <= 255) {
	    static_ltree[n * 2 + 1]/*.Len*/ = 9;
	    n++;
	    bl_count[9]++;
	  }
	  while (n <= 279) {
	    static_ltree[n * 2 + 1]/*.Len*/ = 7;
	    n++;
	    bl_count[7]++;
	  }
	  while (n <= 287) {
	    static_ltree[n * 2 + 1]/*.Len*/ = 8;
	    n++;
	    bl_count[8]++;
	  }
	  /* Codes 286 and 287 do not exist, but we must include them in the
	   * tree construction to get a canonical Huffman tree (longest code
	   * all ones)
	   */
	  gen_codes(static_ltree, L_CODES + 1, bl_count);
	
	  /* The static distance tree is trivial: */
	  for (n = 0; n < D_CODES; n++) {
	    static_dtree[n * 2 + 1]/*.Len*/ = 5;
	    static_dtree[n * 2]/*.Code*/ = bi_reverse(n, 5);
	  }
	
	  // Now data ready and we can init static trees
	  static_l_desc = new StaticTreeDesc(static_ltree, extra_lbits, LITERALS + 1, L_CODES, MAX_BITS);
	  static_d_desc = new StaticTreeDesc(static_dtree, extra_dbits, 0,          D_CODES, MAX_BITS);
	  static_bl_desc = new StaticTreeDesc(new Array(0), extra_blbits, 0,         BL_CODES, MAX_BL_BITS);
	
	  //static_init_done = true;
	}
	
	
	/* ===========================================================================
	 * Initialize a new block.
	 */
	function init_block(s) {
	  var n; /* iterates over tree elements */
	
	  /* Initialize the trees. */
	  for (n = 0; n < L_CODES;  n++) { s.dyn_ltree[n * 2]/*.Freq*/ = 0; }
	  for (n = 0; n < D_CODES;  n++) { s.dyn_dtree[n * 2]/*.Freq*/ = 0; }
	  for (n = 0; n < BL_CODES; n++) { s.bl_tree[n * 2]/*.Freq*/ = 0; }
	
	  s.dyn_ltree[END_BLOCK * 2]/*.Freq*/ = 1;
	  s.opt_len = s.static_len = 0;
	  s.last_lit = s.matches = 0;
	}
	
	
	/* ===========================================================================
	 * Flush the bit buffer and align the output on a byte boundary
	 */
	function bi_windup(s)
	{
	  if (s.bi_valid > 8) {
	    put_short(s, s.bi_buf);
	  } else if (s.bi_valid > 0) {
	    //put_byte(s, (Byte)s->bi_buf);
	    s.pending_buf[s.pending++] = s.bi_buf;
	  }
	  s.bi_buf = 0;
	  s.bi_valid = 0;
	}
	
	/* ===========================================================================
	 * Copy a stored block, storing first the length and its
	 * one's complement if requested.
	 */
	function copy_block(s, buf, len, header)
	//DeflateState *s;
	//charf    *buf;    /* the input data */
	//unsigned len;     /* its length */
	//int      header;  /* true if block header must be written */
	{
	  bi_windup(s);        /* align on byte boundary */
	
	  if (header) {
	    put_short(s, len);
	    put_short(s, ~len);
	  }
	//  while (len--) {
	//    put_byte(s, *buf++);
	//  }
	  utils.arraySet(s.pending_buf, s.window, buf, len, s.pending);
	  s.pending += len;
	}
	
	/* ===========================================================================
	 * Compares to subtrees, using the tree depth as tie breaker when
	 * the subtrees have equal frequency. This minimizes the worst case length.
	 */
	function smaller(tree, n, m, depth) {
	  var _n2 = n * 2;
	  var _m2 = m * 2;
	  return (tree[_n2]/*.Freq*/ < tree[_m2]/*.Freq*/ ||
	         (tree[_n2]/*.Freq*/ === tree[_m2]/*.Freq*/ && depth[n] <= depth[m]));
	}
	
	/* ===========================================================================
	 * Restore the heap property by moving down the tree starting at node k,
	 * exchanging a node with the smallest of its two sons if necessary, stopping
	 * when the heap property is re-established (each father smaller than its
	 * two sons).
	 */
	function pqdownheap(s, tree, k)
	//    deflate_state *s;
	//    ct_data *tree;  /* the tree to restore */
	//    int k;               /* node to move down */
	{
	  var v = s.heap[k];
	  var j = k << 1;  /* left son of k */
	  while (j <= s.heap_len) {
	    /* Set j to the smallest of the two sons: */
	    if (j < s.heap_len &&
	      smaller(tree, s.heap[j + 1], s.heap[j], s.depth)) {
	      j++;
	    }
	    /* Exit if v is smaller than both sons */
	    if (smaller(tree, v, s.heap[j], s.depth)) { break; }
	
	    /* Exchange v with the smallest son */
	    s.heap[k] = s.heap[j];
	    k = j;
	
	    /* And continue down the tree, setting j to the left son of k */
	    j <<= 1;
	  }
	  s.heap[k] = v;
	}
	
	
	// inlined manually
	// var SMALLEST = 1;
	
	/* ===========================================================================
	 * Send the block data compressed using the given Huffman trees
	 */
	function compress_block(s, ltree, dtree)
	//    deflate_state *s;
	//    const ct_data *ltree; /* literal tree */
	//    const ct_data *dtree; /* distance tree */
	{
	  var dist;           /* distance of matched string */
	  var lc;             /* match length or unmatched char (if dist == 0) */
	  var lx = 0;         /* running index in l_buf */
	  var code;           /* the code to send */
	  var extra;          /* number of extra bits to send */
	
	  if (s.last_lit !== 0) {
	    do {
	      dist = (s.pending_buf[s.d_buf + lx * 2] << 8) | (s.pending_buf[s.d_buf + lx * 2 + 1]);
	      lc = s.pending_buf[s.l_buf + lx];
	      lx++;
	
	      if (dist === 0) {
	        send_code(s, lc, ltree); /* send a literal byte */
	        //Tracecv(isgraph(lc), (stderr," '%c' ", lc));
	      } else {
	        /* Here, lc is the match length - MIN_MATCH */
	        code = _length_code[lc];
	        send_code(s, code + LITERALS + 1, ltree); /* send the length code */
	        extra = extra_lbits[code];
	        if (extra !== 0) {
	          lc -= base_length[code];
	          send_bits(s, lc, extra);       /* send the extra length bits */
	        }
	        dist--; /* dist is now the match distance - 1 */
	        code = d_code(dist);
	        //Assert (code < D_CODES, "bad d_code");
	
	        send_code(s, code, dtree);       /* send the distance code */
	        extra = extra_dbits[code];
	        if (extra !== 0) {
	          dist -= base_dist[code];
	          send_bits(s, dist, extra);   /* send the extra distance bits */
	        }
	      } /* literal or match pair ? */
	
	      /* Check that the overlay between pending_buf and d_buf+l_buf is ok: */
	      //Assert((uInt)(s->pending) < s->lit_bufsize + 2*lx,
	      //       "pendingBuf overflow");
	
	    } while (lx < s.last_lit);
	  }
	
	  send_code(s, END_BLOCK, ltree);
	}
	
	
	/* ===========================================================================
	 * Construct one Huffman tree and assigns the code bit strings and lengths.
	 * Update the total bit length for the current block.
	 * IN assertion: the field freq is set for all tree elements.
	 * OUT assertions: the fields len and code are set to the optimal bit length
	 *     and corresponding code. The length opt_len is updated; static_len is
	 *     also updated if stree is not null. The field max_code is set.
	 */
	function build_tree(s, desc)
	//    deflate_state *s;
	//    tree_desc *desc; /* the tree descriptor */
	{
	  var tree     = desc.dyn_tree;
	  var stree    = desc.stat_desc.static_tree;
	  var has_stree = desc.stat_desc.has_stree;
	  var elems    = desc.stat_desc.elems;
	  var n, m;          /* iterate over heap elements */
	  var max_code = -1; /* largest code with non zero frequency */
	  var node;          /* new node being created */
	
	  /* Construct the initial heap, with least frequent element in
	   * heap[SMALLEST]. The sons of heap[n] are heap[2*n] and heap[2*n+1].
	   * heap[0] is not used.
	   */
	  s.heap_len = 0;
	  s.heap_max = HEAP_SIZE;
	
	  for (n = 0; n < elems; n++) {
	    if (tree[n * 2]/*.Freq*/ !== 0) {
	      s.heap[++s.heap_len] = max_code = n;
	      s.depth[n] = 0;
	
	    } else {
	      tree[n * 2 + 1]/*.Len*/ = 0;
	    }
	  }
	
	  /* The pkzip format requires that at least one distance code exists,
	   * and that at least one bit should be sent even if there is only one
	   * possible code. So to avoid special checks later on we force at least
	   * two codes of non zero frequency.
	   */
	  while (s.heap_len < 2) {
	    node = s.heap[++s.heap_len] = (max_code < 2 ? ++max_code : 0);
	    tree[node * 2]/*.Freq*/ = 1;
	    s.depth[node] = 0;
	    s.opt_len--;
	
	    if (has_stree) {
	      s.static_len -= stree[node * 2 + 1]/*.Len*/;
	    }
	    /* node is 0 or 1 so it does not have extra bits */
	  }
	  desc.max_code = max_code;
	
	  /* The elements heap[heap_len/2+1 .. heap_len] are leaves of the tree,
	   * establish sub-heaps of increasing lengths:
	   */
	  for (n = (s.heap_len >> 1/*int /2*/); n >= 1; n--) { pqdownheap(s, tree, n); }
	
	  /* Construct the Huffman tree by repeatedly combining the least two
	   * frequent nodes.
	   */
	  node = elems;              /* next internal node of the tree */
	  do {
	    //pqremove(s, tree, n);  /* n = node of least frequency */
	    /*** pqremove ***/
	    n = s.heap[1/*SMALLEST*/];
	    s.heap[1/*SMALLEST*/] = s.heap[s.heap_len--];
	    pqdownheap(s, tree, 1/*SMALLEST*/);
	    /***/
	
	    m = s.heap[1/*SMALLEST*/]; /* m = node of next least frequency */
	
	    s.heap[--s.heap_max] = n; /* keep the nodes sorted by frequency */
	    s.heap[--s.heap_max] = m;
	
	    /* Create a new node father of n and m */
	    tree[node * 2]/*.Freq*/ = tree[n * 2]/*.Freq*/ + tree[m * 2]/*.Freq*/;
	    s.depth[node] = (s.depth[n] >= s.depth[m] ? s.depth[n] : s.depth[m]) + 1;
	    tree[n * 2 + 1]/*.Dad*/ = tree[m * 2 + 1]/*.Dad*/ = node;
	
	    /* and insert the new node in the heap */
	    s.heap[1/*SMALLEST*/] = node++;
	    pqdownheap(s, tree, 1/*SMALLEST*/);
	
	  } while (s.heap_len >= 2);
	
	  s.heap[--s.heap_max] = s.heap[1/*SMALLEST*/];
	
	  /* At this point, the fields freq and dad are set. We can now
	   * generate the bit lengths.
	   */
	  gen_bitlen(s, desc);
	
	  /* The field len is now set, we can generate the bit codes */
	  gen_codes(tree, max_code, s.bl_count);
	}
	
	
	/* ===========================================================================
	 * Scan a literal or distance tree to determine the frequencies of the codes
	 * in the bit length tree.
	 */
	function scan_tree(s, tree, max_code)
	//    deflate_state *s;
	//    ct_data *tree;   /* the tree to be scanned */
	//    int max_code;    /* and its largest code of non zero frequency */
	{
	  var n;                     /* iterates over all tree elements */
	  var prevlen = -1;          /* last emitted length */
	  var curlen;                /* length of current code */
	
	  var nextlen = tree[0 * 2 + 1]/*.Len*/; /* length of next code */
	
	  var count = 0;             /* repeat count of the current code */
	  var max_count = 7;         /* max repeat count */
	  var min_count = 4;         /* min repeat count */
	
	  if (nextlen === 0) {
	    max_count = 138;
	    min_count = 3;
	  }
	  tree[(max_code + 1) * 2 + 1]/*.Len*/ = 0xffff; /* guard */
	
	  for (n = 0; n <= max_code; n++) {
	    curlen = nextlen;
	    nextlen = tree[(n + 1) * 2 + 1]/*.Len*/;
	
	    if (++count < max_count && curlen === nextlen) {
	      continue;
	
	    } else if (count < min_count) {
	      s.bl_tree[curlen * 2]/*.Freq*/ += count;
	
	    } else if (curlen !== 0) {
	
	      if (curlen !== prevlen) { s.bl_tree[curlen * 2]/*.Freq*/++; }
	      s.bl_tree[REP_3_6 * 2]/*.Freq*/++;
	
	    } else if (count <= 10) {
	      s.bl_tree[REPZ_3_10 * 2]/*.Freq*/++;
	
	    } else {
	      s.bl_tree[REPZ_11_138 * 2]/*.Freq*/++;
	    }
	
	    count = 0;
	    prevlen = curlen;
	
	    if (nextlen === 0) {
	      max_count = 138;
	      min_count = 3;
	
	    } else if (curlen === nextlen) {
	      max_count = 6;
	      min_count = 3;
	
	    } else {
	      max_count = 7;
	      min_count = 4;
	    }
	  }
	}
	
	
	/* ===========================================================================
	 * Send a literal or distance tree in compressed form, using the codes in
	 * bl_tree.
	 */
	function send_tree(s, tree, max_code)
	//    deflate_state *s;
	//    ct_data *tree; /* the tree to be scanned */
	//    int max_code;       /* and its largest code of non zero frequency */
	{
	  var n;                     /* iterates over all tree elements */
	  var prevlen = -1;          /* last emitted length */
	  var curlen;                /* length of current code */
	
	  var nextlen = tree[0 * 2 + 1]/*.Len*/; /* length of next code */
	
	  var count = 0;             /* repeat count of the current code */
	  var max_count = 7;         /* max repeat count */
	  var min_count = 4;         /* min repeat count */
	
	  /* tree[max_code+1].Len = -1; */  /* guard already set */
	  if (nextlen === 0) {
	    max_count = 138;
	    min_count = 3;
	  }
	
	  for (n = 0; n <= max_code; n++) {
	    curlen = nextlen;
	    nextlen = tree[(n + 1) * 2 + 1]/*.Len*/;
	
	    if (++count < max_count && curlen === nextlen) {
	      continue;
	
	    } else if (count < min_count) {
	      do { send_code(s, curlen, s.bl_tree); } while (--count !== 0);
	
	    } else if (curlen !== 0) {
	      if (curlen !== prevlen) {
	        send_code(s, curlen, s.bl_tree);
	        count--;
	      }
	      //Assert(count >= 3 && count <= 6, " 3_6?");
	      send_code(s, REP_3_6, s.bl_tree);
	      send_bits(s, count - 3, 2);
	
	    } else if (count <= 10) {
	      send_code(s, REPZ_3_10, s.bl_tree);
	      send_bits(s, count - 3, 3);
	
	    } else {
	      send_code(s, REPZ_11_138, s.bl_tree);
	      send_bits(s, count - 11, 7);
	    }
	
	    count = 0;
	    prevlen = curlen;
	    if (nextlen === 0) {
	      max_count = 138;
	      min_count = 3;
	
	    } else if (curlen === nextlen) {
	      max_count = 6;
	      min_count = 3;
	
	    } else {
	      max_count = 7;
	      min_count = 4;
	    }
	  }
	}
	
	
	/* ===========================================================================
	 * Construct the Huffman tree for the bit lengths and return the index in
	 * bl_order of the last bit length code to send.
	 */
	function build_bl_tree(s) {
	  var max_blindex;  /* index of last bit length code of non zero freq */
	
	  /* Determine the bit length frequencies for literal and distance trees */
	  scan_tree(s, s.dyn_ltree, s.l_desc.max_code);
	  scan_tree(s, s.dyn_dtree, s.d_desc.max_code);
	
	  /* Build the bit length tree: */
	  build_tree(s, s.bl_desc);
	  /* opt_len now includes the length of the tree representations, except
	   * the lengths of the bit lengths codes and the 5+5+4 bits for the counts.
	   */
	
	  /* Determine the number of bit length codes to send. The pkzip format
	   * requires that at least 4 bit length codes be sent. (appnote.txt says
	   * 3 but the actual value used is 4.)
	   */
	  for (max_blindex = BL_CODES - 1; max_blindex >= 3; max_blindex--) {
	    if (s.bl_tree[bl_order[max_blindex] * 2 + 1]/*.Len*/ !== 0) {
	      break;
	    }
	  }
	  /* Update opt_len to include the bit length tree and counts */
	  s.opt_len += 3 * (max_blindex + 1) + 5 + 5 + 4;
	  //Tracev((stderr, "\ndyn trees: dyn %ld, stat %ld",
	  //        s->opt_len, s->static_len));
	
	  return max_blindex;
	}
	
	
	/* ===========================================================================
	 * Send the header for a block using dynamic Huffman trees: the counts, the
	 * lengths of the bit length codes, the literal tree and the distance tree.
	 * IN assertion: lcodes >= 257, dcodes >= 1, blcodes >= 4.
	 */
	function send_all_trees(s, lcodes, dcodes, blcodes)
	//    deflate_state *s;
	//    int lcodes, dcodes, blcodes; /* number of codes for each tree */
	{
	  var rank;                    /* index in bl_order */
	
	  //Assert (lcodes >= 257 && dcodes >= 1 && blcodes >= 4, "not enough codes");
	  //Assert (lcodes <= L_CODES && dcodes <= D_CODES && blcodes <= BL_CODES,
	  //        "too many codes");
	  //Tracev((stderr, "\nbl counts: "));
	  send_bits(s, lcodes - 257, 5); /* not +255 as stated in appnote.txt */
	  send_bits(s, dcodes - 1,   5);
	  send_bits(s, blcodes - 4,  4); /* not -3 as stated in appnote.txt */
	  for (rank = 0; rank < blcodes; rank++) {
	    //Tracev((stderr, "\nbl code %2d ", bl_order[rank]));
	    send_bits(s, s.bl_tree[bl_order[rank] * 2 + 1]/*.Len*/, 3);
	  }
	  //Tracev((stderr, "\nbl tree: sent %ld", s->bits_sent));
	
	  send_tree(s, s.dyn_ltree, lcodes - 1); /* literal tree */
	  //Tracev((stderr, "\nlit tree: sent %ld", s->bits_sent));
	
	  send_tree(s, s.dyn_dtree, dcodes - 1); /* distance tree */
	  //Tracev((stderr, "\ndist tree: sent %ld", s->bits_sent));
	}
	
	
	/* ===========================================================================
	 * Check if the data type is TEXT or BINARY, using the following algorithm:
	 * - TEXT if the two conditions below are satisfied:
	 *    a) There are no non-portable control characters belonging to the
	 *       "black list" (0..6, 14..25, 28..31).
	 *    b) There is at least one printable character belonging to the
	 *       "white list" (9 {TAB}, 10 {LF}, 13 {CR}, 32..255).
	 * - BINARY otherwise.
	 * - The following partially-portable control characters form a
	 *   "gray list" that is ignored in this detection algorithm:
	 *   (7 {BEL}, 8 {BS}, 11 {VT}, 12 {FF}, 26 {SUB}, 27 {ESC}).
	 * IN assertion: the fields Freq of dyn_ltree are set.
	 */
	function detect_data_type(s) {
	  /* black_mask is the bit mask of black-listed bytes
	   * set bits 0..6, 14..25, and 28..31
	   * 0xf3ffc07f = binary 11110011111111111100000001111111
	   */
	  var black_mask = 0xf3ffc07f;
	  var n;
	
	  /* Check for non-textual ("black-listed") bytes. */
	  for (n = 0; n <= 31; n++, black_mask >>>= 1) {
	    if ((black_mask & 1) && (s.dyn_ltree[n * 2]/*.Freq*/ !== 0)) {
	      return Z_BINARY;
	    }
	  }
	
	  /* Check for textual ("white-listed") bytes. */
	  if (s.dyn_ltree[9 * 2]/*.Freq*/ !== 0 || s.dyn_ltree[10 * 2]/*.Freq*/ !== 0 ||
	      s.dyn_ltree[13 * 2]/*.Freq*/ !== 0) {
	    return Z_TEXT;
	  }
	  for (n = 32; n < LITERALS; n++) {
	    if (s.dyn_ltree[n * 2]/*.Freq*/ !== 0) {
	      return Z_TEXT;
	    }
	  }
	
	  /* There are no "black-listed" or "white-listed" bytes:
	   * this stream either is empty or has tolerated ("gray-listed") bytes only.
	   */
	  return Z_BINARY;
	}
	
	
	var static_init_done = false;
	
	/* ===========================================================================
	 * Initialize the tree data structures for a new zlib stream.
	 */
	function _tr_init(s)
	{
	
	  if (!static_init_done) {
	    tr_static_init();
	    static_init_done = true;
	  }
	
	  s.l_desc  = new TreeDesc(s.dyn_ltree, static_l_desc);
	  s.d_desc  = new TreeDesc(s.dyn_dtree, static_d_desc);
	  s.bl_desc = new TreeDesc(s.bl_tree, static_bl_desc);
	
	  s.bi_buf = 0;
	  s.bi_valid = 0;
	
	  /* Initialize the first block of the first file: */
	  init_block(s);
	}
	
	
	/* ===========================================================================
	 * Send a stored block
	 */
	function _tr_stored_block(s, buf, stored_len, last)
	//DeflateState *s;
	//charf *buf;       /* input block */
	//ulg stored_len;   /* length of input block */
	//int last;         /* one if this is the last block for a file */
	{
	  send_bits(s, (STORED_BLOCK << 1) + (last ? 1 : 0), 3);    /* send block type */
	  copy_block(s, buf, stored_len, true); /* with header */
	}
	
	
	/* ===========================================================================
	 * Send one empty static block to give enough lookahead for inflate.
	 * This takes 10 bits, of which 7 may remain in the bit buffer.
	 */
	function _tr_align(s) {
	  send_bits(s, STATIC_TREES << 1, 3);
	  send_code(s, END_BLOCK, static_ltree);
	  bi_flush(s);
	}
	
	
	/* ===========================================================================
	 * Determine the best encoding for the current block: dynamic trees, static
	 * trees or store, and output the encoded block to the zip file.
	 */
	function _tr_flush_block(s, buf, stored_len, last)
	//DeflateState *s;
	//charf *buf;       /* input block, or NULL if too old */
	//ulg stored_len;   /* length of input block */
	//int last;         /* one if this is the last block for a file */
	{
	  var opt_lenb, static_lenb;  /* opt_len and static_len in bytes */
	  var max_blindex = 0;        /* index of last bit length code of non zero freq */
	
	  /* Build the Huffman trees unless a stored block is forced */
	  if (s.level > 0) {
	
	    /* Check if the file is binary or text */
	    if (s.strm.data_type === Z_UNKNOWN) {
	      s.strm.data_type = detect_data_type(s);
	    }
	
	    /* Construct the literal and distance trees */
	    build_tree(s, s.l_desc);
	    // Tracev((stderr, "\nlit data: dyn %ld, stat %ld", s->opt_len,
	    //        s->static_len));
	
	    build_tree(s, s.d_desc);
	    // Tracev((stderr, "\ndist data: dyn %ld, stat %ld", s->opt_len,
	    //        s->static_len));
	    /* At this point, opt_len and static_len are the total bit lengths of
	     * the compressed block data, excluding the tree representations.
	     */
	
	    /* Build the bit length tree for the above two trees, and get the index
	     * in bl_order of the last bit length code to send.
	     */
	    max_blindex = build_bl_tree(s);
	
	    /* Determine the best encoding. Compute the block lengths in bytes. */
	    opt_lenb = (s.opt_len + 3 + 7) >>> 3;
	    static_lenb = (s.static_len + 3 + 7) >>> 3;
	
	    // Tracev((stderr, "\nopt %lu(%lu) stat %lu(%lu) stored %lu lit %u ",
	    //        opt_lenb, s->opt_len, static_lenb, s->static_len, stored_len,
	    //        s->last_lit));
	
	    if (static_lenb <= opt_lenb) { opt_lenb = static_lenb; }
	
	  } else {
	    // Assert(buf != (char*)0, "lost buf");
	    opt_lenb = static_lenb = stored_len + 5; /* force a stored block */
	  }
	
	  if ((stored_len + 4 <= opt_lenb) && (buf !== -1)) {
	    /* 4: two words for the lengths */
	
	    /* The test buf != NULL is only necessary if LIT_BUFSIZE > WSIZE.
	     * Otherwise we can't have processed more than WSIZE input bytes since
	     * the last block flush, because compression would have been
	     * successful. If LIT_BUFSIZE <= WSIZE, it is never too late to
	     * transform a block into a stored block.
	     */
	    _tr_stored_block(s, buf, stored_len, last);
	
	  } else if (s.strategy === Z_FIXED || static_lenb === opt_lenb) {
	
	    send_bits(s, (STATIC_TREES << 1) + (last ? 1 : 0), 3);
	    compress_block(s, static_ltree, static_dtree);
	
	  } else {
	    send_bits(s, (DYN_TREES << 1) + (last ? 1 : 0), 3);
	    send_all_trees(s, s.l_desc.max_code + 1, s.d_desc.max_code + 1, max_blindex + 1);
	    compress_block(s, s.dyn_ltree, s.dyn_dtree);
	  }
	  // Assert (s->compressed_len == s->bits_sent, "bad compressed size");
	  /* The above check is made mod 2^32, for files larger than 512 MB
	   * and uLong implemented on 32 bits.
	   */
	  init_block(s);
	
	  if (last) {
	    bi_windup(s);
	  }
	  // Tracev((stderr,"\ncomprlen %lu(%lu) ", s->compressed_len>>3,
	  //       s->compressed_len-7*last));
	}
	
	/* ===========================================================================
	 * Save the match info and tally the frequency counts. Return true if
	 * the current block must be flushed.
	 */
	function _tr_tally(s, dist, lc)
	//    deflate_state *s;
	//    unsigned dist;  /* distance of matched string */
	//    unsigned lc;    /* match length-MIN_MATCH or unmatched char (if dist==0) */
	{
	  //var out_length, in_length, dcode;
	
	  s.pending_buf[s.d_buf + s.last_lit * 2]     = (dist >>> 8) & 0xff;
	  s.pending_buf[s.d_buf + s.last_lit * 2 + 1] = dist & 0xff;
	
	  s.pending_buf[s.l_buf + s.last_lit] = lc & 0xff;
	  s.last_lit++;
	
	  if (dist === 0) {
	    /* lc is the unmatched char */
	    s.dyn_ltree[lc * 2]/*.Freq*/++;
	  } else {
	    s.matches++;
	    /* Here, lc is the match length - MIN_MATCH */
	    dist--;             /* dist = match distance - 1 */
	    //Assert((ush)dist < (ush)MAX_DIST(s) &&
	    //       (ush)lc <= (ush)(MAX_MATCH-MIN_MATCH) &&
	    //       (ush)d_code(dist) < (ush)D_CODES,  "_tr_tally: bad match");
	
	    s.dyn_ltree[(_length_code[lc] + LITERALS + 1) * 2]/*.Freq*/++;
	    s.dyn_dtree[d_code(dist) * 2]/*.Freq*/++;
	  }
	
	// (!) This block is disabled in zlib defailts,
	// don't enable it for binary compatibility
	
	//#ifdef TRUNCATE_BLOCK
	//  /* Try to guess if it is profitable to stop the current block here */
	//  if ((s.last_lit & 0x1fff) === 0 && s.level > 2) {
	//    /* Compute an upper bound for the compressed length */
	//    out_length = s.last_lit*8;
	//    in_length = s.strstart - s.block_start;
	//
	//    for (dcode = 0; dcode < D_CODES; dcode++) {
	//      out_length += s.dyn_dtree[dcode*2]/*.Freq*/ * (5 + extra_dbits[dcode]);
	//    }
	//    out_length >>>= 3;
	//    //Tracev((stderr,"\nlast_lit %u, in %ld, out ~%ld(%ld%%) ",
	//    //       s->last_lit, in_length, out_length,
	//    //       100L - out_length*100L/in_length));
	//    if (s.matches < (s.last_lit>>1)/*int /2*/ && out_length < (in_length>>1)/*int /2*/) {
	//      return true;
	//    }
	//  }
	//#endif
	
	  return (s.last_lit === s.lit_bufsize - 1);
	  /* We avoid equality with lit_bufsize because of wraparound at 64K
	   * on 16 bit machines and because stored blocks are restricted to
	   * 64K-1 bytes.
	   */
	}
	
	exports._tr_init  = _tr_init;
	exports._tr_stored_block = _tr_stored_block;
	exports._tr_flush_block  = _tr_flush_block;
	exports._tr_tally = _tr_tally;
	exports._tr_align = _tr_align;


/***/ },
/* 37 */
/***/ function(module, exports) {

	'use strict';
	
	// Note: adler32 takes 12% for level 0 and 2% for level 6.
	// It doesn't worth to make additional optimizationa as in original.
	// Small size is preferable.
	
	function adler32(adler, buf, len, pos) {
	  var s1 = (adler & 0xffff) |0,
	      s2 = ((adler >>> 16) & 0xffff) |0,
	      n = 0;
	
	  while (len !== 0) {
	    // Set limit ~ twice less than 5552, to keep
	    // s2 in 31-bits, because we force signed ints.
	    // in other case %= will fail.
	    n = len > 2000 ? 2000 : len;
	    len -= n;
	
	    do {
	      s1 = (s1 + buf[pos++]) |0;
	      s2 = (s2 + s1) |0;
	    } while (--n);
	
	    s1 %= 65521;
	    s2 %= 65521;
	  }
	
	  return (s1 | (s2 << 16)) |0;
	}
	
	
	module.exports = adler32;


/***/ },
/* 38 */
/***/ function(module, exports) {

	'use strict';
	
	// Note: we can't get significant speed boost here.
	// So write code to minimize size - no pregenerated tables
	// and array tools dependencies.
	
	
	// Use ordinary array, since untyped makes no boost here
	function makeTable() {
	  var c, table = [];
	
	  for (var n = 0; n < 256; n++) {
	    c = n;
	    for (var k = 0; k < 8; k++) {
	      c = ((c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1));
	    }
	    table[n] = c;
	  }
	
	  return table;
	}
	
	// Create table on load. Just 255 signed longs. Not a problem.
	var crcTable = makeTable();
	
	
	function crc32(crc, buf, len, pos) {
	  var t = crcTable,
	      end = pos + len;
	
	  crc ^= -1;
	
	  for (var i = pos; i < end; i++) {
	    crc = (crc >>> 8) ^ t[(crc ^ buf[i]) & 0xFF];
	  }
	
	  return (crc ^ (-1)); // >>> 0;
	}
	
	
	module.exports = crc32;


/***/ },
/* 39 */
/***/ function(module, exports) {

	'use strict';
	
	module.exports = {
	  2:      'need dictionary',     /* Z_NEED_DICT       2  */
	  1:      'stream end',          /* Z_STREAM_END      1  */
	  0:      '',                    /* Z_OK              0  */
	  '-1':   'file error',          /* Z_ERRNO         (-1) */
	  '-2':   'stream error',        /* Z_STREAM_ERROR  (-2) */
	  '-3':   'data error',          /* Z_DATA_ERROR    (-3) */
	  '-4':   'insufficient memory', /* Z_MEM_ERROR     (-4) */
	  '-5':   'buffer error',        /* Z_BUF_ERROR     (-5) */
	  '-6':   'incompatible version' /* Z_VERSION_ERROR (-6) */
	};


/***/ },
/* 40 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	
	
	var utils         = __webpack_require__(35);
	var adler32       = __webpack_require__(37);
	var crc32         = __webpack_require__(38);
	var inflate_fast  = __webpack_require__(41);
	var inflate_table = __webpack_require__(42);
	
	var CODES = 0;
	var LENS = 1;
	var DISTS = 2;
	
	/* Public constants ==========================================================*/
	/* ===========================================================================*/
	
	
	/* Allowed flush values; see deflate() and inflate() below for details */
	//var Z_NO_FLUSH      = 0;
	//var Z_PARTIAL_FLUSH = 1;
	//var Z_SYNC_FLUSH    = 2;
	//var Z_FULL_FLUSH    = 3;
	var Z_FINISH        = 4;
	var Z_BLOCK         = 5;
	var Z_TREES         = 6;
	
	
	/* Return codes for the compression/decompression functions. Negative values
	 * are errors, positive values are used for special but normal events.
	 */
	var Z_OK            = 0;
	var Z_STREAM_END    = 1;
	var Z_NEED_DICT     = 2;
	//var Z_ERRNO         = -1;
	var Z_STREAM_ERROR  = -2;
	var Z_DATA_ERROR    = -3;
	var Z_MEM_ERROR     = -4;
	var Z_BUF_ERROR     = -5;
	//var Z_VERSION_ERROR = -6;
	
	/* The deflate compression method */
	var Z_DEFLATED  = 8;
	
	
	/* STATES ====================================================================*/
	/* ===========================================================================*/
	
	
	var    HEAD = 1;       /* i: waiting for magic header */
	var    FLAGS = 2;      /* i: waiting for method and flags (gzip) */
	var    TIME = 3;       /* i: waiting for modification time (gzip) */
	var    OS = 4;         /* i: waiting for extra flags and operating system (gzip) */
	var    EXLEN = 5;      /* i: waiting for extra length (gzip) */
	var    EXTRA = 6;      /* i: waiting for extra bytes (gzip) */
	var    NAME = 7;       /* i: waiting for end of file name (gzip) */
	var    COMMENT = 8;    /* i: waiting for end of comment (gzip) */
	var    HCRC = 9;       /* i: waiting for header crc (gzip) */
	var    DICTID = 10;    /* i: waiting for dictionary check value */
	var    DICT = 11;      /* waiting for inflateSetDictionary() call */
	var        TYPE = 12;      /* i: waiting for type bits, including last-flag bit */
	var        TYPEDO = 13;    /* i: same, but skip check to exit inflate on new block */
	var        STORED = 14;    /* i: waiting for stored size (length and complement) */
	var        COPY_ = 15;     /* i/o: same as COPY below, but only first time in */
	var        COPY = 16;      /* i/o: waiting for input or output to copy stored block */
	var        TABLE = 17;     /* i: waiting for dynamic block table lengths */
	var        LENLENS = 18;   /* i: waiting for code length code lengths */
	var        CODELENS = 19;  /* i: waiting for length/lit and distance code lengths */
	var            LEN_ = 20;      /* i: same as LEN below, but only first time in */
	var            LEN = 21;       /* i: waiting for length/lit/eob code */
	var            LENEXT = 22;    /* i: waiting for length extra bits */
	var            DIST = 23;      /* i: waiting for distance code */
	var            DISTEXT = 24;   /* i: waiting for distance extra bits */
	var            MATCH = 25;     /* o: waiting for output space to copy string */
	var            LIT = 26;       /* o: waiting for output space to write literal */
	var    CHECK = 27;     /* i: waiting for 32-bit check value */
	var    LENGTH = 28;    /* i: waiting for 32-bit length (gzip) */
	var    DONE = 29;      /* finished check, done -- remain here until reset */
	var    BAD = 30;       /* got a data error -- remain here until reset */
	var    MEM = 31;       /* got an inflate() memory error -- remain here until reset */
	var    SYNC = 32;      /* looking for synchronization bytes to restart inflate() */
	
	/* ===========================================================================*/
	
	
	
	var ENOUGH_LENS = 852;
	var ENOUGH_DISTS = 592;
	//var ENOUGH =  (ENOUGH_LENS+ENOUGH_DISTS);
	
	var MAX_WBITS = 15;
	/* 32K LZ77 window */
	var DEF_WBITS = MAX_WBITS;
	
	
	function zswap32(q) {
	  return  (((q >>> 24) & 0xff) +
	          ((q >>> 8) & 0xff00) +
	          ((q & 0xff00) << 8) +
	          ((q & 0xff) << 24));
	}
	
	
	function InflateState() {
	  this.mode = 0;             /* current inflate mode */
	  this.last = false;          /* true if processing last block */
	  this.wrap = 0;              /* bit 0 true for zlib, bit 1 true for gzip */
	  this.havedict = false;      /* true if dictionary provided */
	  this.flags = 0;             /* gzip header method and flags (0 if zlib) */
	  this.dmax = 0;              /* zlib header max distance (INFLATE_STRICT) */
	  this.check = 0;             /* protected copy of check value */
	  this.total = 0;             /* protected copy of output count */
	  // TODO: may be {}
	  this.head = null;           /* where to save gzip header information */
	
	  /* sliding window */
	  this.wbits = 0;             /* log base 2 of requested window size */
	  this.wsize = 0;             /* window size or zero if not using window */
	  this.whave = 0;             /* valid bytes in the window */
	  this.wnext = 0;             /* window write index */
	  this.window = null;         /* allocated sliding window, if needed */
	
	  /* bit accumulator */
	  this.hold = 0;              /* input bit accumulator */
	  this.bits = 0;              /* number of bits in "in" */
	
	  /* for string and stored block copying */
	  this.length = 0;            /* literal or length of data to copy */
	  this.offset = 0;            /* distance back to copy string from */
	
	  /* for table and code decoding */
	  this.extra = 0;             /* extra bits needed */
	
	  /* fixed and dynamic code tables */
	  this.lencode = null;          /* starting table for length/literal codes */
	  this.distcode = null;         /* starting table for distance codes */
	  this.lenbits = 0;           /* index bits for lencode */
	  this.distbits = 0;          /* index bits for distcode */
	
	  /* dynamic table building */
	  this.ncode = 0;             /* number of code length code lengths */
	  this.nlen = 0;              /* number of length code lengths */
	  this.ndist = 0;             /* number of distance code lengths */
	  this.have = 0;              /* number of code lengths in lens[] */
	  this.next = null;              /* next available space in codes[] */
	
	  this.lens = new utils.Buf16(320); /* temporary storage for code lengths */
	  this.work = new utils.Buf16(288); /* work area for code table building */
	
	  /*
	   because we don't have pointers in js, we use lencode and distcode directly
	   as buffers so we don't need codes
	  */
	  //this.codes = new utils.Buf32(ENOUGH);       /* space for code tables */
	  this.lendyn = null;              /* dynamic table for length/literal codes (JS specific) */
	  this.distdyn = null;             /* dynamic table for distance codes (JS specific) */
	  this.sane = 0;                   /* if false, allow invalid distance too far */
	  this.back = 0;                   /* bits back of last unprocessed length/lit */
	  this.was = 0;                    /* initial length of match */
	}
	
	function inflateResetKeep(strm) {
	  var state;
	
	  if (!strm || !strm.state) { return Z_STREAM_ERROR; }
	  state = strm.state;
	  strm.total_in = strm.total_out = state.total = 0;
	  strm.msg = ''; /*Z_NULL*/
	  if (state.wrap) {       /* to support ill-conceived Java test suite */
	    strm.adler = state.wrap & 1;
	  }
	  state.mode = HEAD;
	  state.last = 0;
	  state.havedict = 0;
	  state.dmax = 32768;
	  state.head = null/*Z_NULL*/;
	  state.hold = 0;
	  state.bits = 0;
	  //state.lencode = state.distcode = state.next = state.codes;
	  state.lencode = state.lendyn = new utils.Buf32(ENOUGH_LENS);
	  state.distcode = state.distdyn = new utils.Buf32(ENOUGH_DISTS);
	
	  state.sane = 1;
	  state.back = -1;
	  //Tracev((stderr, "inflate: reset\n"));
	  return Z_OK;
	}
	
	function inflateReset(strm) {
	  var state;
	
	  if (!strm || !strm.state) { return Z_STREAM_ERROR; }
	  state = strm.state;
	  state.wsize = 0;
	  state.whave = 0;
	  state.wnext = 0;
	  return inflateResetKeep(strm);
	
	}
	
	function inflateReset2(strm, windowBits) {
	  var wrap;
	  var state;
	
	  /* get the state */
	  if (!strm || !strm.state) { return Z_STREAM_ERROR; }
	  state = strm.state;
	
	  /* extract wrap request from windowBits parameter */
	  if (windowBits < 0) {
	    wrap = 0;
	    windowBits = -windowBits;
	  }
	  else {
	    wrap = (windowBits >> 4) + 1;
	    if (windowBits < 48) {
	      windowBits &= 15;
	    }
	  }
	
	  /* set number of window bits, free window if different */
	  if (windowBits && (windowBits < 8 || windowBits > 15)) {
	    return Z_STREAM_ERROR;
	  }
	  if (state.window !== null && state.wbits !== windowBits) {
	    state.window = null;
	  }
	
	  /* update state and reset the rest of it */
	  state.wrap = wrap;
	  state.wbits = windowBits;
	  return inflateReset(strm);
	}
	
	function inflateInit2(strm, windowBits) {
	  var ret;
	  var state;
	
	  if (!strm) { return Z_STREAM_ERROR; }
	  //strm.msg = Z_NULL;                 /* in case we return an error */
	
	  state = new InflateState();
	
	  //if (state === Z_NULL) return Z_MEM_ERROR;
	  //Tracev((stderr, "inflate: allocated\n"));
	  strm.state = state;
	  state.window = null/*Z_NULL*/;
	  ret = inflateReset2(strm, windowBits);
	  if (ret !== Z_OK) {
	    strm.state = null/*Z_NULL*/;
	  }
	  return ret;
	}
	
	function inflateInit(strm) {
	  return inflateInit2(strm, DEF_WBITS);
	}
	
	
	/*
	 Return state with length and distance decoding tables and index sizes set to
	 fixed code decoding.  Normally this returns fixed tables from inffixed.h.
	 If BUILDFIXED is defined, then instead this routine builds the tables the
	 first time it's called, and returns those tables the first time and
	 thereafter.  This reduces the size of the code by about 2K bytes, in
	 exchange for a little execution time.  However, BUILDFIXED should not be
	 used for threaded applications, since the rewriting of the tables and virgin
	 may not be thread-safe.
	 */
	var virgin = true;
	
	var lenfix, distfix; // We have no pointers in JS, so keep tables separate
	
	function fixedtables(state) {
	  /* build fixed huffman tables if first call (may not be thread safe) */
	  if (virgin) {
	    var sym;
	
	    lenfix = new utils.Buf32(512);
	    distfix = new utils.Buf32(32);
	
	    /* literal/length table */
	    sym = 0;
	    while (sym < 144) { state.lens[sym++] = 8; }
	    while (sym < 256) { state.lens[sym++] = 9; }
	    while (sym < 280) { state.lens[sym++] = 7; }
	    while (sym < 288) { state.lens[sym++] = 8; }
	
	    inflate_table(LENS,  state.lens, 0, 288, lenfix,   0, state.work, { bits: 9 });
	
	    /* distance table */
	    sym = 0;
	    while (sym < 32) { state.lens[sym++] = 5; }
	
	    inflate_table(DISTS, state.lens, 0, 32,   distfix, 0, state.work, { bits: 5 });
	
	    /* do this just once */
	    virgin = false;
	  }
	
	  state.lencode = lenfix;
	  state.lenbits = 9;
	  state.distcode = distfix;
	  state.distbits = 5;
	}
	
	
	/*
	 Update the window with the last wsize (normally 32K) bytes written before
	 returning.  If window does not exist yet, create it.  This is only called
	 when a window is already in use, or when output has been written during this
	 inflate call, but the end of the deflate stream has not been reached yet.
	 It is also called to create a window for dictionary data when a dictionary
	 is loaded.
	
	 Providing output buffers larger than 32K to inflate() should provide a speed
	 advantage, since only the last 32K of output is copied to the sliding window
	 upon return from inflate(), and since all distances after the first 32K of
	 output will fall in the output data, making match copies simpler and faster.
	 The advantage may be dependent on the size of the processor's data caches.
	 */
	function updatewindow(strm, src, end, copy) {
	  var dist;
	  var state = strm.state;
	
	  /* if it hasn't been done already, allocate space for the window */
	  if (state.window === null) {
	    state.wsize = 1 << state.wbits;
	    state.wnext = 0;
	    state.whave = 0;
	
	    state.window = new utils.Buf8(state.wsize);
	  }
	
	  /* copy state->wsize or less output bytes into the circular window */
	  if (copy >= state.wsize) {
	    utils.arraySet(state.window, src, end - state.wsize, state.wsize, 0);
	    state.wnext = 0;
	    state.whave = state.wsize;
	  }
	  else {
	    dist = state.wsize - state.wnext;
	    if (dist > copy) {
	      dist = copy;
	    }
	    //zmemcpy(state->window + state->wnext, end - copy, dist);
	    utils.arraySet(state.window, src, end - copy, dist, state.wnext);
	    copy -= dist;
	    if (copy) {
	      //zmemcpy(state->window, end - copy, copy);
	      utils.arraySet(state.window, src, end - copy, copy, 0);
	      state.wnext = copy;
	      state.whave = state.wsize;
	    }
	    else {
	      state.wnext += dist;
	      if (state.wnext === state.wsize) { state.wnext = 0; }
	      if (state.whave < state.wsize) { state.whave += dist; }
	    }
	  }
	  return 0;
	}
	
	function inflate(strm, flush) {
	  var state;
	  var input, output;          // input/output buffers
	  var next;                   /* next input INDEX */
	  var put;                    /* next output INDEX */
	  var have, left;             /* available input and output */
	  var hold;                   /* bit buffer */
	  var bits;                   /* bits in bit buffer */
	  var _in, _out;              /* save starting available input and output */
	  var copy;                   /* number of stored or match bytes to copy */
	  var from;                   /* where to copy match bytes from */
	  var from_source;
	  var here = 0;               /* current decoding table entry */
	  var here_bits, here_op, here_val; // paked "here" denormalized (JS specific)
	  //var last;                   /* parent table entry */
	  var last_bits, last_op, last_val; // paked "last" denormalized (JS specific)
	  var len;                    /* length to copy for repeats, bits to drop */
	  var ret;                    /* return code */
	  var hbuf = new utils.Buf8(4);    /* buffer for gzip header crc calculation */
	  var opts;
	
	  var n; // temporary var for NEED_BITS
	
	  var order = /* permutation of code lengths */
	    [ 16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15 ];
	
	
	  if (!strm || !strm.state || !strm.output ||
	      (!strm.input && strm.avail_in !== 0)) {
	    return Z_STREAM_ERROR;
	  }
	
	  state = strm.state;
	  if (state.mode === TYPE) { state.mode = TYPEDO; }    /* skip check */
	
	
	  //--- LOAD() ---
	  put = strm.next_out;
	  output = strm.output;
	  left = strm.avail_out;
	  next = strm.next_in;
	  input = strm.input;
	  have = strm.avail_in;
	  hold = state.hold;
	  bits = state.bits;
	  //---
	
	  _in = have;
	  _out = left;
	  ret = Z_OK;
	
	  inf_leave: // goto emulation
	  for (;;) {
	    switch (state.mode) {
	    case HEAD:
	      if (state.wrap === 0) {
	        state.mode = TYPEDO;
	        break;
	      }
	      //=== NEEDBITS(16);
	      while (bits < 16) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      if ((state.wrap & 2) && hold === 0x8b1f) {  /* gzip header */
	        state.check = 0/*crc32(0L, Z_NULL, 0)*/;
	        //=== CRC2(state.check, hold);
	        hbuf[0] = hold & 0xff;
	        hbuf[1] = (hold >>> 8) & 0xff;
	        state.check = crc32(state.check, hbuf, 2, 0);
	        //===//
	
	        //=== INITBITS();
	        hold = 0;
	        bits = 0;
	        //===//
	        state.mode = FLAGS;
	        break;
	      }
	      state.flags = 0;           /* expect zlib header */
	      if (state.head) {
	        state.head.done = false;
	      }
	      if (!(state.wrap & 1) ||   /* check if zlib header allowed */
	        (((hold & 0xff)/*BITS(8)*/ << 8) + (hold >> 8)) % 31) {
	        strm.msg = 'incorrect header check';
	        state.mode = BAD;
	        break;
	      }
	      if ((hold & 0x0f)/*BITS(4)*/ !== Z_DEFLATED) {
	        strm.msg = 'unknown compression method';
	        state.mode = BAD;
	        break;
	      }
	      //--- DROPBITS(4) ---//
	      hold >>>= 4;
	      bits -= 4;
	      //---//
	      len = (hold & 0x0f)/*BITS(4)*/ + 8;
	      if (state.wbits === 0) {
	        state.wbits = len;
	      }
	      else if (len > state.wbits) {
	        strm.msg = 'invalid window size';
	        state.mode = BAD;
	        break;
	      }
	      state.dmax = 1 << len;
	      //Tracev((stderr, "inflate:   zlib header ok\n"));
	      strm.adler = state.check = 1/*adler32(0L, Z_NULL, 0)*/;
	      state.mode = hold & 0x200 ? DICTID : TYPE;
	      //=== INITBITS();
	      hold = 0;
	      bits = 0;
	      //===//
	      break;
	    case FLAGS:
	      //=== NEEDBITS(16); */
	      while (bits < 16) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      state.flags = hold;
	      if ((state.flags & 0xff) !== Z_DEFLATED) {
	        strm.msg = 'unknown compression method';
	        state.mode = BAD;
	        break;
	      }
	      if (state.flags & 0xe000) {
	        strm.msg = 'unknown header flags set';
	        state.mode = BAD;
	        break;
	      }
	      if (state.head) {
	        state.head.text = ((hold >> 8) & 1);
	      }
	      if (state.flags & 0x0200) {
	        //=== CRC2(state.check, hold);
	        hbuf[0] = hold & 0xff;
	        hbuf[1] = (hold >>> 8) & 0xff;
	        state.check = crc32(state.check, hbuf, 2, 0);
	        //===//
	      }
	      //=== INITBITS();
	      hold = 0;
	      bits = 0;
	      //===//
	      state.mode = TIME;
	      /* falls through */
	    case TIME:
	      //=== NEEDBITS(32); */
	      while (bits < 32) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      if (state.head) {
	        state.head.time = hold;
	      }
	      if (state.flags & 0x0200) {
	        //=== CRC4(state.check, hold)
	        hbuf[0] = hold & 0xff;
	        hbuf[1] = (hold >>> 8) & 0xff;
	        hbuf[2] = (hold >>> 16) & 0xff;
	        hbuf[3] = (hold >>> 24) & 0xff;
	        state.check = crc32(state.check, hbuf, 4, 0);
	        //===
	      }
	      //=== INITBITS();
	      hold = 0;
	      bits = 0;
	      //===//
	      state.mode = OS;
	      /* falls through */
	    case OS:
	      //=== NEEDBITS(16); */
	      while (bits < 16) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      if (state.head) {
	        state.head.xflags = (hold & 0xff);
	        state.head.os = (hold >> 8);
	      }
	      if (state.flags & 0x0200) {
	        //=== CRC2(state.check, hold);
	        hbuf[0] = hold & 0xff;
	        hbuf[1] = (hold >>> 8) & 0xff;
	        state.check = crc32(state.check, hbuf, 2, 0);
	        //===//
	      }
	      //=== INITBITS();
	      hold = 0;
	      bits = 0;
	      //===//
	      state.mode = EXLEN;
	      /* falls through */
	    case EXLEN:
	      if (state.flags & 0x0400) {
	        //=== NEEDBITS(16); */
	        while (bits < 16) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        state.length = hold;
	        if (state.head) {
	          state.head.extra_len = hold;
	        }
	        if (state.flags & 0x0200) {
	          //=== CRC2(state.check, hold);
	          hbuf[0] = hold & 0xff;
	          hbuf[1] = (hold >>> 8) & 0xff;
	          state.check = crc32(state.check, hbuf, 2, 0);
	          //===//
	        }
	        //=== INITBITS();
	        hold = 0;
	        bits = 0;
	        //===//
	      }
	      else if (state.head) {
	        state.head.extra = null/*Z_NULL*/;
	      }
	      state.mode = EXTRA;
	      /* falls through */
	    case EXTRA:
	      if (state.flags & 0x0400) {
	        copy = state.length;
	        if (copy > have) { copy = have; }
	        if (copy) {
	          if (state.head) {
	            len = state.head.extra_len - state.length;
	            if (!state.head.extra) {
	              // Use untyped array for more conveniend processing later
	              state.head.extra = new Array(state.head.extra_len);
	            }
	            utils.arraySet(
	              state.head.extra,
	              input,
	              next,
	              // extra field is limited to 65536 bytes
	              // - no need for additional size check
	              copy,
	              /*len + copy > state.head.extra_max - len ? state.head.extra_max : copy,*/
	              len
	            );
	            //zmemcpy(state.head.extra + len, next,
	            //        len + copy > state.head.extra_max ?
	            //        state.head.extra_max - len : copy);
	          }
	          if (state.flags & 0x0200) {
	            state.check = crc32(state.check, input, copy, next);
	          }
	          have -= copy;
	          next += copy;
	          state.length -= copy;
	        }
	        if (state.length) { break inf_leave; }
	      }
	      state.length = 0;
	      state.mode = NAME;
	      /* falls through */
	    case NAME:
	      if (state.flags & 0x0800) {
	        if (have === 0) { break inf_leave; }
	        copy = 0;
	        do {
	          // TODO: 2 or 1 bytes?
	          len = input[next + copy++];
	          /* use constant limit because in js we should not preallocate memory */
	          if (state.head && len &&
	              (state.length < 65536 /*state.head.name_max*/)) {
	            state.head.name += String.fromCharCode(len);
	          }
	        } while (len && copy < have);
	
	        if (state.flags & 0x0200) {
	          state.check = crc32(state.check, input, copy, next);
	        }
	        have -= copy;
	        next += copy;
	        if (len) { break inf_leave; }
	      }
	      else if (state.head) {
	        state.head.name = null;
	      }
	      state.length = 0;
	      state.mode = COMMENT;
	      /* falls through */
	    case COMMENT:
	      if (state.flags & 0x1000) {
	        if (have === 0) { break inf_leave; }
	        copy = 0;
	        do {
	          len = input[next + copy++];
	          /* use constant limit because in js we should not preallocate memory */
	          if (state.head && len &&
	              (state.length < 65536 /*state.head.comm_max*/)) {
	            state.head.comment += String.fromCharCode(len);
	          }
	        } while (len && copy < have);
	        if (state.flags & 0x0200) {
	          state.check = crc32(state.check, input, copy, next);
	        }
	        have -= copy;
	        next += copy;
	        if (len) { break inf_leave; }
	      }
	      else if (state.head) {
	        state.head.comment = null;
	      }
	      state.mode = HCRC;
	      /* falls through */
	    case HCRC:
	      if (state.flags & 0x0200) {
	        //=== NEEDBITS(16); */
	        while (bits < 16) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        if (hold !== (state.check & 0xffff)) {
	          strm.msg = 'header crc mismatch';
	          state.mode = BAD;
	          break;
	        }
	        //=== INITBITS();
	        hold = 0;
	        bits = 0;
	        //===//
	      }
	      if (state.head) {
	        state.head.hcrc = ((state.flags >> 9) & 1);
	        state.head.done = true;
	      }
	      strm.adler = state.check = 0;
	      state.mode = TYPE;
	      break;
	    case DICTID:
	      //=== NEEDBITS(32); */
	      while (bits < 32) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      strm.adler = state.check = zswap32(hold);
	      //=== INITBITS();
	      hold = 0;
	      bits = 0;
	      //===//
	      state.mode = DICT;
	      /* falls through */
	    case DICT:
	      if (state.havedict === 0) {
	        //--- RESTORE() ---
	        strm.next_out = put;
	        strm.avail_out = left;
	        strm.next_in = next;
	        strm.avail_in = have;
	        state.hold = hold;
	        state.bits = bits;
	        //---
	        return Z_NEED_DICT;
	      }
	      strm.adler = state.check = 1/*adler32(0L, Z_NULL, 0)*/;
	      state.mode = TYPE;
	      /* falls through */
	    case TYPE:
	      if (flush === Z_BLOCK || flush === Z_TREES) { break inf_leave; }
	      /* falls through */
	    case TYPEDO:
	      if (state.last) {
	        //--- BYTEBITS() ---//
	        hold >>>= bits & 7;
	        bits -= bits & 7;
	        //---//
	        state.mode = CHECK;
	        break;
	      }
	      //=== NEEDBITS(3); */
	      while (bits < 3) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      state.last = (hold & 0x01)/*BITS(1)*/;
	      //--- DROPBITS(1) ---//
	      hold >>>= 1;
	      bits -= 1;
	      //---//
	
	      switch ((hold & 0x03)/*BITS(2)*/) {
	      case 0:                             /* stored block */
	        //Tracev((stderr, "inflate:     stored block%s\n",
	        //        state.last ? " (last)" : ""));
	        state.mode = STORED;
	        break;
	      case 1:                             /* fixed block */
	        fixedtables(state);
	        //Tracev((stderr, "inflate:     fixed codes block%s\n",
	        //        state.last ? " (last)" : ""));
	        state.mode = LEN_;             /* decode codes */
	        if (flush === Z_TREES) {
	          //--- DROPBITS(2) ---//
	          hold >>>= 2;
	          bits -= 2;
	          //---//
	          break inf_leave;
	        }
	        break;
	      case 2:                             /* dynamic block */
	        //Tracev((stderr, "inflate:     dynamic codes block%s\n",
	        //        state.last ? " (last)" : ""));
	        state.mode = TABLE;
	        break;
	      case 3:
	        strm.msg = 'invalid block type';
	        state.mode = BAD;
	      }
	      //--- DROPBITS(2) ---//
	      hold >>>= 2;
	      bits -= 2;
	      //---//
	      break;
	    case STORED:
	      //--- BYTEBITS() ---// /* go to byte boundary */
	      hold >>>= bits & 7;
	      bits -= bits & 7;
	      //---//
	      //=== NEEDBITS(32); */
	      while (bits < 32) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      if ((hold & 0xffff) !== ((hold >>> 16) ^ 0xffff)) {
	        strm.msg = 'invalid stored block lengths';
	        state.mode = BAD;
	        break;
	      }
	      state.length = hold & 0xffff;
	      //Tracev((stderr, "inflate:       stored length %u\n",
	      //        state.length));
	      //=== INITBITS();
	      hold = 0;
	      bits = 0;
	      //===//
	      state.mode = COPY_;
	      if (flush === Z_TREES) { break inf_leave; }
	      /* falls through */
	    case COPY_:
	      state.mode = COPY;
	      /* falls through */
	    case COPY:
	      copy = state.length;
	      if (copy) {
	        if (copy > have) { copy = have; }
	        if (copy > left) { copy = left; }
	        if (copy === 0) { break inf_leave; }
	        //--- zmemcpy(put, next, copy); ---
	        utils.arraySet(output, input, next, copy, put);
	        //---//
	        have -= copy;
	        next += copy;
	        left -= copy;
	        put += copy;
	        state.length -= copy;
	        break;
	      }
	      //Tracev((stderr, "inflate:       stored end\n"));
	      state.mode = TYPE;
	      break;
	    case TABLE:
	      //=== NEEDBITS(14); */
	      while (bits < 14) {
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	      }
	      //===//
	      state.nlen = (hold & 0x1f)/*BITS(5)*/ + 257;
	      //--- DROPBITS(5) ---//
	      hold >>>= 5;
	      bits -= 5;
	      //---//
	      state.ndist = (hold & 0x1f)/*BITS(5)*/ + 1;
	      //--- DROPBITS(5) ---//
	      hold >>>= 5;
	      bits -= 5;
	      //---//
	      state.ncode = (hold & 0x0f)/*BITS(4)*/ + 4;
	      //--- DROPBITS(4) ---//
	      hold >>>= 4;
	      bits -= 4;
	      //---//
	//#ifndef PKZIP_BUG_WORKAROUND
	      if (state.nlen > 286 || state.ndist > 30) {
	        strm.msg = 'too many length or distance symbols';
	        state.mode = BAD;
	        break;
	      }
	//#endif
	      //Tracev((stderr, "inflate:       table sizes ok\n"));
	      state.have = 0;
	      state.mode = LENLENS;
	      /* falls through */
	    case LENLENS:
	      while (state.have < state.ncode) {
	        //=== NEEDBITS(3);
	        while (bits < 3) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        state.lens[order[state.have++]] = (hold & 0x07);//BITS(3);
	        //--- DROPBITS(3) ---//
	        hold >>>= 3;
	        bits -= 3;
	        //---//
	      }
	      while (state.have < 19) {
	        state.lens[order[state.have++]] = 0;
	      }
	      // We have separate tables & no pointers. 2 commented lines below not needed.
	      //state.next = state.codes;
	      //state.lencode = state.next;
	      // Switch to use dynamic table
	      state.lencode = state.lendyn;
	      state.lenbits = 7;
	
	      opts = { bits: state.lenbits };
	      ret = inflate_table(CODES, state.lens, 0, 19, state.lencode, 0, state.work, opts);
	      state.lenbits = opts.bits;
	
	      if (ret) {
	        strm.msg = 'invalid code lengths set';
	        state.mode = BAD;
	        break;
	      }
	      //Tracev((stderr, "inflate:       code lengths ok\n"));
	      state.have = 0;
	      state.mode = CODELENS;
	      /* falls through */
	    case CODELENS:
	      while (state.have < state.nlen + state.ndist) {
	        for (;;) {
	          here = state.lencode[hold & ((1 << state.lenbits) - 1)];/*BITS(state.lenbits)*/
	          here_bits = here >>> 24;
	          here_op = (here >>> 16) & 0xff;
	          here_val = here & 0xffff;
	
	          if ((here_bits) <= bits) { break; }
	          //--- PULLBYTE() ---//
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	          //---//
	        }
	        if (here_val < 16) {
	          //--- DROPBITS(here.bits) ---//
	          hold >>>= here_bits;
	          bits -= here_bits;
	          //---//
	          state.lens[state.have++] = here_val;
	        }
	        else {
	          if (here_val === 16) {
	            //=== NEEDBITS(here.bits + 2);
	            n = here_bits + 2;
	            while (bits < n) {
	              if (have === 0) { break inf_leave; }
	              have--;
	              hold += input[next++] << bits;
	              bits += 8;
	            }
	            //===//
	            //--- DROPBITS(here.bits) ---//
	            hold >>>= here_bits;
	            bits -= here_bits;
	            //---//
	            if (state.have === 0) {
	              strm.msg = 'invalid bit length repeat';
	              state.mode = BAD;
	              break;
	            }
	            len = state.lens[state.have - 1];
	            copy = 3 + (hold & 0x03);//BITS(2);
	            //--- DROPBITS(2) ---//
	            hold >>>= 2;
	            bits -= 2;
	            //---//
	          }
	          else if (here_val === 17) {
	            //=== NEEDBITS(here.bits + 3);
	            n = here_bits + 3;
	            while (bits < n) {
	              if (have === 0) { break inf_leave; }
	              have--;
	              hold += input[next++] << bits;
	              bits += 8;
	            }
	            //===//
	            //--- DROPBITS(here.bits) ---//
	            hold >>>= here_bits;
	            bits -= here_bits;
	            //---//
	            len = 0;
	            copy = 3 + (hold & 0x07);//BITS(3);
	            //--- DROPBITS(3) ---//
	            hold >>>= 3;
	            bits -= 3;
	            //---//
	          }
	          else {
	            //=== NEEDBITS(here.bits + 7);
	            n = here_bits + 7;
	            while (bits < n) {
	              if (have === 0) { break inf_leave; }
	              have--;
	              hold += input[next++] << bits;
	              bits += 8;
	            }
	            //===//
	            //--- DROPBITS(here.bits) ---//
	            hold >>>= here_bits;
	            bits -= here_bits;
	            //---//
	            len = 0;
	            copy = 11 + (hold & 0x7f);//BITS(7);
	            //--- DROPBITS(7) ---//
	            hold >>>= 7;
	            bits -= 7;
	            //---//
	          }
	          if (state.have + copy > state.nlen + state.ndist) {
	            strm.msg = 'invalid bit length repeat';
	            state.mode = BAD;
	            break;
	          }
	          while (copy--) {
	            state.lens[state.have++] = len;
	          }
	        }
	      }
	
	      /* handle error breaks in while */
	      if (state.mode === BAD) { break; }
	
	      /* check for end-of-block code (better have one) */
	      if (state.lens[256] === 0) {
	        strm.msg = 'invalid code -- missing end-of-block';
	        state.mode = BAD;
	        break;
	      }
	
	      /* build code tables -- note: do not change the lenbits or distbits
	         values here (9 and 6) without reading the comments in inftrees.h
	         concerning the ENOUGH constants, which depend on those values */
	      state.lenbits = 9;
	
	      opts = { bits: state.lenbits };
	      ret = inflate_table(LENS, state.lens, 0, state.nlen, state.lencode, 0, state.work, opts);
	      // We have separate tables & no pointers. 2 commented lines below not needed.
	      // state.next_index = opts.table_index;
	      state.lenbits = opts.bits;
	      // state.lencode = state.next;
	
	      if (ret) {
	        strm.msg = 'invalid literal/lengths set';
	        state.mode = BAD;
	        break;
	      }
	
	      state.distbits = 6;
	      //state.distcode.copy(state.codes);
	      // Switch to use dynamic table
	      state.distcode = state.distdyn;
	      opts = { bits: state.distbits };
	      ret = inflate_table(DISTS, state.lens, state.nlen, state.ndist, state.distcode, 0, state.work, opts);
	      // We have separate tables & no pointers. 2 commented lines below not needed.
	      // state.next_index = opts.table_index;
	      state.distbits = opts.bits;
	      // state.distcode = state.next;
	
	      if (ret) {
	        strm.msg = 'invalid distances set';
	        state.mode = BAD;
	        break;
	      }
	      //Tracev((stderr, 'inflate:       codes ok\n'));
	      state.mode = LEN_;
	      if (flush === Z_TREES) { break inf_leave; }
	      /* falls through */
	    case LEN_:
	      state.mode = LEN;
	      /* falls through */
	    case LEN:
	      if (have >= 6 && left >= 258) {
	        //--- RESTORE() ---
	        strm.next_out = put;
	        strm.avail_out = left;
	        strm.next_in = next;
	        strm.avail_in = have;
	        state.hold = hold;
	        state.bits = bits;
	        //---
	        inflate_fast(strm, _out);
	        //--- LOAD() ---
	        put = strm.next_out;
	        output = strm.output;
	        left = strm.avail_out;
	        next = strm.next_in;
	        input = strm.input;
	        have = strm.avail_in;
	        hold = state.hold;
	        bits = state.bits;
	        //---
	
	        if (state.mode === TYPE) {
	          state.back = -1;
	        }
	        break;
	      }
	      state.back = 0;
	      for (;;) {
	        here = state.lencode[hold & ((1 << state.lenbits) - 1)];  /*BITS(state.lenbits)*/
	        here_bits = here >>> 24;
	        here_op = (here >>> 16) & 0xff;
	        here_val = here & 0xffff;
	
	        if (here_bits <= bits) { break; }
	        //--- PULLBYTE() ---//
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	        //---//
	      }
	      if (here_op && (here_op & 0xf0) === 0) {
	        last_bits = here_bits;
	        last_op = here_op;
	        last_val = here_val;
	        for (;;) {
	          here = state.lencode[last_val +
	                  ((hold & ((1 << (last_bits + last_op)) - 1))/*BITS(last.bits + last.op)*/ >> last_bits)];
	          here_bits = here >>> 24;
	          here_op = (here >>> 16) & 0xff;
	          here_val = here & 0xffff;
	
	          if ((last_bits + here_bits) <= bits) { break; }
	          //--- PULLBYTE() ---//
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	          //---//
	        }
	        //--- DROPBITS(last.bits) ---//
	        hold >>>= last_bits;
	        bits -= last_bits;
	        //---//
	        state.back += last_bits;
	      }
	      //--- DROPBITS(here.bits) ---//
	      hold >>>= here_bits;
	      bits -= here_bits;
	      //---//
	      state.back += here_bits;
	      state.length = here_val;
	      if (here_op === 0) {
	        //Tracevv((stderr, here.val >= 0x20 && here.val < 0x7f ?
	        //        "inflate:         literal '%c'\n" :
	        //        "inflate:         literal 0x%02x\n", here.val));
	        state.mode = LIT;
	        break;
	      }
	      if (here_op & 32) {
	        //Tracevv((stderr, "inflate:         end of block\n"));
	        state.back = -1;
	        state.mode = TYPE;
	        break;
	      }
	      if (here_op & 64) {
	        strm.msg = 'invalid literal/length code';
	        state.mode = BAD;
	        break;
	      }
	      state.extra = here_op & 15;
	      state.mode = LENEXT;
	      /* falls through */
	    case LENEXT:
	      if (state.extra) {
	        //=== NEEDBITS(state.extra);
	        n = state.extra;
	        while (bits < n) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        state.length += hold & ((1 << state.extra) - 1)/*BITS(state.extra)*/;
	        //--- DROPBITS(state.extra) ---//
	        hold >>>= state.extra;
	        bits -= state.extra;
	        //---//
	        state.back += state.extra;
	      }
	      //Tracevv((stderr, "inflate:         length %u\n", state.length));
	      state.was = state.length;
	      state.mode = DIST;
	      /* falls through */
	    case DIST:
	      for (;;) {
	        here = state.distcode[hold & ((1 << state.distbits) - 1)];/*BITS(state.distbits)*/
	        here_bits = here >>> 24;
	        here_op = (here >>> 16) & 0xff;
	        here_val = here & 0xffff;
	
	        if ((here_bits) <= bits) { break; }
	        //--- PULLBYTE() ---//
	        if (have === 0) { break inf_leave; }
	        have--;
	        hold += input[next++] << bits;
	        bits += 8;
	        //---//
	      }
	      if ((here_op & 0xf0) === 0) {
	        last_bits = here_bits;
	        last_op = here_op;
	        last_val = here_val;
	        for (;;) {
	          here = state.distcode[last_val +
	                  ((hold & ((1 << (last_bits + last_op)) - 1))/*BITS(last.bits + last.op)*/ >> last_bits)];
	          here_bits = here >>> 24;
	          here_op = (here >>> 16) & 0xff;
	          here_val = here & 0xffff;
	
	          if ((last_bits + here_bits) <= bits) { break; }
	          //--- PULLBYTE() ---//
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	          //---//
	        }
	        //--- DROPBITS(last.bits) ---//
	        hold >>>= last_bits;
	        bits -= last_bits;
	        //---//
	        state.back += last_bits;
	      }
	      //--- DROPBITS(here.bits) ---//
	      hold >>>= here_bits;
	      bits -= here_bits;
	      //---//
	      state.back += here_bits;
	      if (here_op & 64) {
	        strm.msg = 'invalid distance code';
	        state.mode = BAD;
	        break;
	      }
	      state.offset = here_val;
	      state.extra = (here_op) & 15;
	      state.mode = DISTEXT;
	      /* falls through */
	    case DISTEXT:
	      if (state.extra) {
	        //=== NEEDBITS(state.extra);
	        n = state.extra;
	        while (bits < n) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        state.offset += hold & ((1 << state.extra) - 1)/*BITS(state.extra)*/;
	        //--- DROPBITS(state.extra) ---//
	        hold >>>= state.extra;
	        bits -= state.extra;
	        //---//
	        state.back += state.extra;
	      }
	//#ifdef INFLATE_STRICT
	      if (state.offset > state.dmax) {
	        strm.msg = 'invalid distance too far back';
	        state.mode = BAD;
	        break;
	      }
	//#endif
	      //Tracevv((stderr, "inflate:         distance %u\n", state.offset));
	      state.mode = MATCH;
	      /* falls through */
	    case MATCH:
	      if (left === 0) { break inf_leave; }
	      copy = _out - left;
	      if (state.offset > copy) {         /* copy from window */
	        copy = state.offset - copy;
	        if (copy > state.whave) {
	          if (state.sane) {
	            strm.msg = 'invalid distance too far back';
	            state.mode = BAD;
	            break;
	          }
	// (!) This block is disabled in zlib defailts,
	// don't enable it for binary compatibility
	//#ifdef INFLATE_ALLOW_INVALID_DISTANCE_TOOFAR_ARRR
	//          Trace((stderr, "inflate.c too far\n"));
	//          copy -= state.whave;
	//          if (copy > state.length) { copy = state.length; }
	//          if (copy > left) { copy = left; }
	//          left -= copy;
	//          state.length -= copy;
	//          do {
	//            output[put++] = 0;
	//          } while (--copy);
	//          if (state.length === 0) { state.mode = LEN; }
	//          break;
	//#endif
	        }
	        if (copy > state.wnext) {
	          copy -= state.wnext;
	          from = state.wsize - copy;
	        }
	        else {
	          from = state.wnext - copy;
	        }
	        if (copy > state.length) { copy = state.length; }
	        from_source = state.window;
	      }
	      else {                              /* copy from output */
	        from_source = output;
	        from = put - state.offset;
	        copy = state.length;
	      }
	      if (copy > left) { copy = left; }
	      left -= copy;
	      state.length -= copy;
	      do {
	        output[put++] = from_source[from++];
	      } while (--copy);
	      if (state.length === 0) { state.mode = LEN; }
	      break;
	    case LIT:
	      if (left === 0) { break inf_leave; }
	      output[put++] = state.length;
	      left--;
	      state.mode = LEN;
	      break;
	    case CHECK:
	      if (state.wrap) {
	        //=== NEEDBITS(32);
	        while (bits < 32) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          // Use '|' insdead of '+' to make sure that result is signed
	          hold |= input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        _out -= left;
	        strm.total_out += _out;
	        state.total += _out;
	        if (_out) {
	          strm.adler = state.check =
	              /*UPDATE(state.check, put - _out, _out);*/
	              (state.flags ? crc32(state.check, output, _out, put - _out) : adler32(state.check, output, _out, put - _out));
	
	        }
	        _out = left;
	        // NB: crc32 stored as signed 32-bit int, zswap32 returns signed too
	        if ((state.flags ? hold : zswap32(hold)) !== state.check) {
	          strm.msg = 'incorrect data check';
	          state.mode = BAD;
	          break;
	        }
	        //=== INITBITS();
	        hold = 0;
	        bits = 0;
	        //===//
	        //Tracev((stderr, "inflate:   check matches trailer\n"));
	      }
	      state.mode = LENGTH;
	      /* falls through */
	    case LENGTH:
	      if (state.wrap && state.flags) {
	        //=== NEEDBITS(32);
	        while (bits < 32) {
	          if (have === 0) { break inf_leave; }
	          have--;
	          hold += input[next++] << bits;
	          bits += 8;
	        }
	        //===//
	        if (hold !== (state.total & 0xffffffff)) {
	          strm.msg = 'incorrect length check';
	          state.mode = BAD;
	          break;
	        }
	        //=== INITBITS();
	        hold = 0;
	        bits = 0;
	        //===//
	        //Tracev((stderr, "inflate:   length matches trailer\n"));
	      }
	      state.mode = DONE;
	      /* falls through */
	    case DONE:
	      ret = Z_STREAM_END;
	      break inf_leave;
	    case BAD:
	      ret = Z_DATA_ERROR;
	      break inf_leave;
	    case MEM:
	      return Z_MEM_ERROR;
	    case SYNC:
	      /* falls through */
	    default:
	      return Z_STREAM_ERROR;
	    }
	  }
	
	  // inf_leave <- here is real place for "goto inf_leave", emulated via "break inf_leave"
	
	  /*
	     Return from inflate(), updating the total counts and the check value.
	     If there was no progress during the inflate() call, return a buffer
	     error.  Call updatewindow() to create and/or update the window state.
	     Note: a memory error from inflate() is non-recoverable.
	   */
	
	  //--- RESTORE() ---
	  strm.next_out = put;
	  strm.avail_out = left;
	  strm.next_in = next;
	  strm.avail_in = have;
	  state.hold = hold;
	  state.bits = bits;
	  //---
	
	  if (state.wsize || (_out !== strm.avail_out && state.mode < BAD &&
	                      (state.mode < CHECK || flush !== Z_FINISH))) {
	    if (updatewindow(strm, strm.output, strm.next_out, _out - strm.avail_out)) {
	      state.mode = MEM;
	      return Z_MEM_ERROR;
	    }
	  }
	  _in -= strm.avail_in;
	  _out -= strm.avail_out;
	  strm.total_in += _in;
	  strm.total_out += _out;
	  state.total += _out;
	  if (state.wrap && _out) {
	    strm.adler = state.check = /*UPDATE(state.check, strm.next_out - _out, _out);*/
	      (state.flags ? crc32(state.check, output, _out, strm.next_out - _out) : adler32(state.check, output, _out, strm.next_out - _out));
	  }
	  strm.data_type = state.bits + (state.last ? 64 : 0) +
	                    (state.mode === TYPE ? 128 : 0) +
	                    (state.mode === LEN_ || state.mode === COPY_ ? 256 : 0);
	  if (((_in === 0 && _out === 0) || flush === Z_FINISH) && ret === Z_OK) {
	    ret = Z_BUF_ERROR;
	  }
	  return ret;
	}
	
	function inflateEnd(strm) {
	
	  if (!strm || !strm.state /*|| strm->zfree == (free_func)0*/) {
	    return Z_STREAM_ERROR;
	  }
	
	  var state = strm.state;
	  if (state.window) {
	    state.window = null;
	  }
	  strm.state = null;
	  return Z_OK;
	}
	
	function inflateGetHeader(strm, head) {
	  var state;
	
	  /* check state */
	  if (!strm || !strm.state) { return Z_STREAM_ERROR; }
	  state = strm.state;
	  if ((state.wrap & 2) === 0) { return Z_STREAM_ERROR; }
	
	  /* save header structure */
	  state.head = head;
	  head.done = false;
	  return Z_OK;
	}
	
	
	exports.inflateReset = inflateReset;
	exports.inflateReset2 = inflateReset2;
	exports.inflateResetKeep = inflateResetKeep;
	exports.inflateInit = inflateInit;
	exports.inflateInit2 = inflateInit2;
	exports.inflate = inflate;
	exports.inflateEnd = inflateEnd;
	exports.inflateGetHeader = inflateGetHeader;
	exports.inflateInfo = 'pako inflate (from Nodeca project)';
	
	/* Not implemented
	exports.inflateCopy = inflateCopy;
	exports.inflateGetDictionary = inflateGetDictionary;
	exports.inflateMark = inflateMark;
	exports.inflatePrime = inflatePrime;
	exports.inflateSetDictionary = inflateSetDictionary;
	exports.inflateSync = inflateSync;
	exports.inflateSyncPoint = inflateSyncPoint;
	exports.inflateUndermine = inflateUndermine;
	*/


/***/ },
/* 41 */
/***/ function(module, exports) {

	'use strict';
	
	// See state defs from inflate.js
	var BAD = 30;       /* got a data error -- remain here until reset */
	var TYPE = 12;      /* i: waiting for type bits, including last-flag bit */
	
	/*
	   Decode literal, length, and distance codes and write out the resulting
	   literal and match bytes until either not enough input or output is
	   available, an end-of-block is encountered, or a data error is encountered.
	   When large enough input and output buffers are supplied to inflate(), for
	   example, a 16K input buffer and a 64K output buffer, more than 95% of the
	   inflate execution time is spent in this routine.
	
	   Entry assumptions:
	
	        state.mode === LEN
	        strm.avail_in >= 6
	        strm.avail_out >= 258
	        start >= strm.avail_out
	        state.bits < 8
	
	   On return, state.mode is one of:
	
	        LEN -- ran out of enough output space or enough available input
	        TYPE -- reached end of block code, inflate() to interpret next block
	        BAD -- error in block data
	
	   Notes:
	
	    - The maximum input bits used by a length/distance pair is 15 bits for the
	      length code, 5 bits for the length extra, 15 bits for the distance code,
	      and 13 bits for the distance extra.  This totals 48 bits, or six bytes.
	      Therefore if strm.avail_in >= 6, then there is enough input to avoid
	      checking for available input while decoding.
	
	    - The maximum bytes that a single length/distance pair can output is 258
	      bytes, which is the maximum length that can be coded.  inflate_fast()
	      requires strm.avail_out >= 258 for each loop to avoid checking for
	      output space.
	 */
	module.exports = function inflate_fast(strm, start) {
	  var state;
	  var _in;                    /* local strm.input */
	  var last;                   /* have enough input while in < last */
	  var _out;                   /* local strm.output */
	  var beg;                    /* inflate()'s initial strm.output */
	  var end;                    /* while out < end, enough space available */
	//#ifdef INFLATE_STRICT
	  var dmax;                   /* maximum distance from zlib header */
	//#endif
	  var wsize;                  /* window size or zero if not using window */
	  var whave;                  /* valid bytes in the window */
	  var wnext;                  /* window write index */
	  // Use `s_window` instead `window`, avoid conflict with instrumentation tools
	  var s_window;               /* allocated sliding window, if wsize != 0 */
	  var hold;                   /* local strm.hold */
	  var bits;                   /* local strm.bits */
	  var lcode;                  /* local strm.lencode */
	  var dcode;                  /* local strm.distcode */
	  var lmask;                  /* mask for first level of length codes */
	  var dmask;                  /* mask for first level of distance codes */
	  var here;                   /* retrieved table entry */
	  var op;                     /* code bits, operation, extra bits, or */
	                              /*  window position, window bytes to copy */
	  var len;                    /* match length, unused bytes */
	  var dist;                   /* match distance */
	  var from;                   /* where to copy match from */
	  var from_source;
	
	
	  var input, output; // JS specific, because we have no pointers
	
	  /* copy state to local variables */
	  state = strm.state;
	  //here = state.here;
	  _in = strm.next_in;
	  input = strm.input;
	  last = _in + (strm.avail_in - 5);
	  _out = strm.next_out;
	  output = strm.output;
	  beg = _out - (start - strm.avail_out);
	  end = _out + (strm.avail_out - 257);
	//#ifdef INFLATE_STRICT
	  dmax = state.dmax;
	//#endif
	  wsize = state.wsize;
	  whave = state.whave;
	  wnext = state.wnext;
	  s_window = state.window;
	  hold = state.hold;
	  bits = state.bits;
	  lcode = state.lencode;
	  dcode = state.distcode;
	  lmask = (1 << state.lenbits) - 1;
	  dmask = (1 << state.distbits) - 1;
	
	
	  /* decode literals and length/distances until end-of-block or not enough
	     input data or output space */
	
	  top:
	  do {
	    if (bits < 15) {
	      hold += input[_in++] << bits;
	      bits += 8;
	      hold += input[_in++] << bits;
	      bits += 8;
	    }
	
	    here = lcode[hold & lmask];
	
	    dolen:
	    for (;;) { // Goto emulation
	      op = here >>> 24/*here.bits*/;
	      hold >>>= op;
	      bits -= op;
	      op = (here >>> 16) & 0xff/*here.op*/;
	      if (op === 0) {                          /* literal */
	        //Tracevv((stderr, here.val >= 0x20 && here.val < 0x7f ?
	        //        "inflate:         literal '%c'\n" :
	        //        "inflate:         literal 0x%02x\n", here.val));
	        output[_out++] = here & 0xffff/*here.val*/;
	      }
	      else if (op & 16) {                     /* length base */
	        len = here & 0xffff/*here.val*/;
	        op &= 15;                           /* number of extra bits */
	        if (op) {
	          if (bits < op) {
	            hold += input[_in++] << bits;
	            bits += 8;
	          }
	          len += hold & ((1 << op) - 1);
	          hold >>>= op;
	          bits -= op;
	        }
	        //Tracevv((stderr, "inflate:         length %u\n", len));
	        if (bits < 15) {
	          hold += input[_in++] << bits;
	          bits += 8;
	          hold += input[_in++] << bits;
	          bits += 8;
	        }
	        here = dcode[hold & dmask];
	
	        dodist:
	        for (;;) { // goto emulation
	          op = here >>> 24/*here.bits*/;
	          hold >>>= op;
	          bits -= op;
	          op = (here >>> 16) & 0xff/*here.op*/;
	
	          if (op & 16) {                      /* distance base */
	            dist = here & 0xffff/*here.val*/;
	            op &= 15;                       /* number of extra bits */
	            if (bits < op) {
	              hold += input[_in++] << bits;
	              bits += 8;
	              if (bits < op) {
	                hold += input[_in++] << bits;
	                bits += 8;
	              }
	            }
	            dist += hold & ((1 << op) - 1);
	//#ifdef INFLATE_STRICT
	            if (dist > dmax) {
	              strm.msg = 'invalid distance too far back';
	              state.mode = BAD;
	              break top;
	            }
	//#endif
	            hold >>>= op;
	            bits -= op;
	            //Tracevv((stderr, "inflate:         distance %u\n", dist));
	            op = _out - beg;                /* max distance in output */
	            if (dist > op) {                /* see if copy from window */
	              op = dist - op;               /* distance back in window */
	              if (op > whave) {
	                if (state.sane) {
	                  strm.msg = 'invalid distance too far back';
	                  state.mode = BAD;
	                  break top;
	                }
	
	// (!) This block is disabled in zlib defailts,
	// don't enable it for binary compatibility
	//#ifdef INFLATE_ALLOW_INVALID_DISTANCE_TOOFAR_ARRR
	//                if (len <= op - whave) {
	//                  do {
	//                    output[_out++] = 0;
	//                  } while (--len);
	//                  continue top;
	//                }
	//                len -= op - whave;
	//                do {
	//                  output[_out++] = 0;
	//                } while (--op > whave);
	//                if (op === 0) {
	//                  from = _out - dist;
	//                  do {
	//                    output[_out++] = output[from++];
	//                  } while (--len);
	//                  continue top;
	//                }
	//#endif
	              }
	              from = 0; // window index
	              from_source = s_window;
	              if (wnext === 0) {           /* very common case */
	                from += wsize - op;
	                if (op < len) {         /* some from window */
	                  len -= op;
	                  do {
	                    output[_out++] = s_window[from++];
	                  } while (--op);
	                  from = _out - dist;  /* rest from output */
	                  from_source = output;
	                }
	              }
	              else if (wnext < op) {      /* wrap around window */
	                from += wsize + wnext - op;
	                op -= wnext;
	                if (op < len) {         /* some from end of window */
	                  len -= op;
	                  do {
	                    output[_out++] = s_window[from++];
	                  } while (--op);
	                  from = 0;
	                  if (wnext < len) {  /* some from start of window */
	                    op = wnext;
	                    len -= op;
	                    do {
	                      output[_out++] = s_window[from++];
	                    } while (--op);
	                    from = _out - dist;      /* rest from output */
	                    from_source = output;
	                  }
	                }
	              }
	              else {                      /* contiguous in window */
	                from += wnext - op;
	                if (op < len) {         /* some from window */
	                  len -= op;
	                  do {
	                    output[_out++] = s_window[from++];
	                  } while (--op);
	                  from = _out - dist;  /* rest from output */
	                  from_source = output;
	                }
	              }
	              while (len > 2) {
	                output[_out++] = from_source[from++];
	                output[_out++] = from_source[from++];
	                output[_out++] = from_source[from++];
	                len -= 3;
	              }
	              if (len) {
	                output[_out++] = from_source[from++];
	                if (len > 1) {
	                  output[_out++] = from_source[from++];
	                }
	              }
	            }
	            else {
	              from = _out - dist;          /* copy direct from output */
	              do {                        /* minimum length is three */
	                output[_out++] = output[from++];
	                output[_out++] = output[from++];
	                output[_out++] = output[from++];
	                len -= 3;
	              } while (len > 2);
	              if (len) {
	                output[_out++] = output[from++];
	                if (len > 1) {
	                  output[_out++] = output[from++];
	                }
	              }
	            }
	          }
	          else if ((op & 64) === 0) {          /* 2nd level distance code */
	            here = dcode[(here & 0xffff)/*here.val*/ + (hold & ((1 << op) - 1))];
	            continue dodist;
	          }
	          else {
	            strm.msg = 'invalid distance code';
	            state.mode = BAD;
	            break top;
	          }
	
	          break; // need to emulate goto via "continue"
	        }
	      }
	      else if ((op & 64) === 0) {              /* 2nd level length code */
	        here = lcode[(here & 0xffff)/*here.val*/ + (hold & ((1 << op) - 1))];
	        continue dolen;
	      }
	      else if (op & 32) {                     /* end-of-block */
	        //Tracevv((stderr, "inflate:         end of block\n"));
	        state.mode = TYPE;
	        break top;
	      }
	      else {
	        strm.msg = 'invalid literal/length code';
	        state.mode = BAD;
	        break top;
	      }
	
	      break; // need to emulate goto via "continue"
	    }
	  } while (_in < last && _out < end);
	
	  /* return unused bytes (on entry, bits < 8, so in won't go too far back) */
	  len = bits >> 3;
	  _in -= len;
	  bits -= len << 3;
	  hold &= (1 << bits) - 1;
	
	  /* update state and return */
	  strm.next_in = _in;
	  strm.next_out = _out;
	  strm.avail_in = (_in < last ? 5 + (last - _in) : 5 - (_in - last));
	  strm.avail_out = (_out < end ? 257 + (end - _out) : 257 - (_out - end));
	  state.hold = hold;
	  state.bits = bits;
	  return;
	};


/***/ },
/* 42 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	
	
	var utils = __webpack_require__(35);
	
	var MAXBITS = 15;
	var ENOUGH_LENS = 852;
	var ENOUGH_DISTS = 592;
	//var ENOUGH = (ENOUGH_LENS+ENOUGH_DISTS);
	
	var CODES = 0;
	var LENS = 1;
	var DISTS = 2;
	
	var lbase = [ /* Length codes 257..285 base */
	  3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
	  35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258, 0, 0
	];
	
	var lext = [ /* Length codes 257..285 extra */
	  16, 16, 16, 16, 16, 16, 16, 16, 17, 17, 17, 17, 18, 18, 18, 18,
	  19, 19, 19, 19, 20, 20, 20, 20, 21, 21, 21, 21, 16, 72, 78
	];
	
	var dbase = [ /* Distance codes 0..29 base */
	  1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
	  257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
	  8193, 12289, 16385, 24577, 0, 0
	];
	
	var dext = [ /* Distance codes 0..29 extra */
	  16, 16, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21, 21, 22, 22,
	  23, 23, 24, 24, 25, 25, 26, 26, 27, 27,
	  28, 28, 29, 29, 64, 64
	];
	
	module.exports = function inflate_table(type, lens, lens_index, codes, table, table_index, work, opts)
	{
	  var bits = opts.bits;
	      //here = opts.here; /* table entry for duplication */
	
	  var len = 0;               /* a code's length in bits */
	  var sym = 0;               /* index of code symbols */
	  var min = 0, max = 0;          /* minimum and maximum code lengths */
	  var root = 0;              /* number of index bits for root table */
	  var curr = 0;              /* number of index bits for current table */
	  var drop = 0;              /* code bits to drop for sub-table */
	  var left = 0;                   /* number of prefix codes available */
	  var used = 0;              /* code entries in table used */
	  var huff = 0;              /* Huffman code */
	  var incr;              /* for incrementing code, index */
	  var fill;              /* index for replicating entries */
	  var low;               /* low bits for current root entry */
	  var mask;              /* mask for low root bits */
	  var next;             /* next available space in table */
	  var base = null;     /* base value table to use */
	  var base_index = 0;
	//  var shoextra;    /* extra bits table to use */
	  var end;                    /* use base and extra for symbol > end */
	  var count = new utils.Buf16(MAXBITS + 1); //[MAXBITS+1];    /* number of codes of each length */
	  var offs = new utils.Buf16(MAXBITS + 1); //[MAXBITS+1];     /* offsets in table for each length */
	  var extra = null;
	  var extra_index = 0;
	
	  var here_bits, here_op, here_val;
	
	  /*
	   Process a set of code lengths to create a canonical Huffman code.  The
	   code lengths are lens[0..codes-1].  Each length corresponds to the
	   symbols 0..codes-1.  The Huffman code is generated by first sorting the
	   symbols by length from short to long, and retaining the symbol order
	   for codes with equal lengths.  Then the code starts with all zero bits
	   for the first code of the shortest length, and the codes are integer
	   increments for the same length, and zeros are appended as the length
	   increases.  For the deflate format, these bits are stored backwards
	   from their more natural integer increment ordering, and so when the
	   decoding tables are built in the large loop below, the integer codes
	   are incremented backwards.
	
	   This routine assumes, but does not check, that all of the entries in
	   lens[] are in the range 0..MAXBITS.  The caller must assure this.
	   1..MAXBITS is interpreted as that code length.  zero means that that
	   symbol does not occur in this code.
	
	   The codes are sorted by computing a count of codes for each length,
	   creating from that a table of starting indices for each length in the
	   sorted table, and then entering the symbols in order in the sorted
	   table.  The sorted table is work[], with that space being provided by
	   the caller.
	
	   The length counts are used for other purposes as well, i.e. finding
	   the minimum and maximum length codes, determining if there are any
	   codes at all, checking for a valid set of lengths, and looking ahead
	   at length counts to determine sub-table sizes when building the
	   decoding tables.
	   */
	
	  /* accumulate lengths for codes (assumes lens[] all in 0..MAXBITS) */
	  for (len = 0; len <= MAXBITS; len++) {
	    count[len] = 0;
	  }
	  for (sym = 0; sym < codes; sym++) {
	    count[lens[lens_index + sym]]++;
	  }
	
	  /* bound code lengths, force root to be within code lengths */
	  root = bits;
	  for (max = MAXBITS; max >= 1; max--) {
	    if (count[max] !== 0) { break; }
	  }
	  if (root > max) {
	    root = max;
	  }
	  if (max === 0) {                     /* no symbols to code at all */
	    //table.op[opts.table_index] = 64;  //here.op = (var char)64;    /* invalid code marker */
	    //table.bits[opts.table_index] = 1;   //here.bits = (var char)1;
	    //table.val[opts.table_index++] = 0;   //here.val = (var short)0;
	    table[table_index++] = (1 << 24) | (64 << 16) | 0;
	
	
	    //table.op[opts.table_index] = 64;
	    //table.bits[opts.table_index] = 1;
	    //table.val[opts.table_index++] = 0;
	    table[table_index++] = (1 << 24) | (64 << 16) | 0;
	
	    opts.bits = 1;
	    return 0;     /* no symbols, but wait for decoding to report error */
	  }
	  for (min = 1; min < max; min++) {
	    if (count[min] !== 0) { break; }
	  }
	  if (root < min) {
	    root = min;
	  }
	
	  /* check for an over-subscribed or incomplete set of lengths */
	  left = 1;
	  for (len = 1; len <= MAXBITS; len++) {
	    left <<= 1;
	    left -= count[len];
	    if (left < 0) {
	      return -1;
	    }        /* over-subscribed */
	  }
	  if (left > 0 && (type === CODES || max !== 1)) {
	    return -1;                      /* incomplete set */
	  }
	
	  /* generate offsets into symbol table for each length for sorting */
	  offs[1] = 0;
	  for (len = 1; len < MAXBITS; len++) {
	    offs[len + 1] = offs[len] + count[len];
	  }
	
	  /* sort symbols by length, by symbol order within each length */
	  for (sym = 0; sym < codes; sym++) {
	    if (lens[lens_index + sym] !== 0) {
	      work[offs[lens[lens_index + sym]]++] = sym;
	    }
	  }
	
	  /*
	   Create and fill in decoding tables.  In this loop, the table being
	   filled is at next and has curr index bits.  The code being used is huff
	   with length len.  That code is converted to an index by dropping drop
	   bits off of the bottom.  For codes where len is less than drop + curr,
	   those top drop + curr - len bits are incremented through all values to
	   fill the table with replicated entries.
	
	   root is the number of index bits for the root table.  When len exceeds
	   root, sub-tables are created pointed to by the root entry with an index
	   of the low root bits of huff.  This is saved in low to check for when a
	   new sub-table should be started.  drop is zero when the root table is
	   being filled, and drop is root when sub-tables are being filled.
	
	   When a new sub-table is needed, it is necessary to look ahead in the
	   code lengths to determine what size sub-table is needed.  The length
	   counts are used for this, and so count[] is decremented as codes are
	   entered in the tables.
	
	   used keeps track of how many table entries have been allocated from the
	   provided *table space.  It is checked for LENS and DIST tables against
	   the constants ENOUGH_LENS and ENOUGH_DISTS to guard against changes in
	   the initial root table size constants.  See the comments in inftrees.h
	   for more information.
	
	   sym increments through all symbols, and the loop terminates when
	   all codes of length max, i.e. all codes, have been processed.  This
	   routine permits incomplete codes, so another loop after this one fills
	   in the rest of the decoding tables with invalid code markers.
	   */
	
	  /* set up for code type */
	  // poor man optimization - use if-else instead of switch,
	  // to avoid deopts in old v8
	  if (type === CODES) {
	    base = extra = work;    /* dummy value--not used */
	    end = 19;
	
	  } else if (type === LENS) {
	    base = lbase;
	    base_index -= 257;
	    extra = lext;
	    extra_index -= 257;
	    end = 256;
	
	  } else {                    /* DISTS */
	    base = dbase;
	    extra = dext;
	    end = -1;
	  }
	
	  /* initialize opts for loop */
	  huff = 0;                   /* starting code */
	  sym = 0;                    /* starting code symbol */
	  len = min;                  /* starting code length */
	  next = table_index;              /* current table to fill in */
	  curr = root;                /* current table index bits */
	  drop = 0;                   /* current bits to drop from code for index */
	  low = -1;                   /* trigger new sub-table when len > root */
	  used = 1 << root;          /* use root table entries */
	  mask = used - 1;            /* mask for comparing low */
	
	  /* check available table space */
	  if ((type === LENS && used > ENOUGH_LENS) ||
	    (type === DISTS && used > ENOUGH_DISTS)) {
	    return 1;
	  }
	
	  var i = 0;
	  /* process all codes and make table entries */
	  for (;;) {
	    i++;
	    /* create table entry */
	    here_bits = len - drop;
	    if (work[sym] < end) {
	      here_op = 0;
	      here_val = work[sym];
	    }
	    else if (work[sym] > end) {
	      here_op = extra[extra_index + work[sym]];
	      here_val = base[base_index + work[sym]];
	    }
	    else {
	      here_op = 32 + 64;         /* end of block */
	      here_val = 0;
	    }
	
	    /* replicate for those indices with low len bits equal to huff */
	    incr = 1 << (len - drop);
	    fill = 1 << curr;
	    min = fill;                 /* save offset to next table */
	    do {
	      fill -= incr;
	      table[next + (huff >> drop) + fill] = (here_bits << 24) | (here_op << 16) | here_val |0;
	    } while (fill !== 0);
	
	    /* backwards increment the len-bit code huff */
	    incr = 1 << (len - 1);
	    while (huff & incr) {
	      incr >>= 1;
	    }
	    if (incr !== 0) {
	      huff &= incr - 1;
	      huff += incr;
	    } else {
	      huff = 0;
	    }
	
	    /* go to next symbol, update count, len */
	    sym++;
	    if (--count[len] === 0) {
	      if (len === max) { break; }
	      len = lens[lens_index + work[sym]];
	    }
	
	    /* create new sub-table if needed */
	    if (len > root && (huff & mask) !== low) {
	      /* if first time, transition to sub-tables */
	      if (drop === 0) {
	        drop = root;
	      }
	
	      /* increment past last table */
	      next += min;            /* here min is 1 << curr */
	
	      /* determine length of next table */
	      curr = len - drop;
	      left = 1 << curr;
	      while (curr + drop < max) {
	        left -= count[curr + drop];
	        if (left <= 0) { break; }
	        curr++;
	        left <<= 1;
	      }
	
	      /* check for enough space */
	      used += 1 << curr;
	      if ((type === LENS && used > ENOUGH_LENS) ||
	        (type === DISTS && used > ENOUGH_DISTS)) {
	        return 1;
	      }
	
	      /* point entry in root table to sub-table */
	      low = huff & mask;
	      /*table.op[low] = curr;
	      table.bits[low] = root;
	      table.val[low] = next - opts.table_index;*/
	      table[low] = (root << 24) | (curr << 16) | (next - table_index) |0;
	    }
	  }
	
	  /* fill in remaining table entry if code is incomplete (guaranteed to have
	   at most one remaining entry, since if the code is incomplete, the
	   maximum code length that was allowed to get this far is one bit) */
	  if (huff !== 0) {
	    //table.op[next + huff] = 64;            /* invalid code marker */
	    //table.bits[next + huff] = len - drop;
	    //table.val[next + huff] = 0;
	    table[next + huff] = ((len - drop) << 24) | (64 << 16) |0;
	  }
	
	  /* set return parameters */
	  //opts.table_index += used;
	  opts.bits = root;
	  return 0;
	};


/***/ },
/* 43 */
/***/ function(module, exports) {

	'use strict';
	
	
	function ZStream() {
	  /* next input byte */
	  this.input = null; // JS specific, because we have no pointers
	  this.next_in = 0;
	  /* number of bytes available at input */
	  this.avail_in = 0;
	  /* total number of input bytes read so far */
	  this.total_in = 0;
	  /* next output byte should be put there */
	  this.output = null; // JS specific, because we have no pointers
	  this.next_out = 0;
	  /* remaining free space at output */
	  this.avail_out = 0;
	  /* total number of bytes output so far */
	  this.total_out = 0;
	  /* last error message, NULL if no error */
	  this.msg = ''/*Z_NULL*/;
	  /* not visible by applications */
	  this.state = null;
	  /* best guess about the data type: binary or text */
	  this.data_type = 2/*Z_UNKNOWN*/;
	  /* adler32 value of the uncompressed data */
	  this.adler = 0;
	}
	
	module.exports = ZStream;


/***/ },
/* 44 */
/***/ function(module, exports) {

	module.exports = {
		"name": "@hrj/doppiojvm-snapshot",
		"version": "0.4.11",
		"engine": "node >= 4.0.0",
		"license": "MIT",
		"main": "dist/release/doppio.js",
		"typings": "dist/typings/src/doppiojvm",
		"dependencies": {
			"async": "^1.5.2",
			"browserfs": "^0.5.12",
			"glob": "^7.0.0",
			"gunzip-maybe": "^1.3.1",
			"optimist": "~0.6",
			"pako": "^1.0.0",
			"rimraf": "^2.5.2",
			"source-map-support": "^0.4.0",
			"tar-fs": "^1.10.0"
		},
		"devDependencies": {
			"bfs-buffer": "^0.1.1",
			"bfs-path": "^0.1.1",
			"bfs-process": "^0.1.5",
			"cpr": "^1.0.0",
			"escodegen": "^1.8.0",
			"esprima": "^2.7.2",
			"estraverse": "^4.1.0",
			"grunt": "^1.0",
			"grunt-cli": "^1.2",
			"grunt-contrib-connect": "^1.0",
			"grunt-contrib-copy": "^1.0",
			"grunt-contrib-uglify": "^1.0",
			"grunt-karma": "^1.0",
			"grunt-lineending": "^0.2.4",
			"grunt-merge-source-maps": "^0.1.0",
			"grunt-newer": "^1.2.0",
			"grunt-ts": "^5.5",
			"grunt-webpack": "^1.0.11",
			"imports-loader": "^0.6.5",
			"jasmine-core": "^2.3.4",
			"json-loader": "^0.5.4",
			"karma": "^0.13.21",
			"karma-chrome-launcher": "^1.0",
			"karma-firefox-launcher": "^1.0",
			"karma-ie-launcher": "^1.0",
			"karma-jasmine": "^1.0",
			"karma-opera-launcher": "^1.0",
			"karma-safari-launcher": "^1.0",
			"locate-java-home": "^0.1.4",
			"semver": "^5.1.0",
			"source-map-loader": "^0.1.5",
			"typescript": "^1.8.2",
			"uglify-js": "^2.6.2",
			"underscore": "^1.8.3",
			"webpack": "^1.13.0",
			"webpack-dev-server": "^1.14.1"
		},
		"scripts": {
			"test": "grunt test",
			"prepublish": "node ./prepublish.js",
			"install": "node ./install.js",
			"appveyor-test": "grunt test-browser-appveyor"
		},
		"repository": {
			"type": "git",
			"url": "http://github.com/plasma-umass/doppio.git"
		},
		"bin": {
			"doppio": "./bin/doppio",
			"doppioh": "./bin/doppioh",
			"doppio-dev": "./bin/doppio-dev",
			"doppio-fast-dev": "./bin/doppio-fast-dev"
		}
	};

/***/ },
/* 45 */
/***/ function(module, exports) {

	'use strict';
	function text_diff(a_lines, b_lines, context) {
	    return new SequenceMatcher(a_lines, b_lines).text_diff(context);
	}
	exports.text_diff = text_diff;
	function __ntuplecomp(a, b) {
	    var mlen = Math.max(a.length, b.length);
	    for (var i = 0; i < mlen; i++) {
	        if (a[i] < b[i])
	            return -1;
	        if (a[i] > b[i])
	            return 1;
	    }
	    return a.length == b.length ? 0 : a.length < b.length ? -1 : 1;
	}
	function __dictget(dict, key, defaultValue) {
	    return dict.hasOwnProperty(key) ? dict[key] : defaultValue;
	}
	var SequenceMatcher = function () {
	    function SequenceMatcher(a, b) {
	        this.a = a;
	        this.b = b;
	        this.b2j = {};
	        for (var i = 0; i < b.length; i++) {
	            var elt = b[i];
	            if (this.b2j.hasOwnProperty(elt)) {
	                this.b2j[elt].push(i);
	            } else {
	                this.b2j[elt] = [i];
	            }
	        }
	    }
	    SequenceMatcher.prototype.find_longest_match = function (alo, ahi, blo, bhi) {
	        var a = this.a;
	        var b = this.b;
	        var b2j = this.b2j;
	        var besti = alo;
	        var bestj = blo;
	        var bestsize = 0;
	        var j2len = {};
	        for (var i = alo; i < ahi; i++) {
	            var newj2len = {};
	            var jdict = __dictget(b2j, a[i], []);
	            for (var jkey in jdict) {
	                if (jdict.hasOwnProperty(jkey)) {
	                    var j = jdict[jkey];
	                    if (j < blo)
	                        continue;
	                    if (j >= bhi)
	                        break;
	                    var k = __dictget(j2len, j - 1, 0) + 1;
	                    newj2len[j] = k;
	                    if (k > bestsize) {
	                        besti = i - k + 1;
	                        bestj = j - k + 1;
	                        bestsize = k;
	                    }
	                }
	            }
	            j2len = newj2len;
	        }
	        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
	            besti--;
	            bestj--;
	            bestsize++;
	        }
	        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
	            bestsize++;
	        }
	        return [
	            besti,
	            bestj,
	            bestsize
	        ];
	    };
	    SequenceMatcher.prototype.get_matching_blocks = function () {
	        if (this.matching_blocks != null)
	            return this.matching_blocks;
	        var la = this.a.length;
	        var lb = this.b.length;
	        var queue = [[
	                0,
	                la,
	                0,
	                lb
	            ]];
	        var matching_blocks = [];
	        while (queue.length) {
	            var qi = queue.pop();
	            var alo = qi[0];
	            var ahi = qi[1];
	            var blo = qi[2];
	            var bhi = qi[3];
	            var x = this.find_longest_match(alo, ahi, blo, bhi);
	            var i = x[0];
	            var j = x[1];
	            var k = x[2];
	            if (k) {
	                matching_blocks.push(x);
	                if (alo < i && blo < j)
	                    queue.push([
	                        alo,
	                        i,
	                        blo,
	                        j
	                    ]);
	                if (i + k < ahi && j + k < bhi)
	                    queue.push([
	                        i + k,
	                        ahi,
	                        j + k,
	                        bhi
	                    ]);
	            }
	        }
	        matching_blocks.sort(__ntuplecomp);
	        var i1 = 0, j1 = 0, k1 = 0;
	        var non_adjacent = [];
	        for (var idx = 0; idx < matching_blocks.length; idx++) {
	            var block = matching_blocks[idx];
	            var i2 = block[0];
	            var j2 = block[1];
	            var k2 = block[2];
	            if (i1 + k1 == i2 && j1 + k1 == j2) {
	                k1 += k2;
	            } else {
	                if (k1)
	                    non_adjacent.push([
	                        i1,
	                        j1,
	                        k1
	                    ]);
	                i1 = i2;
	                j1 = j2;
	                k1 = k2;
	            }
	        }
	        if (k1)
	            non_adjacent.push([
	                i1,
	                j1,
	                k1
	            ]);
	        non_adjacent.push([
	            la,
	            lb,
	            0
	        ]);
	        this.matching_blocks = non_adjacent;
	        return this.matching_blocks;
	    };
	    SequenceMatcher.prototype.get_opcodes = function () {
	        if (this.opcodes != null)
	            return this.opcodes;
	        var i = 0;
	        var j = 0;
	        var answer = [];
	        this.opcodes = answer;
	        var blocks = this.get_matching_blocks();
	        for (var idx = 0; idx < blocks.length; idx++) {
	            var block = blocks[idx];
	            var ai = block[0];
	            var bj = block[1];
	            var size = block[2];
	            var tag = '';
	            if (i < ai && j < bj) {
	                tag = 'replace';
	            } else if (i < ai) {
	                tag = 'delete';
	            } else if (j < bj) {
	                tag = 'insert';
	            }
	            if (tag)
	                answer.push([
	                    tag,
	                    i,
	                    ai,
	                    j,
	                    bj
	                ]);
	            i = ai + size;
	            j = bj + size;
	            if (size)
	                answer.push([
	                    'equal',
	                    ai,
	                    i,
	                    bj,
	                    j
	                ]);
	        }
	        return answer;
	    };
	    SequenceMatcher.prototype.text_diff = function (context) {
	        var opcodes = this.get_opcodes();
	        var diff = [];
	        var a_side = [];
	        var b_side = [];
	        var a_max_len = 0;
	        var last_seen = -1;
	        for (var op_idx = 0; op_idx < opcodes.length; op_idx++) {
	            var op = opcodes[op_idx];
	            if (op[0] === 'equal')
	                continue;
	            var ai = op[1];
	            var bi = op[3];
	            var aj = op[2] - 1;
	            var bj = op[4] - 1;
	            var start = Math.min(ai, bi);
	            var end = Math.max(aj, bj);
	            var c = '';
	            switch (op[0]) {
	            case 'delete':
	                c = ' < ';
	                break;
	            case 'insert':
	                c = ' > ';
	                break;
	            case 'replace':
	                c = ' | ';
	                break;
	            }
	            for (var i = Math.max(last_seen + 1, start - context); i < start; i++) {
	                var prefix = i + ': ';
	                if (i < this.a.length) {
	                    a_side.push(prefix + this.a[i]);
	                    a_max_len = Math.max(a_max_len, this.a[i].length + prefix.length);
	                } else {
	                    a_side.push(prefix);
	                }
	                if (i < this.b.length) {
	                    b_side.push(this.b[i]);
	                } else {
	                    b_side.push('');
	                }
	                diff.push('   ');
	            }
	            for (var i = start; i <= end; i++) {
	                var prefix = i + ': ';
	                if (i >= ai && i <= aj) {
	                    a_side.push(prefix + this.a[i]);
	                    a_max_len = Math.max(a_max_len, this.a[i].length + prefix.length);
	                } else {
	                    a_side.push(prefix);
	                }
	                if (i >= bi && i <= bj) {
	                    b_side.push(this.b[i]);
	                } else {
	                    b_side.push('');
	                }
	                diff.push(c);
	            }
	            last_seen = end;
	        }
	        for (var i = 0; i < diff.length; i++) {
	            var a = a_side[i];
	            var b = b_side[i];
	            if (a.length < a_max_len)
	                a += new Array(a_max_len - a.length + 1).join(' ');
	            diff[i] = a + diff[i] + b;
	        }
	        return diff;
	    };
	    return SequenceMatcher;
	}();
	exports.SequenceMatcher = SequenceMatcher;


/***/ },
/* 46 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var JVM = __webpack_require__(5);
	exports.JVM = JVM;
	var CLI = __webpack_require__(47);
	exports.CLI = CLI;
	var ClassFile = __webpack_require__(49);
	exports.ClassFile = ClassFile;
	var Threading = __webpack_require__(15);
	exports.Threading = Threading;
	var Long = __webpack_require__(8);
	exports.Long = Long;
	var Util = __webpack_require__(6);
	exports.Util = Util;
	var Enums = __webpack_require__(9);
	exports.Enums = Enums;
	var Interfaces = __webpack_require__(50);
	exports.Interfaces = Interfaces;
	var Monitor = __webpack_require__(25);
	exports.Monitor = Monitor;


/***/ },
/* 47 */
/***/ function(module, exports, __webpack_require__) {

	/* WEBPACK VAR INJECTION */(function(process) {'use strict';
	var option_parser_1 = __webpack_require__(48);
	var JVM = __webpack_require__(5);
	var util = __webpack_require__(6);
	var logging = __webpack_require__(17);
	var parser = new option_parser_1.OptionParser({
	    default: {
	        classpath: {
	            type: 3,
	            alias: 'cp',
	            optDesc: ' <class search path of directories and zip/jar files>',
	            desc: 'A : separated list of directories, JAR archives, and ZIP archives to search for class files.'
	        },
	        D: {
	            type: 4,
	            optDesc: '<name>=<value>',
	            desc: 'set a system property'
	        },
	        jar: {
	            type: 3,
	            stopParsing: true
	        },
	        help: {
	            alias: '?',
	            desc: 'print this help message'
	        },
	        X: { desc: 'print help on non-standard options' },
	        enableassertions: {
	            type: 2,
	            optDesc: '[:<packagename>...|:<classname>]',
	            alias: 'ea',
	            desc: 'enable assertions with specified granularity'
	        },
	        disableassertions: {
	            type: 2,
	            optDesc: '[:<packagename>...|:<classname>]',
	            alias: 'da',
	            desc: 'disable assertions with specified granularity'
	        },
	        enablesystemassertions: {
	            alias: 'esa',
	            desc: 'enable system assertions'
	        },
	        disablesystemassertions: {
	            alias: 'dsa',
	            desc: 'disable system assertions '
	        }
	    },
	    X: {
	        'int': { desc: 'interpreted mode execution only' },
	        'dump-JIT-stats': { desc: 'dump JIT statistics' },
	        log: {
	            desc: 'log level, [0-10]|vtrace|trace|debug|error',
	            type: 3
	        },
	        'vtrace-methods': {
	            type: 3,
	            optDesc: ' <java/lang/Object/getHashCode()I:...>',
	            desc: 'specify particular methods to vtrace separated by colons'
	        },
	        'list-class-cache': { desc: 'list all of the bootstrap loaded classes after execution' },
	        'dump-compiled-code': {
	            type: 3,
	            optDesc: ' <directory>',
	            desc: 'location to dump compiled object definitions'
	        },
	        'native-classpath': {
	            type: 3,
	            optDesc: ' <class search path of directories>',
	            desc: 'A : separated list of directories to search for native mathods in JS files.'
	        },
	        'bootclasspath/a': {
	            type: 1,
	            optDesc: ':<directories and zip/jar files separated by :>',
	            desc: 'append to end of bootstrap class path'
	        },
	        'bootclasspath/p': {
	            type: 1,
	            optDesc: ':<directories and zip/jar files separated by :>',
	            desc: 'prepend in front of bootstrap class path'
	        },
	        'bootclasspath': {
	            type: 1,
	            optDesc: ':<directories and zip/jar files separated by :>',
	            desc: 'set search path for bootstrap classes and resources'
	        },
	        'PrintCompilation': { desc: 'Print JIT compilation details' }
	    }
	});
	function java(args, opts, doneCb, jvmStarted) {
	    if (jvmStarted === void 0) {
	        jvmStarted = function (jvm) {
	        };
	    }
	    var parsedArgs = parser.parse(args), standard = parsedArgs['default'], nonStandard = parsedArgs['X'], jvmState;
	    opts.properties = standard.mapOption('D');
	    if (standard.flag('help', false)) {
	        return printHelp(opts.launcherName, parser.help('default'), doneCb, 0);
	    } else if (standard.flag('X', false)) {
	        return printNonStandardHelp(opts.launcherName, parser.help('X'), doneCb, 0);
	    }
	    var logOption = nonStandard.stringOption('log', 'ERROR');
	    opts.intMode = nonStandard.flag('int', false);
	    opts.dumpJITStats = nonStandard.flag('dump-JIT-stats', false);
	    if (/^[0-9]+$/.test(logOption)) {
	        logging.log_level = parseInt(logOption, 10);
	    } else {
	        var level = logging[logOption.toUpperCase()];
	        if (level == null) {
	            process.stderr.write('Unrecognized log level: ' + logOption + '.');
	            return printHelp(opts.launcherName, parser.help('default'), doneCb, 1);
	        }
	        logging.log_level = level;
	    }
	    if (nonStandard.flag('list-class-cache', false)) {
	        doneCb = function (old_done_cb) {
	            return function (result) {
	                var fpaths = jvmState.getBootstrapClassLoader().getLoadedClassFiles();
	                process.stdout.write(fpaths.join('\n') + '\n');
	                old_done_cb(result);
	            };
	        }(doneCb);
	    }
	    if (standard.flag('enablesystemassertions', false)) {
	        opts.enableSystemAssertions = true;
	    }
	    if (standard.flag('disablesystemassertions', false)) {
	        opts.enableSystemAssertions = false;
	    }
	    if (standard.flag('enableassertions', false)) {
	        opts.enableAssertions = true;
	    } else if (standard.stringOption('enableassertions', null)) {
	        opts.enableAssertions = standard.stringOption('enableassertions', null).split(':');
	    }
	    if (standard.stringOption('disableassertions', null)) {
	        opts.disableAssertions = standard.stringOption('disableassertions', null).split(':');
	    }
	    var bscl = nonStandard.stringOption('bootclasspath', null);
	    if (bscl !== null) {
	        opts.bootstrapClasspath = bscl.split(':');
	    }
	    var bsClAppend = nonStandard.stringOption('bootclasspath/a', null);
	    if (bsClAppend) {
	        opts.bootstrapClasspath = opts.bootstrapClasspath.concat(bsClAppend.split(':'));
	    }
	    var bsClPrepend = nonStandard.stringOption('bootclasspath/p', null);
	    if (bsClPrepend) {
	        opts.bootstrapClasspath = bsClPrepend.split(':').concat(opts.bootstrapClasspath);
	    }
	    if (!opts.classpath) {
	        opts.classpath = [];
	    }
	    if (standard.stringOption('jar', null)) {
	        opts.classpath.push(standard.stringOption('jar', null));
	    } else if (standard.stringOption('classpath', null)) {
	        opts.classpath = opts.classpath.concat(standard.stringOption('classpath', null).split(':'));
	    } else {
	        opts.classpath.push(process.cwd());
	    }
	    var nativeClasspath = standard.stringOption('native-classpath', null);
	    if (nativeClasspath) {
	        opts.nativeClasspath = opts.nativeClasspath.concat(nativeClasspath.split(':'));
	    }
	    jvmState = new JVM(opts, function (err) {
	        if (err) {
	            process.stderr.write('Error constructing JVM:\n');
	            process.stderr.write(err.toString() + '\n');
	            doneCb(1);
	        } else {
	            launchJvm(standard, opts, jvmState, doneCb, jvmStarted);
	        }
	    });
	    jvmState.setPrintJITCompilation(nonStandard.flag('PrintCompilation', false));
	    var vtraceMethods = nonStandard.stringOption('vtrace-methods', null);
	    if (vtraceMethods) {
	        vtraceMethods.split(':').forEach(function (m) {
	            return jvmState.vtraceMethod(m);
	        });
	    }
	    var dumpCompiledCode = nonStandard.stringOption('dumpCompiledCode', null);
	    if (dumpCompiledCode) {
	        jvmState.dumpCompiledCode(dumpCompiledCode);
	    }
	}
	function launchJvm(standardOptions, opts, jvmState, doneCb, jvmStarted) {
	    var mainArgs = standardOptions.unparsedArgs();
	    if (standardOptions.stringOption('jar', null)) {
	        jvmState.runJar(mainArgs, doneCb);
	        jvmStarted(jvmState);
	    } else if (mainArgs.length > 0) {
	        var cname = mainArgs[0];
	        if (cname.slice(-6) === '.class') {
	            cname = cname.slice(0, -6);
	        }
	        if (cname.indexOf('.') !== -1) {
	            cname = util.descriptor2typestr(util.int_classname(cname));
	        }
	        jvmState.runClass(cname, mainArgs.slice(1), doneCb);
	        jvmStarted(jvmState);
	    } else {
	        printHelp(opts.launcherName, parser.help('default'), doneCb, 0);
	    }
	}
	function printHelp(launcherName, str, doneCb, rv) {
	    process.stdout.write('Usage: ' + launcherName + ' [-options] class [args...]\n        (to execute a class)\nor  ' + launcherName + ' [-options] -jar jarfile [args...]\n        (to execute a jar file)\nwhere options include:\n' + str);
	    doneCb(rv);
	}
	function printNonStandardHelp(launcherName, str, doneCb, rv) {
	    process.stdout.write(str + '\n\nThe -X options are non-standard and subject to change without notice.\n');
	    doneCb(rv);
	}
	module.exports = java;
	
	/* WEBPACK VAR INJECTION */}.call(exports, __webpack_require__(3)))

/***/ },
/* 48 */
/***/ function(module, exports) {

	'use strict';
	var PrefixParseResult = function () {
	    function PrefixParseResult(result, unparsedArgs) {
	        if (unparsedArgs === void 0) {
	            unparsedArgs = [];
	        }
	        this._result = result;
	        this._unparsedArgs = unparsedArgs;
	    }
	    PrefixParseResult.prototype.unparsedArgs = function () {
	        return this._unparsedArgs;
	    };
	    PrefixParseResult.prototype.flag = function (name, defaultVal) {
	        var val = this._result[name];
	        if (typeof val === 'boolean') {
	            return val;
	        }
	        return defaultVal;
	    };
	    PrefixParseResult.prototype.stringOption = function (name, defaultVal) {
	        var val = this._result[name];
	        if (typeof val === 'string') {
	            return val;
	        }
	        return defaultVal;
	    };
	    PrefixParseResult.prototype.mapOption = function (name) {
	        var val = this._result[name];
	        if (typeof val === 'object') {
	            return val;
	        }
	        return {};
	    };
	    return PrefixParseResult;
	}();
	exports.PrefixParseResult = PrefixParseResult;
	function getOptName(prefix, name) {
	    return prefix !== 'default' ? '' + prefix + name : name;
	}
	var OptionParser = function () {
	    function OptionParser(desc) {
	        var _this = this;
	        this._parseMap = {};
	        this._prefixes = [];
	        this._mapArgs = [];
	        this._rawDesc = desc;
	        this._prefixes = Object.keys(desc);
	        this._prefixes.forEach(function (prefix) {
	            var opts = desc[prefix];
	            var optNames = Object.keys(opts);
	            optNames.slice(0).forEach(function (optName) {
	                var option = opts[optName];
	                if (!option.type) {
	                    option.type = 0;
	                }
	                if (option.type === 4) {
	                    _this._mapArgs.push(optName);
	                }
	                option.prefix = prefix;
	                option.name = optName;
	                _this._parseMap[getOptName(prefix, optName)] = option;
	                if (option.alias) {
	                    optNames.push(option.alias);
	                    _this._parseMap[getOptName(prefix, option.alias)] = option;
	                }
	            });
	        });
	    }
	    OptionParser.prototype.parse = function (argv) {
	        var _this = this;
	        var result = {}, ptr = 0, len;
	        this._prefixes.forEach(function (prefix) {
	            return result[prefix] = {};
	        });
	        argv = argv.map(function (arg) {
	            return arg.trim();
	        }).filter(function (arg) {
	            return arg !== '';
	        });
	        len = argv.length;
	        while (ptr < len) {
	            var arg = argv[ptr];
	            if (arg[0] === '-') {
	                arg = arg.slice(1);
	                var opt;
	                if (opt = this._parseMap[arg]) {
	                    switch (opt.type) {
	                    case 0:
	                    case 2:
	                        result[opt.prefix][opt.name] = true;
	                        break;
	                    case 3:
	                    case 1:
	                        ptr++;
	                        if (ptr < len) {
	                            result[opt.prefix][opt.name] = argv[ptr];
	                        } else {
	                            throw new Error('-' + arg + ' requires an argument.');
	                        }
	                        break;
	                    case 4:
	                        break;
	                    default:
	                        throw new Error('INTERNAL ERROR: Invalid parse type for -' + arg + '.');
	                    }
	                } else if (this._mapArgs.filter(function (mapArg) {
	                        if (arg.slice(0, mapArg.length) === mapArg) {
	                            opt = _this._parseMap[mapArg];
	                            return true;
	                        }
	                        return false;
	                    }).length > 0) {
	                    var mapping = arg.slice(opt.name.length), map = result[opt.prefix][opt.name];
	                    if (!map) {
	                        map = result[opt.prefix][opt.name] = {};
	                    }
	                    var eqIdx = mapping.indexOf('=');
	                    if (eqIdx !== -1) {
	                        map[mapping.slice(0, eqIdx)] = mapping.slice(eqIdx + 1);
	                    } else {
	                        map[mapping] = '';
	                    }
	                } else if (arg.indexOf(':') !== -1 && (opt = this._parseMap[arg.slice(0, arg.indexOf(':'))])) {
	                    if (opt.type === 1 || opt.type === 2) {
	                        result[opt.prefix][opt.name] = arg.slice(arg.indexOf(':') + 1);
	                    } else {
	                        throw new Error('Unrecognized option: -' + arg);
	                    }
	                } else {
	                    throw new Error('Unrecognized option: -' + arg);
	                }
	                if (opt.stopParsing) {
	                    ptr++;
	                    break;
	                }
	            } else {
	                break;
	            }
	            ptr++;
	        }
	        var unparsedArgs = argv.slice(ptr), rv = {};
	        Object.keys(result).forEach(function (prefix) {
	            rv[prefix] = new PrefixParseResult(result[prefix], unparsedArgs);
	        });
	        return rv;
	    };
	    OptionParser.prototype.help = function (prefix) {
	        return _showHelp(this._rawDesc[prefix], prefix === 'default' ? '' : prefix);
	    };
	    return OptionParser;
	}();
	exports.OptionParser = OptionParser;
	function printCol(value, width) {
	    var rv = value;
	    var padding = width - value.length;
	    while (padding-- > 0) {
	        rv += ' ';
	    }
	    return rv;
	}
	function _showHelp(category, prefix) {
	    var combinedKeys = {};
	    var keyColWidth = 13;
	    Object.keys(category).forEach(function (key) {
	        var opt = category[key];
	        if (opt.stopParsing) {
	            return;
	        }
	        var keys = [key];
	        if (opt.alias != null) {
	            keys.push(opt.alias);
	        }
	        var ckey;
	        if (opt.optDesc) {
	            ckey = keys.map(function (key) {
	                return '-' + prefix + key + opt.optDesc;
	            }).join('\n');
	        } else {
	            ckey = keys.map(function (key) {
	                return '-' + prefix + key;
	            }).join(' | ');
	        }
	        combinedKeys[ckey] = opt;
	    });
	    return Object.keys(combinedKeys).map(function (key) {
	        var option = combinedKeys[key];
	        if (option.optDesc) {
	            var cols = key.split('\n');
	            var rv = cols.map(function (row) {
	                return '    ' + row;
	            });
	            return rv.join('\n') + '\n                  ' + option.desc;
	        } else {
	            var colText = printCol(key, keyColWidth);
	            if (colText.length === keyColWidth) {
	                return '    ' + colText + ' ' + option.desc;
	            } else {
	                return '    ' + colText + '\n                  ' + option.desc;
	            }
	        }
	    }).join('\n') + '\n';
	}


/***/ },
/* 49 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	function __export(m) {
	    for (var p in m)
	        if (!exports.hasOwnProperty(p))
	            exports[p] = m[p];
	}
	var ConstantPool = __webpack_require__(23);
	exports.ConstantPool = ConstantPool;
	var Attributes = __webpack_require__(12);
	exports.Attributes = Attributes;
	__export(__webpack_require__(21));
	__export(__webpack_require__(11));
	__export(__webpack_require__(20));
	__export(__webpack_require__(26));


/***/ },
/* 50 */
/***/ function(module, exports) {

	'use strict';


/***/ },
/* 51 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';
	var Assert = __webpack_require__(13);
	exports.Assert = Assert;
	var Logging = __webpack_require__(17);
	exports.Logging = Logging;
	var Difflib = __webpack_require__(45);
	exports.Difflib = Difflib;


/***/ }
/******/ ])
});
;
//# sourceMappingURL=doppio.js.map