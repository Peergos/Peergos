package peergos.client;

import java.io.IOException;
import java.util.Optional;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.RootPanel;

import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileTreeNode;
/*
 * todo-test
 * todo-fix
 * 
 * 
 * 
 * 
 * 
 * 
 */
public class Peergos implements EntryPoint {
	public void onModuleLoad() {
		final Button sendButton = new Button("Send");
		RootPanel.get().add(sendButton);
      try {
		
			UserContext context =  UserContext.ensureSignedUp("test02", "test02", 8000);
			Optional<FileTreeNode> rootDirOpt = context.getByPath("/test02");
		      // now upload a file
		      int size = 5*1024*1024;
		      String filename = "Somefile";
				rootDirOpt.get().uploadTestFile(filename, size, context);
		      Optional<FileTreeNode> fileOpt = context.getByPath("/test02/"+filename);
	          System.out.println(fileOpt);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
}
