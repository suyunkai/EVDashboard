# AOSP 测试签名

Release 构建使用 AOSP 公开的 `testkey`，签名容器为 `aosp-testkey.p12`。
这是公开测试私钥，任何人都能使用它签名，不是项目独占的正式发布密钥。
该公开容器按用户要求随源码提交；其他私有签名材料仍由 `.gitignore` 排除。

## 构建

```sh
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。
别名为 `aosp-testkey`，PKCS12 容器和私钥密码均为公开值 `android`。
构建时不下载签名材料，也不需要本机私有凭据。

该证书与 Android debug 签名不同，不能直接覆盖证书不同的已有安装。
更新安装要求保持同一签名；卸载应用会清除其内部设置，重要数据应先备份。

## 来源与核验

上游固定为 AOSP `platform/build` 的 `android-15.0.0_r1` 标签：

- [testkey.pk8](https://android.googlesource.com/platform/build/+/refs/tags/android-15.0.0_r1/target/product/security/testkey.pk8)
- [testkey.x509.pem](https://android.googlesource.com/platform/build/+/refs/tags/android-15.0.0_r1/target/product/security/testkey.x509.pem)
- [AOSP 签名说明](https://source.android.com/docs/core/ota/sign_builds)

容器由上述原始私钥与证书转换得到，未生成新密钥或修改证书。

证书 DER SHA-256：

```text
a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
```

上游原始文件 SHA-256：

```text
495675d32e89a149d5abe191f4e9c0e218b9068714e9b53a7c91e164a0741a23  testkey.pk8
a4384ba815b9499a5ce349b4e33c1755278873fe2eac150a068823f526e6dbde  testkey.x509.pem
```

可用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查 APK，
核对签名有效且证书 SHA-256 与上面一致。
