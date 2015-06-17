
var File = React.createClass({

        glyphClass: function() {
                var className = "glyphicon "; 
                className += this.props.isdir ? "glyphicon-folder-open" : "glyphicon-file";
                return className;
        },

    renderGrid: function() {
            var glyphClass = this.glyphClass();
            return (<div ref={this.props.path} className="col-xs-6 col-md-3">
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
                                    this.onRename();
                            } else if (selected  == "Remove")  {
                                    this.onRemove();
                            }  else 
                    console.log("no  action defined for context menu item "+ selected);    
                    }.bind(this)
            });
    },


    remove: function() {
            $.ajax({
                    url: buildRemoveUrl(this.props.path),
            dataType: 'json',
            cache: false,
            success: function() {
                    this.props.browser.reloadFilesFromServer();
            }.bind(this),
            error: function(xhr, status, err) {
                    console.error(this.props.url, status, err.toString());
            }.bind(this)
            });
    },

    rename: function(updatedName) {
            $.ajax({
                    url: buildRenameUrl(this.props.path,  updatedName),
            dataType: 'json',
            cache: false,
            success: function() {
                    this.props.browser.reloadFilesFromServer();
            }.bind(this),
            error: function(xhr, status, err) {
                    console.error(this.props.url, status, err.toString());
            }.bind(this)
            });
    },

    onRemove: function() {
            var type = this.props.isdir ? "folder" : "file";
            var remove = confirm("Remove "+type +" '"+ this.props.path +"' ?");
            if (remove)     
                    this.remove();
    },

    onRename: function() {
            var type = this.props.isdir ? "folder" : "file";
            var updatedName = prompt("Enter new name for "+type +" "+this.props.name);
            if (updatedName != null) 
                    this.rename(updatedName);
    },

    renderList: function() {
            var dateString =  new Date(this.props.time*1000).toGMTString()
                    var glyphClass = this.glyphClass();
            var spanStyle = {fontSize:"1.5em"}; 
            return (<tr id={this.props.id} ref={this.props.path}>
                            <td>
                            <a onClick={this.props.onClick}><span style={{fontSize:"1.5em", paddingRight:"10px"}} className={glyphClass}/>{this.props.name}</a>
                            </td>
                            <td>{File.sizeString(this.props.size)}</td>
                            <td>{dateString}</td>
                            </tr>);
    },

    render: function() {
            return this.props.gridView ? this.renderGrid() : this.renderList();
    }
});

//File.id = function(name) {return name.match(/([a-z]|[0-9])/g).join("");}

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

var Browser = React.createClass({
        getInitialState: function() {
                return {paths : ["."],
                        files: [],
    sort: File.pathSort,
    gridView: true};
        },

    loadFilesFromServer: function(path) {
            $.ajax({
                    url: buildGetChildrenUrl(path),
            dataType: 'json',
            cache: false,
            success: function(data) {
                    var files = data.children.sort(this.state.sort);
                    var paths = this.state.paths; 
                    if (paths[paths.length-1] != path)
                    paths = paths.concat([path]) 
                    this.setState(
                            {files: files, 
                                    paths: paths,
                            sort: this.state.sort,
                            gridView: this.state.gridView});
            updateNavbarPath(this.currentPath());
            hideLogin();
            }.bind(this),
            error: function(xhr, status, err) {
                    console.error(this.props.url, status, err.toString());
            }.bind(this)
            });
    },

    reloadFilesFromServer: function() {this.loadFilesFromServer(this.currentPath())},

    currentPath : function() {
            return this.state.paths[this.state.paths.length-1]
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

            this.setState({files: this.state.files, 
                    sort: this.state.sort,  
                    paths: this.state.paths, 
                    gridView: updatedView});
    },


    uploadFile: function() {
            return function (evt) {
                    var path = this.currentPath();
                    var readFile = evt.target.files[0];
                    var name = readFile.name;
                    console.log(readFile);

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
            }.bind(this)
    },

    loginOnEnter: function(event) {
            if (event.keyCode === 13) {
                    this.login();
                    return false;
            }
    },

    login: function() {
            var user = document.getElementById("login-user-input").value;
            var password = document.getElementById("login-password-input").value;
            var json = JSON.stringify({"username": user, "password": password});
            console.log("post data "+ json);
            $.ajax({
                    url: "/login",
                    type: "POST",
                    dataType: 'json',
                    data: json,
                    cache: false,
                    success: this.reloadFilesFromServer,
                    error: function(xhr, status, err) {
                            console.error(this.props.url, status, err.toString());
                            alert("Failed authentication.");
                    }.bind(this)
            });
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
            var files = this.state.files.map(function(f) {
                    var id  =  File.id(f.name);
                    var onClick = f.isdir ? function(event){
                            this.updatePath(f.path);
                    }.bind(this) :
                            function(event) {
                                    this.getContent(f.path);
                            }.bind(this)
                            return (<File id={id} gridView={this.state.gridView} onClick={onClick} path={f.path} name={f.name} isdir={f.isdir} size={f.size} time={f.time} browser={this}/>)
            }.bind(this));

            var gridGlyph = "glyphicon glyphicon-th-large";
            var listGlyph = "glyphicon glyphicon-list";
            var element = document.getElementById("altViewSpan");
            var className = this.state.gridView ? listGlyph : gridGlyph;
            element.className = className;

            var layout = null;
            var  contextMenu = (<div id="context-menu">
                            <ul className="dropdown-menu" role="menu">
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

                            var sortGlyph = "glyphicon glyphicon-sort";

            return (<div>
                            <table className="table table-responsive table-striped table-hover">
                            <thead><tr>
                            <th><button onClick={this.pathSort} className="btn btn-default"><span className={sortGlyph}/>Path</button></th>
                            <th><button onClick={this.sizeSort} className="btn btn-default"><span className={sortGlyph}/>Size</button></th>
                            <th><button onClick={this.timeSort} className="btn btn-default"><span className={sortGlyph}/>Last modified time</button></th>
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
