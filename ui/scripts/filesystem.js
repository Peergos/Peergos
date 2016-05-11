const centerStyle = {textAlign: "center"};

const buildJumbo = function(header, content) {
    return (<div className="jumbotron">
            <div className="container">
            <h2 style={centerStyle}>{header}</h2>
            <p style={centerStyle}>{content}</p>
            </div>
            </div>);
}

const addStuffComponent = buildJumbo(
    ("Nothing to see here yet"), 
    ['Drag and drop files here or click ', <span className="glyphicon glyphicon-upload"/>,' above to upload']);

const sharedByComponent = function(name) {
    return buildJumbo(
        ("Nothing to see here yet"), 
        ["Files shared with you by '", name,"' will appear here"]);
};

$(document).ready(function(){
    //init. tooltips
    $('[data-toggle="tooltip"]').tooltip();   
});

$('#modal').on('hide.bs.modal', function () {
    $('#modal').removeData();
});

userContext =  null;

populateModalAndShow = function(title, content) {
    $('#modal-title').html(title);
    $('#modal-body').html(content);
    $('#modal').modal("show");   
}


hideNonWritableButtons = function() {
    $("#mkdirButton").css("display","none");
    $("#uploadButton").css("display","none");
}

showNonWritableButtons = function() {
    $("#mkdirButton").css("display","block");
    $("#uploadButton").css("display","block");
}

requireSignedIn = function(callback) {
    if (userContext == null) { 
        const url = window.location.origin.split("#")[0];
        populateModalAndShow('Who is this?','<p>Please <a href="'+url+'">sign in</a> to continue.</p>');
    }
    else
        callback();
}

startInProgess = function() {
    var element = document.getElementById("inProgress");
    element.className += " pong-loader"; 
}

clearInProgress = function() {
    var element = document.getElementById("inProgress");
    element.className = element.className.replace(/pong-loader/, "");
    return Promise.resolve(true);
}

const buildSignupUrl = function(username, email) {
    return "/signup/"+ username +"/"+ email;
}

const submitEmailSignup = function(username, email) {
    const url = buildSignupUrl(username, email);
    $.get(url,
          function(data) {
              const _status = data.status;
              console.log("email registration status : "+ _status);
          }); 
}
var url;
var ae = document.createElement("a");
document.body.appendChild(ae);
ae.style = "display: none"; 
var uploadFragmentCounter = 0;
var uploadFragmentTotal = 0;
var downloadFragmentCounter = 0;
var downloadFragmentTotal = 0;

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

function dragHandler(e) {
    e.stopPropagation();
    e.preventDefault();
    if(e.dataTransfer.effectAllowed ==='move'){
        e.dataTransfer.dropEffect = 'move';
    }else{
        e.dataTransfer.dropEffect = 'copy';
    }
}

function uploadFileOnClient(readFile, browser) {
    var size = readFile.size;
    var name = readFile.name;
    
    startInProgess(); 
    $.toaster(
        {
            priority: "info",
            message: "Uploading file  "+ name,
            settings: {"timeout":  10000} 
        });
    
    return browser.lastRetrievedFilePointer().uploadFile(name, readFile, userContext, browser.setUploadProgressPercent)
        .then(function(res){
            const currentPath =  browser.currentPath();
	    
            clearInProgress();
            $.toaster(
                {
                    priority: "success",
                    message: "File "+ name  +" uploaded to  "+ currentPath,
                    settings: {"timeout":  5000} 
                });
            browser.loadFilesFromServer();        
        });
}

const UserOptions = React.createClass({
    submitFriendRequest: function(targetUsername) {
        return userContext.sendInitialFollowRequest(document.getElementById("friend-name-input").value).then(function(res) {
            if (res)
                populateModalAndShow("Success!", "<p>Follow request sent!</p>")
        });
    },

    getFriendRoots: function()  {
        return userContext.getFriendRoots().then(function(roots) {
            console.log("friend-roots "+ roots);
        });
    }, 
    
    populateTables: function() {
        userContext.getSocialState().then(function(socialState) {
            this.populatePendingTable(socialState.pending);
            this.populateFollowersTable(socialState.followers);
            this.populateFollowingTable(socialState.followingRoots);
        }.bind(this));
    },
    
    populatePendingTable: function(pending)  {
        const reply = function(request,  accept, reciprocate, consoleMsg, onSuccessMsg, onErrorMsg) {
            startInProgess();
            return userContext.sendReplyFollowRequest(request, accept, reciprocate).then(function(result) {
                console.log(consoleMsg +" ? "+ result);
                const  msg = result ? onSuccessMsg : onErrorMsg;
                $.toaster({
                    priority: "info",
                    message: msg, 
                    settings: {"timeout":  5000} 
                });
                clearInProgress();
                this.props.browser.loadFilesFromServer();
		var that = this;
		userContext.init().then(function() {
		    that.populateTables();
		});
            }.bind(this));
        }.bind(this);
	
        const rows = pending.map(function(request) {
            const pendingName = request.entry.owner;
            const allowAndFollowBack = function() {
		
                return reply(request, 
                             true, 
                             true, 
                             "accepted and reciprocated follow request from "+ pendingName,
                             "successfully accepted and reciprocated follow request from "+ pendingName, 
                             "failed to accept and reciprocate follow request from "+ pendingName);
            };
            const allow = function() {
                return reply(request, 
                             true, 
                             false, 
                             "accepted follow request from "+ pendingName,
                             "successfully accepted follow request from "+ pendingName, 
                             "failed to accept follow request from "+ pendingName);
            };
	    
            const deny  =  function() {
                return reply(request, 
                             false, 
                             false, 
                             "denied follow request from "+ pendingName,
                             "denied follow request from "+ pendingName, 
                             "failed to deny follow request from "+ pendingName);
            };
	    
            return (<tr>
                    <td>{request.entry.owner}</td>
                    <td><button className="btn btn-success" onClick={allowAndFollowBack}>allow and follow back</button></td>
                    <td><button className="btn btn-info" onClick={allow}>allow</button></td>
                    <td><button className="btn btn-danger" onClick={deny}>deny</button></td>
                    </tr>);
                }.bind(this));
	
        const PendingTable = React.createClass({
            render: function() {
                return (<div>
                        <table className="table table-responsive table-striped table-hover">
                        <thead></thead>
                        <th>User</th>
                        <th>Follower</th>
                        <th>Remove</th>
                        <tbody>
                        {rows}
                        </tbody>
                        </table>
                        </div>);
            }
        });
	
        React.render(   
                <PendingTable/>,
            document.getElementById("pendingRequestTable")
        );
    },
    
    tableBuilder: function(names) {
        const rows = names.map(function(name) {
            const  onRemove = function(evt) {
                userContext.removeFollower(name);
                evt.preventDefault();
            };
            return (<tr>
                    <td>{name}</td>
                    <td style={{textAlign:"right"}}><button className="btn btn-danger" onClick={onRemove}>remove</button></td>
                    </tr>);
        });
        return React.createClass({
            render: function() {
                return (<div>
                        <table className="table table-responsive table-striped table-hover">
                        <thead></thead>
                        <th>Follower</th>
                        <th></th>
                        <tbody>
                        {rows}
                        </tbody>
                        </table>
                        </div>);
            }
        });
    },
    
    populateFollowersTable: function(names)  {
	names.sort(humanSort);
        const onRemove = function(){};
        const Anon = this.tableBuilder(names);
        React.render(<Anon/>, document.getElementById("followersList"));
    },
    
    populateFollowingTable: function(followingRoots)  {
        const rows = followingRoots.sort(function(l,r) {
            return humanSort(l.getOwner(), r.getOwner());
        }).map(function(froot) {
            const ownerName = froot.getOwner();
            const onClick = function(){
                $('#modal').modal("hide");
                this.props.browser.loadFilesFromServer(froot);
            }.bind(this);
            const onRemove = function(evt){
                userContext.unfollow(ownerName);
                evt.preventDefault();
            };
            return (<tr style={{cursor: "pointer"}} onClick={onClick}>
                    <td><a><span className="glyphicon glyphicon-folder-open"/>&nbsp;&nbsp;{ownerName}</a></td>
                    <td style={{textAlign:"right"}}><button className="btn btn-danger" onClick={onRemove}>unfollow</button></td>
                    </tr>);
        }.bind(this));
        const  table = (<div>
                        <table className="table table-responsive table-striped table-hover">
                        <thead></thead>
                        <th>Shared with you</th>
                        <th></th>
                        <tbody>
                        {rows}
                        </tbody>
                        </table>
                        </div>)
        React.render(table, document.getElementById("followingList"));
    },
    render: function() {
        return (<div>
                <center>
                <h2>Submit Follow Request</h2>
                <div className="form-group">
                <input placeholder="Friend name" id="friend-name-input" className="form-control" type="text"/>
                </div>
                <button className="btn btn-success" onClick={this.submitFriendRequest}>Submit follow request</button>
                <h2>Pending Follow Requests</h2>
                <div id="pendingRequestTable"></div>
                <h2>Followers</h2>
                <div id="followersList"></div>
                <h2>Following</h2>
                <div id="followingList"></div>
                </center>
                </div>)
    },
    
    componentDidMount: function() {
        $('#modal').on('show.bs.modal', function () {
            this.populateTables();
        }.bind(this));
	
        var substringMatcher = function(strs) {
            return function findMatches(q, cb) {
                var matches, substringRegex;
		
                //an array that will be populated with substring matches
                matches = [];
		
                // regex used to determine if a string contains the substring `q`
                substrRegex = new RegExp(q, 'i');
		
                // iterate through the pool of strings and for any string that
                // contains the substring `q`, add it to the `matches` array
                $.each(strs, function(i, str) {
                    if (substrRegex.test(str)) {
                        matches.push(str);
                    }
                });
		
                cb(matches);
            };
        };
        const usernames =  userContext.getUsernames();
        $('#friend-name-input').typeahead({
            hint: true,
            highlight: true,
            minLength: 1
        },
	{
	    name: 'usernames',
	    source: substringMatcher(usernames)
	});
    }
});

const updatePasswordConfirmed = function() {
        $('#change-password-feedback-modal').modal("hide");
        $('#modal').modal("hide");
        userContext.changePassword(document.getElementById('password-new').value).then(function (rootNode) {
            $.toaster({
                priority: "info",
                message: "Password changed",
                settings: {"timeout":  5000}
            });

        }.bind(this));
        $.toaster({
            priority: "info",
            message: "Changing password",
            settings: {"timeout":  5000}
        });

};
const updatePassword = function(confirmed) {

    const oldPwd = document.getElementById("password-old").value;
    const pw1 = document.getElementById("password-new").value;
    const pw2 = document.getElementById("password-confirm").value;
    document.getElementById("change-password-error").textContent = "";
    if (pw1 != pw2) {
        document.getElementById("change-password-error").textContent = "Passwords do not match";
    }else{

        var index = commonPasswords.indexOf(pw1);
        var suffix = ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"][(index+1) % 10];
        if (index != -1) {
            document.getElementById("change-password-label").textContent = "Your password is the " + (index+1) + suffix + " most common password! Continue?";
            $('#change-password-feedback-modal').modal("show")
        }else {
            if(pw1.length < 1) {
                document.getElementById("change-password-error").textContent = "Password not set";
            }else{
                if(pw1.length < passwordWarningThreshold) {
                    document.getElementById("change-password-label").textContent = "Passwords less than "+ passwordWarningThreshold +" characters are considered unsafe. Continue?";
                    $('#change-password-feedback-modal').modal("show")
                }else{
                    updatePasswordConfirmed();
                }
            }
        }
    }
};

const SettingsOptions = React.createClass({

    render: function() {
        return (<div className="container form-change-password">
                <h3>Change Password</h3>
                <div className="form-group">
                    <center>
                        <label id="change-password-error" className= "alert-danger"></label>
                    </center>
                </div>
                <div className="form-group">
                    <label>Old password</label>
                    <input id="password-old" className="form-control" type="password" autoFocus={true}/>
                    <label>New password</label>
                    <input id="password-new" className="form-control" type="password" />
                    <label>Confirm password</label>
                    <input id="password-confirm" className="form-control" type="password" />
                </div>
                <button className="btn btn-success" onClick={updatePassword}>Update</button>
                </div>);
    }
});

const buildSharingTable = function(socialState, props) {
    const treeNode = props.retrievedFilePointer;
    const type = treeNode.isDirectory() ? "Folder": "File";
    
    const alreadySharedWith = socialState.sharedWith(treeNode.getLocation());
    var existing = {};
    alreadySharedWith.forEach(function(entry) {
        existing[entry] = true;
    });
    var availableFollowers = socialState.getFollowers().filter(function(name){
        return !(name in existing);
    });
    
    const rows = availableFollowers.map(function(name) {
        const onClick = function() {
            startInProgess();
            socialState.share(treeNode, name, userContext).then(function() {
                clearInProgress();
                props.browser.loadFilesFromServer();
                $.toaster({
                    priority: "info",
                    message: "Successfully shared with "+ name, 
                    settings: {"timeout":  5000} 
                });
            }, function() {
                clearInProgress();   
                $.toaster({
                    priority: "info",
                    message: "Failed to share with "+ name, 
                    settings: {"timeout":  5000} 
                });
            })
        }.bind(this);
        return (<tr><td style={{cursor: "pointer"}} onClick={onClick}>
                <a>{name}</a>
                </td></tr>);
    }.bind(this));
    
    return (<div>
            <table className="table table-responsive table-striped table-hover">
            <thead>
            <tr><th></th></tr>
            </thead>
            <tbody>
            {rows}
            </tbody>
            </table>
            </div>)
};

const passwordWarningThreshold = 12;

var SignUp = React.createClass({
    getInitialState : function() {
        return {
            usernameClass : "",
            usernameMsg : "",
            passwordClass : "",
            passwordMsg :  "" 
        }
    },
    componentDidMount: function() {
        var submit = function() {
            const username = document.getElementById("signup-user-input").value;
            const pw1 = document.getElementById("signup-password-input").value;
            const pw2 = document.getElementById("signup-verify-password-input").value;
            const email = document.getElementById("signup-email-user-input").value;
            
            if (pw1 != pw2) {
                this.setState({
                    usernameClass : "",
                    usernameMsg : "",
                    passwordClass : "has-error has-feedback",
                    passwordMsg : "Entered passwords do not match!"
                }); 
                return;
		
            }
	    
            if (email.length != 0) 
                submitEmailSignup(username, email);
	    
            var ctx = null;
	    // check username is valid
	    if (username.includes(" ") || username.includes("\t") || username.includes("\n")) {
		this.setState({
                    usernameClass : "has-error has-feedback",
                    usernameMsg : "Username cannot contain whitespace!",
                    passwordClass : "",
                    passwordMsg : ""
                });
		return;
	    }
            startInProgess();
            return generateKeyPairs(username, pw1).then(function(keys) {
                const dht = new DHTClient();
                const corenode = new CoreNodeClient();
                ctx = new UserContext(username, keys.user, keys.root, dht, corenode);    
                return  ctx.isRegistered();
            }).then(function(registered) {
                if  (! registered) {
		    // No user exists with that public key
                    console.log("Now registering  user "+ username);
                    return ctx.register();
                }
                clearInProgress();
                this.setState({
                    usernameClass : "has-error has-feedback",
                    usernameMsg : "That public key is already registered!",
                    passwordClass : "",
                    passwordMsg : ""
                });
                return Promise.reject("Username is already registered!");
            }.bind(this)).then(function(isRegistered) {
                if  (! isRegistered) { 
                    clearInProgress();
		    
                    this.setState({
                        usernameClass : "has-error has-feedback",
                        usernameMsg : "This username is already registered!",
                        passwordClass : "",
                        passwordMsg : ""
                    });
		    // This should never happen unless the same username and password is given as an existing user
                    return Promise.reject("Public key is already registered!");
                }
                return ctx.createEntryDirectory(username);
            }.bind(this),
	    function(err) {
		clearInProgress();
		
		this.setState({
		    usernameClass : "has-error has-feedback",
		    usernameMsg : "Failed to register username. ",
		    passwordClass : "",
		    passwordMsg : ""
		});
                return Promise.reject("Couldn't register username!");
	    }.bind(this)).then(function(root) {
                return root.fileAccess.mkdir("shared", ctx, root.filePointer.writer, root.filePointer.mapKey, root.filePointer.baseKey, null, true);
            }.bind(this)).then(function(res) {
                console.log("Verified user "+ username +" is registered");
		return this.props.browser.login(username, pw1).then(function(){
		    clearInProgress();
		    startTour();
		});
            }.bind(this));
        }.bind(this);
	var validatePassword = function() {
	    // after one failed attempt update the status after each keystroke
	    var passwd = document.getElementById("signup-password-input").value;
	    var index = commonPasswords.indexOf(passwd);
	    var suffix = ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"][(index+1) % 10];
	    if (index != -1) {
		this.setState({
                    usernameClass : "",
                    usernameMsg : "",
		    checkPassword: true,
                    passwordClass : "has-error has-feedback alert alert-danger",
                    passwordMsg : "Warning: your password is the " + (index+1) + suffix + " most common password!"
                });
		document.getElementById("signup-password-input").onkeyup = document.getElementById("signup-verify-password-input").onfocus;
            } else if (passwd.length < passwordWarningThreshold) {
                this.setState({
                    usernameClass : "",
                    usernameMsg : "",
		    checkPassword: true,
                    passwordClass : "has-error has-feedback alert alert-danger",
                    passwordMsg : "Warning: passwords less than "+ passwordWarningThreshold +" characters are considered unsafe."
		});
		document.getElementById("signup-password-input").onkeyup = document.getElementById("signup-verify-password-input").onfocus;
            }
	    else
    		this.setState({
                    usernameClass : "",
                    usernameMsg : "",
                    passwordClass : this.state.checkPassword ? "alert alert-success" : "",
                    passwordMsg : this.state.checkPassword ? "That's a better password." : ""
                });
	}.bind(this);
        document.getElementById("signupSubmitButton").onclick = submit; 
        document.getElementById("signup-verify-password-input").onfocus = validatePassword; 
    }, 
    render: function() {
        const  usernameClass = "form-group "+ this.state.usernameClass;
        const usernameMsg = this.state.usernameMsg;
        const usernameLabel = usernameMsg == "" ? (<div/>) : (<label>{usernameMsg}</label>)
	
        const passwordClass = "form-group " + this.state.passwordClass;
        const pwMsg = this.state.passwordMsg;
        const passwordLabel = pwMsg == "" ? (<div/>) : (<label>{pwMsg}</label>)
	
        return (<div className="container form-signin">
                <center>
                <div className={usernameClass}>
                <h2>Sign up credentials</h2>
                {usernameLabel}
                <input placeholder="Username" id="signup-user-input" className="form-control" type="text" autoFocus={true}/>
                </div>
                <div className={passwordClass}>
                {passwordLabel}
                <input placeholder="Password" id="signup-password-input" className="form-control" type="password"/>
                <input placeholder="Verify password" id="signup-verify-password-input" className="form-control" type="password"/>
                </div>
                <div  className="form-group">
                <input placeholder="Email address (Optional)" id="signup-email-user-input" className="form-control" type="text"/>
                </div>
                <button id="signupSubmitButton" className="btn btn-success">Create account</button>
                </center>
                </div>
               );
    }
});

var File = React.createClass({
    getInitialState: function() {
        return { isShared: false };
    },

    glyphClass: function() {
        var className = "glyphicon "; 
        className += this.props.isdir ? "glyphicon-folder-open" : "glyphicon-file";
        return className;
    },

    sharedStyle: function() {
        return this.state.isShared ? {color: "forestgreen", wordWrap:"break-word"} : {wordWrap:"break-word"};
    },

    sharedListStyle: function() {
        return this.state.isShared ? {color: "forestgreen", fontSize:"1.5em", paddingRight:"20px"} : {fontSize:"1.5em", paddingRight:"20px"};
    },

    renderGrid: function() {
        var glyphClass = this.glyphClass();
        const style = this.sharedStyle();
	
	const icon = this.props.hasThumb ? (<img draggable="true" onDragStart={this.props.onDragStart} onDragOver={this.props.onDragOver} onDrop={this.props.onDrop} className={glyphClass} src={this.props.thumbURL}/>) : (<span id={this.props.id} draggable="true" onDragStart={this.props.onDragStart} onDragOver={this.props.onDragOver} onDrop={this.props.onDrop} style={{fontSize:"3.5em"}} className={glyphClass}/>);
        return (<div className="col-xs-6 col-md-3">
                <a id={this.props.id} onDoubleClick={this.props.onClick} style={{cursor: "pointer"}}>
                {icon}
                </a>
                <div className="caption">
                <h4 className="heading" style={style} >{this.props.name}</h4>
                </div>
                </div>);
    },

    componentDidMount: function() {
        this.state.isShared = false;
        var selector = "#"+this.props.id;
        $(selector).contextmenu({
            target: '#context-menu',
            onItem: function(context, evt) {
                var selected  =  evt.target.text.trim();
                console.log("on item "+ selected);
                if  (selected  == "Rename") {
                    this.rename(); 
                } else if (selected  == "Delete")  {
                    this.remove();
                } else if (selected  == "Open")  {
                    this.props.onClick();
                } else if (selected  == "Copy")  {
                    this.onCopy();
                } else if (selected  == "Cut")  {
                    this.onCut();
                } else if (selected  == "Create public link") {
                    //public link
                    const publicUrl =  window.location.origin + this.props.retrievedFilePointer.toLink();
                    const content = '<div class="container"><p style="word-wrap;break-all;"><a href="'+ publicUrl+'">public-link</a></p></div>';
                    console.log("creating public link for "+ this.props.name);
                    populateModalAndShow("Public link to file "+  this.props.name, content);
                } else if (selected  == "Share")  {
                    userContext.getSocialState().then(function(socialState) {
                        const table = buildSharingTable(socialState, this.props);
			
                        $('#modal-title').html("Share "+  this.props.name +" with...");
                        React.render(
                            table,
                            document.getElementById("modal-body"));
                        $('#modal').modal("show");   
                    }.bind(this));
                } else if (selected  == "Readers")  {
                    userContext.getSocialState().then(function(socialState) {
                        const sharedWith = socialState.sharedWith(this.props.retrievedFilePointer.getLocation())
                            .sort(humanSort)
                            .map(function(name) {
                                return "<li>"+name+"</li>";
                            }).join(""); 
			
                        const ul = "<ul>"+ sharedWith +"</ul>";
                        const title = "'"+this.props.name +"' is shared with";  
                        populateModalAndShow(title, ul); 
                    }.bind(this));
                } else 
                    console.log("no  action defined for context menu item "+ selected);    
            }.bind(this)
        });
    },

    remove: function() {
	const parent = this.props.browser.lastRetrievedFilePointer();
        return this.props.retrievedFilePointer.remove(userContext, parent).then(function(){
            this.props.browser.loadFilesFromServer();
        }.bind(this));
    },
    
    rename: function() {
        const newName = prompt("Specify updated name for "+ this.props.name);
        if (newName == null)
            return;
	const parentFileTreeNode = this.props.browser.lastRetrievedFilePointer();
        const filePointer = this.props.retrievedFilePointer.rename(newName, userContext, parentFileTreeNode).then(function() {
            //now reload the view
            this.props.browser.loadFilesFromServer();
        }.bind(this));
    },
    
    onCopy: function() {
        this.props.browser.setClipboard({
            fileTreeNode: this.props.retrievedFilePointer,
            op: "copy"
        }); 
    },
    onCut: function() {
        this.props.browser.setClipboard({
	    parent: this.props.browser.lastRetrievedFilePointer(),
            fileTreeNode: this.props.retrievedFilePointer,
            op: "cut"
        }); 
    },
    renderList: function() {
        //var dateString =  new Date(this.props.time*1000).toGMTString()
        var glyphClass = this.glyphClass();
        const style = this.sharedListStyle();
	
        var sizeString = this.props.isdir ? "" : File.sizeString(this.props.size);
        return (<tr id={this.props.id}>
                <td>
                <a onDoubleClick={this.props.onClick} style={{cursor: "pointer"}}><span id={this.props.id} draggable="true" onDragStart={this.props.onDragStart} onDragOver={this.props.onDragOver} onDrop={this.props.onDrop} style={style} className={glyphClass} />{this.props.name}</a>
                </td>
                <td>{sizeString}</td>
                </tr>);
    },
    
    render: function() {
        userContext.getSocialState().then(function(socialState) {
            var loc = this.props.retrievedFilePointer.getLocation();
            var is = socialState.sharedWith(loc).length > 0;
            if(is != this.state.isShared){
                this.setState({isShared: is});
            }
        }.bind(this));
        return this.props.gridView ? this.renderGrid() : this.renderList();
    }
});


File.id = function() {return (Math.pow(2,31) * Math.random())|0; }

File.timeSort = function(left, right){return left.time - right.time;} 

File.sizeSort = function(left, right){return left.size - right.size;} 

File.pathSort = function(left, right){return left.path.localeCompare(right.path);} 

File.sizes = [{count : 1, unit:"bytes"}, {count : 1024, unit: "kB"}, {count: 1048576 , unit : "MB"}, {count: 1073741824, unit:"GB" } ]

File.sizeString =  function(sizeBytes) {
    var iUnit=0;
    var count=0;
    for (iUnit=0; iUnit < File.sizes.length;iUnit++) {
        count = sizeBytes / File.sizes[iUnit].count;
        if (count < 1024)
            break;
    }
    return "" + (count|0) +" "+ File.sizes[iUnit].unit;   
}

var Browser = React.createClass({
    getInitialState: function() {
        return {files: [],
                gridView: true,
                sort: File.pathSort,
                retrievedFilePointerPath: [],
                clipboard: {}
               };
    },

    setUploadProgressPercent: function(percent) {
        if(percent >= 100){
            percent = 0;
            uploadFragmentCounter = 0;
            uploadFragmentTotal = 0;
        }
        React.render(
                <Progress percent={percent}/>,
            document.getElementById("uploadprogressbar") 
        );
    },

    setDownloadProgressPercent: function(percent) {
        if(percent >= 100){
            percent = 0;
            downloadFragmentCounter = 0;
            downloadFragmentTotal = 0;
            //document.title = "Peergos - Control your data!";
        }
        React.render(
                <Progress percent={percent}/>,
            document.getElementById("downloadprogressbar") 
        );
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
            const props = e.getFileProperties();
            return props.name;
        }).join("/");
    },

    loadFilesFromServer: function(fileTreeNode, isPublic) {
        const browser = this;
        if (typeof(userContext) == "undefined" || userContext == null)
            return Promise.resolve(false);
	
        const callback = function(children) {
            const files = children.filter(function(child){return !child.getFileProperties().isHidden()}).map(function(treeNode) {
                const props = treeNode.getFileProperties();
                const isDir = treeNode.isDirectory();
                const name  = props.name;
                const size = props.size;
		
		const hasThumb = props.hasThumbnail();
		const thumbURL = props.getThumbURL();
                const onClick = isDir ? function() {
                    this.addToPath(treeNode);
                }.bind(this) :  function() {
                    downloadFragmentTotal = downloadFragmentTotal + 60 * Math.ceil(size/Chunk.MAX_SIZE);
                    //download the chunks and reconstruct the original bytes
                    //get the data
                    $.toaster({
                        priority: "info",
                        message: "Downloading file "+ name, 
                        settings: {"timeout":  5000} 
                    });
                    startInProgess();
                    treeNode.getInputStream(userContext, size, browser.setDownloadProgressPercent).then(function(buf) {
                        return buf.read(size).then(function(originalData) {
                            openItem(name, originalData);
                        });
                    }).then(clearInProgress);
                }.bind(this);
                const onDragStart = function(ev) {
                    ev.dataTransfer.effectAllowed='move';
                    var id = ev.target.id;
                    ev.dataTransfer.setData("text/plain", id);
                    var owner = treeNode.getOwner();
                    var me = userContext.username;
                    if(owner === me){
                        this.setClipboard({
			    parent: this.lastRetrievedFilePointer(),
                            fileTreeNode: treeNode,
                            op: "cut"
                        });
                    }else{
                        ev.dataTransfer.effectAllowed='copy';
                        this.setClipboard({
                            fileTreeNode: treeNode,
                            op: "copy"
                        });
                    }
                }.bind(this);
                const onDrop = function(ev) {
                    ev.preventDefault();
                    var moveId = ev.dataTransfer.getData("text");
                    var id = ev.target.id;
                    if(id != moveId && isDir) {
                        const clipboard = this.state.clipboard;
                        const path = treeNode;
                        if (typeof(clipboard) ==  undefined || typeof(clipboard.op) == "undefined")
                            return;
                        if (clipboard.op == "cut") {
                            clipboard.fileTreeNode.copyTo(path, userContext).then(function() {
                                return clipboard.fileTreeNode.remove(userContext, clipboard.parent);
                            }).then(function() {
                                this.loadFilesFromServer();
                            }.bind(this));
                        }
                    }
                }.bind(this);
                const onDragOver = function(ev) {//Never called, see dragHandler
                    ev.preventDefault();
		    
                }.bind(this);
                return {
                    onClick: onClick,
                    onDragStart : onDragStart,
                    onDragOver : onDragOver,
                    onDrop : onDrop,
                    name: name,
                    isDir: isDir,
                    size: size,
                    filePointer: treeNode,
                    hasThumb: hasThumb,
                    thumbURL: thumbURL
                }
            }.bind(this));
	    
            this.setState({
                files: files, 
                sort: this.state.sort,  
                gridView: this.state.gridView, 
                retrievedFilePointerPath: this.state.retrievedFilePointerPath,
                clipboard: this.state.clipboard 
            }, function() {
                this.updatePending();
                this.updateNavbarPath(this.currentPath());
            }.bind(this)); 
        }.bind(this);
	
        const isEmpty =  this.state.retrievedFilePointerPath.length == 0;
        const rootSupplied =  typeof(fileTreeNode) == "object";
        if (rootSupplied || isEmpty || isPublic) {
            var prom = null; 
            if (isPublic) 
                prom = Promise.resolve(fileTreeNode);
            else if (rootSupplied)  //navigate to /friendname/shared/ourname from the global-root
                prom = fileTreeNode.getChildren(userContext).then(function(children){
                    return children[0].getChildren(userContext).then(
                        function(gChildren){
                            return Promise.resolve(gChildren[0])})});
            else
                prom = userContext.init().then(userContext.getUserRoot);
	    
            return prom.then(function(globalRoot){
		
                if (rootSupplied)
                    this.state.retrievedFilePointerPath = [];
                const tmpPath = this.state.retrievedFilePointerPath;
                if (tmpPath.length > 0 && tmpPath[tmpPath.length-1].equals(globalRoot))
                    return;
                this.state.retrievedFilePointerPath.push(globalRoot);
		
                this.updateNavbarPath(this.currentPath());
		
                const path = this.state.retrievedFilePointerPath;
                if  (path.length ==0 || ! path[0].isWritable())
                    hideNonWritableButtons();
                else 
                    showNonWritableButtons();
		
                return globalRoot.getChildren(userContext).then(function(children) {
                    callback(children);
		    return Promise.resolve(true);
                });
            }.bind(this));
        }
        else {
            return this.lastRetrievedFilePointer().getChildren(userContext).then(function(children) {
                callback(children);
		return Promise.resolve(true);
            }.bind(this));
        }    
    },

    pathAsButtons: function(){
        return this.state.retrievedFilePointerPath.map(function(e) {
            const props = e.getFileProperties();
            const index = this.state.retrievedFilePointerPath.indexOf(e);
            const name = index == 0  ? e.getOwner() : props.name;
            const path = this.state.retrievedFilePointerPath.slice(0, index+1);
            const onClick = function() {
                this.state.retrievedFilePointerPath = path;
                this.loadFilesFromServer();
            }.bind(this);
            const onDrop = function(ev) {
                ev.preventDefault();
                const clipboard = this.state.clipboard;
                if (typeof(clipboard) ==  undefined || typeof(clipboard.op) == "undefined")
                    return;
                if (clipboard.op == "cut") {
                    const pwd = e;
                    clipboard.fileTreeNode.copyTo(pwd, userContext).then(function() {
                        return clipboard.fileTreeNode.remove(userContext, clipboard.parent);
                    }).then(function() {
                        this.loadFilesFromServer();
                    }.bind(this));
                }
            }.bind(this);
            const onDragOver = function(ev) {
                ev.preventDefault();
            }.bind(this);
            const className = index == this.state.retrievedFilePointerPath.length -1 ? "btn-primary" : "btn-default";
	    
            var style = {display: "block"};
            if(e.getOwner() !== userContext.username){
                style = {background: "orangered"};
            }
            return (<button className={"btn "+className + " tour-path"} onClick={onClick} onDrop={onDrop} onDragOver={onDragOver} style={style}>{name}</button>)
        }.bind(this));
    },

    updatePending: function(){
        userContext.getSocialState().then(function(socialState) {
            var pending = socialState.pending.length;
            var spanStyle = {display: "none"};
            if(pending > 0){
                spanStyle = {background: "red", position: "relative", top: "-10px", left: "-5px"};
            }
            const elem = (<span className="badge" style={spanStyle}>
                          {pending}
                          </span>)
            React.render(elem, document.getElementById("pendingSpan"));
        }.bind(this));
    },
    
    updateNavbarPath: function(path){
        const buttons = this.pathAsButtons();
        const elem = (<div> 
                      {buttons}
                      </div>)
        React.render(elem, document.getElementById("pathSpan"));
    },
    
    setClipboard: function(clipboard) {
        this.state.clipboard = clipboard;
    },
    
    onPaste: function() {
        const clipboard = this.state.clipboard;
        const pwd = this.lastRetrievedFilePointer();
        if (typeof(clipboard) ==  undefined || typeof(clipboard.op) == "undefined")
            return;
        if (clipboard.op == "copy") {
            clipboard.fileTreeNode.copyTo(pwd, userContext).then(function() {
                this.loadFilesFromServer();
            }.bind(this));
        } else if (clipboard.op == "cut") {
            clipboard.fileTreeNode.copyTo(pwd, userContext).then(function() {
                return clipboard.fileTreeNode.remove(userContext, clipboard.parent);
            }).then(function() {
                this.loadFilesFromServer();
            }.bind(this));
        } else throw "unknown clipboard op "+ clipboard.op;
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
    
    onUpload: function() {
        requireSignedIn(function()  {
            $('#uploadInput').click();
        });
    },

    onHome: function() {
        requireSignedIn(function()  {
            this.state.retrievedFilePointerPath=[];
            this.loadFilesFromServer();
        }.bind(this));
    },

    onHomeDrop: function(ev) {
        ev.preventDefault();
        const clipboard = this.state.clipboard;
        if (typeof(clipboard) ==  undefined || typeof(clipboard.op) == "undefined")
            return;
        if (clipboard.op == "cut") {
            const pwd = this.state.retrievedFilePointerPath[0];
            clipboard.fileTreeNode.copyTo(pwd, userContext).then(function() {
                return clipboard.fileTreeNode.remove(userContext, clipboard.parent);
            }).then(function() {
                this.loadFilesFromServer();
            }.bind(this));
        }else if (clipboard.op == "copy") {
            FileTreeNode.ROOT.getDescendentByPath("/" + userContext.username, userContext)
                .then(function(home){
                    clipboard.fileTreeNode.copyTo(home, userContext).then(function() {
                        this.loadFilesFromServer();
                    }.bind(this));
                }.bind(this));
        }
    },

    onHomeDragOver: function(ev) {
        ev.preventDefault();
    },

    onUser: function() {
        requireSignedIn(function()  {
            $('#modal-title').html("User options");
            React.render(
                    <UserOptions browser={this}/>, 
                document.getElementById('modal-body'),
                function() {
                    $('#modal').modal("show")
                }   
            );
        }.bind(this));
    },

    alternateView: function() {
        var updatedView = !  this.state.gridView;
        this.setState({
            files: this.state.files, 
            sort: this.state.sort,  
            retrievedFilePointerPath: this.state.retrievedFilePointerPath,
            gridView: updatedView,
            clipboard: this.state.clipboard 
        });
    },

    uploadFile: function() {
        return function (evt) {
            if (userContext == null) {
                alert("Please sign in first!");
                return false;
            }
            const browser = this;
            var files = evt.target.files || evt.dataTransfer.files;
            for(var j = 0; j < files.length; j++) {
                uploadFragmentTotal = uploadFragmentTotal + 60 * Math.ceil(files[j].size/Chunk.MAX_SIZE);
            }
            for(var i = 0; i < files.length; i++) {
                var file = files[i];
                uploadFileOnClient(file, browser);
            }
        }.bind(this);
    },      

    selectHandler: function() {
        return function (evt) {
            if (userContext == null) {
                alert("Please sign in first!");
                return false;
            }
            var moveId = evt.dataTransfer.getData("text");//inside app drag and drop
            if(moveId != null && moveId.length > 0){
                return;
            }
            dragHandler(evt);
            const browser = this;
            var files = evt.target.files || evt.dataTransfer.files;
            for(var j = 0; j < files.length; j++) {
                uploadFragmentTotal = uploadFragmentTotal + 60 * Math.ceil(files[j].size/Chunk.MAX_SIZE);
            }
            for(var i = 0; i < files.length; i++) {
                var file = files[i];
                uploadFileOnClient(file, browser);
            }
        }.bind(this);
    },                                

    loginOnEnter: function(event) {
        if (event.keyCode === 13) {
            this.login();
            return false;
        }
    },

    signup: function() {
        $("#login-form").css("display","none");
        React.render(
                <SignUp browser={this}/>, 
            //document.getElementById('modal-body')
            document.getElementById('signup-form')
        );
    },

    login: function(usernameArg, passwordArg) {
        const usernameInput = document.getElementById("login-user-input");
        const passwordInput = document.getElementById("login-password-input");
	
        const hasUsername = typeof(usernameArg) == "string";
        const hasPassword = typeof(passwordArg) == "string";
        const username = hasUsername ? usernameArg : usernameInput.value;
        const password = hasPassword ? passwordArg : passwordInput.value;
	
        startInProgess();
        const onVerified  = function() {
            const displayName = userContext.username;
	    
            if (! hasUsername) usernameInput.value = "";
            if (! hasPassword) passwordInput.value="";

            return this.loadFilesFromServer().then(clearInProgress).then(function() {
                $("#logout").html("<button id=\"logoutButton\" class=\"btn btn-default dropdown-toggle\" data-toggle=\"dropdown\" aria-haspopup=\"true\" aria-expanded=\"true\">"+
                    "<span class=\"glyphicon glyphicon-off\"/>  " + displayName +"</button>" +
                    "<ul id='settingsMenu' class='dropdown-menu' aria-labelledby='logoutButton' style='margin-top:-20px;'>" +
                    "    <li><a id='changePasswordMenuItem'>Change Password</a></li>" +
                    "    <li role='separator' class='divider'></li>" +
                    "    <li><a id='logoutMenuItem'>Log out</a></li>" +
                    "</ul>"
                );
                $("#changePasswordMenuItem").click(this.changePasswordFunction);
                $("#logoutMenuItem").click(this.logoutFunction);
                $("#login-form").css("display","none");
                $("#signup-form").css("display","none");
            }.bind(this));
        }.bind(this);
	
        var ctx = null;
        return generateKeyPairs(username, password).then(function(keys) {
            var dht = new DHTClient();
            var corenode = new CoreNodeClient();
            ctx = new UserContext(username, keys.user, keys.root, dht, corenode);    
            return  ctx.isRegistered();
        }).then(function(registered) {
            if  (! registered) {
                console.log("User is "+ username + " is not  verified");
                populateModalAndShow("Authentication Failure", "Invalid credentials.");
                return reject();
            }
            else {
                userContext = ctx;  
		return Promise.resolve(true);
	    }
        }).then(onVerified, 
                function() {
                    //failed to authenticate user
                    if (! hasPassword) passwordInput.value='';
                    populateModalAndShow("Authentication Failure", "Invalid credentials.");
                    clearInProgress();
                });
    },

    changePasswordFunction: function(evt) {
        requireSignedIn(function()  {
            $('#modal-title').html("Settings");
            React.render(
                <SettingsOptions />,
                document.getElementById('change-password-body'),
                    function() {
                        $('#modal-body').html("");
                        $('#modal').modal("show")
                    }
                );
        }.bind(this));
    },

    logoutFunction: function(evt) {
        console.log("User logging out.");
        userContext.logout();
        requireSignedIn(function() {
            $("#logout").css("display","none");
            userContext = null;
            this.setState(this.getInitialState(),
                          function() {
                              this.updateNavbarPath((<div/>));
                              $("#login-form").css("display","block");
                              $("#logout").html("");
                              this.componentDidMount();
                              this.clearListeners();
                          }.bind(this));
        }.bind(this));
    },
    
    displayPublic: function() {
        $("#homeButton, #uploadButton, #mkdirButton, #userOptionsButton, #login-form").css("display","none");
	
        const  keysString = window.location.hash.substring(1);
	
        console.log(keysString);
        const filePointer = ReadableFilePointer.fromLink(keysString);
        const baseKey = filePointer.baseKey;
        console.log(filePointer);
        const corenodeClient = new CoreNodeClient();
        userContext = new UserContext(null, null, null, new DHTClient(), corenodeClient);
        userContext.btree.get(filePointer.writer.getPublicKeys(),  filePointer.mapKey).then(function(hash) {
            return userContext.dhtClient.get(hash);
        }).then(function(raw) {
	    
            if (raw.data.length == 0)
                return alert("File not found");
            const fa = FileAccess.deserialize(raw.data);
	    
            return userContext.corenodeClient.getUsername(filePointer.owner.getPublicKeys()).then(function(ownerName) {
                const treeNode =  new FileTreeNode(new RetrievedFilePointer(filePointer, fa), ownerName, [], [], filePointer.writer);
		
                const props = treeNode.getFileProperties();
                const name = props.name;
                if (! treeNode.isDirectory()) {
                    const size = props.size;
                    $.toaster({
                        priority: "info",
                        message: "Downloading file "+ name, 
                        settings: {"timeout":  5000} 
                    });
		    
                    treeNode.getInputStream(userContext, size, function(x){}).then(function(buf) {
                        return buf.read(size).then(function(originalData) {
                            openItem(name, originalData);
                        });
                    }).then(clearInProgress);
                } 
                else {
                    userContext.getAncestorsAndAddToTree(treeNode, userContext);
                    const isPublic = true;
                    this.loadFilesFromServer(treeNode, isPublic);
                }
            }.bind(this));
        }.bind(this));
    },

    componentDidMount: function() {
        if (window.location.hash) 
            this.displayPublic();
        else
            this.loadFilesFromServer();
	
        var homeButton = document.getElementById("homeButton");
        homeButton.onclick = this.onHome;
        homeButton.ondrop = this.onHomeDrop;
        homeButton.ondragover = this.onHomeDragOver;
        var uploadButton = document.getElementById("uploadButton");
        uploadButton.onclick = this.onUpload;
        var userOptionsButton = document.getElementById("userOptionsButton");
        userOptionsButton.onclick = this.onUser;
        var mkdirButton = document.getElementById("mkdirButton"); 
        mkdirButton.onclick = this.mkdir;
        var alternateViewButton = document.getElementById("alternateViewButton"); 
        alternateViewButton.onclick = this.alternateView; 
        var loginButton = document.getElementById("loginButton");
        loginButton.onclick = this.login; 
        var signupButton = document.getElementById("signupButton");
        signupButton.onclick = this.signup; 
        var passwordInput= document.getElementById("login-password-input");
        passwordInput.onkeypress=this.loginOnEnter;
	
        var uploadInput = document.getElementById("uploadInput"); 
        uploadInput.addEventListener("change", this.uploadFile(), false);
        var filedrag = document.getElementById("filedrag");
        filedrag.addEventListener("dragover", dragHandler, false);
        filedrag.addEventListener("dragleave", dragHandler, false);
        filedrag.addEventListener("drop", this.selectHandler(), false);                      
	
        $("#filedrag").contextmenu({
            target: '#browser-context-menu',
            onItem: function(context, evt) {
                const selected  =  evt.target.text.trim();
                if (selected == "Paste") {
                    this.onPaste();
                } else throw "unimplemneted selection "+ selected;
            }.bind(this)        
        });
    },
        
    clearListeners: function() {
        var uploadInput = document.getElementById("uploadInput"); 
        uploadInput.removeEventListener("change", this.uploadFile(), false);
        var filedrag = document.getElementById("filedrag");
        filedrag.removeEventListener("dragover", dragHandler, false);
        filedrag.removeEventListener("dragleave", dragHandler, false);
        filedrag.removeEventListener("drop", this.selectHandler(), false);                      
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
        if (retrievedFilePointer.equals(this.lastRetrievedFilePointer()))
            return;//stop race-condition
        const path= this.state.retrievedFilePointerPath.slice();//copy
        path.push(retrievedFilePointer);
        this.setState({
            files: this.state.files, 
            sort: this.state.sort,  
            gridView: this.state.gridView, 
            retrievedFilePointerPath: path,
            clipboard: this.state.clipboard 
        },
		      this.loadFilesFromServer);
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
            startInProgess(); 
            const isEmpty =  this.state.retrievedFilePointerPath.length == 0;
            if (isEmpty) {
                //create new root-dir
                console.log("creating new entry-point "+ newFolderName);
                return userContext.createEntryDirectory(newFolderName)
                    .then(clearInProgress)
                    .then(this.loadFilesFromServer);
            }
            else {
                console.log("creating new sub-dir "+ newFolderName);
                const lastRetrievedFilePointer =  this.lastRetrievedFilePointer();
                return lastRetrievedFilePointer.mkdir(newFolderName, userContext)
                    .then(clearInProgress)
                    .then(this.loadFilesFromServer);
            }
            this.componentDidMount();
	    
        }.bind(this));
    },

    render: function() {
        var banner = <div className="alert alert-danger"><strong>WARNING:</strong> This is a demo server and all data will be occasionally cleared.</div>;
        if (userContext == null) 
            return (<div className="container form-signin"><div>
		    {this.props.isDemo && banner}
                    <h2>Peergos</h2>
                    <center>
		    <img src="images/logo.png"/>
                    </center>
                    <div id="signup-form">
                    </div>
                    <div id="login-form">
                    <h2>Please log in</h2>
                    <div  className="form-group">
                    <input placeholder="Username" id="login-user-input" className="form-control" type="text" autoFocus={true}/>
                    </div>
                    <div className="form-group">
                    <input placeholder="Password" id="login-password-input" className="form-control" type="password"/>
                    </div>
                    <button id="loginButton" className="btn btn-large btn-block btn-success" >Login</button>
                    <button id="signupButton" className="btn btn-large btn-block btn-primary" >Sign up</button>
                    </div></div>
                    </div>);
	
        const files = this.state.files.map(function(f) {
            return (<File id={File.id()} gridView={this.state.gridView} onClick={f.onClick} onDragStart={f.onDragStart} onDragOver={f.onDragOver} onDrop={f.onDrop} name={f.name} isdir={f.isDir} size={f.size} browser={this} retrievedFilePointer={f.filePointer} hasThumb={f.hasThumb} thumbURL={f.thumbURL}/>)
        }.bind(this)); 

        const jumbo = files.length != 0 ? (<div></div>) : 
            this.lastRetrievedFilePointer().isWritable() ? addStuffComponent : sharedByComponent(this.lastRetrievedFilePointer().getOwner());
	
        const gridGlyph = "glyphicon glyphicon-th-large tour-view";
        const listGlyph = "glyphicon glyphicon-list tour-view";
        const element = document.getElementById("altViewSpan");
        const className = this.state.gridView ? listGlyph : gridGlyph;
        element.className = className;
        var layout = null;
        var  browserContextMenu = (<div id="browser-context-menu">
                                   <ul className="dropdown-menu" role="menu">
                                   <li><a tabIndex="-1">Paste</a></li>
                                   </ul>
                                   </div>);
	
        var  contextMenu = (<div id="context-menu">
                            <ul className="dropdown-menu" style={{cursor: "pointer"}} role="menu">
                            <li><a tabIndex="-1">Open</a></li>
                            <li className="divider"></li>
                            <li><a tabIndex="-1">Rename</a></li>
                            <li><a tabIndex="-1">Delete</a></li>
                            <li className="divider"></li>
                            <li><a tabIndex="-1">Copy</a></li>
                            <li><a tabIndex="-1">Cut</a></li>
                            <li><a tabIndex="-1">Paste</a></li>
                            <li className="divider"></li>
                            <li><a tabIndex="-1">Create public link</a></li>
                            <li className="divider"></li>
                            <li><a tabIndex="-1">Share</a></li>
                            <li><a tabIndex="-1">Readers</a></li>
                            </ul>
                            </div>);

        var progressBarWidget =  (<div><div id="downloadprogressbar" ></div>
                                  <div id="uploadprogressbar" ></div></div>);
        layout = null; 
	
        if (this.state.gridView) 
            return (<div>
                    {progressBarWidget}
                    {jumbo}
                    {files}
                    {contextMenu}
                    {browserContextMenu}
                    </div>)
	
        const sortGlyph = "glyphicon glyphicon-sort";
	
        return (<div>
                {progressBarWidget}
                <table className="table table-responsive table-striped table-hover">
                <thead><tr>
                <th><button onClick={this.pathSort} className="btn btn-default"><span className={sortGlyph}/>Path</button></th>
                <th><button onClick={this.sizeSort} className="btn btn-default"><span className={sortGlyph}/>Size</button></th>
                </tr></thead>
                <tbody>
                {files}
                </tbody>
                </table>
                {jumbo}
                {contextMenu}
                {browserContextMenu}
		
                </div>)
    }
});

var isDemo = window.location.hostname == "demo.peergos.net";
React.render(
        <Browser isDemo={isDemo} />,
    document.getElementById('content')
);

function checkBrowserCapabilities() {
    if (typeof Promise == "undefined")
	populateModalAndShow("Upgrade your browser", "Please use a modern browser. Chrome, Firefox and Safari are supported.");
}

checkBrowserCapabilities();
