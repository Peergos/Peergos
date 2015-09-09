package peergos.storage.cloud;

import java.util.HashMap;

public class RemoteFile {
	private String name;
	private int size;
	private static final int DIRECTORY_SIZE = -1;
	private HashMap<String, RemoteFile> subdirs = new HashMap<String, RemoteFile>();
	private HashMap<String, RemoteFile> files = new HashMap<String, RemoteFile>();
	public RemoteFile(String name){
		this.name = name;
		this.size = DIRECTORY_SIZE;
	}
	public RemoteFile(String name, int size){
		this.name = name;
		this.size = size;
	}
	public boolean isDirectory()
	{
		return size == DIRECTORY_SIZE;
	}
	
	public String getName(){
		return name;
	}
	public int getSize(){
		return size;
	}
	public boolean hasSubDirectory(String childName){
		return subdirs.containsKey(childName);
	}
	public RemoteFile getSubDirectory(String childName)
	{
		return subdirs.get(childName);
	}
	public RemoteFile addSubDirectory(String childName){
		subdirs.put(childName, new RemoteFile(childName));
		return getSubDirectory(childName);
	}
	public boolean hasFile(String filename){
		return files.containsKey(filename);
	}
	public void addFile(String filename, int fileSize){
		files.put(filename, new RemoteFile(filename, fileSize));
	}
	public void removeFile(String filename){
		files.remove(filename);
	}
	public RemoteFile getFile(String filename)
	{
		return files.get(filename);
	}
	public String toString()
	{
		return "name:" + getName() + " size:" + getSize();
	}
}
