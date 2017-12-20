package peergos.server.tests;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import peergos.server.corenode.JDBCCoreNode;
import peergos.server.storage.RAMStorage;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.TweetNaCl;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.curve25519.Ed25519;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.storage.ContentAddressedStorage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

public class JDBCCoreNodeTests {
  private final ContentAddressedStorage STORAGE = RAMStorage.getSingleton();
  private Connection conn;

  @BeforeClass
  public static void initClass() throws Exception {
    PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    // use insecure random otherwise tests take ages
    UserTests.setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
  }

  @Before
  public void init() throws IOException, SQLException {
    conn = JDBCCoreNode.buildSqlLite(":memory:");
  }

  @Test
  public void updateChainTest() throws Exception {
    JDBCCoreNode coreNode = new JDBCCoreNode(conn, 5);
    List<String> usernames = Arrays.asList("a", "b", "c", "d", "e");

    Function<String, Boolean> signup  = username -> {
        SigningKeyPair user = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        UserPublicKeyLink.UsernameClaim node = UserPublicKeyLink.UsernameClaim.create(
            username, user.secretSigningKey, LocalDate.now().plusYears(2));
        try {
            PublicKeyHash owner = STORAGE.putSigningKey(
                    user.secretSigningKey.signatureOnly(user.publicSigningKey.serialize()),
                    STORAGE.hashKey(user.publicSigningKey),
                    user.publicSigningKey).get();
            UserPublicKeyLink upl = new UserPublicKeyLink(owner, node);
            return coreNode.updateChain(
                username, Arrays.asList(), Arrays.asList(upl), Arrays.asList(upl));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    };

    //signup max-count of users
    for(String username : usernames) {
      Assert.assertTrue(signup.apply(username));
    }
    // can't keep signing  up
      try {
          signup.apply("f");
          Assert.fail();
      } catch (IllegalStateException e) {}
  }
}
