package peergos.storage.cloud.webdav;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.*;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;

import peergos.storage.Storage;
import peergos.storage.cloud.RemoteFile;
import peergos.storage.cloud.RemoteFileSystem;
import peergos.storage.cloud.RemoteFileSystemError;

public class WebDavStorage implements Storage
{
    private final AtomicLong remainingSpace = new AtomicLong(0);
    private static final String DIRECTORY = "peergos";
    private String webdavUrl;
    private Sardine session;
    
    private RemoteFileSystem cache = new RemoteFileSystem(DIRECTORY);
    
    public WebDavStorage(long maxBytes, String webdavUrl, String emailAddress, String password) throws IOException
    {
        remainingSpace.addAndGet(maxBytes);
        this.webdavUrl = webdavUrl;
        this.session = WebDavAPI.init(emailAddress, password);
        
        if (!rootExists()){
        	createRoot();
        }else {
			try {
				readAllFiles("", cache.getRoot());
			} catch (RemoteFileSystemError e) {e.printStackTrace();}
        }
    }
    private String getBaseDirectory()
    {
    	return webdavUrl + "/" + DIRECTORY;
    }
    private void createRoot()
    {
    	createDirectory("");
    }
    private void createDirectory(String dir)
    {
    	try {
    		WebDavAPI.createDirectory(session, getBaseDirectory() + "/" + dir);
		} catch (RemoteFileSystemError e) {
			e.printStackTrace();
		}
    }
    private boolean rootExists()
    {
    	try {
			List<DavResource> resources = WebDavAPI.list(session, getBaseDirectory());
			if(resources != null && resources.size() > 0){
				return true;
			}
		} catch (RemoteFileSystemError e) {
			//this happens if directory not found.  e.printStackTrace();
		}
    	return false;
    }
    private void readAllFiles(String subDirectory, RemoteFile dir) throws RemoteFileSystemError
    {
    	List<DavResource> resources = WebDavAPI.list(session, getBaseDirectory() + subDirectory);
    	for(DavResource file : resources){
    		if(file.isDirectory()){
    			RemoteFile childDir = dir.addSubDirectory(file.getName());
    			readAllFiles(subDirectory + "/" + file.getName(), childDir);
    		}else{
    			dir.addFile(file.getName(), file.getContentLength().intValue());
                remainingSpace.addAndGet(-file.getContentLength());		    			
    		}
    	}
    }
    public void close()
    {
    	WebDavAPI.close(session);
    }
    public long remainingSpace() {
        return remainingSpace.get();
    }

    public boolean put(String key, byte[] value) throws IOException
    {
    	ArrayList<String> missingDirs = cache.getMissingDirectories(key);
    	String dirs = "";
    	for(String dir : missingDirs){
    		dirs = dirs + dir + "/";
    		createDirectory(dirs);
    	}
        new Fragment(key).write(value);
		cache.addFile(key, value.length);
        remainingSpace.addAndGet(-value.length);		    			

        return true;
    }

    public boolean remove(String key) throws IOException {
    	try {
			WebDavAPI.delete(session, getBaseDirectory() + RemoteFileSystem.buildPathURL(key), key);
			int len= cache.getFile(key).getSize();
			cache.deleteFile(key);
	        remainingSpace.addAndGet(len);		    			
		} catch (RemoteFileSystemError e) {
			return false;
		}    	
        return true;
    }

    public byte[] get(String key)
    {
        try {
            return new Fragment(key).read();
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public boolean contains(String key)
    {
        return cache.fileExists(key);
    }

    public int sizeOf(String key)
    {
    	RemoteFile f = cache.getFile(key);
    	if(f == null){
    		return 0;
    	}else{
    		return f.getSize();		
    	}
    }

    public class Fragment
    {
        String name;

        public Fragment(String name)
        {
            this.name = name;
        }

        public void write(byte[] data) throws IOException
        {
        	try {
				WebDavAPI.upload(session,data, getBaseDirectory() + RemoteFileSystem.buildPathURL(name), name);
			} catch (RemoteFileSystemError e) {
				e.printStackTrace();
			}
        }

        public byte[] read() throws IOException{
	        byte[] data = null;
			try {
				data = WebDavAPI.download(session, getBaseDirectory() + RemoteFileSystem.buildPathURL(name), name);
			} catch (RemoteFileSystemError e) {
				e.printStackTrace();
			}
            return data;
        }
    }
}
