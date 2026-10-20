package com.example.myapplication.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

/**
 ***导出物的签名**（2026-09-28 立 lmq 指派的「1 TEE 密钥签链尾」）
 *
 * **为什么需要它**：现在的核对器**没有密钥** => 一个有意重算的人能造出一份自洽的文件
 *   （那正是 `tools/check_tamper_cases.py` 的 case E）
 * => 用**硬件背书的密钥**签一次（私钥**不可导出**）=>
 *   拿到设备的人**只能调用密钥签【当下】的数据** => **补不出一个【当时的】签名**
 *   —— 材料里的说法：把"改历史"变成"只能从此刻起造假"
 *
 * 两条曾经"刻意不做"的（本条 2026-09-30 订正过 —— 其中一条的理由不成立）
 *   1 **不强求 StrongBox**（`setIsStrongBoxBacked`）—— 仍然不做
 *     实测材料：绝大多数设备没有 StrongBox（含两台真机与 2024 模拟器）。
 *     强求它只会让我们掉进降级路径，而"降级路径忘了重设参数"是点名的常见缺陷。
 *   2 **不要求用户认证**（`setUserAuthenticationRequired`）—— 从"刻意不做"改为**待决定**
 *     原先写的理由是"会引入 `AUTH_TIMEOUT`（30–300 秒）的窗口"——**那个理由不成立**：
 *     窗口是设了**非零** timeout 才有的；不设 timeout 时默认就是**每次使用都要认证**、没有窗口。
 *     依据是本地 SDK 里的 AOSP 源码（`KeyGenParameterSpec.java`，L1546 与 L1597 两处同文）：
 *       > By default, if user authentication is required, it must take place for every use of the key.
 *       > @param timeout duration in seconds or 0 if user authentication must take place for every use of the key.
 *     而"本场景不需要用户在场"这一句也不对：导出是用户点按钮触发的前台动作，人就在场。
 *     => 加认证能挡住"进程内拿到执行权的人静默驱动这把密钥"（材料里叫 Ride the key），
 *       代价是每次导出多一次解锁。要不要做、以及它和方案 A 的先后，
 *       见 `C:\hub\decisions\2026-09-30-android-evidence-tool-proposal.md` §4.1 / §4.3 M4。
 *
 * **它挡不住什么**（必须写清，否则就是夸大）
 *   进程内有代码执行的人（root Frida）**可以调用**这个密钥签【新】数据
 *   而**时间戳**仍然可改 => **外部锚定不可省**（两件事互补）
 *
 * **失败必须显式**（材料点名："静默降级到软件密钥"是常见缺陷）
 *   => 所以 [levelName] 用**读回来的**数字；而 [sign] 拿不到密钥时返回 [Result.Unavailable]，
 *     由调用方**写进产物**（而不是假装签了）
 *
 * `minSdk = 31` => `KeyInfo.securityLevel` **刚好可用**（它正是 API 31 引入的）
 */
object ExportSigner {

    private const val TAG = "NK"

    /** 别名带版本 —— 将来换算法可以并存，不必销毁旧键 */
    private const val ALIAS = "nk-export-signing-v1"

    private const val PROVIDER = "AndroidKeyStore"

    /**
     * "一次导出一把"的别名前缀。序号从 2 起 —— 1 是**长期那把**（`ALIAS`），它不匹配这个前缀、
     * 也不该被删（老产物用它签的，而"密钥还在"对重新导出/对比有用）。
     */
    private const val PER_EXPORT_PREFIX = "nk-export-signing-v"

    /** 一次签名的结果（含"为什么没签"）*/
    sealed interface Result {
        /**
         * 签成功（`level` 是**读回来**的档位，不是假设的）
         *
         *2026-09-30 增 `attestationChainPem`：一次导出一把密钥时，把它的证书链带出来
         *   （`null` = 这次没有链 —— 退化路径，产物里就不写 `attestationChain`）
         */
        data class Signed(
            val signature: ByteArray,
            val publicKeyPem: String,
            val level: Int,
            val attestationChainPem: String? = null,
        ) : Result {
            /**
             *签名的 **base64 形式**（产物要文本）
             *
             *2026-09-28 修：我第一版在 `NotifStore` 里写了
             *   `esc(r.signature.toString())` => `ByteArray.toString()` 是 **Java 数组的 toString**
             *   => 产物里落下 `[B@362157f`（而**看不出错**，非常像一个签名）
             *   => 现在在这里转好，调用方拿到的就是能写进 JSON 的东西
             */
            val signatureB64: String =
                android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
        }

        /** 签不了（原因要进产物）*/
        data class Unavailable(val reason: String) : Result
    }

    /**
     *2026-09-30（方案 C）：一次导出**分成两步** —— 密钥先建好、签名等认证。
     *
     * 为什么必须分：`setUserAuthenticationParameters(0, …)` 的密钥**每次使用都要认证**，
     * 而认证要靠 `BiometricPrompt`（异步、要 Activity）=> 签名不能是"调一下就拿结果"的同步函数。
     * 于是：[beginAuth] 建密钥并把 `Signature` 交给调用方去弹认证；
     * 认证成功后调用方调 [NeedsAuth.finish] 拿结果。
     *
     * **退化路径**（见 [Failed] / [Done]）：
     * 设备建不出"要求认证"的密钥时（没设锁屏等），退回**不要求认证但仍然一次导出一把**的那把 ——
     * 链照样绑本次内容，而"要不要认证"这件事链上会如实写在 503/504 里（见决策记录）。
     */
    sealed interface Begin {
        /**
         * 需要用户认证：把 [cryptoObject] 交给 `BiometricPrompt.authenticate`；
         * 认证成功后调 [finish] 拿签名结果（它内部会签名并在成功时清掉旧密钥）。
         */
        class NeedsAuth(
            val cryptoObject: android.hardware.biometrics.BiometricPrompt.CryptoObject,
            val finish: () -> Result,
        ) : Begin

        /**
         * 不用认证（退化路径）—— 已经签好了，调用方直接用它。
         *
         * 2026-09-30（C 的验收 e「失效要显式」）：[notice] **非 null 就表示这次降级了**，
         *   而且带着**原因** => 调用方必须把它**告诉用户**，不能静默换一把不要求认证的密钥。
         *   起因：密钥会被**永久失效**（关掉安全锁屏 换/删指纹），而那时"要求认证"的密钥
         *   直接建不出来 => 照旧静默退回，用户完全不知道自己的产物变弱了。
         */
        class Done(val result: Result, val notice: String? = null) : Begin

        /** 连退化路径都建不出密钥。 */
        class Failed(val reason: String) : Begin
    }

    /**
     * 档位的**可读名**（纯函数 => 可单测）
     *
     * 用**读回来的**数字，不用"我们请求了什么"
     *   —— 材料 §5.3.1 那条的修法就是这个：`KeyInfo.getSecurityLevel()`
     *
     *2026-09-28 修（独立复核报的，而它打中的正是上面那两行注释）：
     *   改前是 `TEE -> …` **`else -> "software"`**
     *   => 而 `levelOf()` 读失败时给 **`-1`**（它有意让"读不到"可表达）
     *     => **`-1` 落进那个 `else` => 被说成 `"software"`**
     *     =>**即："读不到" 与 "真的是软件档" 在产物里【同形】**
     *       —— 而那与上面那句"**不假装**"**相反**
     *   现在分开：
     *     `SECURITY_LEVEL_SOFTWARE` => `"software"`（官方常量 => 真的读到了）
     *     其余（含 `-1`）=> `"unavailable"`
     *   `"unavailable"` 与 `NotifStore` 里那个 `?: "unavailable"` **同名**
     *     => 判据：**同一个概念用同一个名字**（别一个叫 unknown、一个叫 unavailable）
     *   **诚实标注**：现在"读不到"与"entry 缺失"仍然同名
     *     —— 而 对读者来说那两件事**意思一样**（都是"没拿到档位"）
     */
    fun levelName(level: Int): String = when (level) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
        else -> "unavailable"
    }

    /**
     * 读一个**已有密钥**的档位（那是唯一可信的来源）
     *
     * 走 `KeyFactory.getInstance(algorithm, "AndroidKeyStore")` =>
     *   再看 `KeyInfo.securityLevel`
     *   —— 我第一次写成了 `privateKey.factory`（那在 Kotlin 里不是属性）
     */
    private fun levelOf(key: java.security.PrivateKey): Int = runCatching {
        val kf = KeyFactory.getInstance(key.algorithm, PROVIDER)
        (kf.getKeySpec(key, KeyInfo::class.java) as KeyInfo).securityLevel
    }.getOrDefault(-1)

    private fun loadEntry(): KeyStore.PrivateKeyEntry? = runCatching {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
    }.getOrNull()

    /** 懒生成：没有才建（已有则直接读）—— 生成失败返回 null */
    private fun generateIfMissing(): Boolean = runCatching {
        if (loadEntry() != null) return true
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build(),
        )
        gen.generateKeyPair()
        true
    }.getOrElse { e ->
        Log.w(TAG, "生成签名密钥失败：${e.javaClass.simpleName} ${e.message}")
        false
    }

    private fun loadKeyPair(): Pair<KeyPair, Int>? = runCatching {
        if (!generateIfMissing()) return null
        val entry = loadEntry() ?: return null
        val kp = KeyPair(entry.certificate.publicKey, entry.privateKey)
        kp to levelOf(entry.privateKey)
    }.getOrNull()

    /**
     * 公钥导出成 PEM（接收方只要它就能离线验签）
     *
     *2026-09-28 修：第一版把 header base64 footer 全拼在**一行**里
     *   => 而标准 PEM 是【多行】的（base64 **64 字符一行**）
     *   =>而**我自己写的解析器就被它绊倒了**
     *     （它用 `splitlines()` 过滤标记行 => 整行被丢掉 => DER = 0 字节）
     *   => 所以现在按标准换行：呼吸感这样一来别的工具也能读
     */
    private fun pem(pub: java.security.PublicKey): String {
        val b64 = android.util.Base64.encodeToString(pub.encoded, android.util.Base64.NO_WRAP)
        val wrapped = b64.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----\n"
    }

    /** `exportSelfHash` 是 64 个 hex 字符 => 原始 32 字节。不是 hex 就返回 null（于是走退化路径）。 */
    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 已有的"一次导出一把"密钥（序号 -> 别名）。**排除长期那把** —— 它虽然匹配前缀，
     *  但它不是"一次导出"的产物，而且不能删（老产物用它签的）。 */
    private fun perExportAliases(): List<Pair<Int, String>> = runCatching {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        ks.aliases().toList()
            .filter { it.startsWith(PER_EXPORT_PREFIX) && it != ALIAS }
            .mapNotNull { a -> a.removePrefix(PER_EXPORT_PREFIX).toIntOrNull()?.let { it to a } }
    }.getOrElse { emptyList() }

    private fun nextAlias(): String {
        val used = perExportAliases().map { it.first }
        return PER_EXPORT_PREFIX + ((used.maxOrNull() ?: 1) + 1)
    }

    private fun generateAttestedKey(alias: String, challenge: ByteArray, requireAuth: Boolean): String? =
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setAttestationChallenge(challenge)
            if (requireAuth) {
                // 方案 C：**每次使用都要认证**、没有有效窗口。
                // 带上 AUTH_DEVICE_CREDENTIAL 是为了换掉一条永久失效条件（换指纹会让密钥失效）——
                // 详见 .agents/notes/implemented/architecture/2026-09-30-option-c-user-authentication.md
                builder
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
            }
            gen.initialize(builder.build())
            gen.generateKeyPair()
            null
        }.getOrElse { e ->
            Log.w(TAG, "带证明的密钥生成失败（requireAuth=$requireAuth）：" +
                "${e.javaClass.simpleName} ${e.message}")
            // 返回**原因**而不是 `false`：退化时要把原因说出来（C 的验收 e）——
            //   布尔值会让"为什么降级"在调用方那一层消失
            "${e.javaClass.simpleName}：${e.message ?: "（无 message）"}"
        }

    /**
     *2026-09-30（方案 C）：导出第一步 —— 建密钥（要求认证），把 `Signature` 交给调用方去弹认证。
     *
     * 调用方拿到 [Begin.NeedsAuth] 后：`BiometricPrompt.authenticate(cryptoObject, …)`，
     * 认证成功回调里调 `finish()` 拿 [Result]。
     *
     * **退化两级**：1 建不出"要求认证"的密钥 => 退回**不要求认证、但仍是"一次导出一把"**的那把
     * （链照样绑本次内容；"要不要认证"链上如实写在 503/504 里）-> [Begin.Done]，**并带上原因**
     * （C 的验收 e「失效要显式」：不能静默换一把不要求认证的密钥）；
     * 2 连那把都建不出 => [Begin.Failed]。
     *
     * 它**取代**了原来的 `sign(payload)`（那个已删）：那是个"能签、但不要求认证"的公开入口，
     * 在方案 C 之后留着就等于给"绕过认证"留了一条路。
     * => 判据：**同一个能力不留两条路径，尤其当其中一条会绕过刚加的防线**。
     */
    fun beginAuth(selfHashHex: String): Begin {
        val challenge = hexToBytes(selfHashHex)?.takeIf { it.size == 32 }
            ?: return Begin.Failed("自身 hash 不是 32 字节的 hex => 建不了绑定内容的密钥")
        val payload = selfHashHex.toByteArray(Charsets.UTF_8)

        // 1 首选：要求认证
        val aliasAuth = nextAlias()
        val whyNotAuth = generateAttestedKey(aliasAuth, challenge, requireAuth = true)
        if (whyNotAuth == null) {
            val entry = loadEntryOf(aliasAuth)
            if (entry != null) {
                val sig = Signature.getInstance("SHA256withRSA").apply { initSign(entry.privateKey) }
                val chain = chainPem(aliasAuth)
                val pub = pem(entry.certificate.publicKey)
                val level = levelOf(entry.privateKey)
                return Begin.NeedsAuth(
                    android.hardware.biometrics.BiometricPrompt.CryptoObject(sig),
                ) {
                    val out = runCatching {
                        sig.update(payload)
                        Result.Signed(sig.sign(), pub, level, chain)
                    }.getOrElse { e ->
                        Log.w(TAG, "认证后的签名失败：${e.javaClass.simpleName} ${e.message}")
                        Result.Unavailable("签名失败（${e.javaClass.simpleName}）")
                    }
                    if (out is Result.Signed) pruneExcept(aliasAuth)
                    out
                }
            }
        }

        // 2 退化：不要求认证（但仍然是"一次导出一把"、链仍然绑本次内容）
        val aliasPlain = nextAlias()
        if (generateAttestedKey(aliasPlain, challenge, requireAuth = false) == null) {
            val signed = signWith(aliasPlain, payload, chainPem(aliasPlain))
            if (signed != null) {
                pruneExcept(aliasPlain)
                // C 的验收 e：**说出**降级与原因。三种典型原因：
                //    设备没有安全锁屏 => 建不出"每次使用都要认证"的密钥（这就是那条永久失效条件）；
                //    或平台不接受我们请求的认证类型。
                return Begin.Done(
                    signed,
                    // 这段字**要短**：Toast 被系统截成两行，长句子会看不见（实测见提交信息）。
                    //   详细原因在日志里，用户看到的这一行只需要"知道降级了 + 大概为什么"。
                    "这次导出没要求认证：设备没有安全锁屏 => 签名密钥可被静默驱动",
                )
            }
        }

        // 3 连退化的也建不出 —— 最后退回长期那把（产物里就没有链）
        val loaded = loadKeyPair()
            ?: return Begin.Failed("密钥不可用（Keystore 生成或读取失败）")
        val (kp, level) = loaded
        val out = runCatching {
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(kp.private)
                update(payload)
            }
            Result.Signed(sig.sign(), pem(kp.public), level)
        }.getOrElse { e ->
            Log.w(TAG, "签名失败：${e.javaClass.simpleName} ${e.message}")
            Result.Unavailable("签名失败（${e.javaClass.simpleName}）")
        }
        return Begin.Done(
            out,
            "这次导出用的是长期那把密钥（没有 attestation 链、也不要求认证）",
        )
    }

    private fun loadEntryOf(alias: String): KeyStore.PrivateKeyEntry? = runCatching {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
            .getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }.getOrNull()

    /** 把整条证书链拼成 PEM 文本（接收端的 `split_pem_certs` 认这个形状）。 */
    private fun chainPem(alias: String): String? = runCatching {
        val chain = KeyStore.getInstance(PROVIDER).apply { load(null) }.getCertificateChain(alias)
        chain?.takeIf { it.isNotEmpty() }?.joinToString("") { c ->
            val b64 = android.util.Base64.encodeToString(c.encoded, android.util.Base64.NO_WRAP)
            "-----BEGIN CERTIFICATE-----\n" + b64.chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
        }
    }.getOrNull()

    private fun signWith(alias: String, payload: ByteArray, chain: String?): Result.Signed? =
        runCatching {
            val entry = KeyStore.getInstance(PROVIDER).apply { load(null) }
                .getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(entry.privateKey)
                update(payload)
            }
            Result.Signed(sig.sign(), pem(entry.certificate.publicKey), levelOf(entry.privateKey), chain)
        }.getOrElse { e ->
            Log.w(TAG, "带证明的签名失败：${e.javaClass.simpleName} ${e.message}")
            null
        }

    /**
     * 清掉旧的"一次导出"密钥，只留这一次用的。
     *
     * **长期那把（`nk-export-signing-v1`）不删** —— 它不匹配 `PER_EXPORT_PREFIX`，
     *   而"密钥还在"对"重新导出 / 对比"有用。
     * 删旧密钥**不影响**已经导出的产物：公钥与链都在产物里，验证不需要 Keystore。
     */
    private fun pruneExcept(keep: String) {
        runCatching {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            perExportAliases().forEach { (_, a) -> if (a != keep) ks.deleteEntry(a) }
        }
    }

    /** 只读档位（给"如实标注"用）—— 拿不到密钥就返回 null */
    /**
     * 只读档位（给"如实标注"用）—— 拿不到就返回 null
     *
     *2026-09-28 修：第一版**只调了 `loadEntry()`** => 它**不生成**
     *   => 而我在注释里写了"会懒生成"（注释与代码不符，那是同一族的错）
     *   => 症状：产物里写 `"signingSecurityLevel":"unavailable"`
     *     而那**恰好证明了"不假装"那条设计是对的**
     *       —— 它没有写 `tee` 骗人，而是老老实实说"拿不到"
     *   => 现在先 `generateIfMissing()` => 首次导出时建键
     */
    fun currentLevelName(): String? {
        if (!generateIfMissing()) return null
        return loadEntry()?.let { levelName(levelOf(it.privateKey)) }
    }
}
