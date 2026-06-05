package pl.matsuo.elm.codegen.wasm;

import static pl.matsuo.elm.codegen.wasm.WasmCompiler.I32;
import static pl.matsuo.elm.codegen.wasm.WasmCompiler.I64;
import static pl.matsuo.elm.codegen.wasm.WasmCompiler.entry;
import static pl.matsuo.elm.codegen.wasm.WasmCompiler.leb;
import static pl.matsuo.elm.codegen.wasm.WasmCompiler.sleb;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import pl.matsuo.elm.codegen.wasm.WasmCompiler.Native;

/**
 * Hand-assembled native runtime functions for the linear-memory WASM backend: the string operations
 * ($strEq/$strConcat/$strLeft/$strReverse/$strToList/...), plus $apply (closure dispatch) and record
 * get/set. Raw-bytecode emitters depending only on WasmCompiler's encoding/DSL helpers. Extracted to
 * keep the FunctionGen codegen engine focused.
 */
final class WasmNativeFns {

  private WasmNativeFns() {}

  static List<Native> stringRuntime() {
    return List.of(
        new Native("$strEq", 2, strEqEntry()),
        new Native("$strConcat", 2, strConcatEntry()),
        new Native("$strLeft", 2, strLeftEntry()),
        new Native("$strDropLeft", 2, strDropLeftEntry()),
        new Native("$strReverse", 2, strReverseEntry()),
        new Native("$strUpper", 2, strCaseEntry(97, 122, false)),
        new Native("$strLower", 2, strCaseEntry(65, 90, true)),
        new Native("$strFromChar", 2, strFromCharEntry()),
        new Native("$strToList", 2, strToListEntry()));
  }

  /** {@code $strToList(s, _) -> i64}: a cons-list of {@code s}'s bytes as char codes (built back to
   *  front so the list reads left to right). Byte-based; second argument ignored. */
  static byte[] strToListEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // s=0, _=1; i64 lenStr=2, result=3; i32 i=4, cell=5, delta=6
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2); // lenStr
    b.write(0x42);
    sleb(b, 0);
    lset(b, 3); // result = 0 (Nil)
    lget(b, 2);
    b.write(0xA7);
    i32c(b, 1);
    b.write(0x6B);
    lset(b, 4); // i = (i32)lenStr - 1
    b.write(0x02);
    b.write(0x40); // block
    b.write(0x03);
    b.write(0x40); // loop
    lget(b, 4);
    i32c(b, 0);
    b.write(0x48); // i32.lt_s (i < 0)
    b.write(0x0D);
    leb(b, 1); // br_if 1
    b.write(0x23);
    leb(b, 0);
    lset(b, 5); // cell = $hp
    lget(b, 5);
    i32c(b, 16);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = cell + 16
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76);
    b.write(0x3F);
    b.write(0x00);
    b.write(0x6B);
    lset(b, 6);
    lget(b, 6);
    i32c(b, 0);
    b.write(0x4A);
    b.write(0x04);
    b.write(0x40);
    lget(b, 6);
    b.write(0x40);
    b.write(0x00);
    b.write(0x1A);
    b.write(0x0B);
    // cell.head = (i64) load8(s + 8 + i)
    lget(b, 5);
    lget(b, 0);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 4);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0);
    b.write(0xAD); // i64.extend_i32_u
    b.write(0x37);
    leb(b, 3);
    leb(b, 0); // i64.store cell+0
    // cell.tail = result
    lget(b, 5);
    lget(b, 3);
    b.write(0x37);
    leb(b, 3);
    leb(b, 8); // i64.store cell+8
    // result = (i64) cell
    lget(b, 5);
    b.write(0xAD);
    lset(b, 3);
    // i = i - 1
    lget(b, 4);
    i32c(b, 1);
    b.write(0x6B);
    lset(b, 4);
    b.write(0x0C);
    leb(b, 0); // br 0
    b.write(0x0B);
    b.write(0x0B); // end loop, end block
    lget(b, 3);
    return entry(b, new int[][] {{2, I64}, {3, I32}});
  }

  /** {@code $strFromChar(c, _) -> i64}: a one-byte heap string holding {@code c}'s low byte (ASCII;
   *  consistent with this backend's byte string model). Second argument ignored. */
  static byte[] strFromCharEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // param c=0; i32 result=2, delta=3
    b.write(0x23);
    leb(b, 0);
    lset(b, 2); // result = $hp
    lget(b, 2);
    i32c(b, 9);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + 9
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76);
    b.write(0x3F);
    b.write(0x00);
    b.write(0x6B);
    lset(b, 3);
    lget(b, 3);
    i32c(b, 0);
    b.write(0x4A);
    b.write(0x04);
    b.write(0x40);
    lget(b, 3);
    b.write(0x40);
    b.write(0x00);
    b.write(0x1A);
    b.write(0x0B);
    // result.length = 1
    lget(b, 2);
    b.write(0x42);
    sleb(b, 1);
    b.write(0x37);
    leb(b, 3);
    leb(b, 0);
    // store8(result + 8, c & 0xFF)
    lget(b, 2);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 0);
    b.write(0xA7);
    i32c(b, 255);
    b.write(0x71);
    b.write(0x3A);
    leb(b, 0);
    leb(b, 0);
    lget(b, 2);
    b.write(0xAD);
    return entry(b, new int[][] {{2, I32}});
  }

  /** {@code $strUpper/$strLower(str, _) -> i64}: a fresh string with each ASCII letter case-folded.
   *  A byte in {@code [lo, hi]} is shifted by 32 ({@code add} = toward lowercase); others are copied.
   *  Byte-based (ASCII only), matching this backend's string model; second arg ignored. */
  static byte[] strCaseEntry(int lo, int hi, boolean add) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // param str=0; i64 lenStr=2; i32 result=3, total=4, delta=5, i=6, byte=7
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2);
    b.write(0x23);
    leb(b, 0);
    lset(b, 3); // result = $hp
    i32c(b, 8);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x6A);
    lset(b, 4);
    lget(b, 3);
    lget(b, 4);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + total
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76);
    b.write(0x3F);
    b.write(0x00);
    b.write(0x6B);
    lset(b, 5);
    lget(b, 5);
    i32c(b, 0);
    b.write(0x4A);
    b.write(0x04);
    b.write(0x40);
    lget(b, 5);
    b.write(0x40);
    b.write(0x00);
    b.write(0x1A);
    b.write(0x0B);
    lget(b, 3);
    lget(b, 2);
    b.write(0x37);
    leb(b, 3);
    leb(b, 0); // result.length = lenStr
    // loop: result[8+i] = caseFold(str[8+i])
    i32c(b, 0);
    lset(b, 6);
    b.write(0x02);
    b.write(0x40);
    b.write(0x03);
    b.write(0x40);
    lget(b, 6);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x4F);
    b.write(0x0D);
    leb(b, 1);
    // byte = load8(str + 8 + i)
    lget(b, 0);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 6);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0);
    lset(b, 7);
    // addr = result + 8 + i
    lget(b, 3);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 6);
    b.write(0x6A);
    // value = byte +/- ((byte >= lo & byte <= hi) * 32)
    lget(b, 7);
    lget(b, 7);
    i32c(b, lo);
    b.write(0x4F); // i32.ge_u
    lget(b, 7);
    i32c(b, hi);
    b.write(0x4D); // i32.le_u
    b.write(0x71); // i32.and
    i32c(b, 32);
    b.write(0x6C); // i32.mul -> delta
    b.write(add ? 0x6A : 0x6B); // i32.add / i32.sub
    b.write(0x3A);
    leb(b, 0);
    leb(b, 0); // store8(addr, value)
    lget(b, 6);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 6);
    b.write(0x0C);
    leb(b, 0);
    b.write(0x0B);
    b.write(0x0B);
    lget(b, 3);
    b.write(0xAD);
    return entry(b, new int[][] {{1, I64}, {5, I32}});
  }

  /** {@code $strReverse(str, _) -> i64}: a fresh heap string with {@code str}'s bytes reversed (the
   *  second argument is ignored — natives share the two-i64 calling convention). Byte-based. */
  static byte[] strReverseEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // param str=0 (1 ignored); i64 lenStr=2; i32 result=3, total=4, delta=5, i=6
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2); // lenStr
    b.write(0x23);
    leb(b, 0);
    lset(b, 3); // result = $hp
    i32c(b, 8);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x6A);
    lset(b, 4); // total = 8 + len
    lget(b, 3);
    lget(b, 4);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + total
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76);
    b.write(0x3F);
    b.write(0x00);
    b.write(0x6B);
    lset(b, 5);
    lget(b, 5);
    i32c(b, 0);
    b.write(0x4A);
    b.write(0x04);
    b.write(0x40);
    lget(b, 5);
    b.write(0x40);
    b.write(0x00);
    b.write(0x1A);
    b.write(0x0B);
    // result.length = lenStr
    lget(b, 3);
    lget(b, 2);
    b.write(0x37);
    leb(b, 3);
    leb(b, 0);
    // copy loop: result[8 + i] = str[8 + (len - 1 - i)]
    i32c(b, 0);
    lset(b, 6);
    b.write(0x02);
    b.write(0x40);
    b.write(0x03);
    b.write(0x40);
    lget(b, 6);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x4F);
    b.write(0x0D);
    leb(b, 1);
    // dest = result + 8 + i
    lget(b, 3);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 6);
    b.write(0x6A);
    // src = str + 8 + (len - 1 - i)
    lget(b, 0);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 2);
    b.write(0xA7);
    i32c(b, 1);
    b.write(0x6B);
    lget(b, 6);
    b.write(0x6B);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0);
    b.write(0x3A);
    leb(b, 0);
    leb(b, 0);
    lget(b, 6);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 6);
    b.write(0x0C);
    leb(b, 0);
    b.write(0x0B);
    b.write(0x0B);
    lget(b, 3);
    b.write(0xAD);
    return entry(b, new int[][] {{1, I64}, {4, I32}});
  }

  /** {@code $strDropLeft(n, str) -> i64}: a fresh heap string holding {@code str} with its first
   *  {@code clamp(0, len, n)} bytes removed (byte-based). */
  static byte[] strDropLeftEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // params n=0, str=1; i64 locals lenStr=2, start=3, count=4; i32 locals result=5, i=6, total=7, delta=8
    lget(b, 1);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2); // lenStr
    // start = min(n, lenStr)
    lget(b, 0);
    lget(b, 2);
    lget(b, 0);
    lget(b, 2);
    b.write(0x53);
    b.write(0x1B);
    lset(b, 3);
    // start = max(0, start)
    lget(b, 3);
    b.write(0x42);
    sleb(b, 0);
    lget(b, 3);
    b.write(0x42);
    sleb(b, 0);
    b.write(0x55);
    b.write(0x1B);
    lset(b, 3);
    // count = lenStr - start
    lget(b, 2);
    lget(b, 3);
    b.write(0x7D); // i64.sub
    lset(b, 4);
    b.write(0x23);
    leb(b, 0);
    lset(b, 5); // result = $hp
    i32c(b, 8);
    lget(b, 4);
    b.write(0xA7);
    b.write(0x6A);
    lset(b, 7); // total = 8 + count
    lget(b, 5);
    lget(b, 7);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + total
    // grow
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76);
    b.write(0x3F);
    b.write(0x00);
    b.write(0x6B);
    lset(b, 8);
    lget(b, 8);
    i32c(b, 0);
    b.write(0x4A);
    b.write(0x04);
    b.write(0x40);
    lget(b, 8);
    b.write(0x40);
    b.write(0x00);
    b.write(0x1A);
    b.write(0x0B);
    // result.length = count
    lget(b, 5);
    lget(b, 4);
    b.write(0x37);
    leb(b, 3);
    leb(b, 0);
    // copy loop: result[8 + i] = str[8 + start + i] for i in 0..count
    i32c(b, 0);
    lset(b, 6);
    b.write(0x02);
    b.write(0x40);
    b.write(0x03);
    b.write(0x40);
    lget(b, 6);
    lget(b, 4);
    b.write(0xA7);
    b.write(0x4F); // i >= count ?
    b.write(0x0D);
    leb(b, 1);
    // dest = result + 8 + i
    lget(b, 5);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 6);
    b.write(0x6A);
    // src byte = load8(str + 8 + start + i)
    lget(b, 1);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 3);
    b.write(0xA7);
    b.write(0x6A);
    lget(b, 6);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0);
    b.write(0x3A);
    leb(b, 0);
    leb(b, 0);
    lget(b, 6);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 6);
    b.write(0x0C);
    leb(b, 0);
    b.write(0x0B);
    b.write(0x0B);
    lget(b, 5);
    b.write(0xAD);
    return entry(b, new int[][] {{3, I64}, {4, I32}});
  }

  /** {@code $strLeft(n, str) -> i64}: a fresh heap string holding the first {@code clamp(0, len, n)}
   *  bytes of {@code str} (byte-based, matching this backend's byte-length string model). */
  static byte[] strLeftEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // params n=0, str=1 (i64); locals lenStr=2, count=3 (i64); result=4, i=5, total=6, delta=7 (i32)
    lget(b, 1);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2); // lenStr = i64.load(str)
    // count = min(n, lenStr)
    lget(b, 0);
    lget(b, 2);
    lget(b, 0);
    lget(b, 2);
    b.write(0x53); // i64.lt_s (n < lenStr)
    b.write(0x1B); // select
    lset(b, 3);
    // count = max(0, count)
    lget(b, 3);
    b.write(0x42);
    sleb(b, 0);
    lget(b, 3);
    b.write(0x42);
    sleb(b, 0);
    b.write(0x55); // i64.gt_s (count > 0)
    b.write(0x1B); // select
    lset(b, 3);
    b.write(0x23);
    leb(b, 0);
    lset(b, 4); // result = $hp
    i32c(b, 8);
    lget(b, 3);
    b.write(0xA7);
    b.write(0x6A);
    lset(b, 6); // total = 8 + (i32)count
    lget(b, 4);
    lget(b, 6);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + total
    // grow by enough pages
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76);
    b.write(0x3F);
    b.write(0x00);
    b.write(0x6B);
    lset(b, 7);
    lget(b, 7);
    i32c(b, 0);
    b.write(0x4A);
    b.write(0x04);
    b.write(0x40);
    lget(b, 7);
    b.write(0x40);
    b.write(0x00);
    b.write(0x1A);
    b.write(0x0B);
    // result.length = count
    lget(b, 4);
    lget(b, 3);
    b.write(0x37);
    leb(b, 3);
    leb(b, 0);
    copyLoop(b, 0, 1, 3, -1); // copy `count` bytes from str's data into result's data
    lget(b, 4);
    b.write(0xAD); // result as i64 pointer
    return entry(b, new int[][] {{2, I64}, {4, I32}});
  }

  static void nload64(ByteArrayOutputStream b, int off) {
    b.write(0x29);
    leb(b, 3);
    leb(b, off);
  }

  static void nstore64(ByteArrayOutputStream b, int off) {
    b.write(0x37);
    leb(b, 3);
    leb(b, off);
  }

  /**
   * {@code $apply(clo, arg) -> i64}: the closure runtime. A closure is a heap block {@code {funcIdx,
   * arity, count, slot…}}. Applying copies it with one more slot; once {@code count} reaches {@code
   * arity} the underlying function is invoked via {@code call_indirect} (dispatched on the arity over
   * the arities that exist), otherwise the larger closure is returned. {@code arityTypes} maps each
   * callable arity to its wasm function-type index.
   */
  static byte[] applyEntry(java.util.SortedMap<Integer, Integer> arityTypes) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // funcIdx/arity/count from the closure header.
    lget(b, 0); b.write(0xA7); nload64(b, 0); lset(b, 2);
    lget(b, 0); b.write(0xA7); nload64(b, 8); lset(b, 3);
    lget(b, 0); b.write(0xA7); nload64(b, 16); lset(b, 4);
    // newClo = $hp; bytes = (4 + (i32)count) * 8; $hp += bytes
    b.write(0x23); leb(b, 0); lset(b, 5);
    i32c(b, 4); lget(b, 4); b.write(0xA7); b.write(0x6A); i32c(b, 8); b.write(0x6C); lset(b, 7);
    lget(b, 5); lget(b, 7); b.write(0x6A); b.write(0x24); leb(b, 0);
    // grow memory if $hp passed capacity
    b.write(0x23); leb(b, 0); i32c(b, 65535); b.write(0x6A); i32c(b, 16); b.write(0x76);
    b.write(0x3F); b.write(0x00); b.write(0x6B); lset(b, 8);
    lget(b, 8); i32c(b, 0); b.write(0x4A); b.write(0x04); b.write(0x40);
    lget(b, 8); b.write(0x40); b.write(0x00); b.write(0x1A); b.write(0x0B);
    // header: funcIdx, arity, count+1
    lget(b, 5); lget(b, 2); nstore64(b, 0);
    lget(b, 5); lget(b, 3); nstore64(b, 8);
    lget(b, 5); lget(b, 4); b.write(0x42); sleb(b, 1); b.write(0x7C); nstore64(b, 16);
    // copy slots 0..count-1
    i32c(b, 0); lset(b, 6);
    b.write(0x02); b.write(0x40); b.write(0x03); b.write(0x40);
    lget(b, 6); lget(b, 4); b.write(0xA7); b.write(0x4F); b.write(0x0D); leb(b, 1);
    lget(b, 5); i32c(b, 24); b.write(0x6A); lget(b, 6); i32c(b, 8); b.write(0x6C); b.write(0x6A); // dest
    lget(b, 0); b.write(0xA7); i32c(b, 24); b.write(0x6A); lget(b, 6); i32c(b, 8); b.write(0x6C);
    b.write(0x6A); nload64(b, 0); // value from clo
    nstore64(b, 0);
    lget(b, 6); i32c(b, 1); b.write(0x6A); lset(b, 6);
    b.write(0x0C); leb(b, 0); b.write(0x0B); b.write(0x0B);
    // newClo slot[count] = arg
    lget(b, 5); i32c(b, 24); b.write(0x6A); lget(b, 4); b.write(0xA7); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    lget(b, 1); nstore64(b, 0);
    // if count+1 == arity: invoke; else return newClo
    lget(b, 4); b.write(0x42); sleb(b, 1); b.write(0x7C); lget(b, 3); b.write(0x51);
    b.write(0x04); b.write(0x7E); // if (result i64)
    emitDispatch(b, new ArrayList<>(arityTypes.entrySet()), 0);
    b.write(0x05); // else
    lget(b, 5); b.write(0xAD); // newClo as i64 pointer
    b.write(0x0B); // end if
    return entry(b, new int[][] {{3, I64}, {4, I32}});
  }

  /** Emits the arity-dispatch if/else chain inside {@code $apply}, invoking via call_indirect. */
  static void emitDispatch(
      ByteArrayOutputStream b, List<Map.Entry<Integer, Integer>> arities, int idx) {
    if (idx >= arities.size()) {
      b.write(0x00); // unreachable: a complete closure always has a known arity
      return;
    }
    int arity = arities.get(idx).getKey();
    int typeIdx = arities.get(idx).getValue();
    lget(b, 3); b.write(0x42); sleb(b, arity); b.write(0x51); // arity == a ?
    b.write(0x04); b.write(0x7E); // if (result i64)
    for (int k = 0; k < arity; k++) {
      lget(b, 5); i32c(b, 24 + 8 * k); b.write(0x6A); nload64(b, 0); // slot k
    }
    lget(b, 2); b.write(0xA7); // funcIdx as table index
    b.write(0x11); leb(b, typeIdx); leb(b, 0); // call_indirect
    b.write(0x05); // else
    emitDispatch(b, arities, idx + 1);
    b.write(0x0B); // end if
  }

  // A record is a self-describing heap block {count:i64, fieldId_0..fieldId_{n-1}, value_0..value_{n-1}}
  // with fields in name-sorted order. Storing the field-name ids lets access and update look fields
  // up by name at runtime, so a row-polymorphic function (which knows only some of a record's fields)
  // works without a closed type.

  /** {@code $recordGet(ptr, fieldId) -> i64}: the value of the named field (linear scan by id). */
  static byte[] recordGetEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals: count i64 (2); i (3), base (4) i32
    lget(b, 0); b.write(0xA7); lset(b, 4); // base = wrap(ptr)
    lget(b, 4); nload64(b, 0); lset(b, 2); // count
    i32c(b, 0); lset(b, 3); // i = 0
    b.write(0x02); b.write(0x40); b.write(0x03); b.write(0x40);
    lget(b, 3); lget(b, 2); b.write(0xA7); b.write(0x4F); b.write(0x0D); leb(b, 1); // i>=count -> exit
    // if load(base + 8 + 8i) == fieldId: return load(base + 8 + 8*count + 8i)
    lget(b, 4); i32c(b, 8); b.write(0x6A); lget(b, 3); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    nload64(b, 0);
    lget(b, 1); b.write(0x51); // == fieldId
    b.write(0x04); b.write(0x40); // if (void)
    lget(b, 4); i32c(b, 8); b.write(0x6A); lget(b, 2); b.write(0xA7); i32c(b, 8); b.write(0x6C);
    b.write(0x6A); lget(b, 3); i32c(b, 8); b.write(0x6C); b.write(0x6A); // base + 8 + 8*count + 8i
    nload64(b, 0);
    b.write(0x0F); // return value
    b.write(0x0B); // end if
    lget(b, 3); i32c(b, 1); b.write(0x6A); lset(b, 3); // i++
    b.write(0x0C); leb(b, 0); b.write(0x0B); b.write(0x0B); // br 0; end loop; end block
    b.write(0x42); sleb(b, 0); // unreachable in well-typed code; yield 0
    return entry(b, new int[][] {{1, I64}, {2, I32}});
  }

  /** {@code $recordSet(ptr, fieldId, val) -> i64}: a copy of the record with one field replaced. */
  static byte[] recordSetEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals: count i64 (3); i (4), src (5), dst (6), bytes (7), delta (8) i32
    lget(b, 0); b.write(0xA7); lset(b, 5); // src = wrap(ptr)
    lget(b, 5); nload64(b, 0); lset(b, 3); // count
    b.write(0x23); leb(b, 0); lset(b, 6); // dst = $hp
    // bytes = (1 + 2*count) * 8
    i32c(b, 1); lget(b, 3); b.write(0xA7); i32c(b, 2); b.write(0x6C); b.write(0x6A); i32c(b, 8); b.write(0x6C); lset(b, 7);
    lget(b, 6); lget(b, 7); b.write(0x6A); b.write(0x24); leb(b, 0); // $hp += bytes
    // grow
    b.write(0x23); leb(b, 0); i32c(b, 65535); b.write(0x6A); i32c(b, 16); b.write(0x76);
    b.write(0x3F); b.write(0x00); b.write(0x6B); lset(b, 8);
    lget(b, 8); i32c(b, 0); b.write(0x4A); b.write(0x04); b.write(0x40);
    lget(b, 8); b.write(0x40); b.write(0x00); b.write(0x1A); b.write(0x0B);
    lget(b, 6); lget(b, 3); nstore64(b, 0); // dst[0] = count
    i32c(b, 0); lset(b, 4); // i = 0
    b.write(0x02); b.write(0x40); b.write(0x03); b.write(0x40);
    lget(b, 4); lget(b, 3); b.write(0xA7); b.write(0x4F); b.write(0x0D); leb(b, 1); // i>=count -> exit
    // copy id: dst[8+8i] = src[8+8i]
    lget(b, 6); i32c(b, 8); b.write(0x6A); lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); // dst id addr
    lget(b, 5); i32c(b, 8); b.write(0x6A); lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); nload64(b, 0); // src id
    nstore64(b, 0);
    // dst value addr = dst + 8 + 8*count + 8i
    lget(b, 6); i32c(b, 8); b.write(0x6A); lget(b, 3); b.write(0xA7); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    // value: if src id_i == fieldId then val else src value_i
    lget(b, 5); i32c(b, 8); b.write(0x6A); lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); nload64(b, 0); // src id_i
    lget(b, 1); b.write(0x51); // == fieldId
    b.write(0x04); b.write(0x7E); // if (result i64)
    lget(b, 2); // val
    b.write(0x05); // else
    lget(b, 5); i32c(b, 8); b.write(0x6A); lget(b, 3); b.write(0xA7); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); nload64(b, 0); // src value_i
    b.write(0x0B); // end if
    nstore64(b, 0); // store the chosen value at dst value addr
    lget(b, 4); i32c(b, 1); b.write(0x6A); lset(b, 4); // i++
    b.write(0x0C); leb(b, 0); b.write(0x0B); b.write(0x0B);
    lget(b, 6); b.write(0xAD); // return dst as i64 pointer
    return entry(b, new int[][] {{1, I64}, {5, I32}});
  }

  static void lget(ByteArrayOutputStream b, int i) {
    b.write(0x20);
    leb(b, i);
  }

  static void lset(ByteArrayOutputStream b, int i) {
    b.write(0x21);
    leb(b, i);
  }

  static void i32c(ByteArrayOutputStream b, int v) {
    b.write(0x41);
    sleb(b, v);
  }

  /** {@code $strEq(a, b) -> i64}: 1 if the two strings have equal length and bytes, else 0. */
  static byte[] strEqEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals (after params a=0, b=1): lenA=2 (i64), i=3, baseA=4, baseB=5 (i32)
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0); // lenA = i64.load(a)
    lset(b, 2);
    lget(b, 2);
    lget(b, 1);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0); // i64.load(b)
    b.write(0x52); // i64.ne
    b.write(0x04);
    b.write(0x40); // if (void) -> lengths differ
    b.write(0x42);
    sleb(b, 0);
    b.write(0x0F); // return 0
    b.write(0x0B); // end if
    lget(b, 0);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lset(b, 4); // baseA = wrap(a) + 8
    lget(b, 1);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lset(b, 5); // baseB = wrap(b) + 8
    i32c(b, 0);
    lset(b, 3); // i = 0
    b.write(0x02);
    b.write(0x40); // block (void)
    b.write(0x03);
    b.write(0x40); // loop (void)
    lget(b, 3);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x4F); // i >= (i32)lenA ?
    b.write(0x0D);
    leb(b, 1); // br_if 1 -> exit block (all matched)
    lget(b, 4);
    lget(b, 3);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0); // load8(baseA + i)
    lget(b, 5);
    lget(b, 3);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0); // load8(baseB + i)
    b.write(0x47); // i32.ne
    b.write(0x04);
    b.write(0x40); // if bytes differ
    b.write(0x42);
    sleb(b, 0);
    b.write(0x0F); // return 0
    b.write(0x0B);
    lget(b, 3);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 3); // i++
    b.write(0x0C);
    leb(b, 0); // br 0 -> loop
    b.write(0x0B); // end loop
    b.write(0x0B); // end block
    b.write(0x42);
    sleb(b, 1); // result: 1 (equal)
    return entry(b, new int[][] {{1, I64}, {3, I32}});
  }

  /** {@code $strConcat(a, b) -> i64}: a fresh heap string holding a's bytes followed by b's. */
  static byte[] strConcatEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals: lenA=2, lenB=3 (i64); result=4, i=5, total=6, delta=7 (i32)
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2); // lenA
    lget(b, 1);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 3); // lenB
    b.write(0x23);
    leb(b, 0);
    lset(b, 4); // result = $hp
    i32c(b, 8);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x6A);
    lget(b, 3);
    b.write(0xA7);
    b.write(0x6A);
    lset(b, 6); // total = 8 + lenA + lenB
    lget(b, 4);
    lget(b, 6);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + total
    // grow: delta = ceilPages($hp) - memory.size; if delta > 0 memory.grow(delta)
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76); // ($hp + 65535) >> 16  (i32.shr_u)
    b.write(0x3F);
    b.write(0x00); // memory.size
    b.write(0x6B); // i32.sub
    lset(b, 7);
    lget(b, 7);
    i32c(b, 0);
    b.write(0x4A); // delta > 0 ? (i32.gt_s)
    b.write(0x04);
    b.write(0x40);
    lget(b, 7);
    b.write(0x40);
    b.write(0x00); // memory.grow(delta)
    b.write(0x1A); // drop
    b.write(0x0B);
    // result.length = lenA + lenB
    lget(b, 4);
    lget(b, 2);
    lget(b, 3);
    b.write(0x7C); // i64.add
    b.write(0x37);
    leb(b, 3);
    leb(b, 0); // i64.store(result, 0)
    copyLoop(b, /*destBaseExtra*/ 0, /*srcParam*/ 0, /*lenLocal*/ 2, /*destLenOffsetLocal*/ -1);
    copyLoop(b, 0, 1, 3, 2); // B copied after A's lenA bytes
    lget(b, 4);
    b.write(0xAD); // result as i64 pointer
    return entry(b, new int[][] {{2, I64}, {4, I32}});
  }

  /**
   * Emits a byte-copy loop into {@code $strConcat}'s body: copies {@code lenLocal} bytes from string
   * {@code srcParam}'s data into {@code result}'s data, offset by the length in {@code
   * destLenOffsetLocal} (or 0 when that is negative). Uses loop counter local 5.
   */
  static void copyLoop(
      ByteArrayOutputStream b, int unused, int srcParam, int lenLocal, int destLenOffsetLocal) {
    i32c(b, 0);
    lset(b, 5); // i = 0
    b.write(0x02);
    b.write(0x40); // block
    b.write(0x03);
    b.write(0x40); // loop
    lget(b, 5);
    lget(b, lenLocal);
    b.write(0xA7);
    b.write(0x4F); // i >= (i32)len ?
    b.write(0x0D);
    leb(b, 1); // br_if 1
    // dest = result + 8 + [destLenOffset] + i
    lget(b, 4);
    i32c(b, 8);
    b.write(0x6A);
    if (destLenOffsetLocal >= 0) {
      lget(b, destLenOffsetLocal);
      b.write(0xA7);
      b.write(0x6A);
    }
    lget(b, 5);
    b.write(0x6A);
    // src = wrap(srcParam) + 8 + i ; then load8
    lget(b, srcParam);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 5);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0); // load8(src)
    b.write(0x3A);
    leb(b, 0);
    leb(b, 0); // store8(dest, byte)
    lget(b, 5);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 5); // i++
    b.write(0x0C);
    leb(b, 0); // br 0
    b.write(0x0B);
    b.write(0x0B); // end loop, end block
  }

  /** Wraps a pre-assembled function body in a code entry: locals declaration + body + end, size-led. */
}
