package com.opautocloud.claim

import java.io.File
import java.util.zip.ZipFile

/**
 * 极简 DEX 解析器：按「方法签名」或「字符串引用」定位宿主里的方法，**不依赖混淆名**。
 *
 * 为什么需要它：云服务每版都会重新混淆（11.3.5 的 `wo.c` / `yl.d` 在 11.4.5 里
 * 全部消失），硬编码混淆名等于每个版本都要改一次代码，改一次坏一次。
 * 而**方法签名**和**字符串常量**是稳定的：
 *  - 福利中心 URL 构造函数一定接收 `WebUrlConstants$UrlType` 枚举并返回 String；
 *  - 打开网页的方法一定接收 (Activity, String)；
 *  - 服务端运营位配置的 key（"keHuDuanFuLiZhongXinRuKouText"）是服务端约定的常量。
 *
 * 模块运行在宿主进程内，可以直接读宿主自己的 APK（applicationInfo.sourceDir），
 * 解析出候选方法后缓存到 SharedPreferences——每个版本只需解析一次。
 */
object DexScan {

    /** 一个被定位到的宿主方法。 */
    data class MethodRef(
        val className: String,     // 点号形式，如 com.heytap.cloud.Foo
        val methodName: String,
        val isStatic: Boolean,
        val params: List<String>,  // 类型描述符，如 android.app.Activity
        val ret: String
    )

    /** 遍历 APK 里的每个 classes*.dex。 */
    private inline fun forEachDex(apkPath: String, action: (Dex) -> Unit) {
        var zf: ZipFile? = null
        try {
            zf = ZipFile(File(apkPath))
            val entries = zf.entries().toList()
                .filter { it.name.matches(Regex("classes(\\d+)?\\.dex")) }
                .sortedBy { it.name }
            for (e in entries) {
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                try {
                    action(Dex(bytes))
                } catch (t: Throwable) {
                    // 单个 dex 解析失败不影响其它
                }
            }
        } catch (t: Throwable) {
            // APK 读不到就当没发现
        } finally {
            runCatching { zf?.close() }
        }
    }

    /** 找出所有「参数与返回值符合条件」的方法。 */
    fun findMethods(
        apkPath: String,
        predicate: (params: List<String>, ret: String) -> Boolean
    ): List<MethodRef> {
        val out = ArrayList<MethodRef>()
        forEachDex(apkPath) { d ->
            for (i in 0 until d.methodIdsSize) {
                val (classIdx, protoIdx, nameIdx, accessFlags) = d.methodId(i)
                val (ret, params) = d.proto(protoIdx)
                if (!predicate(params, ret)) continue
                out.add(
                    MethodRef(
                        className = d.typeDescriptor(classIdx).toClassName(),
                        methodName = d.string(nameIdx),
                        isStatic = (accessFlags and 0x0008) != 0,
                        params = params.map { it.toClassName() },
                        ret = ret.toClassName()
                    )
                )
            }
        }
        return out
    }

    /** 找出所有引用了指定字符串常量（出现在 const-string 指令里）的方法。 */
    fun findMethodsReferencingString(apkPath: String, needle: String): List<MethodRef> {
        val out = ArrayList<MethodRef>()
        forEachDex(apkPath) { d ->
            val target = d.findStringIndex(needle) ?: return@forEachDex
            out.addAll(d.methodsReferencingString(target))
        }
        return out
    }

    /** 找出类名里含指定子串的类（点号形式）。 */
    fun findClasses(apkPath: String, needle: String): List<String> {
        val out = LinkedHashSet<String>()
        forEachDex(apkPath) { d ->
            for (i in 0 until d.typeIdsSize) {
                val desc = d.typeDescriptor(i)
                if (desc.contains(needle)) out.add(desc.toClassName())
            }
        }
        return out.toList()
    }

    /** APK 路径：宿主自己的安装包。 */
    fun hostApkPath(sourceDir: String?): String? =
        sourceDir?.takeIf { it.isNotBlank() && File(it).exists() }

    // ------------------------------------------------------------ 描述符转换

    /** `Lcom/foo/Bar;` -> `com.foo.Bar` */
    private fun String.toClassName(): String {
        if (!startsWith("L") || !endsWith(";")) return this
        return substring(1, length - 1).replace('/', '.')
    }

    // ------------------------------------------------------------ DEX 解析

    private class Dex(val b: ByteArray) {
        val stringIdsSize = i32(0x38)
        val stringIdsOff = i32(0x3C)
        val typeIdsSize = i32(0x40)
        val typeIdsOff = i32(0x44)
        val protoIdsSize = i32(0x48)
        val protoIdsOff = i32(0x4C)
        val methodIdsSize = i32(0x58)
        val methodIdsOff = i32(0x5C)
        val classDefsSize = i32(0x60)
        val classDefsOff = i32(0x64)

        private val strCache = HashMap<Int, String>()
        private val typeCache = HashMap<Int, String>()

        private fun u16(off: Int): Int = (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun i32(off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun u32(off: Int): Int = i32(off)

        /** uleb128 解码，返回值与结束偏移。 */
        private fun uleb(off: Int): Pair<Int, Int> {
            var result = 0
            var shift = 0
            var o = off
            while (true) {
                val cur = b[o].toInt() and 0xFF
                o++
                result = result or ((cur and 0x7F) shl shift)
                if (cur and 0x80 == 0) return result to o
                shift += 7
            }
        }

        fun string(idx: Int): String {
            strCache[idx]?.let { return it }
            val off = u32(stringIdsOff + 4 * idx)
            val (_, dataOff) = uleb(off)
            var end = dataOff
            while (end < b.size && b[end].toInt() != 0) end++
            val s = String(b, dataOff, end - dataOff, Charsets.UTF_8)
            strCache[idx] = s
            return s
        }

        fun typeDescriptor(idx: Int): String {
            typeCache[idx]?.let { return it }
            val strIdx = u32(typeIdsOff + 4 * idx)
            val s = string(strIdx)
            typeCache[idx] = s
            return s
        }

        /** proto_id_item -> (返回类型描述符, 参数类型描述符列表) */
        fun proto(idx: Int): Pair<String, List<String>> {
            val off = protoIdsOff + 12 * idx
            val retIdx = u32(off + 4)
            val paramsOff = u32(off + 8)
            val params = ArrayList<String>()
            if (paramsOff != 0) {
                val size = u32(paramsOff)
                for (i in 0 until size) {
                    params.add(typeDescriptor(u16(paramsOff + 4 + 2 * i)))
                }
            }
            return typeDescriptor(retIdx) to params
        }

        /** method_id_item -> (class_type_idx, proto_idx, name_idx, access_flags) */
        fun methodId(idx: Int): Quad {
            val off = methodIdsOff + 8 * idx
            return Quad(u16(off), u16(off + 2), u32(off + 4), u16(off + 6))
        }

        data class Quad(
            val classIdx: Int,
            val protoIdx: Int,
            val nameIdx: Int,
            val accessFlags: Int
        )

        /** 找字符串常量下标（没有则 null）。 */
        fun findStringIndex(needle: String): Int? {
            for (i in 0 until stringIdsSize) {
                if (string(i) == needle) return i
            }
            return null
        }

        /**
         * 遍历 class_def -> class_data -> 带 code 的方法，
         * 找出 code 里用 const-string 引用了 target 字符串下标的方法。
         */
        fun methodsReferencingString(target: Int): List<MethodRef> {
            val out = ArrayList<MethodRef>()
            for (i in 0 until classDefsSize) {
                val cdOff = classDefsOff + 32 * i
                val classIdx = u32(cdOff)
                val classDataOff = u32(cdOff + 24)
                if (classDataOff == 0) continue
                val className = typeDescriptor(classIdx).toClassName()
                var o = classDataOff
                val (staticFieldsSize, o1) = uleb(o); o = o1
                val (instanceFieldsSize, o2) = uleb(o); o = o2
                val (directMethodsSize, o3) = uleb(o); o = o3
                val (virtualMethodsSize, o4) = uleb(o); o = o4
                // 字段：跳过（encoded_field: field_idx_diff, access_flags）
                repeat(staticFieldsSize + instanceFieldsSize) {
                    val (_, no) = uleb(o); o = no
                    val (_, no2) = uleb(o); o = no2
                }
                var methodIdxAcc = 0
                repeat(directMethodsSize + virtualMethodsSize) { mi ->
                    val (diff, no) = uleb(o); o = no
                    methodIdxAcc = if (mi < directMethodsSize) diff else methodIdxAcc + diff
                    val (accessFlags, no2) = uleb(o); o = no2
                    val (codeOff, no3) = uleb(o); o = no3
                    if (codeOff == 0) return@repeat
                    if (!codeReferencesString(codeOff, target)) return@repeat
                    val m = methodId(methodIdxAcc)
                    val (ret, params) = proto(m.protoIdx)
                    out.add(
                        MethodRef(
                            className = className,
                            methodName = string(m.nameIdx),
                            isStatic = (accessFlags and 0x0008) != 0,
                            params = params.map { it.toClassName() },
                            ret = ret.toClassName()
                        )
                    )
                }
            }
            return out
        }

        /**
         * 扫 code_item 的 insns，看是否用 const-string / const-string/jumbo
         * 引用了目标字符串下标。
         */
        private fun codeReferencesString(codeOff: Int, target: Int): Boolean {
            // code_item: registers_size(u2) ins_size(u2) outs_size(u2) tries_size(u2)
            //            debug_info_off(u4) insns_size(u4) insns(u2[])
            val insnsOff = codeOff + 16
            val insnsSize = u32(codeOff + 12)
            var i = 0
            while (i < insnsSize) {
                val op = u16(insnsOff + 2 * i)
                when (op) {
                    0x1a -> { // const-string vAA, string@BBBB
                        if (i + 1 < insnsSize && u16(insnsOff + 2 * (i + 1)) == target) return true
                        i += 2
                    }
                    0x1b -> { // const-string/jumbo vAA, string@BBBBBBBB
                        if (i + 2 < insnsSize && u32(insnsOff + 2 * (i + 1)) == target) return true
                        i += 3
                    }
                    else -> i += 1
                }
            }
            return false
        }
    }
}
