package com.opensecurity.remotelink.crypto;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/** X25519 DH 桥接（Kotlin 的 init 关键字与 BC 方法名冲突——Java 侧收口）。 */
public final class Xdh {
    private Xdh() {}

    public static byte[] agree(byte[] priv, byte[] pub) {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(new X25519PrivateKeyParameters(priv, 0));
        byte[] out = new byte[32];
        agreement.calculateAgreement(new X25519PublicKeyParameters(pub, 0), out, 0);
        return out;
    }

    public static byte[] publicKey(byte[] priv) {
        return new X25519PrivateKeyParameters(priv, 0).generatePublicKey().getEncoded();
    }
}
