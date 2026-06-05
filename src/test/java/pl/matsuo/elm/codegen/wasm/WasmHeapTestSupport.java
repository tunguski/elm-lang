package pl.matsuo.elm.codegen.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * Shared harness for the WASM-backend test suites: compiles a module to wasm, runs its exported
 * `main` under Node and compares against the Truffle interpreter (runMain/agrees/runMainProject/
 * runMainAndPages/decodeKinds/decodeList/runMainString/agreesFloat). Skipped when Node is
 * unavailable ({@link #NODE}); the {@code *Support} name keeps Surefire from running it.
 */
class WasmHeapTestSupport {
  protected static final boolean NODE = nodeAvailable();

  /** Compiles a module to wasm, runs its exported `main`, and returns the i64 result as a string. */
  protected String runMain(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-heap-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runmain-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "process.stdout.write(r.instance.exports.main().toString());"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out;
  }

  protected void agrees(String source) throws Exception {
    assumeTrue(NODE, "node not available");
    String expected = Show.plain(Interpreter.load(source).value("main"));
    assertEquals(expected, runMain(source), source);
  }

  /** Compiles a multi-module project to one wasm binary, runs `main`, and returns the i64 result. */
  protected String runMainProject(java.util.List<String> sources) throws Exception {
    Path wasm = Files.createTempFile("elm-proj-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSources(sources));
    Path js = Files.createTempFile("elm-proj-run-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "process.stdout.write(r.instance.exports.main().toString());"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(30, TimeUnit.SECONDS);
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out;
  }


  /** Runs `main` (an Int result), returning {result, finalMemoryPages}. */
  protected long[] runMainAndPages(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-mem-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runmem-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const v=ex.main();"
            + "process.stdout.write(v.toString()+','+(ex.memory.buffer.byteLength/65536));"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    String[] parts = out.trim().split(",");
    return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
  }


  /** Compiles a multi-export module and decodes each f{i}() per the matching kind (as the page does). */
  protected String[] decodeKinds(String source, String[] kinds) throws Exception {
    Path wasm = Files.createTempFile("elm-multi-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-multi-run-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs'); const kinds="
            + "[" + String.join(",", java.util.Arrays.stream(kinds).map(k -> "\"" + k + "\"").toList()) + "];"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const fb=new ArrayBuffer(8), fdv=new DataView(fb);"
            + "function decode(kind,raw){"
            + " if(kind==='float'){fdv.setBigInt64(0,raw,true);return String(fdv.getFloat64(0,true));}"
            + " const dv=new DataView(ex.memory.buffer);"
            + " if(kind==='string'){const p=Number(raw),len=Number(dv.getBigInt64(p,true));"
            + "  return new TextDecoder().decode(new Uint8Array(ex.memory.buffer,p+8,len));}"
            + " if(kind==='list'){let p=Number(raw);const out=[];"
            + "  while(p!==0){out.push(Number(dv.getBigInt64(p,true)).toString());p=Number(dv.getBigInt64(p+8,true));}"
            + "  return '['+out.join(',')+']';}"
            + " return raw.toString();}"
            + "const res=kinds.map((k,i)=>decode(k,ex['f'+i]()));"
            + "process.stdout.write(res.join('\\u0001'));"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out.split("\u0001", -1);
  }


  /** Runs `main` (a cons-list), walking the heap from the host to recover the elements as JSON. */
  protected String decodeList(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-list-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runlist-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const dv=new DataView(ex.memory.buffer);"
            + "let p=Number(ex.main()); const out=[];"
            + "while(p!==0){ out.push(Number(dv.getBigInt64(p,true))); p=Number(dv.getBigInt64(p+8,true)); }"
            + "process.stdout.write(JSON.stringify(out));"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out;
  }

  /** Runs `main`, treating its i64 result as a pointer to a heap string, and decodes the bytes. */
  protected String runMainString(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-str-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runstr-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const ptr=Number(ex.main());"
            + "const dv=new DataView(ex.memory.buffer);"
            + "const len=Number(dv.getBigInt64(ptr,true));"
            + "const bytes=new Uint8Array(ex.memory.buffer, ptr+8, len);"
            + "process.stdout.write(Buffer.from(bytes).toString('utf8'));"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out;
  }

  /** Runs `main` whose result is a Float, reinterpreting the returned i64 bit pattern to a double. */
  protected void agreesFloat(String source) throws Exception {
    assumeTrue(NODE, "node not available");
    double expected = ((Number) Interpreter.load(source).value("main")).doubleValue();
    double actual = Double.longBitsToDouble(Long.parseLong(runMain(source).trim()));
    assertEquals(expected, actual, 1e-9, source);
  }


  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

}
