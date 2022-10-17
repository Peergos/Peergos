package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;

public class FragmentedPaddedCipherTextTests {

    private static final Crypto crypto = JavaCrypto.init();

    @Test
    public void legacyFormatWithinlinedIdentityMultihash() {
        byte[] raw = ArrayOps.hexToBytes("a2616681d82a591016000155009020426139995416ed3d728693c3438c4269418752de1392aeb3aa6cb57ec4974b46a596d37967e961636a4a74e5c7d8c929097e18145b76a4d4cea156034dda281da91ecb2eae053cd441000dc0cc44ca7365cc491b9ca16cf2dde4ff95aa5c94079172d826a3eaab5db2d09125a8b1cba4319dfe793c339a4f1265e9339ebc53e224dcbeaf70dc5b3e01f462bf7efe8b0aad9c03d776a270c52dca62446739d4a0bb73adda1253354f1c7b0e2daa5a9b8062cf267188ac51d4860399e0e9a93e762dbd97bd0f96eff8b53d9346563e514071dd361577e7b5d041bddb9b8ec9c1bab602356f1c3f8acd4119fff32ddd3241fff978c54e00926a9696dd638bb89e8012e43755a6f9401ab4cf61459ee6785176ac78778859292cd5e8b2d2ee4cea46ed2ebd334b463119cf144de67bfd70b0bf9798ae636ce028ba6bcf701dff2f3edfef0d50fb8ba83c868fe0ae0fc5a4423b24b92227b71800710d7eebe0820332cafda64253dd004d7fc165d05dd1b1a7024f8ce307b33de3a2a3ea4b9a1767ae6ec6ad3de9c3c0c345baacd81c9a4958db1adb39fbacb9f84d6ee22b9191589483510148e1037a6811728fb4b94b9a707f0ee078aaabad9986d502842a22feb37894fa4d3c675a508d00a0256cd132e06f973c6d56c8d22e327a535af4688586ee96db4520ec1e356a3954af8068fbea5db2aea1c4783eb1f39c3cfd66f2db39357d2ccfd2aa9d52adb01fd28db7c374583a3409ef81846b209089618c18703187ff13cbc2ea8319d8bc5214849e96e035bcfbd35b3c91bf260f07ea35a28fde793f85fd5f2680f2821a0f0b24380d5add993ea63edaa6ff9275c8ca228959b7a2939dfeca9f64c448241d2aa0ce1bef92155363eeaf36f94455688e8f7c75614b75bb0eb99e8860cef3ffb8aba37a5ec624dc5708807925cf886846336cb8722d424d7b7e369e94e584c4bb1a3a6558cae771b46d6c3c2b0c954b98419982a4a6919f37f09608c6ff3d8ae17274fe9671ca23aac2c2ff6fc1058994379706347d66baa72ebeaa00c840dba6e97b194e1c26ca143527fd09f62da58f4546f45970e01de0c2b416ac4a1c5e2f2c6da411f5bb8ba1c7796b46f689c95696a40dda60f49d3694914853453bdb5cf62a5c76f39c4764adca9ec5336711714ed15ff5ef8dbf92d2921fa0a3c1b6ecd793ffa8cc37f8465e40ea9c130fe0b06a67c491cf869a6991fbbe7034b2ce19965890c37c35abf4348171cc9faa1bb3d6f6f7e9b5970f48f3878383cb1c5eea862172fda59f6b93a27697e6f1bbb8da161d1fa1798e5d40bcb654876884fb7cdd0358eca2a29d4b2bbf2f6e2b67aa38b32a1e6622ce1a27531b9110fd2bc0dce02f9b69175a39dfda0838496c03e44cd28ad2760781de44a7190e41333e87e2fcd0b1769092d1535d13b63fb9fd27c9e6653e19ebc2c6214b20dc7c2b607ba6c7465ca9434c99db482db695cea8cd33ccdd2197abab290eb46fac60c37a8ff42f761eb13a494c2a6ef18dcb23162c232cb386d97510560d31357f5fde35f097f988d25ddf7e8679d268e61f5b6bd32c924038e7ef971b91241905d050507cf19ff28164f7ed11a6a76c589071a5416eb048fe57cb9fe9f0d9d534fb916af0b22a21fa6785a8176f4d1424b1442d2d635c156e5c6746668244ee5b88c1bd1e50a821572a09d957c37bf8e3a5c831fb98dd6607149fb58791524e6569ab97185dec6b81532ce456f388d2d62f136e208faaa5e5d63e63066078cc45569615c149581669d3744310dba61464554153d6b9620f97c38893fe6b7ce3e6b964770e77730e3844753efffea82b0936382af790bd13aee4f605f150296f41a98dc3876a20ddf33a4ed1ff312f20731ca685c927f6f64643e4dc31fc5d1192ec35ed7f88673e69fd330a004d0e50cc07137686edbb7a86f01fc274b363482694a512ce3a9754cbd23f324a71a822d173d5a3124f3b432783856cc73044f86bb8d141da775b7227e817d963db8c0ce6b8207710492b95623c693e06a285ea34c265fb0bf270e670b8d923797fa11994c8743c3209ecfe9579b7c51c7d69a132b6bbf77562a7241cbc8441caf30477fa81c9b2ed9b9b08420975b668654c2152c6d83ee7fbe49d9e5884336aeb2eef65af615f617133210ae2e22f1642f66997b64adb022ed8b413613b9a4e9f3a1ae6f5385f33b4575e77c5d2c8fc13ccc93c02cb33f71b5eaf0f86aa374ab0272ce998b538906f8bec182d38faa1ef0f4b14d29b84f8ce27cea4e717b7bcc10ea462828a685e42dd56c1886ab371bbb03ef7b8beea55c216679ec8aaa548aa01c8af4d47890f437e6682329900bfe5eb9d4a420c47ee4838cb220199b934ed9e58ae174b0bd9a89ac9dea59291f3ccd98a5fab93560662cf9477c152efbc3ef84a807d089130c1d570146a5879d4a94d96975c4e60e99ae3b27d68e43843ce33ebc399626819615a5c31d877bd8e372df1ba6c7ffcbf2f1cbb965b2c9045e905a97f6ebdf6a03ebaf329b2edc249c61ea2b38526e1daf2814621a89ec0e21fe9d544bddf6c60666042465f68363f938b76f786df6fb51ac59e1080f642186ddd340017efb920ae5e9458de2c764685702e367b49a732bf66d928d2df237205bbe96c5e0586fc4e8a63aa8dc501940c8ace556992e361d2c408a0ef7c54bde34a81431da532231e187a0ca2ecf17b0ce55bc14f6a6d04ac72e510246c46b7a105ccbc9973684387a5b3d8e79f69bebae8d268dbe5ab8d69e44bb852a77aa240facf0b095acb280ef2c015cd723620568220f97e3f87909d206ac4b0bfa2203e98675dc7161fe25f19fc74856a40b7068bb09f66ffdf9cc81bec6d414158d0ec8d06ffb4da1674c9349c06e70d858957e03ba3ab3908ebc306a46366f6aeda040571bdbb794e5fd2e9ac79c86a9450bceae55d383eab1a408ea522d570ec9357cfb58d2cdaf81745865f2310da8d9ebd0d9a3ac1a1e36296772204b9c6dc9ac4cada5e44336390936a7543d37008335dba382fc82990595b4a0f102c9a59ab74df6364567e3af1e7401dcc1e19f4f2bdc7013dcf01c9fa7e0ff7bff687e09720facc129746dec9c73bb1a63302de3925b07e703d9f75b54f9bf6de76f53c8f07ee47eb157e07f6582027e951a1f40c017906770193e320895166e711bd956aa83a81a77258c1e4b06533fc0cd208877d4b56c7250cca0420c5edba7e4bbb2eb4e44b93e6a6788a262b1c4b18fabd641d4ce2a5cbf1c029c663c94447d617357b36574f3bdbd18d411f52c21f0def02d3c4c43faa99918ff9f7de26f7b510c7d690c14f083e1d7aee8176754ca2140b7f2c938a8fe777f50cd83604450fb1c39bc4ab87e5584ca38994442f4a38c791ed24e275ee7755dfc16fda062aeae761e4d1e28d9eb0c370e1b86250a657b76a2c1c514a1ad21df990b082f3d9b69ad0e8cfcde4b435ff3b55ac481bff1b02cea0077f6cee2a0ad6e1c8ab8b390d7249ab19f9d65da00f2837b04d1684f079bd998d2563036bbcfb4303d0f62a7cd60b2b45b4aeeeb71508aac6c32960a9881ac061b67757e226fc6f0957928372b88ae5a1d216f7147ff8c6283ede139003f4dccf39cd4b19ac838b7da2748c8fdb193388b67af15c59c6e460050c087316cd1acaba946d626e532326e0f577ce908ba3cc687e2b911983124a35952027ecc92cf1ae319663a1433c3910f1132ab5df7aba0f3f88c0087ba99c1872c18b6295479d7290b10a94d9b5b9e144e8320605e49af4b0455d7f65b05b605861a4de5242912970bc0fe0b05b0646ccb01083127b20d843338558d338a518538aae33ea4734d8b92221bc821a18c1b3fb75de30692a39485e29d1dad3952be86251218dfeaeac330dafd164470ff5c1b892c598fdcff7ab2c89fdbb4af1349b9a3098ee9e2db34c16e8eb044a71951bc2464ae90b4a12df576dcd71e2c7c41a4107ce52911826d4815ae499391b869cfba72a13dfae2d146d666d1c94f2bde16063f62129c8c12c9622ae926b61c000d40ef16b7279c1b44add8c8060087930abd691080d645b1df59f9ef83e803c7f7b36f666fe017749874aa237b066847c6993734fd6d934329b0cb3d8ecbcd794298c06cb4de370f78c9f6b82df25c440181d0341cc9a58d4b47b9f9b07733313c2b98438d0763d8420bfbe40ff8808e5e9c01da43608e395112fe3aa9dc17c71eef3c5c56bd6be2a0fae4fa00fb8cfa56461e182ac2a2067bc94eb35ff596ba71b64a7de063e5be2eb2e0606b86d2777b6b00e2d700bbeaa36efeff51495d92238f068414fc69b8391039f72335ccf997dff25a988fbf1c7ce60a05c5d49fe6b7554c04ca40e0eca32817a480f547e9315e1cb4743552a1076f02a49590ec0f7a58f122c7c58c9ad5e3ccad5aadbac0fe16795017d013c446b7368605966582819141ec43db37b3e645947a3f101c76b231c38fdfd6da9e2e2a3b46039c9dbeb2ff2dc8bde8d79420211dd34a177fbc02a5f565c6acfeabc4137db9c8e0590580c94cfbb27eedd19c26817bbb4cc9d77b09e7a4ad8f70f19a633513ae3e55cd193328ea2a32c1f9f2e0b90da8b3cbab0df63eb5cecf2aa35b8bc9dc654452c928a36e352671d2a80d36603200d0ae38fc3dc74635cc6930717548f1355a49ecdf445fd87a543dda919fc641681e4cb83b049463d0c217cbb9b0b69663a32cdc3f149d5e80191e05b8052da771dfc9b05a842cacd47d35e2161cbdeb8ba99c295d1631adc42c658020282e6a2ecad001f3302766058e9a514d15932ad8994ef6f1c83f525e12b63b1abd4729b490a36b4d98684f0916826e35257b1f913f2bf275d69a0b84afe40734ac5d6ff51b3a2e3431fa1de1c0ae69739e015738e3349e1ba47885f6e2a4001f335a2cd3e49637664d8ae192737b8348e2de97c2e8d6860be054b0675a2d5272c669800c5e01088f4c9e29deafa254f263d221688dcce2d454e349f9602b004806e1c6992987bcb80c0ae07d7649c3bd563de0191827945237caec16e4a92730ad94346849c062b1fc3ef2fad3556c82269e694d2efc70f3c143d95d2301dbbea5ea0db35fb9d2c58c89cfcde170408ba59517b654109b0dbc9a62cff073b3d6261b644d95433ded05e28246fe434b5e2c0172cba09adfc46ef12076635c7cfdc1354189c9d12ab58843cf6d498abc7d963733ad966be1301d3fe4ea84102d6710c9b370ff3788182b24273d17f7382e96ec8a78bf6b4d22eb7301f5e04b6e0db2e79c6ffe6b274f0b928b4a7c8859891b0289bb7831155408c76a7b717bc65ad9ea9341b642e431152520884c2e511bdcdd4104d9dd2c6dbf631db2fadcc6ef658ff6da09e0f5d6c094253cefe6637d31130187bce44701a2d6cbdd71b77179a9bf55490855e4784c140a5c1baca15781a7cae6e33069c395534a636de8f05fc5406fbcb8a3929b402f5a332a7d0d44ab1abebfc912dd3ab5f571debfbfeab4a8fc740fee7726fd2cc5acb1f741cfd28cc478c1c7cc7a52dbf0a7dba2763743c893481af53bc5be2dd2595a7040858d6a5479bba4676b51f7e50071fa4caf0ed39f56fe3a96e2f56e56b84d6b8095ec0a669b6f579ae9ae1bf9dc54845edb41a84bbd43b4c92b8f209c3395f4ff1406714b348cf593fc34032be0f55e4b8604a40030c2b543db929524f23ea7eae4fd4b8ba6bb49dd7cc0c6453a1ee4933d1e059c11d05b851cd7ec4964e827ce67df111814b8d1c95e3a81e78011f68a6ff21a0845ff17622de27469f9c938af0e71e8c50acdcb0652c40616e581881c0e4b17db05731488b56c75275c5f58302167c1a783b3b");
        // test we can parse this legacy format correctly
        FragmentedPaddedCipherText.fromCbor(CborObject.fromByteArray(raw));
    }

    @Test
    public void fragmentCountAndAlignment() {
        SymmetricKey from = SymmetricKey.random();
        for (int len: List.of(0, 4000, 4093, 4096, 4099,
                Fragment.MAX_LENGTH - 3, Fragment.MAX_LENGTH, Fragment.MAX_LENGTH + 3,
                Chunk.MAX_SIZE - 4, Chunk.MAX_SIZE)) {
            byte[] data = new byte[len];
            int paddingBlockSize = 4096;
            Optional<BatId> mirrorBat = Optional.of(Bat.random(crypto.random).calculateId(crypto.hasher).join());
            Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> p = FragmentedPaddedCipherText.build(from,
                    new CborObject.CborByteArray(data), paddingBlockSize, Fragment.MAX_LENGTH, mirrorBat, crypto.random, crypto.hasher, false).join();

            Assert.assertTrue("block sizes, len: " + len, p.right.stream()
                    .allMatch(f -> Bat.removeRawBlockBatPrefix(f.fragment.data).length % paddingBlockSize == 0));
            Assert.assertTrue("# blocks, len: " + len, p.right.size() <= Chunk.MAX_SIZE / Fragment.MAX_LENGTH);
            int maxInlineSize = 4096 + 6;
            if (data.length > maxInlineSize)
                Assert.assertTrue("# blocks exact, len: " + len, p.right.size() == (data.length + Fragment.MAX_LENGTH - 1) / Fragment.MAX_LENGTH);
            if (data.length <= maxInlineSize)
                Assert.assertTrue("len: " + len, p.right.size() == 0);
        }
    }

    @Test
    public void directorySmallFileEquality() {
        SymmetricKey from = SymmetricKey.random();
        byte[] data = new byte[0];
        int paddingBlockSize = 4096;
        Optional<BatId> mirrorBat = Optional.of(Bat.random(crypto.random).calculateId(crypto.hasher).join());
        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> file = FragmentedPaddedCipherText.build(from,
                new CborObject.CborByteArray(data), paddingBlockSize, Fragment.MAX_LENGTH, mirrorBat, crypto.random, crypto.hasher, false).join();

        Pair<FragmentedPaddedCipherText, List<FragmentWithHash>> dir = FragmentedPaddedCipherText.build(from,
                CryptreeNode.ChildrenLinks.empty(), paddingBlockSize, Fragment.MAX_LENGTH, mirrorBat, crypto.random, crypto.hasher, false).join();

        Assert.assertTrue("cbor length", file.left.serialize().length == dir.left.serialize().length);
        CborObject fileCbor = file.left.toCbor();
        CborObject dirCbor = dir.left.toCbor();
        Assert.assertTrue("cbor stuctural equality", structurallyEqual(fileCbor, dirCbor));
    }

    private static boolean structurallyEqual(Cborable a, Cborable b) {
        if (!a.getClass().equals(b.getClass()))
            return false;
        if (a instanceof CborObject.CborByteArray)
            return ((CborObject.CborByteArray)b).value.length == ((CborObject.CborByteArray) a).value.length;
        if (a instanceof CborObject.CborList){
            List<? extends Cborable> aVals = ((CborObject.CborList) a).value;
            for (int i=0; i < aVals.size(); i++)
                if (! structurallyEqual(aVals.get(i), ((CborObject.CborList)b).value.get(i)))
                    return false;
            return true;
        }
        if (a instanceof CborObject.CborMap) {
            CborObject.CborMap aMap = (CborObject.CborMap) a;
            for (String key : aMap.keySet()) {
                if (! structurallyEqual(aMap.get(key), ((CborObject.CborMap)b).get(key)))
                    return false;
            }
            return true;
        }
        return true;
    }
}
