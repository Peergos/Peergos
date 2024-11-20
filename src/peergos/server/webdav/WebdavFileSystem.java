/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package peergos.server.webdav;

import org.peergos.util.Pair;
import peergos.server.simulation.InputStreamAsyncReader;
import peergos.server.util.Logging;
import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.IWebdavStore;
import peergos.server.webdav.modeshape.webdav.StoredObject;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;
import peergos.shared.util.Serialize;

import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.security.Principal;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Integration with Peergos File system
 * This class was based on org.modeshape.webdav.LocalFileSystemStore
 * which in turn was based on reference Implementation of WebdavStore
 * Originally by:
 * @author joa
 * @author re
 * @author hchiorea@redhat.com
 */
public class WebdavFileSystem implements IWebdavStore {

    private static final Logger LOG = Logging.LOG();

    private final UserContext context;

    public WebdavFileSystem(String username, String password, String peergosUrl) {
        Crypto crypto = Main.initCrypto();
        try {
            NetworkAccess network = Builder.buildJavaNetworkAccess(new URL(peergosUrl), peergosUrl.startsWith("https")).join();
            context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, ex, () -> "Unable to connect to Peergos account");
            throw new IllegalStateException("Unable to connect to Peergos account: ", ex);
        }
    }

    @Override
    public void destroy() {
    }

    @Override
    public ITransaction begin(Principal principal ) throws WebdavException {
        LOG.fine("PeergosFileSystem.begin()");
        return null;
    }

    @Override
    public void checkAuthentication( ITransaction transaction ) throws SecurityException {
        LOG.fine("PeergosFileSystem.checkAuthentication()");
    }

    @Override
    public void commit( ITransaction transaction ) throws WebdavException {
        LOG.fine("PeergosFileSystem.commit()");
    }

    @Override
    public void rollback( ITransaction transaction ) throws WebdavException {
        LOG.fine("PeergosFileSystem.rollback()");
    }

    private Optional<FileWrapper> getByPath(Path path) {
        return context.getByPath(path.toString().replace('\\', '/')).join();
    }

    @Override
    public void createFolder( ITransaction transaction,
                              String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.createFolder(" + uri + ")");
        Path path = new File(uri).toPath();
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty() || parentFolder.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find parent of folder: " + uri);
        }
        try {
            parentFolder.get().mkdir(path.getFileName().toString(), context.network, false, context.mirrorBatId(), context.crypto).join();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, ex, () -> "cannot create folder");
            throw new WebdavException("cannot create folder: " + uri);
        }
    }

    @Override
    public void createResource( ITransaction transaction,
                                String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.createResource(" + uri + ")");
        Path path = new File(uri).toPath();
        if (path.getFileName().toString().startsWith("._") || path.getFileName().toString().equals(".DS_Store")) { // MacOS rubbish!
            return;
        }
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty() || parentFolder.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find parent of file: " + uri);
        }
        byte[] contents = new byte[0];
        try {
            parentFolder.get().uploadOrReplaceFile(path.getFileName().toString(), new AsyncReader.ArrayBacked(contents), contents.length, context.network, context.crypto, l -> {}).join();
        } catch (Exception e) {
            LOG.warning("PeergosFileSystem.createResource(" + uri + ") failed");
            throw new WebdavException(e);
        }
    }

    @Override
    public long setResourceContent( ITransaction transaction,
                                    String uri,
                                    Pair<AsyncReader, Long> readerPair,
                                    String contentType,
                                    String characterEncoding ) throws WebdavException {

        LOG.fine("PeergosFileSystem.setResourceContent(" + uri + ")");
        System.out.println("PeergosFileSystem.setResourceContent(" + uri + ") length=" + readerPair.right);
        Path path = new File(uri).toPath();
        if (path.getFileName().toString().startsWith("._") || path.getFileName().toString().equals(".DS_Store")) { // MacOS rubbish!
            return 0;
        }
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty() || parentFolder.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find parent of file: " + uri);
        }
        try {
            System.out.println("setResourceContent calling upload");
            parentFolder.get().uploadOrReplaceFile(path.getFileName().toString(), readerPair.left, readerPair.right, context.network, context.crypto, l -> {}).join();
            System.out.println("setResourceContent upload complete");
            return readerPair.right;
        } catch (Exception e) {
            e.printStackTrace();
            LOG.warning("PeergosFileSystem.setResourceContent(" + uri + ") failed");
            throw new WebdavException(e);
        }
    }

    @Override
    public String[] getChildrenNames( ITransaction transaction,
                                      String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.getChildrenNames(" + uri + ")");
        Path path = new File(uri).toPath();
        Optional<FileWrapper> folder = getByPath(path);
        if (folder.isEmpty() || !folder.get().isDirectory() || folder.get().getFileProperties().isHidden) {
            return new String[0];
        }
        Set<FileWrapper> children = folder.get().getChildren(context.crypto.hasher, context.network).join();
        List<String> filenames = children.stream()
                .filter(f -> !f.getFileProperties().isHidden)
                .map(f -> f.getName()).collect(Collectors.toList());
        String[] childrenNames = new String[filenames.size()];
        return filenames.toArray(childrenNames);
    }

    @Override
    public void removeObject( ITransaction transaction,
                              String uri ) throws WebdavException {
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fw = getByPath(path);
        if (fw.isEmpty() || fw.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find: " + uri);
        }
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty()) {
            throw new WebdavException("cannot find parent folder of: " + uri);
        }
        try {
            fw.get().remove(parentFolder.get(), path, context).join();
        } catch (Exception ex) {
            throw new WebdavException("cannot delete object: " + uri);
        }
    }

    @Override
    public Pair<AsyncReader, Long> getResourceContent(ITransaction transaction,
                                                      String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.getResourceContent(" + uri + ")");
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fw = context.getByPath(path.toString()).join();
        if (fw.isEmpty() || fw.get().isDirectory() || fw.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find file: " + uri);
        }
        try {
            return new Pair<>(fw.get().getInputStream(context.network, context.crypto,
                    fw.get().getSize(), l-> {}).join(), fw.get().getSize());
        } catch (Exception e) {
            LOG.warning("PeergosFileSystem.getResourceContent(" + uri + ") failed");
            throw new WebdavException(e);
        }
    }

    @Override
    public long getResourceLength( ITransaction transaction,
                                   String uri) throws WebdavException {
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fw = context.getByPath(path.toString()).join();
        if (fw.isEmpty() || fw.get().isDirectory() || fw.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find file: " + uri);
        }
        return fw.get().getFileProperties().size;
    }

    @Override
    public StoredObject getStoredObject(ITransaction transaction,
                                        String uri ) {
        StoredObject so = null;
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fwOpt = context.getByPath(path.toString()).join();
        if (fwOpt.isPresent() && !fwOpt.get().getFileProperties().isHidden) {
            so = new StoredObject();
            FileWrapper fw = fwOpt.get();
            so.setFolder(fw.isDirectory());
            so.setLastModified(new Date(fw.getFileProperties().modified.toEpochSecond(ZoneOffset.UTC) * 1000));
            so.setCreationDate(new Date(fw.getFileProperties().created.toEpochSecond(ZoneOffset.UTC) * 1000));
            so.setResourceLength(fw.getFileProperties().size);
        }
        return so;
    }

    @Override
    public Map<String, String> setCustomProperties( ITransaction transaction,
                                                    String resourceUri,
                                                    Map<String, Object> propertiesToSet,
                                                    List<String> propertiesToRemove ) {
        LOG.fine("PeergosFileSystem.setCustomProperties(" + resourceUri + ")");
        return null;
    }

    @Override
    public Map<String, Object> getCustomProperties( ITransaction transaction,
                                                    String resourceUri ) {
        LOG.fine("PeergosFileSystem.getCustomProperties(" + resourceUri + ")");
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getCustomNamespaces( ITransaction transaction,
                                                    String resourceUri ) {
        return Collections.emptyMap();
    }
}
