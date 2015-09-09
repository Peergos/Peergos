package peergos.storage.cloud.webdav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import peergos.storage.cloud.RemoteFileSystemError;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

public class WebDavAPI {

	public static Sardine init(String email, String password)
	{
    	Sardine session = SardineFactory.begin(email, password);
		return session;
	}
	public static void close(Sardine session)
	{
    	try {
    		if(session != null){
    			session.shutdown();
    		}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void createDirectory(Sardine session, String url) throws RemoteFileSystemError
	{
		try {
	    	session.createDirectory(url);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RemoteFileSystemError(e.getMessage());
		}
	}
	public static List<DavResource> list(Sardine session, String url) throws RemoteFileSystemError
	{
		List<DavResource> resources = new ArrayList<>();
		try {
	    	resources = session.list(url);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RemoteFileSystemError(e.getMessage());
		}
		//First entry is the directory itself!
		if(resources.size() >= 1){
			resources = resources.subList(1, resources.size());
		}
		return resources;
	}
	/*
	public static DavResource info(Sardine session, String url, String filename) throws FSError
	{
		List<DavResource> resources = new ArrayList<>();
		try {
	    	resources = session.list(url+"/"+filename, 0);
		} catch (IOException e) {
			e.printStackTrace();
			throw new FSError(e.getMessage());
		}
		DavResource resource = null;
		if(resources.size() > 0){
			resource = resources.get(0);
		}
		return resource;
	}*/
	
    public static void upload(Sardine session, byte[] payload, String url, String filename) throws RemoteFileSystemError {
		try {
			session.put(url+"/"+filename, payload);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RemoteFileSystemError(e.getMessage());
		}
    }
    public static byte[] download(Sardine session, String url, String filename) throws RemoteFileSystemError
    {
	    try {
			return readBytes(session.get(url+"/"+filename));
		} catch (IOException e) {
			e.printStackTrace();
			throw new RemoteFileSystemError(e.getMessage());
		}
    }
	public static void delete(Sardine session, String url, String filename) throws RemoteFileSystemError
	{
        try {
			session.delete(url + "/" + filename);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RemoteFileSystemError(e.getMessage());
		}
	}

    private static byte[] readBytes(InputStream in) throws Error
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            final byte data[] = new byte[1024];
            int count;
				while ((count = in.read(data, 0, 1024)) != -1) {
				    bout.write(data, 0, count);
				}
		} catch (IOException e) {
			e.printStackTrace();
			throw new Error("Could not read data");
        } finally {
            if (in != null) {
                try {
					in.close();
				} catch (IOException e) {}
            }
            try {
				bout.close();
			} catch (IOException e) {}
        }
        return bout.toByteArray();
    }
}
