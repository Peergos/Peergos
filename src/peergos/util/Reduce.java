package peergos.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
/*
 * todo:
 * if a file name is duplicated, all occurrences are kept. it does not take into account the directory name!
 */
public class Reduce {

    private static final String READ_FILE = "[Loaded";
    private static final String READ_FILE_END = "/rt.jar]";
    Set<String> filesKept = new HashSet<>();
    private int deleteFileCount = 0;
    private int deleteDirectoryCount = 0;

    private Reduce(ArrayList<String> logContents, String rootDir,Set<String> keepRules)
    {
        if(confirm()){
            Set<String> usedFiles = getFilesTouched(logContents);
            System.out.println("Files found: " + usedFiles.size());
            File directoryFile = new File(rootDir);
            deleteFiles(directoryFile,usedFiles,keepRules);
            System.out.println("Number of directories deleted:" + deleteDirectoryCount);
            System.out.println("Number of files deleted:" + deleteFileCount);
            int missedCount = 0;
            for(String file : usedFiles){
                if(!filesKept.contains(file)){
                    missedCount++;
                }
            }
            if(missedCount > 0) {
                System.out.println("The following list of size " + missedCount + " contains files expecting to keep but couldn't find:");
                for(String file : usedFiles){
                    if(!filesKept.contains(file)){
                        System.out.println(file);
                    }
                }
            }
            System.out.println("done");
        }else{
            System.out.println("aborted");
        }
    }
    private boolean confirm()
    {
        boolean confirmed = false;
        System.out.println("continue? (y,n)");
        try{
            int val = System.in.read();
            //121 y or 89 Y
            if(val == 121 || val == 89){
                confirmed= true;
            }
        }catch(IOException ioe){}
        return confirmed;
    }

    private void deleteFile(File file){
        deleteFileCount++;
        file.delete();
    }

    private void deleteDirectory(File directoryFile){
        deleteDirectoryCount++;
        directoryFile.delete();
    }

    private boolean inKeepList(File directoryFile, Set<String> keepItems)
    {
        String filename = directoryFile.getName();
        if(keepItems.contains(filename)){
            return true;
        }
        //does the file belong to a directory that has been marked as 'to be kept'
        File parent = directoryFile.getParentFile();
        while(parent != null){
            String name = parent.getName();
            if(keepItems.contains(name)){
                return true;
            }
            parent = parent.getParentFile();
        }
        //now the other way, see if any regexp match the file
        String fullFilename = directoryFile.getAbsolutePath();
        for(String regexp : keepItems){
            try{
                if(fullFilename.matches(regexp)){
                    return true;
                }
            }catch(Exception e){}//may not be a valid regexp
        }
        return false;
    }
    private boolean alwaysRemove(String filename)
    {
        if(filename.endsWith(".DS_Store")){//screw you Apple.
            return true;
        }
        return false;
    }
    private void deleteFiles(File directoryFile, Set<String> filesToKeep, Set<String> keepRules){
        try{
            String filename = directoryFile.getName();
            if(directoryFile.isFile()){
                if(alwaysRemove(filename)){
                    deleteFile(directoryFile);
                }else{
                    if(filesToKeep.contains(filename)){
                        filesKept.add(filename);
                    }else if(!inKeepList(directoryFile, keepRules)){
                        deleteFile(directoryFile);
                    }
                }
            }else{
                filesKept.add(filename);
                File[] files = directoryFile.listFiles();
                for(File file : files){
                    deleteFiles(file, filesToKeep, keepRules); //recurse
                }
                File[] afterFiles = directoryFile.listFiles();
                if(afterFiles == null || afterFiles.length == 0){
                    deleteDirectory(directoryFile);
                }
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }


    public Set<String> getFilesTouched(ArrayList<String> contents){
        HashSet<String> files = new HashSet<>();
        for(String line : contents){
            int index = line.indexOf(READ_FILE);
            if(index > -1 && line.endsWith("]")){
                int startIndex = index + READ_FILE.length() + 1;
                String text = line.substring(startIndex);
                int endIndex = text.indexOf(" ");
                if(endIndex > -1) {
                    text = text.substring(0, endIndex);
                    int classIndex = text.lastIndexOf("/");
                    if(classIndex > -1) {
                        text = text.substring(classIndex +1) + ".class";
                    }
                    //System.out.println(text);
                    files.add(text);
                }
            }
        }
        return files;
    }

    private static Set<String> extractList(String[] extractList){
        Set<String> list = new HashSet<>();
        if(extractList.length>2){
            for(int i=2;i<extractList.length;i++){
                list.add(extractList[i]);
            }
        }
        return list;
    }
    public static void main(String[] args) {

        if(args.length < 2){
            System.out.println("Reduce is used to prune Java's SDK lib.");
            System.out.println("Usage: Reduce usagelogfile.txt c:\temp\root Main dirA");
            System.out.println("Where: usagelogfile.txt is the output from running with doppio's equivalent of -verbose:class");
            System.out.println("c:/temp/root is the location of the root file system to be pruned");
            System.out.println("Main dirA is a list of files, dirs, regexp(s) to use to indicate additional files to keep");
            System.exit(-1);
        }
        Set<String> keepRulesList = extractList(args);
        new Reduce(readLog(args[0]), rootDir(args[1]), keepRulesList);
    }
    private static ArrayList<String> readLog(String filename){
        if(filename == null){
            throw new Error("Please specify the filename for the usage log file");
        }
        if(!(filename.startsWith("/") || filename.startsWith("\\"))){
            filename = System.getProperty("user.dir") + "/" + filename;
        }
        File file = checkFileExists(filename);
        System.out.println("Log file:"+filename);
        return readFile(file);
    }
    private static File checkFileExists(String filename){
        File file = new File(filename);
        if(!file.exists()){
            throw new Error("Can't find file:"+filename);
        }
        return file;
    }
    private static File checkDirectoryExists(String filename){
        File file = new File(filename);
        if(!file.exists() || !file.isDirectory()){
            throw new Error("Can't find directory:"+filename);
        }
        return file;
    }
    private static String rootDir(String dir)
    {
        if(dir==null){
            throw new Error("Please specify a root directory to prune");
        }
        if(!(dir.startsWith("/") || dir.startsWith("\\"))){
            dir = System.getProperty("user.dir") + "/" + dir;
        }
        checkDirectoryExists(dir);
        System.out.println("Prune directory:"+dir);
        return dir;
    }
    private static ArrayList<String> readFile(File file)
    {
        ArrayList<String> contents = new ArrayList<String>();
        BufferedReader br =  null;
        try{
            br = new BufferedReader(new FileReader(file));
            boolean finished=false;
            while(!finished){
                String line = br.readLine();
                if(line==null){
                    finished=true;
                }else{
                    contents.add(line);
                }
            }
        }catch(Exception e){
            e.printStackTrace();
        }finally{
            if(br!=null){
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return contents;
    }
}

