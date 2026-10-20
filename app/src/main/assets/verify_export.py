#!/usr/bin/env python3
"""
NK 证据包**独立核对器**（不依赖那个 App，也不依赖 Android）

用途：拿到一份 `nk-export-*.jsonl` 后，**自己算一遍**，回答四个问题 ——
  1 **记录完整吗**（哈希链有没有断？断在哪一条？）
  2 **这份文件被改过吗**（导出自身 hash 对不对？）
  3 **有没有"我不知道的时段"**（断档清单长什么样？）
  4 **它清空过吗**（清空留痕：什么时候、清掉多少、当时链尾 hash）

2026-09-29 增第五问（8 attestation 那一层）：
  5 **签这份文件的那把公钥，是谁背书的？**（证书链 pin Google 根 看 `hardwareEnforced` 绑 challenge）
  => 它补的是**签名答不了的那个问题**：签名只证明"是持有那把私钥的人签的"，
    而**那把公钥**原本就写在文件自己身上 => "换一对密钥、连公钥一起换掉再重签"照样验得过
  **缺这一层不算失败**（老产物没有）—— 与 `exportSelfHash` 缺失**不同**：那是新增的一层

它与 App 的关系：**只读** —— 算法是照 `NotifStore.exportText()` /
  `HashChain` / `OpsEvents` **复刻**的（复刻的根据是那份源码，不是我猜的）
  => 所以它算出来的结论，是**独立于 App 的第二次计算**

判据（照 App 里写进文件的 `chainIdentityNote`）：
  **判断两批记录是否属于同一条链，要看 `chainId`，不要看 `epoch`** ——
  重装/清空后 `epoch` 会重新数，会误判

用法：
    python verify_export.py <导出的.jsonl> [另一份.jsonl ...]
"""
from __future__ import annotations

import datetime
import hashlib
import json
import sys
import zipfile
from pathlib import Path

SEP = "\u0001"                     # 与 App 的 HashChain.SEP 一致
GENESIS = "0" * 64                 # 与 HashChain.GENESIS 一致
SELF_HASH_KEY = "exportSelfHash"   # 与 OpsEvents.SELF_HASH_KEY 一致
RECORD_FIELDS = ("pkg", "key", "event", "title", "text", "big", "posted", "reason", "captured")


def sha256(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def payload_of(rec: dict) -> str:
    """与 HashChain.payloadOf 逐字段对齐（顺序不能变，变了整条链就验不过）"""
    return SEP.join(
        str(rec.get(f, "") if rec.get(f) is not None else "") if f != "reason" else str(rec.get(f, 0))
        for f in ("pkg", "key", "event", "title", "text", "big", "posted", "reason", "captured")
    )


def payload_of_strict(rec: dict) -> str:
    """严格版（与 Kotlin 的字面逐字对齐）：title/text/big 为空时用 ""，其余用 toString()"""
    parts = [
        rec.get("pkg", ""), rec.get("key", ""), rec.get("event", ""),
        rec.get("title") or "", rec.get("text") or "", rec.get("big") or "",
        str(rec.get("posted", 0)), str(rec.get("reason", 0)), str(rec.get("captured", 0)),
    ]
    return SEP.join(parts)


def link(prev: str, payload: str) -> str:
    """与 HashChain.link 一致：sha256(prev + SEP + payload)"""
    return sha256(prev + SEP + payload)


def load(path: Path, text: str | None = None) -> tuple[list[dict], list[dict], dict | None, list[str]]:
    """把 jsonl 分成【元信息行】与【记录行】

    `text` 给了就直接用它 —— 用于"输入是 zip"的场合：从 zip 读出正文即可，
       不必先落一个临时文件（少一次磁盘往返，也少一个可能残留的文件）
    """
    if text is None:
        text = path.read_text(encoding="utf-8")
    meta, records = [], []
    # 把"读不出来的声明行"记下来 —— 它们声明的字段**没有被核对**，
    # 而旧写法只打印一行警告就跳过 => 结论行照旧是干净的 => 接收方可能读成"全查过了"
    unreadable: list[str] = []
    #2026-09-28 修（A9 读侧）：**按 LF 切，不按 Unicode 行分隔符切**
    #   改前用 text.splitlines() —— 它在 U+0085 / U+2028 / U+2029 上**也断行**
    #   而写侧 esc() 当时【不转义】这三个字符 => 一个**合法**导出会被劈成两半
    #   => 那行 JSON 解析失败、记录被丢、自 hash 对不上 => 判"文件被改过"（狼来了）
    #   同时把 CRLF 归一（旧导出可能是 CRLF => 不能把 \r 留在行尾）
    #   与 Kotlin 侧对齐：OpsEvents.kt:54 本来就是 fullText.trimEnd('\n').split('\n')
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as e:
            print(f"   有一行不是合法 JSON：{e}")
            unreadable.append(line)
            continue
        if "id" in obj and "selfHash" in obj:
            records.append(obj)
        else:
            meta.append(obj)
    #2026-09-29 修（F2g 爻光研判报告 L3 之后的第二条）：
    #   症状：核对器**把攻击者的话当成自己的话打印出来**
    #     机制：下面收集 `limitation*` 时扫的是【所有 meta 行】=> 而 **末行也在 meta 里**
    #     而 **末行【不受自 hash 保护】**（`exportSelfHash` 的口径是"除末行外全部字节"）
    #       => 往末行塞一个 `limitation7` => 它就用**核对器自己的 前缀**被转述出来
    #         格式与那 6 条真局限**完全一致** => 接收方无法从格式上区分"工具说的"与"文件里塞的"
    #   => 修法：**收集 `limitation*` 时排除末行**（末行是唯一不受保护的行 => 它里面的东西
    #      不能当"文件声明"转述）
    #   而**不能**把末行从 `meta` 里整个排除 —— 签名那段要 `pick(meta, "publicKeyPem")`
    #      => 所以这里只**多返回一个引用**（谁调用谁决定要不要用它排除）=> 零副作用
    tail_obj = meta[-1] if meta else None
    # 第 4 个元素：读不出来的声明行（原文）。同 `tail_obj` 那个先例 —— 只多返回一个引用，
    # 谁调用谁决定要不要用它 => 零副作用。判决要用它来把"那几项没被核对"写进结论
    return meta, records, tail_obj, unreadable


def pick(meta: list[dict], key: str):
    for m in meta:
        if key in m:
            return m[key]
    return None


def verify(path: Path, text: str | None = None) -> bool:
    print("=" * 74)
    print(f"核对：{path.name}  （{path.stat().st_size} 字节）")
    if text is None:
        text = path.read_text(encoding="utf-8")
    meta, records, tail_obj, unreadable = load(path, text)
    ok = True

    #1 头信息
    count = pick(meta, "count")
    print(f"   声明条数 = {count} 实际记录行 = {len(records)}", end="")
    #2026-09-28 加（lmq 指派的 2）：**打印覆盖范围**（接收方一眼看出这份覆盖了哪段）
    #   它来自文件自己的声明 => **同样可被改**（设备时钟）=> 不是锚，只是可读性
    _from = pick(meta, "coveredFrom")
    _to = pick(meta, "coveredTo")
    if _from and _to and str(_from) != "null" and str(_to) != "null":
        def _fmt(ms):
            try:
                return datetime.datetime.fromtimestamp(int(ms) / 1000.0).strftime("%m-%d %H:%M:%S")
            except Exception:
                return str(ms)
        print(f"   覆盖范围：{_fmt(_from)} -> {_fmt(_to)}"
              f"  （这是文件自己声明的，同样可被改 => 不是时间锚）")
    if count is not None and int(count) != len(records):
        print("  对不上（文件被改过或没导全）")
        ok = False
    else:
        print(" ")

    #2 链完整性（逐条重算）
    prev = GENESIS
    broken = None
    tail_actual = GENESIS
    for rec in sorted(records, key=lambda r: r.get("id", 0)):
        expect = link(prev, payload_of_strict(rec))
        if rec.get("selfHash") != expect or rec.get("prevHash") != prev:
            if broken is None:
                broken = rec.get("id")
        prev = expect
        tail_actual = expect          # 链尾必须用【重算值】—— 见下面的注释
    declared_broken = pick(meta, "firstBrokenId")
    if broken is None:
        print(f"   链：完整（逐条重算过 {len(records)} 条）")
        if declared_broken not in (None, -1):
            print(f"      但文件自称断在 {declared_broken} => 不一致")
            ok = False
    else:
        print(f"   链：**断在第 {broken} 条**")
        #2026-09-28 修（A1 的后半）：**断链本身就是失败 => 无条件 ok = False**
        #   改前只在与文件自称的断点不一致时才置 False =>
        #   于是"断链 + 声明与实算一致"这条路**绕过了 ok** => 结论仍打（实测 rc=0）
        #   （这正是"检查器的口径"那一族：拿被检查对象自己声明的值去决定要不要拦）
        ok = False
        if declared_broken not in (None, -1) and int(declared_broken) != int(broken):
            print(f"      与文件自称的断点 {declared_broken} 不一致")
    tail_declared = pick(meta, "chainTail")
    #2026-09-28 修：链尾**必须用重算值**比。
    #   原来这里用的是"最后一条记录里写的 selfHash" => 那个值本身就是被改过的文件里
    #   原封不动抄下来的 => 于是"链尾一致"这条检查对被篡改的文件**照样通过**
    #   （这正是"检查器的口径"又一个实例：拿被检查对象自己声明的值去比它自己）
    print(f"   链尾：文件声明 {str(tail_declared)[:16]}… 实算（重算）{tail_actual[:16]}…", end="")
    print(" " if tail_declared in (None, tail_actual) else "  不一致")
    if tail_declared not in (None, tail_actual):
        ok = False

    #2之二 两个「完整性声明」必须与【实算】对照（2026-09-29 加 来源：爻光的 F2hb）
    #   他的原话：「两个"完整性"字段是死字段：chainIntact 与 chainGenesis 在核对器源码里出现 0 次」
    #      => 实测确认（本文件此前对这两个词的命中 = 0 / 0）
    #      后果：文件里那句「chainIntact: true」**没人读** —— 而本工具却会打印
    #        「链：完整」=> 两句"完整"各说各的、从不对照
    #   判据（与上面 firstBrokenId / chainTail 那两处同族）：
    #     **拿被检查对象自己声明的值去决定要不要拦 = 不行**；
    #     声明与实算【不一致】这件事本身就要报出来
    #   而 `chainGenesis` 声明的是"链的起点该是什么"（真值是 64 个 0）
    #     => 它该与【重算时用的起点】一致（而首条记录的 prevHash 也在上面按它比过了）
    declared_intact = pick(meta, "chainIntact")
    if declared_intact is not None:
        actual_intact = (broken is None)
        print(f"   完整性声明：chainIntact={declared_intact} 实算链完整={actual_intact}", end="")
        if bool(declared_intact) == actual_intact:
            print(" ")
        else:
            print("  不一致（文件自称的完整性被实算否定）")
            ok = False
    declared_genesis = pick(meta, "chainGenesis")
    if declared_genesis is not None:
        print(f"   链起点声明：chainGenesis={str(declared_genesis)[:16]}… 重算起点={GENESIS[:16]}…", end="")
        if str(declared_genesis) == GENESIS:
            print(" ")
        else:
            print("  不一致")
            ok = False

    #3 导出文件自身的 hash（去掉最后一行再算）
    #2026-09-28 修（A9 读侧第二处 同一条判据）：按 LF 切，不按 Unicode 行分隔符切
    lines = [l for l in text.replace("\r\n", "\n").replace("\r", "\n").split("\n") if l.strip()]
    claimed_self = None
    # 先置 None：下面的 if 分支才会给 `recomputed` 赋值 => 否则"末行没有 exportSelfHash"时
    #    引用它会 NameError（8 attestation 那一层要拿它做 challenge 绑定）
    recomputed = None
    if lines:
        try:
            claimed_self = json.loads(lines[-1]).get(SELF_HASH_KEY)
        except json.JSONDecodeError:
            pass
    if claimed_self:
        recomputed = sha256("\n".join(lines[:-1]))
        match = recomputed == claimed_self
        print(f"   导出自身 hash：{'一致（这份文件就是当时导出的那份）' if match else '不一致（文件被改过）'}")
        print(f"      声明 {claimed_self[:16]}… 实算 {recomputed[:16]}…")
        ok = ok and match
        self_hash_part = "对得上" if match else "对不上"
    else:
        #2026-09-28 修（A8）：**缺这一行 => 判失败**
        #   改前只打印一句警告、**不置 ok = False** =>
        #   于是"删掉这一行"就可以让文件头被任意改写而不被发现（实测 rc=0）
        #   这会让**旧版导出**判失败 —— **那是对的**：
        #      没有这一行 => 无法证明文件未被改 => 不该判"自洽"
        print("   导出自身 hash：文件里没有这一行（旧版导出？）")
        print("      没有这一行 => **无法证明这份文件未被改动** => 本次判失败")
        ok = False
        self_hash_part = "缺失（无法自证）"

    #7 签名验证（2026-09-28 加 lmq 指派的 1 第三步）
    # 三种状态要分清（见 `check_signature` 的文档）：
    #   0 验过 1 签了但验不过（真问题 => 置失败）2 没有签名（不是失败）
    #2026-09-29 加（F2f1 爻光报的"两个值永不相遇"）：
    #   他的原话：「文件里放两个 exportSelfHash 行 => 签名侧与内容侧各读一个，两者永不相遇」
    #     机制：`pick()` 是 first-wins => 签名侧取【第一个】含该键的 meta 行；
    #           而内容侧取【最后一行】（`lines[-1]`）=> 全流程没有一处比较这两个值
    #     实测（他复现）：在受覆盖区域里插一份原件末行（含真签名 + 真公钥），
    #       末行换成按改写后内容重算的值 => rc=0「导出自身 hash：一致」「签名：验过」
    #       而原文真值【从未被打印】=> 接收方看不到签名实际验的是哪个值
    #   => 修法：**让两个值相遇** —— 数一数该键在文件里出现几处
    #      真正的产物只写一处 => **> 1 就是异常**，报出来并判失败
    n_selfhash = sum(1 for m in meta if "exportSelfHash" in m)
    if n_selfhash > 1:
        vals = [str(m["exportSelfHash"])[:16] + "…" for m in meta if "exportSelfHash" in m]
        print(f"  文件里有 {n_selfhash} 个 exportSelfHash 行 => 签名侧与内容侧会各读一个")
        print("      出现的位置（按文件顺序）：" + " ".join(vals))
        print("      真正的产物只写一处 => 多于一处的，**签名保护的可能不是内容那一版**")
        ok = False
    sig_state, sig_why = check_signature(meta, pick(meta, "exportSelfHash"))
    if sig_state == 0:
        print("   签名：验过（用的是文件里那把公钥）")
        print("      => 它说明【这份文件是持有那把私钥的人签的】")
        print("        而**不**说明时间 —— 时间只能靠外部锚定")
    elif sig_state == 1:
        print(f"   签名：**验不过**（{sig_why}）")
        print("      => 两种可能：内容被改过，或公钥被换成攻击者自己的")
        ok = False
    else:
        print(f"   签名：{sig_why}")
        print("      没有签名**不算失败**（老产物本来就没有）—— 但要说清它的代价：")
        print("        **「本来就没签」与「签名被删掉」在结构上不可分** =>")
        print("           这类产物在【防篡改】这件事上**没有证据价值**（它只证明「文件自身自洽」）")
        print("           => 要当证据用，必须有一份**带有效签名**的产物")

    #8 出处证明（attestation 证书链 + 硬件声明 2026-09-29 加）
    # 这一层补的是**签名答不了的那个问题**：「这把公钥是谁的」（见 §8 的注释）
    # 三态与签名同族：**没有这一层 ≠ 验不过**（老产物就是没有）
    att_state, att_why, att_detail = check_attestation(meta, recomputed if lines else None)
    if att_state == 0:
        print("   出处证明：硬件背书验过（链逐级验过 根是 Google 硬件证明根 "
              "challenge 对得上 App 身份对得上）")
        for ln in att_detail.splitlines():
            if ln:
                print(ln)
        print("      => 它说明【签这份文件的那把公钥，是这台设备的安全硬件背书的、"
              "而且这条链是这个 App 的】")
        print("        它**仍然不**说明时间 —— 时间只能靠外部锚定")
    elif att_state == 1:
        print(f"   出处证明：**{att_why}**")
        for ln in att_detail.splitlines():
            if ln:
                print(ln)
        print("      => 链**自洽不等于可信**：模拟器链、自签链的每一条签名也都验得过")
        ok = False
    else:
        #缺这一层**不是失败** —— 与 A8（缺 exportSelfHash）**不同**：
        #    A8 缺的是"已有保护的证据"，而 attestation 是一个**新增的层**
        print(f"   出处证明：{att_why}")
        print("      这不是失败 —— 只是这份产物**没有「公钥归谁」那一层保护**")
        print("        它仍然能用 `exportSelfHash` + 签名回答「有没有被改」「谁签的」两问")
    gaps = pick(meta, "gapCount")
    if gaps is not None:
        certain = pick(meta, "certainGaps")
        suspected = pick(meta, "suspectedGaps")
        open_gap = pick(meta, "hasOpenGap")
        #2026-09-28 修（P2 来自独立复核）：**按证据强度分档说**
        #    改前只要 hasOpenGap 为真就打「**此刻仍处于断档中**」——
        #    而那个字段**含疑似段**（只是"超过 30 分钟没有记录"）
        #    => 一台**健康但安静**的手机会被这份报告说成"正在断档"
        #      —— 最强措辞配最弱证据，与本工具的宗旨反向
        #    现在分三档：有事件作证的才说"仍处于断档中"
        #    兼容：老导出文件没有细分字段 => 退回原来那一句（不假装知道更多）
        open_c = pick(meta, "openCertainGap")
        open_s = pick(meta, "openSuspectedGap")
        tag = ""
        if open_c is not None:
            if open_c:
                tag = "  **此刻仍处于断档中**（有事件作证）"
            elif open_s:
                tag = "  最后一段记录之后暂无记录（**可能仍没在听，也可能只是没人发消息**）"
        elif open_gap:
            tag = "  **此刻仍处于断档中**"
        print(f"   断档：共 {gaps} 段（确定 {certain} 疑似 {suspected}）" + tag)

    #5 清空留痕 + 链身份
    lc = pick(meta, "lastClear")
    if lc:
        print(f"   清空过：清掉 {lc.get('count')} 条 被清掉那一段是 第 {lc.get('clearedEpoch')} 段"
              f"（chainId {lc.get('clearedChainId')}）当时链尾 {str(lc.get('tailHash'))[:16]}…")
    else:
        print("   清空留痕：无（这份链没被清空过）")
    cid = pick(meta, "chainId")
    if cid is not None:
        print(f"   本段链：第 {pick(meta, 'epoch')} 段 chainId = {cid}")
        print("      （判断两批记录是否同一条链 => 比 chainId；别比 epoch）")

    #6 局限声明（原样转述，别让人误读）
    #2026-09-28 修：**读【所有】`limitation*` 键，不再写死两条**
    #   改前是 `for lim in ("limitation", "limitation2")` =>
    #   而导出侧一旦多写几条（说明书 markdown 里当时已有四条），
    #   跑核对器的人**看到的比读说明书的人少**
    #   => 现在按"键以 `limitation` 开头"收集，并按后缀数字排序
    #     （这样连老文件的 `limitation`（无数字）也照样读得到 => 不需要同步）
    #2026-09-28 再修：上一版只扫 **`meta[-1]`** => 一条都打不出来
    #   事实：导出侧把 `limitation1..5` 写在 **`exportSelfHash` 行之前**
    #   => 它们**不在最后一行** => 旧写法读不到
    #   而我当时以为“接上了”—— 因为输出里确实有几行，
    #     而那几行是**“不是合法 JSON”的警告**，不是局限
    #   => 现在扫**所有** meta 行，不依赖“它在哪一行”
    lims: list[tuple[str, str]] = []
    injected: list[tuple[str, str]] = []
    for m in meta:
        #2026-09-29 修（F2g 爻光研判报的"报告面注入"）：
        #   症状：核对器**把攻击者的话当成自己的话打印出来**
        #     机制：这段原本扫【所有 meta 行】=> 而末行也在 meta 里
        #     而 末行是唯一【不受自 hash 保护】的行（selfHash 的口径 = 除末行外全部字节）
        #       => 往末行塞一个 `limitation7` => 它就用**核对器自己的 前缀**被转述
        #         格式与那 6 条真局限**完全一致** => 接收方无法从格式上区分
        #   => 修法：**把末行排除在"文件声明"之外**
        for k, v in m.items():
            if k.startswith("limitation") and v:
                if m is tail_obj:
                    injected.append((k, str(v)))
                else:
                    lims.append((k, str(v)))
    lims.sort(key=lambda kv: int(kv[0][len("limitation"):] or 1))
    for _k, v in lims:
        print(f"   {v}")
    #而**不静默**（判据："静默 = 最坏的一种" => 藏起来和冒充一样坏）：
    #   末行里若真有 `limitation*` => 报出来、但标明它【不是文件声明】
    if injected:
        ks = "、".join(k for k, _ in injected)
        print(f"   末行里有 {len(injected)} 个 limitation* 键（{ks}）——")
        print("      **本核对器不把它们当【文件声明】转述**：")
        print("      末行是唯一不受自身 hash 保护的那一行（selfHash 只覆盖除它以外的字节），")
        print("      所以那一行里的话可能是【后加进去的】，不能当导出方的声明看。")
        for _k, v in injected:
            print(f"         （末行 {_k} 原文，仅存档、不转述）{v}")
    #2026-09-28 修（A1 的前半）：结论串里那句"**链完整**"必须**由事实派生**
    #   改前：无论上面打印什么，只要 ok 还是 True 就写"链完整 自身 hash 对得上"
    #   => 实测出现过「链：**断在第 1811 条**」紧接着「这份文件自洽（链完整 …）」
    #   => 那是**同一份输出里自相矛盾** => 接收方只看最后一行就会读反
    chain_part = "链完整" if broken is None else f"**链是断的（第 {broken} 条）**"
    head = "这份文件自洽" if ok else "有问题，见上面的"
    # 读不出来的声明行要**出现在结论里** —— 判据同上面 A1 那段：结论串的每一项都要由事实派生。
    # 实测（09-28 那三份产物）：3 行非法 JSON 里有 `gapCount`（缺口账）、`chainIdentityNote`、
    # `legend` => 那几项**从来没被核对过**，而结论只写"自洽" => 接收方会读成"全查过了"
    decl_part = ""
    if unreadable:
        _keys = []
        for _ln in unreadable:
            _s = _ln[1:].lstrip() if _ln.startswith("{") else _ln
            _k = _s[1:].split('"', 1)[0] if _s.startswith('"') else ""
            _keys.append(_k if _k.isidentifier() else "?")
        _names = ", ".join(_keys)
        print(f"   **有 {len(unreadable)} 行声明没被读到**（{_names}）"
              " => 那几项**没有被核对**（不是「核对过没问题」）")
        decl_part = f" {len(unreadable)} 行声明没被读到（{_names}）=> 那几项没有被核对"
    print(f"   => 结论：{head}（{chain_part} 自身 hash {self_hash_part}{decl_part}）")
    return ok


#7 签名验证（纯 Python 不装任何第三方包）
#
#为什么这一段值得存在（2026-09-28 加）：这个脚本是**交给接收方**的
#   而接收方**不一定会 pip install** => 所以不能用 cryptography 之类的库
#   => 于是选 **RSA** 而不是 EC：RSA 验签只需要 `pow(sig, e, n)` 与
#     PKCS#1 v1.5 的填充检查（都在标准库里）
#   而 EC（P-256）要在纯 Python 里写椭圆曲线点运算 => 上百行且易错
#
# 诚实的边界：这段代码**只验签**，不生成签名
#   => 它也不做"证书链校验"（我们用的公钥是文件里带的那个）
#     => 所以它证明的是【**这份文件与那把公钥匹配**】
#       而"那把公钥真的来自某台设备" => 那要靠 attestation（本项目没做）
#
# 三者要分清（这是本文件里最容易混淆的地方）：
#   `exportSelfHash` 对得上 => **这份文件与它自己的声明一致**
#   签名验得过 => **这份文件是【持有那把私钥的人】签的**
#     =>而私钥**不可导出**（硬件背书时）=> **事后补不出当时的签名**
#   两者都不能证明【时间】=> 那只能靠**外部锚定**


def _der_tlv(buf: bytes, i: int):
    """读一个 DER TLV，返回 (tag, content_bytes, next_index)。只支持定长形式。"""
    if i + 2 > len(buf):
        raise ValueError("DER truncated")
    tag = buf[i]
    i += 1
    first = buf[i]
    i += 1
    if first < 0x80:
        length = first
    else:
        nbytes = first & 0x7F
        if nbytes == 0 or i + nbytes > len(buf):
            raise ValueError("DER bad length")
        length = int.from_bytes(buf[i:i + nbytes], "big")
        i += nbytes
    if i + length > len(buf):
        raise ValueError("DER content beyond buffer")
    return tag, buf[i:i + length], i + length


def _pem_to_der(pem: str) -> bytes:
    """
    PEM => DER（只用标准库）

   2026-09-28 修：第一版按行过滤（`splitlines()` + 跳过 `-----` 开头的行）
      => 而导出侧当时把 PEM 写成了**一行**
        =>整行以 `-----BEGIN…` 开头 => 被该过滤器**整行丢掉** => DER = 0 字节
      => 现在不看行，而是：去掉所有 `-----…-----` 标记，再去掉所有空白
        那样**多行与单行两种写法都能解**（老文件也能验）
    """
    import base64
    import re as _re
    body = _re.sub(r"-----[^-]*-----", "", pem)
    body = _re.sub(r"\s+", "", body)
    return base64.b64decode(body)


def rsa_public_numbers(pem: str):
    """
    从 `SubjectPublicKeyInfo` PEM 里取出 (n, e)

    结构（RFC 5280）：
        SEQUENCE { SEQUENCE { OID rsaEncryption, NULL }, BIT STRING { SEQUENCE { INTEGER n, INTEGER e } } }
    只取那两个 INTEGER => **不校验 OID**
      => 那是有意的：这段代码只验签，而"公钥是什么算法"由 `sigAlg` 字段说明
    """
    der = _pem_to_der(pem)
    tag, spki, _ = _der_tlv(der, 0)
    if tag != 0x30:
        raise ValueError("not a SEQUENCE")
    _, _, j = _der_tlv(spki, 0)          # 跳过 AlgorithmIdentifier
    tag, bits, _ = _der_tlv(spki, j)
    if tag != 0x03:
        raise ValueError("expected BIT STRING")
    if not bits or bits[0] != 0x00:
        raise ValueError("unexpected BIT STRING padding")
    tag, rsa_pub, _ = _der_tlv(bits[1:], 0)
    if tag != 0x30:
        raise ValueError("expected RSA public key SEQUENCE")
    tag, n_bytes, k = _der_tlv(rsa_pub, 0)
    if tag != 0x02:
        raise ValueError("expected INTEGER n")
    tag, e_bytes, _ = _der_tlv(rsa_pub, k)
    if tag != 0x02:
        raise ValueError("expected INTEGER e")
    return int.from_bytes(n_bytes, "big"), int.from_bytes(e_bytes, "big")


# SHA-256 的 DigestInfo 前缀（PKCS#1 v1.5 规定的那 19 字节）
_SHA256_DIGESTINFO_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def pkcs1_v15_verify_sha256(pub_pem: str, sig: bytes, message: bytes) -> tuple[bool, str]:
    """
    验 `SHA256withRSA`（PKCS#1 v1.5）—— 只用 `pow()` 与哈希

    步骤（RFC 8017 §8.2.2 的简化版）：
        1 m = sig^e mod n         2 转成 k 字节（k = n 的字节长度）
        3 检查 m = 00 01 FF…FF 00 || DigestInfo || H(message)
    这里对填充做**逐字节检查**（不用"以 …开头"的宽松判定）
      —— 宽松判定是这类代码最经典的漏洞（Bleichenbacher 那一族）
    """
    import hashlib
    try:
        n, e = rsa_public_numbers(pub_pem)
    except Exception as exc:                       # noqa: BLE001
        return False, f"公钥解析失败（{exc}）"
    k = (n.bit_length() + 7) // 8
    sig_int = int.from_bytes(sig, "big")
    if sig_int >= n:
        return False, "签名数值不小于模数"
    m = pow(sig_int, e, n).to_bytes(k, "big")
    if not m.startswith(b"\x00\x01"):
        return False, "填充不以 00 01 开头"
    # 找 00 分隔符（它前面必须全是 FF）
    idx = m.find(b"\x00", 2)
    if idx < 0:
        return False, "填充里没有 00 分隔符"
    if any(b != 0xFF for b in m[2:idx]):
        return False, "填充段里出现了非 FF 字节"
    if idx < 10:
        return False, "填充段太短（FF 少于 8 字节）"
    t = m[idx + 1:]
    want = _SHA256_DIGESTINFO_PREFIX + hashlib.sha256(message).digest()
    if t != want:
        return False, "DigestInfo 或摘要不匹配"
    return True, "ok"


def check_signature(meta: list[dict], self_hash: str) -> tuple[int, str]:
    """
    验签入口 —— 返回 (状态, 说明)

    三种状态（**必须分清**，否则老文件会被误判）：
        `0` 验过
        `1` **签了但验不过** => 那是**真问题**（文件被改过，或公钥被换过）
        `2` **文件里没有签名**（老版本导出的，或设备上建键失败）
          => **这不是失败** —— 只是"这份没有那个保护"
            => 所以调用方**不该**因此把 rc 变 1
    """
    import base64
    sig_b64 = pick(meta, "signature")
    pub = pick(meta, "publicKeyPem")
    if not sig_b64 or not pub or str(sig_b64) == "null":
        err = pick(meta, "signatureError")
        extra = f"（文件里写了原因：{err}）" if err else ""
        return 2, "这份文件没有签名" + extra
    try:
        sig = base64.b64decode(str(sig_b64))
    except Exception as exc:                       # noqa: BLE001
        return 1, f"签名不是合法 base64（{exc}）"
    #2026-09-29 加（F2f2 爻光报的"退化公钥"）：
    #   他的原话：「退化公钥 e = 1 => 世上不存在对应私钥，而核对器断言"持有那把私钥的人签的"」
    #     机制：`rsa_public_numbers()` 只把 SPKI 里两个 INTEGER 读出来（它自认「不校验 OID」），
    #           **且不检查 e 的下界** => e=1 时 pow(sig, 1, n) = sig
    #           => **"签名"就是 PKCS#1 填充块本身、任何人都能直接从消息算出来**
    #     实测（他复现）：公钥设 e=1 n=2^2047 => rc=0「签名：验过」
    #   => 修法：**验签之前先检查公钥健全性**
    #     1 e 必须 > 1（e=1 就是恒等映射 => 那道防线根本不存在）
    #     2 e 必须是奇数（RSA 的公开指数按定义是奇 —— 偶数 e 与 φ(n) 必不互素）
    #     3 n 的位数不低于 2048 位（弱密钥该报出来）
    #   而**不拦 e=3** —— 那在定义上合法（只是不常见）=> 拦它会误伤
    try:
        n_val, e_val = rsa_public_numbers(str(pub))
    except Exception as exc:                       # noqa: BLE001
        return 1, f"公钥解不出来（{exc}）"
    if e_val <= 1:
        return 1, (f"公钥的公开指数 e={e_val} => **这不是一个能验签的公钥**"
                   "（e=1 时任何填充块都能通过 => 那道防线不存在）")
    if e_val % 2 == 0:
        return 1, f"公钥的公开指数是偶数（e={e_val}）=> 不可能是 RSA 公钥"
    if n_val.bit_length() < 2048:
        return 1, (f"公钥模数只有 {n_val.bit_length()} 位（< 2048）=> 密钥太弱，"
                   "不足以保证签名不可伪造")
    ok, why = pkcs1_v15_verify_sha256(str(pub), sig, str(self_hash).encode("utf-8"))
    if ok:
        return 0, "ok"
    return 1, why

#8 attestation：证书链 + 硬件声明（2026-09-29 加 纯 Python 仍零依赖）
#
#这一节回答的是【签名答不了的那个问题】（见中枢 `reference/security/attestation-path.md` §一）：
#   签名证明的是「这份文件是拿【这把公钥】对应的私钥签的」
#   它**证明不了**【这把公钥是谁的】—— 而 `publicKeyPem` 就写在文件自己身上
#   => attestation 把"公钥归谁"从**文件自己的声明**换成**硬件签的声明**
#
#两条**不可省**（引自那份手册 L878 原文）：
#   > **chain validity and trustworthiness are different questions**. When you review a verifier,
#   > the two things to check are: **which root it pins, and which list it reads.**
#   1 **必须 pin 根** —— 否则模拟器链、自签链**照样全绿**（每一条签名都真的验得过）
#   2 **只读 `hardwareEnforced`** —— 合并两个列表 = 教科书级漏洞（手册 §5.2）

ATTEST_OID = "1.3.6.1.4.1.11129.2.1.17"

#内置的受信根 —— **两把**（这不是冗余，是必须）
#
# 来源与核对（2026-09-29 命令与哈希都留在这里）：
#   取自 **Google 官方端点** `https://android.googleapis.com/attestation/root`
#     => 它返回一个 JSON 数组，**正好两把**：老根（RSA-4096，出厂密钥设备用）与新根（ECDSA P-384，RKP 设备用）
#   交叉核对（**第二份独立来源**）：`keybox-tools/res/google.pem` 给的是**裸公钥**，
#     其 SPKI DER 的 sha256 = `feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae`
#     —— 与 `android-security-handbook` lab-03 在**真机链**上量到的根 `spki-sha256` **逐位相同**
#   本文件另有一条运行期自检 `attestation_self_test()`，会把这把老根的 SPKI 再算一遍并比对那个哈希
#
#为什么必须是两把（这条如果漏掉，接收方会**拒掉现代设备**）：
#   Google 于 **2026-02** 引入新根、**2026-04-10** 起 **RKP 设备只链到新根**（ECDSA P-384）；
#   而**出厂预置密钥的老设备**会**继续**链到老根 => 信任库**必须同时保留两把**
#   （来源：jasonbayton/11ty 的 Android Enterprise FAQ「key attestation root certificate change in 2026」
#     ＋ 官方端点本身同时给出两把 => 两份对得上）
GOOGLE_ATTEST_ROOTS: tuple[str, ...] = (
    #1 老根（出厂密钥设备）RSA-4096 subject = serialNumber=f92009e853b6b045
    """-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgw
NzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7
174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGIC
W/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2G
tkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkx
oSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG
1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mF
mr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPz
lHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVw
n6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu
zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHo
vaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHn
w1IdYIg2Wxg7yHcQZemFQg==
-----END CERTIFICATE-----""",
    #2 新根（RKP 设备）ECDSA P-384 CN=Key Attestation CA1, O=Google LLC
    """-----BEGIN CERTIFICATE-----
MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEc
MBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENBMTEQMA4GA1UECwwHQW5kcm9pZDET
MBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4
WhcNMzUwNzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0Ex
MRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29nbGUgTExDMQswCQYDVQQG
EwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCt
G/mLFfRT0MsIssPBEEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5
Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0TAQH/BAUwAwEB
/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepA
MAoGCCqGSM49BAMDA2gAMGUCMETfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifH
EUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1GcxeY
uR2zh/80lQyu9vAFCj6E4AXc+osmRg==
-----END CERTIFICATE-----""",
)
"""上面两把根的 base64 一旦被改过一个字符，`attestation_self_test()` 会当场报出来 —— 见下。
而"我手抄得对不对"这件事**不靠再看一眼**：`C:\\hub\\.scratch\\nk-attest\\fetch_roots.py` 会把
   文件里嵌的这两段与**官方端点返回的字节逐字节比**（本次实测抓到过一处 typo 并把两把根的 spki-sha256 都算了出来）。"""

# 两把内置根的 SPKI sha256（用来给内置根做运行期自检）
#   老根那个值有**两份独立来源**佐证：`keybox-tools/res/google.pem`（裸公钥）与
#     `android-security-handbook` lab-03 在**真机链**上量到的 `spki-sha256` —— 逐位相同
#   新根那个值由**官方端点**的两份副本互校算出（`C:\hub\.scratch\nk-attest\fetch_roots.py`）
_ROOT_SPKI_SHA256 = (
    "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",   # 老根 RSA-4096
    "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec",   # 新根 ECDSA P-384
)

_SECURITY_LEVEL = {0: "Software", 1: "TrustedEnvironment", 2: "StrongBox"}
_VERIFIED_BOOT_STATE = {0: "Verified", 1: "SelfSigned", 2: "Unverified", 3: "Failed"}

# AuthorizationList 里的字段号 = **KeyMint 的 Tag 枚举值**（不是从 1 连续排的）
#
#这张表**只能靠真链定**，不能靠记忆（2026-09-29 我第一版就写错了好几个）：
#   真机链（`C:\hub\.scratch\chain-fixture.pem`）的 hardwareEnforced 实际出现在这些号上：
#     1 2 3 5 6 **200** **503** 702 **704** 705 706 718 719
#   其中 **704 就是 `rootOfTrust`**（长度 76，读出来 verifiedBootState=Verified / deviceLocked=True），
#   而 **200 = rsaPublicExponent**（5 字节 = `02 03 01 00 01` = 65537）、
#   **503 = `noAuthRequired`**（2 字节 = NULL）。
#   => 我第一版把 rootOfTrust 写成 503、把 200 写成 origin => **会读错字段**（已按真链改正）
#
#2026-09-30 **再更正一次 503 的名字**（同一张表第二次错在这一格）：
#   改前叫 `unlockedDeviceRequired` —— **那是 509 的名字**
#   实测依据（同一台模拟器上两把只差"要不要认证"的密钥，用同一套解析读出来）：
#     不要求认证的那把 => softwareEnforced 含 **503**（值 `05 00` = NULL）
#     要求认证的那把   => **503 消失**，改为含 **504**（`02 01 01` / `02 01 03` = USER_AUTH_TYPE）
#   => 503 的语义是 **NO_AUTH_REQUIRED**（KeyMint），不是我原先写的那个
#   而这条错误**不是无害的**：名字写错 => 没人会想到用它来判"这把密钥要不要用户认证"，
#     于是方案 C 那层保证在核对器里就**读不出来**（判据：**名字错了，判断就跟着缺**）
#
# 只登记**已核实**的那些；未登记的会显示成 `fieldNNN` —— 宁可显示成数字，也不要显示一个猜的名字
_AUTH_FIELDS = {
    1: "purpose",                       # 真链 len=8（SET OF INTEGER）
    2: "algorithm",                     # 真链 len=3
    3: "keySize",                       # 真链 len=4
    5: "digest",                        # 真链 len=5
    6: "padding",                       # 真链 len=5
    200: "rsaPublicExponent",           # 真链 len=5
    503: "noAuthRequired",              # NULL 在场 = **不要求**认证（实测见上）
    504: "userAuthType",                # 1=DEVICE_CREDENTIAL 2=BIOMETRIC_STRONG 3=两者
    505: "authTimeout",                 # 缺席（配合 504 在场）表示"每次使用都要认证"
    509: "unlockedDeviceRequired",      # 2026-09-30 归位：原先错挂在 503 上
    701: "creationDateTime",            # 真链 softwareEnforced len=8
    702: "origin",                      # 真链 len=3（ENUMERATED）
    704: "rootOfTrust",                 # 真链 len=76 —— **这条是安全关键，别写错**
    705: "osVersion",
    706: "osPatchLevel",
    709: "attestationApplicationId",    # 真链 softwareEnforced len=74
    718: "vendorPatchLevel",
    719: "bootPatchLevel",
}


class AttestError(Exception):
    """attestation 解析/验证过程中**可预期**的失败（不是程序崩溃）"""


# ---------- ASN.1 基础（只支持定长形式 —— X.509 / KeyDescription 都用定长）----------

def _tlv(buf: bytes, i: int = 0):
    tag, content, nxt = _der_tlv(buf, i)
    return tag, content, nxt


def _tlv_all(buf: bytes) -> list[tuple[int, bytes]]:
    out: list[tuple[int, bytes]] = []
    i = 0
    while i < len(buf):
        tag, content, i = _tlv(buf, i)
        out.append((tag, content))
    return out


def _oid_dotted(b: bytes) -> str:
    if not b:
        raise AttestError("空 OID")
    first = b[0]
    parts = [str(first // 40), str(first % 40)]
    val = 0
    for byte in b[1:]:
        val = (val << 7) | (byte & 0x7F)
        if not byte & 0x80:
            parts.append(str(val))
            val = 0
    return ".".join(parts)


def _int_of(b: bytes) -> int:
    return int.from_bytes(b, "big", signed=True)


_NAME_OIDS = {
    "2.5.4.3": "CN", "2.5.4.10": "O", "2.5.4.11": "OU", "2.5.4.6": "C",
    "2.5.4.5": "serialNumber", "1.2.840.113549.1.9.1": "emailAddress",
}


def _name_text(name_tlv: bytes) -> str:
    """把 `Name`（**完整 TLV**）渲染成 `CN=…, O=…` —— 只为可读，**比较一律用原始 DER**"""
    try:
        _t, rdn_content, _ = _tlv(name_tlv, 0)
        parts: list[str] = []
        for _tag, rdn in _tlv_all(rdn_content):        # 每个 rdn 是一个 SET
            for _t2, atv in _tlv_all(rdn):             # SET 的内容是一个个 AttributeTypeAndValue
                kids = _tlv_all(atv)
                if len(kids) != 2:
                    continue
                oid = _oid_dotted(kids[0][1])
                try:
                    val = kids[1][1].decode("utf-8", "replace")
                except Exception:                      # noqa: BLE001
                    val = kids[1][1].hex()
                parts.append(f"{_NAME_OIDS.get(oid, oid)}={val}")
        return ", ".join(parts) if parts else name_tlv.hex()
    except Exception:                                  # noqa: BLE001
        return name_tlv.hex()


def _tlv_spans(buf: bytes) -> list[tuple[int, bytes, int, int]]:
    """与 `_tlv_all` 同，但**保留每个 TLV 的起止偏移**

    为什么必须留偏移（2026-09-29 踩到）：
       `SubjectPublicKeyInfo` 的"值"必须是**含它自己那层 SEQUENCE 头的完整 DER**
       —— 因为公钥指纹的通行口径就是**整段 SPKI DER**的哈希，而 `_tlv_all` 只给内容
       实测后果：只取内容 => 老根的 spki-sha256 算成 `4dd03e81…`，
       而两份独立来源（keybox-tools 的裸公钥 handbook 真机量到的值）都是 `feb2ea75…` => **差一个 4 字节头**
    """
    out: list[tuple[int, bytes, int, int]] = []
    i = 0
    while i < len(buf):
        start = i
        tag, content, nxt = _tlv(buf, i)
        out.append((tag, content, start, nxt))
        i = nxt
    return out


def parse_cert(der: bytes) -> dict:
    """极简 X.509 解析（只取验链需要的那些字段）"""
    tag, cert, _ = _tlv(der)
    if tag != 0x30:
        raise AttestError("证书不是 SEQUENCE")
    top = _tlv_spans(cert)
    if len(top) != 3:
        raise AttestError(f"证书顶层应有 3 项，实得 {len(top)}")
    # 约定：`tbs` / `spki` / `issuer_raw` / `subject_raw` 都存**完整 TLV（含头）**
    #    —— 因为公钥指纹的通行口径就是"整段 SPKI DER"的哈希
    #    （只取内容会把老根算成 `4dd03e81…`，而两份独立来源都是 `feb2ea75…` => 差一个 4 字节头）
    tbs_raw = cert[top[0][2]:top[0][3]]            # tbs 的原始字节（含头）—— 验签要的正是它
    tbs_content = top[0][1]                        # 字段要走**内容**（走含头的那段只会得到它自己）

    sigalg = top[1][1]
    sig_oid = _oid_dotted(_tlv_all(sigalg)[0][1])
    sig_bits = top[2][1]
    if not sig_bits or sig_bits[0] != 0x00:
        raise AttestError("signatureValue 的 BIT STRING 前导字节不是 0")
    signature = sig_bits[1:]

    tk = _tlv_spans(tbs_content)
    i = 0
    version = 1
    if tk and tk[0][0] == 0xA0:
        version = _int_of(_tlv_all(tk[0][1])[0][1]) + 1
        i = 1
    if len(tk) < i + 6:
        raise AttestError("TBSCertificate 字段不够")
    serial = _int_of(tk[i][1])
    issuer = tbs_content[tk[i + 2][2]:tk[i + 2][3]]     # Name 的内容（两侧同口径 => 比较有效）
    subject = tbs_content[tk[i + 4][2]:tk[i + 4][3]]
    # `_tlv_spans` 给的 [2]:[3] 是**整个 TLV** 的起止（含头）=> 这样切出来就带 SPKI 自己的 SEQUENCE 头
    spki = tbs_content[tk[i + 5][2]:tk[i + 5][3]]
    exts: dict[str, bytes] = {}
    for tag_, content, _s, _e in tk[i + 6:]:
        if tag_ == 0xA3:
            for _t, ext in _tlv_all(_tlv_all(content)[0][1]):
                ek = _tlv_all(ext)
                if len(ek) >= 2:
                    exts[_oid_dotted(ek[0][1])] = ek[1][1]
    return {
        "version": version, "serial": serial,
        "issuer_raw": issuer, "subject_raw": subject,
        "issuer": _name_text(issuer), "subject": _name_text(subject),
        "spki": spki, "sig_alg": sig_oid, "signature": signature,
        "tbs": tbs_raw, "exts": exts,
        "self_signed": issuer == subject,
    }


# ---------- 公钥 ----------

def _spki_sha256(spki: bytes) -> str:
    return hashlib.sha256(spki).hexdigest()


def _spki_parts(spki: bytes) -> tuple[tuple[int, bytes], tuple[int, bytes]]:
    """拆 `SubjectPublicKeyInfo`（**完整 TLV**）-> (AlgorithmIdentifier, BIT STRING)

    所有 SPKI 消费者都要走这里 —— 因为 `parse_cert` 交出来的是**含头**的那一段
       （2026-09-29 踩到：`_tlv_all(spki)` 直接解析含头的 SPKI 只会得到**它自己一项** => 各消费者全错）
    """
    _t, content, _ = _tlv(spki, 0)
    kids = _tlv_all(content)
    if len(kids) != 2:
        raise AttestError(f"SPKI 内容应有 2 项，实得 {len(kids)}")
    return kids[0], kids[1]


def _rsa_from_spki(spki: bytes) -> tuple[int, int]:
    _alg, bits_tlv = _spki_parts(spki)
    if bits_tlv[0] != 0x03:
        raise AttestError("SPKI 第二项不是 BIT STRING")
    bits = bits_tlv[1]
    if not bits or bits[0] != 0x00:
        raise AttestError("SPKI BIT STRING 前导字节不是 0")
    inner = _tlv_all(bits[1:])
    if len(inner) != 1 or inner[0][0] != 0x30:
        raise AttestError("BIT STRING 里不是 RSA 公钥 SEQUENCE")
    ints = _tlv_all(inner[0][1])
    if len(ints) != 2 or ints[0][0] != 0x02 or ints[1][0] != 0x02:
        raise AttestError("RSA 公钥不是 { INTEGER n, INTEGER e }")
    return int.from_bytes(ints[0][1], "big"), int.from_bytes(ints[1][1], "big")


def _spki_key_kind(spki: bytes) -> str:
    """只报算法名（不参与判断 —— 判据是**签名算法 OID**）"""
    try:
        alg, _bits = _spki_parts(spki)
        oid = _oid_dotted(_tlv_all(alg[1])[0][1])
    except Exception:                                  # noqa: BLE001
        return "?"
    if oid == "1.2.840.113549.1.1.1":
        try:
            n, _e = _rsa_from_spki(spki)
            return f"RSA-{n.bit_length()}"
        except AttestError:
            return "RSA-?"
    if oid == "1.2.840.10045.2.1":
        try:
            sub = _tlv_all(alg[1])
            curve = _oid_dotted(sub[1][1]) if len(sub) > 1 else "?"
        except Exception:                              # noqa: BLE001
            curve = "?"
        return {"1.2.840.10045.3.1.7": "EC-P256", "1.3.132.0.34": "EC-P384"}.get(curve, f"EC({curve})")
    return f"({oid})"


# ---------- ECDSA（P-256 / P-384 纯 Python）----------
#
# 为什么**必须**有它（这一条推翻了我原来的判断）：
#   本文件旧注释说「EC 要在纯 Python 里写点运算 => 上百行且易错」——
#   而 **Google 2026 换根之后，新根就是 ECDSA P-384**，且新链的链接签名也可能是 EC
#   => 不实现 EC => 现代设备的链**一条都验不了** => pin 根这件事**等于没做**
#   而"验不了"**不许**当"通过"：`verify_cert_signature` 对不支持的算法**一律返回失败**
_EC_CURVES = {
    "1.2.840.10045.3.1.7": (  # secp256r1 / P-256
        0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF,
        -3,
        0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B,
        0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
        0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5,
        0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551,
        "sha256",
    ),
    "1.3.132.0.34": (  # secp384r1 / P-384
        0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF,
        -3,
        0xB3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE8141120314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF,
        0xAA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7,
        0x3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F,
        0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973,
        "sha384",
    ),
}


def _ec_add(P, Q, p, a):
    if P is None:
        return Q
    if Q is None:
        return P
    x1, y1 = P
    x2, y2 = Q
    if x1 == x2 and (y1 + y2) % p == 0:
        return None
    if P == Q:
        lam = (3 * x1 * x1 + a) * pow(2 * y1, -1, p) % p
    else:
        lam = (y2 - y1) * pow(x2 - x1, -1, p) % p
    x3 = (lam * lam - x1 - x2) % p
    return x3, (lam * (x1 - x3) - y1) % p


def _ec_mul(k: int, P, p, a):
    R = None
    while k:
        if k & 1:
            R = _ec_add(R, P, p, a)
        P = _ec_add(P, P, p, a)
        k >>= 1
    return R


def _ec_verify(spki: bytes, sig: bytes, message: bytes, curve_oid: str) -> tuple[bool, str]:
    if curve_oid not in _EC_CURVES:
        return False, f"不支持的椭圆曲线 {curve_oid}（只实现了 P-256 / P-384）"
    p, a, b, gx, gy, n, digest = _EC_CURVES[curve_oid]
    try:
        _alg, bits_tlv = _spki_parts(spki)
        if bits_tlv[0] != 0x03:
            return False, "EC 的 SPKI 第二项不是 BIT STRING"
        bits = bits_tlv[1]
        if not bits or bits[0] != 0x00:
            return False, "EC 公钥的 BIT STRING 前导字节不是 0"
        point = bits[1:]
        if not point or point[0] != 0x04:
            return False, "EC 公钥不是未压缩点（首字节应 0x04）"
        if (len(point) - 1) % 2 != 0:
            return False, "EC 公钥点长度不是偶数"
        half = (len(point) - 1) // 2
        Q = (int.from_bytes(point[1:1 + half], "big"), int.from_bytes(point[1 + half:], "big"))
    except Exception as exc:                           # noqa: BLE001
        return False, f"EC 公钥解析失败（{exc}）"
    # 公钥必须在曲线上（这一步同时给 p / a / b 三个常数当判据）
    if (Q[1] * Q[1] - Q[0] * Q[0] * Q[0] - a * Q[0] - b) % p != 0:
        return False, "EC 公钥不在曲线上"
    try:
        _t, sig_content, _ = _tlv(sig, 0)              # 签名给的是**完整 DER** => 先解一层
        rs = _tlv_all(sig_content)
        if len(rs) != 2 or rs[0][0] != 0x02 or rs[1][0] != 0x02:
            return False, "ECDSA 签名不是 SEQUENCE{ INTEGER r, INTEGER s }"
        r = int.from_bytes(rs[0][1], "big")
        s = int.from_bytes(rs[1][1], "big")
    except Exception as exc:                           # noqa: BLE001
        return False, f"ECDSA 签名解析失败（{exc}）"
    if not (1 <= r < n and 1 <= s < n):
        return False, "ECDSA 的 r/s 不在 [1, n-1]"
    e = int.from_bytes(hashlib.new(digest, message).digest(), "big")
    e >>= max(0, len(hashlib.new(digest).digest()) * 8 - n.bit_length())
    w = pow(s, -1, n)
    P = _ec_add(_ec_mul(e * w % n, (gx, gy), p, a), _ec_mul(r * w % n, Q, p, a), p, a)
    if P is None:
        return False, "ECDSA 验证时点落在无穷远"
    return (P[0] % n == r), "ok" if P[0] % n == r else "ECDSA 的 v != r"


# ---------- RSA ----------

# 常见摘要的 DigestInfo 前缀（PKCS#1 v1.5 规定）—— SHA-256 那条本文件已有
_DIGESTINFO_PREFIX = {
    "sha1": bytes.fromhex("3021300906052b0e03021a05000414"),
    "sha256": bytes.fromhex("3031300d060960864801650304020105000420"),
    "sha384": bytes.fromhex("3041300d060960864801650304020205000430"),
    "sha512": bytes.fromhex("3051300d060960864801650304020305000440"),
}

_SIG_ALGS = {
    "1.2.840.113549.1.1.5": ("rsa", "sha1"),
    "1.2.840.113549.1.1.11": ("rsa", "sha256"),
    "1.2.840.113549.1.1.12": ("rsa", "sha384"),
    "1.2.840.113549.1.1.13": ("rsa", "sha512"),
    "1.2.840.10045.4.3.2": ("ec", "sha256"),
    "1.2.840.10045.4.3.3": ("ec", "sha384"),
}


def _rsa_verify(spki: bytes, sig: bytes, message: bytes, digest: str) -> tuple[bool, str]:
    try:
        n, e = _rsa_from_spki(spki)
    except Exception as exc:                           # noqa: BLE001
        return False, f"父证书公钥解析失败（{exc}）"
    if e <= 1 or e % 2 == 0:
        return False, f"父证书公钥的公开指数不健全（e={e}）"
    k = (n.bit_length() + 7) // 8
    sig_int = int.from_bytes(sig, "big")
    if sig_int >= n:
        return False, "签名数值不小于模数"
    m = pow(sig_int, e, n).to_bytes(k, "big")
    if not m.startswith(b"\x00\x01"):
        return False, "填充不以 00 01 开头"
    idx = m.find(b"\x00", 2)
    if idx < 0:
        return False, "填充里没有 00 分隔符"
    if any(byte != 0xFF for byte in m[2:idx]):
        return False, "填充段里出现了非 FF 字节"
    if idx < 10:
        return False, "填充段太短（FF 少于 8 字节）"
    want = _DIGESTINFO_PREFIX[digest] + hashlib.new(digest, message).digest()
    return (m[idx + 1:] == want), "ok" if m[idx + 1:] == want else "DigestInfo 或摘要不匹配"


def verify_cert_signature(child: dict, parent_spki: bytes) -> tuple[bool, str]:
    """用**父证书的公钥**验**子证书的签名**

    不支持的算法 => **返回失败**（不是"跳过"）——
       因为本工具的用途是**信任判定**；"验不了"不能当"通过了"
    """
    alg = child["sig_alg"]
    if alg not in _SIG_ALGS:
        if alg in ("1.2.840.113549.1.1.10",):          # RSASSA-PSS
            return False, "签名算法是 RSASSA-PSS —— 本工具未实现 => **无法验证**（不能当通过）"
        return False, f"未实现的签名算法 OID {alg} => **无法验证**（不能当通过）"
    kind, digest = _SIG_ALGS[alg]
    if kind == "rsa":
        return _rsa_verify(parent_spki, child["signature"], child["tbs"], digest)
    curve = "?"
    try:
        algid, _bits = _spki_parts(parent_spki)
        sub = _tlv_all(algid[1])
        curve = _oid_dotted(sub[1][1]) if len(sub) > 1 else "?"
    except Exception:                                  # noqa: BLE001
        pass
    return _ec_verify(parent_spki, child["signature"], child["tbs"], curve)


# ---------- 证书链 ----------

def split_pem_certs(text: str) -> list[str]:
    """把"若干张 PEM 拼接"切成一张张（多行/单行都能切）"""
    import re as _re
    return _re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", text, _re.S)


def verify_attestation_chain(pem_text: str) -> tuple[bool, str, list[dict], int]:
    """逐级验证：子.issuer == 父.subject，且子签名能被父公钥验过

    返回 (是否整链验过, 说明, 每张证书的 dict, 命中的内置根下标（-1 = 没命中）)
    """
    pems = split_pem_certs(pem_text)
    if not pems:
        return False, "attestationChain 里没有 PEM 证书", [], -1
    certs = [parse_cert(_pem_to_der(p)) for p in pems]
    lines: list[str] = []
    ok = True
    for idx, c in enumerate(certs):
        lines.append(f"      [{idx}] subject={c['subject']}   key={_spki_key_kind(c['spki'])}"
                     f"   签名算法={c['sig_alg']}")
    for idx in range(len(certs) - 1):
        child, parent = certs[idx], certs[idx + 1]
        if child["issuer_raw"] != parent["subject_raw"]:
            lines.append(f"      [{idx}] 的 issuer 与 [{idx + 1}] 的 subject **不相等**"
                         f"（{child['issuer']} vs {parent['subject']}）")
            ok = False
            continue
        good, why = verify_cert_signature(child, parent["spki"])
        lines.append(f"      {'' if good else ''} [{idx}] 由 [{idx + 1}] 签"
                     f"（issuer/subject 相符 签名{'验过' if good else '**验不过**：' + why}）")
        ok = ok and good
    # 根：必须自签（否则"链"根本没到头）
    root = certs[-1]
    if root["issuer_raw"] != root["subject_raw"]:
        lines.append("      最后一张不是自签（issuer != subject）=> 这不是一条到头了的链")
        ok = False
    else:
        good, why = verify_cert_signature(root, root["spki"])
        lines.append(f"      {'' if good else ''} 根自签{'验过' if good else '**验不过**：' + why}")
        ok = ok and good
    #pin 根（**按公钥**，不按整张证书 —— 同名的证书可以被重发）
    hit = -1
    root_spki = root["spki"]
    for i, pinned in enumerate(GOOGLE_ATTEST_ROOTS):
        try:
            if parse_cert(_pem_to_der(pinned))["spki"] == root_spki:
                hit = i
                break
        except Exception:                              # noqa: BLE001
            continue
    return ok, "\n".join(lines), certs, hit


# ---------- KeyDescription（OID 1.3.6.1.4.1.11129.2.1.17）----------

def _maybe_unwrap(content: bytes, want_tag: int) -> bytes:
    """兼容 EXPLICIT / IMPLICIT 两种标注（只在该内容**恰好**是一个完整 TLV 且 tag 对得上时解包）"""
    if len(content) >= 2 and content[0] == want_tag:
        try:
            _t, inner, nxt = _tlv(content, 0)
            if nxt == len(content):
                return inner
        except Exception:                              # noqa: BLE001
            pass
    return content


def _auth_list_long_form(seq_content: bytes) -> dict:
    """解析一个 `AuthorizationList` —— 返回 {字段名: 原始内容}

    必须支持 `[503]` 这类**长形式** context tag（`rootOfTrust` 就在其中）——
       只认 1 字节 tag 的实现会**静默地漏掉 rootOfTrust** => 那正是"该读的字段读不到"的经典失法
    """
    out: dict[str, bytes] = {}
    i = 0
    while i < len(seq_content):
        tag = seq_content[i]
        i += 1
        if tag & 0x1F == 0x1F:                         # 长形式 tag number
            num = 0
            while i < len(seq_content):
                byte = seq_content[i]
                i += 1
                num = (num << 7) | (byte & 0x7F)
                if not byte & 0x80:
                    break
        else:
            num = tag & 0x1F
        first = seq_content[i]
        i += 1
        if first < 0x80:
            length = first
        else:
            nbytes = first & 0x7F
            length = int.from_bytes(seq_content[i:i + nbytes], "big")
            i += nbytes
        out[_AUTH_FIELDS.get(num, f"field{num}")] = seq_content[i:i + length]
        i += length
    return out


def parse_root_of_trust(content: bytes) -> dict:
    """`RootOfTrust ::= SEQUENCE { verifiedBootKey OCTET_STRING, deviceLocked BOOLEAN,
    verifiedBootState ENUMERATED, verifiedBootHash OCTET_STRING }`

   **这里的字段是【普通 ASN.1 tag】，不是 context tag** —— 2026-09-29 我写错过一次：
       真机链里 `[704]` 的内容是 `30 4a 04 20 <32B key> 01 01 ff 0a 01 00 04 20 <32B hash>`
       —— `04`=OCTET STRING `01`=BOOLEAN `0a`=ENUMERATED，**两个 OCTET STRING 只能靠位置区分**（前=key、后=hash）
       我第一版按 `tag & 0x1F` 当"字段号"读（0/1/2/3）=>
          `01 01 ff` 恰好落进 `num==1` 的分支 => **deviceLocked 读对了**（纯属巧合），
          而 `0a 01 00` 落成 `num==10` => **`verifiedBootState` 被静默丢掉**
       => 这正是"**读对了一半**"最危险的地方：字段少一个，报告里只显示一个 `?`，不报错
    而 context tag 那种写法（`80/81/82/83`）也一并收着 —— 别把版本差异读成"证书坏了"
    """
    inner = _maybe_unwrap(content, 0x30)           # [704] EXPLICIT => 内层是一个 SEQUENCE
    out: dict[str, object] = {}
    octets: list[bytes] = []
    for tag, body in _tlv_all(inner):
        if tag == 0x01:                            # BOOLEAN
            out["deviceLocked"] = bool(body and body[0] != 0)
        elif tag == 0x0A:                          # ENUMERATED
            val = body[-1] if body else -1
            out["verifiedBootState"] = _VERIFIED_BOOT_STATE.get(val, f"?({val})")
        elif tag == 0x04:                          # OCTET STRING（**出现两次** => 按位置分）
            octets.append(body)
        elif 0x80 <= tag <= 0x9F:                  # 兼容 context tag 写法
            num = tag & 0x1F
            raw = _maybe_unwrap(body, (0x04, 0x01, 0x0A)[num] if num < 3 else 0x04)
            if num == 0:
                octets.insert(0, raw)
            elif num == 1:
                out["deviceLocked"] = bool(raw and raw[0] != 0)
            elif num == 2:
                val = raw[-1] if raw else -1
                out["verifiedBootState"] = _VERIFIED_BOOT_STATE.get(val, f"?({val})")
            elif num == 3:
                octets.append(raw)
    if octets:
        out["verifiedBootKey"] = octets[0]
    if len(octets) > 1:
        out["verifiedBootHash"] = octets[-1]
    return out


def parse_key_description(ext_value: bytes) -> dict:
    """解析证书扩展 `1.3.6.1.4.1.11129.2.1.17` 的内容

    `KeyDescription ::= SEQUENCE { attestationVersion INTEGER, attestationSecurityLevel SecurityLevel,
       keymasterVersion INTEGER, keymasterSecurityLevel SecurityLevel, attestationChallenge OCTET STRING,
       uniqueId OCTET STRING, softwareEnforced AuthorizationList, hardwareEnforced AuthorizationList }`

    关于"要不要先剥一层 OCTET STRING"（这里踩过一次，故两种都收）：
       X.509 的 `extnValue` **本身就是** OCTET STRING；而 `parse_cert` 交给本函数的是
       那个 OCTET STRING 的**内容** => 也就是 `KeyDescription` 的 DER
       但有些来源（含真机产物）会**再套一层** OCTET STRING => 两种写法都要能读，
          否则"解析失败"会被误读成"证书有问题"
    """
    tag, content, nxt = _tlv(ext_value, 0)
    if tag == 0x04 and nxt == len(ext_value):
        ext_value = content                            # 多套了一层 => 剥掉
    _t2, kd, _ = _tlv(ext_value, 0)
    if _t2 != 0x30:
        raise AttestError(f"KeyDescription 应为 SEQUENCE，实得 tag={_t2:#x}")
    kids = _tlv_all(kd)
    if len(kids) != 8:
        raise AttestError(f"KeyDescription 应有 8 项，实得 {len(kids)}")
    sw = _auth_list_long_form(kids[6][1])
    hw = _auth_list_long_form(kids[7][1])
    if "rootOfTrust" in hw:
        hw["rootOfTrust"] = parse_root_of_trust(hw["rootOfTrust"])          # type: ignore[assignment]
    if "rootOfTrust" in sw:
        sw["rootOfTrust"] = parse_root_of_trust(sw["rootOfTrust"])          # type: ignore[assignment]
    return {
        "attestationVersion": _int_of(kids[0][1]),
        "attestationSecurityLevel": _SECURITY_LEVEL.get(_int_of(kids[1][1]), f"?({_int_of(kids[1][1])})"),
        "keymasterVersion": _int_of(kids[2][1]),
        "keymasterSecurityLevel": _SECURITY_LEVEL.get(_int_of(kids[3][1]), f"?({_int_of(kids[3][1])})"),
        "attestationChallenge": kids[4][1],
        "uniqueId": kids[5][1],
        "softwareEnforced": {k: v for k, v in sw.items()},
        "hardwareEnforced": {k: v for k, v in hw.items()},
    }


# ---------- 运行期自检（内置根一旦被改坏，当场报出来）----------

#App 身份（tag 709 2026-09-30 加）
# 为什么必须有这一条：不查 709 的话，**同设备任意 App** 都能铸一把 TEE 背书、链到同一把 Google 根的密钥，
#   把 challenge 填成自己伪造内容的 hash、公钥与链都填成自己的
#   => 根 pin 叶子 SPKI == 自己的公钥 securityLevel=TEE rootOfTrust challenge => rc=0。
#   那样这一层回答的是"**某台设备的安全硬件为某把密钥背书**"，而不是"这份产物是 NK 生成的"。
#
# 取值来源（两条独立路径，2026-09-30 核过、逐字节相同）：
#   包名 = AndroidManifest 的 applicationId
#   证书摘要 = sha256(签名证书 DER)：本机用 keytool 从 ~/.android/debug.keystore 导出后算得
#     `39b59b5f…`，与真链 709 里那个 32 字节摘要一致（位置 42）
NK_PACKAGE_NAME = "com.liumiaoqi.notificationkeep"
NK_SIGNING_CERT_SHA256 = "39b59b5f12bdd32fc534c2bb59fa82b17d574a5ea9b055bdf4ee7658ebd55f4b"


def parse_attestation_application_id(content: bytes) -> tuple[list[str], list[bytes]]:
    """解 tag 709，返回 (包名列表, 签名证书摘要列表)。

    真链实测形状（hex）：
        0448 3046 3120 301e 0419 <包名 25B> 020101
                  3122 0420 <32 字节摘要>
    即 OCTET STRING 包一个 SEQUENCE，里面两个容器：先 PackageInfo、后 signatureDigests。

    容器 tag **不写死**：规范写的是 SEQUENCE OF / SET OF，而实测这两个是 `31`(SET)。
       按"里面装的是 SEQUENCE 还是 OCTET STRING"来分，比按容器 tag 分稳。
    """
    v = _maybe_unwrap(content, 0x04)
    tag, body, _ = _tlv(v, 0)
    if tag != 0x30:
        raise AttestError(f"attestationApplicationId 的外层不是 SEQUENCE（tag=0x{tag:02x}）")
    names: list[str] = []
    digests: list[bytes] = []
    for _t, container in _tlv_all(body):
        for t2, c2 in _tlv_all(container):
            if t2 == 0x30:                              # PackageInfo
                inner = _tlv_all(c2)
                if inner and inner[0][0] == 0x04:
                    names.append(inner[0][1].decode("utf-8", "replace"))
            elif t2 == 0x04:                            # signatureDigest
                digests.append(c2)
    return names, digests


def check_app_identity(kd: dict) -> tuple[bool, str]:
    """查这条链是不是**这个 App** 的。

    必须从 softwareEnforced 读：平台把 `attestationApplicationId` 放在那个列表里。
       这不违反"只读 hardwareEnforced"那条 —— 那条管的是**硬件声明**（`rootOfTrust` 之类）；
       而 709 说的是 App 身份，本来就是 Android 侧写的。两个列表**都在证书里、都被 TEE 签过**，
       区别是语义（谁 enforced），不是真假。
    """
    raw = kd["softwareEnforced"].get("attestationApplicationId")
    if not raw:
        return False, ("链里**没有** attestationApplicationId（tag 709）"
                       "=> 无法证明这条链是这个 App 的（任何 App 都能铸一条链到同一把 Google 根）")
    try:
        names, digests = parse_attestation_application_id(raw)
    except Exception as exc:                            # noqa: BLE001
        return False, f"attestationApplicationId 解不出：{type(exc).__name__} {exc}"
    if NK_PACKAGE_NAME not in names:
        return False, (f"链的 App 身份不对：包名 {names} ≠ {NK_PACKAGE_NAME}"
                       " => 这条链是**别的 App** 的（它只证明某台设备为某把密钥背书）")
    if NK_SIGNING_CERT_SHA256 not in [d.hex() for d in digests]:
        return False, (f"链的签名证书摘要对不上：{[d.hex()[:16] + '…' for d in digests]}"
                       f" ≠ {NK_SIGNING_CERT_SHA256[:16]}… => 这条链是别的 App 的")
    return True, (f"App 身份：包名 {NK_PACKAGE_NAME} 签名证书摘要 {NK_SIGNING_CERT_SHA256[:16]}…"
                  "（从 softwareEnforced 读；它同样是 TEE 签过的）")


def _int_from_auth_value(raw: bytes | None) -> int | None:
    """读 AuthorizationList 里一个整数字段的值。

   实测（2026-09-30）：那个字段的内容是**一层 TLV**，不是裸字节 ——
      `504` 的内容是 `02 01 03`（INTEGER 3），**不是** `03`。
      我第一版按裸字节读 => 报成「未知(131331)」（131331 = 0x020103）
    => 先当成 TLV 解一层；解不出就退回按裸字节读（保守，但不静默说谎）。
    """
    if not raw:
        return None
    try:
        tag, content, nxt = _tlv(raw, 0)
        if tag == 0x02 and nxt == len(raw):
            return int.from_bytes(content, "big")
    except Exception:
        pass
    return int.from_bytes(raw, "big")


def describe_user_auth(sw: dict | None, hw: dict | None) -> str:
    """这把签名密钥**要不要用户认证**？—— 如实报告，**不是失败判据**。

    判据（KeyMint 的 AuthorizationList tag，2026-09-30 实测定）：
      `503 noAuthRequired` **在场**（NULL）=> **不要求** —— 密钥可以被静默驱动
      `503` 缺席 且 `504 userAuthType` 在场 => **要求**；再看 `505 authTimeout`：
        缺席或 0 => **每次使用都要**（方案 C 想要的形态）；> 0 => 有个有效窗口

    为什么它**不能**判失败：没要求认证的产物是**合法**的（方案 C 之前全是那样），
       而"要当证据用就该要求它"由接收方按用途定 —— 与"链缺席不算失败"同一个处理。
    字段落在 softwareEnforced —— 与"只读 hardwareEnforced"那条**不冲突**：
       那条管的是**硬件声明**；"要不要认证"是策略（真机与模拟器上都落在 softwareEnforced）
    """
    sw = sw or {}
    hw = hw or {}

    def has(name: str) -> bool:
        return name in sw or name in hw

    if has("noAuthRequired"):
        return ("签名密钥的用户认证：**不要求** => 进程内拿到执行权的人可以**静默**驱动它签名"
                "（方案 C 之前的形态）")
    if not has("userAuthType"):
        return "签名密钥的用户认证：503 / 504 都不在场 => 与「不要求」同效"
    raw = sw.get("userAuthType") or hw.get("userAuthType")
    t = _int_from_auth_value(raw)
    names = {1: "设备凭据", 2: "生物特征", 3: "设备凭据或生物特征"}.get(t, f"未知({t})")
    secs = _int_from_auth_value(sw.get("authTimeout") or hw.get("authTimeout"))
    if not secs:
        return f"签名密钥的用户认证：**要求**（{names} **每次使用都要**）方案 C 的形态"
    return f"签名密钥的用户认证：**要求**（{names}），但**有 {secs} 秒的有效窗口** => 窗口内可被静默重用"


def attestation_self_test() -> tuple[bool, str]:
    """自检内置的两把根：1 能解析 2 SPKI 哈希对得上 3 **自签名验得过**

    3 不只是形式 —— 新根是 **ECDSA P-384 自签**的，
       => 它同时充当**本文件那段 EC 实现的真值向量**：
         若曲线常数（p/a/b/G/n）或点运算写错，这一步**必红**
         而那正是"我记得这些常数"最不可靠的地方（`coding-discipline` §七）
    """
    parts: list[str] = []
    try:
        certs = [parse_cert(_pem_to_der(p)) for p in GOOGLE_ATTEST_ROOTS]
    except Exception as exc:                           # noqa: BLE001
        return False, f"内置根解不出来（{exc}）—— 很可能是 base64 被改坏了"
    for i, c in enumerate(certs):
        got = _spki_sha256(c["spki"])
        want = _ROOT_SPKI_SHA256[i]
        if got != want:
            return False, (f"内置根[{i}] 的 SPKI sha256 不符：实算 {got[:16]}… 期望 {want[:16]}…"
                           " => 内置根被改过或被截断")
        if not c["self_signed"]:
            return False, f"内置根[{i}] 不是自签（issuer != subject）=> 内置常量有误"
        good, why = verify_cert_signature(c, c["spki"])
        if not good:
            return False, (f"内置根[{i}] 的自签名验不过（{why}）=> 根常量或其签名算法实现有误")
        parts.append(f"[{i}] {_spki_key_kind(c['spki'])} 自签spki={got[:16]}…")
    return True, " ".join(parts)


# ---------- 检查入口 ----------

def check_attestation(meta: list[dict], content_hash: str | None) -> tuple[int, str, str]:
    """出处证明那一层 —— 返回 (状态, 说明, 详情文本)

    三态（与 `check_signature` **同族**，必须分清）：
        `0` 链验过 **且** 根 pin 上 **且** challenge 对得上
        `1` 有那一层，但**链/根/challenge 有问题** => 真问题（置失败）
        `2` **文件里没有 `attestationChain`** => **不是失败**
              —— 老产物就是没有这一层（与 A8 那条**不一样**：
                 A8 的 `exportSelfHash` 缺失会影响**已有的**保护强度；
                 而 attestation 本来就是一个**新增的**层 => 缺它只是"没有这一层"）
    """
    n_chain = sum(1 for m in meta if "attestationChain" in m)
    raw = pick(meta, "attestationChain")
    if raw is None or str(raw) in ("", "null"):
        return 2, "这份产物没有 attestationChain 那一层（老产物 / 或设备上没建成证明密钥）", ""
    if n_chain > 1:
        # 与 exportSelfHash 那条同族（F2f1）：pick 是 first-wins => 多份就该报出来
        return 1, (f"文件里有 {n_chain} 处 attestationChain => 本工具取的是**第一处**，"
                   "多于一处的产物不可信"), ""
    text = str(raw)
    if "\\n" in text and "-----BEGIN" not in text:
        text = text.replace("\\n", "\n")               # 兼容被双重转义过的写法

    detail: list[str] = []
    ok_self, self_why = attestation_self_test()
    if not ok_self:
        return 1, f"内置根自检失败：{self_why}", ""

    good, why, certs, root_hit = verify_attestation_chain(text)
    detail.append(why if why else "")
    if not certs:
        return 1, f"链解不出来：{why}", ""
    if not good:
        return 1, "链**验不过**（逐级签名 / issuer-subject / 根自签其一不成立）", "\n".join(detail)
    #pin 根 —— **这一条是"有效性"与"可信性"的分界**
    if root_hit < 0:
        root = certs[-1]
        return 1, (f"链本身自洽，但**根 pin 不上**：根的 spki="
                   f"{_spki_sha256(root['spki'])[:16]}… 不在内置的 {len(GOOGLE_ATTEST_ROOTS)} 把 Google 根里"
                   f" => 这正是「模拟器链/自签链」的形状：**每条签名都验得过，而它证明不了出处**"), \
            "\n".join(detail)

    # 找到带 attestation 扩展的那张（应当是叶子）
    leaf_idx = -1
    for idx, c in enumerate(certs):
        if ATTEST_OID in c["exts"]:
            leaf_idx = idx
            break
    if leaf_idx < 0:
        return 1, "链里没有任何一张证书带 attestation 扩展（OID 1.3.6.1.4.1.11129.2.1.17）" \
                  " => 这条链证明不了任何硬件声明", "\n".join(detail)
    if leaf_idx != 0:
        detail.append(f"      带 attestation 扩展的是 [{leaf_idx}]，不是叶子 [0] —— 与预期不符")

    #把链**绑到签名公钥**上（这一刀才闭合「公钥归谁」）
    #   缺这一条时，链证明的是「**某把硬件密钥**为某个 KeyDescription 背书」，
    #     而产物里的签名是拿 `publicKeyPem` 验的 => **两者可以不是同一把**
    #     攻击：拿一条**真的**链（真设备出的）+ **自己的**签名公钥 =>
    #       链那一层全绿，而真正签字的其实是他 —— 缺口换个形态又回来了
    #   => 判据：**比字节**（不是比 PEM 文本 —— 换行/头尾可能不同）
    pub_pem = pick(meta, "publicKeyPem")
    if not pub_pem or str(pub_pem) == "null":
        return 1, ("链在，但产物里**没有 publicKeyPem** => 无法把链绑到签名公钥上"
                   "（「这把公钥归谁」这个问题**答不了**）"), "\n".join(detail)
    try:
        sign_spki = _pem_to_der(str(pub_pem))
    except Exception as exc:                           # noqa: BLE001
        return 1, f"publicKeyPem 解不出 DER（{exc}）=> 无法把链绑到签名公钥", "\n".join(detail)
    leaf_spki = certs[leaf_idx]["spki"]
    if sign_spki != leaf_spki:
        said = ""
        try:
            # 只是**同把密钥、编码不同**，还是**真是两把**？—— 报清楚，别让接收方猜
            said = ("（两边的密钥*类型*相同但字节不同 —— **按字节判失败**；"
                    "若产生端确认是同一把，那是编码没对齐，也要先对齐）"
                    if _spki_key_kind(sign_spki) == _spki_key_kind(leaf_spki) else "")
        except Exception:                              # noqa: BLE001
            pass
        return 1, ("**链背书的那把密钥，不是产物签名用的那把** => "
                   f"叶子证书 spki={_spki_sha256(leaf_spki)[:16]}… "
                   f"publicKeyPem spki={_spki_sha256(sign_spki)[:16]}…"
                   "（这正是「拿真链 + 自己的签名公钥」那种拼装）" + said), "\n".join(detail)
    detail.append(f"      公钥绑定：叶子证书的 SPKI == publicKeyPem 的 SPKI"
                  f"（{_spki_sha256(leaf_spki)[:16]}…）=> 链背书的就是签字那把")

    kd = parse_key_description(certs[leaf_idx]["exts"][ATTEST_OID])
    hw = kd["hardwareEnforced"]
    sw = kd["softwareEnforced"]
    detail.append(f"      attestationSecurityLevel = {kd['attestationSecurityLevel']}"
                  f"   keymasterSecurityLevel = {kd['keymasterSecurityLevel']}"
                  f"   attestationVersion = {kd['attestationVersion']}")
    detail.append("      hardwareEnforced（**只有这个列表是硬件说的**）："
                  + (", ".join(sorted(hw)) if hw else "**空**"))
    detail.append(f"      softwareEnforced（Android 说的，**不作为证据**）："
                  + (", ".join(sorted(sw)) if sw else "空"))
    #这把密钥要不要用户认证？（方案 C 2026-09-30）
    # **如实报告，不是失败判据**：没要求认证的产物是合法的（方案 C 之前全是那样），
    #    而"要当证据用就该要求它"这件事由接收方按用途定 —— 与"链缺席不算失败"同一处理。
    detail.append("      " + describe_user_auth(sw, hw))

    #App 身份（tag 709）：这条链是不是【这个 App】的
    # 少了它，这一层只回答"某台设备的安全硬件为某把密钥背书"，不回答"这份产物出自 NK"。
    app_ok, app_why = check_app_identity(kd)
    detail.append("      " + app_why)
    if not app_ok:
        return 1, app_why, "\n".join(detail)

    #只读 hardwareEnforced —— **不回退**到 softwareEnforced
    rot = hw.get("rootOfTrust")
    if not isinstance(rot, dict):
        extra = "（但 softwareEnforced 里有 —— 这正是「合并列表」能骗过的那种链，不要读它）" \
            if "rootOfTrust" in sw else ""
        return 1, f"`rootOfTrust` **不在 hardwareEnforced 里** => 硬件没有为它背书{extra}", \
            "\n".join(detail)
    detail.append(f"      rootOfTrust: verifiedBootState={rot.get('verifiedBootState', '?')}"
                  f"   deviceLocked={rot.get('deviceLocked', '?')}"
                  f"   verifiedBootKey={str(rot.get('verifiedBootKey', b'').hex())[:32]}…")
    # challenge 绑定（防"把别人的链搬到改过的产物里"）
    chal = kd["attestationChallenge"]
    if content_hash is None:
        detail.append("      challenge：**无法绑定** —— 这份文件没有可用的自身 hash")
    else:
        want = bytes.fromhex(content_hash)
        if chal == want:
            detail.append(f"      challenge：与产物自身 hash 的 **32 字节原始值**一致"
                          f"（{chal.hex()[:16]}…）")
        else:
            alt = hashlib.sha256(content_hash.encode("utf-8")).digest()
            hint = ("但它等于 **sha256(那个 64 字符 hex 串)** —— "
                    "说明产生端用的是**另一种约定**" if chal == alt else "")
            detail.append(f"      challenge：**对不上**（链里 {chal.hex()[:16]}… "
                          f"产物 {want.hex()[:16]}…）{hint}")
            return 1, "challenge 与产物自身 hash 对不上 => **这条链不是为这份产物签的**" \
                      "（把别人的链搬过来了）", "\n".join(detail)

    if kd["attestationSecurityLevel"] not in ("TrustedEnvironment", "StrongBox"):
        return 1, (f"attestationSecurityLevel = {kd['attestationSecurityLevel']}"
                   " => 声明不是在安全硬件里作出的"), "\n".join(detail)
    if rot.get("verifiedBootState") != "Verified":
        detail.append(f"      verifiedBootState = {rot.get('verifiedBootState')}"
                      "（这是**登记**不是拒绝 —— 是否要求 Verified 由接收方按用途定）")
    if rot.get("deviceLocked") is not True:
        detail.append(f"      deviceLocked = {rot.get('deviceLocked')}"
                      "（同上：登记，不拒绝）")
    return 0, "ok", "\n".join(detail)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    allok = True
    chain_ids = {}
    for a in argv[1:]:
        p = Path(a)
        if not p.exists():
            print(f"找不到 {p}")
            allok = False
            continue
        #输入可以是 .zip（本 App 的导出就是证据包 zip）——
        #    从里面取那份 .jsonl 再验；不落地临时文件（正文直接传下去）
        text = None
        if p.suffix.lower() == ".zip":
            try:
                with zipfile.ZipFile(p) as z:
                    names = [n for n in z.namelist() if n.endswith(".jsonl")]
                    if not names:
                        print(f"{p.name} 是个 zip，但里面没有 .jsonl（它是不是证据包？）")
                        allok = False
                        continue
                    member = names[0]
                    text = z.read(member).decode("utf-8")
                    print(f"   （证据包 zip：{p.name} -> 取出 {member}）")
            except zipfile.BadZipFile:
                print(f"{p.name} 不是合法的 zip")
                allok = False
                continue
        allok = verify(p, text) and allok
        meta, _, _tail2, _ = load(p, text)
        cid = pick(meta, "chainId")
        if cid is not None:
            chain_ids[p.name] = cid
    # 多份文件一起核对时：比 chainId（这是跨文件唯一正确的判据）
    if len(chain_ids) > 1:
        print("=" * 74)
        print("多份文件的链身份对比（判据 = chainId）：")
        for name, cid in chain_ids.items():
            print(f"   {name}  => chainId {cid}")
        uniq = set(chain_ids.values())
        print(f"   => {'它们是【同一条链】的不同批次' if len(uniq) == 1 else '它们是【不同段的链】—— 不要拼成一条看'}")
    return 0 if allok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
