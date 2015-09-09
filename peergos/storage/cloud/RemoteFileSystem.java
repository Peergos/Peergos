package peergos.storage.cloud;

import java.util.ArrayList;

public class RemoteFileSystem {
	private RemoteFile rootFile;
	public RemoteFileSystem(String root){
		rootFile = new RemoteFile(root);
	}
	public RemoteFile getRoot()
	{
		return rootFile;
	}
	private RemoteFile getDirectory(String filename)
	{
    	String[] levels = directoryLevels(filename);
    	RemoteFile dir = getRoot();
    	for(int i=0;i< levels.length; i++){
    		String dirName = levels[i];
    		if(!dir.hasSubDirectory(dirName)){
    			return null;
    		}
    		dir = dir.getSubDirectory(dirName);
    	}		
    	return dir;
	}
	private RemoteFile buildDirectory(String filename)
	{
    	String[] levels = directoryLevels(filename);
    	RemoteFile dir = getRoot();
    	for(int i=0;i< levels.length; i++){
    		String dirName = levels[i];
    		if(!dir.hasSubDirectory(dirName)){
    			dir.addSubDirectory(dirName);
    		}
    		dir = dir.getSubDirectory(dirName);
    	}		
    	return dir;
	}
	public ArrayList<String> getMissingDirectories(String filename)
	{
    	String[] levels = directoryLevels(filename);
    	ArrayList<String> missingDirs = new ArrayList<String>();
    	RemoteFile dir = getRoot();
    	boolean missing = false;
    	for(int i=0; i< levels.length; i++){
    		String dirName = levels[i];
    		if(!missing){
	    		if(!dir.hasSubDirectory(dirName)){
	    			missingDirs.add(dirName);
	    			missing = true;
	    		}else{
	    			dir = dir.getSubDirectory(dirName);
	    		}
    		}else{
    			missingDirs.add(dirName);    			
    		}
    	}		
    	return missingDirs;
	}
	public void deleteFile(String filename)
	{
		RemoteFile dir = getDirectory(filename);
    	if(dir !=null){
    		dir.removeFile(filename);
    	}
	}
	public boolean fileExists(String filename)
	{
		RemoteFile dir = getDirectory(filename);
		return dir != null && dir.hasFile(filename);
	}
	public RemoteFile getFile(String filename)
	{
		RemoteFile dir = getDirectory(filename);
		if(dir != null && dir.hasFile(filename)){
			return dir.getFile(filename);
		}else{
			return null;
		}
	}
	public void addFile(String filename, int size)
	{
		RemoteFile dir = buildDirectory(filename);
    	if(dir !=null){
    		dir.addFile(filename, size);
    	}
	}
    public static String buildPathURL(String filename)
    {
    	String[] levels = directoryLevels(filename);
    	StringBuilder sb = new StringBuilder("/");
    	for(int i=0;i< levels.length; i++){
    		sb.append(levels[i]);
    		sb.append("/");
    	}
    	return sb.toString();
    }
    public static String buildFileURL(String filename)
    {
    	return buildPathURL(filename) + "/" + filename;
    }
    private static String[] directoryLevels(String filename)
    {
    	String[] levels = new String[2];
    	if(filename == null){
    		throw new IllegalArgumentException("Filename can't be null!");
    	}
    	if(filename.length() <2){
    		levels[0] = "a";
    		levels[1] = "a";
    	}else{
    		levels[0] = "" + filename.charAt(0);
    		levels[1] = "" + filename.charAt(1);   		
    	}
    	return levels;
    }
    
    public static void main(String[] args){
    	String base = "peergos";
    	RemoteFileSystem fs = new RemoteFileSystem(base);
    	RemoteFile root = fs.getRoot();
    	String file2 = "file2.txt";
    	fs.addFile(file2, 2);
    	String file3 = "file3.txt";
    	RemoteFile tempfile = fs.getFile(file2);
    	if(tempfile == null || !tempfile.getName().equals(file2) || tempfile.getSize() != 2){
    		throw new Error("something wrong tempfile=" + tempfile.toString());
    	}
    	fs.addFile("file3.txt", 3);
    	tempfile = fs.getFile(file3);
    	if(tempfile == null || !tempfile.getName().equals(file3) || tempfile.getSize() != 3){
    		throw new Error("something wrong tempfile=" + tempfile.toString());
    	}
    }
}
