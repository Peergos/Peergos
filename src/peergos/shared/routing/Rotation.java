package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;

public interface Rotation extends Establishment {

    PublicSigningKey from();
}
