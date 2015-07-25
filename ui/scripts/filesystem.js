userContext =  null;


//entrypoint.owner //top level name 
//ReadableFilePointer.owner
//
//get dir children
//get entry point readers/writers/owner
//get parent of dir
//
//get file content
//get 
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
                                        alert("rename " + selected);
                                } else if (selected  == "Remove")  {
                                        alert("remove " + selected);
                                } else if (selected  == "Open")  {
                                        openItem(this);
                                }  else 
                                        console.log("no  action defined for context menu item "+ selected);    
                        }.bind(this)
                });
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
var url;
var ae = document.createElement("a");
document.body.appendChild(ae);
ae.style = "display: none"; 

function openItem(props) {
    //var path =  props.path;
    var name = "something"; //props.name;
    
    
    if(url != null){
        window.URL.revokeObjectURL(url);
    }
    var data = new Uint8Array(2);
    data[0] = 64;
    data[1] = 70;
    var blob =  new Blob([data], {type: "octet/stream"});		
    url = window.URL.createObjectURL(blob);
    ae.href = url;
    ae.download = name;
    ae.click();
}
function buildGetChildrenUrl(path) {
        return  "children?path="+path;
}

function buildGetParentUrl(path) {
        return  "parent?path="+path;
}

function buildRenameUrl(path, name) {
        return  "rename?path="+path+"&name="+name;
}

function buildRemoveUrl(path) {
        return  "remove?path="+path;
}
function buildGetContentUrl(path) {
        return "content?path="+path;
}

function buildUploadUrl(path, name) {
        return "upload?path="+path+"&name="+name;
}

function buildMkdirUrl(path, name) {
        return "mkdir?path="+path+"&name="+name;
}

function hideLogin() {
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
function readFileCallback(data, name){    
    console.log("in upload name=" + name);
}

var Browser = React.createClass({
        getInitialState: function() {
                return {files: [],
                        gridView: true,
                        sort: File.pathSort,
                        retrievedFilePointerPath: []
                };
        },
            
    
        loadFilesFromServer: function() {
                console.log("Loading files with  user context "+ userContext +" with type "+ typeof(userContext));
                if (typeof(userContext) == "undefined" || userContext == null)
                        return;
                const isEmpty =  this.state.retrievedFilePointerPath.length == 0;
               
                var roots = null; 
                if (isEmpty) {
                    userContext.getRoots().then(function(roots) {
                        const files = roots.map(function(root) {
                            const entryPoint = root[0];
                            const fileAccess = root[1];

                            const filePointer = entryPoint.pointer;
                            const rootDirKey = filePointer.baseKey;
		                    const parentKey = fileAccess.getParentKey(rootDirKey);

                            const retrievedFilePointer = new RetrievedFilePointer(filePointer, fileAccess);
                            
                            const props = fileAccess.getFileProperties(parentKey);
                            const name  = props.name;
                            const size = props.getSize();
                            const isDir = fileAccess.isDirectory();
                            const id = File.id();
                            console.log("name "+ name + " with size "+ size); 
                            const onClick = isDir ? function() {
                                console.log("clicked on dir "+ name); 
                                this.addToPath(retrievedFilePointer);
                            }.bind(this) :  function() {
                                openItem(this);
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
                        retrievedFilePointerPath: [] 
                      }); 
                    }.bind(this));
                } 
                else {
                    const lastRetrievedFilePointer =  this.state.retrievedFilePointerPath.slice(-1)[0];
                    const filePointer = lastRetrievedFilePointer.filePointer;
                    const rootDirKey = filePointer.baseKey;

                    const fileAccess = lastRetrievedFilePointer.fileAccess;
                    const parentKey = fileAccess.getParentKey(rootDirKey);
                    const files = fileAccess.getChildren(userContext, rootDirKey).map(function(retrievedFilePointer) {

                    const props = retrievedFilePointer.fileAccess.getFileProperties(parentKey);
                    const name  = props.name;
                    const size = props.getSize();
                    const isDir = fileAccess.isDirectory();
                    const id = File.id();
                            
                    return {
                                    onClick: onClick,
                                    name: name,
                                    isDir: isDir,
                                    size: size,
                                    filePointer: retrievedFilePointer
                        }
                    });
                      
                    this.setState({
                        files: files, 
                        sort: this.state.sort,  
                        gridView: this.state.gridView, 
                        retrievedFilePointerPath: [] 
                      }); 

                }
                /*
                 * Browser {
                 *  [retrievedFilePointer] 
                 * }
                 * <File {dir/file-access}}
                 *if user context null bomb out
                 *
                 *if retrievedFilePointerPath is empty 
                 *      get roots
                 *      retrievedFilePointerPath = [entry-point -> rfp] 
                 *      files <- subfiles/subfolders 
                 *
                 *else
                 *      get children of last elem in  retrievedFilePointerPath 
                 *      files <- subfiles/subfolders
                 *
                 *
                //
                if (userContext == null)
                        return;

                if (entryPoint ==  null) {
                    //get roots
                    userContext.getRoots().then(function(roots) {
                    const files = []
                    for (var i=0; i < roots.length; i++) {
                        const entryPoint = roots[i][0];
                        const rootDirKey = entryPoint.pointer.baseKey;
                        const fileAccess = roots[i][1];
                        const props = fileAccess.getFileProperties(parentKey);
                        const name  = props.name;
                        const length  = props.length();
                        const id  =  File.id(name);
                        
                        var onClick = isdir ? function(event){
                                this.updatePath();
                        }.bind(this) :
                                function(event) {
                                        this.getContent(f.path);
                                }.bind(this);

                                        <File id={id} gridView={this.state.gridView{ }}>
                                        )
                    }
                }




                return userContext.getRoots().then(function(roots) {
                console.log("Found "+ roots.length +"  roots here.");
                for (var i=0; i < roots.length; i++) {
                        //roots : [[entry-point, fileaccess]]
                        const entryPoint = roots[i][0];
                        const rootDirKey = entryPoint.pointer.baseKey;
                        const fileAccess = roots[i][1];
                        const isDir = fileAccess.isDir();
                        if (fileAccess == null)
                                continue;
		                const parentKey = fileAccess.getParentKey(rootDirKey);
                        const props = fileAccess.getFileProperties(parentKey);
                        const name  = props.name;
                        const length  = props.length();

                        //if  dir-access
                        //
                        // to get  children
                        fileAccess.getChildren(userContext, rootDirKey);
                        //
                        // fileAccess.files
                        // fileAccess.subfolders
                        //
                        //const subfolderlink  = subfolders[i]
                        //const subfolder_baseKey = subfolderlink.target(rootDirKey);
                        //const subfolder_metadatablob_location_0 = subfolderlink.targetLocation(rootDirKey);
                        //
                        //const subfiles_fileaccesses  =  userContext.retrieveMetadata(fileAccess.files, fileAccess.subfolders2files.target(rootDirKey));
                        //const subfolder_diraccesses  =  userContext.retrieveMetadata(fileAccess.subfolders, rootDirKey);
                        //
                        //
                        //const subfolder_readableFilePointer = new ReadableFilePointer(
                        // subfolder_metadatablob_location_0.owner,
                        // subfolder_metadatablob_location_0.writer,
                        // subfolder_metadatablob_location_0.mapKey,
                        // subfolder_baseKey
                        //);
                        //
                        //
                        //
                        //
                        //
                        //
                        //
                        //
                        console.log("Found root-dir with name "+ name + ".");
                   }
                });
                        

                        /*
                        //[[SymmetricLocationLink, FileAccess]]
                        return userContext.retrieveAllMetadata(dir.files, rootDirKey).then(function(files) {
                                for (var i=0; i < files.length; i++) {
                                        var baseKey = files[i][0].target(rootDirKey);
                                        var fileBlob = files[i][1];
                                        // download fragments in chunk
                                        var fileProps = fileBlob.getFileProperties(baseKey);
                                        console.log("found "+ JSON.stringify(fileProps));
                                }
                        });
                        */
        },

        reloadFilesFromServer: function() {this.loadFilesFromServer(this.currentPath())},

        currentPath : function() {
                return this.state.retrievedFilePointerPath.map(function(e) {
                    //TODO
                    return "TODO"; 
                }).join("/");
        },

        onBack : function() {
                if (this.state.paths.length <2) {
                        alert("Cannot go back from "+ this.currentPath());
                        return;
                }
                this.state.paths = this.state.paths.slice(0,-1);
                this.loadFilesFromServer(this.currentPath());
        },

        onUpload: function() {
                $('#uploadInput').click();
        },

        onParent: function() {
                var onSuccess = function(data) {
                        var parentPath = data.path;
                        this.updatePath(parentPath);
                }.bind(this);
                getParent(this.currentPath(), onSuccess);
        },

        alternateView: function() {
                var updatedView = !  this.state.gridView;

                this.setState({
                        files: this.state.files, 
                        sort: this.state.sort,  
                        retrievedFilePointerPath: this.retrievedFilePointerPath,
                        gridView: updatedView
                });
        },


        uploadFile: function() {
                return function (evt) {
                        var path = this.currentPath();
                        var readFile = evt.target.files[0];
                        var name = readFile.name;
                        console.log(readFile);
                        var filereader = new FileReader();
                        filereader.file_name = readFile.name;
                        filereader.onload = function(){readFileCallback(this.result, this.file_name)};
                        filereader.readAsArrayBuffer(readFile);

                        
                                /*
                        var formData = new FormData();
                        formData.append("file", readFile, name);

                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', buildUploadUrl(path, name) , true);
                        xhr.onreadystatechange=function()
                        {
                                if (xhr.readyState != 4)
                                        return;

                                if (xhr.status == 200){ 
                                        alert("Successfully uploaded file "+ name +" to "+ path); 
                                        this.reloadFilesFromServer();
                                }
                                else
                                        console.log(request.status);
                        }.bind(this);
                        xhr.send(formData);
                                 */
                }.bind(this)
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
                        console.log("adding root entry");
                        var milliseconds = (new Date).getTime();
                        return userContext.createEntryDirectory("test_"+milliseconds);
                }).then(function() {
                    return userContext.getRoots();
                }).then(function(roots) {
		            for (var i=0; i < roots.length; i++) {
           		    var dirPointer = roots[i][0];
		            var rootDirKey = dirPointer.pointer.baseKey;
        		    var dir = roots[i][1];
		            if (dir == null)
            			continue;
                        var milliseconds = (new Date).getTime();
                    dir.mkdir("subfolder_test"+milliseconds, userContext, dirPointer.pointer.writer, rootDirKey);
		            }
                }).then(function() {
                        hideLogin();   
                }).then(this.loadFilesFromServer);

        },

        componentDidMount: function() {
                var path = this.currentPath();
                this.loadFilesFromServer(path);
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
                });
        },

        updateDir: function(entryPoint, dirAccess) {
                this.loadFilesFromServer(path);
        },

        getContent: function(path) {
                var url = buildGetContentUrl(path);
                location.href=url;
        },

        mkdir: function() {

                var newFolderName = prompt("Enter new folder name");
                if (newFolderName == null)
                        return;

                $.ajax({
                        url: buildMkdirUrl(this.currentPath(),newFolderName),
                        dataType: 'json',
                        cache: false,
                        success: this.reloadFilesFromServer,
                        error: function(xhr, status, err) {
                                console.error(this.props.url, status, err.toString());
                        }.bind(this)
                });
        },

        render: function() {
                const files = this.state.files.map(function(f) {
                            return (<File id={File.id()} gridView={this.state.gridView} onClick={f.onClick} name={f.name} isdir={f.isDir} size={f.size} browser={this}/>)
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
