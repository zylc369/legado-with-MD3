# BouncyCastle provider 通过反射（ClassUtil.loadClass）加载 *Mappings 等 SPI 服务类。
# release 的 R8 会裁掉未被静态引用的服务类，导致 provider 找不到 ChaCha20-Poly1305
# （表现为 NoSuchAlgorithmException），因此保留 provider 相关类。
-keep class org.bouncycastle.jcajce.provider.** { *; }
