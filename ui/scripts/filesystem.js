
$(document).ready(function(){
    //init. tooltips
    $('[data-toggle="tooltip"]').tooltip();   
});


userContext =  null;

requireSignedIn = function(callback) {
    if (userContext == null)  { 
        $('#modal-title').html('Who is this?')
        $('#modal-body').html('<p>Please sign in to continue.</p>')
        $('#modal').modal("show");   
    }
    else
        callback();
}

var url;
var ae = document.createElement("a");
document.body.appendChild(ae);
ae.style = "display: none"; 

function openItem(name, data) {
    if(url != null){
        window.URL.revokeObjectURL(url);
    }
    
    var blob =  new Blob([data], {type: "octet/stream"});		
    url = window.URL.createObjectURL(blob);
    ae.href = url;
    ae.download = name;
    ae.click();
}

var File = React.createClass({
        
        glyphClass: function() {
                var className = "glyphicon "; 
                className += this.props.isdir ? "glyphicon-folder-open" : "glyphicon-file";
                return className;
        },

        renderGrid: function() {
                var glyphClass = this.glyphClass();

                return (<div className="col-xs-6 col-md-3">
                                <a id={this.props.id} onClick={this.props.onClick}>
                                <span style={{fontSize:"3.5em"}} className={glyphClass}/>
                                </a>
                                <div className="caption">
                                <h4 className="heading">{this.props.name}</h4>
                                </div>
                                </div>);
        },

        componentDidMount: function() {
                var selector = "#"+this.props.id;
                $(selector).contextmenu({
                        target: '#context-menu',
                        onItem: function(context, evt) {
                                var selected  =  evt.target.text.trim();
                                console.log("on item "+ selected);
                                if  (selected  == "Rename") {
                                       this.rename(); 
                                } else if (selected  == "Remove")  {
				       this.remove();
                                } else if (selected  == "Open")  {
                                        this.props.onClick();
                                }  else 
                                        console.log("no  action defined for context menu item "+ selected);    
                        }.bind(this)
                });
        },

        remove: function() {
	    new RetrievedFilePointer(this.writerFilePointer(), this.props.retrievedFilePointer.fileAccess).remove(userContext).then(function(){
		this.props.browser.loadFilesFromServer();
	    }.bind(this));
	},

        rename: function() {
                const newName = prompt("Specify updated name for "+ this.props.name);
                if (newName == null)
                        return;
                //get current props
                const filePointer = this.props.retrievedFilePointer.filePointer;
                const baseKey = filePointer.baseKey;
                const fileAccess = this.props.retrievedFilePointer.fileAccess;

                const key = fileAccess.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey; 
                const currentProps = fileAccess.getFileProperties(key);

                const newProps = new FileProperties(newName, currentProps.size);

                fileAccess.rename(this.writerFilePointer(), newProps, userContext).then(function() {
                    //now reload the view
                    this.props.browser.loadFilesFromServer();
		}.bind(this));
        },
    
        writerFilePointer: function() {
                var  entryPointFilePointer;
                try  {
                    entryPointFilePointer = this.props.browser.entryPoint().filePointer;
                } catch(err)  {
                    return this.props.retrievedFilePointer.filePointer;
                }

                const current = this.props.retrievedFilePointer.filePointer;

                return new ReadableFilePointer(
                                current.owner,
                                entryPointFilePointer.writer,
                                current.mapKey,
                                current.baseKey);
        },

        renderList: function() {
                //var dateString =  new Date(this.props.time*1000).toGMTString()
                var glyphClass = this.glyphClass();
                var spanStyle = {fontSize:"1.5em"}; 
                
                console.log("rendering list file with props "+ this.props.name);
                return (<tr id={this.props.id}>
                                <td>
                                <a onClick={this.props.onClick}><span style={{fontSize:"1.5em", paddingRight:"10px"}} className={glyphClass}/>{this.props.name}</a>
                                </td>
                                <td>{File.sizeString(this.props.size)}</td>
                                </tr>);
        },

        render: function() {
                console.log("rendering with grid? "+ this.props.gridView);

                return this.props.gridView ? this.renderGrid() : this.renderList();
        }

});


File.id = function() {return (Math.pow(2,31) * Math.random())|0; }

File.timeSort = function(left, right){return left.time - right.time;} 

File.sizeSort = function(left, right){return left.size - right.size;} 

File.pathSort = function(left, right){return left.path.localeCompare(right.path);} 

File.sizes = [{count : 1, unit:"bytes"}, {count : 1024, unit: "kB"}, {count: 1048576 , unit : "MB"}, {count: 1073741824, unit:"GB" } ]

File.sizeString =  function(sizeBytes){
        var iUnit=0;
        var count=0;
        for (iUnit=0; iUnit < File.sizes.length;iUnit++) {
                count = sizeBytes / File.sizes[iUnit].count;
                if (count < 1024)
                        break;
        }
        return "" + (count|0) +" "+ File.sizes[iUnit].unit;   
}







function hideLogin() {
        document.getElementById("login-form").style.display= "none";
}
function showUsername() {
        document.getElementById("login-form").style.display= "none";
}

function showLogin() {
        $('#login-form').style.display= "block";
}

function getParent(path, onSuccess) {
        $.ajax({
                url: buildGetParentUrl(path),
                dataType: 'json',
                cache: false,
                success: onSuccess,
                error: function(xhr, status, err) {
                        console.error(this.props.url, status, err.toString());
                }.bind(this)
        });
}

function updateNavbarPath(path) {
        var elem  = document.getElementById("pathSpan");
        elem.innerHTML = '<span class="glyphicon glyphicon-chevron-right"/>' +path;
}

var Browser = React.createClass({
        getInitialState: function() {
                return {files: [],
                        gridView: true,
                        sort: File.pathSort,
                        retrievedFilePointerPath: []
                };
        },
            
        entryPoint: function() {
            if (this.state.retrievedFilePointerPath.length == 0)
                    throw "No entry-point!";
            return this.state.retrievedFilePointerPath[0];
        },

        entryPointWriterKey: function() {
            return this.entryPoint().filePointer.writer;
        },

        lastRetrievedFilePointer: function() {
            if (this.state.retrievedFilePointerPath.length == 0)
                    throw "No retrived file-pointers!";
            return this.state.retrievedFilePointerPath.slice(-1)[0];
        },

        currentPath : function() {
                return  "/"+ this.state.retrievedFilePointerPath.map(function(e) {
                    const parentKey = e.fileAccess.getParentKey(e.filePointer.baseKey);
                    const props = e.fileAccess.getFileProperties(parentKey);
                    return props.name;
                }).join("/");
        },

        loadFilesFromServer: function() {
                if (typeof(userContext) == "undefined" || userContext == null)
                        return;
                const callback = function(children) {
                            const files = children.map(function(retrievedFilePointer) {
            			    	var baseKey = retrievedFilePointer.filePointer.baseKey;
                                const fileAccess =  retrievedFilePointer.fileAccess;
			                    const parentKey =  fileAccess.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey;
            	    			const props = fileAccess.getFileProperties(parentKey);
	    	            		const isDir = fileAccess.isDirectory();
			                	const name  = props.name;
                				const size = props.size;
                                const onClick = isDir ? function() {
                                    this.addToPath(retrievedFilePointer);
                                }.bind(this) :  function() {
                                    //download the chunks and reconstruct the original bytes
                                    //get the data
                                    const baseKey = retrievedFilePointer.filePointer.baseKey;
                                    retrievedFilePointer.fileAccess.retriever.getFile(userContext, baseKey).then(function(buf) {
                                        console.log("reading "+ name + " with size "+ size);
			                            return buf.read(size).then(function(originalData) {
                                            openItem(name, originalData);
                                        });
                                    });
                              }.bind(this);

                  			  return {
                                       onClick: onClick,
                                       name: name,
                                       isDir: isDir,
                                       size: size,
                                       filePointer: retrievedFilePointer
			            	    }
                            }.bind(this));

                            this.setState({
                                files: files, 
                                sort: this.state.sort,  
                                gridView: this.state.gridView, 
                                retrievedFilePointerPath: this.state.retrievedFilePointerPath 
                            }, function() {
                                    updateNavbarPath(this.currentPath());
                            }.bind(this)); 
                }.bind(this);

                const isEmpty =  this.state.retrievedFilePointerPath.length == 0;
                if (isEmpty) {
                    userContext.getRoots().then(function(roots) {
                        const children = roots.map(function(root) {
                            const entryPoint = root[0];
                            const fileAccess = root[1];

                            const filePointer = entryPoint.pointer;
                            const rootDirKey = filePointer.baseKey;
		                    const parentKey = fileAccess.getParentKey(rootDirKey);

                            return new RetrievedFilePointer(filePointer, fileAccess);
                        });

                        callback(children);
                    }.bind(this));
                }
                else {
                    const filePointer = this.lastRetrievedFilePointer().filePointer;
                    const fileAccess = this.lastRetrievedFilePointer().fileAccess;
                    const rootDirKey = filePointer.baseKey;

                    fileAccess.getChildren(userContext, rootDirKey).then(function(children) {
                            callback(children);
                    }.bind(this));
                }    
        },
        onParent: function() {
                    requireSignedIn(function()  {
                    if (this.state.retrievedFilePointerPath.length == 0) {
                            alert("Cannot go back from "+ this.currentPath());
                            return;
                    }
                    this.state.retrievedFilePointerPath = this.state.retrievedFilePointerPath.slice(0,-1);
                    this.loadFilesFromServer();
                }.bind(this));
        },

        onBack : function() {
                requireSignedIn(function()  {
                    //TODO something more appropriate
                    this.onParent();
                }.bind(this));
        },

        onUpload: function() {
                requireSignedIn(function()  {
                $('#uploadInput').click();
                });
        },

        alternateView: function() {
                var updatedView = !  this.state.gridView;

                this.setState({
                        files: this.state.files, 
                        sort: this.state.sort,  
                        retrievedFilePointerPath: this.state.retrievedFilePointerPath,
                        gridView: updatedView
                });
        },


        uploadFile: function() {
                return function (evt) {
                        if (userContext == null) {
                            alert("Please sign in first!");
                            return false;
                        }
                        var readFile = evt.target.files[0];
                        var name = readFile.name;
                        var filereader = new FileReader();
                        filereader.file_name = readFile.name;
                        const browser = this;
                        filereader.onload = function(){
                            const data = new Uint8Array(this.result);
                            const filename = this.file_name;
                            console.log("upload file-name " + filename +" with data-length "+ data.length);
                                
            
                            const fileKey = SymmetricKey.random();
                            const rootRKey = browser.lastRetrievedFilePointer().filePointer.baseKey;
                            const owner = browser.lastRetrievedFilePointer().filePointer.owner;
                            const dirMapKey = browser.lastRetrievedFilePointer().filePointer.mapKey;
                            const writer = browser.entryPoint().filePointer.writer;
                            const dirAccess =  browser.lastRetrievedFilePointer().fileAccess;
			    const parentLocation = new Location(owner, writer, dirMapKey);
			    const dirParentKey = dirAccess.getParentKey(rootRKey);

                            const file = new FileUploader(filename, data, fileKey, parentLocation, dirParentKey);
                            return file.upload(userContext, owner, writer).then(function(fileLocation) {
                                dirAccess.addFile(fileLocation, rootRKey, fileKey);
                                return userContext.uploadChunk(dirAccess, [], owner, writer, dirMapKey);
                            }).then(function() {
                                browser.loadFilesFromServer();
                            });
                        };
                        filereader.readAsArrayBuffer(readFile);
                }.bind(this);
        },
                                
        loginOnEnter: function(event) {
                if (event.keyCode === 13) {
                        this.login();
                        return false;
                }
        },

        login: function() {
                var username = document.getElementById("login-user-input").value;
                var password = document.getElementById("login-password-input").value;
                var ctx = null;
                return generateKeyPairs(username, password).then(function(user) {
                        var dht = new DHTClient();
                        var corenode = new CoreNodeClient();
                        ctx = new UserContext(username, user, dht, corenode);    
                        return  ctx.isRegistered();
                }).then(function(registered) {
                        if  (! registered) {
                                console.log("Now registering  user "+ username);
                                return ctx.register();
                        }
                        else   
                                return Promise.resolve(true);
                }).then(function(isRegistered) {
                        if  (! isRegistered) 
                                reject(Error("Could not register user "+ username));
                        console.log("Verified user "+ username +" is registered");
                        userContext = ctx;  
                }).then(function() {
                    return userContext.getRoots();
                }).then(function(roots) {
		            if (roots.length > 0)
            			return Promise.resolve(true);
                    console.log("adding root entries");
                    var milliseconds = (new Date).getTime();
                    return Promise.all(
                        [1].map(function(num) {
                            return userContext.createEntryDirectory("test_"+milliseconds+"_"+num);
                        })
                    );
                }).then(function() {
                    const displayName = userContext.username;
                    $("#login-form").html("<button class=\"btn btn-default\">"+displayName+"</button>");
                }).then(this.loadFilesFromServer);

        },

        componentDidMount: function() {
                this.loadFilesFromServer();
                var backButton = document.getElementById("backButton");
                backButton.onclick = this.onBack;
                var uploadButton = document.getElementById("uploadButton");
                uploadButton.onclick = this.onUpload;
                var parentButton = document.getElementById("parentButton");
                parentButton.onclick = this.onParent;
                var uploadInput = document.getElementById("uploadInput"); 
                uploadInput.addEventListener("change", this.uploadFile(), false);
                var mkdirButton = document.getElementById("mkdirButton"); 
                mkdirButton.onclick = this.mkdir;
                var alternateViewButton = document.getElementById("alternateViewButton"); 
                alternateViewButton.onclick = this.alternateView; 
                var loginButton = document.getElementById("loginButton");
                loginButton.onclick = this.login; 
                var passwordInput= document.getElementById("login-password-input");
                passwordInput.onkeypress=this.loginOnEnter;
        },

        updateSort: function(sort) {
                var files  = this.state.files
                        var lastSort = this.state.sort;
                if  (lastSort == sort)  
                        files = files.reverse();
                else 
                        files = files.sort(sort);

                this.setState({files: files, sort: sort,  paths: this.state.paths, gridView: this.state.gridView});
        },

        timeSort: function() {
                this.updateSort(File.timeSort);
        },
        pathSort: function() {
                this.updateSort(File.pathSort);
        },
        sizeSort: function() {
                this.updateSort(File.sizeSort);
        },
        updatePath: function(path) {
                this.loadFilesFromServer(path);
        },

        addToPath: function(retrievedFilePointer) {
                const path= this.state.retrievedFilePointerPath.slice();//copy
                path.push(retrievedFilePointer);
                this.setState({
                        files: this.state.files, 
                        sort: this.state.sort,  
                        gridView: this.state.gridView, 
                        retrievedFilePointerPath: path
                },
                this.loadFilesFromServer
                );
        },

        updateDir: function(entryPoint, dirAccess) {
                this.loadFilesFromServer(path);
        },

        getContent: function(path) {
                var url = buildGetContentUrl(path);
                location.href=url;
        },

        mkdir: function() {
                requireSignedIn(function()  {
                    const newFolderName = prompt("Enter new folder name");
                    if (newFolderName == null)
                        return;
                
                    const isEmpty =  this.state.retrievedFilePointerPath.length == 0;
                    if (isEmpty) {
                        //create new root-dir
                        console.log("creating new entry-point "+ newFolderName);
                        return userContext.createEntryDirectory(newFolderName)
                            .then(this.loadFilesFromServer);
                    }
                    else {
                        console.log("creating new sub-dir "+ newFolderName);
                        const lastRetrievedFilePointer =  this.lastRetrievedFilePointer();
		                const dirPointer = lastRetrievedFilePointer.filePointer;
		                const dirAccess = lastRetrievedFilePointer.fileAccess;
    		            var rootDirKey = dirPointer.baseKey;
	    			    return dirAccess.mkdir(newFolderName, userContext, this.entryPointWriterKey(), dirPointer.mapKey, rootDirKey)
                            .then(this.loadFilesFromServer);
                    }
                }.bind(this));
        },

        render: function() {
                const files = this.state.files.map(function(f) {
                            return (<File id={File.id()} gridView={this.state.gridView} onClick={f.onClick} name={f.name} isdir={f.isDir} size={f.size} browser={this} retrievedFilePointer={f.filePointer}/>)
                }.bind(this)); 

                const gridGlyph = "glyphicon glyphicon-th-large";
                const listGlyph = "glyphicon glyphicon-list";
                const element = document.getElementById("altViewSpan");
                const className = this.state.gridView ? listGlyph : gridGlyph;
                element.className = className;
                var layout = null;
                var  contextMenu = this.props.isdir ? (<div id="context-menu">
                                <ul className="dropdown-menu" role="menu">
                                <li><a tabIndex="-1">Rename</a></li>
                                <li className="divider"></li>
                                <li><a tabIndex="-1">Remove</a></li>
                                </ul>
                                </div>) : (<div id="context-menu">
                                <ul className="dropdown-menu" role="menu">
                                <li><a tabIndex="-1">Open</a></li>
                                <li className="divider"></li>
                                <li><a tabIndex="-1">Rename</a></li>
                                <li className="divider"></li>
                                <li><a tabIndex="-1">Remove</a></li>
                                </ul>
                                </div>);

                layout = null; 
                if (this.state.gridView) 
                        return (<div>
                                        {files}
                                        {contextMenu}
                                        </div>)

                                const sortGlyph = "glyphicon glyphicon-sort";

                return (<div>
                                <table className="table table-responsive table-striped table-hover">
                                <thead><tr>
                                <th><button onClick={this.pathSort} className="btn btn-default"><span className={sortGlyph}/>Path</button></th>
                                <th><button onClick={this.sizeSort} className="btn btn-default"><span className={sortGlyph}/>Size</button></th>
                                </tr></thead>
                                <tbody>
                                {files}
                                </tbody>
                                </table>
                                {contextMenu}
                                </div>)
        }
});

React.render(
                <Browser/>,
                document.getElementById('content')
            );
